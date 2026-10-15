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
package com.jagrosh.jmusicbot.theme;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jagrosh.jmusicbot.theme.ThemeFile.Person;
import com.jagrosh.jmusicbot.theme.ThemeStore.PictureUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class ThemeStoreTest
{
    private static final long EIGHT_MB = 8L * 1024 * 1024;
    private static final Instant T0 = Instant.parse("2026-10-14T18:00:00Z");
    private static final Person ALICE = new Person("111111111111111111", "alice");
    private static final Person BOB = new Person("222222222222222222", "bob");
    /** The bot owner is an ordinary Discord user as far as the store goes: the caller says whether the viewer is the owner. */
    private static final String OWNER = "999999999999999999";
    private static final String LIMIT = "You can have up to 10 themes";
    private static final String CHANGED = "This theme changed — reload it";
    private static final String HIDDEN = "Hidden by the bot owner";
    private static final String NEEDS_PICTURE = "Choose a picture or remove the background";
    private static final String NOT_A_PICTURE = "That file isn't a picture we can use (JPG, PNG, WebP or GIF)";
    private static final String TOO_LARGE = "Pictures can be up to 8 MB";
    private static final String NAME_RULE = "[0-9a-f]{32}\\.(jpg|png|webp|gif)";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path themesDir;
    private Path imagesDir;
    private TestClock clock;
    private ThemeImages images;
    private ThemeStore store;
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> log;
    private boolean additive;

    @Before
    public void setUp()
    {
        // not created: the store and the pictures make their folders when they first write
        themesDir = tmp.getRoot().toPath().resolve("Themes");
        imagesDir = themesDir.resolve("images");
        clock = new TestClock(T0);
        images = new ThemeImages(imagesDir);
        store = new ThemeStore(themesDir, images, clock);

        // collect the store's log lines instead of printing them
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThemeStore.class);
        log = new ListAppender<>();
        log.setContext(logger.getLoggerContext());
        log.start();
        additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(log);
    }

    @After
    public void tearDown()
    {
        logger.detachAppender(log);
        logger.setAdditive(additive);
    }

    /** A clock that stands still until a test moves it. */
    private static final class TestClock extends Clock
    {
        private volatile Instant now;

        TestClock(Instant now)
        {
            this.now = now;
        }

        void set(Instant now)
        {
            this.now = now;
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

    /** A store whose theme writes can be made to fail, to see what the store cleans up. */
    private static final class FlakyStore extends ThemeStore
    {
        volatile boolean failWrites;

        FlakyStore(Path dir, ThemeImages images, Clock clock)
        {
            super(dir, images, clock);
        }

        @Override
        void write(Path target, ThemeFile theme)
        {
            if(failWrites)
                throw new UncheckedIOException("disk full", new IOException("disk full"));
            super.write(target, theme);
        }
    }

    // ---- fixtures

    private static JSONObject colors()
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
        colors.put("glass", "#10131ce0");
        return colors;
    }

    private static JSONObject json(String name, String visibility)
    {
        return new JSONObject()
                .put("name", name)
                .put("visibility", visibility)
                .put("base", "ember")
                .put("effects", false)
                .put("colors", colors())
                .put("radiusScale", 100)
                .put("font", "Outfit");
    }

    /** What a browser sends for a theme without a background picture. */
    private static ThemeFile values(String name, String visibility)
    {
        return ThemeFile.valuesFromClient(json(name, visibility));
    }

    /** What a browser sends for a theme with background settings (the picture itself comes as a separate upload). */
    private static ThemeFile valuesWithBackground(String name, String visibility)
    {
        return ThemeFile.valuesFromClient(json(name, visibility)
                .put("background", new JSONObject().put("dim", 45).put("blur", 4).put("fit", "cover")));
    }

    /** A PNG signature followed by one distinguishing byte, so every picture differs. */
    private static byte[] png(int tag)
    {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};
        byte[] all = Arrays.copyOf(head, head.length + 1);
        all[head.length] = (byte) tag;
        return all;
    }

    private static PictureUpload upload(byte[] bytes)
    {
        return new PictureUpload(new ByteArrayInputStream(bytes), bytes.length);
    }

    private ThemeFile createPublic(Person who, String name)
    {
        return store.create(values(name, "public"), who, null);
    }

    private ThemeFile createPrivate(Person who, String name)
    {
        return store.create(values(name, "private"), who, null);
    }

    private ThemeFile createWithPicture(Person who, String name, String visibility, int tag)
    {
        return store.create(valuesWithBackground(name, visibility), who, upload(png(tag)));
    }

    private Path fileOf(ThemeFile theme)
    {
        return themesDir.resolve(theme.getId() + ".json");
    }

    private static String read(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String text) throws IOException
    {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(List<ThemeFile> themes)
    {
        return themes.stream().map(ThemeFile::getName).collect(Collectors.toList());
    }

    private static List<String> filesIn(Path dir) throws IOException
    {
        if(!Files.isDirectory(dir))
            return new ArrayList<>();
        try(Stream<Path> list = Files.list(dir))
        {
            return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private List<String> pictures() throws IOException
    {
        return filesIn(imagesDir);
    }

    private static String imageOf(ThemeFile theme)
    {
        return theme.getBackground() == null ? null : theme.getBackground().getImage();
    }

    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> log)
    {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).collect(Collectors.toList());
    }

    /** Runs the tasks at the same moment on their own threads; each result is the value returned or the exception thrown. */
    private static List<Object> together(List<Callable<?>> tasks) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try
        {
            CyclicBarrier start = new CyclicBarrier(tasks.size());
            List<Future<Object>> futures = new ArrayList<>();
            for(Callable<?> task : tasks)
            {
                futures.add(pool.submit(() ->
                {
                    start.await(20, TimeUnit.SECONDS);
                    try
                    {
                        Object result = task.call();
                        return result == null ? "done" : result;
                    }
                    catch(Throwable t)
                    {
                        return t;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for(Future<Object> future : futures)
                results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    // ---- create and list

    @Test
    public void createAndListOwnAndPublic() throws Exception
    {
        ThemeFile zebra = createPublic(ALICE, "Zebra");
        createPublic(BOB, "apple");
        createPrivate(ALICE, "Mango");
        createPrivate(BOB, "Bob's secret");

        // what comes back is what was stored: ids, owner, version and times are the store's
        assertTrue(zebra.getId().matches("[a-z0-9]{12}"));
        assertEquals("Zebra", zebra.getName());
        assertEquals("public", zebra.getVisibility());
        assertEquals(ALICE.getId(), zebra.getOwner().getId());
        assertEquals("alice", zebra.getOwner().getName());
        assertEquals(1, zebra.getVersion());
        assertEquals(T0, zebra.getCreatedAt());
        assertEquals(T0, zebra.getUpdatedAt());
        assertFalse(zebra.isHiddenByOwner());
        assertEquals(zebra.toJson(), read(fileOf(zebra)));

        // public themes and the viewer's own, by name without regard to case
        assertEquals(Arrays.asList("apple", "Mango", "Zebra"), names(store.visibleTo(ALICE.getId())));
        assertEquals(Arrays.asList("apple", "Bob's secret", "Zebra"), names(store.visibleTo(BOB.getId())));
        // somebody else, or nobody logged in: public ones only
        assertEquals(Arrays.asList("apple", "Zebra"), names(store.visibleTo("333333333333333333")));
        assertEquals(Arrays.asList("apple", "Zebra"), names(store.visibleTo(null)));

        assertEquals(zebra.toJson(), store.get(zebra.getId(), BOB.getId()).get().toJson());
        assertEquals(zebra.toJson(), store.get(zebra.getId(), null).get().toJson());
    }

    @Test
    public void createStoresTheEditableValuesAndIgnoresTheRest()
    {
        JSONObject json = json("Full", "public").put("effects", true)
                .put("mini", new JSONObject().put("colors", miniColors()).put("useImage", false).put("dim", 30));
        ThemeFile given = ThemeFile.valuesFromClient(json);
        // an editor that is handed a full theme cannot choose who owns it, what version it is or whether the owner hid it
        given.setOwner(BOB);
        given.setVersion(77);
        given.setHiddenByOwner(true);
        given.setId("zzzzzzzzzzzz");

        ThemeFile made = store.create(given, ALICE, null);

        assertNotEquals("zzzzzzzzzzzz", made.getId());
        assertEquals(ALICE.getId(), made.getOwner().getId());
        assertEquals(1, made.getVersion());
        assertFalse(made.isHiddenByOwner());
        assertEquals("ember", made.getBase());
        assertTrue(made.isEffects());
        assertEquals("Outfit", made.getFont());
        assertEquals(100, made.getRadiusScale());
        assertEquals(30, made.getMini().getDim());
        assertFalse(made.getMini().isUseImage());
        assertNull(made.getBackground());
        assertEquals(made.toJson(), store.get(made.getId(), ALICE.getId()).get().toJson());
    }

    private static JSONObject miniColors()
    {
        JSONObject mini = colors();
        mini.remove("glass");
        return mini;
    }

    @Test
    public void foldersAreCreatedLazily() throws Exception
    {
        assertFalse(Files.exists(themesDir));
        assertEquals(new ArrayList<ThemeFile>(), store.visibleTo(ALICE.getId()));
        assertFalse(store.get("abcdefghijkl", ALICE.getId()).isPresent());
        assertEquals(0, store.countOwnedBy(ALICE.getId()));
        assertFalse(Files.exists(themesDir));

        ThemeFile made = createPublic(ALICE, "First");
        assertTrue(Files.isRegularFile(fileOf(made)));
        // no picture, no picture folder; and no temp files left in the themes folder
        assertFalse(Files.exists(imagesDir));
        assertEquals(Arrays.asList(made.getId() + ".json"), filesIn(themesDir));
    }

    @Test
    public void privateInvisibleToOthersAndOwner() throws Exception
    {
        ThemeFile secret = createPrivate(ALICE, "Secret");

        assertTrue(store.get(secret.getId(), ALICE.getId()).isPresent());
        assertFalse(store.get(secret.getId(), BOB.getId()).isPresent());
        assertFalse(store.get(secret.getId(), OWNER).isPresent());
        assertFalse(store.get(secret.getId(), null).isPresent());

        assertEquals(Arrays.asList("Secret"), names(store.visibleTo(ALICE.getId())));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(OWNER)));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(null)));
    }

    @Test
    public void limitOfTen() throws Exception
    {
        List<ThemeFile> made = new ArrayList<>();
        for(int i = 0; i < 10; i++)
            made.add(store.create(values("Theme " + i, i % 2 == 0 ? "public" : "private"), ALICE, null));
        assertEquals(10, store.countOwnedBy(ALICE.getId()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.create(valuesWithBackground("Eleventh", "public"), ALICE, upload(png(1))));
        assertEquals(LIMIT, e.getMessage());
        // refused before anything was written
        assertEquals(10, filesIn(themesDir).size());
        assertEquals(new ArrayList<String>(), pictures());

        // other people have their own ten; deleting one makes room
        createPublic(BOB, "Bob's");
        assertEquals(1, store.countOwnedBy(BOB.getId()));
        store.delete(made.get(0).getId(), ALICE.getId(), false);
        assertEquals(9, store.countOwnedBy(ALICE.getId()));
        createPublic(ALICE, "Eleventh at last");
        assertEquals(10, store.countOwnedBy(ALICE.getId()));
    }

    @Test
    public void countOwnedByCountsHiddenAndPrivateAndNobody()
    {
        ThemeFile one = createPublic(ALICE, "One");
        createPrivate(ALICE, "Two");
        store.hide(one.getId(), true, true);
        assertEquals(2, store.countOwnedBy(ALICE.getId()));
        assertEquals(0, store.countOwnedBy(BOB.getId()));
        assertEquals(0, store.countOwnedBy(null));
    }

    @Test
    public void twoCreatesAtNineLeaveRoomForOnlyOne() throws Exception
    {
        for(int round = 0; round < 15; round++)
        {
            Person user = new Person("5000000000000000" + (10 + round), "racer" + round);
            for(int i = 0; i < 9; i++)
                store.create(values("Theme " + i, "private"), user, null);

            List<Object> results = together(Arrays.asList(
                    () -> store.create(values("Race A", "private"), user, null),
                    () -> store.create(values("Race B", "private"), user, null)));

            long won = results.stream().filter(r -> r instanceof ThemeFile).count();
            List<Throwable> lost = results.stream().filter(r -> r instanceof Throwable).map(r -> (Throwable) r).collect(Collectors.toList());
            assertEquals("round " + round, 1, won);
            assertEquals(1, lost.size());
            assertTrue(lost.get(0) instanceof IllegalStateException);
            assertEquals(LIMIT, lost.get(0).getMessage());
            assertEquals(10, store.countOwnedBy(user.getId()));
        }
    }

    // ---- who may change what

    @Test
    public void onlyCreatorEdits() throws Exception
    {
        ThemeFile shared = createPublic(ALICE, "Shared");
        ThemeFile secret = createPrivate(ALICE, "Secret");
        String before = read(fileOf(shared));

        // somebody else, and the bot owner, can see a public theme but not change it
        ThemeForbiddenException e = assertThrows(ThemeForbiddenException.class,
                () -> store.update(shared.getId(), BOB.getId(), 1, values("Mine now", "public"), null, false));
        assertEquals("Only the creator can change this theme", e.getMessage());
        assertThrows(ThemeForbiddenException.class,
                () -> store.update(shared.getId(), OWNER, 1, values("Owner's", "public"), null, false));
        // nobody logged in
        assertThrows(ThemeForbiddenException.class,
                () -> store.update(shared.getId(), null, 1, values("Nobody's", "public"), null, false));
        assertEquals(before, read(fileOf(shared)));

        // a private theme of somebody else does not exist for anyone else, the owner included
        assertThrows(NoSuchElementException.class, () -> store.update(secret.getId(), BOB.getId(), 1, values("x", "public"), null, false));
        assertThrows(NoSuchElementException.class, () -> store.update(secret.getId(), OWNER, 1, values("x", "public"), null, false));
        assertThrows(NoSuchElementException.class, () -> store.update(secret.getId(), null, 1, values("x", "public"), null, false));

        // the creator can
        ThemeFile changed = store.update(shared.getId(), ALICE.getId(), 1, values("Renamed", "private"), null, false);
        assertEquals("Renamed", changed.getName());
        assertEquals("private", changed.getVisibility());
        assertEquals(changed.toJson(), read(fileOf(shared)));
        assertEquals(Arrays.asList("Renamed", "Secret"), names(store.visibleTo(ALICE.getId())));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
    }

    @Test
    public void updateKeepsWhoAndWhenAndBumpsTheVersion()
    {
        ThemeFile made = createPublic(ALICE, "Original");
        clock.set(T0.plusSeconds(600));
        ThemeFile values = values("Edited", "public");
        values.setOwner(BOB);
        values.setId("zzzzzzzzzzzz");
        values.setVersion(40);
        values.setCreatedAt(T0.minusSeconds(5));

        ThemeFile updated = store.update(made.getId(), ALICE.getId(), 1, values, null, false);

        assertEquals(made.getId(), updated.getId());
        assertEquals(ALICE.getId(), updated.getOwner().getId());
        assertEquals(2, updated.getVersion());
        assertEquals(T0, updated.getCreatedAt());
        assertEquals(T0.plusSeconds(600), updated.getUpdatedAt());
        assertEquals("Edited", updated.getName());
        assertEquals(updated.toJson(), store.get(made.getId(), ALICE.getId()).get().toJson());
    }

    @Test
    public void ownerHidesAndUnhides() throws Exception
    {
        ThemeFile theme = createPublic(ALICE, "Loud");
        assertEquals(Arrays.asList("Loud"), names(store.visibleTo(BOB.getId())));

        // only the bot owner can
        ThemeForbiddenException refused = assertThrows(ThemeForbiddenException.class, () -> store.hide(theme.getId(), false, true));
        assertEquals("Only the bot owner can do that", refused.getMessage());
        assertEquals(Arrays.asList("Loud"), names(store.visibleTo(BOB.getId())));

        clock.set(T0.plusSeconds(60));
        ThemeFile hidden = store.hide(theme.getId(), true, true);
        assertTrue(hidden.isHiddenByOwner());
        assertEquals("private", hidden.getVisibility());
        assertEquals(2, hidden.getVersion());
        assertEquals(T0.plusSeconds(60), hidden.getUpdatedAt());
        assertEquals(hidden.toJson(), read(fileOf(theme)));
        // gone for everyone but its creator, who sees why
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(null)));
        assertFalse(store.get(theme.getId(), BOB.getId()).isPresent());
        ThemeFile seen = store.get(theme.getId(), ALICE.getId()).get();
        assertTrue(seen.isHiddenByOwner());
        assertEquals("private", seen.getVisibility());

        // hiding it again changes nothing (no new version)
        assertEquals(2, store.hide(theme.getId(), true, true).getVersion());
        assertEquals(hidden.toJson(), read(fileOf(theme)));

        // un-hiding clears the flag but leaves it private; the creator can then share it again
        clock.set(T0.plusSeconds(120));
        ThemeFile shown = store.hide(theme.getId(), true, false);
        assertFalse(shown.isHiddenByOwner());
        assertEquals("private", shown.getVisibility());
        assertEquals(3, shown.getVersion());
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
        ThemeFile again = store.update(theme.getId(), ALICE.getId(), 3, values("Loud", "public"), null, false);
        assertEquals("public", again.getVisibility());
        assertEquals(Arrays.asList("Loud"), names(store.visibleTo(BOB.getId())));
        // un-hiding something that is not hidden changes nothing
        assertEquals(4, store.hide(theme.getId(), true, false).getVersion());
    }

    @Test
    public void hideHasNothingToDoWithMissingOrPlainPrivateThemes()
    {
        ThemeFile secret = createPrivate(ALICE, "Secret");
        assertThrows(NoSuchElementException.class, () -> store.hide(secret.getId(), true, true));
        assertThrows(NoSuchElementException.class, () -> store.hide(secret.getId(), true, false));
        assertThrows(NoSuchElementException.class, () -> store.hide("abcdefghijkl", true, true));
        assertEquals(1, store.get(secret.getId(), ALICE.getId()).get().getVersion());
    }

    @Test
    public void creatorCannotRepublishHidden() throws Exception
    {
        ThemeFile theme = createPublic(ALICE, "Loud");
        ThemeFile hidden = store.hide(theme.getId(), true, true);
        String before = read(fileOf(theme));

        ThemeForbiddenException e = assertThrows(ThemeForbiddenException.class,
                () -> store.update(theme.getId(), ALICE.getId(), hidden.getVersion(), values("Loud", "public"), null, false));
        assertEquals(HIDDEN, e.getMessage());
        assertEquals(before, read(fileOf(theme)));

        // other edits are fine, as long as it stays private; the flag stays
        ThemeFile quieter = store.update(theme.getId(), ALICE.getId(), hidden.getVersion(), values("Quiet", "private"), null, false);
        assertEquals("Quiet", quieter.getName());
        assertEquals("private", quieter.getVisibility());
        assertTrue(quieter.isHiddenByOwner());
        assertTrue(store.get(theme.getId(), ALICE.getId()).get().isHiddenByOwner());

        // a hidden theme cannot be made public, whatever the editor sends about the flag
        ThemeFile sneaky = values("Quiet", "public");
        sneaky.setHiddenByOwner(false);
        assertThrows(ThemeForbiddenException.class, () -> store.update(theme.getId(), ALICE.getId(), quieter.getVersion(), sneaky, null, false));
        assertTrue(store.get(theme.getId(), ALICE.getId()).get().isHiddenByOwner());
    }

    @Test
    public void aHandEditedFileThatIsHiddenAndPublicIsPrivate() throws Exception
    {
        ThemeFile theme = createPublic(ALICE, "Edited by hand");
        String text = read(fileOf(theme));
        assertTrue(text.contains("\"hiddenByOwner\": false"));
        write(fileOf(theme), text.replace("\"hiddenByOwner\": false", "\"hiddenByOwner\": true"));
        assertTrue(read(fileOf(theme)).contains("\"visibility\": \"public\""));

        // never listed or served to anybody else, even though the file says public
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(OWNER)));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(null)));
        assertFalse(store.get(theme.getId(), BOB.getId()).isPresent());
        assertFalse(store.get(theme.getId(), null).isPresent());
        assertThrows(NoSuchElementException.class, () -> store.delete(theme.getId(), OWNER, true));
        assertThrows(NoSuchElementException.class, () -> store.update(theme.getId(), BOB.getId(), 1, values("x", "public"), null, false));

        // its creator sees it as private and hidden, and still counts it
        ThemeFile mine = store.get(theme.getId(), ALICE.getId()).get();
        assertEquals("private", mine.getVisibility());
        assertTrue(mine.isHiddenByOwner());
        assertEquals(Arrays.asList("Edited by hand"), names(store.visibleTo(ALICE.getId())));
        assertEquals(1, store.countOwnedBy(ALICE.getId()));

        // and can't publish it
        assertThrows(ThemeForbiddenException.class, () -> store.update(theme.getId(), ALICE.getId(), 1, values("Edited by hand", "public"), null, false));
        // un-hiding it leaves it private on disk too
        ThemeFile shown = store.hide(theme.getId(), true, false);
        assertFalse(shown.isHiddenByOwner());
        assertEquals("private", shown.getVisibility());
        assertTrue(read(fileOf(theme)).contains("\"visibility\": \"private\""));
        assertEquals(new ArrayList<String>(), names(store.visibleTo(BOB.getId())));
    }

    @Test
    public void ownerDeletesPublic() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        String picture = imageOf(theme);
        assertEquals(Arrays.asList(picture), pictures());

        // somebody else may not, nobody logged in may not
        ThemeForbiddenException e = assertThrows(ThemeForbiddenException.class, () -> store.delete(theme.getId(), BOB.getId(), false));
        assertEquals("Only the creator can delete this theme", e.getMessage());
        assertThrows(ThemeForbiddenException.class, () -> store.delete(theme.getId(), null, false));
        // "the owner" without a login is nobody
        assertThrows(ThemeForbiddenException.class, () -> store.delete(theme.getId(), null, true));
        assertTrue(Files.exists(fileOf(theme)));

        store.delete(theme.getId(), OWNER, true);

        assertFalse(Files.exists(fileOf(theme)));
        assertEquals(new ArrayList<String>(), pictures());
        assertFalse(store.get(theme.getId(), ALICE.getId()).isPresent());
        assertEquals(0, store.countOwnedBy(ALICE.getId()));
    }

    @Test
    public void creatorDeletesOwnPublicAndPrivateAndThePictureGoesWithIt() throws Exception
    {
        ThemeFile open = createWithPicture(ALICE, "Open", "public", 1);
        ThemeFile closed = createWithPicture(ALICE, "Closed", "private", 2);
        ThemeFile plain = createPrivate(ALICE, "Plain");
        assertEquals(2, pictures().size());

        store.delete(open.getId(), ALICE.getId(), false);
        assertEquals(Arrays.asList(imageOf(closed)), pictures());
        store.delete(closed.getId(), ALICE.getId(), false);
        store.delete(plain.getId(), ALICE.getId(), false);
        assertEquals(new ArrayList<String>(), pictures());
        assertEquals(new ArrayList<String>(), filesIn(themesDir));

        // gone is gone
        assertThrows(NoSuchElementException.class, () -> store.delete(open.getId(), ALICE.getId(), false));
        assertThrows(NoSuchElementException.class, () -> store.delete("abcdefghijkl", ALICE.getId(), false));
    }

    @Test
    public void ownerCannotSeeOrDeletePrivate() throws Exception
    {
        ThemeFile secret = createPrivate(ALICE, "Secret");

        // 404, exactly as for a theme that is not there, so the owner can't tell it exists
        NoSuchElementException e = assertThrows(NoSuchElementException.class, () -> store.delete(secret.getId(), OWNER, true));
        assertEquals("Theme not found", e.getMessage());
        assertEquals("Theme not found", assertThrows(NoSuchElementException.class, () -> store.delete("abcdefghijkl", OWNER, true)).getMessage());
        // so does somebody else, and nobody logged in
        assertThrows(NoSuchElementException.class, () -> store.delete(secret.getId(), BOB.getId(), false));
        assertThrows(NoSuchElementException.class, () -> store.delete(secret.getId(), null, false));
        assertTrue(Files.exists(fileOf(secret)));

        // a hidden theme is private to its creator: the owner no longer reaches it either
        ThemeFile loud = createPublic(BOB, "Loud");
        store.hide(loud.getId(), true, true);
        assertThrows(NoSuchElementException.class, () -> store.delete(loud.getId(), OWNER, true));
        assertTrue(Files.exists(fileOf(loud)));
        // but the creator can
        store.delete(loud.getId(), BOB.getId(), false);
        assertFalse(Files.exists(fileOf(loud)));
    }

    @Test
    public void nobodyLoggedInCannotCreate()
    {
        assertThrows(ThemeForbiddenException.class, () -> store.create(values("x", "public"), null, null));
        assertFalse(Files.exists(themesDir));
    }

    // ---- versions

    @Test
    public void versionConflict() throws Exception
    {
        ThemeFile theme = createPublic(ALICE, "Versioned");
        ThemeFile second = store.update(theme.getId(), ALICE.getId(), 1, values("Second", "public"), null, false);
        assertEquals(2, second.getVersion());
        String before = read(fileOf(theme));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, values("Stale", "public"), null, false));
        assertEquals(CHANGED, e.getMessage());
        assertEquals(before, read(fileOf(theme)));
        // too new is just as wrong
        assertThrows(IllegalStateException.class, () -> store.update(theme.getId(), ALICE.getId(), 3, values("Future", "public"), null, false));
        assertEquals(before, read(fileOf(theme)));

        // a stale save with a picture leaves no picture behind
        assertThrows(IllegalStateException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Stale", "public"), upload(png(1)), false));
        assertEquals(new ArrayList<String>(), pictures());
        assertEquals(before, read(fileOf(theme)));

        assertEquals(3, store.update(theme.getId(), ALICE.getId(), 2, values("Third", "public"), null, false).getVersion());
    }

    @Test
    public void concurrentSaves() throws Exception
    {
        for(int round = 0; round < 10; round++)
        {
            ThemeFile theme = createWithPicture(ALICE, "Race " + round, "public", 1);
            String original = imageOf(theme);

            List<Object> results = together(Arrays.asList(
                    () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Left", "public"), upload(png(2)), false),
                    () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Right", "public"), upload(png(3)), false)));

            List<ThemeFile> won = results.stream().filter(r -> r instanceof ThemeFile).map(r -> (ThemeFile) r).collect(Collectors.toList());
            List<Throwable> lost = results.stream().filter(r -> r instanceof Throwable).map(r -> (Throwable) r).collect(Collectors.toList());
            assertEquals("round " + round + ": " + results, 1, won.size());
            assertEquals(1, lost.size());
            assertTrue(lost.get(0) instanceof IllegalStateException);
            assertEquals(CHANGED, lost.get(0).getMessage());

            ThemeFile stored = store.get(theme.getId(), ALICE.getId()).get();
            assertEquals(2, stored.getVersion());
            assertEquals(won.get(0).getName(), stored.getName());
            // the loser's picture was never kept and the first one is gone: exactly the winner's remains
            assertNotEquals(original, imageOf(stored));
            assertEquals(Arrays.asList(imageOf(stored)), pictures());
            store.delete(theme.getId(), ALICE.getId(), false);
            assertEquals(new ArrayList<String>(), pictures());
        }
    }

    // ---- pictures

    @Test
    public void createWithPictureStoresItAndNamesItInTheTheme() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 7);

        String name = imageOf(theme);
        assertTrue(name, name.matches(NAME_RULE));
        assertTrue(name.endsWith(".png"));
        assertArrayEquals(png(7), Files.readAllBytes(imagesDir.resolve(name)));
        assertTrue(images.resolve(name).isPresent());
        assertEquals(45, theme.getBackground().getDim());
        assertEquals(4, theme.getBackground().getBlur());
        assertEquals("cover", theme.getBackground().getFit());
        assertFalse(theme.isEffects());
        assertEquals(name, imageOf(store.get(theme.getId(), BOB.getId()).get()));
        // pictures live in the images folder, themes next to it
        assertEquals(Arrays.asList(theme.getId() + ".json"), filesIn(themesDir));
    }

    @Test
    public void aBackgroundNeedsAPicture() throws Exception
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.create(valuesWithBackground("No picture", "public"), ALICE, null));
        assertEquals(NEEDS_PICTURE, e.getMessage());
        // a picture without settings for it is just as unusable
        assertThrows(IllegalArgumentException.class, () -> store.create(values("No settings", "public"), ALICE, upload(png(1))));
        assertFalse(Files.exists(themesDir));
        assertFalse(Files.exists(imagesDir));

        // the same when editing a theme that has no picture yet
        ThemeFile plain = createPublic(ALICE, "Plain");
        String before = read(fileOf(plain));
        assertEquals(NEEDS_PICTURE, assertThrows(IllegalArgumentException.class,
                () -> store.update(plain.getId(), ALICE.getId(), 1, valuesWithBackground("Plain", "public"), null, false)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.update(plain.getId(), ALICE.getId(), 1, values("Plain", "public"), upload(png(1)), false));
        // a picture and "remove it" in one go makes no sense
        assertThrows(IllegalArgumentException.class,
                () -> store.update(plain.getId(), ALICE.getId(), 1, valuesWithBackground("Plain", "public"), upload(png(1)), true));
        assertEquals(before, read(fileOf(plain)));
        assertFalse(Files.exists(imagesDir));
    }

    @Test
    public void pictureReplacedDeletesOld() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        String first = imageOf(theme);

        ThemeFile replaced = store.update(theme.getId(), ALICE.getId(), 1,
                ThemeFile.valuesFromClient(json("Pretty", "public").put("background", new JSONObject().put("dim", 10).put("blur", 2).put("fit", "contain"))),
                upload(png(2)), false);

        String second = imageOf(replaced);
        assertNotEquals(first, second);
        assertEquals(Arrays.asList(second), pictures());
        assertArrayEquals(png(2), Files.readAllBytes(imagesDir.resolve(second)));
        assertEquals(10, replaced.getBackground().getDim());
        assertEquals("contain", replaced.getBackground().getFit());
        assertEquals(second, imageOf(store.get(theme.getId(), ALICE.getId()).get()));
    }

    @Test
    public void backgroundSettingsWithoutANewPictureKeepTheStoredOne() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        String picture = imageOf(theme);

        ThemeFile tweaked = store.update(theme.getId(), ALICE.getId(), 1,
                ThemeFile.valuesFromClient(json("Pretty", "public").put("background", new JSONObject().put("dim", 80).put("blur", 0).put("fit", "center"))),
                null, false);

        assertEquals(picture, imageOf(tweaked));
        assertEquals(80, tweaked.getBackground().getDim());
        assertEquals("center", tweaked.getBackground().getFit());
        assertEquals(Arrays.asList(picture), pictures());
    }

    @Test
    public void removeBackgroundDeletesPicture() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        assertEquals(1, pictures().size());

        ThemeFile bare = store.update(theme.getId(), ALICE.getId(), 1, values("Pretty", "public"), null, true);

        assertNull(bare.getBackground());
        assertEquals(new ArrayList<String>(), pictures());
        assertNull(store.get(theme.getId(), ALICE.getId()).get().getBackground());
        assertFalse(read(fileOf(theme)).contains("background"));

        // the flag wins even when the editor still sends its background sliders
        ThemeFile again = createWithPicture(ALICE, "Again", "public", 2);
        ThemeFile gone = store.update(again.getId(), ALICE.getId(), 1, valuesWithBackground("Again", "public"), null, true);
        assertNull(gone.getBackground());
        assertEquals(new ArrayList<String>(), pictures());
    }

    @Test
    public void sendingNoBackgroundDropsTheStoredPicture() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);

        ThemeFile bare = store.update(theme.getId(), ALICE.getId(), 1, values("Pretty", "public"), null, false);

        assertNull(bare.getBackground());
        assertEquals(new ArrayList<String>(), pictures());

        // effects can come back once there's no picture
        ThemeFile fancy = store.update(theme.getId(), ALICE.getId(), 2,
                ThemeFile.valuesFromClient(json("Pretty", "public").put("effects", true)), null, false);
        assertTrue(fancy.isEffects());
    }

    @Test
    public void badPicturesAreRefusedAndLeaveNothing() throws Exception
    {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.create(valuesWithBackground("Svg", "public"), ALICE, upload(svg)));
        assertEquals(NOT_A_PICTURE, e.getMessage());
        assertEquals(new ArrayList<String>(), filesIn(themesDir));
        assertEquals(new ArrayList<String>(), pictures());

        // on an edit the theme stays as it was, picture included
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        String before = read(fileOf(theme));
        assertThrows(IllegalArgumentException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Pretty", "public"), upload(svg), false));
        assertEquals(before, read(fileOf(theme)));
        assertEquals(Arrays.asList(imageOf(theme)), pictures());
    }

    @Test
    public void tooLargePicturesAreRefusedAndLeaveNothing() throws Exception
    {
        // said to be too big: refused before a byte is read
        InputStream untouched = new InputStream()
        {
            @Override
            public int read()
            {
                throw new AssertionError("must not be read");
            }
        };
        PictureTooLargeException declared = assertThrows(PictureTooLargeException.class,
                () -> store.create(valuesWithBackground("Big", "public"), ALICE, new PictureUpload(untouched, EIGHT_MB + 1)));
        assertEquals(TOO_LARGE, declared.getMessage());
        assertFalse(Files.exists(themesDir));
        assertFalse(Files.exists(imagesDir));

        // claims to be small (or does not say) but is not: stopped while being written
        byte[] big = new byte[(int) EIGHT_MB + 1];
        System.arraycopy(png(1), 0, big, 0, png(1).length);
        assertThrows(PictureTooLargeException.class,
                () -> store.create(valuesWithBackground("Big", "public"), ALICE, new PictureUpload(new ByteArrayInputStream(big), 10)));
        assertThrows(PictureTooLargeException.class,
                () -> store.create(valuesWithBackground("Big", "public"), ALICE, new PictureUpload(new ByteArrayInputStream(big), -1)));
        assertEquals(new ArrayList<String>(), filesIn(themesDir));
        assertEquals(new ArrayList<String>(), pictures());

        // exactly 8 MB is fine
        byte[] exact = Arrays.copyOf(big, (int) EIGHT_MB);
        ThemeFile theme = store.create(valuesWithBackground("Exactly", "public"), ALICE, new PictureUpload(new ByteArrayInputStream(exact), exact.length));
        assertEquals(EIGHT_MB, Files.size(imagesDir.resolve(imageOf(theme))));
        // too large on an edit: the old picture stays
        assertThrows(PictureTooLargeException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Exactly", "public"),
                        new PictureUpload(new ByteArrayInputStream(big), big.length), false));
        assertEquals(Arrays.asList(imageOf(theme)), pictures());
    }

    @Test
    public void failedJsonWriteRemovesNewPicture() throws Exception
    {
        // the themes "folder" is a plain file, so the theme cannot be written; the pictures have a folder of their own
        write(themesDir, "in the way");
        Path pictureFolder = tmp.getRoot().toPath().resolve("pictures");
        ThemeStore broken = new ThemeStore(themesDir, new ThemeImages(pictureFolder), clock);

        assertThrows(UncheckedIOException.class, () -> broken.create(valuesWithBackground("Doomed", "public"), ALICE, upload(png(1))));

        // the picture was saved first (its folder exists) and taken away again
        assertTrue(Files.isDirectory(pictureFolder));
        assertEquals(new ArrayList<String>(), filesIn(pictureFolder));
        assertEquals("in the way", read(themesDir));
    }

    @Test
    public void failedUpdateWriteRemovesNewPictureAndKeepsTheOld() throws Exception
    {
        FlakyStore flaky = new FlakyStore(themesDir, images, clock);
        ThemeFile theme = flaky.create(valuesWithBackground("Pretty", "public"), ALICE, upload(png(1)));
        String before = read(fileOf(theme));
        assertEquals(1, pictures().size());

        flaky.failWrites = true;
        assertThrows(UncheckedIOException.class,
                () -> flaky.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Pretty", "public"), upload(png(2)), false));
        assertThrows(UncheckedIOException.class, () -> flaky.update(theme.getId(), ALICE.getId(), 1, values("Pretty", "public"), null, true));
        assertThrows(UncheckedIOException.class, () -> flaky.hide(theme.getId(), true, true));
        flaky.failWrites = false;

        // the theme is as it was, with its own picture; the failed upload left no file; nothing was deleted
        assertEquals(before, read(fileOf(theme)));
        assertEquals(Arrays.asList(imageOf(theme)), pictures());
    }

    @Test
    public void saveAfterDeleteIs404AndPictureRemoved() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Doomed", "public", 1);
        store.delete(theme.getId(), ALICE.getId(), false);
        assertEquals(new ArrayList<String>(), pictures());

        // the editor still had it open: the save is refused and its picture is not kept
        assertThrows(NoSuchElementException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Doomed", "public"), upload(png(2)), false));
        assertThrows(NoSuchElementException.class,
                () -> store.update(theme.getId(), ALICE.getId(), 1, values("Doomed", "public"), null, false));

        assertFalse(Files.exists(fileOf(theme)));
        assertEquals(new ArrayList<String>(), pictures());
        assertEquals(new ArrayList<String>(), filesIn(themesDir));
    }

    @Test
    public void aDeleteRacingASaveLeavesOneOutcomeAndNoStrayPicture() throws Exception
    {
        for(int round = 0; round < 20; round++)
        {
            ThemeFile theme = createWithPicture(ALICE, "Race " + round, "public", 1);

            List<Object> results = together(Arrays.asList(
                    () ->
                    {
                        store.delete(theme.getId(), ALICE.getId(), false);
                        return null;
                    },
                    () -> store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Saved", "public"), upload(png(2)), false)));

            // the delete always works; the save either came first (and its new picture went with the theme) or found nothing
            assertEquals("round " + round + ": " + results, "done", results.get(0));
            assertTrue(results.get(1) instanceof ThemeFile || results.get(1) instanceof NoSuchElementException);
            assertFalse(Files.exists(fileOf(theme)));
            assertEquals("round " + round, new ArrayList<String>(), pictures());
        }
    }

    // ---- checks before anything is written

    @Test
    public void incompleteValuesAreRefusedBeforeAnythingIsWritten() throws Exception
    {
        ThemeFile empty = new ThemeFile();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.create(empty, ALICE, null));
        assertEquals("The theme is incomplete", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.create(null, ALICE, null));
        assertFalse(Files.exists(themesDir));

        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        String before = read(fileOf(theme));
        assertThrows(IllegalArgumentException.class, () -> store.update(theme.getId(), ALICE.getId(), 1, empty, upload(png(2)), false));
        assertThrows(IllegalArgumentException.class, () -> store.update(theme.getId(), ALICE.getId(), 1, null, null, false));
        assertEquals(before, read(fileOf(theme)));
        assertEquals(Arrays.asList(imageOf(theme)), pictures());
    }

    @Test
    public void oddIdsAreJustNotThere() throws Exception
    {
        createPublic(ALICE, "Real");
        write(tmp.getRoot().toPath().resolve("secret.json"), "{}");
        for(String id : new String[]{null, "", "../secret", "..\\secret", "../Themes/x", "ABCDEFGHIJKL", "abcdefghijk", "abcdefghijklm", "abcdefghijk.", "abc/efghijkl"})
        {
            assertFalse(String.valueOf(id), store.get(id, ALICE.getId()).isPresent());
            assertThrows(NoSuchElementException.class, () -> store.update(id, ALICE.getId(), 1, values("x", "public"), null, false));
            assertThrows(NoSuchElementException.class, () -> store.delete(id, ALICE.getId(), false));
            assertThrows(NoSuchElementException.class, () -> store.hide(id, true, true));
        }
        assertTrue(Files.exists(tmp.getRoot().toPath().resolve("secret.json")));
        assertEquals(Arrays.asList("Real"), names(store.visibleTo(ALICE.getId())));
    }

    // ---- bad files on disk

    @Test
    public void corruptOrInvalidFileSkipped() throws Exception
    {
        ThemeFile good = createPublic(ALICE, "Good");
        ThemeFile alsoGood = createPrivate(ALICE, "Also good");
        String goodText = read(fileOf(good));

        String corrupt = "{oops";
        String badColor = goodText.replace("\"id\": \"" + good.getId() + "\"", "\"id\": \"bbbbbbbbbbbb\"")
                .replace("#7c5cff", "red; background:url(x)");
        String badRadius = goodText.replace("\"id\": \"" + good.getId() + "\"", "\"id\": \"cccccccccccc\"")
                .replace("\"radiusScale\": 100", "\"radiusScale\": 999");
        // a valid theme under the wrong name: the file name is the identity
        String wrongName = goodText.replace("\"id\": \"" + good.getId() + "\"", "\"id\": \"eeeeeeeeeeee\"");
        assertNotEquals(goodText, badColor);
        assertNotEquals(goodText, badRadius);
        write(themesDir.resolve("aaaaaaaaaaaa.json"), corrupt);
        write(themesDir.resolve("bbbbbbbbbbbb.json"), badColor);
        write(themesDir.resolve("cccccccccccc.json"), badRadius);
        write(themesDir.resolve("dddddddddddd.json"), wrongName);
        // not theme files at all: not even a warning
        write(themesDir.resolve("notes.json"), "{}");
        write(themesDir.resolve("readme.txt"), "hello");
        Files.createDirectories(themesDir.resolve("ffffffffffff.json"));
        log.list.clear();

        assertEquals(Arrays.asList("Also good", "Good"), names(store.visibleTo(ALICE.getId())));
        // one warning per bad file, naming it
        List<ILoggingEvent> first = warnings(log);
        assertEquals(first.toString(), 4, first.size());
        for(String bad : new String[]{"aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc", "dddddddddddd"})
            assertTrue(bad, first.stream().anyMatch(e -> e.getFormattedMessage().contains(bad + ".json")));

        // and again for every listing, whoever asks
        assertEquals(Arrays.asList("Good"), names(store.visibleTo(BOB.getId())));
        assertEquals(8, warnings(log).size());

        // left alone on disk, untouched
        assertEquals(corrupt, read(themesDir.resolve("aaaaaaaaaaaa.json")));
        assertEquals(badColor, read(themesDir.resolve("bbbbbbbbbbbb.json")));
        assertEquals(badRadius, read(themesDir.resolve("cccccccccccc.json")));
        assertEquals(wrongName, read(themesDir.resolve("dddddddddddd.json")));
        assertEquals(goodText, read(fileOf(good)));

        // they are not themes: not served, not editable, not deletable, not counted
        for(String bad : new String[]{"aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc", "dddddddddddd", "eeeeeeeeeeee", "ffffffffffff"})
        {
            assertFalse(bad, store.get(bad, ALICE.getId()).isPresent());
            assertThrows(NoSuchElementException.class, () -> store.update(bad, ALICE.getId(), 1, values("x", "public"), null, false));
            assertThrows(NoSuchElementException.class, () -> store.delete(bad, ALICE.getId(), false));
            assertThrows(NoSuchElementException.class, () -> store.hide(bad, true, true));
        }
        assertEquals(2, store.countOwnedBy(ALICE.getId()));
        assertEquals(corrupt, read(themesDir.resolve("aaaaaaaaaaaa.json")));
        assertTrue(Files.isRegularFile(fileOf(alsoGood)));
    }

    @Test
    public void aListingOfGoodFilesLogsNothing()
    {
        createPublic(ALICE, "Good");
        store.visibleTo(ALICE.getId());
        store.get("abcdefghijkl", ALICE.getId());
        assertEquals(0, warnings(log).size());
    }

    // ---- clean-up of temp files

    @Test
    public void staleTempFilesAreSweptOnStart() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Keeper", "public", 1);
        Instant old = T0.minus(java.time.Duration.ofHours(2));
        Instant recent = T0.minus(java.time.Duration.ofMinutes(30));
        Path oldTheme = themesDir.resolve(".theme-old.tmp");
        Path recentTheme = themesDir.resolve(".theme-recent.tmp");
        Path oldPicture = imagesDir.resolve(".pic-old.tmp");
        Path recentPicture = imagesDir.resolve(".pic-recent.tmp");
        Path oldOther = themesDir.resolve("notes.tmp");
        Path oldOtherPicture = imagesDir.resolve("notes.tmp");
        Path oldTheFile = fileOf(theme);
        Path oldPictureFile = imagesDir.resolve(imageOf(theme));
        for(Path file : new Path[]{oldTheme, recentTheme, oldPicture, recentPicture, oldOther, oldOtherPicture})
            write(file, "partial");
        for(Path file : new Path[]{oldTheme, oldPicture, oldOther, oldOtherPicture, oldTheFile, oldPictureFile})
            Files.setLastModifiedTime(file, FileTime.from(old));
        for(Path file : new Path[]{recentTheme, recentPicture})
            Files.setLastModifiedTime(file, FileTime.from(recent));

        new ThemeStore(themesDir, new ThemeImages(imagesDir), clock);

        assertFalse(Files.exists(oldTheme));
        assertFalse(Files.exists(oldPicture));
        // fresh ones may belong to a write that is still going on
        assertTrue(Files.exists(recentTheme));
        assertTrue(Files.exists(recentPicture));
        // only the temp names of the store and the pictures; old themes and pictures are not stale
        assertTrue(Files.exists(oldOther));
        assertTrue(Files.exists(oldOtherPicture));
        assertTrue(Files.exists(oldTheFile));
        assertTrue(Files.exists(oldPictureFile));
        assertEquals(1, store.visibleTo(ALICE.getId()).size());
    }

    @Test
    public void sweepingNeverMakesFoldersOrFails() throws Exception
    {
        Path elsewhere = tmp.getRoot().toPath().resolve("elsewhere").resolve("Themes");
        new ThemeStore(elsewhere, new ThemeImages(elsewhere.resolve("images")), clock);
        assertFalse(Files.exists(elsewhere));

        // a plain file where the folder should be is no reason for the bot not to start
        Path file = tmp.getRoot().toPath().resolve("file");
        write(file, "x");
        new ThemeStore(file, new ThemeImages(file.resolve("images")), clock);
        assertEquals("x", read(file));
    }

    @Test
    public void noTempFilesAreLeftByNormalWrites() throws Exception
    {
        ThemeFile theme = createWithPicture(ALICE, "Pretty", "public", 1);
        store.update(theme.getId(), ALICE.getId(), 1, valuesWithBackground("Pretty 2", "public"), upload(png(2)), false);
        store.hide(theme.getId(), true, true);
        store.hide(theme.getId(), true, false);

        assertEquals(Arrays.asList(theme.getId() + ".json"), filesIn(themesDir));
        assertEquals(1, pictures().size());
        assertTrue(pictures().get(0).matches(NAME_RULE));
        Optional<ThemeFile> stored = store.get(theme.getId(), ALICE.getId());
        assertEquals(4, stored.get().getVersion());
    }
}
