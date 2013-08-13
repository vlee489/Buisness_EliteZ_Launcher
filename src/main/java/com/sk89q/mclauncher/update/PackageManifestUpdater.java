/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010, 2011 Albert Pham <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.mclauncher.update;

import java.awt.Window;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EventObject;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.event.EventListenerList;

import com.sk89q.mclauncher.Launcher;
import com.sk89q.mclauncher.SelectComponentsDialog;
import com.sk89q.mclauncher.event.DownloadListener;
import com.sk89q.mclauncher.event.DownloadProgressEvent;
import com.sk89q.mclauncher.event.ProgressListener;
import com.sk89q.mclauncher.event.StatusChangeEvent;
import com.sk89q.mclauncher.event.TitleChangeEvent;
import com.sk89q.mclauncher.event.ValueChangeEvent;
import com.sk89q.mclauncher.model.Component;
import com.sk89q.mclauncher.model.FileGroup;
import com.sk89q.mclauncher.model.Message;
import com.sk89q.mclauncher.model.PackageFile;
import com.sk89q.mclauncher.model.PackageManifest;
import com.sk89q.mclauncher.util.Downloader;
import com.sk89q.mclauncher.util.LauncherUtils;
import com.sk89q.mclauncher.util.URLConnectionDownloader;
import com.sk89q.mclauncher.util.XmlUtils;

/**
 * Downloads and applies an update using {@link PackageManifest}.
 */
public class PackageManifestUpdater implements Updater, DownloadListener {
    
    private static final Logger logger = Logger.getLogger(
            PackageManifestUpdater.class.getCanonicalName());

    private final URL baseUrl;
    private final PackageManifest manifest;
    private final File rootDir;
    private final UpdateCache cache;
    private final EventListenerList listenerList = new EventListenerList();
    private final File downloadDir;
    private final long totalEstimatedSize;
    private final int numFiles;
    
    private Window owner;
    private int downloadTries = 5;
    private long retryDelay = 2000;
    private boolean forced = false;
    private String targetVersion;
    
    private double subprogressOffset = 0;
    private double subprogressSize = 1;
    private Downloader downloader;
    private int currentIndex = 0;
    private long downloadedEstimatedSize = 0;
    
    /**
     * Construct the updater.
     * 
     * @param baseUrl the base URL
     * @param manifest the manifest
     * @param targetDir the directory to update
     * @param cache update cache
     * @param targetVersion the target version
     */
    public PackageManifestUpdater(URL baseUrl, PackageManifest manifest, 
            File targetDir, UpdateCache cache, String targetVersion) {
        this.baseUrl = baseUrl;
        this.manifest = manifest;
        this.rootDir = targetDir;
        this.cache = cache;
        this.downloadDir = new File(rootDir, "_download");
        this.targetVersion = targetVersion;
        
        downloadDir.mkdirs();

        manifest.setDestDir(rootDir);
        totalEstimatedSize = manifest.getTotalSize();
        numFiles = manifest.getDownloadCount();
    }
    
    /**
     * Get the URL of a file.
     * 
     * @param group the group
     * @param file the file
     * @return the URL
     */
    private URL getURL(FileGroup group, PackageFile file) {
        return group.getURL(baseUrl, file);
    }
    
    /**
     * Returns whether two digests (in hex) match.
     * 
     * @param s1 digest 1
     * @param s2 digest 2
     * @return true for match
     */
    private boolean matchesDigest(String s1, String s2) {
        return s1.replaceAll("^0+", "").equalsIgnoreCase(s2.replaceAll("^0+", ""));
    }
    
    /**
     * Show messages for the given phase.
     * 
     * @param phase the phase
     * @throws UpdateException on an error
     * @throws InterruptedException on interruption
     */
    private void showMessages(Phase phase) throws UpdateException, InterruptedException {
        for (Message message : manifest.getMessages(phase)) {
            if (message.mark(cache)) {
                try {
                    if (!message.showDialog(getOwner(), baseUrl)) {
                        throw new UpdateException(
                                "The update has been cancelled.");
                    }
                } catch (IOException e) {
                    throw new UpdateException(
                            "Failed to show message dialog due to error " +
                            "in package manifest", e);
                }
            }
        }
    }
    
