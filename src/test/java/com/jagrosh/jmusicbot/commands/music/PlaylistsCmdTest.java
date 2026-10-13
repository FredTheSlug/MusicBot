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
package com.jagrosh.jmusicbot.commands.music;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** The playlist list a Discord reply shows. */
public class PlaylistsCmdTest
{
    private static final String HEADER = "Available playlists:\n";
    private static final String FOOTER = "\nType `!play playlist <name>` to play a playlist";

    @Test
    public void aShortListShowsEveryPlaylist()
    {
        String message = PlaylistsCmd.listMessage(HEADER, Arrays.asList("Top", "Rock/Mix", "Rock/Sub/Deep"), FOOTER, 2000);
        assertEquals(HEADER + "`Top` `Rock/Mix` `Rock/Sub/Deep` " + FOOTER, message);
    }

    @Test
    public void aListThatIsTooLongIsCutAndSaysHowManyAreLeft()
    {
        List<String> paths = new ArrayList<>();
        for(int i = 1; i <= 200; i++)
            paths.add(String.format("Folder/Playlist number %03d", i));
        String message = PlaylistsCmd.listMessage(HEADER, paths, FOOTER, 2000);

        assertTrue("fits in a Discord message: " + message.length(), message.length() <= 2000);
        assertTrue(message.startsWith(HEADER + "`Folder/Playlist number 001` `Folder/Playlist number 002` "));
        assertTrue("the footer is kept", message.endsWith(FOOTER));
        int shown = message.split("`Folder/Playlist number ", -1).length - 1;
        assertTrue(shown > 40 && shown < 200);
        assertTrue(message, message.contains("(+" + (200 - shown) + " more)"));
        assertTrue("names are cut whole, in order", message.contains(String.format("`Folder/Playlist number %03d` ", shown)));
        assertFalse(message.contains(String.format("`Folder/Playlist number %03d` ", shown + 1)));
    }

    @Test
    public void aListThatJustFitsIsNotCut()
    {
        String name = "x".repeat(97); // each piece, with its backticks and the space after it, is 100 characters
        List<String> paths = new ArrayList<>();
        for(int i = 0; i < 19; i++)
            paths.add(name);
        String message = PlaylistsCmd.listMessage("", paths, "", 1900);
        assertEquals(1900, message.length());
        assertFalse(message.contains("more)"));
        assertTrue(PlaylistsCmd.listMessage("", paths, "", 1899).contains("more)"));
    }
}
