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
import com.jagrosh.jmusicbot.playlist.PlaylistConverter;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.TrackResolver;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
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
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/** The playlist endpoints on a bare Javalin, over a real store in a temp folder, as {@code WebAuthMiddlewareTest} does for the guard. */
public class PlaylistRoutesTest
{
    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");
    private static final Person TESTER = new Person("42", "tester");
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final String BAD_SONG = "That song can't be added to a playlist";
    private static final String TREE = "{\"tree\":true}";
    private static final String[] QUIET = {"io.javalin", "org.eclipse.jetty", "com.jagrosh.jmusicbot.playlist",
            "com.jagrosh.jmusicbot.web.PlaylistRoutes"};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private TestClock clock;
    private PlaylistStore store;
    private FakeResolver resolver;
    private PlaylistConverter converter;
    private ImportJobs imports;
    private final AtomicReference<Person> user = new AtomicReference<>(TESTER);
    private final List<Broadcast> broadcasts = Collections.synchronizedList(new ArrayList<Broadcast>());
    private final List<Play> plays = Collections.synchronizedList(new ArrayList<Play>());
    private final AtomicReference<RuntimeException> playFails = new AtomicReference<>();
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Level> savedLevels = new LinkedHashMap<>();

    @Before
    public void startServer()
    {
        for(String name : QUIET) // the server and the store log every start, stop and conversion: keep that out of the test output
        {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            savedLevels.put(name, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
        root = tmp.getRoot().toPath().resolve("Playlists");
        clock = new TestClock(T0);
        store = new PlaylistStore(root, clock);
        resolver = new FakeResolver();
        converter = new PlaylistConverter(store, resolver);
        imports = new ImportJobs(store, resolver, Runnable::run,
                progress -> broadcasts.add(new Broadcast("playlist-import", PlaylistRoutes.importProgress(progress))));
        app = Javalin.create();
        routes().register(app);
        app.start("127.0.0.1", 0);
    }

    @After
    public void stopServer()
    {
        app.stop();
        for(Map.Entry<String, Level> saved : savedLevels.entrySet())
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(saved.getKey())).setLevel(saved.getValue());
    }

    private PlaylistRoutes routes()
    {
        return new PlaylistRoutes(store, converter, imports, resolver, ctx -> user.get(),
                (type, data) -> broadcasts.add(new Broadcast(type, (JSONObject) data)), this::recordPlay, clock, uri -> false);
    }

    private void recordPlay(Context ctx, String guildId, String name, String folder, String mode, String songId)
    {
        RuntimeException fails = playFails.get();
        if(fails != null)
            throw fails;
        plays.add(new Play(guildId, name, folder, mode, songId));
        ctx.contentType("application/json").result(new JSONObject().put("message", "Loading playlist " + name + "...").toString());
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
        final String name;
        final String folder;
        final String mode;
        final String songId;

        Play(String guildId, String name, String folder, String mode, String songId)
        {
            this.guildId = guildId;
            this.name = name;
            this.folder = folder;
            this.mode = mode;
            this.songId = songId;
        }
    }

    /** Answers every line with one song unless a test scripts something else. */
    private static final class FakeResolver implements TrackResolver
    {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        volatile Function<String, Resolved> script = line -> Resolved.ok(Collections.singletonList(resolved(line)), null);

        @Override
        public Resolved resolve(String lineOrLink)
        {
            lines.add(lineOrLink);
            return script.apply(lineOrLink);
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

        JSONArray array()
        {
            return new JSONArray(body);
        }
    }

    private static Song resolved(String uri)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle("Title of " + uri);
        song.setAuthor("Artist");
        song.setDurationMs(1000);
        return song;
    }

    private static Song stored(String uri, String title)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle(title);
        song.setDurationMs(1000);
        return song;
    }

    private static JSONObject songJson(String title)
    {
        return new JSONObject().put("uri", "https://example.com/" + title).put("title", title)
                .put("author", "Artist " + title).put("durationMs", 1000).put("artworkUrl", "https://example.com/" + title + ".png");
    }

    private static JSONArray songsJson(String... titles)
    {
        JSONArray array = new JSONArray();
        for(String title : titles)
            array.put(songJson(title));
        return array;
    }

