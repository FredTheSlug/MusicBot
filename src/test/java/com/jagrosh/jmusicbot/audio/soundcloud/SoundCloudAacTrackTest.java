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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextRetryCounter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 * Plays a SoundCloud AAC track end to end, through lavaplayer's real player, against a loopback server that answers
 * like SoundCloud does: the api-v2 lookup answers 401 without a {@code client_id} (or with one other than
 * {@link #acceptedClientId}, when a test sets it) and otherwise {@code {"url": <signed playlist link>}}; the signed
 * playlist, its init file and its chunks answer 403 as soon as a {@code client_id} is added to their links (or their
 * signature is missing, or a test lists them in {@link #forbidden}). The audio is the committed fixture in
 * {@code src/test/resources/soundcloud-aac} (see its README): 2.020136 s + 1.903084 s.
 */
public class SoundCloudAacTrackTest
{
    private static final String CLIENT_ID = "test-client-id";
    private static final String SIGNED = "?Policy=p&Signature=s";
    private static final String LOOKUP = "/lookup";
    private static final String PLAYLIST = "/media/playlist.m3u8";
    private static final String INIT = "/media/init.mp4";
    private static final String CHUNK0 = "/media/chunk0.m4s";
    private static final String CHUNK1 = "/media/chunk1.m4s";
    private static final double CHUNK0_MS = 2020.136;
    private static final double FIXTURE_MS = CHUNK0_MS + 1903.084;
    /** lavaplayer's SoundCloud user agent. */
    private static final String CHROME_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36";
    private static final double TOLERANCE_MS = 100;
    private static final long PLAY_TIMEOUT_MS = 30_000;

    private static final Map<String, byte[]> fixture = new ConcurrentHashMap<>();

    private HttpServer server;
    /** Every request the server got, as path plus raw query; the server answers on its own thread. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    /** The User-Agent of every request, in the same order. */
    private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
    /** The only client id the lookup takes; any when null. */
    private volatile String acceptedClientId;
    /** Media paths that answer 403 even when signed as listed. */
    private final Set<String> forbidden = ConcurrentHashMap.newKeySet();
    /** The links the client id refresh was asked to fetch, for sources made by {@link #source}. */
    private final List<String> refreshes = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public static void loadFixture() throws IOException
    {
        fixture.put(INIT, resource("init.mp4"));
        fixture.put(CHUNK0, resource("chunk0.m4s"));
        fixture.put(CHUNK1, resource("chunk1.m4s"));
    }

    private static byte[] resource(String name) throws IOException
    {
        try(InputStream in = SoundCloudAacTrackTest.class.getResourceAsStream("/soundcloud-aac/" + name))
        {
            assertNotNull("missing fixture " + name, in);
            return in.readAllBytes();
        }
    }

    @Before
    public void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange ->
        {
            URI uri = exchange.getRequestURI();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            requests.add(query == null ? path : path + "?" + query);
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            int status;
            byte[] body = new byte[0];
            if(LOOKUP.equals(path))
            {
                String clientId = clientIdIn(query);
                boolean accepted = clientId != null && (acceptedClientId == null || acceptedClientId.equals(clientId));
                status = accepted ? 200 : 401;
                if(status == 200)
                    body = ("{\"url\":\"" + base() + PLAYLIST + SIGNED + "\"}").getBytes(StandardCharsets.UTF_8);
            }
            else if(PLAYLIST.equals(path) || fixture.containsKey(path))
            {
                boolean signedAsListed = !forbidden.contains(path) && query != null && query.contains("Signature=s")
                        && !query.contains("client_id");
                status = signedAsListed ? 200 : 403;
                if(signedAsListed)
                    body = PLAYLIST.equals(path) ? playlist().getBytes(StandardCharsets.UTF_8) : fixture.get(path);
            }
            else
                status = 404;
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if(body.length > 0)
                try(OutputStream out = exchange.getResponseBody())
                {
                    out.write(body);
                }
            exchange.close();
        });
        server.start();
    }

    @After
    public void stopServer()
    {
        server.stop(0);
    }

    private String base()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The value of the query's {@code client_id}, or null when it has none. */
    private static String clientIdIn(String query)
    {
        if(query == null)
            return null;
        for(String parameter : query.split("&"))
            if(parameter.startsWith("client_id="))
                return parameter.substring("client_id=".length());
        return null;
    }

    /** The bot's SoundCloud source with this client id; its refresh only records the link it was given. */
    private SoundCloudSource source(Supplier<String> clientId)
    {
        Consumer<String> refresh = refreshes::add;
        return new SoundCloudSource(SoundCloudAudioSourceManager.createDefault(), clientId, refresh, null, null);
    }

    /** SoundCloud's AAC playlist: absolute signed links, the init file in {@code #EXT-X-MAP}. */
    private String playlist()
    {
        return "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-MAP:URI=\"" + base() + INIT + SIGNED + "\"\n"
                + "#EXTINF:2.020136,\n" + base() + CHUNK0 + SIGNED + "\n"
                + "#EXTINF:1.903084,\n" + base() + CHUNK1 + SIGNED + "\n"
                + "#EXT-X-ENDLIST\n";
    }

    /** The track as lavaplayer's SoundCloud source loads it: identifier {@code A:} + lookup URL. */
    private AudioTrackInfo aacInfo()
    {
        return new AudioTrackInfo("Tone", "Fixture", (long) FIXTURE_MS,
                CksSoundCloudFormatHandler.AAC_PREFIX + base() + LOOKUP, false,
                "https://soundcloud.com/fixture/tone", null, null);
    }

    // ---- playing

    @Test
    public void playsThroughSignedLinksWithoutClientId() throws Exception
    {
        SoundCloudSource source = source(() -> CLIENT_ID);
        try
        {
            // decoded from lavaplayer's stored form; loading by link swaps the same way (SoundCloudSourceTest)
            AudioTrack track = source.decodeTrack(aacInfo(), null);
            assertTrue(String.valueOf(track), track instanceof SoundCloudAacTrack);

            Played played = play(track);

            assertNull(chain(played.failure), played.failure);
            assertEquals(AudioTrackEndReason.FINISHED, played.endReason);
            assertEquals(FIXTURE_MS, played.millis, TOLERANCE_MS);
            assertTrue(requests.toString(), requests.contains(LOOKUP + "?client_id=" + CLIENT_ID));
            for(String path : new String[]{PLAYLIST, INIT, CHUNK0, CHUNK1})
                assertTrue(path + " never requested: " + requests, requests.contains(path + SIGNED));
            assertNoClientIdOnSignedLinks();
            // lavaplayer's own SoundCloud user agent, on every request
            assertEquals(requests.size(), userAgents.size());
            for(String userAgent : userAgents)
                assertEquals(CHROME_USER_AGENT, userAgent);
            assertEquals(Collections.emptyList(), refreshes);
        }
        finally
        {
            source.shutdown();
        }
    }

    @Test
    public void aStaleClientIdIsRefreshedOnceAndTheTrackPlays() throws Exception
    {
        acceptedClientId = "fresh";
        SoundCloudSource source = source(() -> refreshes.isEmpty() ? "stale" : "fresh");
        try
        {
            Played played = play(source.decodeTrack(aacInfo(), null));

            assertNull(chain(played.failure), played.failure);
            assertEquals(FIXTURE_MS, played.millis, TOLERANCE_MS);
            // the refresh is given the lookup without the stale id, and runs once for the track's two lookups
            assertEquals(Collections.singletonList(base() + LOOKUP), refreshes);
            assertEquals(requests.toString(), 1, Collections.frequency(requests, LOOKUP + "?client_id=stale"));
            assertTrue(requests.toString(), requests.contains(LOOKUP + "?client_id=fresh"));
            assertNoClientIdOnSignedLinks();
        }
        finally
        {
            source.shutdown();
        }
    }

    @Test
    public void theRefreshGoesThroughLavaplayersSoundCloudSource() throws Exception
    {
        acceptedClientId = "fresh";
        LavaplayerSoundCloud lavaplayer = new LavaplayerSoundCloud("stale");
        SoundCloudSource source = new SoundCloudSource(lavaplayer, null, null);
        try
        {
            Played played = play(source.decodeTrack(aacInfo(), null));

            assertNull(chain(played.failure), played.failure);
            assertEquals(FIXTURE_MS, played.millis, TOLERANCE_MS);
            // the bare lookup, once, through lavaplayer's HTTP interface: its own 401 handling fetched the new id
            assertEquals(Collections.singletonList(base() + LOOKUP), lavaplayer.asked);
            assertEquals("fresh", lavaplayer.getClientId());
            assertNoClientIdOnSignedLinks();
        }
        finally
        {
            source.shutdown();
        }
    }

    @Test
    public void aClientIdThatStaysStaleFailsAfterOneRefresh() throws Exception
    {
        acceptedClientId = "fresh";
        SoundCloudSource source = source(() -> "stale");
        try
        {
            Played played = play(source.decodeTrack(aacInfo(), null));

            assertNotNull(played.failure);
            assertTrue(chain(played.failure), chain(played.failure).contains("401"));
            assertEquals(Collections.singletonList(base() + LOOKUP), refreshes);
            assertEquals(Arrays.asList(LOOKUP + "?client_id=stale", LOOKUP + "?client_id=stale"), requests);
        }
        finally
        {
            source.shutdown();
        }
    }

    @Test
    public void aMissingClientIdIsAFriendlyError()
    {
        for(Supplier<String> clientId : Arrays.<Supplier<String>>asList(() -> null, () -> ""))
        {
            SoundCloudAacTrack track = new SoundCloudAacTrack(aacInfo(), clientId, () ->
            {
                throw new AssertionError("no HTTP without a client id");
            }, null);

            FriendlyException e = assertThrows(FriendlyException.class, () -> track.process(null));

            assertEquals("SoundCloud can't be reached right now", e.getMessage());
            assertEquals(FriendlyException.Severity.SUSPICIOUS, e.severity);
        }
        assertEquals(Collections.emptyList(), requests);
    }

    @Test
    public void aFailingChunkNeverPutsItsSignedLinkInTheErrorOrTheLog() throws Exception
    {
        forbidden.add(CHUNK1);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        SoundCloudSource source = source(() -> CLIENT_ID);
        try
        {
            Played played = play(source.decodeTrack(aacInfo(), null));

            assertNotNull(played.failure);
            String failure = chain(played.failure);
            assertTrue(failure, failure.contains("chunk1.m4s") && failure.contains("403"));
            assertNoSignedLink(failure);
            assertTrue("the first chunk still played", played.millis > CHUNK0_MS - TOLERANCE_MS);
            boolean failureLogged = false;
            for(ILoggingEvent event : logged.list)
            {
                assertNoSignedLink(event.getFormattedMessage());
                for(IThrowableProxy cause = event.getThrowableProxy(); cause != null; cause = cause.getCause())
                {
                    failureLogged = true;
                    assertNoSignedLink(cause.getClassName() + ": " + cause.getMessage());
                }
            }
            assertTrue("lavaplayer logs the failure", failureLogged);
        }
        finally
        {
            root.detachAppender(logged);
            source.shutdown();
        }
    }

    @Test
    public void aSeekBeforePlayingStartsInsideTheSecondChunk() throws Exception
    {
        SoundCloudSource source = source(() -> CLIENT_ID);
        try
        {
            AudioTrack track = source.decodeTrack(aacInfo(), null);
            track.setPosition(2500);

            Played played = play(track);

            assertNull(chain(played.failure), played.failure);
            assertEquals(AudioTrackEndReason.FINISHED, played.endReason);
            assertEquals(FIXTURE_MS - 2500, played.millis, TOLERANCE_MS);
            assertFalse(requests.toString(), requests.contains(CHUNK0 + SIGNED));
            assertTrue(requests.toString(), requests.contains(CHUNK1 + SIGNED));
        }
        finally
        {
            source.shutdown();
        }
    }

    /**
     * The route the bot took before (lavaplayer's SoundCloud track hands its own HTTP interface, which adds the
     * client id to every link, to its M3U track) fails on this server exactly as it did against SoundCloud. If it
     * ever stops failing, the server no longer tells the two routes apart and the tests above prove nothing.
     */
    @Test
    public void lavaplayersOwnRouteGetsTheProduction403() throws Exception
    {
        HttpInterfaceManager withClientId = HttpClientTools.createDefaultThreadLocalManager();
        withClientId.setHttpContextFilter(new LavaplayersClientIdFilter());
        try
        {
            // what the format handler's getM3uInfo("A:...") built, and lavaplayer's SoundCloud track played
            String lookupUrl = base() + LOOKUP;
            AudioTrack track = new SoundCloudM3uAudioTrack(aacInfo(), withClientId.getInterface(),
                    new SoundCloudM3uInfo(lookupUrl, chunks -> new SoundCloudAacSegmentDecoder(chunks,
                            () -> HlsInitLookup.loadInit(withClientId.getInterface(), lookupUrl))));

            Played played = play(track);

            assertNotNull(played.failure);
            assertTrue(chain(played.failure), chain(played.failure).contains("Unexpected response code 403 from stream segments list"));
            assertEquals(0, played.millis, 0);
            assertTrue(requests.toString(), requests.contains(PLAYLIST + SIGNED + "&client_id=" + CLIENT_ID));
        }
        finally
        {
            withClientId.close();
        }
    }

    @Test
    public void closesItsHttpWhetherItPlaysOrFails() throws Exception
    {
        CountingFilter counting = new CountingFilter();
        HttpInterfaceManager http = HttpClientTools.createDefaultThreadLocalManager();
        http.setHttpContextFilter(counting);
        try
        {
            Played played = play(new SoundCloudAacTrack(aacInfo(), () -> CLIENT_ID, http::getInterface, null));
            assertNull(chain(played.failure), played.failure);
            assertTrue(counting.opened.get() > 0);
            assertEquals(counting.opened.get(), counting.closed.get());

            AudioTrackInfo missing = new AudioTrackInfo("Gone", "Fixture", 1000,
                    CksSoundCloudFormatHandler.AAC_PREFIX + base() + "/missing", false, null, null, null);
            Played failed = play(new SoundCloudAacTrack(missing, () -> CLIENT_ID, http::getInterface, null));
            assertNotNull(failed.failure);
            assertEquals(counting.opened.get(), counting.closed.get());
        }
        finally
        {
            http.close();
        }
    }

    @Test
    public void stoppingMidPlayClosesItsHttp() throws Exception
    {
        CountingFilter counting = new CountingFilter();
        HttpInterfaceManager http = HttpClientTools.createDefaultThreadLocalManager();
        http.setHttpContextFilter(counting);
        try
        {
            Played played = play(new SoundCloudAacTrack(aacInfo(), () -> CLIENT_ID, http::getInterface, null), 200);

            assertNull(chain(played.failure), played.failure);
            assertEquals(AudioTrackEndReason.STOPPED, played.endReason);
            assertTrue(String.valueOf(played.millis), played.millis < FIXTURE_MS - 1000);
            assertTrue(counting.opened.get() > 0);
            assertEquals(counting.opened.get(), counting.closed.get());
        }
        finally
        {
            http.close();
        }
    }

    // ---- the track itself

    @Test
    public void clientIdJoinsTheLookupsQuery()
    {
        assertEquals("https://api-v2.soundcloud.com/media/soundcloud:tracks:1/a/stream/hls?client_id=abc",
                SoundCloudAacTrack.withClientId("https://api-v2.soundcloud.com/media/soundcloud:tracks:1/a/stream/hls", "abc"));
        assertEquals("https://api-v2.soundcloud.com/media/1/a/stream/hls?track_authorization=t&client_id=abc",
                SoundCloudAacTrack.withClientId("https://api-v2.soundcloud.com/media/1/a/stream/hls?track_authorization=t", "abc"));
        assertEquals("https://example.test/hls?client_id=a%26b+c%3D", SoundCloudAacTrack.withClientId("https://example.test/hls", "a&b c="));
    }

    @Test
    public void aCloneIsAFreshTrackWithTheSameInfoAndSource() throws Exception
    {
        SoundCloudSource source = source(() -> CLIENT_ID);
        try
        {
            AudioTrack track = source.decodeTrack(aacInfo(), null);

            AudioTrack clone = track.makeClone();

            assertTrue(clone instanceof SoundCloudAacTrack);
            assertNotSame(track, clone);
            assertSame(track.getInfo(), clone.getInfo());
            assertSame(source, track.getSourceManager());
            assertSame(source, clone.getSourceManager());
            assertEquals("soundcloud", clone.getSourceManager().getSourceName());
        }
        finally
        {
            source.shutdown();
        }
    }

    // ---- helpers

    /** How a play went: what was heard, how it ended, and the error it reported, if any. */
    private static final class Played
    {
        double millis;
        AudioTrackEndReason endReason;
        FriendlyException failure;
    }

    /** Plays {@code track} on lavaplayer's own player and pulls its frames until it ends. */
    private static Played play(AudioTrack track) throws InterruptedException
    {
        return play(track, -1);
    }

    /**
     * As {@link #play(AudioTrack)}, but stops the track once {@code stopAfterMs} have been heard (when not negative).
     * A stop also shrinks the frame buffer to lavaplayer's least (200 ms), so the track is still downloading then.
     */
    private static Played play(AudioTrack track, long stopAfterMs) throws InterruptedException
    {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        if(stopAfterMs >= 0)
            manager.setFrameBufferDuration(200);
        try
        {
            AudioPlayer player = manager.createPlayer();
            AtomicReference<AudioTrackEndReason> ended = new AtomicReference<>();
            AtomicReference<FriendlyException> failure = new AtomicReference<>();
            player.addListener(new AudioEventAdapter()
            {
                @Override
                public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
                {
                    ended.set(endReason);
                }

                @Override
                public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception)
                {
                    failure.set(exception);
                }
            });
            player.playTrack(track);
            // the player detaches the executor from the track when it ends, so keep hold of it
            AudioTrackExecutor executor = ((InternalAudioTrack) track).getActiveExecutor();

            Played played = new Played();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PLAY_TIMEOUT_MS);
            while(ended.get() == null)
            {
                assertTrue("the track did not end within " + PLAY_TIMEOUT_MS + " ms", System.nanoTime() < deadline);
                AudioFrame frame;
                try
                {
                    frame = player.provide(50, TimeUnit.MILLISECONDS);
                }
                catch(TimeoutException e)
                {
                    continue;
                }
                if(frame != null)
                    played.millis += frame.getFormat().frameDuration();
                if(stopAfterMs >= 0 && played.millis >= stopAfterMs && ended.get() == null)
                    player.stopTrack();
            }
            // a failing track ends its frames before it reports the error: wait until it is done reporting
            while(executor.getState() != AudioTrackState.FINISHED)
            {
                assertTrue("the track did not finish within " + PLAY_TIMEOUT_MS + " ms", System.nanoTime() < deadline);
                Thread.sleep(10);
            }
            played.endReason = ended.get();
            played.failure = failure.get();
            return played;
        }
        finally
        {
            manager.shutdown();
        }
    }

    private void assertNoClientIdOnSignedLinks()
    {
        for(String request : requests)
            assertFalse(request, request.startsWith("/media/") && request.contains("client_id"));
    }

    private static void assertNoSignedLink(String text)
    {
        assertFalse(text, text.contains("Signature=") || text.contains("Policy="));
    }

    /** Every message along a failure's causes, for an assertion message. */
    private static String chain(Throwable failure)
    {
        StringBuilder text = new StringBuilder();
        for(Throwable t = failure; t != null; t = t.getCause())
            text.append(text.length() == 0 ? "" : " <- ").append(t);
        return text.toString();
    }

    /**
     * What lavaplayer's {@code SoundCloudHttpContextFilter} does to every request whose host is not on
     * {@code sndcdn.com}: sets {@code client_id} in its query.
     */
    private static final class LavaplayersClientIdFilter implements HttpContextFilter
    {
        @Override
        public void onContextOpen(HttpClientContext context)
        {
        }

        @Override
        public void onContextClose(HttpClientContext context)
        {
        }

        @Override
        public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition)
        {
            if(request.getURI().getHost().contains("sndcdn.com"))
                return;
            try
            {
                ((HttpRequestBase) request).setURI(new URIBuilder(request.getURI()).setParameter("client_id", CLIENT_ID).build());
            }
            catch(URISyntaxException e)
            {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response)
        {
            return false;
        }

        @Override
        public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error)
        {
            return false;
        }
    }

    /**
     * lavaplayer's SoundCloud source with its client id in a field instead of read from soundcloud.com, and an HTTP
     * interface that does with it what lavaplayer's does: sets the id on every request and, when SoundCloud answers
     * 401, takes a new id ("fresh") and asks once more. It records the links it is asked for.
     */
    private static final class LavaplayerSoundCloud extends SoundCloudAudioSourceManager
    {
        private static final HttpContextRetryCounter retries = new HttpContextRetryCounter("test-sc-id-retry");
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        private volatile String clientId;
        private final HttpInterfaceManager http = HttpClientTools.createDefaultThreadLocalManager();

        LavaplayerSoundCloud(String clientId)
        {
            super(true, new DefaultSoundCloudDataReader(), new DefaultSoundCloudDataLoader(),
                    new CksSoundCloudFormatHandler(), new DefaultSoundCloudPlaylistLoader(new DefaultSoundCloudDataLoader(),
                            new DefaultSoundCloudDataReader(), new CksSoundCloudFormatHandler()));
            this.clientId = clientId;
            http.setHttpContextFilter(new HttpContextFilter()
            {
                @Override
                public void onContextOpen(HttpClientContext context)
                {
                }

                @Override
                public void onContextClose(HttpClientContext context)
                {
                }

                @Override
                public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition)
                {
                    retries.handleUpdate(context, isRepetition);
                    if(!isRepetition)
                        asked.add(request.getURI().toString());
                    try
                    {
                        ((HttpRequestBase) request).setURI(new URIBuilder(request.getURI())
                                .setParameter("client_id", LavaplayerSoundCloud.this.clientId).build());
                    }
                    catch(URISyntaxException e)
                    {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response)
                {
                    if(response.getStatusLine().getStatusCode() != 401 || retries.getRetryCount(context) >= 1)
                        return false;
                    LavaplayerSoundCloud.this.clientId = "fresh";
                    return true;
                }

                @Override
                public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error)
                {
                    return false;
                }
            });
        }

        @Override
        public String getClientId()
        {
            return clientId;
        }

        @Override
        public HttpInterface getHttpInterface()
        {
            return http.getInterface();
        }

        @Override
        public void shutdown()
        {
            super.shutdown();
            ExceptionTools.closeWithWarnings(http);
        }
    }

    /** Leaves requests alone; counts how often an HTTP interface is taken ({@code onContextOpen}) and closed. */
    private static final class CountingFilter implements HttpContextFilter
    {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public void onContextOpen(HttpClientContext context)
        {
            opened.incrementAndGet();
        }

        @Override
        public void onContextClose(HttpClientContext context)
        {
            closed.incrementAndGet();
        }

        @Override
        public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition)
        {
        }

        @Override
        public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response)
        {
            return false;
        }

        @Override
        public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error)
        {
            return false;
        }
    }
}