    /**
     * Download the files.
     * 
     * @throws UpdateException on download error
     * @throws InterruptedException on interruption
     */
    private void downloadFiles() throws UpdateException, InterruptedException {
        currentIndex = -1;
        
        for (FileGroup group : manifest.getFileGroups()) {
            for (PackageFile file : group.getFiles()) {
                LauncherUtils.checkInterrupted();
                
                currentIndex++;
                
                if (!file.matchesEnvironment()) {
                    logger.info(getURL(group, file) + " does NOT match environment");
                    continue;
                }
                
                if (!file.matchesFilter(manifest.getComponents())) {
                    logger.info(getURL(group, file) + " does NOT match filter");
                    continue;
                }

                // Try to download
                int retryNum = 0;
                Exception e = null;
                for (int trial = 0; trial < downloadTries; trial++) {
                    LauncherUtils.checkInterrupted();
                    
                    logger.info("Downloading " + getURL(group, file) + "...");

                    fireStatusChange(String.format(
                            "Downloading %s (%d/%d) [try %d]...",
                            file.getFile().getName(),
                            currentIndex + 1, numFiles, trial + 1
                            ));
                    fireAdjustedValueChange((downloadedEstimatedSize)
                            / (double) totalEstimatedSize);
                    
                    if ((e = downloadFile(group, file)) == null) {
                        break;
                    } else {
                        retryNum++;
                        fireDownloadStatusChange("Download failed; retrying (" + retryNum + ")...");
                        Launcher.showConsole();
                        logger.warning("Failed to download " + getURL(group, file));
                        Thread.sleep(retryDelay);
                    }
                }
                
                if (e != null) {
                    throw new UpdateException(
                            "Could not download " + file + 
                            ": " + e.getMessage(), e);
                }
                
                downloadedEstimatedSize += file.getSize();
            }
        }
    }
    
    /**
     * Download the given file.
     * 
     * @param group the group
     * @param file the file
     * @return exception if there was a recoverable error
     * @throws UpdateException on download error
     * @throws InterruptedException on interruption
     */
    private Exception downloadFile(FileGroup group, PackageFile file) 
            throws UpdateException, InterruptedException {
        
        OutputStream out;
        MessageDigest digest = null; // Not null if we are verifying the file hash
        URL url = getURL(group, file);
        String cacheId = getCacheId(file);
        String lastVersion = cache.getFileVersion(cacheId);
        
        // Tell the update log that this file was used this update
        cache.touch(cacheId);
        
        // Load the MessageDigest used for verification
        if (!forced) {
            try {
                digest = group.createMessageDigest(); // May return null
            } catch (NoSuchAlgorithmException e) {
                throw new UpdateException("Unknown digest algorithm: " + e.getMessage());
            }
        }

        LauncherUtils.checkInterrupted();

        // The file kept after the download has finished
        // To be later transformed/read to be converted into the final file
        File tempFile = new File(downloadDir, "_" + 
                LauncherUtils.getDigestAsHex(url.toExternalForm(), "MD5"));
        
        // The download file is where the data stored during the download
        // We use two different files because the system/Java can die any second and
        // leave the file behind, and if we want to "resume" the update using already
        // downloaded files, we'd have half-downloaded files we thought were done
        File downloadFile = new File(tempFile.getAbsoluteFile() + ".download");
        
        // We will use this later
        file.setTempFile(tempFile);

        // Now open a stream
        try {
            out = new BufferedOutputStream(new FileOutputStream(downloadFile));
        } catch (IOException e) {
            throw new UpdateException(
                    "Could not write to " + tempFile.getAbsolutePath() + ".", e);
        }

        LauncherUtils.checkInterrupted();

        // Attempt downloading
        try {
            downloader = new URLConnectionDownloader(url, out);
            downloader.addDownloadListener(this);
            
            boolean needsUpdate = true;
            
            // We have our own per-file versioning mechanism where we compare the
            // given file version (an arbitrary string) with the string we
            // last stored for this file
            if (!forced && file.getVersion() != null) {
                if (lastVersion != null && lastVersion.equals(file.getVersion())) {
                    needsUpdate = false;
                }
            // But Mojang has their own digest-based + E-Tag method that we also support
            } else {
                if (digest != null) {
                    downloader.setMessageDigest(digest);
                    downloader.setEtagCheck(cache.getFileVersion(cacheId));
                }
            }

            LauncherUtils.checkInterrupted();
            
            // Download?
            if (needsUpdate) {
                // OK, before we actually download, see if that temporary file "kept after
                // the download has finished" exists from last time
                // Note: Files that use a hash to verify its versions can't use this because
                // we don't yet store the E-tag from last time
                if (digest == null && tempFile.exists()) {
                    logger.info("Found file already downloaded at " + tempFile.getAbsolutePath());
                    
                    // Pretend that we downloaded it
                } else {
                    logger.info("Downloading to " + downloadFile.getAbsolutePath() + "...");

                    LauncherUtils.checkInterrupted();
                    
                    if (downloader.download()) {
                        // Rename the .download file to the temporary file
                        tempFile.delete();
                        downloadFile.renameTo(tempFile);
                    } else {
                        // E-tag mechanism can switch "needs update" flag back to false
                        needsUpdate = false;
                    }
                }
            }
            
            LauncherUtils.checkInterrupted();
            
            // We still need to update?
            if (needsUpdate) {
                String storedVersion = null;
                
                // Check MD5 hash
                if (digest != null) {
                    String signature = new BigInteger(1, digest.digest()).toString(16);
                    if (!matchesDigest(downloader.getEtag(), signature)) {
                        throw new UpdateException(
                                String.format("Signature for %s did not match; expected %s, got %s",
                                        getURL(group, file), downloader.getEtag(), signature));
                    }
                    
                    storedVersion = signature;
                }
                
                // Use our own per-file versioning if we have that
                if (file.getVersion() != null) {
                    storedVersion = file.getVersion();
                }
                
                // OK, if we're not overwriting, then store the last version
                if (file.getOverwrite() != null) {
                    storedVersion = lastVersion;
                }
                
                // This may clear the version if we don't have a version to store
                // this time
                cache.setFileVersion(cacheId, storedVersion);
            } else { // File already downloaded
                file.setIgnored(true);
            }
            
            return null;
        } catch (IOException e) {
            return e;
        } finally {
            downloader = null;
            LauncherUtils.close(out);
            downloadFile.delete();
        }
    }
    
