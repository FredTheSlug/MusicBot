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
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * A SoundCloud track played from its plain AAC stream ({@code A:} + lookup URL, see
 * {@link CksSoundCloudFormatHandler}). It keeps the SoundCloud {@link AudioTrackInfo} and names the bot's SoundCloud
 * source as its own, so it is stored and restored like any SoundCloud track.
 * <p>
 * Why it exists: lavaplayer's SoundCloud source adds {@code client_id} to every request whose host is not on
 * {@code sndcdn.com}, and hands that HTTP interface to the track that plays an HLS stream. The api-v2 lookup needs
 * the client id (401 without it), but the AAC streams live on another host, on signed links (playlist, init file,
 * chunks) that SoundCloud answers with 403 as soon as anything is added to them, a client id included. So this track
 * plays the stream itself, with lavaplayer's own {@link SoundCloudM3uAudioTrack} and {@link SoundCloudAacSegmentDecoder}:
 * the client id goes into the lookup URL, and every request (lookup, playlist, chunks, and the init file
 * {@link HlsInitLookup} fetches) goes through a plain HTTP interface that adds nothing.
 * <p>
 * The lookup URL with the client id and the signed links are never logged; the info's identifier (logged by
 * lavaplayer when a play fails) holds the lookup URL without it.
 */
public class SoundCloudAacTrack extends DelegatedAudioTrack
{
    private final Supplier<String> clientId;
    private final Supplier<HttpInterface> http;
    private final AudioSourceManager source;

    /**
     * @param soundCloudInfo the SoundCloud track's info, kept as this track's own; its identifier is {@code A:} +
     *                       lookup URL
     * @param clientId       SoundCloud's client id (lavaplayer's SoundCloud source's), read when the track starts
     * @param http           a plain HTTP interface that leaves links alone, one per play (this track closes it)
     * @param source         the source this track belongs to: the bot's SoundCloud source, which stores and restores it
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

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception
    {
        String lookupUrl = withClientId(getIdentifier().substring(CksSoundCloudFormatHandler.AAC_PREFIX.length()),
                clientId.get());
        try(HttpInterface plain = http.get())
        {
            SoundCloudM3uInfo stream = new SoundCloudM3uInfo(lookupUrl, chunks ->
                    new SoundCloudAacSegmentDecoder(chunks, () -> HlsInitLookup.loadInit(plain, lookupUrl)));
            processDelegate(new SoundCloudM3uAudioTrack(getInfo(), plain, stream), executor);
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
}
