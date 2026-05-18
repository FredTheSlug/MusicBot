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
