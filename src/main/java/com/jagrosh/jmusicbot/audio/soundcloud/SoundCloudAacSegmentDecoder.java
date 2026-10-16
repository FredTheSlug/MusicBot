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

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAacTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackInfo;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudSegmentDecoder;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.function.Supplier;

/**
 * Plays SoundCloud's plain AAC streams ({@code hls}, {@code audio/mp4; codecs="mp4a.40.2"}). Each chunk of such a
 * stream ({@code styp} + {@code sidx} + {@code moof} + {@code mdat}) is a fragment of one MP4 file whose header
 * ({@code ftyp} + {@code moov}) sits in a separate init file named by the playlist's {@code #EXT-X-MAP}. lavaplayer's
 * {@code SoundCloudM3uAudioTrack} downloads the chunks but never the init file, so this decoder fetches the init once
 * per track through its {@link InitLoader}, puts it in front of the chunks and lets lavaplayer's own MP4 reader
 * ({@link MpegFileLoader}) and AAC decoder ({@link MpegAacTrackConsumer}) play the result.
 * <p>
 * What it relies on in the container:
 * <ul>
 *   <li>The init's {@code moov} carries an {@code mvex} (with its {@code trex}), the marker of a fragmented MP4. That is
 *       what makes lavaplayer's {@code MpegFragmentedFileTrackProvider} take the stream: {@link MpegFileLoader#loadReader}
 *       hands out that reader only when it saw the {@code mvex}; without one, lavaplayer falls back to its whole-file
 *       reader, which does not play {@code moof} chunks. A {@code sidx} is not required; SoundCloud's chunks carry
 *       one, but nothing here depends on it.</li>
 *   <li>The chunks are {@code moof} + {@code mdat} pairs, read once and only forward through the joined stream: nothing
 *       is seeked or read twice. A seek is a new stream starting at the chunk that holds the target.</li>
 * </ul>
 * <p>
 * How {@code SoundCloudM3uAudioTrack} drives it (one decoder per play of a track):
 * <ul>
 *   <li>{@link #prepareStream} when playback starts and again after every seek; the first call loads the init.</li>
 *   <li>{@link #playStream} once at the start and once after every seek. Its stream holds every chunk from the
 *       current one to the end of the track, joined; {@code startPosition} is where the first of them starts and
 *       {@code desiredPosition} where playback should start, which is inside that chunk after a seek. The audio in
 *       between is decoded and dropped, and the frames are timed from {@code desiredPosition}.</li>
 *   <li>{@link #resetStream} before every seek and when the track ends; {@link #close} is never called by lavaplayer.
 *       Neither has anything to do: each {@code playStream} opens and releases its own stream and AAC decoder.</li>
 * </ul>
 * An init that can't be loaded or holds no AAC track fails the track with
 * {@value #CANT_READ}; an error the {@link InitLoader} already made friendly is passed on unchanged.
 */
public class SoundCloudAacSegmentDecoder implements SoundCloudSegmentDecoder
{
    static final String CANT_READ = "SoundCloud sent a stream the bot can't read yet";
    /** The MP4 codec name of AAC. */
    private static final String AAC = "mp4a";

    private final Supplier<SeekableInputStream> nextStream;
    private final InitLoader initLoader;
    /** The init file, once loaded. */
    private byte[] init;

    /** Fetches the track's init file (the playlist's {@code #EXT-X-MAP}). */
    public interface InitLoader
    {
        byte[] load() throws IOException;
    }

    /**
     * {@code nextStream} is lavaplayer's: each call gives the chunks from the current one on. {@code init} fetches the
     * init file; the first {@link #prepareStream} calls it.
     */
    public SoundCloudAacSegmentDecoder(Supplier<SeekableInputStream> nextStream, InitLoader init)
    {
        this.nextStream = nextStream;
        this.initLoader = init;
    }

    /** Loads and checks the init file, the first time only; {@code beginning} makes no difference for AAC. */
    @Override
    public void prepareStream(boolean beginning)
    {
        if(init != null)
            return;
        byte[] loaded;
        try
        {
            loaded = initLoader.load();
        }
        catch(IOException e)
        {
            throw cantRead(e);
        }
        MpegFileLoader file = new MpegFileLoader(new NonSeekableInputStream(new ByteArrayInputStream(loaded)));
        try
        {
            file.parseHeaders();
        }
        catch(RuntimeException e)
        {
            throw cantRead(e);
        }
        aacTrack(file);
        init = loaded;
    }

    /** Nothing to reset: {@link #playStream} closes its stream and decoder itself, and the init stays for the track. */
    @Override
    public void resetStream()
    {
    }

    /** Plays the chunks of the next stream, from {@code desiredPosition}, until they end. */
    @Override
    public void playStream(AudioProcessingContext context, long startPosition, long desiredPosition) throws InterruptedException, IOException
    {
        try(SeekableInputStream chunks = nextStream.get())
        {
            InputStream joined = new SequenceInputStream(new ByteArrayInputStream(init), chunks);
            MpegFileLoader file = new MpegFileLoader(new NonSeekableInputStream(joined));
            file.parseHeaders();
            MpegAacTrackConsumer consumer = new MpegAacTrackConsumer(context, aacTrack(file));
            try
            {
                MpegFileTrackProvider reader = file.loadReader(consumer);
                if(reader == null)
                    throw cantRead(null);
                consumer.initialise();
                // always (lavaplayer's MP3 decoder does too), not only for a seek inside a chunk: it also makes the
                // frames' timecodes, which are the track's position, count from the chunk's start instead of from 0
                consumer.seekPerformed(desiredPosition, startPosition);
                // no consumer.flush(): the native AAC decoder answers it with endless output (lavaplayer's own MP4
                // track never calls it either), and nothing is held back: each AAC frame is decoded as it arrives
                reader.provideFrames();
            }
            finally
            {
                consumer.close();
            }
        }
    }

    /** Nothing to release, see {@link #resetStream}. */
    @Override
    public void close()
    {
    }

    private static MpegTrackInfo aacTrack(MpegFileLoader file)
    {
        for(MpegTrackInfo track : file.getTrackList())
            if(AAC.equals(track.codecName))
                return track;
        throw cantRead(null);
    }

    private static FriendlyException cantRead(Throwable cause)
    {
        return new FriendlyException(CANT_READ, FriendlyException.Severity.SUSPICIOUS, cause);
    }
}
