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

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A SoundCloud track played from its plain AAC stream ({@code A:} + lookup URL, see
 * {@link CksSoundCloudFormatHandler}). It keeps the SoundCloud {@link AudioTrackInfo} and names the bot's SoundCloud
 * source as its own, which also decodes it (see {@link SoundCloudSource}).
 * <p>
 * Why it exists: lavaplayer's SoundCloud source adds {@code client_id} to every request whose host is not on
 * {@code sndcdn.com}, and hands that HTTP interface to the track that plays an HLS stream. The api-v2 lookup needs
 * the client id (401 without it), but the AAC streams live on another host, on signed links (playlist, init file,
 * chunks) that SoundCloud answers with 403 as soon as anything is added to them, a client id included. So this track
 * plays the stream itself, with lavaplayer's own {@link SoundCloudM3uAudioTrack} and {@link SoundCloudAacSegmentDecoder}:
 * the client id goes into the lookup URL, and every request (lookup, playlist, chunks, and the init file
 * {@link HlsInitLookup} fetches) goes through the HTTP interface it is given, which adds nothing to a signed link
 * ({@link SoundCloudStreamFilter}).
 * <p>
 * Links stay out of the bot's logs with their query strings: a play that fails is rethrown with every query string
 * cut from the messages along its causes (lavaplayer puts a failing chunk's whole signed link in its message), and
 * the info's identifier, which lavaplayer logs with the failure, holds the lookup URL without the client id.
 */
public class SoundCloudAacTrack extends DelegatedAudioTrack
{
    static final String NO_CLIENT_ID = "SoundCloud can't be reached right now";
    /** A query string inside a message: from the {@code ?} up to the next blank or quote. */
    private static final Pattern QUERY_STRING = Pattern.compile("\\?[^\\s\"]*");
    /** How many causes deep a failure is looked at; a cause chain is never longer in practice. */
    private static final int MAX_CAUSES = 32;

    private final Supplier<String> clientId;
    private final Supplier<HttpInterface> http;
    private final AudioSourceManager source;

    /**
     * @param soundCloudInfo the SoundCloud track's info, kept as this track's own; its identifier is {@code A:} +
     *                       lookup URL
     * @param clientId       SoundCloud's client id (lavaplayer's SoundCloud source's), read when the track starts
     * @param http           an HTTP interface that leaves signed links alone, one per play (this track closes it)
     * @param source         the source this track belongs to: the bot's SoundCloud source
     */
    public SoundCloudAacTrack(AudioTrackInfo soundCloudInfo, Supplier<String> clientId, Supplier<HttpInterface> http,
            AudioSourceManager source)
    {
        super(soundCloudInfo);
        this.clientId = clientId;
        this.http = http;
        this.source = source;
    }

    /** {@code lookupUrl} with {@code client_id} added to its query (a new one when it has none). */
    static String withClientId(String lookupUrl, String clientId)
    {
        return lookupUrl + (lookupUrl.indexOf('?') < 0 ? "?" : "&") + "client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
    }

    /**
     * @throws FriendlyException {@value #NO_CLIENT_ID} when there is no client id; whatever else the play fails with,
     *                           with the query strings cut from its messages
     */
    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception
    {
        String id = clientId.get();
        if(id == null || id.isEmpty())
            throw new FriendlyException(NO_CLIENT_ID, FriendlyException.Severity.SUSPICIOUS, null);
        String lookupUrl = withClientId(getIdentifier().substring(CksSoundCloudFormatHandler.AAC_PREFIX.length()), id);
        try(HttpInterface plain = http.get())
        {
            SoundCloudM3uInfo stream = new SoundCloudM3uInfo(lookupUrl, chunks ->
                    new SoundCloudAacSegmentDecoder(chunks, () -> HlsInitLookup.loadInit(plain, lookupUrl)));
            try
            {
                processDelegate(new SoundCloudM3uAudioTrack(getInfo(), plain, stream), executor);
            }
            catch(Exception e)
            {
                throw withoutQueryStrings(e);
            }
        }
    }

    @Override
    public AudioSourceManager getSourceManager()
    {
        return source;
    }

    @Override
    protected AudioTrack makeShallowClone()
    {
        return new SoundCloudAacTrack(getInfo(), clientId, http, source);
    }

    /**
     * {@code failure} itself when no message along its causes holds a query string, or when an interrupt is among them
     * (lavaplayer looks for one there to tell a stop or a seek from a failure); else a copy of the chain with the query
     * strings cut: a {@link FriendlyException} keeps its class and severity, any other cause becomes a
     * {@link RedactedException} that names its class in the message, and every copy keeps the original stack trace.
     */
    static Exception withoutQueryStrings(Exception failure)
    {
        boolean anyQuery = false;
        int depth = 0;
        for(Throwable cause = failure; cause != null && depth < MAX_CAUSES; cause = cause.getCause(), depth++)
        {
            if(cause instanceof InterruptedException || cause instanceof InterruptedIOException)
                return failure;
            anyQuery |= cause.getMessage() != null && QUERY_STRING.matcher(cause.getMessage()).find();
        }
        return anyQuery ? (Exception) redacted(failure, 0) : failure;
    }

    private static Throwable redacted(Throwable original, int depth)
    {
        Throwable cause = original.getCause() == null || depth + 1 >= MAX_CAUSES ? null
                : redacted(original.getCause(), depth + 1);
        Throwable copy = original instanceof FriendlyException
                ? new FriendlyException(cut(original.getMessage()), ((FriendlyException) original).severity, cause)
                : new RedactedException(original, cause);
        copy.setStackTrace(original.getStackTrace());
        return copy;
    }

    private static String cut(String message)
    {
        return message == null ? null : QUERY_STRING.matcher(message).replaceAll("");
    }

    /** Stands in for a cause whose message held a link: its class name, then its message with query strings cut. */
    static final class RedactedException extends Exception
    {
        private RedactedException(Throwable original, Throwable cause)
        {
            super(original.getClass().getName()
                    + (original.getMessage() == null ? "" : ": " + cut(original.getMessage())), cause);
        }
    }
}
