/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug)
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

import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SpotifyResolverTest
{
    @Test
    public void parseOpenTrackUrl()
    {
        SpotifyResolver.SpotifyLink link = SpotifyResolver.parseLink(
                "https://open.spotify.com/track/6rqhFgbbKwnb9MLmUQDhG6?si=abc");
        assertNotNull(link);
        assertEquals("track", link.type);
        assertEquals("6rqhFgbbKwnb9MLmUQDhG6", link.id);
    }

    @Test
    public void parsePlaylistUri()
    {
        SpotifyResolver.SpotifyLink link = SpotifyResolver.parseLink("spotify:playlist:37i9dQZF1DXcF6B6QPhFDv");
        assertNotNull(link);
        assertEquals("playlist", link.type);
        assertEquals("37i9dQZF1DXcF6B6QPhFDv", link.id);
    }

    @Test
    public void parseAlbumUrl()
    {
        SpotifyResolver.SpotifyLink link = SpotifyResolver.parseLink("https://open.spotify.com/album/4yP0hdKOZMnixs1XptvNZF");
        assertNotNull(link);
        assertEquals("album", link.type);
    }

    @Test
    public void rejectsNonSpotify()
    {
        assertNull(SpotifyResolver.parseLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(SpotifyResolver.looksLikeSpotifyUrl("https://open.spotify.com/track/abc123"));
    }
}