    private static String enc(String text)
    {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
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

    private static JSONObject target(String name, String folder)
    {
        return new JSONObject().put("name", name).put("folder", folder);
    }

    /** A 200 answer that is JSON; returns it. */
    private static JSONObject ok(Resp resp)
    {
        assertEquals(resp.body, 200, resp.status);
        assertTrue(resp.contentType, resp.contentType.startsWith("application/json"));
        return resp.json();
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

    private JSONObject item(String name, String folder) throws Exception
    {
        return ok(get("/api/playlists/item?name=" + enc(name) + "&folder=" + enc(folder)));
    }

    private static List<String> titles(JSONObject item)
    {
        List<String> titles = new ArrayList<>();
        JSONArray songs = item.getJSONArray("songs");
        for(int i = 0; i < songs.length(); i++)
            titles.add(songs.getJSONObject(i).getString("title"));
        return titles;
    }

    private static String idOf(JSONObject item, String title)
    {
        JSONArray songs = item.getJSONArray("songs");
        for(int i = 0; i < songs.length(); i++)
            if(title.equals(songs.getJSONObject(i).getString("title")))
                return songs.getJSONObject(i).getString("id");
        throw new AssertionError("no song titled " + title + " in " + titles(item));
    }

    private static List<String> uris(JSONArray items)
    {
        List<String> uris = new ArrayList<>();
        for(int i = 0; i < items.length(); i++)
            uris.add(items.getString(i));
        return uris;
    }

    private Resp add(String name, String folder, String... titles) throws Exception
    {
        return post("/api/playlists/songs/add", target(name, folder).put("songs", songsJson(titles)));
    }

    private void writeTxt(String relative, String text) throws Exception
    {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private Set<String> storedUris(String name, String folder)
    {
        Set<String> uris = new HashSet<>();
        for(Song song : store.get(name, folder).get().getSongs())
            uris.add(song.getUri());
        return uris;
    }

    private Broadcast single(int mark)
    {
        List<Broadcast> fresh = new ArrayList<>(broadcasts.subList(mark, broadcasts.size()));
        assertEquals(fresh.toString(), 1, fresh.size());
        return fresh.get(0);
    }

    private static void assertTreeBroadcast(Broadcast broadcast)
    {
        assertEquals("playlists", broadcast.type);
        assertTrue(broadcast.data.toString(), broadcast.data.similar(new JSONObject(TREE)));
    }

    private static void assertContentBroadcast(Broadcast broadcast, String name, String folder, long version)
    {
        assertEquals("playlists", broadcast.type);
        JSONObject expected = target(name, folder).put("version", version);
        assertTrue(broadcast.data + " vs " + expected, broadcast.data.similar(expected));
    }

    // ---- the endpoints of spec section 4 -----------------------------------------------------------------------

    @Test
    public void createAddMoveRemove() throws Exception
    {
        JSONObject created = ok(post("/api/playlists", target("Road trip", "Rock")));
        assertTrue(created.toString(), created.similar(target("Road trip", "Rock").put("version", 1)));

        clock.advance(Duration.ofSeconds(60));
        JSONObject added = ok(add("Road trip", "Rock", "a", "b", "c"));
        assertTrue(added.toString(), added.similar(target("Road trip", "Rock").put("version", 2)));

        JSONObject full = item("Road trip", "Rock");
        assertEquals(Arrays.asList("a", "b", "c"), titles(full));
        assertEquals("Rock", full.getString("folder"));
        assertFalse(full.getBoolean("legacy"));
        assertFalse(full.getBoolean("shuffle"));
        assertEquals("Road trip", full.getString("name"));
        assertEquals(2, full.getLong("version"));
        assertEquals("tester", full.getJSONObject("createdBy").getString("name"));
        JSONObject first = full.getJSONArray("songs").getJSONObject(0);
        assertTrue(first.getString("id").matches("[a-z0-9]{8}"));
        assertEquals("https://example.com/a", first.getString("uri"));
        assertEquals("Artist a", first.getString("author"));
        assertEquals(1000, first.getLong("durationMs"));
        assertEquals("https://example.com/a.png", first.getString("artworkUrl"));
        assertEquals("42", first.getJSONObject("addedBy").getString("id"));
        assertEquals("tester", first.getJSONObject("addedBy").getString("name"));
        assertEquals(T0.plusSeconds(60).toString(), first.getString("addedAt"));
        assertFalse(first.getBoolean("unresolved"));

        // an index puts the batch there
        ok(post("/api/playlists/songs/add", target("Road trip", "Rock").put("songs", songsJson("d")).put("index", 1)));
        assertEquals(Arrays.asList("a", "d", "b", "c"), titles(item("Road trip", "Rock")));

        // toIndex is the final position
        JSONObject moved = ok(post("/api/playlists/songs/move",
                target("Road trip", "Rock").put("songId", idOf(item("Road trip", "Rock"), "c")).put("toIndex", 0)));
        assertEquals(4, moved.getLong("version"));
        assertEquals(Arrays.asList("c", "a", "d", "b"), titles(item("Road trip", "Rock")));

        JSONObject removed = ok(post("/api/playlists/songs/remove",
                target("Road trip", "Rock").put("songId", idOf(item("Road trip", "Rock"), "d"))));
        assertEquals(5, removed.getLong("version"));
        assertEquals("Road trip", removed.getString("name"));
        assertEquals("Rock", removed.getString("folder"));
        assertFalse("an undo token", removed.getString("undo").isEmpty());
        assertEquals(Arrays.asList("c", "a", "b"), titles(item("Road trip", "Rock")));

        // what the API says is what the store has
        PlaylistFile onDisk = store.get("Road trip", "Rock").get();
        assertEquals(5, onDisk.getVersion());
        assertEquals(3, onDisk.getSongs().size());
    }

    @Test
    public void badNameIs400AndMissingIs404() throws Exception
    {
        String[] badNames = {"../evil", "a/b", "a\\b", "  ", ".", "..", ".deleted", ".legacy-txt"};
        for(String bad : badNames)
            error(post("/api/playlists", target(bad, "")), 400);
        error(post("/api/playlists", new JSONObject().put("folder", "Rock")), 400); // no name at all
        error(post("/api/playlists", new JSONObject().put("name", 7)), 400); // a name that is not text
        error(post("/api/playlists", target("Fine", "../escape")), 400);
        error(post("/api/playlists", target("Fine", "a//b")), 400);
        error(post("/api/playlists", target("Fine", ".deleted")), 400);
        error(post("/api/playlists", new JSONObject().put("name", "Fine").put("folder", 5)), 400);
        error(post("/api/playlists/rename", target("Fine", "").put("newName", "../x")), 400);
        error(post("/api/playlists/move", target("Fine", "").put("toFolder", "../x")), 400);
        error(get("/api/playlists/item"), 400);
        error(get("/api/playlists/item?name=..%2Fx"), 400);
        error(post("/api/playlists/restore", new JSONObject().put("id", "../x.json")), 400);
        error(post("/api/playlists/restore", new JSONObject()), 400);
        assertTrue("nothing was made", store.tree().isEmpty());
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("evil.json")));
        assertTrue("a failed request tells nobody anything", broadcasts.isEmpty());

        assertEquals("Playlist not found", error(get("/api/playlists/item?name=Nope"), 404));
        error(add("Nope", "", "a"), 404);
        error(post("/api/playlists/rename", target("Nope", "").put("newName", "Other")), 404);
        error(post("/api/playlists/move", target("Nope", "").put("toFolder", "X")), 404);
        error(post("/api/playlists/delete", target("Nope", "")), 404);
        error(post("/api/playlists/shuffle", target("Nope", "").put("on", true)), 404);
        error(post("/api/playlists/restore", new JSONObject().put("id", "123-Nope.json")), 404);
        error(post("/api/playlists/folder/delete", new JSONObject().put("path", "NoFolder")), 404);

        ok(post("/api/playlists", target("Real", "")));
        assertEquals("Song not found", error(post("/api/playlists/songs/remove", target("Real", "").put("songId", "nosuchid")), 404));
        error(post("/api/playlists/songs/move", target("Real", "").put("songId", "nosuchid").put("toIndex", 0)), 404);
        error(post("/api/playlists/songs/retry", target("Real", "").put("songId", "nosuchid")), 404);
        error(post("/api/playlists/songs/remove", target("Real", "")), 400); // no song id
        error(post("/api/playlists/songs/move", target("Real", "").put("songId", "x")), 400); // no toIndex
        error(post("/api/playlists/songs/move", target("Real", "").put("songId", "x").put("toIndex", "first")), 400);
        error(post("/api/playlists/shuffle", target("Real", "")), 400); // no on
        error(post("/api/playlists/shuffle", target("Real", "").put("on", "yes")), 400);

        // a name that is taken is a conflict, not a bad request
        assertEquals("A playlist with that name already exists", error(post("/api/playlists", target("Real", "")), 409));
        ok(post("/api/playlists", target("Other", "")));
        error(post("/api/playlists/rename", target("Other", "").put("newName", "Real")), 409);
        assertEquals("only the two playlists that were made were announced", 2, broadcasts.size());
    }

    @Test
    public void twoConcurrentAddsBothLand() throws Exception
    {
        ok(post("/api/playlists", target("Shared", "")));
        int each = 10;
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            List<Future<Integer>> results = new ArrayList<>();
            for(String who : new String[]{"fred", "anna"})
                results.add(pool.submit((Callable<Integer>) () ->
                {
                    together.await(10, TimeUnit.SECONDS);
                    int failures = 0;
                    for(int i = 0; i < each; i++)
                        if(add("Shared", "", who + i).status != 200)
                            failures++;
                    return failures;
                }));
            for(Future<Integer> result : results)
                assertEquals("every add answered 200", 0, (int) result.get(60, TimeUnit.SECONDS));
        }
        finally
        {
            pool.shutdownNow();
        }

        JSONObject shared = item("Shared", "");
        List<String> titles = titles(shared);
        assertEquals(titles.toString(), 2 * each, titles.size());
        for(String who : new String[]{"fred", "anna"})
            for(int i = 0; i < each; i++)
                assertTrue(who + i + " is missing from " + titles, titles.contains(who + i));
        assertEquals("one version per change", 1 + 2 * each, shared.getLong("version"));
        Set<String> ids = new HashSet<>();
        JSONArray songs = shared.getJSONArray("songs");
        for(int i = 0; i < songs.length(); i++)
            assertTrue("ids are unique", ids.add(songs.getJSONObject(i).getString("id")));
    }

    @Test
    public void deleteRestoreViaApi() throws Exception
    {
        ok(post("/api/playlists", target("Chill", "Rock")));
        add("Chill", "Rock", "a", "b");
        clock.advance(Duration.ofMinutes(1));

        int mark = broadcasts.size();
        JSONObject gone = ok(post("/api/playlists/delete", target("Chill", "Rock")));
        assertEquals("Chill", gone.getString("name"));
        assertEquals("Rock", gone.getString("folder"));
        String id = gone.getString("id");
        assertFalse(id.isEmpty());
        assertTreeBroadcast(single(mark));
        error(get("/api/playlists/item?name=Chill&folder=Rock"), 404);

        JSONObject tree = ok(get("/api/playlists/tree"));
        assertEquals("the playlist left the tree", 0, tree.getJSONArray("playlists").length());
        JSONArray deleted = tree.getJSONArray("deleted");
        assertEquals(1, deleted.length());
        JSONObject entry = deleted.getJSONObject(0);
        assertEquals(id, entry.getString("id"));
        assertEquals("Chill", entry.getString("name"));
        assertEquals("Rock", entry.getString("originalFolder"));
        assertEquals("42", entry.getJSONObject("deletedBy").getString("id"));
        assertEquals("tester", entry.getJSONObject("deletedBy").getString("name"));
        assertEquals(T0.plusSeconds(60).toString(), entry.getString("deletedAt"));
        assertEquals(7, entry.getInt("daysLeft"));

        mark = broadcasts.size();
        JSONObject back = ok(post("/api/playlists/restore", new JSONObject().put("id", id)));
        assertEquals("Chill", back.getString("name"));
        assertEquals("Rock", back.getString("folder"));
        assertTrue("a restored playlist has a version", back.getLong("version") > 0);
        assertTreeBroadcast(single(mark));
        assertEquals(Arrays.asList("a", "b"), titles(item("Chill", "Rock")));
        assertEquals(0, ok(get("/api/playlists/tree")).getJSONArray("deleted").length());
        error(post("/api/playlists/restore", new JSONObject().put("id", id)), 404);

        // a name taken in the meantime: the answer says what it was restored as
        JSONObject second = ok(post("/api/playlists/delete", target("Chill", "Rock")));
        ok(post("/api/playlists", target("Chill", "Rock")));
        JSONObject clash = ok(post("/api/playlists/restore", new JSONObject().put("id", second.getString("id"))));
        assertEquals("Chill (restored)", clash.getString("name"));
        assertEquals("Rock", clash.getString("folder"));

        // the folder it came from is gone: it is made again, and the answer names it
        ok(post("/api/playlists/delete", target("Chill (restored)", "Rock")));
        ok(post("/api/playlists/delete", target("Chill", "Rock")));
        ok(post("/api/playlists/folder/delete", new JSONObject().put("path", "Rock")));
        assertEquals(0, ok(get("/api/playlists/tree")).getJSONArray("folders").length());
        String latest = ok(get("/api/playlists/tree")).getJSONArray("deleted").getJSONObject(0).getString("id");
        JSONObject recreated = ok(post("/api/playlists/restore", new JSONObject().put("id", latest)));
        assertEquals("Rock", recreated.getString("folder"));
        assertEquals(Arrays.asList("Rock"), jsonStrings(ok(get("/api/playlists/tree")).getJSONArray("folders")));
    }

