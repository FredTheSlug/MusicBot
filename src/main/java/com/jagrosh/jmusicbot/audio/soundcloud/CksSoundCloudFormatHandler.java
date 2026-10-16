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
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;

/**
 * Teaches lavaplayer's SoundCloud source the streams SoundCloud's api-v2 offers now. lavaplayer's default handler
 * only knows MP3 and Opus and fails a track that has neither with "Did not detect any supported formats"; this one
 * asks it first and only then looks at what else is on offer. The choice, in order:
 * <ol>
 *   <li>whatever the default handler chooses (progressive MP3, HLS MP3, HLS Opus), so a track that still has those
 *       plays as before;</li>
 *   <li>plain AAC: protocol {@code hls}, mime type {@code audio/mp4} (SoundCloud sends
 *       {@code audio/mp4; codecs="mp4a.40.2"}), identifier {@value #AAC_PREFIX} + lookup URL, played by
 *       {@link SoundCloudAacTrack};</li>
 *   <li>an encrypted stream ({@code cbc-encrypted-hls}, {@code ctr-encrypted-hls}) when that is all there is: it is
 *       chosen only to mark the track as protected, identifier {@value #PROTECTED_PREFIX} + lookup URL, so the track
 *       still loads with its SoundCloud details; nothing here can play it;</li>
 *   <li>otherwise the default handler's own error, unchanged. The adaptive {@code hls | audio/mpegurl} stream is
 *       never chosen.</li>
 * </ol>
 * lavaplayer's formats carry only protocol, mime type and lookup URL, not SoundCloud's preset ({@code aac_160k},
 * {@code aac_96k}), so among several plain AAC streams the first in SoundCloud's own order is taken. Identifiers
 * with any other prefix are built and routed by the default handler, exactly as before.
 * <p>
 * An {@code A:} identifier never plays through lavaplayer's SoundCloud track either: its HTTP interface adds a client
 * id to SoundCloud's signed AAC links and those then answer 403. AAC playback goes through
 * {@link SoundCloudAacTrack}, which {@link SoundCloudSource} swaps in; {@link #getM3uInfo} refuses an {@code A:}
 * identifier at once with "SoundCloud sent a stream the bot can't read yet", so a track that somehow was not swapped
 * fails clearly instead of with a 403.
 * <p>
 * An {@code X:} identifier never plays through lavaplayer: both lookups refuse it with {@value #PROTECTED_MESSAGE}.
 * The source that loads the track is expected to have swapped it for another one by then.
 */
public class CksSoundCloudFormatHandler implements SoundCloudFormatHandler
{
    /** Identifier prefix of a plain AAC stream; the rest is the stream's lookup URL. */
    public static final String AAC_PREFIX = "A:";
    /** Identifier prefix of a track SoundCloud only offers encrypted; the rest is the lookup URL of one such stream. */
    public static final String PROTECTED_PREFIX = "X:";
    static final String PROTECTED_MESSAGE = "SoundCloud protects this track and YouTube had no match";

    private final SoundCloudFormatHandler lavaplayer = new DefaultSoundCloudFormatHandler();

    /** Whether the track was loaded from a stream SoundCloud only offers encrypted. */
    public static boolean isProtected(AudioTrack track)
    {
        String identifier = track.getIdentifier();
        return identifier != null && identifier.startsWith(PROTECTED_PREFIX);
    }

    @Override
    public SoundCloudTrackFormat chooseBestFormat(List<SoundCloudTrackFormat> formats)
    {
        RuntimeException todaysError;
        try
        {
            return lavaplayer.chooseBestFormat(formats);
        }
        catch(RuntimeException e)
        {
            todaysError = e;
        }
        for(SoundCloudTrackFormat format : formats)
            if(isPlainAac(format))
                return format;
        for(SoundCloudTrackFormat format : formats)
            if(isEncrypted(format))
                return format;
        throw todaysError;
    }

    @Override
    public String buildFormatIdentifier(SoundCloudTrackFormat format)
    {
        if(isPlainAac(format))
            return AAC_PREFIX + format.getLookupUrl();
        if(isEncrypted(format))
            return PROTECTED_PREFIX + format.getLookupUrl();
        return lavaplayer.buildFormatIdentifier(format);
    }

    @Override
    public SoundCloudM3uInfo getM3uInfo(String identifier)
    {
        if(identifier.startsWith(PROTECTED_PREFIX))
            throw protectedTrack();
        if(identifier.startsWith(AAC_PREFIX))
            throw new FriendlyException(SoundCloudAacSegmentDecoder.CANT_READ, FriendlyException.Severity.SUSPICIOUS,
                    null);
        return lavaplayer.getM3uInfo(identifier);
    }

    @Override
    public String getMp3LookupUrl(String identifier)
    {
        if(identifier.startsWith(PROTECTED_PREFIX))
            throw protectedTrack();
        return lavaplayer.getMp3LookupUrl(identifier);
    }

    /**
     * Plain AAC: HLS in an MP4 container. The mime type may carry parameters ({@code ; codecs="mp4a.40.2"}) and any
     * case; the adaptive {@code audio/mpegurl} stream and encrypted protocols don't match.
     */
    private static boolean isPlainAac(SoundCloudTrackFormat format)
    {
        if(!"hls".equals(format.getProtocol()) || format.getMimeType() == null)
            return false;
        String mimeType = format.getMimeType();
        int parameters = mimeType.indexOf(';');
        return "audio/mp4".equalsIgnoreCase((parameters < 0 ? mimeType : mimeType.substring(0, parameters)).trim());
    }

    private static boolean isEncrypted(SoundCloudTrackFormat format)
    {
        return format.getProtocol() != null && format.getProtocol().contains("encrypted");
    }

    private static FriendlyException protectedTrack()
    {
        return new FriendlyException(PROTECTED_MESSAGE, FriendlyException.Severity.COMMON, null);
    }
}
