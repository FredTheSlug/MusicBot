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
package com.jagrosh.jmusicbot.web;

import ch.qos.logback.classic.Level;
import com.jagrosh.jmusicbot.playlist.ImportJobs;
import com.jagrosh.jmusicbot.playlist.LikesStore;
import com.jagrosh.jmusicbot.playlist.PlaylistConverter;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.TrackResolver;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 * The liked-songs endpoints on a bare Javalin, over a real {@link LikesStore} and playlist store in a temp folder,
 * with three people taking turns, as {@code PlaylistRoutesTest} does for the playlists.
 */
public class LikeRoutesTest
{
    private static final Instant T0 = Instant.parse("2026-10-14T18:00:00Z");
    private static final Person ANNA = new Person("1", "anna");
    private static final Person FRED = new Person("2", "fred");
    private static final Person ZED = new Person("3", "Zed");
    private static final String LOG_IN = "Log in with Discord to like songs";
    private static final String BAD_SONG = "That song can't be liked";
    private static final String TOO_LATE = "Too late to undo";
    private static final String LOCAL = "/home/cloutkings/music/Artist/song.m4a";
    private static final String[] QUIET = {"io.javalin", "org.eclipse.jetty", "com.jagrosh.jmusicbot.playlist",
            "com.jagrosh.jmusicbot.web.PlaylistRoutes", "com.jagrosh.jmusicbot.web.LikeRoutes"};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private Path likesDir;
    private TestClock clock;
    private PlaylistStore playlists;
    private LikesStore likes;
    private final AtomicReference<Person> user = new AtomicReference<>(ANNA);
    private final List<Broadcast> broadcasts = Collections.synchronizedList(new ArrayList<Broadcast>());
    private final List<Play> plays = Collections.synchronizedList(new ArrayList<Play>());
    private final AtomicReference<RuntimeException> playFails = new AtomicReference<>();
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Level> savedLevels = new LinkedHashMap<>();

