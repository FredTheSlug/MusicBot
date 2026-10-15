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
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.theme.ThemeFile;
import com.jagrosh.jmusicbot.theme.ThemeImages;
import com.jagrosh.jmusicbot.theme.ThemeStore;
import com.jagrosh.jmusicbot.theme.ThemeStore.PictureUpload;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
 * The theme endpoints on a bare Javalin, over a real store in a temp folder, with two ordinary people and the bot
 * owner. Multipart bodies are built by hand, as a browser's {@code FormData} would send them.
 */
public class ThemeRoutesTest
{
    private static final Instant T0 = Instant.parse("2026-10-14T18:00:00Z");
    private static final Person ALICE = new Person("111111111111111111", "alice");
    private static final Person BOB = new Person("222222222222222222", "bob");
    /** The bot owner is a Discord user like any other, told apart by his id alone. */
    private static final Person BOSS = new Person("999999999999999999", "boss");
    private static final long BOSS_ID = 999999999999999999L;
    private static final long EIGHT_MB = 8L * 1024 * 1024;
    private static final String PICTURES = "/theme-images/";
    private static final String NOT_FOUND = "Theme not found";
    private static final String NOT_A_PICTURE = "That file isn't a picture we can use (JPG, PNG, WebP or GIF)";
    private static final String TOO_LARGE = "Pictures can be up to 8 MB";
    private static final Set<String> THEME_KEYS = keys("id", "name", "owner", "visibility", "hiddenByOwner", "mine", "version",
            "updatedAt", "base", "effects", "colors", "radiusScale", "font", "background", "mini");
    private static final String[] QUIET = {"io.javalin", "org.eclipse.jetty", "com.jagrosh.jmusicbot.theme",
            "com.jagrosh.jmusicbot.web.ThemeRoutes"};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path themesDir;
    private Path imagesDir;
    private TestClock clock;
    private ThemeImages images;
    private ThemeStore store;
    private final AtomicReference<Person> user = new AtomicReference<>(ALICE);
    private final List<Broadcast> broadcasts = Collections.synchronizedList(new ArrayList<Broadcast>());
    private final AtomicInteger broadcastAttempts = new AtomicInteger();
    private volatile boolean broadcasterFails;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Level> savedLevels = new LinkedHashMap<>();

