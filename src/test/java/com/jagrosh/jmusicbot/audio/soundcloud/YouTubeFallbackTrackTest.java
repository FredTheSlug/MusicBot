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
package com.jagrosh.jmusicbot.audio.soundcloud;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.io.DataInput;
import java.io.DataOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Plays fallback tracks against a fake YouTube search. The tracks it "finds" are bare lavaplayer tracks that only
 * count how often they are played, so a test can see which result became the delegate and that a finished one is
 * never played twice (lavaplayer refuses to play the same instance again). No executor is needed: the fallback
 * hands the one it is given to its delegate, and the fake delegates ignore it.
 */
public class YouTubeFallbackTrackTest
{
    private static final String PROTECTED = "SoundCloud protects this track and YouTube had no match";
    private static final long THREE_MINUTES = 180_000;
    private static final AudioTrackInfo SOUNDCLOUD = new AudioTrackInfo("444222a", "Lil Uzi Vert", THREE_MINUTES,
            "X:https://api-v2.soundcloud.com/media/soundcloud:tracks:1/abc/stream/hls", false,
            "https://soundcloud.com/liluzivert/444222a", "https://i1.sndcdn.com/artworks-1-t500x500.jpg", null);

    // ---- which result is picked

    @Test
    public void picksTheClosestLengthAmongTheTopFive()
    {
        List<AudioTrack> results = Arrays.asList(video("hour", 3_600_000), video("seven", 420_000),
                video("close", 185_000), video("five", 300_000));
        assertEquals("close", YouTubeFallbackTrack.pick(results, THREE_MINUTES).getIdentifier());
    }

    @Test
    public void firstResultWhenNoneIsClose()
    {
        List<AudioTrack> results = Arrays.asList(video("hour", 3_600_000), video("ten", 600_000),
                video("short", 30_000));
        assertEquals("hour", YouTubeFallbackTrack.pick(results, THREE_MINUTES).getIdentifier());
    }

    @Test
    public void onlySearchesTheTopFive()
    {
        List<AudioTrack> results = Arrays.asList(video("one", 3_600_000), video("two", 3_600_000),
                video("three", 3_600_000), video("four", 3_600_000), video("five", 3_600_000),
                video("sixth", THREE_MINUTES));
        assertEquals("one", YouTubeFallbackTrack.pick(results, THREE_MINUTES).getIdentifier());
    }

    @Test
    public void noResultIsTheProtectedMessage() throws Exception
    {
        assertNull(YouTubeFallbackTrack.pick(Collections.emptyList(), THREE_MINUTES));

        YouTubeFallbackTrack nothingFound = new YouTubeFallbackTrack(SOUNDCLOUD, query -> Collections.emptyList(),
                new NamedSource());
        assertProtected(nothingFound);
        assertFalse(nothingFound.isPlayingFromYouTube());

        YouTubeFallbackTrack searchFailed = new YouTubeFallbackTrack(SOUNDCLOUD, query ->
        {
            throw new FriendlyException("YouTube said no", FriendlyException.Severity.SUSPICIOUS, null);
        }, new NamedSource());
        assertProtected(searchFailed);
        assertFalse(searchFailed.isPlayingFromYouTube());
    }

    // ---- what plays and what is kept

    @Test
    public void keepsTheSoundCloudInfo() throws Exception
    {
        CountingSearch search = new CountingSearch();
        AudioSourceManager source = new NamedSource();
        YouTubeFallbackTrack track = new YouTubeFallbackTrack(SOUNDCLOUD, search, source);
        assertFalse(track.isPlayingFromYouTube());

        track.process(null);

        assertEquals(Collections.singletonList("Lil Uzi Vert 444222a"), search.queries);
        assertEquals(0, search.found.get(0).get(0).plays);
        assertEquals(1, search.found.get(0).get(1).plays);
        assertTrue(track.isPlayingFromYouTube());
        assertSame(SOUNDCLOUD, track.getInfo());
        assertEquals("444222a", track.getInfo().title);
        assertEquals("Lil Uzi Vert", track.getInfo().author);
        assertEquals(THREE_MINUTES, track.getInfo().length);
        assertEquals("https://soundcloud.com/liluzivert/444222a", track.getInfo().uri);
        assertEquals("https://i1.sndcdn.com/artworks-1-t500x500.jpg", track.getInfo().artworkUrl);
        assertEquals(SOUNDCLOUD.identifier, track.getIdentifier());
        assertSame(source, track.getSourceManager());
    }

