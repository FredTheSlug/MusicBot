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

import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Feeds the handler the format lists lavaplayer's {@code DefaultSoundCloudDataReader} builds from SoundCloud's
 * api-v2 ({@code DefaultSoundCloudTrackFormat}: protocol, mime type and lookup URL only) and checks which one it
 * picks, the identifier it gives that choice, and where the identifiers are routed afterwards. Today's errors are
 * never typed in here: they are read from lavaplayer's own default handler given the same list.
 */
public class CksSoundCloudFormatHandlerTest
{
    private static final String AAC = "audio/mp4; codecs=\"mp4a.40.2\"";
    private static final String PROTECTED = "SoundCloud protects this track and YouTube had no match";

    private final CksSoundCloudFormatHandler handler = new CksSoundCloudFormatHandler();

    private static SoundCloudTrackFormat format(String protocol, String mimeType, String lookupUrl)
    {
        return new DefaultSoundCloudTrackFormat("123", protocol, mimeType, lookupUrl);
    }

    private static List<SoundCloudTrackFormat> list(SoundCloudTrackFormat... formats)
    {
        return Arrays.asList(formats);
    }

    // ---- what is chosen

    @Test
    public void mp3AndOpusStillWin()
    {
        SoundCloudTrackFormat aac = format("hls", AAC, "aac");
        SoundCloudTrackFormat opus = format("hls", "audio/ogg", "opus");
        SoundCloudTrackFormat hlsMp3 = format("hls", "audio/mpeg", "hlsmp3");
        SoundCloudTrackFormat progressive = format("progressive", "audio/mpeg", "prog");
        DefaultSoundCloudFormatHandler lavaplayer = new DefaultSoundCloudFormatHandler();

        List<List<SoundCloudTrackFormat>> offers = Arrays.asList(
                list(aac, progressive, hlsMp3, opus),
                list(aac, progressive, hlsMp3),
                list(aac, progressive));
        List<String> expectedIds = Arrays.asList("O:opus", "U:hlsmp3", "M:prog");
        for(int i = 0; i < offers.size(); i++)
        {
            SoundCloudTrackFormat chosen = handler.chooseBestFormat(offers.get(i));
            assertSame(lavaplayer.chooseBestFormat(offers.get(i)), chosen);
            assertEquals(expectedIds.get(i), handler.buildFormatIdentifier(chosen));
            assertEquals(lavaplayer.buildFormatIdentifier(chosen), handler.buildFormatIdentifier(chosen));
        }
    }

    /**
     * lavaplayer's formats carry no SoundCloud preset ({@code aac_160k} / {@code aac_96k}), so the first plain AAC in
     * SoundCloud's own order is the one taken. This pins that order, not a bitrate.
     */
    @Test
    public void firstPlainAacInSoundCloudsOrderWins()
    {
        SoundCloudTrackFormat first = format("hls", AAC, "first");
        SoundCloudTrackFormat second = format("hls", AAC, "second");

        SoundCloudTrackFormat chosen = handler.chooseBestFormat(list(
                format("hls", "audio/mpegurl", "abr"), first, second));

        assertSame(first, chosen);
        assertEquals("A:first", handler.buildFormatIdentifier(chosen));
        assertSame(second, handler.chooseBestFormat(list(second, first)));
    }

    @Test
    public void aacCountsWithOrWithoutCodecsParameter()
    {
        assertEquals("A:bare", handler.buildFormatIdentifier(
                handler.chooseBestFormat(list(format("hls", "audio/mp4", "bare")))));
        assertEquals("A:coded", handler.buildFormatIdentifier(
                handler.chooseBestFormat(list(format("hls", AAC, "coded")))));
        assertEquals("A:spaced", handler.buildFormatIdentifier(
                handler.chooseBestFormat(list(format("hls", "Audio/MP4 ; codecs=\"mp4a.40.2\"", "spaced")))));
    }

    @Test
    public void plainAacBeatsEncryptedAndAdaptive()
    {
        SoundCloudTrackFormat aac = format("hls", AAC, "aac160");

        SoundCloudTrackFormat chosen = handler.chooseBestFormat(list(
                format("ctr-encrypted-hls", "audio/mp4; codecs=\"mp4a.40.2\"", "ctr"),
                format("cbc-encrypted-hls", "audio/mp4; codecs=\"mp4a.40.2\"", "cbc"),
                format("hls", "audio/mpegurl", "abr"),
                aac));

        assertSame(aac, chosen);
        assertEquals("A:aac160", handler.buildFormatIdentifier(chosen));
    }

