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
package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** The parts of {@link LavaTrackResolver} that need no network: line to identifier, track to song, playlist to answer. */
public class LavaTrackResolverTest
{
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;

    private static AudioTrackInfo info(String title, long length, String identifier, boolean stream, String uri, String artwork)
    {
        return info(title, length, identifier, stream, uri, artwork, "Dr. Dre");
    }

    private static AudioTrackInfo info(String title, long length, String identifier, boolean stream, String uri, String artwork, String author)
    {
        return new AudioTrackInfo(title, author, length, identifier, stream, uri, artwork, null);
    }

    /** A track that only carries its details. */
    private static final class InfoTrack extends BaseAudioTrack
    {
        InfoTrack(AudioTrackInfo info)
        {
            super(info);
        }

        @Override
        public void process(LocalAudioTrackExecutor executor)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static AudioTrack track(String title)
    {
        return new InfoTrack(info(title, 1000, title, false, "https://example.test/" + title, "https://img.test/a.jpg"));
    }

    private static List<AudioTrack> tracks(int count)
    {
        List<AudioTrack> list = new ArrayList<>();
        for(int i = 1; i <= count; i++)
            list.add(track("t" + i));
        return list;
    }

    private static List<String> titles(Resolved resolved)
    {
        List<String> titles = new ArrayList<>();
        for(Song each : resolved.getSongs())
            titles.add(each.getTitle());
        return titles;
    }

    // ---- identifierFor

    @Test
    public void linksAreLoadedAsTheyAre()
    {
        assertEquals("https://www.youtube.com/playlist?list=PL1", LavaTrackResolver.identifierFor("https://www.youtube.com/playlist?list=PL1"));
        assertEquals("http://example.test/a.mp3", LavaTrackResolver.identifierFor("http://example.test/a.mp3"));
        assertEquals("https://open.spotify.com/album/x", LavaTrackResolver.identifierFor("  https://open.spotify.com/album/x \t"));
        assertEquals("spotify:track:abc123", LavaTrackResolver.identifierFor("spotify:track:abc123"));
    }

    @Test
    public void linesThatAlreadySearchAreLoadedAsTheyAre()
    {
        assertEquals("ytsearch:Dr Dre Still", LavaTrackResolver.identifierFor("ytsearch:Dr Dre Still"));
        assertEquals("ytmsearch:Dr Dre Still", LavaTrackResolver.identifierFor(" ytmsearch:Dr Dre Still "));
        assertEquals("scsearch:Dr Dre Still", LavaTrackResolver.identifierFor("scsearch:Dr Dre Still"));
    }

    @Test
    public void anythingElseIsAYoutubeSearch()
    {
        assertEquals("ytsearch:Dr Dre - Still D.R.E.", LavaTrackResolver.identifierFor("Dr Dre - Still D.R.E."));
        assertEquals("ytsearch:trimmed", LavaTrackResolver.identifierFor("   trimmed\t"));
        assertEquals("a name that only mentions a link", "ytsearch:listen to https://x.test", LavaTrackResolver.identifierFor("listen to https://x.test"));
    }

    @Test
    public void localFilesAreLoadedAsTheyAre()
    {
        String[] paths = {
            "/home/cloutkings/music/Juice/Hollywood Dreams/Analog.m4a",
            "/home/cloutkings/music/Artist (Deluxe Edition)/01 Song (feat. Someone) [Live].mp3",
            "C:\\Music\\Juice\\Analog.m4a",
            "d:/Music/Juice/Analog.m4a",
            "\\\\server\\share\\Analog.m4a",
            "file:///home/cloutkings/music/Analog.m4a",
        };
        for(String path : paths)
        {
            assertEquals(path, LavaTrackResolver.identifierFor(path));
            assertEquals("trimmed", path, LavaTrackResolver.identifierFor("  " + path + "\t"));
        }
    }

    @Test
    public void textThatOnlyLooksLikeAPathIsStillASearch()
    {
        assertEquals("relative: not a file we know about", "ytsearch:music/Analog.m4a", LavaTrackResolver.identifierFor("music/Analog.m4a"));
        assertEquals("ytsearch:C is for cookie", LavaTrackResolver.identifierFor("C is for cookie"));
        assertEquals("ytsearch:C:song", LavaTrackResolver.identifierFor("C:song"));
    }

