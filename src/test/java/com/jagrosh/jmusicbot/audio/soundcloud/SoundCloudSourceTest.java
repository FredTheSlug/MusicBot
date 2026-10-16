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

import com.jagrosh.jmusicbot.audio.soundcloud.YouTubeFallbackTrackTest.Video;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Loads through the wrapper with a fake SoundCloud source behind it (it hands back whatever the test tells it to)
 * and a fake YouTube source that counts its searches. Tracks are "protected" exactly as the format handler marks
 * them: identifier {@code X:} + lookup URL. The decode test uses lavaplayer's real SoundCloud source and player
 * manager, so the stored form is the one the bot really writes.
 */
public class SoundCloudSourceTest
{
    private final FakeSoundCloud soundCloud = new FakeSoundCloud();
    private final FakeYouTube youtube = new FakeYouTube();
    private final SoundCloudSource source = new SoundCloudSource(soundCloud, youtube, null);

    // ---- loading

    @Test
    public void normalTracksPassThrough()
    {
        AudioTrack plain = soundCloudTrack("aac", false);
        soundCloud.next = plain;
        assertSame(plain, load("https://soundcloud.com/booyabooya/jhbd-4-2026"));

        AudioPlaylist set = new BasicAudioPlaylist("Set", Arrays.asList(plain, soundCloudTrack("mp3", false)), null, false);
        soundCloud.next = set;
        assertSame(set, load("https://soundcloud.com/someone/sets/a-set"));

        soundCloud.next = AudioReference.NO_TRACK;
        assertSame(AudioReference.NO_TRACK, load("scsearch:nothing at all"));

        soundCloud.next = null;
        assertNull(load("https://example.com/not-soundcloud"));
        assertEquals(0, youtube.searches.size());
    }

    @Test
    public void protectedSingleBecomesAFallback() throws Exception
    {
        AudioTrack protectedTrack = soundCloudTrack("444222a", true);
        soundCloud.next = protectedTrack;

        AudioItem loaded = load("https://soundcloud.com/liluzivert/444222a");

        assertTrue(loaded instanceof YouTubeFallbackTrack);
        YouTubeFallbackTrack fallback = (YouTubeFallbackTrack) loaded;
        assertSame(protectedTrack.getInfo(), fallback.getInfo());
        assertSame(source, fallback.getSourceManager());
        assertEquals("soundcloud", fallback.getSourceManager().getSourceName());
        assertEquals(0, youtube.searches.size());

        fallback.process(null);

        assertEquals(Collections.singletonList("ytsearch:Lil Uzi Vert 444222a"), youtube.searches);
        assertEquals(1, youtube.found.get(0).get(1).plays);
        assertTrue(fallback.isPlayingFromYouTube());
    }

    @Test
    public void protectedTracksInsideAPlaylistAndASearchPage()
    {
        AudioTrack first = soundCloudTrack("first", false);
        AudioTrack second = soundCloudTrack("second", true);
        AudioTrack third = soundCloudTrack("third", false);
        AudioTrack fourth = soundCloudTrack("fourth", true);
        soundCloud.next = new BasicAudioPlaylist("A set", Arrays.asList(first, second, third, fourth), second, false);

        AudioPlaylist set = (AudioPlaylist) load("https://soundcloud.com/someone/sets/a-set");

        assertEquals("A set", set.getName());
        assertFalse(set.isSearchResult());
        assertEquals(4, set.getTracks().size());
        assertSame(first, set.getTracks().get(0));
        assertFallbackFor(second, set.getTracks().get(1));
        assertSame(third, set.getTracks().get(2));
        assertFallbackFor(fourth, set.getTracks().get(3));
        assertSame(set.getTracks().get(1), set.getSelectedTrack());

        AudioTrack hit = soundCloudTrack("hit", true);
        AudioTrack other = soundCloudTrack("other", false);
        AudioTrack last = soundCloudTrack("last", false);
        soundCloud.next = new BasicAudioPlaylist("Search results for: hit", Arrays.asList(hit, other, last), null, true);

        AudioPlaylist page = (AudioPlaylist) load("scsearch:hit");

        assertEquals("Search results for: hit", page.getName());
        assertTrue(page.isSearchResult());
        assertNull(page.getSelectedTrack());
        assertEquals(3, page.getTracks().size());
        assertFallbackFor(hit, page.getTracks().get(0));
        assertSame(other, page.getTracks().get(1));
        assertSame(last, page.getTracks().get(2));
        assertEquals(0, youtube.searches.size());
    }

    @Test
    public void loadingProtectedTracksSearchesNothing()
    {
        List<AudioTrack> songs = new ArrayList<>();
        for(int i = 0; i < 50; i++)
            songs.add(soundCloudTrack("song" + i, i % 2 == 0));
        soundCloud.next = new BasicAudioPlaylist("50 songs", songs, null, false);

        AudioPlaylist set = (AudioPlaylist) load("https://soundcloud.com/someone/sets/fifty");

        assertEquals(50, set.getTracks().size());
        for(int i = 0; i < 50; i++)
            assertEquals(i % 2 == 0, set.getTracks().get(i) instanceof YouTubeFallbackTrack);
        assertEquals(0, youtube.searches.size());
    }

    // ---- name, storage, shutdown