    private static List<String> jsonStrings(JSONArray array)
    {
        List<String> strings = new ArrayList<>();
        for(int i = 0; i < array.length(); i++)
            strings.add(array.getString(i));
        return strings;
    }

    @Test
    public void migrationProgressEndpoint() throws Exception
    {
        JSONObject idle = ok(get("/api/playlists/migration"));
        assertTrue(idle.toString(), idle.similar(new JSONObject().put("total", 0).put("done", 0).put("failed", 0)));

        writeTxt("Fine.txt", "https://example.com/a\n");
        writeTxt("Rap/Also fine.txt", "https://example.com/b\n");
        writeTxt("Broken.txt", "boom\n");
        resolver.script = line ->
        {
            if(line.contains("boom"))
                throw new IllegalStateException("the resolver is broken");
            return Resolved.ok(Collections.singletonList(resolved(line)), null);
        };
        assertEquals(2, converter.convertPending());

        JSONObject progress = ok(get("/api/playlists/migration"));
        assertTrue(progress.toString(), progress.similar(new JSONObject().put("total", 3).put("done", 2).put("failed", 1)));
        JSONArray listed = ok(get("/api/playlists/tree")).getJSONArray("playlists");
        int stillLegacy = 0;
        for(int i = 0; i < listed.length(); i++)
            if(listed.getJSONObject(i).getBoolean("legacy"))
                stillLegacy++;
        assertEquals("the playlist that was given up on stays as it was", 1, stillLegacy);
    }

    @Test
    public void oldEndpointsStillWork() throws Exception
    {
        ok(post("/api/playlists", target("Chill", "")));
        add("Chill", "", "a", "b");
        ok(post("/api/playlists", target("Rap", "Fred/Old")));
        add("Rap", "Fred/Old", "c");
        writeTxt("Legacy.txt", "https://example.com/l1\nlofi beats\n#shuffle\n");
        ok(post("/api/playlists/folder", new JSONObject().put("path", "Empty")));

        // /api/playlists-tree: nested nodes, playlists with trackCount and items
        JSONObject rootNode = ok(get("/api/playlists-tree"));
        assertEquals("", rootNode.getString("folder"));
        assertFalse("the root has no name", rootNode.has("name"));
        assertEquals(2, rootNode.getInt("playlistCount"));
        JSONArray rootPlaylists = rootNode.getJSONArray("playlists");
        assertEquals(2, rootPlaylists.length());
        JSONObject chill = rootPlaylists.getJSONObject(0);
        assertEquals("Chill", chill.getString("name"));
        assertEquals("", chill.getString("folder"));
        assertEquals(2, chill.getInt("trackCount"));
        assertEquals(Arrays.asList("https://example.com/a", "https://example.com/b"), uris(chill.getJSONArray("items")));
        JSONObject legacy = rootPlaylists.getJSONObject(1);
        assertEquals("Legacy", legacy.getString("name"));
        assertEquals(2, legacy.getInt("trackCount"));
        assertEquals(Arrays.asList("https://example.com/l1", "lofi beats"), uris(legacy.getJSONArray("items")));
        assertEquals(2, rootNode.getInt("subFolderCount"));
        JSONArray top = rootNode.getJSONArray("folders");
        assertEquals("Empty", top.getJSONObject(0).getString("name"));
        assertEquals(0, top.getJSONObject(0).getInt("playlistCount"));
        JSONObject fred = top.getJSONObject(1);
        assertEquals("Fred", fred.getString("name"));
        assertEquals("Fred", fred.getString("folder"));
        assertEquals(1, fred.getInt("subFolderCount"));
        JSONObject old = fred.getJSONArray("folders").getJSONObject(0);
        assertEquals("Old", old.getString("name"));
        assertEquals("Fred/Old", old.getString("folder"));
        assertEquals(1, old.getInt("playlistCount"));
        assertEquals("Rap", old.getJSONArray("playlists").getJSONObject(0).getString("name"));
        assertEquals("Fred/Old", old.getJSONArray("playlists").getJSONObject(0).getString("folder"));
        assertEquals(1, old.getJSONArray("playlists").getJSONObject(0).getInt("trackCount"));
        assertEquals(0, old.getJSONArray("folders").length());

        // /api/playlist-detail/{name}: the items of a v2 playlist are its song uris
        JSONObject detail = ok(get("/api/playlist-detail/Rap?folder=" + enc("Fred/Old")));
        assertEquals("Rap", detail.getString("name"));
        assertEquals("Fred/Old", detail.getString("folder"));
        assertEquals(Arrays.asList("https://example.com/c"), uris(detail.getJSONArray("items")));
        assertEquals(1, detail.getInt("trackCount"));
        assertFalse(detail.getBoolean("shuffle"));
        JSONObject legacyDetail = ok(get("/api/playlist-detail/Legacy"));
        assertTrue("the #shuffle line of a .txt", legacyDetail.getBoolean("shuffle"));
        assertEquals(2, legacyDetail.getInt("trackCount"));

        // /api/playlist/{name}
        JSONObject plain = ok(get("/api/playlist/Chill"));
        assertEquals("Chill", plain.getString("name"));
        assertEquals("", plain.getString("folder"));
        assertEquals(2, plain.getJSONArray("items").length());
        assertFalse(plain.has("trackCount"));
        ok(post("/api/playlists/shuffle", target("Chill", "").put("on", true)));
        assertTrue(ok(get("/api/playlist/Chill")).getBoolean("shuffle"));
        error(get("/api/playlist/Nope"), 404);
        error(get("/api/playlist-detail/Nope"), 404);
        error(get("/api/playlist/Rap"), 404); // it is in a folder

        // /api/playlists?folder=
        JSONArray topLevel = ok2(get("/api/playlists"));
        assertEquals(2, topLevel.length());
        assertEquals("Chill", topLevel.getJSONObject(0).getString("name"));
        assertEquals(2, topLevel.getJSONObject(0).getInt("trackCount"));
        assertEquals(2, topLevel.getJSONObject(0).getJSONArray("items").length());
        JSONArray inOld = ok2(get("/api/playlists?folder=" + enc("Fred/Old")));
        assertEquals(1, inOld.length());
        assertEquals("Rap", inOld.getJSONObject(0).getString("name"));
        assertEquals("Fred/Old", inOld.getJSONObject(0).getString("folder"));
        assertEquals(0, ok2(get("/api/playlists?folder=Empty")).length());

        // /api/playlist-folders?parent=
        JSONArray folders = ok2(get("/api/playlist-folders"));
        assertEquals(2, folders.length());
        assertEquals("Empty", folders.getJSONObject(0).getString("name"));
        assertEquals("Empty", folders.getJSONObject(0).getString("path"));
        assertEquals(0, folders.getJSONObject(0).getInt("playlistCount"));
        assertEquals(0, folders.getJSONObject(0).getInt("subFolderCount"));
        assertEquals(1, folders.getJSONObject(1).getInt("subFolderCount"));
        JSONArray below = ok2(get("/api/playlist-folders?parent=Fred"));
        assertEquals(1, below.length());
        assertEquals("Old", below.getJSONObject(0).getString("name"));
        assertEquals("Fred/Old", below.getJSONObject(0).getString("path"));
        assertEquals(1, below.getJSONObject(0).getInt("playlistCount"));
        assertEquals(0, ok2(get("/api/playlist-folders?parent=Empty")).length());
    }

    /** A 200 answer that is a JSON array; returns it. */
    private static JSONArray ok2(Resp resp)
    {
        assertEquals(resp.body, 200, resp.status);
        assertTrue(resp.contentType, resp.contentType.startsWith("application/json"));
        return resp.array();
    }

