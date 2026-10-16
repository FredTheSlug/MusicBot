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

import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudHelper;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds and downloads the init file ({@code ftyp} + {@code moov}) of one SoundCloud AAC stream: the file its HLS
 * playlist names in {@code #EXT-X-MAP:URI="…"}, which lavaplayer's own HLS code never reads. The stream's lookup URL
 * (the api-v2 {@code …/stream/hls} link inside an {@code A:} identifier) answers {@code {"url": <playlist link>}};
 * the playlist link and the init link are signed, so their query strings are never logged or put in an error message.
 * <p>
 * {@link #loadInit} fails in exactly two ways, because that is all the AAC decoder takes from its loader: an
 * {@link IOException} (a download that did not work: the decoder turns it into the "can't read yet" error) or a
 * {@link FriendlyException} with that message itself. Anything else that goes wrong on the way, such as a malformed
 * link, is turned into the second kind here.
 */
public final class HlsInitLookup
{
    private static final Logger LOG = LoggerFactory.getLogger(HlsInitLookup.class);
    private static final String MAP_TAG = "#EXT-X-MAP:";
    /** The URI attribute of a tag: always a quoted string in HLS. */
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("[:,]URI=\"([^\"]+)\"");
    /** A query string inside a tag line: from the {@code ?} up to the closing quote or the next blank. */
    private static final Pattern QUERY_STRING = Pattern.compile("\\?[^\"\\s]*");

    private HlsInitLookup()
    {
    }

    /**
     * The init file's link: the {@code URI} of the playlist's first {@code #EXT-X-MAP}, resolved against
     * {@code playlistUrl} when it is relative.
     *
     * @throws IllegalStateException with the "can't read yet" message when the playlist has no usable map
     * @throws IllegalArgumentException when a link is malformed (its message leaves the link out)
     */
    public static String initUri(String playlistText, String playlistUrl)
    {
        Optional<String> mapUri = playlistText.lines()
                .filter(line -> line.startsWith(MAP_TAG))
                .map(URI_ATTRIBUTE::matcher)
                .filter(Matcher::find)
                .map(attribute -> attribute.group(1))
                .findFirst();
        if(!mapUri.isPresent())
            throw new IllegalStateException(SoundCloudAacSegmentDecoder.CANT_READ);
        return uri(playlistUrl).resolve(uri(mapUri.get())).toString();
    }

    /**
     * What is safe to log of a playlist: its {@code #} lines (tags and comments, so no media link) with every
     * query string cut off, one per line.
     */
    public static String redact(String playlistText)
    {
        return playlistText.lines()
                .filter(line -> line.startsWith("#"))
                .map(line -> QUERY_STRING.matcher(line).replaceAll(""))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Downloads the init file of the stream behind {@code lookupUrl}: lookup, then playlist, then the file its
     * map names. A playlist with no usable map is logged ({@link #redact redacted}, at WARN) and fails the track.
     *
     * @param http what to download with; the caller closes it. SoundCloud answers 403 when anything is added to the
     *             signed playlist and init links, so {@link SoundCloudAacTrack} passes an interface that adds nothing
     *             to them ({@link SoundCloudStreamFilter}) and puts the client id the lookup needs into
     *             {@code lookupUrl} itself
     * @throws IOException when SoundCloud can't be reached or answers with an error
     * @throws FriendlyException "SoundCloud sent a stream the bot can't read yet" for everything else
     */
    public static byte[] loadInit(HttpInterface http, String lookupUrl) throws IOException
    {
        String playlistUrl;
        String playlist;
        try
        {
            playlistUrl = SoundCloudHelper.loadPlaybackUrl(http, lookupUrl);
            if(playlistUrl == null || playlistUrl.isEmpty())
                throw new IOException("SoundCloud gave no playlist link for the stream");
            playlist = new String(get(http, playlistUrl, "playlist"), StandardCharsets.UTF_8);
        }
        catch(RuntimeException e)
        {
            throw cantRead(e);
        }

        String initUri;
        try
        {
            initUri = initUri(playlist, playlistUrl);
        }
        catch(RuntimeException e)
        {
            LOG.warn("SoundCloud playlist has no usable #EXT-X-MAP; its tag lines were:\n{}", redact(playlist));
            throw cantRead(e);
        }

        try
        {
            return get(http, initUri, "init file");
        }
        catch(RuntimeException e)
        {
            throw cantRead(e);
        }
    }

    /** The failure the decoder reports for a stream it can't read; {@code cause} is kept for the log. */
    static FriendlyException cantRead(Throwable cause)
    {
        return new FriendlyException(SoundCloudAacSegmentDecoder.CANT_READ, FriendlyException.Severity.SUSPICIOUS, cause);
    }

    private static byte[] get(HttpInterface http, String link, String what) throws IOException
    {
        try(CloseableHttpResponse response = http.execute(new HttpGet(uri(link))))
        {
            int status = response.getStatusLine().getStatusCode();
            if(!HttpClientTools.isSuccessWithContent(status))
                throw new IOException("SoundCloud answered " + status + " for the " + what);
            return EntityUtils.toByteArray(response.getEntity());
        }
    }

    /** As {@link URI#create}, but a malformed link doesn't end up in the message: it may be signed. */
    private static URI uri(String link)
    {
        try
        {
            return new URI(link);
        }
        catch(URISyntaxException e)
        {
            throw new IllegalArgumentException("malformed link");
        }
    }
}