    @Test
    public void sourceNameIsSoundcloud() throws Exception
    {
        assertEquals("soundcloud", source.getSourceName());

        SoundCloudSource real = new SoundCloudSource(youtube, null);
        assertEquals("soundcloud", real.getSourceName());
        AudioTrack plain = real.decodeTrack(soundCloudTrack("aac", false).getInfo(), null);
        SoundCloudAudioSourceManager lavaplayer = (SoundCloudAudioSourceManager) plain.getSourceManager();
        assertTrue(lavaplayer.getFormatHandler() instanceof CksSoundCloudFormatHandler);
        assertEquals("soundcloud", lavaplayer.getSourceName());
    }

    @Test
    public void decodedProtectedTrackIsAFallbackAgain() throws Exception
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        try
        {
            SoundCloudSource real = new SoundCloudSource(youtube, manager);
            manager.registerSourceManager(real);
            AudioTrackInfo protectedInfo = soundCloudTrack("444222a", true).getInfo();
            AudioTrack fallback = real.decodeTrack(protectedInfo, null);
            assertTrue(fallback instanceof YouTubeFallbackTrack);
            assertTrue(real.isTrackEncodable(fallback));

            AudioTrack decoded = roundTrip(manager, fallback);

            assertTrue(decoded instanceof YouTubeFallbackTrack);
            assertNotSame(fallback, decoded);
            assertSame(real, decoded.getSourceManager());
            assertInfoEquals(protectedInfo, decoded.getInfo());

            AudioTrack plain = roundTrip(manager, real.decodeTrack(soundCloudTrack("aac", false).getInfo(), null));
            assertFalse(plain instanceof YouTubeFallbackTrack);
            assertEquals(soundCloudTrack("aac", false).getIdentifier(), plain.getIdentifier());
            assertEquals(0, youtube.searches.size());
        }
        finally
        {
            manager.shutdown();
        }
    }

    @Test
    public void shutdownReachesSoundCloud()
    {
        source.shutdown();
        assertTrue(soundCloud.shutDown);
    }

    // ---- helpers

    private AudioItem load(String identifier)
    {
        return source.loadItem(null, new AudioReference(identifier, null));
    }

    private static AudioTrack roundTrip(DefaultAudioPlayerManager manager, AudioTrack track) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manager.encodeTrack(new MessageOutput(bytes), track);
        return manager.decodeTrack(new MessageInput(new ByteArrayInputStream(bytes.toByteArray()))).decodedTrack;
    }

    private void assertFallbackFor(AudioTrack original, AudioTrack swapped)
    {
        assertTrue(swapped instanceof YouTubeFallbackTrack);
        assertSame(original.getInfo(), swapped.getInfo());
        assertSame(source, swapped.getSourceManager());
    }

    private static void assertInfoEquals(AudioTrackInfo expected, AudioTrackInfo actual)
    {
        assertEquals(expected.title, actual.title);
        assertEquals(expected.author, actual.author);
        assertEquals(expected.length, actual.length);
        assertEquals(expected.identifier, actual.identifier);
        assertEquals(expected.uri, actual.uri);
        assertEquals(expected.artworkUrl, actual.artworkUrl);
    }

    /** A track as lavaplayer's SoundCloud source loads it: plain AAC ({@code A:}) or protected ({@code X:}). */
    private static AudioTrack soundCloudTrack(String slug, boolean isProtected)
    {
        String lookupUrl = "https://api-v2.soundcloud.com/media/soundcloud:tracks:1/" + slug + "/stream/hls";
        String identifier = (isProtected ? CksSoundCloudFormatHandler.PROTECTED_PREFIX
                : CksSoundCloudFormatHandler.AAC_PREFIX) + lookupUrl;
        return new Video(new AudioTrackInfo(slug, "Lil Uzi Vert", 180_000, identifier, false,
                "https://soundcloud.com/liluzivert/" + slug, null, null));
    }

    /** Hands back whatever the test put in {@link #next}; decodes like lavaplayer (a new track with that info). */
    private static class FakeSoundCloud implements AudioSourceManager
    {
        AudioItem next;
        boolean shutDown;

        @Override
        public String getSourceName()
        {
            return "soundcloud";
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
        {
            return next;
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
            return new Video(trackInfo);
        }

        @Override
        public void shutdown()
        {
            shutDown = true;
        }
    }

    /** Answers every search with a fresh page: a 1-hour video first, then one close to three minutes. */
    private static class FakeYouTube implements AudioSourceManager
    {
        final List<String> searches = new ArrayList<>();
        final List<List<Video>> found = new ArrayList<>();

        @Override
        public String getSourceName()
        {
            return "youtube";
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
        {
            searches.add(reference.identifier);
            List<Video> videos = Arrays.asList(
                    new Video(new AudioTrackInfo("Hour", "Channel", 3_600_000, "hour" + found.size(), false, null)),
                    new Video(new AudioTrackInfo("Close", "Channel", 182_000, "close" + found.size(), false, null)));
            found.add(videos);
            return new BasicAudioPlaylist("Search results for: " + reference.identifier, new ArrayList<>(videos),
                    null, true);
        }

        @Override
        public boolean isTrackEncodable(AudioTrack track)
        {
            return false;
        }

        @Override
        public void encodeTrack(AudioTrack track, DataOutput output)
        {
        }

        @Override
        public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input)
        {
            return null;
        }

        @Override
        public void shutdown()
        {
        }
    }
}
