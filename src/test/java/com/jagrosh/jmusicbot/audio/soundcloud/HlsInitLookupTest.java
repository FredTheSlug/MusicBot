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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 * The pure parts ({@code initUri}, {@code redact}) on playlist text like SoundCloud's, and {@code loadInit} against
 * a loopback server that plays SoundCloud's three answers: the lookup JSON, the playlist and the init file.
 */
public class HlsInitLookupTest
{
    private static final String CANT_READ = "SoundCloud sent a stream the bot can't read yet";
    private static final String SIGNED = "?Policy=eyJTZWNyZXQ&Signature=SECRET-SIGNATURE&Key-Pair-Id=K2ABC";

    // ---- initUri

    @Test
    public void initUriQuotedAbsoluteAndRelative()
    {
        String playlistUrl = "https://cf-hls-media.sndcdn.com/playlist/abc/aac_160k/playlist.m3u8" + SIGNED;

        assertEquals("https://cf-hls-media.sndcdn.com/init/abc/init.mp4" + SIGNED,
                HlsInitLookup.initUri(playlist("#EXT-X-MAP:URI=\"https://cf-hls-media.sndcdn.com/init/abc/init.mp4" + SIGNED + "\""), playlistUrl));
        assertEquals("https://cf-hls-media.sndcdn.com/playlist/abc/aac_160k/init.mp4",
                HlsInitLookup.initUri(playlist("#EXT-X-MAP:URI=\"init.mp4\""), playlistUrl));
        assertEquals("https://cf-hls-media.sndcdn.com/playlist/abc/init.mp4",
                HlsInitLookup.initUri(playlist("#EXT-X-MAP:URI=\"../init.mp4\""), playlistUrl));
        assertEquals("https://cf-hls-media.sndcdn.com/init.mp4",
                HlsInitLookup.initUri(playlist("#EXT-X-MAP:URI=\"/init.mp4\""), playlistUrl));
    }

    @Test
    public void initUriFindsTheMapAmongOtherLinesAndAttributes()
    {
        String text = "#EXTM3U\r\n#EXT-X-VERSION:6\r\n#EXT-X-TARGETDURATION:10\r\n"
                + "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"\r\n#EXTINF:10.0,\r\ndata000.m4s\r\n#EXT-X-ENDLIST\r\n";

        assertEquals("https://example.test/a/init.mp4", HlsInitLookup.initUri(text, "https://example.test/a/playlist.m3u8"));
    }