    @Test
    public void oldWritingEndpointsAreThinWrappers() throws Exception
    {
        // saving a whole list under a new name = create + add; what can't be a song is skipped
        int mark = broadcasts.size();
        JSONObject saved = ok(post("/api/playlist/Fresh", new JSONObject().put("folder", "Rock")
                .put("items", new JSONArray(Arrays.asList("https://example.com/x", "not a url", "  https://example.com/y  ", "", "ytsearch:hello")))));
        assertEquals("Playlist saved", saved.getString("message"));
        assertEquals("Fresh", saved.getString("name"));
        assertEquals("Rock", saved.getString("folder"));
        assertEquals(1, saved.getInt("skipped"));
        assertTreeBroadcast(single(mark));
        PlaylistFile fresh = store.get("Fresh", "Rock").get();
        assertEquals(3, fresh.getSongs().size());
        assertEquals("https://example.com/y", fresh.getSongs().get(1).getUri());
        assertEquals("the item is its own title", "https://example.com/y", fresh.getSongs().get(1).getTitle());
        assertEquals("tester", fresh.getSongs().get(0).getAddedBy().getName());
        assertEquals("tester", fresh.getCreatedBy().getName());

        // an existing playlist is not rewritten from a stale page
        assertEquals("Reload the page to edit playlists", error(post("/api/playlist/Fresh", new JSONObject().put("folder", "Rock")
                .put("items", new JSONArray(Arrays.asList("https://example.com/z")))), 409));
        assertEquals(3, store.get("Fresh", "Rock").get().getSongs().size());
        error(post("/api/playlist/Other", new JSONObject().put("folder", "Rock")), 400); // no items
        error(post("/api/playlist/Other", new JSONObject().put("items", "nope")), 400);
        error(post("/api/playlist/.deleted", new JSONObject().put("items", new JSONArray())), 400); // a reserved name
        assertTrue(ok(post("/api/playlist/Blank", new JSONObject().put("items", new JSONArray()))).has("message"));
        assertEquals("an empty list makes an empty playlist", 0, store.get("Blank", "").get().getSongs().size());

        // folders
        mark = broadcasts.size();
        JSONObject folder = ok(post("/api/playlist-folder", new JSONObject().put("name", " New ").put("parent", "Rock")));
        assertEquals("Folder created", folder.getString("message"));
        assertEquals("Rock/New", folder.getString("path"));
        assertTreeBroadcast(single(mark));
        assertTrue(store.folders().contains("Rock/New"));
        error(post("/api/playlist-folder", new JSONObject().put("name", "  ")), 400);
        error(post("/api/playlist-folder", new JSONObject().put("name", "../x")), 400);
        mark = broadcasts.size();
        JSONObject folderGone = ok(call("DELETE", "/api/playlist-folder", new JSONObject().put("path", "Rock/New")));
        assertEquals("Folder deleted", folderGone.getString("message"));
        assertEquals("Rock/New", folderGone.getString("path"));
        assertTreeBroadcast(single(mark));
        assertFalse(store.folders().contains("Rock/New"));
        error(call("DELETE", "/api/playlist-folder", new JSONObject().put("path", "Rock/New")), 404);
        error(call("DELETE", "/api/playlist-folder", new JSONObject()), 400);

        // move, rename
        mark = broadcasts.size();
        JSONObject moved = ok(call("PUT", "/api/playlist-move", new JSONObject().put("name", "Fresh").put("fromFolder", "Rock").put("toFolder", "Pop")));
        assertEquals("Playlist moved", moved.getString("message"));
        assertEquals("Fresh", moved.getString("name"));
        assertEquals("Pop", moved.getString("folder"));
        assertTreeBroadcast(single(mark));
        assertTrue(store.get("Fresh", "Pop").isPresent());
        assertFalse(store.get("Fresh", "Rock").isPresent());
        error(call("PUT", "/api/playlist-move", new JSONObject().put("name", "Fresh").put("fromFolder", "Rock").put("toFolder", "Pop")), 404);
        error(call("PUT", "/api/playlist-move", new JSONObject().put("fromFolder", "Rock")), 400);
        mark = broadcasts.size();
        JSONObject renamed = ok(call("PUT", "/api/playlist-rename/Fresh", new JSONObject().put("newName", " Fresher ").put("folder", "Pop")));
        assertEquals("Playlist renamed", renamed.getString("message"));
        assertEquals("Fresher", renamed.getString("name"));
        assertEquals("Pop", renamed.getString("folder"));
        assertTreeBroadcast(single(mark));
        assertEquals(3, store.get("Fresher", "Pop").get().getSongs().size());
        error(call("PUT", "/api/playlist-rename/Fresher", new JSONObject().put("folder", "Pop")), 400);
        error(call("PUT", "/api/playlist-rename/Nope", new JSONObject().put("newName", "X").put("folder", "Pop")), 404);

        // delete goes to the Deleted folder
        mark = broadcasts.size();
        JSONObject deleted = ok(call("DELETE", "/api/playlist/Fresher?folder=Pop", null));
        assertEquals("Playlist deleted", deleted.getString("message"));
        assertEquals("Fresher", deleted.getString("name"));
        assertTreeBroadcast(single(mark));
        assertFalse(store.get("Fresher", "Pop").isPresent());
        assertEquals(1, store.deleted().size());
        assertEquals("Fresher", store.deleted().get(0).getName());
        error(call("DELETE", "/api/playlist/Fresher?folder=Pop", null), 404);
    }

    @Test
    public void oldPlayEndpointUsesTheHookInPlayAllMode() throws Exception
    {
        JSONObject answer = ok(post("/api/guild/g1/playplaylist/" + enc("Late night") + "?folder=" + enc("Fred/Old"), null));
        assertEquals("Loading playlist Late night...", answer.getString("message"));
        assertEquals(1, plays.size());
        assertEquals("g1", plays.get(0).guildId);
        assertEquals("Late night", plays.get(0).name);
        assertEquals("Fred/Old", plays.get(0).folder);
        assertEquals("all", plays.get(0).mode);
        assertNull(plays.get(0).songId);

        ok(post("/api/guild/g2/playplaylist/Chill", null));
        assertEquals("", plays.get(1).folder);
        assertEquals("g2", plays.get(1).guildId);
    }

    // ---- undo, retry -------------------------------------------------------------------------------------------

    @Test
    public void undoRestoresTheExactSongAndIsSingleUse() throws Exception
    {
        ok(post("/api/playlists", target("Mix", "")));
        clock.advance(Duration.ofSeconds(60));
        add("Mix", "", "a", "b", "c");
        JSONObject before = item("Mix", "");
        JSONObject songB = before.getJSONArray("songs").getJSONObject(1);
        String songId = songB.getString("id");

        clock.advance(Duration.ofSeconds(120)); // addedAt must stay at the first add, not move to the undo
        JSONObject removed = ok(post("/api/playlists/songs/remove", target("Mix", "").put("songId", songId)));
        String token = removed.getString("undo");
        assertEquals(Arrays.asList("a", "c"), titles(item("Mix", "")));

        clock.advance(Duration.ofSeconds(30));
        int mark = broadcasts.size();
        // a client can say anything it likes about who added the song; it is not believed
        JSONObject forged = new JSONObject().put("token", token).put("addedBy", new JSONObject().put("id", "666").put("name", "mallory"))
                .put("song", songJson("evil")).put("index", 0);
        JSONObject undone = ok(post("/api/playlists/songs/undo", forged));
        assertEquals("Mix", undone.getString("name"));
        assertEquals(removed.getLong("version") + 1, undone.getLong("version"));
        assertContentBroadcast(single(mark), "Mix", "", undone.getLong("version"));

        JSONObject after = item("Mix", "");
        assertEquals(Arrays.asList("a", "b", "c"), titles(after));
        assertTrue("the song is exactly as it was (id, addedBy, addedAt, details): " + after.getJSONArray("songs").getJSONObject(1),
                songB.similar(after.getJSONArray("songs").getJSONObject(1)));

        // a token works once
        assertEquals("Too late to undo", error(post("/api/playlists/songs/undo", new JSONObject().put("token", token)), 404));
        assertEquals(Arrays.asList("a", "b", "c"), titles(item("Mix", "")));
    }

