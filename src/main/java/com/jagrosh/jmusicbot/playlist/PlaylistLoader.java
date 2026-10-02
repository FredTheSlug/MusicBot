/*
 * Copyright 2018 John Grosh (jagrosh).
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlaylistLoader
{
    private final BotConfig config;
    
    public PlaylistLoader(BotConfig config)
    {
        this.config = config;
    }
    
    public List<String> getPlaylistNames()
    {
        return getPlaylistNamesInFolder("");
    }
    
    public List<String> getPlaylistNamesInFolder(String folder)
    {
        if(folderExists())
        {
            File dir;
            try
            {
                dir = getSubfolderFile(folder);
            }
            catch(IllegalArgumentException e)
            {
                return Collections.emptyList();
            }
            if(!dir.exists() || !dir.isDirectory())
                return Collections.emptyList();
            File[] files = dir.listFiles((pathname) -> pathname.getName().endsWith(".txt") && pathname.isFile());
            if(files == null) return Collections.emptyList();
            return Arrays.asList(files).stream()
                    .map(f -> f.getName().substring(0, f.getName().length()-4))
                    .filter(name -> !name.isEmpty())
                    .collect(Collectors.toList());
        }
        else
        {
            createFolder();
            return Collections.emptyList();
        }
    }
    
    public List<String> getFolderNames()
    {
        return getFolderNamesInFolder("");
    }
    
    public List<String> getFolderNamesInFolder(String parentFolder)
    {
        if(!folderExists())
        {
            createFolder();
            return Collections.emptyList();
        }
        File dir;
        try
        {
            dir = getSubfolderFile(parentFolder);
        }
        catch(IllegalArgumentException e)
        {
            return Collections.emptyList();
        }
        if(!dir.exists() || !dir.isDirectory())
            return Collections.emptyList();
        File[] subdirs = dir.listFiles(File::isDirectory);
        if(subdirs == null) return Collections.emptyList();
        return Arrays.asList(subdirs).stream().map(File::getName).sorted().collect(Collectors.toList());
    }
    
    public boolean folderExists()
    {
        return Files.exists(OtherUtil.getPath(config.getPlaylistsFolder()));
    }
    
    public void createFolder()
    {
        try
        {
            Files.createDirectory(OtherUtil.getPath(config.getPlaylistsFolder()));
        } 
        catch (IOException ignore) {}
    }
    
    public void createSubfolder(String folderPath) throws IOException
    {
        Path path = resolvePlaylistPath(folderPath);
        Files.createDirectories(path);
    }

    public void deleteSubfolder(String folderPath) throws IOException
    {
        Path path = resolvePlaylistPath(folderPath);
        if(path.equals(playlistsRoot()))
            throw new IllegalArgumentException("Refusing to delete the playlists folder");
        if(!Files.exists(path)) throw new IOException("Folder not found");
        File[] contents = path.toFile().listFiles();
        if(contents != null)
        {
            for(File f : contents)
            {
                if(f.isDirectory()) deleteSubfolder(folderPath + "/" + f.getName());
                else Files.delete(f.toPath());
            }
        }
        Files.delete(path);
    }
    
    public void movePlaylist(String name, String fromFolder, String toFolder) throws IOException
    {
        Path source = resolvePlaylistFile(name, fromFolder);
        Path target = resolvePlaylistFile(name, toFolder);
        Files.createDirectories(target.getParent());
        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void createPlaylist(String name) throws IOException
    {
        createPlaylistInFolder(name, "");
    }

    public void createPlaylistInFolder(String name, String folder) throws IOException
    {
        Path file = resolvePlaylistFile(name, folder);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    public void deletePlaylist(String name) throws IOException
    {
        deletePlaylistInFolder(name, "");
    }

    public void deletePlaylistInFolder(String name, String folder) throws IOException
    {
        Files.delete(resolvePlaylistFile(name, folder));
    }

    public void writePlaylist(String name, String text) throws IOException
    {
        writePlaylistInFolder(name, "", text);
    }

    public void writePlaylistInFolder(String name, String folder, String text) throws IOException
    {
        Path file = resolvePlaylistFile(name, folder);
        Files.createDirectories(file.getParent());
        Files.write(file, text.trim().getBytes());
    }
    
    public Playlist getPlaylist(String name)
    {
        return getPlaylistInFolder(name, "");
    }
    
    public Playlist getPlaylistInFolder(String name, String folder)
    {
        try
        {
            if(!folderExists())
            {
                createFolder();
                return null;
            }
            Path filePath = resolvePlaylistFile(name, folder);
            if(!Files.exists(filePath))
                return null;
            boolean[] shuffle = {false};
            List<String> list = new ArrayList<>();
            Files.readAllLines(filePath).forEach(str -> 
            {
                String s = str.trim();
                if(s.isEmpty())
                    return;
                if(s.startsWith("#") || s.startsWith("//"))
                {
                    s = s.replaceAll("\\s+", "");
                    if(s.equalsIgnoreCase("#shuffle") || s.equalsIgnoreCase("//shuffle"))
                        shuffle[0]=true;
                }
                else
                    list.add(s);
            });
            if(shuffle[0])
                shuffle(list);
            return new Playlist(name, list, shuffle[0]);
        }
        catch(IOException | IllegalArgumentException e)
        {
            return null;
        }
    }
    
    private Path playlistsRoot()
    {
        return OtherUtil.getPath(config.getPlaylistsFolder()).toAbsolutePath().normalize();
    }

    /**
     * Resolves a folder (relative, '/'-separated) under the playlists folder.
     * Names and folders come from Discord and the web panel, so anything that would
     * land outside the playlists folder (.., absolute paths) is rejected.
     */
    private Path resolvePlaylistPath(String folder)
    {
        Path base = playlistsRoot();
        if(folder == null || folder.isEmpty())
            return base;
        Path resolved = base.resolve(folder.replace('/', File.separatorChar)).normalize();
        if(!resolved.startsWith(base))
            throw new IllegalArgumentException("Invalid playlist folder: " + folder);
        return resolved;
    }

    private Path resolvePlaylistFile(String name, String folder)
    {
        if(name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("\0"))
            throw new IllegalArgumentException("Invalid playlist name: " + name);
        return resolvePlaylistPath(folder).resolve(name + ".txt");
    }

    private File getSubfolderFile(String folder)
    {
        return resolvePlaylistPath(folder).toFile();
    }
    
    
    private static <T> void shuffle(List<T> list)
    {
        for(int first =0; first<list.size(); first++)
        {
            int second = (int)(Math.random()*list.size());
            T tmp = list.get(first);
            list.set(first, list.get(second));
            list.set(second, tmp);
        }
    }
    
    
    public class Playlist
    {
        private final String name;
        private final List<String> items;
        private final boolean shuffle;
        private final List<AudioTrack> tracks = new LinkedList<>();
        private final List<PlaylistLoadError> errors = new LinkedList<>();
        private boolean loaded = false;
        
        private Playlist(String name, List<String> items, boolean shuffle)
        {
            this.name = name;
            this.items = items;
            this.shuffle = shuffle;
        }
        
        public void loadTracks(AudioPlayerManager manager, Consumer<AudioTrack> consumer, Runnable callback)
        {
            if(loaded)
                return;
            loaded = true;
            for(int i=0; i<items.size(); i++)
            {
                boolean last = i+1 == items.size();
                int index = i;
                manager.loadItemOrdered(name, items.get(i), new AudioLoadResultHandler() 
                {
                    private void done()
                    {
                        if(last)
                        {
                            if(shuffle)
                                shuffleTracks();
                            if(callback != null)
                                callback.run();
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack at) 
                    {
                        if(config.isTooLong(at))
                            errors.add(new PlaylistLoadError(index, items.get(index), "This track is longer than the allowed maximum"));
                        else
                        {
                            at.setUserData(0L);
                            tracks.add(at);
                            consumer.accept(at);
                        }
                        done();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist ap) 
                    {
                        if(ap.isSearchResult())
                        {
                            trackLoaded(ap.getTracks().get(0));
                        }
                        else if(ap.getSelectedTrack()!=null)
                        {
                            trackLoaded(ap.getSelectedTrack());
                        }
                        else
                        {
                            List<AudioTrack> loaded = new ArrayList<>(ap.getTracks());
                            if(shuffle)
                                for(int first =0; first<loaded.size(); first++)
                                {
                                    int second = (int)(Math.random()*loaded.size());
                                    AudioTrack tmp = loaded.get(first);
                                    loaded.set(first, loaded.get(second));
                                    loaded.set(second, tmp);
                                }
                            loaded.removeIf(track -> config.isTooLong(track));
                            loaded.forEach(at -> at.setUserData(0L));
                            tracks.addAll(loaded);
                            loaded.forEach(at -> consumer.accept(at));
                        }
                        done();
                    }

                    @Override
                    public void noMatches() 
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "No matches found."));
                        done();
                    }

                    @Override
                    public void loadFailed(FriendlyException fe) 
                    {
                        errors.add(new PlaylistLoadError(index, items.get(index), "Failed to load track: "+fe.getLocalizedMessage()));
                        done();
                    }
                });
            }
        }
        
        public void shuffleTracks()
        {
            shuffle(tracks);
        }
        
        public String getName()
        {
            return name;
        }

        public List<String> getItems()
        {
            return items;
        }

        public List<AudioTrack> getTracks()
        {
            return tracks;
        }
        
        public List<PlaylistLoadError> getErrors()
        {
            return errors;
        }
    }
    
    public class PlaylistLoadError
    {
        private final int number;
        private final String item;
        private final String reason;
        
        private PlaylistLoadError(int number, String item, String reason)
        {
            this.number = number;
            this.item = item;
            this.reason = reason;
        }
        
        public int getIndex()
        {
            return number;
        }
        
        public String getItem()
        {
            return item;
        }
        
        public String getReason()
        {
            return reason;
        }
    }
}