    @Test
    public void missingMapIsTheCantReadYetError()
    {
        for(String text : new String[]{"", "#EXTM3U\n#EXTINF:10.0,\ndata000.m4s\n#EXT-X-ENDLIST\n", "#EXT-X-MAP:BYTERANGE=\"720@0\"", "#EXT-X-MAP:URI=\"\""})
        {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> HlsInitLookup.initUri(text, "https://example.test/playlist.m3u8"));

            assertEquals(CANT_READ, e.getMessage());
        }
    }

    @Test
    public void aMalformedMapUriNeverEchoesTheSignedLink()
    {
        // a space is not allowed in a URI; java.net.URI would put the whole string in its message
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> HlsInitLookup.initUri(
                playlist("#EXT-X-MAP:URI=\"https://example.test/in it.mp4" + SIGNED + "\""), "https://example.test/playlist.m3u8"));

        assertNull(e.getCause());
        assertFalse(e.getMessage(), e.getMessage().contains("SECRET"));
    }

    // ---- redact

    @Test
    public void redactDropsQueryStringsAndMediaLines()
    {
        String text = "#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"https://cf-hls-media.sndcdn.com/init.mp4" + SIGNED + "\",BYTERANGE=\"720@0\"\r\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cf-hls-media.sndcdn.com/key" + SIGNED + "\",IV=0x01\n"
                + "#EXTINF:10.0,\n"
                + "https://cf-hls-media.sndcdn.com/data000.m4s" + SIGNED + "\n"
                + "\n"
                + "#EXT-X-ENDLIST\n";

        String redacted = HlsInitLookup.redact(text);

        assertEquals("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"https://cf-hls-media.sndcdn.com/init.mp4\",BYTERANGE=\"720@0\"\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cf-hls-media.sndcdn.com/key\",IV=0x01\n"
                + "#EXTINF:10.0,\n"
                + "#EXT-X-ENDLIST", redacted);
        assertFalse(redacted.contains("?"));
        assertFalse(redacted.contains("SECRET"));
        assertFalse(redacted.contains("data000"));
    }

    @Test
    public void redactOfNothingIsEmpty()
    {
        assertEquals("", HlsInitLookup.redact(""));
        assertEquals("", HlsInitLookup.redact("https://example.test/a.m4s?x=1\n"));
    }

    // ---- loadInit, over loopback HTTP

    private HttpServer server;
    private HttpInterface http;
    /** The server answers on its own thread. */
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requested = Collections.synchronizedList(new ArrayList<>());
    private ListAppender<ILoggingEvent> logged;
    private Logger lookupLog;

    private static final class Reply
    {
        final int status;
        final byte[] body;

        Reply(int status, byte[] body)
        {
            this.status = status;
            this.body = body;
        }
    }

    @Before
    public void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange ->
        {
            requested.add(exchange.getRequestURI().getRawPath());
            Reply reply = replies.getOrDefault(exchange.getRequestURI().getRawPath(), new Reply(404, new byte[0]));
            exchange.sendResponseHeaders(reply.status, reply.body.length == 0 ? -1 : reply.body.length);
            if(reply.body.length > 0)
                try(OutputStream out = exchange.getResponseBody())
                {
                    out.write(reply.body);
                }
            exchange.close();
        });
        server.start();
        http = HttpClientTools.createDefaultThreadLocalManager().getInterface();

        lookupLog = (Logger) LoggerFactory.getLogger(HlsInitLookup.class);
        logged = new ListAppender<>();
        logged.start();
        lookupLog.addAppender(logged);
        lookupLog.setLevel(Level.DEBUG);
    }

    @After
    public void stopServer() throws IOException
    {
        lookupLog.detachAppender(logged);
        lookupLog.setLevel(null);
        http.close();
        server.stop(0);
    }

    private String base()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void reply(String path, int status, String body)
    {
        replies.put(path, new Reply(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** SoundCloud's lookup answers {@code {"url": <playlist link>}}. */
    private void serveLookup(String playlistLink)
    {
        reply("/media/1/2/stream/hls", 200, "{\"url\":\"" + playlistLink + "\"}");
    }

    private String lookupUrl()
    {
        return base() + "/media/1/2/stream/hls";
    }

    @Test
    public void loadInitFollowsTheLookupToThePlaylistToTheInit() throws IOException
    {
        byte[] init = {0, 0, 0, 24, 'f', 't', 'y', 'p', (byte) 0xFF, (byte) 0x80, 0, 1};
        serveLookup(base() + "/playlist/abc/playlist.m3u8" + SIGNED);
        reply("/playlist/abc/playlist.m3u8", 200, playlist("#EXT-X-MAP:URI=\"init.mp4\""));
        replies.put("/playlist/abc/init.mp4", new Reply(200, init));

        assertArrayEquals(init, HlsInitLookup.loadInit(http, lookupUrl()));

        assertEquals(Arrays.asList("/media/1/2/stream/hls", "/playlist/abc/playlist.m3u8", "/playlist/abc/init.mp4"), requested);
    }

    @Test
    public void loadInitMissingMapIsTheCantReadYetErrorAndLogsOnlyTheTagLines()
    {
        serveLookup(base() + "/playlist.m3u8" + SIGNED);
        reply("/playlist.m3u8", 200, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"" + base() + "/key" + SIGNED + "\"\n#EXTINF:10.0,\n"
                + base() + "/data000.m4s" + SIGNED + "\n#EXT-X-ENDLIST\n");

        FriendlyException e = assertThrows(FriendlyException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));

        assertEquals(CANT_READ, e.getMessage());
        assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
        assertNotNull(e.getCause());
        assertEquals(1, logged.list.size());
        ILoggingEvent warning = logged.list.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        String text = warning.getFormattedMessage();
        assertTrue(text, text.contains("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"" + base() + "/key\""));
        assertTrue(text, text.contains("#EXT-X-ENDLIST"));
        assertNoSecrets(text, e);
    }

    @Test
    public void loadInitMalformedMapUriIsTheCantReadYetErrorToo()
    {
        serveLookup(base() + "/playlist.m3u8" + SIGNED);
        reply("/playlist.m3u8", 200, playlist("#EXT-X-MAP:URI=\"https://example.test/in it.mp4" + SIGNED + "\""));

        // not an IllegalArgumentException: the decoder only wraps IOExceptions, a raw one would reach the user as lavaplayer's generic error
        FriendlyException e = assertThrows(FriendlyException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));

        assertEquals(CANT_READ, e.getMessage());
        assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
        for(ILoggingEvent event : logged.list)
            assertNoSecrets(event.getFormattedMessage(), null);
        assertNoSecrets("", e);
    }

    @Test
    public void loadInitMalformedPlaylistLinkIsTheCantReadYetError()
    {
        serveLookup(base() + "/play list.m3u8" + SIGNED);

        FriendlyException e = assertThrows(FriendlyException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));

        assertEquals(CANT_READ, e.getMessage());
        assertNoSecrets("", e);
    }

    @Test
    public void loadInitMalformedLookupUrlIsTheCantReadYetError()
    {
        FriendlyException e = assertThrows(FriendlyException.class, () -> HlsInitLookup.loadInit(http, "http://not a url"));

        assertEquals(CANT_READ, e.getMessage());
    }

    @Test
    public void loadInitLookupWithoutAUrlIsAnIOException()
    {
        reply("/media/1/2/stream/hls", 200, "{}");

        assertThrows(IOException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));
    }

    @Test
    public void loadInitServerErrorsAreIOExceptions()
    {
        // lookup 404
        assertThrows(IOException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));

        // playlist 403
        serveLookup(base() + "/playlist.m3u8" + SIGNED);
        reply("/playlist.m3u8", 403, "denied");
        assertThrows(IOException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));

        // init 404
        reply("/playlist.m3u8", 200, playlist("#EXT-X-MAP:URI=\"init.mp4\""));
        assertThrows(IOException.class, () -> HlsInitLookup.loadInit(http, lookupUrl()));
    }

    // ---- helpers

    private static String playlist(String mapLine)
    {
        return "#EXTM3U\n#EXT-X-VERSION:6\n" + mapLine + "\n#EXTINF:10.0,\ndata000.m4s\n#EXT-X-ENDLIST\n";
    }

    /** Neither {@code text} nor any message along {@code failure}'s causes may hold a piece of the signed query string. */
    private static void assertNoSecrets(String text, Throwable failure)
    {
        assertFalse(text, text.contains("SECRET") || text.contains("Signature=") || text.contains("Policy="));
        for(Throwable t = failure; t != null; t = t.getCause())
        {
            String message = String.valueOf(t.getMessage());
            assertFalse(t + ": " + message, message.contains("SECRET") || message.contains("Signature=") || message.contains("Policy="));
        }
    }
}