    @Test
    public void undoTokensExpireAfterAMinute() throws Exception
    {
        ok(post("/api/playlists", target("Mix", "")));
        add("Mix", "", "a", "b");
        String late = ok(post("/api/playlists/songs/remove", target("Mix", "").put("songId", idOf(item("Mix", ""), "a")))).getString("undo");
        clock.advance(Duration.ofSeconds(61));
        String fresh = ok(post("/api/playlists/songs/remove", target("Mix", "").put("songId", idOf(item("Mix", ""), "b")))).getString("undo");
        assertNotEquals("tokens are not guessable from each other", late, fresh);

        assertEquals("Too late to undo", error(post("/api/playlists/songs/undo", new JSONObject().put("token", late)), 404));
        assertEquals(0, item("Mix", "").getJSONArray("songs").length());
        ok(post("/api/playlists/songs/undo", new JSONObject().put("token", fresh)));
        assertEquals(Arrays.asList("b"), titles(item("Mix", "")));

        error(post("/api/playlists/songs/undo", new JSONObject().put("token", "never-issued")), 404);
        error(post("/api/playlists/songs/undo", new JSONObject()), 400);
    }

    @Test
    public void undoCanFailWithoutLosingTheToken() throws Exception
    {
        ok(post("/api/playlists", target("Mix", "")));
        add("Mix", "", "a");
        String token = ok(post("/api/playlists/songs/remove", target("Mix", "").put("songId", idOf(item("Mix", ""), "a")))).getString("undo");
        ok(post("/api/playlists/delete", target("Mix", "")));

        assertEquals("Playlist not found", error(post("/api/playlists/songs/undo", new JSONObject().put("token", token)), 404));
        // the playlist comes back (a new one with the old name): the same token still works
        ok(post("/api/playlists", target("Mix", "")));
        ok(post("/api/playlists/songs/undo", new JSONObject().put("token", token)));
        assertEquals(Arrays.asList("a"), titles(item("Mix", "")));
    }

    @Test
    public void retryReplacesAnUnresolvedSong() throws Exception
    {
        ok(post("/api/playlists", target("Old stuff", "")));
        Song lost = stored("ytsearch:sad song", "sad song");
        lost.setUnresolved(true);
        store.addSongs("Old stuff", "", Arrays.asList(stored("https://example.com/fine", "fine"), lost), null, TESTER);
        JSONObject before = item("Old stuff", "");
        String lostId = idOf(before, "sad song");
        long version = before.getLong("version");
        Song found = new Song();
        found.setUri("https://www.youtube.com/watch?v=real");
        found.setTitle("Real Title");
        found.setAuthor("Real Artist");
        found.setDurationMs(215000);
        found.setArtworkUrl("https://i.ytimg.com/real.jpg");
        resolver.script = line -> Resolved.ok(Arrays.asList(TrackResolver.unresolvedSong("junk", line), found), null);

        int mark = broadcasts.size();
        JSONObject answer = ok(post("/api/playlists/songs/retry", target("Old stuff", "").put("songId", lostId)));

        assertEquals(Arrays.asList("ytsearch:sad song"), resolver.lines);
        assertEquals(version + 1, answer.getLong("version"));
        assertContentBroadcast(single(mark), "Old stuff", "", version + 1);
        JSONObject after = item("Old stuff", "");
        JSONObject song = after.getJSONArray("songs").getJSONObject(1);
        assertEquals("the entry keeps its id", lostId, song.getString("id"));
        assertEquals("Real Title", song.getString("title"));
        assertEquals("Real Artist", song.getString("author"));
        assertEquals("https://www.youtube.com/watch?v=real", song.getString("uri"));
        assertEquals(215000, song.getLong("durationMs"));
        assertEquals("https://i.ytimg.com/real.jpg", song.getString("artworkUrl"));
        assertFalse(song.getBoolean("unresolved"));
        assertEquals("tester", song.getJSONObject("addedBy").getString("name"));
        assertEquals(2, after.getJSONArray("songs").length());

        // now that it is loaded there is nothing to retry
        resolver.lines.clear();
        error(post("/api/playlists/songs/retry", target("Old stuff", "").put("songId", lostId)), 409);
        assertTrue("nothing was looked up", resolver.lines.isEmpty());
    }

    @Test
    public void retryThatFindsNothingIsAConflict() throws Exception
    {
        ok(post("/api/playlists", target("Old stuff", "")));
        Song lost = stored("ytsearch:sad song", "sad song");
        lost.setUnresolved(true);
        store.addSongs("Old stuff", "", Collections.singletonList(lost), null, TESTER);
        String lostId = idOf(item("Old stuff", ""), "sad song");
        long version = item("Old stuff", "").getLong("version");

        resolver.script = line -> Resolved.failed("No matches");
        assertEquals("Still couldn't load this song", error(post("/api/playlists/songs/retry", target("Old stuff", "").put("songId", lostId)), 409));
        resolver.script = line -> Resolved.ok(Collections.singletonList(TrackResolver.unresolvedSong("x", line)), null);
        assertEquals("Still couldn't load this song", error(post("/api/playlists/songs/retry", target("Old stuff", "").put("songId", lostId)), 409));
        assertEquals("nothing was written", version, item("Old stuff", "").getLong("version"));
    }

    // ---- what clients may put in a playlist (spec section 9, R16) ----------------------------------------------

    @Test
    public void songUrisMustBeWebLinksOrSearches() throws Exception
    {
        ok(post("/api/playlists", target("Safe", "")));
        String[] refused = {"file:///etc/passwd", "/etc/passwd", "C:\\Windows\\win.ini", "../../secret.m4a", "javascript:alert(1)",
                "ftp://example.com/a.mp3", "http://", "https://", "https:///nohost", "http://example.com/a b", "data:text/html,hi",
                "ytsearch:", "  ", "", "ytsearch:line\nbreak", "YTSEARCH-nope"};
        for(String uri : refused)
            assertEquals(uri, BAD_SONG, error(post("/api/playlists/songs/add",
                    target("Safe", "").put("songs", new JSONArray().put(new JSONObject().put("uri", uri).put("title", "x")))), 400));
        assertEquals(BAD_SONG, error(post("/api/playlists/songs/add",
                target("Safe", "").put("songs", new JSONArray().put(new JSONObject().put("title", "no uri")))), 400));
        assertEquals(BAD_SONG, error(post("/api/playlists/songs/add",
                target("Safe", "").put("songs", new JSONArray().put(new JSONObject().put("uri", 5)))), 400));
        assertEquals("nothing got in", 0, store.get("Safe", "").get().getSongs().size());
        assertEquals(1, store.get("Safe", "").get().getVersion());

        // a batch is all or nothing
        JSONArray mixed = new JSONArray().put(songJson("good")).put(new JSONObject().put("uri", "file:///etc/passwd").put("title", "bad"));
        error(post("/api/playlists/songs/add", target("Safe", "").put("songs", mixed)), 400);
        assertEquals(0, store.get("Safe", "").get().getSongs().size());

        String[] accepted = {"https://youtu.be/x", "HTTP://example.com/a.mp3", "http://example.com:8080/path?q=1#frag",
                "ytsearch:lofi beats", "ytmsearch:song name", "scsearch:remix"};
        JSONArray good = new JSONArray();
        for(String uri : accepted)
            good.put(new JSONObject().put("uri", uri).put("title", uri));
        ok(post("/api/playlists/songs/add", target("Safe", "").put("songs", good)));
        assertEquals(Arrays.asList(accepted), titles(item("Safe", "")));
    }

    @Test
    public void localFilesAreAcceptedOnlyWhenAlreadyStored() throws Exception
    {
        String local = "/home/cloutkings/music/Artist/song.m4a";
        String oldLine = "/home/cloutkings/music/Artist/from-a-txt.m4a";
        ok(post("/api/playlists", target("Library", "Rock")));
        ok(post("/api/playlists", target("Target", "")));
        store.addSongs("Library", "Rock", Collections.singletonList(stored(local, "song")), null, TESTER);
        writeTxt("Rap/Old.txt", oldLine + "\n");
        JSONArray songs = new JSONArray().put(new JSONObject().put("uri", local).put("title", "song"))
                .put(new JSONObject().put("uri", oldLine).put("title", "from a txt"));

        ok(post("/api/playlists/songs/add", target("Target", "").put("songs", songs)));
        assertEquals(new HashSet<>(Arrays.asList(local, oldLine)), storedUris("Target", ""));

        // any other path, or a near miss, is refused
        for(String other : new String[]{"/home/cloutkings/music/Artist/other.m4a", local + "x", " " + local.toUpperCase(), "/etc/passwd"})
            assertEquals(other, BAD_SONG, error(post("/api/playlists/songs/add", target("Target", "").put("songs",
                    new JSONArray().put(new JSONObject().put("uri", other).put("title", "t")))), 400));
        assertEquals(2, store.get("Target", "").get().getSongs().size());

        // a deleted playlist's songs don't count as stored
        String doomed = "/home/cloutkings/music/Artist/doomed.m4a";
        ok(post("/api/playlists", target("Doomed", "")));
        store.addSongs("Doomed", "", Collections.singletonList(stored(doomed, "doomed")), null, TESTER);
        ok(post("/api/playlists/delete", target("Doomed", "")));
        error(post("/api/playlists/songs/add", target("Target", "").put("songs",
                new JSONArray().put(new JSONObject().put("uri", doomed).put("title", "doomed")))), 400);
        assertEquals(2, store.get("Target", "").get().getSongs().size());
    }

