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

package com.sk89q.mclauncher.util;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import util.WinRegistry;

import com.sk89q.mclauncher.Launcher;

/**
 * Finds the best Java runtime to use.
 */
public final class JavaRuntimeFinder {

    private JavaRuntimeFinder() {
    }
    
    public static File findBestJavaPath() {
        if (Launcher.getPlatform() != Platform.WINDOWS) {
            return null; // Psshhh
        }
        
        List<JREEntry> entries = new ArrayList<JREEntry>();
        try {
            getEntriesFromRegistry(entries, "SOFTWARE\\JavaSoft\\Java Runtime Environment");
            getEntriesFromRegistry(entries, "SOFTWARE\\JavaSoft\\Java Development Kit");
        } catch (Throwable e) {
        }
        Collections.sort(entries);
        
        if (entries.size() > 0) {
            return new File(entries.get(0).dir, "bin");
        }
        
        return null;
    }
    
    private static void getEntriesFromRegistry(List<JREEntry> entries, String basePath)
            throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        List<String> subKeys = WinRegistry.readStringSubKeys(
                WinRegistry.HKEY_LOCAL_MACHINE, basePath);
        for (String subKey : subKeys) {
            JREEntry entry = getEntryFromRegistry(basePath, subKey);
            if (entry != null) {
                entries.add(entry);
            }
        }
    }
    
    private static JREEntry getEntryFromRegistry(String basePath, String version) 
            throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
        String regPath = basePath + "\\" + version;
        String path = WinRegistry.readString(
                WinRegistry.HKEY_LOCAL_MACHINE, regPath, "JavaHome");
        File dir = new File(path);
        if (dir.exists() && new File(dir, "bin/java.exe").exists()) {
            JREEntry entry = new JREEntry();
            entry.dir = dir;
            entry.version = version;
            entry.is64Bit = guessIf64Bit(dir);
            return entry;
        } else {
            return null;
        }
    }
    
    private static boolean guessIf64Bit(File path) {
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 == null) {
            return true;
        }
        return !path.toString().startsWith(new File(programFilesX86).toString());
    }
    
    private static class JREEntry implements Comparable<JREEntry> {
        private File dir;
        private String version;
        private boolean is64Bit;

        @Override
        public int compareTo(JREEntry o) {
            if (is64Bit && !o.is64Bit) {
                return -1;
            } else if (!is64Bit && o.is64Bit) {
                return 1;
            }
            
            String[] a = version.split("[\\._]");
            String[] b = o.version.split("[\\._]");
            int min = Math.min(a.length, b.length);
            
            for (int i = 0; i < min; i++) {
                int first, second;
                
                try {
                    first = Integer.parseInt(a[i]);
                } catch (NumberFormatException e) {
                    return -1;
                }
                
                try {
                    second = Integer.parseInt(b[i]);
                } catch (NumberFormatException e) {
                    return 1;
                }
                
                if (first > second) {
                    return -1;
                } else if (first < second) {
                    return 1;
                }
            }
            
            if (a.length == b.length) {
                return 0; // Same
            }
            
            return a.length > b.length ? -1 : 1;
        }
    }

}