    // ---- songOf

    @Test
    public void songOfNamesAnUntaggedLocalFileAfterItsFile()
    {
        String path = "/home/cloutkings/music/Juice/Hollywood Dreams/Analog.m4a";
        Song song = LavaTrackResolver.songOf(info("Unknown title", 215000, path, false, path, null, "Unknown artist"));

        assertEquals("Analog", song.getTitle());
        assertNull("no author is better than 'Unknown artist'", song.getAuthor());
        assertEquals("the path stays the way to play it", path, song.getUri());
        assertEquals(215000, song.getDurationMs());
        assertNull(song.getArtworkUrl());

        assertEquals("a name with parentheses and a dotted title", "01 Song (feat. Someone) [Live]",
                LavaTrackResolver.songOf(info(null, 1, "/m/A (Deluxe)/01 Song (feat. Someone) [Live].mp3", false,
                        "/m/A (Deluxe)/01 Song (feat. Someone) [Live].mp3", null, "Unknown artist")).getTitle());
        assertEquals("Song (Live)", LavaTrackResolver.songOf(info("", 1, "C:\\Music\\Song (Live).mp3", false,
                "C:\\Music\\Song (Live).mp3", null, null)).getTitle());
        assertEquals("blank title", "Song", LavaTrackResolver.songOf(info("   ", 1, "/m/Song.flac", false, "/m/Song.flac", null, null)).getTitle());
        assertEquals("no extension", "track", LavaTrackResolver.songOf(info("Unknown title", 1, "/m/track", false, "/m/track", null, null)).getTitle());
        assertEquals("only the last extension goes", "a.b", LavaTrackResolver.songOf(info("Unknown title", 1, "/m/a.b.mp3", false, "/m/a.b.mp3", null, null)).getTitle());
        assertEquals("a leading dot is not an extension", ".hidden", LavaTrackResolver.songOf(info("Unknown title", 1, "/m/.hidden", false, "/m/.hidden", null, null)).getTitle());
    }

    @Test
    public void songOfKeepsTheTagsOfALocalFile()
    {
        String path = "/home/cloutkings/music/Analog.m4a";
        Song song = LavaTrackResolver.songOf(info("Analog (Remix)", 1000, path, false, path, null, "Juice"));

        assertEquals("Analog (Remix)", song.getTitle());
        assertEquals("Juice", song.getAuthor());
    }

    @Test
    public void songOfLeavesOtherUntitledTracksAlone()
    {
        assertNull("not a local file: no file name to use", LavaTrackResolver.songOf(info(null, 1, "x", false, "https://example.test/stream", null)).getTitle());
        assertEquals("Unknown title", LavaTrackResolver.songOf(info("Unknown title", 1, "x", false, "https://example.test/a", null)).getTitle());
        assertNull("'Unknown artist' goes for any track", LavaTrackResolver.songOf(info("t", 1, "x", false, "https://example.test/a", null, "Unknown artist")).getAuthor());
    }

    @Test
    public void songOfCopiesTheTrackDetails()
    {
        Song song = LavaTrackResolver.songOf(info("Still D.R.E.", 270000, VIDEO_ID, false, VIDEO_URL, "https://img.test/big.jpg"));

        assertEquals(VIDEO_URL, song.getUri());
        assertEquals("Still D.R.E.", song.getTitle());
        assertEquals("Dr. Dre", song.getAuthor());
        assertEquals(270000, song.getDurationMs());
        assertEquals("the artwork lavaplayer gave", "https://img.test/big.jpg", song.getArtworkUrl());
        assertFalse(song.isUnresolved());
        assertNull("the playlist gives it an id", song.getId());
        assertNull(song.getAddedBy());
        assertNull(song.getAddedAt());
    }

    @Test
    public void songOfFillsInYoutubeThumbnail()
    {
        String thumbnail = "https://i.ytimg.com/vi/" + VIDEO_ID + "/hqdefault.jpg";
        assertEquals(thumbnail, LavaTrackResolver.songOf(info("t", 1000, VIDEO_ID, false, VIDEO_URL, null)).getArtworkUrl());
        assertEquals("blank counts as missing", thumbnail,
                LavaTrackResolver.songOf(info("t", 1000, VIDEO_ID, false, "https://youtu.be/" + VIDEO_ID, "")).getArtworkUrl());
    }

