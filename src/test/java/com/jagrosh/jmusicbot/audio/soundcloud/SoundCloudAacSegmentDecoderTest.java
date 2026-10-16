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

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AllocatingAudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Plays the generated SoundCloud-style chunks in {@code src/test/resources/soundcloud-aac} (see its README) through
 * the decoder, calling it the way lavaplayer's {@code SoundCloudM3uAudioTrack} does: {@code prepareStream(true)}
 * first, then {@code playStream} with one stream of every chunk from the current one to the end; a seek is
 * {@code resetStream}, {@code prepareStream} and {@code playStream} again from the chunk that holds the target.
 * The tests run at Discord's output format (48 kHz 16-bit PCM, {@link StandardAudioDataFormats#DISCORD_PCM_S16_LE}),
 * which is what the bot plays at: lavaplayer resamples the 44.1 kHz source to it. Lengths are counted in the output
 * format's own samples and allow 50 ms: lavaplayer's AAC decoder spends the first AAC frame (23 ms) of every
 * {@code playStream} on finding the stream's format, and the last unfinished 20 ms output frame is never sent.
 */
public class SoundCloudAacSegmentDecoderTest
{
    private static final String CANT_READ = "SoundCloud sent a stream the bot can't read yet";
    private static final double TOLERANCE_MS = 50;
    private static final AudioDataFormat FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_LE;
    /** Room for the whole fixture (under 4 s), so playStream never waits for a listener. */
    private static final int BUFFER_MS = 10_000;

    private static byte[] init;
    private static byte[] chunk0;
    private static byte[] chunk1;

    @BeforeClass
    public static void loadFixture() throws IOException
    {
        init = resource("init.mp4");
        chunk0 = resource("chunk0.m4s");
        chunk1 = resource("chunk1.m4s");
    }

    private static byte[] resource(String name) throws IOException
    {
        try(InputStream in = SoundCloudAacSegmentDecoderTest.class.getResourceAsStream("/soundcloud-aac/" + name))
        {
            assertNotNull("missing fixture " + name, in);
            return in.readAllBytes();
        }
    }

    /** The types of a file's top-level MP4 boxes, in order. */
    private static List<String> topLevelBoxes(byte[] file)
    {
        List<String> types = new ArrayList<>();
        ByteBuffer data = ByteBuffer.wrap(file);
        while(data.remaining() >= 8)
        {
            int start = data.position();
            int size = data.getInt();
            byte[] type = new byte[4];
            data.get(type);
            assertTrue("box too small at " + start, size >= 8);
            types.add(new String(type, StandardCharsets.ISO_8859_1));
            data.position(start + size);
        }
        assertEquals("bytes after the last box", 0, data.remaining());
        return types;
    }

    /** The body (after the 8-byte header) of a file's first top-level box of {@code type}. */
    private static ByteBuffer box(byte[] file, String type)
    {
        ByteBuffer data = ByteBuffer.wrap(file);
        while(data.remaining() >= 8)
        {
            int start = data.position();
            int size = data.getInt();
            byte[] found = new byte[4];
            data.get(found);
            if(type.equals(new String(found, StandardCharsets.ISO_8859_1)))
            {
                ByteBuffer body = data.slice();
                body.limit(size - 8);
                return body;
            }
            data.position(start + size);
        }
        throw new AssertionError("no " + type + " box");
    }

    /** A chunk's length in milliseconds, from its {@code sidx}. */
    private static double chunkMillis(byte[] chunk)
    {
        ByteBuffer sidx = box(chunk, "sidx");
        int version = sidx.get() & 0xFF;
        sidx.position(4 + 4);
        long timescale = Integer.toUnsignedLong(sidx.getInt());
        sidx.position(sidx.position() + (version == 0 ? 8 : 16) + 2);
        int references = sidx.getShort() & 0xFFFF;
        long duration = 0;
        for(int i = 0; i < references; i++)
        {
            sidx.getInt();
            duration += Integer.toUnsignedLong(sidx.getInt());
            sidx.getInt();
        }
        return duration * 1000.0 / timescale;
    }

    /** Where a chunk starts, the way lavaplayer reads it from the playlist's {@code #EXTINF} (whole milliseconds). */
    private static long startOfChunk1()
    {
        return (long) chunkMillis(chunk0);
    }

    /** Hands out every chunk from {@code next} on as one stream, like lavaplayer's segment tracker. */
    private static final class Segments implements Supplier<SeekableInputStream>
    {
        private final List<byte[]> chunks;
        private int next;

        Segments(byte[]... chunks)
        {
            this.chunks = Arrays.asList(chunks);
        }

        @Override
        public SeekableInputStream get()
        {
            List<InputStream> rest = new ArrayList<>();
            for(int i = next; i < chunks.size(); i++)
                rest.add(new ByteArrayInputStream(chunks.get(i)));
            next = chunks.size();
            return new NonSeekableInputStream(new SequenceInputStream(Collections.enumeration(rest)));
        }
    }

    /** What one {@code playStream} call wrote. */
    private static final class Played
    {
        private double millis;
        private Long firstTimecode;
    }

    private static Played play(SoundCloudAacSegmentDecoder decoder, long startPosition, long desiredPosition) throws Exception
    {
        AllocatingAudioFrameBuffer buffer = new AllocatingAudioFrameBuffer(BUFFER_MS, FORMAT, new AtomicBoolean());
        AudioProcessingContext context = new AudioProcessingContext(new AudioConfiguration(), buffer, new AudioPlayerOptions(), FORMAT);
        decoder.playStream(context, startPosition, desiredPosition);
        Played played = new Played();
        long samples = 0;
        AudioFrame frame;
        while((frame = buffer.provide()) != null)
        {
            if(played.firstTimecode == null)
                played.firstTimecode = frame.getTimecode();
            samples += frame.getDataLength() / (2 * FORMAT.channelCount);
        }
        played.millis = samples * 1000.0 / FORMAT.sampleRate;
        return played;
    }

