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

package com.sk89q.mclauncher.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.sk89q.mclauncher.update.Phase;

@XmlRootElement(name = "package")
public class PackageManifest {

    private String version;

    private List<Component> components = new ArrayList<Component>();
    private List<FileGroup> fileGroups = new ArrayList<FileGroup>();
    private List<Message> messages = new ArrayList<Message>();

    @XmlAttribute
    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @XmlTransient
    public boolean isSupportedVersion() {
        return getVersion().matches("^1\\.[012]$");
    }

    @XmlElement(name = "component")
    public List<Component> getComponents() {
        return components;
    }

    public void setComponents(List<Component> components) {
        this.components = components;
    }

    @XmlElement(name = "filegroup")
    public List<FileGroup> getFileGroups() {
        return fileGroups;
    }

    public void setFileGroups(List<FileGroup> fileGroups) {
        this.fileGroups = fileGroups;
    }

    @XmlElement(name = "message")
    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
    
    public List<Message> getMessages(Phase phase) {
        List<Message> filtered = new ArrayList<Message>();
        for (Message message : getMessages()) {
            if (message.getPhase().equals(phase)) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    public long getTotalSize() {
        long totalSize = 0;
        for (FileGroup group : fileGroups) {
            totalSize += group.getTotalSize();
        }
        return totalSize;
    }

    public void setDestDir(File dir) {
        for (FileGroup group : fileGroups) {
            group.setDestDir(dir);
        }
    }

    public int getDownloadCount() {
        int count = 0;
        for (FileGroup group : fileGroups) {
            count += group.getFiles().size();
        }
        return count;
    }
    
    
}