    /**
     * Deploy newly-downloaded updates.
     * 
     * @throws UpdateException 
     * @throws InterruptedException on interruption
     */
    private void deploy(UninstallLog log) throws UpdateException, InterruptedException {
        currentIndex = -1;

        for (FileGroup group : manifest.getFileGroups()) {
            for (PackageFile file : group.getFiles()) {
                LauncherUtils.checkInterrupted();
                
                logger.info("Installing " + file.getFile().getAbsolutePath());
                
                currentIndex++;
                
                if (!file.matchesEnvironment()) {
                    continue;
                }
                
                if (!file.matchesFilter(manifest.getComponents())) {
                    continue;
                }
                
                if (file.isIgnored()) {
                    continue;
                }
                
                fireAdjustedValueChange(currentIndex / numFiles);
                fireStatusChange(String.format(
                        "Installing %s (%d/%d)...", file.getFile().getName(),
                        currentIndex + 1, numFiles));
                
                try {
                    file.getFile().getParentFile().mkdirs();
                    file.deploy(log);
                } catch (SecurityException e) {
                    logger.log(Level.WARNING, "Failed to deploy " + file, e);
                    throw new UpdateException("The digital signature(s) of " +
                            file.getFile().getAbsolutePath() + " could not be verified: " + e.getMessage(), e);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to deploy " + file, e);
                    throw new UpdateException("Could not install to " +
                            file.getFile().getAbsolutePath() + ": " + e.getMessage(), e);
                } catch (Throwable e) {
                    logger.log(Level.WARNING, "Failed to deploy " + file, e);
                    throw new UpdateException("Could not install " +
                            file.getFile().getAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * Delete old files from the previous installation.
     * 
     * @param oldLog old log
     * @param newLog new log
     * @throws UpdateException update exception
     * @throws InterruptedException on interruption
     */
    private void deleteOldFiles(UninstallLog oldLog, UninstallLog newLog) 
            throws UpdateException, InterruptedException {
        for (FileGroup group : manifest.getFileGroups()) {
            for (PackageFile file : group.getFiles()) {
                LauncherUtils.checkInterrupted();
                
                if (file.isIgnored()) {
                    newLog.copyGroupFrom(oldLog, file.getFile());
                }
            }
        }
        
        for (Entry<String, Set<String>> entry : oldLog.getEntrySet()) {
            for (String path : entry.getValue()) {
                LauncherUtils.checkInterrupted();
                
                if (!newLog.has(path)) {
                    new File(rootDir, path).delete();
                }
            }
        }
    }
    
    /**
     * Ask to select components, if necessary.
     */
    private void askComponents() {
        boolean needsDialog = false;
        
        for (Component component : manifest.getComponents()) {
            if (!component.isRequired()) {
                needsDialog = true;
                cache.recallSelection(component);
            }
        }
        
        if (needsDialog) {
            fireStatusChange("Asking for install options...");
            SelectComponentsDialog dialog = new SelectComponentsDialog(owner, manifest);
            dialog.setVisible(true);
        }
        
        for (Component component : manifest.getComponents()) {
            if (!component.isRequired()) {
                cache.storeSelection(component);
            }
        }
    }
    
    @Override
    public void update(UpdateType type) throws UpdateException, InterruptedException {
        // Set whether this update is forced
        forced = type != UpdateType.INCREMENTAL;
        
        File logFile = new File(rootDir, "uninstall.dat");

        showMessages(Phase.INITIALIZE);
        askComponents();
        
        if (forced) {
            // Clean the download cache directory if we are force downloading
            try {
                LauncherUtils.cleanDir(downloadDir);
            } catch (InterruptedException e) {
            }
        }
        
        logger.info("Downloading files...");
        fireStatusChange("Downloading files...");
        setSubprogress(0, 0.95);
        showMessages(Phase.PRE_DOWNLOAD);
        downloadFiles();
        showMessages(Phase.POST_DOWNLOAD);
        
        UninstallLog oldLog = new UninstallLog();
        UninstallLog newLog = new UninstallLog();
        newLog.setBaseDir(rootDir);
        try {
            oldLog.read(logFile);
        } catch (IOException e) {
        }

        logger.info("Installing...");
        fireStatusChange("Installing...");
        setSubprogress(0.95, 0.05);
        showMessages(Phase.PRE_INSTALL);
        deploy(newLog);
        showMessages(Phase.POST_INSTALL);

        logger.info("Removing old files...");
        fireStatusChange("Removing old files...");
        deleteOldFiles(oldLog, newLog);
        
        // Save install log
        try {
            newLog.write(logFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to write " + logFile, e);
            throw new UpdateException("The uninstall log file could not be written to. " +
            		"The update has been aborted.", e);
        }

        // Make sure to delete all the downloads if we're successful
        try {
            LauncherUtils.cleanDir(downloadDir);
        } catch (InterruptedException e) {
        }
        downloadDir.delete();
        
        showMessages(Phase.FINALIZE);

        cache.setLastUpdateId(targetVersion);
        try {
            cache.write();
        } catch (IOException e) {
            throw new UpdateException("Failed to save update cache");
        }
    }
    
    /**
     * Get the current file.
     * 
     * @return the current file
     */
    private PackageFile getCurrentFile() {
        int index = 0;
        for (FileGroup group : manifest.getFileGroups()) {
            for (PackageFile file : group.getFiles()) {
                if (index == currentIndex) {
                    return file;
                }
                index++;
            }
        }
        return null;
    }
    
    /**
     * Fires a status message for the currently downloading file.
     * 
     * @param message message to show
     */
    private void fireDownloadStatusChange(String message) {
        fireStatusChange(String.format("(%d left) %s: %s", numFiles - currentIndex,
                getCurrentFile().getFile().getName(), message));
    }

    /**
     * Called whenever a HTTP download connection is created.
     */
    @Override
    public void connectionStarted(EventObject event) {
        fireDownloadStatusChange("Connected.");
    }

    /**
     * Called with the length is known in an HTTP download.
     */
    @Override
    public void lengthKnown(EventObject event) {
    }

    @Override
    public void downloadProgress(DownloadProgressEvent event) {
        long total = ((Downloader) event.getSource()).getTotalLength();
        PackageFile download = getCurrentFile();
        
        // If length is known
        if (total > 0) {
            fireDownloadStatusChange(String.format("Downloaded %,d/%,d KB...",
                    event.getDownloadedLength() / 1024, total / 1024));
            fireAdjustedValueChange((downloadedEstimatedSize / (double) totalEstimatedSize) +
                    (download.getSize() / (double) totalEstimatedSize) *
                    (event.getDownloadedLength() / (double) total));
        } else {
            fireDownloadStatusChange(String.format("Downloaded %,d KB...",
                    (event.getDownloadedLength() / 1024)));
        }
    }

    @Override
    public void downloadCompleted(EventObject event) {
        fireDownloadStatusChange("Download completed.");
        fireAdjustedValueChange((downloadedEstimatedSize / (double) totalEstimatedSize) +
                (getCurrentFile().getSize() / (double) totalEstimatedSize));
    }
    
    /**
     * Set a sub-progress range with is used by {@link #fireAdjustedValueChange(double)}.
     * 
     * @param offset offset, between 0 and 1
     * @param size size, between 0 and 1
     */
    protected void setSubprogress(double offset, double size) {
        this.subprogressOffset = offset;
        this.subprogressSize = size;
    }
    
    /**
     * Fire a title change.
     * 
     * @param message title
     */
    protected void fireTitleChange(String message) {
        Object[] listeners = listenerList.getListenerList();
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            ((ProgressListener) listeners[i + 1]).titleChanged(
                    new TitleChangeEvent(this, message));
        }
    }
    
    /**
     * Fire a status change.
     * 
     * @param message new status
     */
    protected void fireStatusChange(String message) {
        Object[] listeners = listenerList.getListenerList();
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            ((ProgressListener) listeners[i + 1]).statusChanged(
                    new StatusChangeEvent(this, message));
        }
    }
    
    /**
     * Fire a value change.
     * 
     * @param value value between 0 and 1
     */
    protected void fireValueChange(double value) {
        Object[] listeners = listenerList.getListenerList();
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            ((ProgressListener) listeners[i + 1]).valueChanged(
                    new ValueChangeEvent(this, value));
        }
    }
    
    /**
     * Fire an adjusted value change, which is adjusted with
     * {@link #setSubprogress(double, double)}.
     * 
     * @param value value between 0 and 1
     */
    protected void fireAdjustedValueChange(double value) {
        fireValueChange(value * subprogressSize + subprogressOffset);
    }
    
    /**
     * Gets the relative path between a base and a path.
     * 
     * @param base base path containing path
     * @param path path
     * @return relative path
     */
    private String getRelative(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }
    
    /**
     * Get the ID used to be stored as the key for a file on an {@link UpdateCache}.
     * 
     * @param file the file
     * @return an ID
     */
    private String getCacheId(PackageFile file) {
        return getRelative(rootDir, file.getFile());
    }
    
    @Override
    public void addProgressListener(ProgressListener l) {
        listenerList.add(ProgressListener.class, l);
    }
    
    @Override
    public void removeProgressListener(ProgressListener l) {
        listenerList.remove(ProgressListener.class, l);
    }

    @Override
    public Window getOwner() {
        return owner;
    }

    @Override
    public void setOwner(Window owner) {
        this.owner = owner;
    }

    /**
     * Parse the package file.
     * 
     * @param is the input stream
     * @return the manifest
     * @throws UpdateException on package parse error
     */
    public static PackageManifest parsePackage(InputStream is) 
            throws UpdateException {
        try {
            PackageManifest manifest = XmlUtils.parseJaxb(PackageManifest.class, is);
            
            if (!manifest.isSupportedVersion()) {
                throw new UpdateException(
                        "The update package is written in an unsupported version. " +
                        "(Update launcher?)");
            }
            
            return manifest;
        } catch (Throwable e) {
            logger.log(Level.SEVERE, "Failed to read package file", e);
            throw new UpdateException(
                    "Could not read package.xml file. " +
                    "The update cannot continue.\n\nThe error: " + e.getMessage(), e);
        }
    }

}