    /** What lavaplayer's segment tracker does for a seek that lands in chunk {@code index}, starting at {@code startMillis}. */
    private static void seek(SoundCloudAacSegmentDecoder decoder, Segments segments, int index, long startMillis) throws IOException
    {
        decoder.resetStream();
        segments.next = index;
        decoder.prepareStream(startMillis == 0);
    }

    private static void assertCantReadYet(SoundCloudAacSegmentDecoder decoder)
    {
        try
        {
            decoder.prepareStream(true);
            fail("expected the can't-read-yet error");
        }
        catch(FriendlyException e)
        {
            assertEquals(CANT_READ, e.getMessage());
            assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
        }
    }

    @Test
    public void fixtureLooksLikeSoundCloud()
    {
        assertEquals(Arrays.asList("ftyp", "moov"), topLevelBoxes(init));
        assertEquals(Arrays.asList("styp", "sidx", "moof", "mdat"), topLevelBoxes(chunk0));
        assertEquals(Arrays.asList("styp", "sidx", "moof", "mdat"), topLevelBoxes(chunk1));
        assertEquals(2020, chunkMillis(chunk0), 50);
        assertEquals(1900, chunkMillis(chunk1), 50);
    }

    @Test
    public void decodesTwoChunksToTheirLength() throws Exception
    {
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(new Segments(chunk0, chunk1), () -> init))
        {
            decoder.prepareStream(true);
            Played played = play(decoder, 0, 0);
            assertEquals(chunkMillis(chunk0) + chunkMillis(chunk1), played.millis, TOLERANCE_MS);
            assertEquals(Long.valueOf(0), played.firstTimecode);
        }
    }

    @Test
    public void seekInsideAChunkStartsAtTheRequestedSample() throws Exception
    {
        Segments segments = new Segments(chunk0, chunk1);
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(segments, () -> init))
        {
            decoder.prepareStream(true);
            long start = startOfChunk1();
            seek(decoder, segments, 1, start);
            Played played = play(decoder, start, start + 700);
            assertEquals(chunkMillis(chunk1) - 700, played.millis, TOLERANCE_MS);
            assertEquals(Long.valueOf(start + 700), played.firstTimecode);
        }
    }

    @Test
    public void seekToAChunkBoundary() throws Exception
    {
        Segments segments = new Segments(chunk0, chunk1);
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(segments, () -> init))
        {
            decoder.prepareStream(true);
            play(decoder, 0, 0);

            long start = startOfChunk1();
            seek(decoder, segments, 1, start);
            Played chunk1Only = play(decoder, start, start);
            assertEquals(chunkMillis(chunk1), chunk1Only.millis, TOLERANCE_MS);
            assertEquals(Long.valueOf(start), chunk1Only.firstTimecode);

            seek(decoder, segments, 0, 0);
            Played fromTheStart = play(decoder, 0, 0);
            assertEquals(chunkMillis(chunk0) + chunkMillis(chunk1), fromTheStart.millis, TOLERANCE_MS);
            assertEquals(Long.valueOf(0), fromTheStart.firstTimecode);
        }
    }

    @Test
    public void initIsLoadedOncePerTrack() throws Exception
    {
        AtomicInteger loads = new AtomicInteger();
        Segments segments = new Segments(chunk0, chunk1);
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(segments, () ->
        {
            loads.incrementAndGet();
            return init;
        }))
        {
            decoder.prepareStream(true);
            play(decoder, 0, 0);
            long start = startOfChunk1();
            seek(decoder, segments, 1, start);
            Played played = play(decoder, start, start);
            assertEquals(chunkMillis(chunk1), played.millis, TOLERANCE_MS);
        }
        assertEquals(1, loads.get());
    }

    @Test
    public void missingInitIsTheCantReadYetError() throws Exception
    {
        IOException forbidden = new IOException("init.mp4 answered 403");
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(new Segments(chunk0), () ->
        {
            throw forbidden;
        }))
        {
            decoder.prepareStream(true);
            fail("expected the can't-read-yet error");
        }
        catch(FriendlyException e)
        {
            assertEquals(CANT_READ, e.getMessage());
            assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
            assertSame(forbidden, e.getCause());
        }
    }

    @Test
    public void initWithoutAacIsTheCantReadYetError() throws Exception
    {
        byte[] ftypOnly = Arrays.copyOf(init, ByteBuffer.wrap(init).getInt());
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(new Segments(chunk0), () -> ftypOnly))
        {
            assertCantReadYet(decoder);
        }
        // a chunk where the init should be: no moov at all
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(new Segments(chunk0), () -> chunk0))
        {
            assertCantReadYet(decoder);
        }
    }

    @Test
    public void friendlyInitErrorsPassThrough() throws Exception
    {
        FriendlyException own = new FriendlyException("SoundCloud is down", FriendlyException.Severity.COMMON, null);
        try(SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(new Segments(chunk0), () ->
        {
            throw own;
        }))
        {
            decoder.prepareStream(true);
            fail("expected the loader's own error");
        }
        catch(FriendlyException e)
        {
            assertSame(own, e);
        }
    }
}