    @Before
    public void startServer()
    {
        for(String name : QUIET) // keep the server's and the store's start-up and error lines out of the test output
        {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            savedLevels.put(name, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
        themesDir = tmp.getRoot().toPath().resolve("Themes");
        imagesDir = themesDir.resolve("images");
        clock = new TestClock(T0);
        images = new ThemeImages(imagesDir);
        store = new ThemeStore(themesDir, images, clock);
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

    private ThemeRoutes routes()
    {
        return new ThemeRoutes(store, images, ctx -> user.get(), () -> BOSS_ID, (type, data) ->
        {
            broadcastAttempts.incrementAndGet();
            if(broadcasterFails)
                throw new IllegalStateException("the socket is closed");
            broadcasts.add(new Broadcast(type, (JSONObject) data));
        });
    }

    // ---- fixtures

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

    private static final class Resp
    {
        final int status;
        final byte[] bytes;
        final HttpHeaders headers;

        Resp(int status, byte[] bytes, HttpHeaders headers)
        {
            this.status = status;
            this.bytes = bytes;
            this.headers = headers;
        }

        String body()
        {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        String header(String name)
        {
            return headers.firstValue(name).orElse("");
        }

        JSONObject json()
        {
            return new JSONObject(body());
        }
    }

    /** A multipart/form-data body, put together the way a browser does it. */
    private static final class Form
    {
        private final String boundary = "----themetest" + UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Form field(String name, String value)
        {
            return part(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        Form file(String name, String filename, String type, byte[] data)
        {
            return part(name, filename, type, data);
        }

        private Form part(String name, String filename, String type, byte[] data)
        {
            text("--" + boundary + "\r\n");
            text("Content-Disposition: form-data; name=\"" + name + "\"" + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n");
            if(type != null)
                text("Content-Type: " + type + "\r\n");
            text("\r\n");
            out.write(data, 0, data.length);
            text("\r\n");
            return this;
        }

        private void text(String text)
        {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }

        byte[] body()
        {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] parts = out.toByteArray();
            all.write(parts, 0, parts.length);
            byte[] end = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            all.write(end, 0, end.length);
            return all.toByteArray();
        }

        String contentType()
        {
            return "multipart/form-data; boundary=" + boundary;
        }
    }

    private static Set<String> keys(String... names)
    {
        return new HashSet<>(Arrays.asList(names));
    }

    private static JSONObject colors(boolean withGlass)
    {
        JSONObject colors = new JSONObject();
        colors.put("bgPrimary", "#0b0d14");
        colors.put("bgSecondary", "#10131c");
        colors.put("bgCard", "#161a26");
        colors.put("bgElevated", "#1c2131");
        colors.put("bgHover", "#262c42");
        colors.put("accent", "#7c5cff");
        colors.put("accentLight", "#a08bff");
        colors.put("accent2", "#22d3ee");
        colors.put("textPrimary", "#eef0f8");
        colors.put("textSecondary", "#9aa0b8");
        colors.put("textTertiary", "#646a82");
        colors.put("border", "#ffffff12");
        colors.put("borderLight", "#ffffff1f");
        colors.put("success", "#34d399");
        if(withGlass)
            colors.put("glass", "#10131ce0");
        return colors;
    }

    /** What a browser sends for a theme without a background picture or mini-player version. */
    private static JSONObject themeJson(String name, String visibility)
    {
        return new JSONObject()
                .put("name", name)
                .put("visibility", visibility)
                .put("base", "ember")
                .put("effects", false)
                .put("colors", colors(true))
                .put("radiusScale", 100)
                .put("font", "Outfit");
    }

    private static JSONObject withBackground(JSONObject theme)
    {
        return theme.put("background", new JSONObject().put("dim", 45).put("blur", 4).put("fit", "cover"));
    }

    private static JSONObject withMini(JSONObject theme, boolean useImage)
    {
        return theme.put("mini", new JSONObject().put("colors", colors(false)).put("useImage", useImage).put("dim", 55));
    }

    /** Gives the theme (and its mini-player version) a main color that no other theme in a test has, so it can be searched for. */
    private static JSONObject withAccent(JSONObject theme, String accent)
    {
        theme.getJSONObject("colors").put("accent", accent);
        if(theme.has("mini"))
            theme.getJSONObject("mini").getJSONObject("colors").put("accent", accent);
        return theme;
    }

    private static byte[] withHead(byte[] head, int tag)
    {
        byte[] all = Arrays.copyOf(head, head.length + 1);
        all[head.length] = (byte) tag;
        return all;
    }

    /** A JPEG signature plus one distinguishing byte, so every picture differs. */
    private static byte[] jpeg(int tag)
    {
        return withHead(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00}, tag);
    }

    private static byte[] png(int tag)
    {
        return withHead(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'}, tag);
    }

    private static byte[] gif(int tag)
    {
        return withHead("GIF89a".getBytes(StandardCharsets.US_ASCII), tag);
    }

    private static byte[] webp(int tag)
    {
        return withHead(new byte[]{'R', 'I', 'F', 'F', 0x10, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '}, tag);
    }

    // ---- requests

    private Resp request(String method, String path, String contentType, byte[] body) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if(contentType != null)
            request.header("Content-Type", contentType);
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), response.body(), response.headers());
    }

    private Resp get(String path) throws Exception
    {
        return request("GET", path, null, null);
    }

    private Resp post(String path, Object body) throws Exception
    {
        return request("POST", path, "application/json", body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Resp upload(String path, Form form) throws Exception
    {
        return request("POST", path, form.contentType(), form.body());
    }

    /** Creates a theme; the picture is sent as a file part named background when there is one. */
    private Resp createTheme(JSONObject theme, byte[] picture) throws Exception
    {
        Form form = new Form().field("theme", theme.toString());
        if(picture != null)
            form.file("background", "background.jpg", "image/jpeg", picture);
        return upload("/api/themes", form);
    }

    private Resp editTheme(String id, JSONObject theme, byte[] picture, long version, boolean removeBackground) throws Exception
    {
        Form form = new Form().field("theme", theme.toString()).field("version", String.valueOf(version));
        if(removeBackground)
            form.field("removeBackground", "true");
        if(picture != null)
            form.file("background", "background.jpg", "image/jpeg", picture);
        return upload("/api/themes/" + id, form);
    }

    private Resp deleteTheme(String id) throws Exception
    {
        return post("/api/themes/" + id + "/delete", null);
    }

    private Resp hideTheme(String id, boolean hidden) throws Exception
    {
        return post("/api/themes/" + id + "/hide", new JSONObject().put("hidden", hidden));
    }

    /** A 200 answer that is JSON; returns it. */
    private static JSONObject ok(Resp resp)
    {
        assertEquals(resp.body(), 200, resp.status);
        assertTrue(resp.header("Content-Type"), resp.header("Content-Type").startsWith("application/json"));
        return resp.json();
    }

    /** An error answer: the status, JSON, and a message for people; returns the message. */
    private static String error(Resp resp, int status)
    {
        assertEquals(resp.body(), status, resp.status);
        assertTrue(resp.header("Content-Type"), resp.header("Content-Type").startsWith("application/json"));
        assertEquals("only an error message", keys("error"), resp.json().keySet());
        String message = resp.json().optString("error", "");
        assertFalse("an error message in " + resp.body(), message.isEmpty());
        return message;
    }

    private JSONObject list(Person who) throws Exception
    {
        user.set(who);
        return ok(get("/api/themes"));
    }

    private static List<String> names(JSONObject list)
    {
        List<String> names = new ArrayList<>();
        JSONArray themes = list.getJSONArray("themes");
        for(int i = 0; i < themes.length(); i++)
            names.add(themes.getJSONObject(i).getString("name"));
        return names;
    }

    private static JSONObject themeNamed(JSONObject list, String name)
    {
        JSONArray themes = list.getJSONArray("themes");
        for(int i = 0; i < themes.length(); i++)
            if(name.equals(themes.getJSONObject(i).getString("name")))
                return themes.getJSONObject(i);
        throw new AssertionError("no theme named " + name + " in " + names(list));
    }

    private static String pictureNameOf(JSONObject theme)
    {
        String url = theme.getJSONObject("background").getString("url");
        assertTrue(url, url.startsWith(PICTURES));
        return url.substring(PICTURES.length());
    }

    private static void assertNoTrace(String text, String... secrets)
    {
        for(String secret : secrets)
            assertFalse("'" + secret + "' showed up in " + text, text.contains(secret));
    }

    private List<String> pictureFiles() throws Exception
    {
        if(!Files.isDirectory(imagesDir))
            return new ArrayList<>();
        try(Stream<Path> files = Files.list(imagesDir))
        {
            return files.map(file -> file.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private void assertNothingStored() throws Exception
    {
        assertTrue("no theme was made", store.visibleTo(ALICE.getId()).isEmpty() && store.visibleTo(BOB.getId()).isEmpty());
        assertEquals("no picture was kept", new ArrayList<String>(), pictureFiles());
        assertTrue("nobody was told anything: " + broadcasts, broadcasts.isEmpty());
    }

    private static void assertIdBroadcast(Broadcast broadcast, String id)
    {
        assertEquals("themes", broadcast.type);
        assertTrue(broadcast.data.toString(), broadcast.data.similar(new JSONObject().put("id", id)));
    }

    private Broadcast single(int mark)
    {
        List<Broadcast> fresh = new ArrayList<>(broadcasts.subList(mark, broadcasts.size()));
        assertEquals(fresh.toString(), 1, fresh.size());
        return fresh.get(0);
    }

    // ---- the endpoints of spec section 4

    @Test
    public void createWithPictureAndList() throws Exception
    {
        byte[] picture = jpeg(1);
        JSONObject created = ok(createTheme(withMini(withBackground(themeJson("Midnight Drive", "public")), true), picture));

        assertEquals(THEME_KEYS, created.keySet());
        assertTrue(created.getString("id"), created.getString("id").matches("[a-z0-9]{12}"));
        assertEquals("Midnight Drive", created.getString("name"));
        assertTrue(created.getJSONObject("owner").toString(), created.getJSONObject("owner")
                .similar(new JSONObject().put("id", ALICE.getId()).put("name", "alice")));
        assertEquals("public", created.getString("visibility"));
        assertFalse(created.getBoolean("hiddenByOwner"));
        assertTrue(created.getBoolean("mine"));
        assertEquals(1, created.getLong("version"));
        assertEquals(T0.toString(), created.getString("updatedAt"));
        assertEquals("ember", created.getString("base"));
        assertFalse(created.getBoolean("effects"));
        assertTrue(created.getJSONObject("colors").similar(colors(true)));
        assertEquals(100, created.getInt("radiusScale"));
        assertEquals("Outfit", created.getString("font"));
        JSONObject background = created.getJSONObject("background");
        assertEquals(keys("url", "dim", "blur", "fit"), background.keySet());
        assertTrue(background.getString("url"), background.getString("url").matches("/theme-images/[0-9a-f]{32}\\.jpg"));
        assertEquals(45, background.getInt("dim"));
        assertEquals(4, background.getInt("blur"));
        assertEquals("cover", background.getString("fit"));
        JSONObject mini = created.getJSONObject("mini");
        assertEquals(keys("colors", "useImage", "dim"), mini.keySet());
        assertTrue(mini.getJSONObject("colors").similar(colors(false)));
        assertTrue(mini.getBoolean("useImage"));
        assertEquals(55, mini.getInt("dim"));
        assertIdBroadcast(single(0), created.getString("id"));

        // the list holds the same theme, the picture is at its link, and the numbers are right
        JSONObject mine = list(ALICE);
        assertEquals(1, mine.getJSONArray("themes").length());
        assertTrue(mine.getJSONArray("themes").getJSONObject(0).toString(), created.similar(mine.getJSONArray("themes").getJSONObject(0)));
        assertTrue(mine.getJSONObject("me").toString(), mine.getJSONObject("me")
                .similar(new JSONObject().put("id", ALICE.getId()).put("isOwner", false)));
        assertTrue(mine.getJSONObject("limit").toString(), mine.getJSONObject("limit").similar(new JSONObject().put("max", 10).put("used", 1)));
        assertFalse("only the bot owner is told about hidden themes", mine.has("hidden"));
        Resp served = get(background.getString("url"));
        assertEquals(200, served.status);
        assertArrayEquals(picture, served.bytes);

        // somebody else sees a public theme as not theirs, and does not count it against their limit
        JSONObject theirs = list(BOB);
        assertEquals(Arrays.asList("Midnight Drive"), names(theirs));
        assertFalse(theirs.getJSONArray("themes").getJSONObject(0).getBoolean("mine"));
        assertTrue(theirs.getJSONObject("limit").similar(new JSONObject().put("max", 10).put("used", 0)));
        assertEquals(BOB.getId(), theirs.getJSONObject("me").getString("id"));

        // a theme without a picture or mini-player version says so with nulls, and the list is sorted by name
        user.set(ALICE);
        JSONObject plain = ok(createTheme(themeJson("Another", "private"), null));
        assertTrue(plain.isNull("background"));
        assertTrue(plain.isNull("mini"));
        assertEquals(THEME_KEYS, plain.keySet());
        assertEquals(Arrays.asList("Another", "Midnight Drive"), names(list(ALICE)));
        assertEquals(2, list(ALICE).getJSONObject("limit").getInt("used"));
        assertEquals(2, store.countOwnedBy(ALICE.getId()));
    }

    @Test
    public void editAndVersionConflict() throws Exception
    {
        JSONObject created = ok(createTheme(withBackground(themeJson("Old name", "public")), jpeg(1)));
        String id = created.getString("id");
        String firstUrl = created.getJSONObject("background").getString("url");
        broadcasts.clear();

        clock.advance(Duration.ofSeconds(60));
        JSONObject changed = withBackground(themeJson("New name", "private"));
        changed.getJSONObject("background").put("dim", 10);
        JSONObject edited = ok(editTheme(id, changed, null, 1, false));
        assertEquals(THEME_KEYS, edited.keySet());
        assertEquals(id, edited.getString("id"));
        assertEquals(2, edited.getLong("version"));
        assertEquals("New name", edited.getString("name"));
        assertEquals("private", edited.getString("visibility"));
        assertEquals(T0.plusSeconds(60).toString(), edited.getString("updatedAt"));
        assertEquals("without a new picture the old one stays", firstUrl, edited.getJSONObject("background").getString("url"));
        assertEquals(10, edited.getJSONObject("background").getInt("dim"));
        assertEquals(ALICE.getId(), edited.getJSONObject("owner").getString("id"));
        assertIdBroadcast(single(0), id);

        // somebody saving from a stale copy is turned away and changes nothing
        int mark = broadcasts.size();
        assertEquals("This theme changed — reload it", error(editTheme(id, themeJson("Stale", "public"), null, 1, false), 409));
        JSONObject now = themeNamed(list(ALICE), "New name");
        assertEquals(2, now.getLong("version"));
        assertEquals(mark, broadcasts.size());

        // a new picture replaces the old one, whose file goes
        JSONObject replaced = ok(editTheme(id, withBackground(themeJson("New name", "private")), jpeg(2), 2, false));
        String secondUrl = replaced.getJSONObject("background").getString("url");
        assertNotEquals(firstUrl, secondUrl);
        assertEquals(3, replaced.getLong("version"));
        assertEquals(404, get(firstUrl).status);
        assertEquals(200, get(secondUrl).status);
        assertEquals(1, pictureFiles().size());

        // taking the background away removes the picture too
        JSONObject plain = ok(editTheme(id, themeJson("New name", "private"), null, 3, true));
        assertTrue(plain.isNull("background"));
        assertEquals(4, plain.getLong("version"));
        assertEquals(404, get(secondUrl).status);
        assertEquals(new ArrayList<String>(), pictureFiles());

        // the version is required, and must be a whole number
        error(upload("/api/themes/" + id, new Form().field("theme", themeJson("X", "public").toString())), 400);
        for(String bad : new String[]{"abc", "-1", "1.5", "", "99999999999999999999", "0x10"})
            error(upload("/api/themes/" + id, new Form().field("theme", themeJson("X", "public").toString()).field("version", bad)), 400);
        error(upload("/api/themes/" + id, new Form().field("theme", themeJson("X", "public").toString()).field("version", "4")
                .field("removeBackground", "maybe")), 400);
        assertEquals("nothing changed", 4, themeNamed(list(ALICE), "New name").getLong("version"));

        // a theme that isn't there
        assertEquals(NOT_FOUND, error(editTheme("zzzzzzzzzzzz", themeJson("X", "public"), null, 1, false), 404));
        assertEquals(NOT_FOUND, error(editTheme("not-an-id", themeJson("X", "public"), null, 1, false), 404));
    }

    @Test
    public void privateNeverLeaks() throws Exception
    {
        String secretAccent = "#5ec12e";
        String roseAccent = "#a1b2c3";
        JSONObject secret = ok(createTheme(withAccent(withMini(withBackground(themeJson("Secret Sauce", "private")), true), secretAccent), jpeg(7)));
        String secretId = secret.getString("id");
        String pictureName = pictureNameOf(secret);
        JSONObject open = ok(createTheme(themeJson("Open Book", "public"), null));
        assertEquals(2, broadcasts.size());

        // another person's list, and what happens when they try to reach it by a guessed id
        user.set(BOB);
        Resp bobsList = get("/api/themes");
        assertEquals(Arrays.asList("Open Book"), names(ok(bobsList)));
        assertNoTrace(bobsList.body(), "Secret Sauce", pictureName, secretId, secretAccent);
        Resp guessedEdit = editTheme(secretId, themeJson("Mine now", "public"), null, 1, false);
        Resp guessedDelete = deleteTheme(secretId);
        Resp missingEdit = editTheme("zzzzzzzzzzzz", themeJson("Mine now", "public"), null, 1, false);
        Resp missingDelete = deleteTheme("zzzzzzzzzzzz");
        assertEquals(NOT_FOUND, error(guessedEdit, 404));
        assertEquals(NOT_FOUND, error(guessedDelete, 404));
        assertEquals("a private theme answers exactly as one that doesn't exist", missingEdit.body(), guessedEdit.body());
        assertEquals(missingDelete.body(), guessedDelete.body());
        for(Resp answer : new Resp[]{guessedEdit, guessedDelete})
            assertNoTrace(answer.body(), "Secret Sauce", pictureName, secretId, secretAccent);

        // the bot owner does not see other people's private themes either
        user.set(BOSS);
        Resp bossList = get("/api/themes");
        assertEquals(Arrays.asList("Open Book"), names(ok(bossList)));
        assertNoTrace(bossList.body(), "Secret Sauce", pictureName, secretId, secretAccent);
        assertEquals(0, ok(bossList).getJSONArray("hidden").length());
        Resp hide = hideTheme(secretId, true);
        assertEquals(NOT_FOUND, error(hide, 404));
        assertEquals(hide.body(), hideTheme("zzzzzzzzzzzz", true).body());
        Resp bossDelete = deleteTheme(secretId);
        assertEquals(NOT_FOUND, error(bossDelete, 404));
        assertNoTrace(bossDelete.body(), "Secret Sauce", pictureName, secretId, secretAccent);

        // nothing happened to it, and nobody was told about the failed tries
        assertEquals(Arrays.asList("Open Book", "Secret Sauce"), names(list(ALICE)));
        assertEquals(1, themeNamed(list(ALICE), "Secret Sauce").getLong("version"));
        assertEquals(2, broadcasts.size());

        // a public theme the owner hides turns private: the answer and the owner's list say as little as can be
        user.set(ALICE);
        JSONObject rose = ok(createTheme(withAccent(withMini(withBackground(themeJson("Rose Garden", "public")), true), roseAccent), jpeg(8)));
        String roseId = rose.getString("id");
        String rosePicture = pictureNameOf(rose);
        assertEquals(Arrays.asList("Open Book", "Rose Garden"), names(list(BOB)));

        user.set(BOSS);
        Resp hidden = hideTheme(roseId, true);
        assertTrue(hidden.body(), ok(hidden).similar(new JSONObject().put("id", roseId).put("hidden", true)));
        assertNoTrace(hidden.body(), "Rose Garden", rosePicture, roseAccent, "alice");
        Resp bossAfter = get("/api/themes");
        JSONObject bossJson = ok(bossAfter);
        assertEquals("he can't use it: it is not his", Arrays.asList("Open Book"), names(bossJson));
        JSONArray hiddenList = bossJson.getJSONArray("hidden");
        assertEquals(1, hiddenList.length());
        assertEquals("only an id, a name and the creator's name", keys("id", "name", "creator"), hiddenList.getJSONObject(0).keySet());
        assertTrue(hiddenList.getJSONObject(0).toString(), hiddenList.getJSONObject(0)
                .similar(new JSONObject().put("id", roseId).put("name", "Rose Garden").put("creator", "alice")));
        assertNoTrace(bossAfter.body(), rosePicture, roseAccent);
        user.set(BOB);
        Resp bobAfter = get("/api/themes");
        assertEquals(Arrays.asList("Open Book"), names(ok(bobAfter)));
        assertNoTrace(bobAfter.body(), "Rose Garden", rosePicture, roseId, roseAccent);

        // every broadcast is a bare id: no name, no color, no picture
        assertEquals(4, broadcasts.size());
        Set<String> ids = new HashSet<>(Arrays.asList(secretId, open.getString("id"), roseId));
        for(Broadcast broadcast : broadcasts)
        {
            assertEquals("themes", broadcast.type);
            assertEquals(broadcast.toString(), keys("id"), broadcast.data.keySet());
            assertTrue(broadcast.toString(), ids.contains(broadcast.data.getString("id")));
            assertNoTrace(broadcast.toString(), "Secret Sauce", "Rose Garden", pictureName, rosePicture, secretAccent, roseAccent, "#");
        }
    }

    @Test
    public void ownerHideAndDelete() throws Exception
    {
        JSONObject shown = ok(createTheme(withBackground(themeJson("Rose Garden", "public")), jpeg(1)));
        String id = shown.getString("id");
        String url = shown.getJSONObject("background").getString("url");

        // hiding makes it private to its creator, with a note; everybody else loses it
        user.set(BOSS);
        int mark = broadcasts.size();
        assertTrue(ok(hideTheme(id, true)).similar(new JSONObject().put("id", id).put("hidden", true)));
        assertIdBroadcast(single(mark), id);
        JSONObject creatorSees = themeNamed(list(ALICE), "Rose Garden");
        assertTrue(creatorSees.getBoolean("hiddenByOwner"));
        assertEquals("private", creatorSees.getString("visibility"));
        assertTrue(creatorSees.getBoolean("mine"));
        assertEquals(2, creatorSees.getLong("version"));
        assertTrue(names(list(BOB)).isEmpty());
        JSONObject bossSees = list(BOSS);
        assertTrue(bossSees.getJSONObject("me").getBoolean("isOwner"));
        assertEquals(1, bossSees.getJSONArray("hidden").length());
        assertEquals("the owner's own themes count only himself", 0, bossSees.getJSONObject("limit").getInt("used"));
        assertFalse("only the owner is told about hidden themes", list(ALICE).has("hidden"));

        // its creator can't make it public again, but can still change it, and the picture stays
        assertEquals("Hidden by the bot owner", error(editTheme(id, withBackground(themeJson("Rose Garden", "public")), null, 2, false), 403));
        ok(editTheme(id, withBackground(themeJson("Rose Garden", "private")), null, 2, false));

        // un-hiding gives it back to the creator, still private
        user.set(BOSS);
        assertTrue(ok(hideTheme(id, false)).similar(new JSONObject().put("id", id).put("hidden", false)));
        assertEquals(0, list(BOSS).getJSONArray("hidden").length());
        JSONObject back = themeNamed(list(ALICE), "Rose Garden");
        assertFalse(back.getBoolean("hiddenByOwner"));
        assertEquals("private", back.getString("visibility"));
        assertTrue(names(list(BOB)).isEmpty());
        user.set(ALICE);
        ok(editTheme(id, withBackground(themeJson("Rose Garden", "public")), null, back.getLong("version"), false));
        assertEquals(Arrays.asList("Rose Garden"), names(list(BOB)));
        assertEquals(200, get(url).status);

        // the owner deletes a public theme, picture and all
        user.set(BOSS);
        mark = broadcasts.size();
        assertTrue(ok(deleteTheme(id)).similar(new JSONObject().put("id", id)));
        assertIdBroadcast(single(mark), id);
        assertTrue(names(list(ALICE)).isEmpty());
        assertEquals(404, get(url).status);
        assertEquals(new ArrayList<String>(), pictureFiles());
        assertEquals(NOT_FOUND, error(deleteTheme(id), 404));

        // and one he hid, which he finds in his own list
        user.set(ALICE);
        String second = ok(createTheme(themeJson("Second", "public"), null)).getString("id");
        user.set(BOSS);
        ok(hideTheme(second, true));
        assertEquals(second, list(BOSS).getJSONArray("hidden").getJSONObject(0).getString("id"));
        assertTrue(ok(deleteTheme(second)).similar(new JSONObject().put("id", second)));
        assertEquals(0, list(BOSS).getJSONArray("hidden").length());
        assertTrue(names(list(ALICE)).isEmpty());

        // people can delete their own, public or private
        user.set(BOB);
        String mineId = ok(createTheme(themeJson("Mine", "private"), null)).getString("id");
        assertTrue(ok(deleteTheme(mineId)).similar(new JSONObject().put("id", mineId)));
        assertTrue(names(list(BOB)).isEmpty());
    }

    @Test
    public void othersForbidden() throws Exception
    {
        JSONObject alices = ok(createTheme(withBackground(themeJson("Alice's", "public")), jpeg(1)));
        String id = alices.getString("id");
        int mark = broadcasts.size();

        user.set(BOB);
        assertEquals("Only the creator can change this theme", error(editTheme(id, themeJson("Taken", "public"), null, 1, false), 403));
        assertEquals("Only the creator can delete this theme", error(deleteTheme(id), 403));
        assertEquals("Only the bot owner can do that", error(hideTheme(id, true), 403));
        assertEquals("Only the bot owner can do that", error(hideTheme(id, false), 403));
        user.set(ALICE);
        assertEquals("a creator is no bot owner", "Only the bot owner can do that", error(hideTheme(id, true), 403));

        JSONObject unchanged = themeNamed(list(BOB), "Alice's");
        assertEquals(1, unchanged.getLong("version"));
        assertEquals("public", unchanged.getString("visibility"));
        assertEquals(200, get(alices.getJSONObject("background").getString("url")).status);
        assertEquals("failed tries are not announced", mark, broadcasts.size());

        // nobody logged in (web login switched off): the public themes can be used, none can be made or changed
        user.set(null);
        JSONObject anonymous = ok(get("/api/themes"));
        assertEquals(Arrays.asList("Alice's"), names(anonymous));
        assertTrue(anonymous.getJSONObject("me").isNull("id"));
        assertFalse(anonymous.getJSONObject("me").getBoolean("isOwner"));
        assertEquals(0, anonymous.getJSONObject("limit").getInt("used"));
        assertFalse(anonymous.getJSONArray("themes").getJSONObject(0).getBoolean("mine"));
        assertEquals("Log in to create themes", error(createTheme(themeJson("Anonymous", "public"), null), 403));
        error(editTheme(id, themeJson("Taken", "public"), null, 1, false), 403);
        error(deleteTheme(id), 403);
        error(hideTheme(id, true), 403);
        assertEquals(mark, broadcasts.size());
        assertEquals(1, store.visibleTo(ALICE.getId()).size());
    }

    @Test
    public void limitIs409() throws Exception
    {
        String last = null;
        for(int i = 1; i <= 10; i++)
            last = ok(createTheme(themeJson("Theme " + i, i % 2 == 0 ? "public" : "private"), null)).getString("id");
        assertEquals(10, list(ALICE).getJSONObject("limit").getInt("used"));
        int mark = broadcasts.size();

        assertEquals("You can have up to 10 themes", error(createTheme(themeJson("Eleven", "public"), null), 409));
        assertEquals("a refused theme leaves no picture behind", "You can have up to 10 themes",
                error(createTheme(withBackground(themeJson("Eleven", "public")), jpeg(1)), 409));
        assertEquals(new ArrayList<String>(), pictureFiles());
        assertEquals(10, store.countOwnedBy(ALICE.getId()));
        assertEquals(mark, broadcasts.size());

        // the limit is per person, and deleting one makes room
        user.set(BOB);
        assertEquals("Bob's", ok(createTheme(themeJson("Bob's", "public"), null)).getString("name"));
        user.set(ALICE);
        ok(deleteTheme(last));
        ok(createTheme(themeJson("Eleven", "public"), null));
        assertEquals(10, list(ALICE).getJSONObject("limit").getInt("used"));
    }

    @Test
    public void badValuesAre400WithFieldMessage() throws Exception
    {
        assertBad("Name must be 1 to 40 characters", t -> t.put("name", ""));
        assertBad("Name must be 1 to 40 characters", t -> t.put("name", "   "));
        assertBad("Name must be 1 to 40 characters", t -> t.put("name", String.join("", Collections.nCopies(41, "x"))));
        assertBad("Name must be 1 to 40 characters", t -> t.remove("name"));
        assertBad("Name can't contain control characters", t -> t.put("name", "bad\u0007name"));
        assertBad("Visibility must be public or private", t -> t.put("visibility", "friends"));
        assertBad("Starting theme must be one of the built-in themes", t -> t.put("base", "disco"));
        assertBad("Font must be one of the fonts offered", t -> t.put("font", "Comic Sans"));
        assertBad("Corners must be a whole number from 0 to 200", t -> t.put("radiusScale", 201));
        assertBad("Corners must be a whole number from 0 to 200", t -> t.put("radiusScale", "wide"));
        assertBad("Main color must be a color like #7c5cff", t -> t.getJSONObject("colors").put("accent", "red"));
        assertBad("Main color must be a color like #7c5cff", t -> t.getJSONObject("colors").put("accent", "url(javascript:alert(1))"));
        assertBad("Cards is missing", t -> t.getJSONObject("colors").remove("bgCard"));
        assertBad("Unknown color: sparkle", t -> t.getJSONObject("colors").put("sparkle", "#ffffff"));
        assertBad("Background darken must be a whole number from 0 to 90", t -> t.getJSONObject("background").put("dim", 91));
        assertBad("Background blur must be a whole number from 0 to 20", t -> t.getJSONObject("background").put("blur", 21));
        assertBad("Background fit must be cover, contain or center", t -> t.getJSONObject("background").put("fit", "stretch"));
        assertBad("Mini-player main color must be a color like #7c5cff", t -> t.getJSONObject("mini").getJSONObject("colors").put("accent", "nope"));
        assertBad("Mini-player darken must be a whole number from 0 to 90", t -> t.getJSONObject("mini").put("dim", 100));
        assertNothingStored();

        // background settings need a picture, and a picture needs settings
        assertEquals("Choose a picture or remove the background", error(createTheme(withBackground(themeJson("No picture", "public")), null), 400));
        assertEquals("Background settings are missing", error(createTheme(themeJson("No settings", "public"), jpeg(1)), 400));
        assertNothingStored();
    }

    /** A theme with a picture, a mini-player version and one thing broken must be refused with that message, and kept nowhere. */
    private void assertBad(String message, Consumer<JSONObject> breakIt) throws Exception
    {
        JSONObject theme = withMini(withBackground(themeJson("Fine", "public")), true);
        breakIt.accept(theme);
        assertEquals(theme.toString(), message, error(createTheme(theme, jpeg(1)), 400));
    }

    @Test
    public void requestsThatAreNotThemeUploadsAre400() throws Exception
    {
        JSONObject theme = themeJson("Fine", "public");
        // not a form at all
        error(request("POST", "/api/themes", "application/json", theme.toString().getBytes(StandardCharsets.UTF_8)), 400);
        error(request("POST", "/api/themes", "application/x-www-form-urlencoded", ("theme=" + theme).getBytes(StandardCharsets.UTF_8)), 400);
        error(request("POST", "/api/themes", null, null), 400);
        // a form without the theme, or with something else in its place
        error(upload("/api/themes", new Form().file("background", "a.jpg", "image/jpeg", jpeg(1))), 400);
        error(upload("/api/themes", new Form().field("name", "Fine")), 400);
        for(String bad : new String[]{"", "not json", "[1,2]", "\"text\"", "42", "{\"name\":", "null"})
            error(upload("/api/themes", new Form().field("theme", bad)), 400);
        error(upload("/api/themes", new Form().file("theme", "theme.json", "application/json", new byte[]{(byte) 0xC3, 0x28})), 400);
        // an upload that was cut short or is garbage
        error(request("POST", "/api/themes", "multipart/form-data; boundary=nothing-like-this", "this is not a form".getBytes(StandardCharsets.UTF_8)), 400);
        error(request("POST", "/api/themes", "multipart/form-data", "--x\r\n".getBytes(StandardCharsets.UTF_8)), 400);
        // the same for editing
        error(request("POST", "/api/themes/zzzzzzzzzzzz", "application/json", theme.toString().getBytes(StandardCharsets.UTF_8)), 400);
        error(upload("/api/themes/zzzzzzzzzzzz", new Form().field("version", "1")), 400);
        assertNothingStored();

        // a theme sent as a file part (a Blob) is as good as a plain field
        Form blob = new Form().file("theme", "blob", "application/json", theme.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("Fine", ok(upload("/api/themes", blob)).getString("name"));
    }

    @Test
    public void themePartOver64KbIs400() throws Exception
    {
        JSONObject padded = themeJson("Fine", "public").put("padding", String.join("", Collections.nCopies(70 * 1024, "x")));
        assertEquals("The theme is too big", error(upload("/api/themes", new Form().field("theme", padded.toString())), 400));
        assertEquals("The theme is too big", error(upload("/api/themes/zzzzzzzzzzzz",
                new Form().field("theme", padded.toString()).field("version", "1")), 400));
        assertNothingStored();

        // a theme with a little padding, well under the limit, is fine: the extra key is ignored
        JSONObject roomy = themeJson("Roomy", "public").put("padding", String.join("", Collections.nCopies(30 * 1024, "x")));
        ok(upload("/api/themes", new Form().field("theme", roomy.toString())));
    }

    @Test
    public void serverFieldsCannotBeSetByTheClient() throws Exception
    {
        String chosen = "0123456789abcdef0123456789abcdef.png";
        JSONObject forged = withBackground(themeJson("Forged", "public"))
                .put("id", "aaaaaaaaaaaa")
                .put("owner", new JSONObject().put("id", "666").put("name", "mallory"))
                .put("version", 99)
                .put("hiddenByOwner", true)
                .put("createdAt", "2001-01-01T00:00:00Z")
                .put("updatedAt", "2001-01-01T00:00:00Z")
                .put("mine", false);
        forged.getJSONObject("background").put("image", chosen);
        JSONObject made = ok(createTheme(forged, jpeg(1)));

        assertNotEquals("aaaaaaaaaaaa", made.getString("id"));
        assertEquals(ALICE.getId(), made.getJSONObject("owner").getString("id"));
        assertEquals("alice", made.getJSONObject("owner").getString("name"));
        assertEquals(1, made.getLong("version"));
        assertFalse(made.getBoolean("hiddenByOwner"));
        assertEquals("public", made.getString("visibility"));
        assertTrue(made.getBoolean("mine"));
        assertEquals(T0.toString(), made.getString("updatedAt"));
        assertNotEquals(chosen, pictureNameOf(made));
        assertEquals(Arrays.asList(pictureNameOf(made)), pictureFiles());
        assertEquals(Arrays.asList("Forged"), names(list(BOB)));
    }

    @Test
    public void fakePictureIs400() throws Exception
    {
        byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] exe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00};
        String theme = withBackground(themeJson("Fake", "public")).toString();
        assertEquals(NOT_A_PICTURE, error(upload("/api/themes", new Form().field("theme", theme).file("background", "evil.png", "image/png", html)), 400));
        assertEquals(NOT_A_PICTURE, error(upload("/api/themes", new Form().field("theme", theme).file("background", "logo.svg", "image/svg+xml", svg)), 400));
        assertEquals(NOT_A_PICTURE, error(upload("/api/themes", new Form().field("theme", theme).file("background", "photo.jpg", "image/jpeg", exe)), 400));
        assertEquals(NOT_A_PICTURE, error(upload("/api/themes", new Form().field("theme", theme).file("background", "empty.jpg", "image/jpeg", new byte[]{0})), 400));
        assertNothingStored();

        // editing refuses it too, and keeps the picture the theme has
        JSONObject real = ok(createTheme(withBackground(themeJson("Real", "public")), jpeg(1)));
        String name = pictureNameOf(real);
        assertEquals(NOT_A_PICTURE, error(editTheme(real.getString("id"), withBackground(themeJson("Real", "public")), html, 1, false), 400));
        assertEquals(Arrays.asList(name), pictureFiles());
        assertEquals(1, themeNamed(list(ALICE), "Real").getLong("version"));

        // the kind of picture comes from its first bytes alone, never from the name or type the browser claims
        Form disguised = new Form().field("theme", theme).file("background", "photo.html", "text/html", jpeg(5));
        JSONObject made = ok(upload("/api/themes", disguised));
        assertTrue(pictureNameOf(made), pictureNameOf(made).endsWith(".jpg"));
        assertEquals("image/jpeg", get(made.getJSONObject("background").getString("url")).header("Content-Type"));
    }

    @Test
    public void tooLargeIs413() throws Exception
    {
        byte[] big = new byte[(int) EIGHT_MB + 1];
        System.arraycopy(jpeg(0), 0, big, 0, 11);
        assertEquals(TOO_LARGE, error(createTheme(withBackground(themeJson("Big", "public")), big), 413));
        assertNothingStored();

        // exactly 8 MB is fine
        byte[] limit = Arrays.copyOf(big, (int) EIGHT_MB);
        JSONObject made = ok(createTheme(withBackground(themeJson("Limit", "public")), limit));
        assertEquals(EIGHT_MB, Files.size(imagesDir.resolve(pictureNameOf(made))));

        // and a replacement over the limit leaves the old picture and the theme as they were
        assertEquals(TOO_LARGE, error(editTheme(made.getString("id"), withBackground(themeJson("Limit", "public")), big, 1, false), 413));
        assertEquals(Arrays.asList(pictureNameOf(made)), pictureFiles());
        assertEquals(1, themeNamed(list(ALICE), "Limit").getLong("version"));
    }

    @Test
    public void pictureServedWithSafetyHeaders() throws Exception
    {
        JSONObject made = ok(createTheme(withBackground(themeJson("Pic", "private")), jpeg(3)));
        String url = made.getJSONObject("background").getString("url");
        String name = pictureNameOf(made);

        // no login is needed: whoever has the secret name can see it, which is the accepted trade-off
        user.set(null);
        Resp served = get(url);
        assertEquals(200, served.status);
        assertArrayEquals(jpeg(3), served.bytes);
        assertEquals("image/jpeg", served.header("Content-Type"));
        assertEquals("nosniff", served.header("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", served.header("Content-Security-Policy"));
        assertEquals("public, max-age=31536000, immutable", served.header("Cache-Control"));
        assertFalse(served.headers.firstValue("Set-Cookie").isPresent());

        // each kind of picture goes out as its own type
        user.set(ALICE);
        Map<String, byte[]> kinds = new LinkedHashMap<>();
        kinds.put("image/png", png(1));
        kinds.put("image/gif", gif(1));
        kinds.put("image/webp", webp(1));
        for(Map.Entry<String, byte[]> kind : kinds.entrySet())
        {
            JSONObject other = ok(createTheme(withBackground(themeJson("Kind " + kind.getKey(), "public")), kind.getValue()));
            Resp one = get(other.getJSONObject("background").getString("url"));
            assertEquals(kind.getKey(), one.header("Content-Type"));
            assertArrayEquals(kind.getValue(), one.bytes);
            assertEquals("nosniff", one.header("X-Content-Type-Options"));
        }

        // anything that is not exactly the name of a stored picture is a plain 404 with nothing to read in it
        String hex = "0123456789abcdef0123456789abcdef";
        String themeId = made.getString("id");
        Files.write(imagesDir.resolve(".pic-" + UUID.randomUUID() + ".tmp"), jpeg(1));
        Files.createDirectories(imagesDir.resolve(hex + ".jpg"));
        String[] names = {hex + ".jpg", hex + ".png", name.replace(".jpg", ".png"), name.replace(".jpg", ".JPG"), name.toUpperCase(),
                name + ".", name + "x", name.replace(".jpg", ".svg"), name.substring(1), "x", "x.jpg", hex, ".", "..", themeId + ".json",
                "..%2F" + themeId + ".json", "%2e%2e%2f" + themeId + ".json", "..%5C" + name, "%2F" + name, "images%2F" + name, "%2e%2e", "..%2F"};
        for(String bad : names)
        {
            Resp missing = get(PICTURES + bad);
            assertEquals(bad, 404, missing.status);
            assertEquals(bad + " has no detail to read: " + missing.body(), 0, missing.bytes.length);
            assertFalse(bad, missing.header("Cache-Control").contains("immutable"));
        }
        // the server itself turns some spellings away before they reach the route: never with anything but an error
        String[] refused = {"..%2F..%2FThemes%2F" + themeId + ".json", name.replace(".jpg", "%00.jpg"), name + "%00.png"};
        for(String bad : refused)
        {
            Resp missing = get(PICTURES + bad);
            assertTrue(bad + " answered " + missing.status, missing.status == 400 || missing.status == 404);
            assertNoTrace(missing.body(), themeId, "Pic", "colors");
        }
        assertEquals(404, get(PICTURES).status);
        assertEquals(404, get(PICTURES + name + "/more").status);
        assertEquals(404, get(PICTURES + ".pic-x.tmp").status);
        assertEquals("the picture is still there", 200, get(url).status);
    }

    @Test
    public void everyChangeBroadcastsThemes() throws Exception
    {
        int mark = broadcasts.size();
        JSONObject created = ok(createTheme(themeJson("Mix", "public"), null));
        String id = created.getString("id");
        assertIdBroadcast(single(mark), id);

        mark = broadcasts.size();
        ok(editTheme(id, themeJson("Mix 2", "public"), null, 1, false));
        assertIdBroadcast(single(mark), id);

        user.set(BOSS);
        mark = broadcasts.size();
        ok(hideTheme(id, true));
        assertIdBroadcast(single(mark), id);
        mark = broadcasts.size();
        ok(hideTheme(id, false));
        assertIdBroadcast(single(mark), id);

        user.set(ALICE);
        mark = broadcasts.size();
        ok(deleteTheme(id));
        assertIdBroadcast(single(mark), id);

        // reading and failing change nothing, so tell nobody
        mark = broadcasts.size();
        ok(get("/api/themes"));
        error(createTheme(themeJson("", "public"), null), 400);
        error(editTheme(id, themeJson("Gone", "public"), null, 1, false), 404);
        error(deleteTheme(id), 404);
        error(hideTheme(id, true), 403);
        user.set(BOSS);
        error(hideTheme(id, true), 404);
        error(post("/api/themes/" + id + "/hide", new JSONObject()), 400);
        assertEquals(mark, broadcasts.size());
        assertEquals(5, broadcastAttempts.get());
    }

    @Test
    public void failingBroadcasterDoesNotFailTheRequest() throws Exception
    {
        broadcasterFails = true;
        JSONObject created = ok(createTheme(withBackground(themeJson("Quiet", "public")), jpeg(1)));
        String id = created.getString("id");
        ok(editTheme(id, themeJson("Quiet 2", "public"), null, 1, true));
        user.set(BOSS);
        ok(hideTheme(id, true));
        ok(deleteTheme(id));
        assertEquals("each change did try to tell the panels", 4, broadcastAttempts.get());
        assertTrue(broadcasts.isEmpty());
        assertTrue(store.visibleTo(ALICE.getId()).isEmpty());
    }

    @Test
    public void hideNeedsAYesOrNo() throws Exception
    {
        String id = ok(createTheme(themeJson("Hideable", "public"), null)).getString("id");
        user.set(BOSS);
        for(Object bad : new Object[]{new JSONObject(), new JSONObject().put("hidden", "yes"), new JSONObject().put("hidden", 1),
                new JSONObject().put("hidden", JSONObject.NULL), "not json", "[true]"})
            error(post("/api/themes/" + id + "/hide", bad), 400);
        assertEquals("public", themeNamed(list(ALICE), "Hideable").getString("visibility"));
        user.set(BOSS);
        assertEquals(NOT_FOUND, error(hideTheme("zzzzzzzzzzzz", true), 404));
        assertEquals(NOT_FOUND, error(hideTheme("not-an-id", true), 404));
        assertEquals(NOT_FOUND, error(deleteTheme("not-an-id"), 404));
    }

    @Test
    public void useImageWithoutBackgroundIsSentAsFalse() throws Exception
    {
        JSONObject both = ok(createTheme(withMini(withBackground(themeJson("Both", "public")), true), jpeg(1)));
        assertTrue(both.getJSONObject("mini").getBoolean("useImage"));

        // no picture, so the mini-player has none to use, whatever the stored flag says
        JSONObject noPicture = ok(createTheme(withMini(themeJson("No picture", "public"), true), null));
        assertFalse(noPicture.getJSONObject("mini").getBoolean("useImage"));
        assertEquals(55, noPicture.getJSONObject("mini").getInt("dim"));

        // the same when the picture is taken away later
        JSONObject removed = ok(editTheme(both.getString("id"), withMini(themeJson("Both", "public"), true), null, 1, true));
        assertTrue(removed.isNull("background"));
        assertFalse(removed.getJSONObject("mini").getBoolean("useImage"));
        JSONObject listed = list(BOB);
        assertFalse(themeNamed(listed, "Both").getJSONObject("mini").getBoolean("useImage"));
        assertFalse(themeNamed(listed, "No picture").getJSONObject("mini").getBoolean("useImage"));
        assertTrue("what is stored is untouched", store.get(both.getString("id"), ALICE.getId()).get().getMini().isUseImage());
    }

    @Test
    public void ownerIsKnownByIdAlone() throws Exception
    {
        assertFalse(list(ALICE).getJSONObject("me").getBoolean("isOwner"));
        assertFalse(list(BOB).getJSONObject("me").getBoolean("isOwner"));
        assertFalse(list(new Person("99999999999999999", "boss")).getJSONObject("me").getBoolean("isOwner"));
        assertFalse(list(new Person("9999999999999999990", "boss")).getJSONObject("me").getBoolean("isOwner"));
        assertFalse(list(null).getJSONObject("me").getBoolean("isOwner"));
        assertFalse("a name proves nothing", list(new Person("1", "boss")).has("hidden"));
        JSONObject owner = list(BOSS);
        assertTrue(owner.getJSONObject("me").getBoolean("isOwner"));
        assertEquals(BOSS.getId(), owner.getJSONObject("me").getString("id"));
        assertEquals(new ArrayList<Object>(), owner.getJSONArray("hidden").toList());
    }

    @Test
    public void saveFailureIs500AndSaysSo() throws Exception
    {
        // a file where the Themes folder should be: nothing can be written there
        Files.write(themesDir, new byte[]{1});
        assertEquals("Couldn't save — try again", error(createTheme(themeJson("Doomed", "public"), null), 500));
        assertEquals("Couldn't save — try again", error(createTheme(withBackground(themeJson("Doomed", "public")), jpeg(1)), 500));
        assertTrue(broadcasts.isEmpty());
        assertEquals("reading still works", 0, ok(get("/api/themes")).getJSONArray("themes").length());
    }

    @Test
    public void themesFolderIsNextToThePlaylistsFolder()
    {
        Path here = Paths.get("").toAbsolutePath();
        assertEquals(here.resolve("Themes"), WebServer.themesFolder("Playlists"));
        assertEquals(here.resolve("data").resolve("Themes"), WebServer.themesFolder("data/Playlists"));
        assertEquals(here.resolve("Themes"), WebServer.themesFolder("other/../Playlists"));
        Path absolute = tmp.getRoot().toPath().toAbsolutePath();
        assertEquals(absolute.resolve("Themes"), WebServer.themesFolder(absolute.resolve("Playlists").toString()));
        assertEquals(absolute.resolve("Themes"), WebServer.themesFolder(absolute.resolve("Playlists").resolve("..").resolve("Playlists").toString()));
    }

    // ---- the guard

    @Test
    public void everyApiRouteNeedsLogin() throws Exception
    {
        // a picture exists, so that the one route without a login has something to serve
        ThemeFile values = ThemeFile.valuesFromClient(withBackground(themeJson("Public one", "public")));
        ThemeFile existing = store.create(values, new ThemeFile.Person(ALICE.getId(), "alice"),
                new PictureUpload(new ByteArrayInputStream(jpeg(1)), jpeg(1).length));
        String pictureName = existing.getBackground().getImage();

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
            List<String> expected = Arrays.asList("GET /api/themes", "POST /api/themes", "POST /api/themes/{id}",
                    "POST /api/themes/{id}/delete", "POST /api/themes/{id}/hide", "GET /theme-images/{name}");
            assertEquals(new HashSet<>(expected), new HashSet<>(seen));
            assertEquals("each route once: " + seen, expected.size(), seen.size());
            for(String route : seen)
            {
                String[] parts = route.split(" ", 2);
                if(parts[1].startsWith("/api/"))
                {
                    Form form = new Form().field("theme", themeJson("Sneaky", "public").toString()).field("version", "1");
                    HttpRequest.BodyPublisher body = parts[0].equals("GET") ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(form.body());
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + guarded.port() + parts[1].replaceAll("\\{[^}]*}", "zzzzzzzzzzzz")))
                            .method(parts[0], body).header("Content-Type", form.contentType()).build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(route + " answered " + response.body(), 401, response.statusCode());
                    assertNoTrace(response.body(), "Public one", pictureName);
                }
                else
                {
                    // the pictures are the one thing outside /api/, served without a login by their secret names
                    assertEquals("GET /theme-images/{name}", route);
                }
            }
            HttpResponse<byte[]> picture = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + guarded.port() + PICTURES + pictureName)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, picture.statusCode());
            assertArrayEquals(jpeg(1), picture.body());
            HttpResponse<byte[]> missing = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + guarded.port()
                    + PICTURES + "0123456789abcdef0123456789abcdef.jpg")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, missing.statusCode());
        }
        finally
        {
            guarded.stop();
        }
        assertEquals("no route changed anything", 1, store.visibleTo(null).size());
        assertEquals(1, store.countOwnedBy(ALICE.getId()));
        assertTrue(broadcasts.isEmpty());
    }
}