    @Before
    public void startServer()
    {
        for(String name : QUIET) // the server and the stores log every start, stop and broken file: keep that out of the test output
        {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            savedLevels.put(name, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
        root = tmp.getRoot().toPath().resolve("Playlists");
        likesDir = root.resolve(".liked");
        clock = new TestClock(T0);
        playlists = new PlaylistStore(root, clock);
        likes = new LikesStore(likesDir, clock);
        app = Javalin.create();
        // wired as WebServer wires them: a local file stored in a playlist or in someone's likes may go into either
        Supplier<Set<String>> likedUris = likes::allUris;
        TrackResolver resolver = line -> TrackResolver.Resolved.failed("nothing is looked up in these tests");
        new PlaylistRoutes(playlists, new PlaylistConverter(playlists, resolver), new ImportJobs(playlists, resolver, Runnable::run, progress -> { }),
                resolver, ctx -> user.get(), this::broadcast, (ctx, guildId, name, folder, mode, songId) ->
                {
                    throw new AssertionError("no playlist is played in these tests");
                }, clock, likedUris).register(app);
        routes(likedUris).register(app);
        app.start("127.0.0.1", 0);
    }

    @After
    public void stopServer()
    {
        app.stop();
        for(Map.Entry<String, Level> saved : savedLevels.entrySet())
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(saved.getKey())).setLevel(saved.getValue());
    }

    private LikeRoutes routes(Supplier<Set<String>> likedUris)
    {
        return new LikeRoutes(likes, uri -> SongInput.storedIn(playlists, likedUris).test(uri), ctx -> user.get(), this::broadcast,
                this::recordPlay, clock);
    }

    private void broadcast(String type, Object data)
    {
        broadcasts.add(new Broadcast(type, (JSONObject) data));
    }

    private void recordPlay(Context ctx, String guildId, PlaylistFile list, String mode, String songId)
    {
        RuntimeException fails = playFails.get();
        if(fails != null)
            throw fails;
        plays.add(new Play(guildId, list, mode, songId));
        ctx.contentType("application/json").result(new JSONObject().put("message", LikeRoutes.loadingMessage(list)).toString());
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    /** A clock that stands still until a test moves it. */
    private static final class TestClock extends Clock
    {
        private volatile Instant now;

        TestClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration by)
        {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }

    private static final class Broadcast
    {
        final String type;
        final JSONObject data;

        Broadcast(String type, JSONObject data)
        {
            this.type = type;
            this.data = data;
        }

        @Override
        public String toString()
        {
            return type + " " + data;
        }
    }

    private static final class Play
    {
        final String guildId;
        final PlaylistFile list;
        final String mode;
        final String songId;

        Play(String guildId, PlaylistFile list, String mode, String songId)
        {
            this.guildId = guildId;
            this.list = list;
            this.mode = mode;
            this.songId = songId;
        }
    }

    private static final class Resp
    {
        final int status;
        final String body;
        final String contentType;

        Resp(int status, String body, String contentType)
        {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        JSONObject json()
        {
            return new JSONObject(body);
        }
    }

    private void as(Person person)
    {
        user.set(person);
    }

    private Resp call(String method, String path, Object body) throws Exception
    {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .method(method, publisher);
        if(body != null)
            request.header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(""));
    }

    private Resp get(String path) throws Exception
    {
        return call("GET", path, null);
    }

    private Resp post(String path, Object body) throws Exception
    {
        return call("POST", path, body);
    }

    /** A 200 answer that is JSON; returns it. */
    private static JSONObject ok(Resp resp)
    {
        assertEquals(resp.body, 200, resp.status);
        assertTrue(resp.contentType, resp.contentType.startsWith("application/json"));
        return resp.json();
    }

    private static JSONArray okArray(Resp resp)
    {
        assertEquals(resp.body, 200, resp.status);
        assertTrue(resp.contentType, resp.contentType.startsWith("application/json"));
        return new JSONArray(resp.body);
    }

    /** An error answer: the status, JSON, and a message for people; returns the message. */
    private static String error(Resp resp, int status)
    {
        assertEquals(resp.body, status, resp.status);
        assertTrue(resp.contentType, resp.contentType.startsWith("application/json"));
        String message = resp.json().optString("error", "");
        assertFalse("an error message in " + resp.body, message.isEmpty());
        return message;
    }

    private static String enc(String text)
    {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String uriOf(String title)
    {
        return "https://example.com/" + title;
    }

    private static JSONObject songJson(String title)
    {
        return new JSONObject().put("uri", uriOf(title)).put("title", title).put("author", "Artist " + title)
                .put("durationMs", 1000).put("artworkUrl", uriOf(title) + ".png");
    }

    private Resp like(String title) throws Exception
    {
        return post("/api/likes/add", new JSONObject().put("song", songJson(title)));
    }

    private Resp likeUri(String uri) throws Exception
    {
        return post("/api/likes/add", new JSONObject().put("song", new JSONObject().put("uri", uri).put("title", "t")));
    }

    private Resp play(String userId, String mode) throws Exception
    {
        return post("/api/guild/g42/likes/play", new JSONObject().put("userId", userId).put("mode", mode));
    }

    private JSONObject page(Person person) throws Exception
    {
        return ok(get("/api/likes/" + person.getId()));
    }

    private static List<String> titles(JSONObject page)
    {
        List<String> titles = new ArrayList<>();
        JSONArray songs = page.getJSONArray("songs");
        for(int i = 0; i < songs.length(); i++)
            titles.add(songs.getJSONObject(i).getString("title"));
        return titles;
    }

    private static String idOf(JSONObject page, String title)
    {
        JSONArray songs = page.getJSONArray("songs");
        for(int i = 0; i < songs.length(); i++)
            if(title.equals(songs.getJSONObject(i).getString("title")))
                return songs.getJSONObject(i).getString("id");
        throw new AssertionError("no song titled " + title + " in " + titles(page));
    }

    private static List<String> strings(JSONArray array)
    {
        List<String> strings = new ArrayList<>();
        for(int i = 0; i < array.length(); i++)
            strings.add(array.getString(i));
        return strings;
    }

    private static List<String> uris(PlaylistFile list)
    {
        List<String> uris = new ArrayList<>();
        for(Song song : list.getSongs())
            uris.add(song.getUri());
        return uris;
    }

    private static List<String> urisOf(String... titles)
    {
        List<String> uris = new ArrayList<>();
        for(String title : titles)
            uris.add(uriOf(title));
        return uris;
    }

    private static JSONObject answer(String key, Object value, String key2, Object value2)
    {
        return new JSONObject().put(key, value).put(key2, value2);
    }

    /** Writes a likes file straight to disk, as the store would, with these uris (newest first). */
    private void writeLikes(Person owner, List<String> uris) throws Exception
    {
        PlaylistFile file = new PlaylistFile();
        file.setName(owner.getId());
        file.setVersion(1);
        file.setCreatedBy(owner);
        file.setCreatedAt(T0);
        file.setUpdatedAt(T0);
        Set<String> ids = new HashSet<>();
        SecureRandom random = new SecureRandom();
        for(String uri : uris)
        {
            Song song = new Song();
            song.setId(PlaylistFile.newSongId(random, ids));
            song.setUri(uri);
            song.setTitle(uri);
            song.setDurationMs(1000);
            song.setAddedBy(owner);
            song.setAddedAt(T0);
            file.getSongs().add(song);
        }
        Files.createDirectories(likesDir);
        Files.write(likesDir.resolve(owner.getId() + ".json"), file.toJson().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> numbered(String prefix, int from, int to)
    {
        List<String> uris = new ArrayList<>();
        for(int i = from; i < to; i++)
            uris.add(uriOf(prefix + i));
        return uris;
    }

    private Broadcast single(int mark)
    {
        List<Broadcast> fresh = new ArrayList<>(broadcasts.subList(mark, broadcasts.size()));
        assertEquals(fresh.toString(), 1, fresh.size());
        return fresh.get(0);
    }

    private static void assertLikesBroadcast(Broadcast broadcast, Person who)
    {
        assertEquals("likes", broadcast.type);
        assertTrue("only whose likes changed: " + broadcast.data,
                broadcast.data.similar(new JSONObject().put("userId", who.getId())));
    }

    // ---- liking ------------------------------------------------------------------------------------------------

    @Test
    public void likeUnlikeAndMine() throws Exception
    {
        JSONObject empty = ok(get("/api/likes/mine"));
        assertTrue(empty.toString(), empty.similar(new JSONObject().put("uris", new JSONArray()).put("count", 0).put("max", 2000)));
        assertFalse("reading makes nothing", Files.exists(likesDir));

        JSONObject liked = ok(like("a"));
        assertTrue(liked.toString(), liked.similar(answer("liked", true, "count", 1)));
        clock.advance(Duration.ofSeconds(60));
        assertTrue(ok(like("b")).similar(answer("liked", true, "count", 2)));
        assertTrue("liking again changes nothing", ok(like("a")).similar(answer("liked", true, "count", 2)));

        JSONObject mine = ok(get("/api/likes/mine"));
        assertEquals("newest first", urisOf("b", "a"), strings(mine.getJSONArray("uris")));
        assertEquals(2, mine.getInt("count"));
        assertEquals(2000, mine.getInt("max"));

        PlaylistFile stored = likes.get(ANNA.getId()).get();
        Song a = stored.getSongs().get(1);
        assertEquals(uriOf("a"), a.getUri());
        assertEquals("a", a.getTitle());
        assertEquals("Artist a", a.getAuthor());
        assertEquals(1000, a.getDurationMs());
        assertEquals(uriOf("a") + ".png", a.getArtworkUrl());
        assertEquals("1", a.getAddedBy().getId());
        assertEquals(T0, a.getAddedAt());

        JSONObject unliked = ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("a"))));
        assertTrue(unliked.toString(), unliked.similar(answer("liked", false, "count", 1)));
        assertTrue("unliking what isn't liked", ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("never"))))
                .similar(answer("liked", false, "count", 1)));
        assertEquals(urisOf("b"), strings(ok(get("/api/likes/mine")).getJSONArray("uris")));