    @Test
    public void songOfLeavesArtworkEmptyOutsideYoutube()
    {
        assertNull(LavaTrackResolver.songOf(info("t", 1000, "12345", false, "https://soundcloud.com/a/b", null)).getArtworkUrl());
        assertNull("an identifier that is no video id", LavaTrackResolver.songOf(info("t", 1000, "PLalbum", false, VIDEO_URL, null)).getArtworkUrl());
        assertNull(LavaTrackResolver.songOf(info("t", 1000, VIDEO_ID, false, null, null)).getArtworkUrl());
    }

    @Test
    public void songOfHasNoDurationForStreamsOrUnknownLengths()
    {
        assertEquals("a stream", 0, LavaTrackResolver.songOf(info("live", 123456, VIDEO_ID, true, VIDEO_URL, null)).getDurationMs());
        assertEquals("lavaplayer's 'unknown'", 0, LavaTrackResolver.songOf(info("t", Long.MAX_VALUE, VIDEO_ID, false, VIDEO_URL, null)).getDurationMs());
        assertEquals(0, LavaTrackResolver.songOf(info("t", -1, VIDEO_ID, false, VIDEO_URL, null)).getDurationMs());
        assertEquals(1, LavaTrackResolver.songOf(info("t", 1, VIDEO_ID, false, VIDEO_URL, null)).getDurationMs());
    }

    // ---- fromPlaylist

    @Test
    public void searchResultIsItsFirstTrack()
    {
        Resolved resolved = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("Search results for: x", tracks(5), null, true));

        assertNull(resolved.getError());
        assertEquals(Collections.singletonList("t1"), titles(resolved));
        assertNull("a search is not a collection", resolved.getTitle());
    }

    @Test
    public void linkWithSelectedTrackIsJustThatTrack()
    {
        List<AudioTrack> all = tracks(5);
        Resolved resolved = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("Some mix", all, all.get(2), false));

        assertNull(resolved.getError());
        assertEquals(Collections.singletonList("t3"), titles(resolved));
        assertNull(resolved.getTitle());
    }

    @Test
    public void albumIsAllItsTracksWithItsTitle()
    {
        Resolved resolved = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("The Chronic", tracks(3), null, false));

        assertNull(resolved.getError());
        assertEquals(Arrays.asList("t1", "t2", "t3"), titles(resolved));
        assertEquals("The Chronic", resolved.getTitle());
        assertEquals("https://example.test/t2", resolved.getSongs().get(1).getUri());
    }

    @Test
    public void playlistIsCappedAt2000Tracks()
    {
        Resolved resolved = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("Huge", tracks(2500), null, false));

        assertEquals(2000, resolved.getSongs().size());
        assertEquals("t2000", resolved.getSongs().get(1999).getTitle());
        assertNull(resolved.getError());
    }

    @Test
    public void emptyPlaylistIsAnError()
    {
        Resolved search = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("none", Collections.emptyList(), null, true));
        Resolved album = LavaTrackResolver.fromPlaylist(new BasicAudioPlaylist("none", Collections.emptyList(), null, false));

        for(Resolved each : new Resolved[]{search, album})
        {
            assertTrue(each.getSongs().isEmpty());
            assertNotNull(each.getError());
        }
    }

    // ---- no network

    @Test
    public void spotifyLinkWithoutSpotifyIsAnError()
    {
        LavaTrackResolver resolver = new LavaTrackResolver(null, null); // must not touch the player manager
        List<Integer> batches = new ArrayList<>();

        Resolved resolved = resolver.resolve("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", (songs, total) -> batches.add(total));

        assertEquals("Spotify isn't set up on this bot", resolved.getError());
        assertTrue(resolved.getSongs().isEmpty());
        assertTrue(batches.isEmpty());
        assertEquals("Spotify isn't set up on this bot", resolver.resolve("spotify:track:abc123").getError());
    }

    @Test
    public void blankLineIsAnErrorNotAnException()
    {
        LavaTrackResolver resolver = new LavaTrackResolver(null, null);

        assertNotNull(resolver.resolve("   ").getError());
        assertNotNull(resolver.resolve(null).getError());
    }
}
