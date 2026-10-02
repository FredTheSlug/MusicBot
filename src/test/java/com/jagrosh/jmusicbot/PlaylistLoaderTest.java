/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug/MusicBot).
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Playlist names and folders come from Discord commands and the web panel, so they
 * must never resolve to files outside the playlists folder (e.g. the bot's config.txt).
 */
public class PlaylistLoaderTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path botDir;
    private Path playlistsDir;
    private PlaylistLoader loader;

    @Before
    public void setUp() throws Exception
    {
        botDir = tmp.newFolder("bot").toPath();
        Files.write(botDir.resolve("config.txt"), "token = SECRET\n".getBytes());
        playlistsDir = Files.createDirectory(botDir.resolve("Playlists"));
        Files.write(playlistsDir.resolve("Chill.txt"), "https://example.com/a.mp3\n".getBytes());
        Files.createDirectories(playlistsDir.resolve("Rap"));
        Files.write(playlistsDir.resolve("Rap").resolve("Kanye.txt"), "https://example.com/b.mp3\n".getBytes());

        final String folder = playlistsDir.toString();
        loader = new PlaylistLoader(new BotConfig(null)
        {
            @Override
            public String getPlaylistsFolder()
            {
                return folder;
            }
        });
    }

    @Test
    public void playlistsInsideTheFolderStillLoad()
    {
        assertEquals(List.of("https://example.com/a.mp3"), loader.getPlaylist("Chill").getItems());
        assertEquals(List.of("https://example.com/b.mp3"), loader.getPlaylistInFolder("Kanye", "Rap").getItems());
        assertEquals(List.of("Rap"), loader.getFolderNamesInFolder(""));
    }

    @Test
    public void playlistNameCannotEscapeTheFolder()
    {
        assertNull(loader.getPlaylist("../config"));
        assertNull(loader.getPlaylist(".." + File.separator + "config"));
    }

    @Test
    public void folderCannotEscapeTheFolder()
    {
        assertNull(loader.getPlaylistInFolder("config", ".."));
        assertNull(loader.getPlaylistInFolder("config", "Rap/../.."));
        assertNull(loader.getPlaylistInFolder("config", botDir.toAbsolutePath().toString()));
    }

    @Test
    public void listingOutsideTheFolderReturnsNothing()
    {
        assertTrue(loader.getPlaylistNamesInFolder("..").isEmpty());
        assertTrue(loader.getFolderNamesInFolder("..").isEmpty());
    }

    @Test
    public void deletingAFolderOutsideTheFolderIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> loader.deleteSubfolder(".."));
        assertTrue(Files.exists(botDir.resolve("config.txt")));
    }

    @Test
    public void deletingThePlaylistsFolderItselfIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> loader.deleteSubfolder("."));
        assertThrows(IllegalArgumentException.class, () -> loader.deleteSubfolder("Rap/.."));
        assertTrue(Files.exists(playlistsDir.resolve("Chill.txt")));
    }

    @Test
    public void writesOutsideTheFolderAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> loader.writePlaylistInFolder("evil", "..", "x"));
        assertThrows(IllegalArgumentException.class, () -> loader.createPlaylistInFolder("../evil", ""));
        assertThrows(IllegalArgumentException.class, () -> loader.createSubfolder("../evil"));
        assertThrows(IllegalArgumentException.class, () -> loader.movePlaylist("Chill", "", ".."));
        assertThrows(IllegalArgumentException.class, () -> loader.deletePlaylistInFolder("config", ".."));
        assertFalse(Files.exists(botDir.resolve("evil.txt")));
        assertFalse(Files.exists(botDir.resolve("evil")));
        assertTrue(Files.exists(botDir.resolve("config.txt")));
    }
}