    @Test
    public void aCloneSearchesAgain() throws Exception
    {
        CountingSearch search = new CountingSearch();
        YouTubeFallbackTrack track = new YouTubeFallbackTrack(SOUNDCLOUD, search, new NamedSource());
        track.setUserData("requester");
        track.process(null);

        AudioTrack clone = track.makeClone();

        assertTrue(clone instanceof YouTubeFallbackTrack);
        assertNotSame(track, clone);
        assertSame(SOUNDCLOUD, clone.getInfo());
        assertEquals("requester", clone.getUserData());
        assertSame(track.getSourceManager(), clone.getSourceManager());
        assertFalse(((YouTubeFallbackTrack) clone).isPlayingFromYouTube());
        assertEquals(1, search.queries.size());

        ((YouTubeFallbackTrack) clone).process(null);

        assertEquals(2, search.queries.size());
        assertEquals(1, search.found.get(0).get(1).plays);
        assertEquals(1, search.found.get(1).get(1).plays);
        assertTrue(((YouTubeFallbackTrack) clone).isPlayingFromYouTube());
    }

    @Test
    public void theSwapLineLeavesOutTheQueryString()
    {
        assertEquals("https://soundcloud.com/liluzivert/444222a",
                YouTubeFallbackTrack.withoutQuery("https://soundcloud.com/liluzivert/444222a?si=abc&utm_source=x"));
        assertEquals("https://soundcloud.com/liluzivert/444222a",
                YouTubeFallbackTrack.withoutQuery("https://soundcloud.com/liluzivert/444222a"));
        assertNull(YouTubeFallbackTrack.withoutQuery(null));
    }

    // ---- helpers

    private static void assertProtected(YouTubeFallbackTrack track) throws Exception
    {
        try
        {
            track.process(null);
            fail("expected the protected message");
        }
        catch(FriendlyException e)
        {
            assertEquals(PROTECTED, e.getMessage());
            assertEquals(FriendlyException.Severity.COMMON, e.severity);
        }
    }

    private static Video video(String id, long lengthMs)
    {
        return new Video(new AudioTrackInfo("Song", "Channel", lengthMs, id, false, "https://youtu.be/" + id));
    }

    /** A YouTube result that only counts how often it is played. */
    static class Video extends BaseAudioTrack
    {
        int plays;

        Video(AudioTrackInfo info)
        {
            super(info);
        }

        @Override
        public void process(LocalAudioTrackExecutor executor)
        {
            plays++;
        }
    }

    /** Finds two fresh videos for every query (a 1-hour one first, then a close one) and keeps what it found. */
    static class CountingSearch implements YouTubeFallbackTrack.YouTubeSearch
    {
        final List<String> queries = new ArrayList<>();
        final List<List<Video>> found = new ArrayList<>();

        @Override
        public List<AudioTrack> search(String query)
        {
            queries.add(query);
            List<Video> videos = Arrays.asList(video("hour" + found.size(), 3_600_000),
                    video("close" + found.size(), 181_000));
            found.add(videos);
            return new ArrayList<>(videos);
        }
    }

    /** Stands in for the SoundCloud source a fallback track names as its own. */
    static class NamedSource implements AudioSourceManager
    {
        @Override
        public String getSourceName()
        {
            return "soundcloud";
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
        {
            throw new AssertionError("not used");
        }

        @Override
        public boolean isTrackEncodable(AudioTrack track)
        {
            return true;
        }

        @Override
        public void encodeTrack(AudioTrack track, DataOutput output)
        {
        }

        @Override
        public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input)
        {
            throw new AssertionError("not used");
        }

        @Override
        public void shutdown()
        {
        }
    }
}
