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
package com.jagrosh.jmusicbot.playlist;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class PlaylistTrashTest
{
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final String DELETED_NOT_FOUND = "Deleted playlist not found";
    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");
    private static final Person FRED = new Person("233362518045753345", "fred");
    private static final Person SNOOP = new Person("42", "snoop");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private Path trashDir;
    private TestClock clock;
    private PlaylistStore store;

    @Before
    public void setUp() throws Exception
    {
        root = tmp.getRoot().toPath().resolve("Playlists");
        trashDir = root.resolve(".deleted");
        clock = new TestClock(T0);
        store = new PlaylistStore(root, clock);
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

        void advance(Duration by)
        {
            this.now = now.plus(by);
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

    private static void write(Path file, String text) throws Exception
    {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String repeat(String text, int count)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < count; i++)
            sb.append(text);
        return sb.toString();
    }

    private static Song song(String title)
    {
        Song song = new Song();
        song.setId(PlaylistFile.newSongId(new Random()));
        song.setTitle(title);
        song.setUri("https://example.com/" + title);
        return song;
    }

    private static List<String> tmpFilesUnder(Path dir) throws Exception
    {
        try(Stream<Path> files = Files.walk(dir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).map(Path::toString)
                    .collect(Collectors.toList());
        }
    }

    private static List<String> ids(List<PlaylistStore.Deleted> deleted)
    {
        List<String> ids = new ArrayList<>();
        for(PlaylistStore.Deleted entry : deleted)
            ids.add(entry.getId());
        return ids;
    }

    private static List<String> names(List<PlaylistStore.Deleted> deleted)
    {
        List<String> names = new ArrayList<>();
        for(PlaylistStore.Deleted entry : deleted)
            names.add(entry.getOriginalFolder() + "|" + entry.getName());
        return names;
    }

    private static List<String> sorted(List<String> list)
    {
        List<String> copy = new ArrayList<>(list);
        Collections.sort(copy);
        return copy;
    }

    private static String trashName(Instant at, String name)
    {
        return at.toEpochMilli() + "-" + name + ".json";
    }

    /** Runs {@code body} with the store's warnings collected instead of printed. */
    private static List<String> warningsDuring(ThrowingRunnable body) throws Exception
    {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PlaylistStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected warnings out of the test output
        logger.addAppender(appender);
        try
        {
            body.run();
        }
        finally
        {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }

    /** Sets one top-level field of a trash file, the way a hand edit would. */
    private void editTrash(String id, String key, Object value) throws Exception
    {
        Path file = trashDir.resolve(id);
        write(file, new JSONObject(read(file)).put(key, value).toString(2));
    }

    @Test
    public void deleteAndRestore() throws Exception
    {
        store.create("Chill", "Rock", FRED);
        store.addSongs("Chill", "Rock", Arrays.asList(song("a"), song("b")), null, FRED);
        List<String> songIds = new ArrayList<>();
        for(Song s : store.get("Chill", "Rock").get().getSongs())
            songIds.add(s.getId());

        clock.advance(Duration.ofSeconds(60));
        Instant deletedAt = clock.instant();
        PlaylistStore.Deleted gone = store.delete("Chill", "Rock", SNOOP);

        assertEquals(trashName(deletedAt, "Chill"), gone.getId());
        assertEquals("Chill", gone.getName());
        assertEquals("Rock", gone.getOriginalFolder());
        assertEquals("42", gone.getDeletedBy().getId());
        assertEquals(deletedAt, gone.getDeletedAt());
        assertEquals(7, gone.getDaysLeft());

        // the file moved: nothing left in its folder, and the listings do not show the trash
        assertFalse(Files.exists(root.resolve("Rock").resolve("Chill.json")));
        assertTrue(Files.isDirectory(root.resolve("Rock")));
        assertFalse(store.get("Chill", "Rock").isPresent());
        assertTrue(store.tree().isEmpty());
        assertEquals(Arrays.asList("Rock"), store.folders());

        // the trash file holds the playlist as it was, plus the three fields
        JSONObject raw = new JSONObject(read(trashDir.resolve(gone.getId())));
        assertEquals(2, raw.getInt("format"));
        assertEquals("Chill", raw.getString("name"));
        assertEquals(2, raw.getJSONArray("songs").length());
        assertEquals("42", raw.getJSONObject("deletedBy").getString("id"));
        assertEquals("snoop", raw.getJSONObject("deletedBy").getString("name"));
        assertEquals(deletedAt.toString(), raw.getString("deletedAt"));
        assertEquals("Rock", raw.getString("originalFolder"));
        assertEquals(2L, raw.getLong("version"));
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));

        List<PlaylistStore.Deleted> listed = store.deleted();
        assertEquals(1, listed.size());
        assertEquals(gone.getId(), listed.get(0).getId());
        assertEquals("Chill", listed.get(0).getName());
        assertEquals("Rock", listed.get(0).getOriginalFolder());
        assertEquals("snoop", listed.get(0).getDeletedBy().getName());
        assertEquals(deletedAt, listed.get(0).getDeletedAt());

        clock.advance(Duration.ofMinutes(5));
        Instant restoredAt = clock.instant();
        PlaylistFile restored = store.restore(gone.getId());

        assertEquals("Chill", restored.getName());
        assertEquals(3L, restored.getVersion());
        assertEquals(restoredAt, restored.getUpdatedAt());
        assertNull(restored.getDeletedBy());
        assertNull(restored.getDeletedAt());
        assertNull(restored.getOriginalFolder());
        assertEquals(2, restored.getSongs().size());

        Path back = root.resolve("Rock").resolve("Chill.json");
        assertTrue(Files.isRegularFile(back));
        JSONObject onDisk = new JSONObject(read(back));
        assertFalse(onDisk.has("deletedBy"));
        assertFalse(onDisk.has("deletedAt"));
        assertFalse(onDisk.has("originalFolder"));
        PlaylistFile loaded = store.get("Chill", "Rock").get();
        assertEquals(3L, loaded.getVersion());
        assertEquals(songIds.get(0), loaded.getSongs().get(0).getId());
        assertEquals(songIds.get(1), loaded.getSongs().get(1).getId());
        assertEquals("fred", loaded.getCreatedBy().getName());
        assertFalse(Files.exists(trashDir.resolve(gone.getId())));
        assertTrue(store.deleted().isEmpty());
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
    }

    @Test
    public void deleteWithoutAPersonStillWorks() throws Exception
    {
        store.create("Anon", "", FRED);
        PlaylistStore.Deleted gone = store.delete("Anon", "", null);
        assertNull(gone.getDeletedBy());
        assertEquals("", gone.getOriginalFolder());
        assertFalse(new JSONObject(read(trashDir.resolve(gone.getId()))).has("deletedBy"));
        assertEquals("Anon", store.restore(gone.getId()).getName());
        assertTrue(Files.isRegularFile(root.resolve("Anon.json")));
    }

    @Test
    public void deleteRefusesWhatItCannotMove() throws Exception
    {
        write(root.resolve("Old.txt"), "https://example.com/a\n");
        IllegalStateException legacy = assertThrows(IllegalStateException.class, () -> store.delete("Old", "", FRED));
        assertEquals(UPGRADING, legacy.getMessage());
        assertTrue(Files.isRegularFile(root.resolve("Old.txt")));
        assertTrue(store.deleted().isEmpty());

        NoSuchElementException unknown = assertThrows(NoSuchElementException.class, () -> store.delete("Nope", "", FRED));
        assertEquals("Playlist not found", unknown.getMessage());

        write(root.resolve("Bad.json"), "{oops");
        List<String> warnings = warningsDuring(() ->
                assertThrows(NoSuchElementException.class, () -> store.delete("Bad", "", FRED)));
        assertEquals(1, warnings.size());
        assertEquals("{oops", read(root.resolve("Bad.json")));

        assertThrows(IllegalArgumentException.class, () -> store.delete("../x", "", FRED));
        assertThrows(IllegalArgumentException.class, () -> store.delete("x", "../y", FRED));
        assertFalse(Files.exists(trashDir));
    }

    @Test
    public void deletesInTheSameMillisecondKeepBoth() throws Exception
    {
        store.create("Twin", "A", FRED);
        store.create("Twin", "B", FRED);
        PlaylistStore.Deleted first = store.delete("Twin", "A", FRED);
        PlaylistStore.Deleted second = store.delete("Twin", "B", FRED);
        store.create("Twin", "C", FRED);
        PlaylistStore.Deleted third = store.delete("Twin", "C", FRED);

        assertEquals(trashName(T0, "Twin"), first.getId());
        assertEquals(T0.toEpochMilli() + "-Twin-2.json", second.getId());
        assertEquals(T0.toEpochMilli() + "-Twin-3.json", third.getId());
        assertEquals(3, store.deleted().size());
        assertEquals("B", new JSONObject(read(trashDir.resolve(second.getId()))).getString("originalFolder"));
        assertEquals("Twin", store.restore(second.getId()).getName());
        assertTrue(Files.isRegularFile(root.resolve("B").resolve("Twin.json")));
    }

    @Test
    public void restoreClashGetsRestoredSuffix() throws Exception
    {
        store.create("Mix", "", FRED);
        PlaylistStore.Deleted first = store.delete("Mix", "", FRED);
        clock.advance(Duration.ofSeconds(1));
        store.create("Mix", "", FRED);
        PlaylistStore.Deleted second = store.delete("Mix", "", FRED);
        clock.advance(Duration.ofSeconds(1));
        store.create("Mix", "", FRED);
        store.addSongs("Mix", "", Arrays.asList(song("live")), null, FRED);

        PlaylistFile one = store.restore(first.getId());
        assertEquals("Mix (restored)", one.getName());
        PlaylistFile two = store.restore(second.getId());
        assertEquals("Mix (restored 2)", two.getName());

        clock.advance(Duration.ofSeconds(1));
        PlaylistStore.Deleted third = store.delete("Mix", "", FRED);
        store.create("Mix", "", FRED);
        assertEquals("Mix (restored 3)", store.restore(third.getId()).getName());

        assertTrue(Files.isRegularFile(root.resolve("Mix (restored).json")));
        assertTrue(Files.isRegularFile(root.resolve("Mix (restored 2).json")));
        assertTrue(Files.isRegularFile(root.resolve("Mix (restored 3).json")));
        assertEquals("the one that was live when deleted keeps its song", 1,
                store.get("Mix (restored 3)", "").get().getSongs().size());
        assertEquals("the new live playlist is untouched", 0, store.get("Mix", "").get().getSongs().size());
        assertEquals("Mix (restored)", store.get("Mix (restored)", "").get().getName());
        assertTrue(store.deleted().isEmpty());
    }

    @Test
    public void restoreClashWithLegacyTxtGetsSuffix() throws Exception
    {
        store.create("Mix", "Rap", FRED);
        PlaylistStore.Deleted gone = store.delete("Mix", "Rap", FRED);
        write(root.resolve("Rap").resolve("Mix.txt"), "https://example.com/a\n");

        PlaylistFile restored = store.restore(gone.getId());
        assertEquals("Mix (restored)", restored.getName());
        assertEquals("https://example.com/a\n", read(root.resolve("Rap").resolve("Mix.txt")));
        assertFalse(Files.exists(root.resolve("Rap").resolve("Mix.json")));
    }

    @Test
    public void restoredSuffixKeepsTheNameWithinTheByteLimit() throws Exception
    {
        String name = repeat("é", 100); // 200 bytes: as long as a name may be
        store.create(name, "", FRED);
        PlaylistStore.Deleted first = store.delete(name, "", FRED);
        clock.advance(Duration.ofSeconds(1));
        store.create(name, "", FRED);
        PlaylistStore.Deleted second = store.delete(name, "", FRED);
        clock.advance(Duration.ofSeconds(1));
        store.create(name, "", FRED);

        String one = store.restore(first.getId()).getName();
        String two = store.restore(second.getId()).getName();

        assertTrue(one, one.endsWith(" (restored)"));
        assertTrue(two, two.endsWith(" (restored 2)"));
        assertTrue(one.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue(two.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue("shortened, not mangled", one.startsWith("éé") && !one.contains("\uFFFD"));
        assertTrue(Files.isRegularFile(root.resolve(one + ".json")));
        assertTrue(Files.isRegularFile(root.resolve(two + ".json")));
        // and they are normal playlists again: the store can address them by name
        assertTrue(store.get(one, "").isPresent());
        assertTrue(store.get(two, "").isPresent());
    }

    @Test
    public void restoreRecreatesMissingFolder() throws Exception
    {
        store.create("Deep", "A/B", FRED);
        PlaylistStore.Deleted gone = store.delete("Deep", "A/B", FRED);
        Files.delete(root.resolve("A").resolve("B"));
        Files.delete(root.resolve("A"));
        assertTrue(store.folders().isEmpty());

        PlaylistFile restored = store.restore(gone.getId());

        assertEquals("Deep", restored.getName());
        assertTrue(Files.isRegularFile(root.resolve("A").resolve("B").resolve("Deep.json")));
        assertEquals(Arrays.asList("A", "A/B"), store.folders());
    }

    @Test
    public void restoreFallsBackToTheTopLevelForAnInvalidFolder() throws Exception
    {
        String[] badFolders = {"../escape", ".deleted", "a//b", "a/", "/abs", "x\\y"};
        for(String bad : badFolders)
        {
            store.create("Lost", "", FRED);
            PlaylistStore.Deleted gone = store.delete("Lost", "", FRED);
            clock.advance(Duration.ofSeconds(1));
            editTrash(gone.getId(), "originalFolder", bad);

            PlaylistFile restored = store.restore(gone.getId());

            assertEquals(bad, "Lost", restored.getName());
            assertTrue(bad, Files.isRegularFile(root.resolve("Lost.json")));
            assertEquals(bad, Arrays.asList(), store.folders());
            Files.delete(root.resolve("Lost.json"));
        }
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("escape")));
        assertFalse(Files.exists(root.resolve("a")));
        assertEquals(new ArrayList<String>(), tmpFilesUnder(tmp.getRoot().toPath()));
    }

    @Test
    public void restoreWithFolderReportsWhereThePlaylistLanded() throws Exception
    {
        store.create("Deep", "A/B", FRED);
        PlaylistStore.Deleted deep = store.delete("Deep", "A/B", FRED);
        Files.delete(root.resolve("A").resolve("B"));
        Files.delete(root.resolve("A"));
        PlaylistStore.Restored back = store.restoreWithFolder(deep.getId());
        assertEquals("Deep", back.getPlaylist().getName());
        assertEquals("A/B", back.getFolder());
        assertTrue(Files.isRegularFile(root.resolve("A").resolve("B").resolve("Deep.json")));

        clock.advance(Duration.ofSeconds(1));
        store.create("Top", "", FRED);
        PlaylistStore.Deleted top = store.delete("Top", "", FRED);
        PlaylistStore.Restored topBack = store.restoreWithFolder(top.getId());
        assertEquals("", topBack.getFolder());
        assertEquals("Top", topBack.getPlaylist().getName());

        clock.advance(Duration.ofSeconds(1));
        PlaylistStore.Deleted again = store.delete("Deep", "A/B", FRED);
        store.create("Deep", "A/B", FRED);
        PlaylistStore.Restored clash = store.restoreWithFolder(again.getId());
        assertEquals("A/B", clash.getFolder());
        assertEquals("Deep (restored)", clash.getPlaylist().getName());
        assertEquals("Deep (restored)", store.get("Deep (restored)", "A/B").get().getName());
        assertTrue(store.deleted().isEmpty());
    }

    @Test
    public void restoreWithFolderNamesTheTopLevelWhenTheFolderIsUnusable() throws Exception
    {
        store.create("Lost", "", FRED);
        PlaylistStore.Deleted gone = store.delete("Lost", "", FRED);
        editTrash(gone.getId(), "originalFolder", "../escape");

        PlaylistStore.Restored restored = store.restoreWithFolder(gone.getId());

        assertEquals("", restored.getFolder());
        assertTrue(Files.isRegularFile(root.resolve("Lost.json")));
        assertThrows(NoSuchElementException.class, () -> store.restoreWithFolder(gone.getId()));
        assertThrows(IllegalArgumentException.class, () -> store.restoreWithFolder("../x.json"));
    }

    @Test
    public void namesWithDotsRestoreToo() throws Exception
    {
        store.create("Wait...", "", FRED);
        PlaylistStore.Deleted gone = store.delete("Wait...", "", FRED);
        assertTrue(gone.getId().contains(".."));
        assertEquals("Wait...", store.restore(gone.getId()).getName());
        assertTrue(Files.isRegularFile(root.resolve("Wait....json")));
    }

    @Test
    public void purgeAfterSevenDays() throws Exception
    {
        store.create("Old", "", FRED);
        PlaylistStore.Deleted old = store.delete("Old", "", FRED);
        clock.advance(Duration.ofDays(3));
        store.create("Recent", "", FRED);
        PlaylistStore.Deleted recent = store.delete("Recent", "", FRED);

        clock.set(T0.plus(Duration.ofDays(6)).plus(Duration.ofHours(23)));
        assertEquals(0, store.purgeExpired());
        assertEquals(2, store.deleted().size());

        clock.set(T0.plus(Duration.ofDays(7))); // exactly seven days is not more than seven
        assertEquals(0, store.purgeExpired());
        assertTrue(Files.exists(trashDir.resolve(old.getId())));

        clock.set(T0.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(1)));
        assertEquals(1, store.purgeExpired());
        assertFalse(Files.exists(trashDir.resolve(old.getId())));
        assertTrue(Files.exists(trashDir.resolve(recent.getId())));
        assertEquals(Arrays.asList(recent.getId()), ids(store.deleted()));
        assertEquals("nothing more is due", 0, store.purgeExpired());

        clock.set(T0.plus(Duration.ofDays(10)).plus(Duration.ofMinutes(1)));
        assertEquals(1, store.purgeExpired());
        assertTrue(store.deleted().isEmpty());
        assertTrue("the folder itself stays", Files.isDirectory(trashDir));
    }

    @Test
    public void purgeWithoutATrashFolderIsANoop() throws Exception
    {
        assertEquals(0, store.purgeExpired());
        assertTrue(store.deleted().isEmpty());
        assertFalse(Files.exists(root));
    }

    @Test
    public void purgeFallsBackToTheFileNameForUnreadableJson() throws Exception
    {
        long oldMillis = T0.minus(Duration.ofDays(8)).toEpochMilli();
        long freshMillis = T0.minus(Duration.ofDays(1)).toEpochMilli();
        write(trashDir.resolve(oldMillis + "-Broken.json"), "{oops");
        write(trashDir.resolve(freshMillis + "-AlsoBroken.json"), "{oops");
        write(trashDir.resolve("notes.json"), "{oops"); // no time anywhere: left alone
        write(trashDir.resolve("readme.txt"), "not a playlist");

        int[] purged = new int[1];
        List<String> warnings = warningsDuring(() -> purged[0] = store.purgeExpired());

        assertEquals(1, purged[0]);
        assertFalse(Files.exists(trashDir.resolve(oldMillis + "-Broken.json")));
        assertTrue(Files.exists(trashDir.resolve(freshMillis + "-AlsoBroken.json")));
        assertTrue(Files.exists(trashDir.resolve("notes.json")));
        assertTrue(Files.exists(trashDir.resolve("readme.txt")));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void purgeUsesTheFileNameWhenTheTimestampIsMissing() throws Exception
    {
        store.create("Old", "", FRED);
        PlaylistStore.Deleted old = store.delete("Old", "", FRED);
        Path file = trashDir.resolve(old.getId());
        JSONObject raw = new JSONObject(read(file));
        raw.remove("deletedAt");
        write(file, raw.toString(2));

        clock.set(T0.plus(Duration.ofDays(8)));
        assertEquals(1, store.purgeExpired());
        assertFalse(Files.exists(file));
    }

    @Test
    public void deletedDaysLeftCountsUpAndStopsAtZero() throws Exception
    {
        store.create("Timer", "", FRED);
        PlaylistStore.Deleted gone = store.delete("Timer", "", FRED);
        assertEquals(7, gone.getDaysLeft());

        clock.set(T0.plus(Duration.ofHours(1)));
        assertEquals("6d23h left rounds up", 7, store.deleted().get(0).getDaysLeft());
        clock.set(T0.plus(Duration.ofDays(1)));
        assertEquals(6, store.deleted().get(0).getDaysLeft());
        clock.set(T0.plus(Duration.ofDays(6)).plus(Duration.ofHours(1)));
        assertEquals("23h left", 1, store.deleted().get(0).getDaysLeft());
        clock.set(T0.plus(Duration.ofDays(7)));
        assertEquals(0, store.deleted().get(0).getDaysLeft());
        clock.set(T0.plus(Duration.ofDays(9)));
        assertEquals("never below zero", 0, store.deleted().get(0).getDaysLeft());
    }

    @Test
    public void deletedListsNewestFirstAndSkipsUnreadableFiles() throws Exception
    {
        store.create("First", "", FRED);
        PlaylistStore.Deleted a = store.delete("First", "", FRED);
        clock.advance(Duration.ofHours(1));
        store.create("Second", "X", FRED);
        PlaylistStore.Deleted b = store.delete("Second", "X", SNOOP);
        clock.advance(Duration.ofHours(1));
        store.create("Third", "", FRED);
        PlaylistStore.Deleted c = store.delete("Third", "", FRED);
        write(trashDir.resolve("12345-Junk.json"), "{oops");
        write(trashDir.resolve("stray.txt"), "hello");
        Files.createDirectories(trashDir.resolve("sub.json"));

        AtomicReference<List<PlaylistStore.Deleted>> listed = new AtomicReference<>();
        List<String> warnings = warningsDuring(() -> listed.set(store.deleted()));

        assertEquals(Arrays.asList(c.getId(), b.getId(), a.getId()), ids(listed.get()));
        assertEquals(Arrays.asList("|Third", "X|Second", "|First"), names(listed.get()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("Junk.json"));
        assertEquals("{oops", read(trashDir.resolve("12345-Junk.json")));
    }

    @Test
    public void deleteFolderSendsEachPlaylist() throws Exception
    {
        store.create("A", "Rock", FRED);
        store.create("B", "Rock/Metal", FRED);
        store.create("C", "Rock/Metal/Deep", FRED);
        store.createFolder("Rock/Empty");
        store.create("Keep", "Pop", FRED);
        store.create("Top", "", FRED);

        int moved = store.deleteFolder("Rock", SNOOP);

        assertEquals(3, moved);
        assertFalse("the folder and all its subfolders are gone", Files.exists(root.resolve("Rock")));
        assertEquals(Arrays.asList("Pop"), store.folders());
        assertTrue(store.get("Keep", "Pop").isPresent());
        assertTrue(store.get("Top", "").isPresent());

        List<PlaylistStore.Deleted> listed = store.deleted();
        assertEquals(3, listed.size());
        assertEquals(Arrays.asList("Rock/Metal/Deep|C", "Rock/Metal|B", "Rock|A"), sorted(names(listed)));
        for(PlaylistStore.Deleted entry : listed)
        {
            assertEquals("snoop", entry.getDeletedBy().getName());
            assertEquals(T0, entry.getDeletedAt());
            assertEquals(7, entry.getDaysLeft());
        }

        // each one can come back to its own folder
        for(PlaylistStore.Deleted entry : listed)
            store.restore(entry.getId());
        assertTrue(Files.isRegularFile(root.resolve("Rock").resolve("A.json")));
        assertTrue(Files.isRegularFile(root.resolve("Rock").resolve("Metal").resolve("B.json")));
        assertTrue(Files.isRegularFile(root.resolve("Rock").resolve("Metal").resolve("Deep").resolve("C.json")));
    }

    @Test
    public void deleteFolderKeepsWhatItCannotParse() throws Exception
    {
        store.create("Good", "Rock", FRED);
        store.create("Fine", "Rock/Sub", FRED);
        write(root.resolve("Rock").resolve("Bad").resolve("Broken.json"), "{oops");
        write(root.resolve("Rock").resolve("Bad").resolve("notes.md"), "keep me");
        store.create("AlsoGood", "Rock/Bad", FRED);

        int[] moved = new int[1];
        List<String> warnings = warningsDuring(() -> moved[0] = store.deleteFolder("Rock", FRED));

        assertEquals(3, moved[0]);
        assertFalse(warnings.isEmpty());
        assertEquals("{oops", read(root.resolve("Rock").resolve("Bad").resolve("Broken.json")));
        assertEquals("keep me", read(root.resolve("Rock").resolve("Bad").resolve("notes.md")));
        assertTrue("the folders holding them stay", Files.isDirectory(root.resolve("Rock")));
        assertFalse(Files.exists(root.resolve("Rock").resolve("Good.json")));
        assertFalse("an emptied sibling goes", Files.exists(root.resolve("Rock").resolve("Sub")));
        assertFalse(Files.exists(root.resolve("Rock").resolve("Bad").resolve("AlsoGood.json")));
        assertEquals(Arrays.asList("Rock/Bad|AlsoGood", "Rock/Sub|Fine", "Rock|Good"), sorted(names(store.deleted())));
    }

    @Test
    public void deleteFolderRefusesLegacyAnywhereInIt() throws Exception
    {
        store.create("Good", "Rock", FRED);
        store.create("Deeper", "Rock/Sub/More", FRED);
        write(root.resolve("Rock").resolve("Sub").resolve("Old.txt"), "https://example.com/a\n");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> store.deleteFolder("Rock", FRED));

        assertEquals(UPGRADING, refused.getMessage());
        assertTrue("nothing was touched", Files.isRegularFile(root.resolve("Rock").resolve("Good.json")));
        assertTrue(Files.isRegularFile(root.resolve("Rock").resolve("Sub").resolve("More").resolve("Deeper.json")));
        assertTrue(Files.isRegularFile(root.resolve("Rock").resolve("Sub").resolve("Old.txt")));
        assertTrue(store.deleted().isEmpty());
        assertFalse(Files.exists(trashDir));
    }

    @Test
    public void deleteFolderRejectsTheRootAndUnknownFolders() throws Exception
    {
        store.create("Top", "", FRED);
        assertThrows(IllegalArgumentException.class, () -> store.deleteFolder("", FRED));
        assertThrows(IllegalArgumentException.class, () -> store.deleteFolder(null, FRED));
        assertThrows(IllegalArgumentException.class, () -> store.deleteFolder(".deleted", FRED));
        assertThrows(IllegalArgumentException.class, () -> store.deleteFolder("../x", FRED));
        assertThrows(IllegalArgumentException.class, () -> store.deleteFolder("a//b", FRED));
        NoSuchElementException unknown = assertThrows(NoSuchElementException.class, () -> store.deleteFolder("Nope", FRED));
        assertEquals("Folder not found", unknown.getMessage());
        assertTrue(Files.isRegularFile(root.resolve("Top.json")));
        assertTrue(store.deleted().isEmpty());
    }

    @Test
    public void deletedFolderCanBeDeletedAgainOnceItsPlaylistsAreRestored() throws Exception
    {
        store.create("A", "Rock", FRED);
        assertEquals(1, store.deleteFolder("Rock", FRED));
        store.restore(store.deleted().get(0).getId());
        assertEquals(1, store.deleteFolder("Rock", FRED));
        assertEquals(1, store.deleted().size());
    }

    @Test
    public void restoreRejectsForgedIds() throws Exception
    {
        store.create("Mix", "", FRED);
        store.create("Other", "", FRED);
        Path absolute = root.resolve("Mix.json").toAbsolutePath();
        Files.createDirectories(trashDir.resolve("dir.json"));
        String[] malformed = {"../x", "../Mix.json", "a/b", "a/b.json", "..\\x", "..\\Mix.json", absolute.toString(),
                "/etc/passwd.json", "C:\\Windows\\x.json", "", "   ", ".", "..", ".json", "x.txt", "no-extension",
                "a\0b.json"};
        for(String id : malformed)
        {
            assertThrows("id " + id.replace("\0", "<NUL>"), IllegalArgumentException.class, () -> store.restore(id));
        }
        assertThrows(IllegalArgumentException.class, () -> store.restore(null));

        // well-formed but not there: a different failure
        NoSuchElementException missing = assertThrows(NoSuchElementException.class, () -> store.restore("123-Nothing.json"));
        assertEquals(DELETED_NOT_FOUND, missing.getMessage());
        assertThrows(NoSuchElementException.class, () -> store.restore("dir.json")); // a directory is no playlist
        assertThrows(NoSuchElementException.class, () -> store.restore("..%2fMix.json")); // just an odd file name

        assertTrue("nothing outside the trash was touched", Files.isRegularFile(root.resolve("Mix.json")));
        assertTrue(Files.isRegularFile(root.resolve("Other.json")));
        assertEquals(1L, store.get("Mix", "").get().getVersion());
        assertTrue(store.deleted().isEmpty());
    }

    @Test
    public void restoreOfAnUnreadableFileIsNotFound() throws Exception
    {
        write(trashDir.resolve("12345-Junk.json"), "{oops");
        List<String> warnings = warningsDuring(() ->
        {
            NoSuchElementException e = assertThrows(NoSuchElementException.class, () -> store.restore("12345-Junk.json"));
            assertEquals(DELETED_NOT_FOUND, e.getMessage());
        });
        assertFalse(warnings.isEmpty());
        assertEquals("{oops", read(trashDir.resolve("12345-Junk.json")));
    }

    @Test
    public void concurrentRestoresOfOneIdHaveOneWinner() throws Exception
    {
        store.create("Once", "F", FRED);
        PlaylistStore.Deleted gone = store.delete("Once", "F", FRED);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for(int i = 0; i < threads; i++)
                results.add(pool.submit(() ->
                {
                    go.await();
                    try
                    {
                        store.restore(gone.getId());
                        return true;
                    }
                    catch(NoSuchElementException e)
                    {
                        return false;
                    }
                }));
            go.countDown();
            int winners = 0;
            for(Future<Boolean> result : results)
                if(result.get(30, TimeUnit.SECONDS))
                    winners++;
            assertEquals(1, winners);
        }
        finally
        {
            pool.shutdownNow();
        }
        assertTrue(store.deleted().isEmpty());
        try(Stream<Path> files = Files.list(root.resolve("F")))
        {
            assertEquals("no (restored) copies", 1, files.count());
        }
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
    }

    @Test
    public void concurrentDeletesOfOneNameKeepEveryFile() throws Exception
    {
        int count = 20;
        for(int i = 0; i < count; i++)
            store.create("Same", "F" + i, FRED);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try
        {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PlaylistStore.Deleted>> results = new ArrayList<>();
            for(int i = 0; i < count; i++)
            {
                String folder = "F" + i;
                results.add(pool.submit(() ->
                {
                    go.await();
                    return store.delete("Same", folder, FRED);
                }));
            }
            go.countDown();
            Set<String> trashIds = new HashSet<>();
            Set<String> folders = new HashSet<>();
            for(Future<PlaylistStore.Deleted> result : results)
            {
                PlaylistStore.Deleted entry = result.get(30, TimeUnit.SECONDS);
                assertTrue("distinct trash file " + entry.getId(), trashIds.add(entry.getId()));
                folders.add(entry.getOriginalFolder());
            }
            assertEquals(count, folders.size());
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(count, store.deleted().size());
        assertTrue(store.tree().isEmpty());
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
    }

    @Test
    public void concurrentRestoresOfOneNameGetDistinctNames() throws Exception
    {
        int count = 20;
        for(int i = 0; i < count; i++)
        {
            store.create("Same", "", FRED);
            store.delete("Same", "", FRED);
        }
        assertEquals(count, store.deleted().size());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try
        {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PlaylistFile>> results = new ArrayList<>();
            for(PlaylistStore.Deleted entry : store.deleted())
                results.add(pool.submit(() ->
                {
                    go.await();
                    return store.restore(entry.getId());
                }));
            go.countDown();
            Set<String> restoredNames = new HashSet<>();
            for(Future<PlaylistFile> result : results)
                assertTrue("distinct name", restoredNames.add(result.get(30, TimeUnit.SECONDS).getName()));
            assertTrue(restoredNames.contains("Same"));
            assertTrue(restoredNames.contains("Same (restored)"));
            assertTrue(restoredNames.contains("Same (restored " + (count - 1) + ")"));
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(count, store.tree().size());
        assertTrue(store.deleted().isEmpty());
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
    }

    @Test
    public void deletedPlaylistsStayHiddenFromListings() throws Exception
    {
        store.create("A", "Rock", FRED);
        store.delete("A", "Rock", FRED);
        assertTrue(store.tree().isEmpty());
        assertFalse(store.folders().contains(".deleted"));
        assertEquals(Arrays.asList("Rock"), store.folders());
        assertFalse(store.get("A", "Rock").isPresent());
        assertThrows(IllegalArgumentException.class, () -> store.get("A", ".deleted"));
    }
}