    @Test
    public void songFieldsAreCleaned() throws Exception
    {
        ok(post("/api/playlists", target("Clean", "")));
        StringBuilder longTitle = new StringBuilder();
        for(int i = 0; i < 400; i++)
            longTitle.append('t');
        JSONArray songs = new JSONArray()
                .put(songJson("kept"))
                .put(songJson("badart").put("artworkUrl", "javascript:alert(1)"))
                .put(songJson("dataart").put("artworkUrl", "data:image/png;base64,AAAA"))
                .put(songJson("negative").put("durationMs", -5))
                .put(songJson("texty").put("durationMs", "soon"))
                .put(new JSONObject().put("uri", "https://example.com/bare"))
                .put(songJson("forged").put("addedBy", new JSONObject().put("id", "666").put("name", "mallory"))
                        .put("id", "forgedid1").put("unresolved", true).put("addedAt", "2001-01-01T00:00:00Z"))
                .put(new JSONObject().put("uri", "https://example.com/long").put("title", longTitle.toString()));
        ok(post("/api/playlists/songs/add", target("Clean", "").put("songs", songs)));

        JSONArray stored = item("Clean", "").getJSONArray("songs");
        assertEquals("https://example.com/kept.png", stored.getJSONObject(0).getString("artworkUrl"));
        assertTrue("a javascript: artwork link is dropped", stored.getJSONObject(1).isNull("artworkUrl"));
        assertTrue("so is a data: one", stored.getJSONObject(2).isNull("artworkUrl"));
        assertEquals("a negative length is none", 0, stored.getJSONObject(3).getLong("durationMs"));
        assertEquals("so is one that is no number", 0, stored.getJSONObject(4).getLong("durationMs"));
        assertEquals("a song with no title is shown by its link", "https://example.com/bare", stored.getJSONObject(5).getString("title"));
        assertEquals(0, stored.getJSONObject(5).getLong("durationMs"));
        JSONObject forged = stored.getJSONObject(6);
        assertEquals("the server decides who added a song", "42", forged.getJSONObject("addedBy").getString("id"));
        assertNotEquals("and what its id is", "forgedid1", forged.getString("id"));
        assertFalse("and whether it loaded", forged.getBoolean("unresolved"));
        assertEquals(T0.toString(), forged.getString("addedAt"));
        assertEquals("text is cut", 300, stored.getJSONObject(7).getString("title").length());
    }

    @Test
    public void youtubeSongWithoutArtworkGetsItsThumbnail() throws Exception
    {
        ok(post("/api/playlists", target("Thumbs", "")));
        JSONArray songs = new JSONArray()
                .put(new JSONObject().put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ").put("title", "watch"))
                .put(new JSONObject().put("uri", "https://youtu.be/9bZkp7q19f0?t=5").put("title", "short link"))
                .put(new JSONObject().put("uri", "https://music.youtube.com/watch?list=PLx&v=kJQP7kiw5Fk&feature=share").put("title", "music"))
                .put(new JSONObject().put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ").put("title", "bad art")
                        .put("artworkUrl", "javascript:alert(1)"))
                .put(new JSONObject().put("uri", "https://www.youtube.com/watch?v=dQw4w9WgXcQ").put("title", "own art")
                        .put("artworkUrl", "https://example.com/own.png"))
                .put(new JSONObject().put("uri", "https://www.youtube.com/playlist?list=PLalbum").put("title", "a list"))
                .put(new JSONObject().put("uri", "https://soundcloud.com/artist/track").put("title", "soundcloud"))
                .put(new JSONObject().put("uri", "https://example.com/watch?v=dQw4w9WgXcQ").put("title", "elsewhere"))
                .put(new JSONObject().put("uri", "ytsearch:Dr Dre - Still").put("title", "search"));
        ok(post("/api/playlists/songs/add", target("Thumbs", "").put("songs", songs)));

        JSONArray stored = item("Thumbs", "").getJSONArray("songs");
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", stored.getJSONObject(0).getString("artworkUrl"));
        assertEquals("a youtu.be link too", "https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg", stored.getJSONObject(1).getString("artworkUrl"));
        assertEquals("the video of a link that also names a list", "https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg",
                stored.getJSONObject(2).getString("artworkUrl"));
        assertEquals("a refused artwork link is replaced", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                stored.getJSONObject(3).getString("artworkUrl"));
        assertEquals("the client's own artwork wins", "https://example.com/own.png", stored.getJSONObject(4).getString("artworkUrl"));
        assertTrue("a list link is no video", stored.getJSONObject(5).isNull("artworkUrl"));
        assertTrue("a SoundCloud link stays without", stored.getJSONObject(6).isNull("artworkUrl"));
        assertTrue("so does a look-alike on another site", stored.getJSONObject(7).isNull("artworkUrl"));
        assertTrue("and a search", stored.getJSONObject(8).isNull("artworkUrl"));
    }

    @Test
    public void songListsAreBounded() throws Exception
    {
        ok(post("/api/playlists", target("Bounded", "")));
        error(post("/api/playlists/songs/add", target("Bounded", "").put("songs", new JSONArray())), 400);
        error(post("/api/playlists/songs/add", target("Bounded", "")), 400);
        error(post("/api/playlists/songs/add", target("Bounded", "").put("songs", "https://example.com/a")), 400);
        error(post("/api/playlists/songs/add", target("Bounded", "").put("songs", new JSONArray().put("https://example.com/a"))), 400);
        error(post("/api/playlists/songs/add", target("Bounded", "").put("songs", songsJson("a")).put("index", "first")), 400);
        JSONArray tooMany = new JSONArray();
        for(int i = 0; i < 2001; i++)
            tooMany.put(new JSONObject().put("uri", "https://example.com/" + i).put("title", "s" + i));
        error(post("/api/playlists/songs/add", target("Bounded", "").put("songs", tooMany)), 400);
        assertEquals(0, store.get("Bounded", "").get().getSongs().size());

        // a full playlist is a conflict (R4), and nothing is added
        JSONArray full = new JSONArray();
        for(int i = 0; i < 2000; i++)
            full.put(new JSONObject().put("uri", "https://example.com/" + i).put("title", "s" + i));
        ok(post("/api/playlists/songs/add", target("Bounded", "").put("songs", full)));
        long version = item("Bounded", "").getLong("version");
        assertEquals("Playlist is full", error(add("Bounded", "", "onemore"), 409));
        assertEquals(2000, item("Bounded", "").getJSONArray("songs").length());
        assertEquals(version, item("Bounded", "").getLong("version"));
    }

    @Test
    public void legacyPlaylistsReadButDoNotEdit() throws Exception
    {
        writeTxt("Rap/Old.txt", "https://example.com/a\nlofi beats\n");

        JSONObject view = item("Old", "Rap");
        assertTrue(view.getBoolean("legacy"));
        assertEquals("Rap", view.getString("folder"));
        assertEquals(Arrays.asList("https://example.com/a", "lofi beats"), titles(view));
        assertTrue(view.getJSONArray("songs").getJSONObject(0).getBoolean("unresolved"));
        JSONObject entry = ok(get("/api/playlists/tree")).getJSONArray("playlists").getJSONObject(0);
        assertTrue(entry.getBoolean("legacy"));
        assertEquals(2, entry.getInt("songCount"));

        assertEquals(UPGRADING, error(add("Old", "Rap", "x"), 409));
        assertEquals(UPGRADING, error(post("/api/playlists/shuffle", target("Old", "Rap").put("on", true)), 409));
        assertEquals(UPGRADING, error(post("/api/playlists/delete", target("Old", "Rap")), 409));
        assertEquals(UPGRADING, error(post("/api/playlists/songs/retry", target("Old", "Rap")
                .put("songId", view.getJSONArray("songs").getJSONObject(0).getString("id"))), 409));
        assertTrue("a legacy retry never goes looking", resolver.lines.isEmpty());
        assertTrue(broadcasts.isEmpty());
    }

    // ---- tree, folders, play, import ---------------------------------------------------------------------------

    @Test
    public void treeListsFoldersPlaylistsAndDeleted() throws Exception
    {
        ok(post("/api/playlists", target("Zed", "")));
        ok(post("/api/playlists", target("Alpha", "Rock/Deep")));
        store.addSongs("Alpha", "Rock/Deep", Arrays.asList(stored("https://example.com/1", "one"), stored("https://example.com/2", "two")), null, null);
        clock.advance(Duration.ofSeconds(90));
        user.set(null); // web login off: nobody to credit
        ok(post("/api/playlists", target("Anon", "")));

        JSONObject tree = ok(get("/api/playlists/tree"));
        assertEquals(Arrays.asList("Rock", "Rock/Deep"), jsonStrings(tree.getJSONArray("folders")));
        JSONArray playlists = tree.getJSONArray("playlists");
        assertEquals(3, playlists.length());
        JSONObject anon = playlists.getJSONObject(0);
        assertEquals("Anon", anon.getString("name"));
        assertEquals("", anon.getString("folder"));
        assertEquals(0, anon.getInt("songCount"));
        assertEquals(0, anon.getLong("durationMs"));
        assertTrue("createdBy is there, and null", anon.has("createdBy") && anon.isNull("createdBy"));
        assertEquals(T0.plusSeconds(90).toString(), anon.getString("updatedAt"));
        assertFalse(anon.getBoolean("legacy"));
        assertEquals("Zed", playlists.getJSONObject(1).getString("name"));
        assertEquals("tester", playlists.getJSONObject(1).getJSONObject("createdBy").getString("name"));
        assertEquals("42", playlists.getJSONObject(1).getJSONObject("createdBy").getString("id"));
        JSONObject alpha = playlists.getJSONObject(2);
        assertEquals("Alpha", alpha.getString("name"));
        assertEquals("Rock/Deep", alpha.getString("folder"));
        assertEquals(2, alpha.getInt("songCount"));
        assertEquals(2000, alpha.getLong("durationMs"));
        assertEquals(0, tree.getJSONArray("deleted").length());

        ok(post("/api/playlists/delete", target("Zed", "")));
        JSONObject entry = ok(get("/api/playlists/tree")).getJSONArray("deleted").getJSONObject(0);
        assertTrue("deleted by nobody is null", entry.has("deletedBy") && entry.isNull("deletedBy"));
    }

    @Test
    public void folderEndpoints() throws Exception
    {
        int mark = broadcasts.size();
        JSONObject made = ok(post("/api/playlists/folder", new JSONObject().put("path", " Rock/Deep ")));
        assertEquals("Rock/Deep", made.getString("path"));
        assertTreeBroadcast(single(mark));
        assertEquals(Arrays.asList("Rock", "Rock/Deep"), store.folders());
        ok(post("/api/playlists/folder", new JSONObject().put("path", "Rock/Deep"))); // again: nothing to do
        error(post("/api/playlists/folder", new JSONObject().put("path", "  ")), 400);
        error(post("/api/playlists/folder", new JSONObject()), 400);
        error(post("/api/playlists/folder", new JSONObject().put("path", "../out")), 400);
        error(post("/api/playlists/folder", new JSONObject().put("path", ".legacy-txt")), 400);
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("out")));