    @Test
    public void encryptedOnlyIsProtected()
    {
        SoundCloudTrackFormat ctr = format("ctr-encrypted-hls", AAC, "ctrlookup");

        SoundCloudTrackFormat chosen = handler.chooseBestFormat(list(
                format("cbc-encrypted-hls", AAC, "cbclookup"), ctr));

        assertEquals("X:cbclookup", handler.buildFormatIdentifier(chosen));
        assertEquals("X:ctrlookup", handler.buildFormatIdentifier(handler.chooseBestFormat(list(ctr))));
    }

    @Test
    public void encryptedWithOnlyAdaptiveIsStillProtected()
    {
        SoundCloudTrackFormat chosen = handler.chooseBestFormat(list(
                format("hls", "audio/mpegurl", "abr"), format("ctr-encrypted-hls", AAC, "ctr")));

        assertEquals("X:ctr", handler.buildFormatIdentifier(chosen));
    }

    @Test
    public void adaptiveOnlyIsTodaysError()
    {
        assertTodaysError(list(format("hls", "audio/mpegurl", "abr")));
        // not hls, or not mp4: not something the AAC decoder can take either
        assertTodaysError(list(
                format("hls", "audio/mpegurl", "abr"),
                format("progressive", "audio/mp4", "prog"),
                format("hls", "audio/flac", "flac")));
    }

    @Test
    public void emptyIsTodaysError()
    {
        assertTodaysError(Collections.emptyList());
    }

    private void assertTodaysError(List<SoundCloudTrackFormat> formats)
    {
        RuntimeException today = null;
        try
        {
            new DefaultSoundCloudFormatHandler().chooseBestFormat(formats);
        }
        catch(RuntimeException e)
        {
            today = e;
        }
        assertNotNull("lavaplayer's default handler is expected to refuse this list", today);

        RuntimeException ours = assertThrows(RuntimeException.class, () -> handler.chooseBestFormat(formats));

        assertEquals(today.getClass(), ours.getClass());
        assertEquals(today.getMessage(), ours.getMessage());
    }

    // ---- where identifiers go

    @Test
    public void aacIdentifierFailsFast()
    {
        // lavaplayer's SoundCloud track would play it with an HTTP interface that breaks SoundCloud's signed links
        FriendlyException e = assertThrows(FriendlyException.class, () -> handler.getM3uInfo("A:u"));

        assertEquals("SoundCloud sent a stream the bot can't read yet", e.getMessage());
        assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
        assertNull(e.getCause());
    }

    @Test
    public void everyOtherIdentifierIsTheDefaultHandlersAnswer()
    {
        DefaultSoundCloudFormatHandler lavaplayer = new DefaultSoundCloudFormatHandler();
        for(String id : Arrays.asList("O:x", "U:x", "M:x", "N:x", "zzz", ""))
        {
            SoundCloudM3uInfo expected = lavaplayer.getM3uInfo(id);
            SoundCloudM3uInfo actual = handler.getM3uInfo(id);
            assertEquals(id, expected == null, actual == null);
            if(expected != null)
                assertEquals(id, expected.lookupUrl, actual.lookupUrl);
            assertEquals(id, lavaplayer.getMp3LookupUrl(id), handler.getMp3LookupUrl(id));
        }
        assertEquals("x", handler.getMp3LookupUrl("M:x"));
        assertNull(handler.getMp3LookupUrl("A:x"));
    }

    @Test
    public void protectedNeverPlaysDirectly()
    {
        for(Runnable lookup : Arrays.<Runnable>asList(() -> handler.getM3uInfo("X:u"), () -> handler.getMp3LookupUrl("X:u")))
        {
            FriendlyException e = assertThrows(FriendlyException.class, lookup::run);

            assertEquals(PROTECTED, e.getMessage());
            assertEquals(FriendlyException.Severity.COMMON, e.severity);
            assertNull(e.getCause());
        }
    }

    @Test
    public void isProtectedFollowsTheIdentifier()
    {
        assertTrue(CksSoundCloudFormatHandler.isProtected(track("X:https://api-v2.soundcloud.com/media/1/2/stream/hls")));
        assertFalse(CksSoundCloudFormatHandler.isProtected(track("A:https://api-v2.soundcloud.com/media/1/2/stream/hls")));
        assertFalse(CksSoundCloudFormatHandler.isProtected(track("M:x")));
        assertFalse(CksSoundCloudFormatHandler.isProtected(track("dQw4w9WgXcQ")));
    }

    private static AudioTrack track(String identifier)
    {
        return new BaseAudioTrack(new AudioTrackInfo("Song", "Artist", 1000, identifier, false, "https://soundcloud.com/a/b", null, null))
        {
            @Override
            public void process(LocalAudioTrackExecutor executor)
            {
                throw new UnsupportedOperationException();
            }
        };
    }
}
