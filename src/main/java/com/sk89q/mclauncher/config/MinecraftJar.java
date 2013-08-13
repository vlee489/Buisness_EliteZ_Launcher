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

package com.sk89q.mclauncher.config;

import java.io.File;

import com.sk89q.mclauncher.util.LauncherUtils;

/**
 * Represents a jar that contains the main Minecraft game.
 */
public class MinecraftJar {

    private final File file;
    private String version;

    public MinecraftJar(File f) {
        this.file = f;
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return file.getName();
    }

    public String getVersion() {
        return (version == null) ? (version = LauncherUtils.getMCVersion(file)) : version;
    }
    
    public boolean allowsUpdate() {
        return false;
    }
    
    public boolean isInstalled(File baseDir) {
        return true;
    }

    @Override
    public String toString() {
        return file.getName() + " (" + getVersion() + ")";
    }
    
}