        ok(post("/api/playlists", target("One", "Rock")));
        ok(post("/api/playlists", target("Two", "Rock/Deep")));
        mark = broadcasts.size();
        JSONObject gone = ok(post("/api/playlists/folder/delete", new JSONObject().put("path", "Rock")));
        assertEquals("Rock", gone.getString("path"));
        assertEquals(2, gone.getInt("moved"));
        assertTreeBroadcast(single(mark));
        assertTrue(store.folders().isEmpty());
        JSONArray deleted = ok(get("/api/playlists/tree")).getJSONArray("deleted");
        assertEquals(2, deleted.length());
        Set<String> from = new HashSet<>();
        for(int i = 0; i < deleted.length(); i++)
            from.add(deleted.getJSONObject(i).getString("originalFolder") + "|" + deleted.getJSONObject(i).getString("name"));
        assertEquals(new HashSet<>(Arrays.asList("Rock|One", "Rock/Deep|Two")), from);

        error(post("/api/playlists/folder/delete", new JSONObject().put("path", "Rock")), 404);
        error(post("/api/playlists/folder/delete", new JSONObject().put("path", "")), 400);
        error(post("/api/playlists/folder/delete", new JSONObject()), 400);
        writeTxt("Old/Legacy.txt", "https://example.com/a\n");
        assertEquals(UPGRADING, error(post("/api/playlists/folder/delete", new JSONObject().put("path", "Old")), 409));
        assertTrue(Files.exists(root.resolve("Old").resolve("Legacy.txt")));
    }

    @Test
    public void playEndpointCallsTheHook() throws Exception
    {
        JSONObject answer = ok(post("/api/guild/g42/playlists/play",
                target("Chill", "Fred").put("mode", "from").put("songId", "abc12345")));
        assertEquals("Loading playlist Chill...", answer.getString("message"));
        assertEquals(1, plays.size());
        assertEquals("g42", plays.get(0).guildId);
        assertEquals("Chill", plays.get(0).name);
        assertEquals("Fred", plays.get(0).folder);
        assertEquals("from", plays.get(0).mode);
        assertEquals("abc12345", plays.get(0).songId);

        ok(post("/api/guild/g42/playlists/play", new JSONObject().put("name", "Chill").put("mode", "shuffle")));
        assertEquals("", plays.get(1).folder);
        assertEquals("shuffle", plays.get(1).mode);
        assertNull(plays.get(1).songId);
        ok(post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "all")));
        assertEquals("all", plays.get(2).mode);
        assertEquals(3, plays.size());
        assertTrue("playing changes no playlist", broadcasts.isEmpty());

        // bad requests never reach the hook
        error(post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "backwards")), 400);
        error(post("/api/guild/g42/playlists/play", target("Chill", "")), 400);
        error(post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "from")), 400); // no song to start at
        error(post("/api/guild/g42/playlists/play", new JSONObject().put("mode", "all")), 400);
        error(post("/api/guild/g42/playlists/play", "not json"), 400);
        assertEquals(3, plays.size());

        // what the hook refuses is mapped like any other error
        playFails.set(new NoSuchElementException("Playlist not found"));
        assertEquals("Playlist not found", error(post("/api/guild/g42/playlists/play", target("Nope", "").put("mode", "all")), 404));
        playFails.set(new IllegalArgumentException("Unknown play mode: x"));
        error(post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "all")), 400);
        playFails.set(new IllegalStateException("busy"));
        error(post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "all")), 409);
        // anything else is a 500 that says nothing about the inside of the bot
        playFails.set(new RuntimeException("boom in /home/cloutkings/secret"));
        Resp broken = post("/api/guild/g42/playlists/play", target("Chill", "").put("mode", "all"));
        error(broken, 500);
        assertFalse(broken.body, broken.body.contains("secret"));
    }

    @Test
    public void diskErrorsAre500WithAFriendlyMessage() throws Exception
    {
        Files.createDirectories(root.getParent());
        Files.write(root, "a file where the playlists folder should be".getBytes(StandardCharsets.UTF_8));

        Resp failed = post("/api/playlists", target("Mix", ""));

        assertEquals("Couldn't save — try again", error(failed, 500));
        assertFalse("no paths in answers: " + failed.body, failed.body.contains("Playlists"));
        assertTrue("a change that failed is not announced", broadcasts.isEmpty());
    }

    @Test
    public void importStartsAJobAndReportsProgress() throws Exception
    {
        resolver.script = link -> Resolved.ok(Arrays.asList(resolved("https://example.com/1"), resolved("https://example.com/2"),
                TrackResolver.unresolvedSong("three", "ytsearch:three")), "Great Album");

        JSONObject started = ok(post("/api/playlists/import", new JSONObject().put("link", "https://www.youtube.com/playlist?list=PL1")
                .put("folder", "Imports")));
        String jobId = started.getString("jobId");
        assertFalse(jobId.isEmpty());

        assertEquals("the job made a playlist named after the link", 3, store.get("Great Album", "Imports").get().getSongs().size());
        assertEquals("credited to who asked", "tester", store.get("Great Album", "Imports").get().getCreatedBy().getName());
        assertEquals("tester", store.get("Great Album", "Imports").get().getSongs().get(0).getAddedBy().getName());
        Broadcast last = broadcasts.get(broadcasts.size() - 1);
        assertEquals("playlist-import", last.type);
        JSONObject expected = new JSONObject().put("jobId", jobId).put("done", 3).put("total", 3).put("failedCount", 1)
                .put("playlist", "Great Album").put("folder", "Imports").put("finished", true).put("error", JSONObject.NULL);
        assertTrue(last.data + " vs " + expected, last.data.similar(expected));
        for(Broadcast each : broadcasts)
            assertEquals("playlist-import", each.type);

        // into an existing playlist, and with a name
        broadcasts.clear();
        ok(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/album").put("into", "Great Album").put("folder", "Imports")));
        assertEquals(6, store.get("Great Album", "Imports").get().getSongs().size());
        ok(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/album").put("name", " Named ")));
        assertEquals(3, store.get("Named", "").get().getSongs().size());
    }

    @Test
    public void importErrorsUseTheStandardMapping() throws Exception
    {
        error(post("/api/playlists/import", new JSONObject().put("link", "ftp://example.com/x")), 400);
        error(post("/api/playlists/import", new JSONObject().put("link", "ytsearch:lofi")), 400);
        error(post("/api/playlists/import", new JSONObject()), 400);
        error(post("/api/playlists/import", new JSONObject().put("link", 5)), 400);
        error(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/a").put("folder", "../x")), 400);
        error(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/a").put("into", "Nope")), 404);
        ok(post("/api/playlists", target("Taken", "")));
        assertEquals("A playlist with that name already exists",
                error(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/a").put("name", "Taken")), 409));
        writeTxt("Old.txt", "https://example.com/a\n");
        assertEquals(UPGRADING, error(post("/api/playlists/import", new JSONObject().put("link", "https://example.com/a").put("into", "Old")), 409));
        assertTrue("nothing was looked up", resolver.lines.isEmpty());
    }

    @Test
    public void importProgressJsonCarriesEveryField() throws Exception
    {
        JSONObject running = PlaylistRoutes.importProgress(new ImportJobs.Progress("job12345", 4, 10, 1, null, "Rock", false, null));
        assertTrue(running.toString(), running.similar(new JSONObject().put("jobId", "job12345").put("done", 4).put("total", 10)
                .put("failedCount", 1).put("playlist", JSONObject.NULL).put("folder", "Rock").put("finished", false).put("error", JSONObject.NULL)));
        JSONObject failed = PlaylistRoutes.importProgress(new ImportJobs.Progress("job12345", 0, 0, 0, "P", null, true, "Nothing found at that link"));
        assertTrue(failed.getBoolean("finished"));
        assertEquals("Nothing found at that link", failed.getString("error"));
        assertEquals("P", failed.getString("playlist"));
        assertEquals("", failed.getString("folder"));
    }

    // ---- broadcasts, bad bodies, the guard ---------------------------------------------------------------------

    @Test
    public void everyMutationBroadcastsTheRightMessage() throws Exception
    {
        int mark = broadcasts.size();
        ok(post("/api/playlists", target("Mix", "Rock")));
        assertTreeBroadcast(single(mark));

        mark = broadcasts.size();
        JSONObject added = ok(add("Mix", "Rock", "a", "b", "c"));
        assertContentBroadcast(single(mark), "Mix", "Rock", added.getLong("version"));

        mark = broadcasts.size();
        JSONObject shuffled = ok(post("/api/playlists/shuffle", target("Mix", "Rock").put("on", true)));
        assertContentBroadcast(single(mark), "Mix", "Rock", shuffled.getLong("version"));
        assertTrue(item("Mix", "Rock").getBoolean("shuffle"));

        mark = broadcasts.size();
        JSONObject moved = ok(post("/api/playlists/songs/move", target("Mix", "Rock").put("songId", idOf(item("Mix", "Rock"), "a")).put("toIndex", 2)));
        assertContentBroadcast(single(mark), "Mix", "Rock", moved.getLong("version"));

        mark = broadcasts.size();
        JSONObject removed = ok(post("/api/playlists/songs/remove", target("Mix", "Rock").put("songId", idOf(item("Mix", "Rock"), "b"))));
        assertContentBroadcast(single(mark), "Mix", "Rock", removed.getLong("version"));

        mark = broadcasts.size();
        JSONObject undone = ok(post("/api/playlists/songs/undo", new JSONObject().put("token", removed.getString("undo"))));
        assertContentBroadcast(single(mark), "Mix", "Rock", undone.getLong("version"));

        mark = broadcasts.size();
        JSONObject renamed = ok(post("/api/playlists/rename", target("Mix", "Rock").put("newName", " Mixtape ")));
        assertEquals("Mixtape", renamed.getString("name"));
        assertEquals("Rock", renamed.getString("folder"));
        assertTrue(renamed.getLong("version") > undone.getLong("version"));
        assertTreeBroadcast(single(mark));

        mark = broadcasts.size();
        JSONObject relocated = ok(post("/api/playlists/move", target("Mixtape", "Rock").put("toFolder", "Pop")));
        assertEquals("Mixtape", relocated.getString("name"));
        assertEquals("the answer names the new folder", "Pop", relocated.getString("folder"));
        assertTrue(relocated.getLong("version") > renamed.getLong("version"));
        assertTreeBroadcast(single(mark));
        assertEquals(Arrays.asList("b", "c", "a"), titles(item("Mixtape", "Pop")));
        assertEquals("back to the top level", "", ok(post("/api/playlists/move", target("Mixtape", "Pop").put("toFolder", ""))).getString("folder"));
    }

    @Test
    public void bodiesThatAreNotJsonObjectsAreRejected() throws Exception
    {
        String[] paths = {"/api/playlists", "/api/playlists/rename", "/api/playlists/move", "/api/playlists/delete", "/api/playlists/restore",
                "/api/playlists/folder", "/api/playlists/folder/delete", "/api/playlists/songs/add", "/api/playlists/songs/remove",
                "/api/playlists/songs/undo", "/api/playlists/songs/move", "/api/playlists/songs/retry", "/api/playlists/shuffle",
                "/api/playlists/import", "/api/guild/g1/playlists/play"};
        String[] bodies = {"not json", "[1,2]", "\"text\"", "42", "{\"name\":", ""};
        for(String path : paths)
            for(String body : bodies)
                error(call("POST", path, body.isEmpty() ? "" : body), 400);
        error(post("/api/playlist/Named", "garbage"), 400);
        error(call("PUT", "/api/playlist-move", "garbage"), 400);
        error(call("PUT", "/api/playlist-rename/X", "[]"), 400);
        error(post("/api/playlist-folder", "garbage"), 400);
        error(call("DELETE", "/api/playlist-folder", "garbage"), 400);
        assertTrue(store.tree().isEmpty());
        assertTrue(broadcasts.isEmpty());
        assertTrue(plays.isEmpty());
    }

    @Test
    public void everyRouteSitsBehindTheAuthMiddleware() throws Exception
    {
        List<String> seen = new ArrayList<>();
        Javalin guarded = Javalin.create(config -> config.events(events -> events.handlerAdded(info ->
        {
            HandlerType type = info.getHttpMethod();
            if(type == HandlerType.GET || type == HandlerType.POST || type == HandlerType.PUT || type == HandlerType.DELETE)
                seen.add(type + " " + info.getPath());
        })));
        routes().register(guarded);
        guarded.before("/api/*", new WebServer(null)::authMiddleware); // after the routes, as WebServer does
        guarded.start("127.0.0.1", 0);
        try
        {
            assertTrue(seen.toString(), seen.size() >= 30);
            for(String route : seen)
            {
                String[] parts = route.split(" ", 2);
                assertTrue(route + " is not under /api/", parts[1].startsWith("/api/"));
                HttpRequest.BodyPublisher body = parts[0].equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{\"name\":\"Sneaky\",\"mode\":\"all\",\"link\":\"https://example.com/a\",\"path\":\"X\",\"items\":[]}");
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + guarded.port() + parts[1].replaceAll("\\{[^}]*}", "x")))
                        .method(parts[0], body).header("Content-Type", "application/json").build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(route + " answered " + response.body(), 401, response.statusCode());
            }
        }
        finally
        {
            guarded.stop();
        }
        assertTrue("no route ran", store.tree().isEmpty() && broadcasts.isEmpty() && plays.isEmpty() && resolver.lines.isEmpty());
        assertFalse(Files.exists(root));
    }
}