        error(post("/api/likes/remove", new JSONObject()), 400);
        error(post("/api/likes/remove", new JSONObject().put("uri", 5)), 400);
        assertEquals(BAD_SONG, error(post("/api/likes/add", new JSONObject()), 400));
        assertEquals(BAD_SONG, error(post("/api/likes/add", new JSONObject().put("song", uriOf("c"))), 400));
        assertEquals(1, likes.get(ANNA.getId()).get().getSongs().size());
    }

    @Test
    public void withoutLoginChangesAre401AndReadsStillWork() throws Exception
    {
        ok(like("a"));
        ok(like("b"));
        String songId = idOf(page(ANNA), "a");
        String token = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), "b")))).getString("undo");
        long version = likes.get(ANNA.getId()).get().getVersion();
        broadcasts.clear();

        as(null); // web login off: nobody to like for
        assertEquals(LOG_IN, error(like("c"), 401));
        assertEquals(LOG_IN, error(post("/api/likes/remove", new JSONObject().put("uri", uriOf("a")).put("userId", "1")), 401));
        assertEquals(LOG_IN, error(post("/api/likes/remove-song", new JSONObject().put("songId", songId).put("userId", "1")), 401));
        assertEquals(LOG_IN, error(post("/api/likes/undo", new JSONObject().put("token", token)), 401));
        assertEquals(LOG_IN, error(post("/api/likes/move", new JSONObject().put("songId", songId).put("toIndex", 0)), 401));
        assertEquals("nothing changed", version, likes.get(ANNA.getId()).get().getVersion());
        assertTrue(broadcasts.isEmpty());

        JSONObject mine = ok(get("/api/likes/mine"));
        assertTrue(mine.toString(), mine.similar(new JSONObject().put("uris", new JSONArray()).put("count", 0).put("max", 2000)));
        assertEquals(1, okArray(get("/api/likes/people")).length());
        assertEquals(Arrays.asList("anna"), strings(ok(get("/api/likes/who?uri=" + enc(uriOf("a")))).getJSONArray("names")));
        JSONObject annas = page(ANNA);
        assertFalse(annas.getBoolean("mine"));
        assertEquals(Arrays.asList("a"), titles(annas));
        ok(play("1", "all"));
        assertEquals(1, plays.size());

        as(ANNA);
        ok(post("/api/likes/undo", new JSONObject().put("token", token)));
        assertEquals("the token was not used up", Arrays.asList("b", "a"), titles(page(ANNA)));
    }

    @Test
    public void onlyYourOwnListChanges() throws Exception
    {
        ok(like("a"));
        ok(like("b"));
        JSONObject annasBefore = page(ANNA);
        String annasA = idOf(annasBefore, "a");

        as(FRED);
        assertTrue("a userId in the body is ignored", ok(post("/api/likes/add",
                new JSONObject().put("userId", "1").put("ownerId", "1").put("song", songJson("c")))).similar(answer("liked", true, "count", 1)));
        assertTrue(ok(post("/api/likes/remove", new JSONObject().put("userId", "1").put("uri", uriOf("a"))))
                .similar(answer("liked", false, "count", 1)));
        assertEquals("another person's song id is looked for in your list only", "Song not found",
                error(post("/api/likes/remove-song", new JSONObject().put("userId", "1").put("songId", annasA)), 404));
        assertEquals("Song not found", error(post("/api/likes/move", new JSONObject().put("userId", "1").put("songId", annasA).put("toIndex", 1)), 404));
        as(ZED); // no list at all
        error(post("/api/likes/remove-song", new JSONObject().put("songId", annasA)), 404);
        error(post("/api/likes/move", new JSONObject().put("songId", annasA).put("toIndex", 1)), 404);
        assertFalse("nothing was made for Zed", likes.get(ZED.getId()).isPresent());

        as(ANNA);
        assertTrue("anna's list is as it was: " + page(ANNA), annasBefore.similar(page(ANNA)));
        assertEquals(Arrays.asList("c"), titles(page(FRED)));

        // an Undo token works only for whoever removed the song
        String token = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), "b")))).getString("undo");
        as(FRED);
        assertEquals(TOO_LATE, error(post("/api/likes/undo", new JSONObject().put("token", token)), 404));
        assertEquals(Arrays.asList("c"), titles(page(FRED)));
        as(ANNA);
        assertEquals(Arrays.asList("a"), titles(page(ANNA)));
        ok(post("/api/likes/undo", new JSONObject().put("token", token)));
        assertEquals("the token was still good for anna", Arrays.asList("b", "a"), titles(page(ANNA)));
    }

    @Test
    public void userIdPathIsValidated() throws Exception
    {
        ok(like("a"));
        for(String bad : new String[]{"abc", "1a", "-1", "%20", "123456789012345678901", "everyone"})
            assertEquals(bad, "Invalid user id", error(get("/api/likes/" + bad), 400));
        for(String trick : new String[]{"..%2Fx", "..%2F..%2Fsecret", "%2E%2E", "..%5C1", "1%2F..%2F2"})
        {
            Resp resp = get("/api/likes/" + trick);
            assertTrue(trick + " answered " + resp.status + " " + resp.body, resp.status == 400 || resp.status == 404);
            assertFalse(trick + " leaked a list: " + resp.body, resp.body.contains(uriOf("a")));
        }
        assertEquals("No liked songs", error(get("/api/likes/999"), 404));
        assertEquals(Arrays.asList("a"), titles(page(ANNA)));

        // the fixed paths are never taken for a user id
        assertTrue(ok(get("/api/likes/mine")).has("uris"));
        assertEquals(1, okArray(get("/api/likes/people")).length());
        assertTrue(ok(get("/api/likes/who?uri=x")).has("names"));

        for(String bad : new String[]{"../x", "abc", "", " ", "1/../2", "Everyone"})
            error(post("/api/guild/g42/likes/play", new JSONObject().put("userId", bad).put("mode", "all")), 400);
        error(post("/api/guild/g42/likes/play", new JSONObject().put("userId", 1).put("mode", "all")), 400);
        assertTrue(plays.isEmpty());
        try(Stream<Path> files = Files.list(likesDir))
        {
            assertEquals("only anna's file", Arrays.asList("1.json"), files.map(p -> p.getFileName().toString()).collect(Collectors.toList()));
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    @Test
    public void peopleAndAFriendsList() throws Exception
    {
        as(ZED);
        ok(like("z1"));
        clock.advance(Duration.ofSeconds(60));
        as(ANNA);
        ok(like("a1"));
        ok(like("a2"));
        as(FRED);
        ok(like("f1"));

        JSONArray people = okArray(get("/api/likes/people"));
        List<String> order = new ArrayList<>();
        for(int i = 0; i < people.length(); i++)
            order.add(people.getJSONObject(i).getString("name"));
        assertEquals("me first, then by name", Arrays.asList("fred", "anna", "Zed"), order);
        JSONObject anna = people.getJSONObject(1);
        JSONObject expected = new JSONObject().put("id", "1").put("name", "anna").put("count", 2).put("durationMs", 2000)
                .put("updatedAt", T0.plusSeconds(60).toString());
        assertTrue(anna + " vs " + expected, anna.similar(expected));
        assertEquals(T0.toString(), people.getJSONObject(2).getString("updatedAt"));

        as(ANNA);
        assertEquals("anna", okArray(get("/api/likes/people")).getJSONObject(0).getString("name"));
        as(null);
        assertEquals("nobody to put first", "anna", okArray(get("/api/likes/people")).getJSONObject(0).getString("name"));

        // a friend's list: the playlist page shape, plus who it belongs to
        as(FRED);
        JSONObject annas = page(ANNA);
        assertEquals(Arrays.asList("a2", "a1"), titles(annas));
        assertEquals("1", annas.getString("name"));
        assertEquals("", annas.getString("folder"));
        assertFalse(annas.getBoolean("legacy"));
        assertFalse(annas.getBoolean("shuffle"));
        assertEquals(likes.get(ANNA.getId()).get().getVersion(), annas.getLong("version"));
        assertTrue(annas.getJSONObject("owner").similar(new JSONObject().put("id", "1").put("name", "anna")));
        assertFalse(annas.getBoolean("mine"));
        JSONObject song = annas.getJSONArray("songs").getJSONObject(1);
        assertTrue(song.getString("id").matches("[a-z0-9]{8}"));
        assertEquals(uriOf("a1"), song.getString("uri"));
        assertEquals("anna", song.getJSONObject("addedBy").getString("name"));
        assertEquals(T0.plusSeconds(60).toString(), song.getString("addedAt"));
        JSONObject own = page(FRED);
        assertTrue(own.getBoolean("mine"));
        assertTrue(own.getJSONObject("owner").similar(new JSONObject().put("id", "2").put("name", "fred")));

        // the owner's name is the one of their latest change
        as(new Person("1", "Anna B"));
        ok(like("a3"));
        as(FRED);
        assertEquals("Anna B", page(ANNA).getJSONObject("owner").getString("name"));

        // someone who unliked everything is no longer listed; their (empty) list is still there to open
        as(ZED);
        ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("z1"))));
        assertEquals(2, okArray(get("/api/likes/people")).length());
        assertEquals(0, page(ZED).getJSONArray("songs").length());
        assertEquals("No liked songs", error(get("/api/likes/42"), 404));
    }

    @Test
    public void whoExcludesYou() throws Exception
    {
        as(ANNA);
        ok(like("a"));
        as(ZED);
        ok(like("a"));
        ok(like("z"));
        as(FRED);
        ok(like("z"));

        as(ANNA);
        assertEquals(Arrays.asList("Zed"), strings(ok(get("/api/likes/who?uri=" + enc(uriOf("a")))).getJSONArray("names")));
        as(FRED);
        assertEquals(Arrays.asList("anna", "Zed"), strings(ok(get("/api/likes/who?uri=" + enc(uriOf("a")))).getJSONArray("names")));
        as(ZED);
        assertEquals(Arrays.asList("fred"), strings(ok(get("/api/likes/who?uri=" + enc(uriOf("z")))).getJSONArray("names")));
        as(null);
        assertEquals(Arrays.asList("anna", "Zed"), strings(ok(get("/api/likes/who?uri=" + enc(uriOf("a")))).getJSONArray("names")));
        assertEquals(0, ok(get("/api/likes/who?uri=" + enc(uriOf("nobody")))).getJSONArray("names").length());
        error(get("/api/likes/who"), 400);
        error(get("/api/likes/who?uri="), 400);
    }

    // ---- what may be liked -------------------------------------------------------------------------------------

    @Test
    public void songInputRulesApply() throws Exception
    {
        for(String uri : new String[]{"file:///etc/passwd", "/etc/passwd", LOCAL, "C:\\Windows\\win.ini", "javascript:alert(1)",
                "ftp://example.com/a.mp3", "ytsearch:", "ytsearch:a\nb", "data:text/html,hi", ""})
            assertEquals(uri, BAD_SONG, error(likeUri(uri), 400));
        assertEquals(BAD_SONG, error(post("/api/likes/add", new JSONObject().put("song", new JSONObject().put("uri", 5))), 400));
        assertFalse("nothing got in", likes.get(ANNA.getId()).isPresent());

        ok(post("/api/likes/add", new JSONObject().put("song", songJson("x").put("artworkUrl", "javascript:alert(1)"))));
        ok(post("/api/likes/add", new JSONObject().put("song", new JSONObject().put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .put("title", "video").put("artworkUrl", "data:image/png;base64,AAAA"))));
        ok(likeUri("ytsearch:lofi beats"));
        List<Song> stored = likes.get(ANNA.getId()).get().getSongs();
        assertNull("a javascript: artwork link is dropped", stored.get(2).getArtworkUrl());
        assertEquals("a video gets its thumbnail instead", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", stored.get(1).getArtworkUrl());

        // a local file that a playlist holds may be liked
        playlists.create("Library", "", ANNA);
        playlists.addSongs("Library", "", Collections.singletonList(local(LOCAL)), null, ANNA);
        ok(likeUri(LOCAL));
        // and one that is only in someone's likes may go into a playlist, or be liked by someone else
        playlists.delete("Library", "", ANNA);
        as(FRED);
        ok(post("/api/playlists", new JSONObject().put("name", "Mine")));
        ok(post("/api/playlists/songs/add", new JSONObject().put("name", "Mine")
                .put("songs", new JSONArray().put(new JSONObject().put("uri", LOCAL).put("title", "song")))));
        assertEquals(LOCAL, playlists.get("Mine", "").get().getSongs().get(0).getUri());
        ok(likeUri(LOCAL));
        // anything else on the disk is refused by both
        String other = "/home/cloutkings/music/Artist/other.m4a";
        assertEquals(BAD_SONG, error(likeUri(other), 400));
        error(post("/api/playlists/songs/add", new JSONObject().put("name", "Mine")
                .put("songs", new JSONArray().put(new JSONObject().put("uri", other)))), 400);
        assertEquals(Arrays.asList(LOCAL), uris(likes.get(FRED.getId()).get()));
    }

    private static Song local(String uri)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle("song");
        return song;
    }

    // ---- your own Liked page: remove with Undo, move, full ------------------------------------------------------

    @Test
    public void removeSongAndUndo() throws Exception
    {
        ok(like("a"));
        clock.advance(Duration.ofSeconds(60));
        ok(like("b"));
        ok(like("c"));
        JSONObject before = page(ANNA);
        JSONObject songB = before.getJSONArray("songs").getJSONObject(1);

        clock.advance(Duration.ofSeconds(120)); // addedAt must stay at the like, not move to the undo
        JSONObject removed = ok(post("/api/likes/remove-song", new JSONObject().put("songId", songB.getString("id"))));
        assertEquals(2, removed.getInt("count"));
        assertEquals(before.getLong("version") + 1, removed.getLong("version"));
        String token = removed.getString("undo");
        assertFalse(token.isEmpty());
        assertEquals(Arrays.asList("c", "a"), titles(page(ANNA)));
        assertEquals(urisOf("c", "a"), strings(ok(get("/api/likes/mine")).getJSONArray("uris")));

        clock.advance(Duration.ofSeconds(30));
        // a client can say anything it likes about the song or where it goes; it is not believed
        JSONObject forged = new JSONObject().put("token", token).put("song", songJson("evil")).put("index", 0).put("userId", "2");
        JSONObject undone = ok(post("/api/likes/undo", forged));
        assertEquals(3, undone.getInt("count"));
        assertEquals(removed.getLong("version") + 1, undone.getLong("version"));
        JSONObject after = page(ANNA);
        assertEquals(Arrays.asList("c", "b", "a"), titles(after));
        assertTrue("the song is exactly as it was (id, addedBy, addedAt, details): " + after.getJSONArray("songs").getJSONObject(1),
                songB.similar(after.getJSONArray("songs").getJSONObject(1)));

        assertEquals("a token works once", TOO_LATE, error(post("/api/likes/undo", new JSONObject().put("token", token)), 404));
        assertEquals(Arrays.asList("c", "b", "a"), titles(page(ANNA)));

        String late = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), "a")))).getString("undo");
        clock.advance(Duration.ofSeconds(61));
        assertEquals("tokens expire", TOO_LATE, error(post("/api/likes/undo", new JSONObject().put("token", late)), 404));
        assertEquals(Arrays.asList("c", "b"), titles(page(ANNA)));
        assertEquals(TOO_LATE, error(post("/api/likes/undo", new JSONObject().put("token", "never-issued")), 404));
        error(post("/api/likes/undo", new JSONObject()), 400);
        error(post("/api/likes/remove-song", new JSONObject()), 400);
        assertEquals("Song not found", error(post("/api/likes/remove-song", new JSONObject().put("songId", "nosuchid")), 404));

        // liked again before the Undo: the Undo is fine and the song is there once
        String again = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), "c")))).getString("undo");
        ok(like("c"));
        assertEquals(2, ok(post("/api/likes/undo", new JSONObject().put("token", again))).getInt("count"));
        assertEquals(Arrays.asList("c", "b"), titles(page(ANNA)));
    }

    @Test
    public void moveToFinalIndex() throws Exception
    {
        for(String title : new String[]{"a", "b", "c", "d"})
            ok(like(title));
        JSONObject before = page(ANNA);
        assertEquals(Arrays.asList("d", "c", "b", "a"), titles(before));

        JSONObject moved = ok(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "a")).put("toIndex", 0)));
        assertEquals(4, moved.getInt("count"));
        assertEquals(before.getLong("version") + 1, moved.getLong("version"));
        assertEquals(Arrays.asList("a", "d", "c", "b"), titles(page(ANNA)));
        ok(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "a")).put("toIndex", 3)));
        assertEquals("toIndex is the final position", Arrays.asList("d", "c", "b", "a"), titles(page(ANNA)));
        ok(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "d")).put("toIndex", 99)));
        assertEquals(Arrays.asList("c", "b", "a", "d"), titles(page(ANNA)));
        ok(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "d")).put("toIndex", -5)));
        assertEquals(Arrays.asList("d", "c", "b", "a"), titles(page(ANNA)));

        error(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "d"))), 400);
        error(post("/api/likes/move", new JSONObject().put("songId", idOf(before, "d")).put("toIndex", "first")), 400);
        error(post("/api/likes/move", new JSONObject().put("toIndex", 0)), 400);
        assertEquals("Song not found", error(post("/api/likes/move", new JSONObject().put("songId", "nosuchid").put("toIndex", 0)), 404));
        assertEquals(Arrays.asList("d", "c", "b", "a"), titles(page(ANNA)));
    }

    @Test
    public void fullIs409() throws Exception
    {
        writeLikes(ANNA, numbered("n", 0, 2000));
        long version = likes.get(ANNA.getId()).get().getVersion();

        assertEquals(LikesStore.FULL, error(like("onemore"), 409));
        assertEquals(2000, likes.get(ANNA.getId()).get().getSongs().size());
        assertEquals("nothing was written", version, likes.get(ANNA.getId()).get().getVersion());
        assertTrue("a song already liked is just liked", ok(likeUri(uriOf("n5"))).similar(answer("liked", true, "count", 2000)));

        // an Undo that finds the list full keeps its token until there is room again
        String token = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), uriOf("n0"))))).getString("undo");
        ok(like("onemore"));
        assertEquals(LikesStore.FULL, error(post("/api/likes/undo", new JSONObject().put("token", token)), 409));
        ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("onemore"))));
        assertEquals(2000, ok(post("/api/likes/undo", new JSONObject().put("token", token))).getInt("count"));
        assertTrue(likes.uris(ANNA.getId()).contains(uriOf("n0")));
    }

    // ---- what the panels are told ------------------------------------------------------------------------------

    @Test
    public void everyChangeBroadcastsLikesWithoutSongData() throws Exception
    {
        int mark = broadcasts.size();
        ok(like("a"));
        assertLikesBroadcast(single(mark), ANNA);
        ok(like("b"));
        ok(like("c"));

        mark = broadcasts.size();
        ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("c"))));
        assertLikesBroadcast(single(mark), ANNA);

        mark = broadcasts.size();
        String token = ok(post("/api/likes/remove-song", new JSONObject().put("songId", idOf(page(ANNA), "a")))).getString("undo");
        assertLikesBroadcast(single(mark), ANNA);

        mark = broadcasts.size();
        ok(post("/api/likes/undo", new JSONObject().put("token", token)));
        assertLikesBroadcast(single(mark), ANNA);

        mark = broadcasts.size();
        ok(post("/api/likes/move", new JSONObject().put("songId", idOf(page(ANNA), "a")).put("toIndex", 0)));
        assertLikesBroadcast(single(mark), ANNA);

        as(FRED);
        mark = broadcasts.size();
        ok(like("a"));
        assertLikesBroadcast(single(mark), FRED);

        // reading and playing tell nobody anything, nor does a change that failed
        mark = broadcasts.size();
        ok(get("/api/likes/mine"));
        okArray(get("/api/likes/people"));
        ok(get("/api/likes/who?uri=" + enc(uriOf("a"))));
        page(ANNA);
        ok(play("1", "all"));
        ok(play(LikeRoutes.EVERYONE, "shuffle"));
        error(likeUri("/etc/passwd"), 400);
        error(post("/api/likes/remove-song", new JSONObject().put("songId", "nosuchid")), 404);
        error(post("/api/likes/undo", new JSONObject().put("token", "nope")), 404);
        error(post("/api/likes/move", new JSONObject().put("songId", "nosuchid").put("toIndex", 0)), 404);
        assertEquals(broadcasts.subList(mark, broadcasts.size()).toString(), mark, broadcasts.size());

        for(Broadcast each : broadcasts)
        {
            assertEquals("likes", each.type);
            assertFalse("no song data: " + each.data, each.data.toString().contains("example.com"));
        }
    }

    // ---- playing -----------------------------------------------------------------------------------------------

    @Test
    public void playALikesListUsesTheHook() throws Exception
    {
        ok(like("a"));
        ok(like("b"));
        JSONObject annas = page(ANNA);

        as(FRED);
        JSONObject answer = ok(play("1", "shuffle"));
        assertEquals("Loading anna's likes...", answer.getString("message"));
        Play first = plays.get(0);
        assertEquals("g42", first.guildId);
        assertEquals("shuffle", first.mode);
        assertNull(first.songId);
        assertEquals("anna's list as it is stored", urisOf("b", "a"), uris(first.list));
        assertEquals("1", first.list.getName());

        ok(post("/api/guild/g42/likes/play", new JSONObject().put("userId", "1").put("mode", "from").put("songId", idOf(annas, "a"))));
        assertEquals("from", plays.get(1).mode);
        assertEquals(idOf(annas, "a"), plays.get(1).songId);
        as(ANNA);
        assertEquals("Loading anna's likes...", ok(play("1", "all")).getString("message"));
        assertEquals(3, plays.size());

        // bad requests never reach the hook
        error(post("/api/guild/g42/likes/play", new JSONObject().put("userId", "1").put("mode", "from")), 400);
        assertEquals("Song not found", error(post("/api/guild/g42/likes/play",
                new JSONObject().put("userId", "1").put("mode", "from").put("songId", "nosuchid")), 404));
        error(play("1", "backwards"), 400);
        error(post("/api/guild/g42/likes/play", new JSONObject().put("mode", "all")), 400);
        error(post("/api/guild/g42/likes/play", new JSONObject().put("userId", "1")), 400);
        assertEquals("No liked songs to play", error(play("999", "all"), 404));
        as(ZED);
        ok(like("z"));
        ok(post("/api/likes/remove", new JSONObject().put("uri", uriOf("z"))));
        assertEquals("an emptied list has nothing to play", "No liked songs to play", error(play("3", "all"), 404));
        assertEquals(3, plays.size());

        // what the hook refuses is mapped like any other error
        playFails.set(new IllegalStateException("busy"));
        error(play("1", "all"), 409);
        playFails.set(new RuntimeException("boom in /home/cloutkings/secret"));
        Resp broken = play("1", "all");
        error(broken, 500);
        assertFalse(broken.body, broken.body.contains("secret"));
    }

    @Test
    public void playEveryoneMergesDedupesAndShuffles() throws Exception
    {
        assertEquals("nobody has liked anything yet", "No liked songs to play", error(play(LikeRoutes.EVERYONE, "all"), 404));

        as(ANNA);
        for(String uri : numbered("s", 0, 20))
            ok(likeUri(uri));
        as(FRED);
        for(String uri : numbered("s", 10, 30))
            ok(likeUri(uri));
        as(ZED);
        ok(likeUri(uriOf("s25")));
        ok(likeUri(uriOf("s30")));
        List<String> merged = new ArrayList<>(); // the lists in the order of people, each newest first, each uri once
        for(Person person : new Person[]{ANNA, FRED, ZED})
            for(String uri : uris(likes.get(person.getId()).get()))
                if(!merged.contains(uri))
                    merged.add(uri);
        long annasVersion = likes.get(ANNA.getId()).get().getVersion();

        JSONObject answer = ok(play(LikeRoutes.EVERYONE, "all"));
        assertEquals("Loading everyone's likes...", answer.getString("message"));
        assertEquals(1, plays.size());
        Play mix = plays.get(0);
        assertEquals("all", mix.mode);
        assertEquals(LikeRoutes.EVERYONE, mix.list.getName());
        List<String> played = uris(mix.list);
        assertEquals("no uri twice: " + played, new HashSet<>(played).size(), played.size());
        assertEquals(new HashSet<>(numbered("s", 0, 31)), new HashSet<>(played));
        assertNotEquals("shuffled, not in the order of the names", merged, played);
        assertEquals("the mix is not saved anywhere", annasVersion, likes.get(ANNA.getId()).get().getVersion());
        assertEquals(3, likes.people().size());

        // capped at 2000
        writeLikes(new Person("4", "dee"), numbered("u", 0, 2000));
        writeLikes(new Person("5", "eve"), numbered("u", 1000, 3000));
        ok(play(LikeRoutes.EVERYONE, "shuffle"));
        List<String> capped = uris(plays.get(1).list);
        assertEquals(2000, capped.size());
        assertEquals(2000, new HashSet<>(capped).size());
        Set<String> all = new HashSet<>(numbered("u", 0, 3000));
        all.addAll(numbered("s", 0, 31));
        assertTrue(all.containsAll(capped));
        assertEquals("shuffle", plays.get(1).mode);
    }

    // ---- bodies and the guard ----------------------------------------------------------------------------------

    @Test
    public void bodiesThatAreNotJsonObjectsAreRejected() throws Exception
    {
        String[] paths = {"/api/likes/add", "/api/likes/remove", "/api/likes/remove-song", "/api/likes/undo", "/api/likes/move",
                "/api/guild/g1/likes/play"};
        String[] bodies = {"not json", "[1,2]", "\"text\"", "42", "{\"song\":", ""};
        for(String path : paths)
            for(String body : bodies)
                error(call("POST", path, body), 400);
        assertFalse(Files.exists(likesDir));
        assertTrue(broadcasts.isEmpty());
        assertTrue(plays.isEmpty());
    }

    @Test
    public void everyRouteNeedsLogin() throws Exception
    {
        List<String> seen = new ArrayList<>();
        Javalin guarded = Javalin.create(config -> config.events(events -> events.handlerAdded(info ->
        {
            HandlerType type = info.getHttpMethod();
            if(type == HandlerType.GET || type == HandlerType.POST || type == HandlerType.PUT || type == HandlerType.DELETE)
                seen.add(type + " " + info.getPath());
        })));
        routes(likes::allUris).register(guarded);
        guarded.before("/api/*", new WebServer(null)::authMiddleware); // after the routes, as WebServer does
        guarded.start("127.0.0.1", 0);
        try
        {
            List<String> expected = Arrays.asList("GET /api/likes/mine", "GET /api/likes/people", "GET /api/likes/who",
                    "GET /api/likes/{userId}", "POST /api/likes/add", "POST /api/likes/remove", "POST /api/likes/remove-song",
                    "POST /api/likes/undo", "POST /api/likes/move", "POST /api/guild/{guildId}/likes/play");
            assertEquals(new HashSet<>(expected), new HashSet<>(seen));
            assertEquals("each route once: " + seen, expected.size(), seen.size());
            String sneaky = new JSONObject().put("userId", "1").put("mode", "all").put("song", songJson("a")).put("uri", uriOf("a"))
                    .put("songId", "abcdefgh").put("toIndex", 0).put("token", "x").toString();
            for(String route : seen)
            {
                String[] parts = route.split(" ", 2);
                HttpRequest.BodyPublisher body = parts[0].equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(sneaky);
                String path = parts[1].replaceAll("\\{[^}]*}", "1") + (parts[1].endsWith("/who") ? "?uri=" + enc(uriOf("a")) : "");
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + guarded.port() + path))
                        .method(parts[0], body).header("Content-Type", "application/json").build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(route + " answered " + response.body(), 401, response.statusCode());
            }
        }
        finally
        {
            guarded.stop();
        }
        assertFalse("no route ran", Files.exists(likesDir));
        assertTrue(broadcasts.isEmpty() && plays.isEmpty());
    }
}
