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
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class PlaylistStoreTest
{
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final String TAKEN = "A playlist with that name already exists";
    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");
    private static final Person FRED = new Person("233362518045753345", "fred");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private TestClock clock;
    private PlaylistStore store;

    @Before
    public void setUp() throws Exception
    {
        // not created: the store makes the folder when it first writes
        root = tmp.getRoot().toPath().resolve("Playlists");
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

    private static List<String> tmpFilesUnder(Path dir) throws Exception
    {
        try(Stream<Path> files = Files.walk(dir))
        {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).map(Path::toString)
                    .collect(Collectors.toList());
        }
    }

    private static List<String> names(List<PlaylistStore.Entry> entries)
    {
        List<String> names = new ArrayList<>();
        for(PlaylistStore.Entry entry : entries)
            names.add(entry.getFolder() + "|" + entry.getName());
        return names;
    }

    private static Song song(String title)
    {
        Song song = new Song();
        song.setId(PlaylistFile.newSongId(new Random()));
        song.setTitle(title);
        song.setUri("https://example.com/" + title);
        return song;
    }

    private static boolean onWindows()
    {
        return System.getProperty("os.name").startsWith("Windows");
    }

    @Test
    public void createGetRenameMove() throws Exception
    {
        PlaylistFile created = store.create("The Chronic", "Dr. Dre", FRED);

        Path chronic = root.resolve("Dr. Dre").resolve("The Chronic.json");
        assertEquals(chronic, store.pathFor("The Chronic", "Dr. Dre"));
        assertTrue(Files.isRegularFile(chronic));
        assertEquals("The Chronic", created.getName());
        assertEquals(1, created.getVersion());
        assertEquals("233362518045753345", created.getCreatedBy().getId());
        assertEquals("fred", created.getCreatedBy().getName());
        assertEquals(T0, created.getCreatedAt());
        assertEquals(T0, created.getUpdatedAt());
        assertFalse(created.isShuffle());
        assertTrue(created.getSongs().isEmpty());
        assertFalse(created.isLegacy());

        PlaylistFile loaded = store.get("The Chronic", "Dr. Dre").get();
        assertEquals(1, loaded.getVersion());
        assertEquals("The Chronic", loaded.getName());
        assertEquals(T0, loaded.getUpdatedAt());

        // an old playlist that has not been converted yet is listed beside it
        write(root.resolve("Dr. Dre").resolve("Old Mix.txt"), "https://example.com/a\nhttps://example.com/b\n");
        List<PlaylistStore.Entry> tree = store.tree();
        assertEquals(2, tree.size());
        PlaylistStore.Entry legacy = tree.get(0);
        assertEquals("Old Mix", legacy.getName());
        assertEquals("Dr. Dre", legacy.getFolder());
        assertTrue(legacy.isLegacy());
        assertEquals(2, legacy.getSongCount());
        PlaylistStore.Entry json = tree.get(1);
        assertEquals("The Chronic", json.getName());
        assertEquals("Dr. Dre", json.getFolder());
        assertFalse(json.isLegacy());
        assertEquals(0, json.getSongCount());
        assertEquals("fred", json.getCreatedBy().getName());
        assertEquals(T0, json.getUpdatedAt());

        // rename keeps the folder, bumps the version and rewrites the name inside the file
        Instant t1 = T0.plusSeconds(60);
        clock.set(t1);
        PlaylistFile renamed = store.rename("The Chronic", "Dr. Dre", "2001");
        Path twoThousandOne = root.resolve("Dr. Dre").resolve("2001.json");
        assertEquals("2001", renamed.getName());
        assertEquals(2, renamed.getVersion());
        assertEquals(t1, renamed.getUpdatedAt());
        assertEquals(T0, renamed.getCreatedAt());
        assertFalse(Files.exists(chronic));
        assertTrue(Files.isRegularFile(twoThousandOne));
        assertEquals("2001", new JSONObject(read(twoThousandOne)).getString("name"));
        assertFalse(store.get("The Chronic", "Dr. Dre").isPresent());
        assertEquals(2, store.get("2001", "Dr. Dre").get().getVersion());

        // move creates the target folder (and its parents) and keeps the name
        Instant t2 = T0.plusSeconds(120);
        clock.set(t2);
        PlaylistFile moved = store.move("2001", "Dr. Dre", "Hip Hop/West");
        Path target = root.resolve("Hip Hop").resolve("West").resolve("2001.json");
        assertEquals(3, moved.getVersion());
        assertEquals(t2, moved.getUpdatedAt());
        assertEquals("2001", moved.getName());
        assertFalse(Files.exists(twoThousandOne));
        assertTrue(Files.isRegularFile(target));
        assertFalse(store.get("2001", "Dr. Dre").isPresent());
        assertEquals(3, store.get("2001", "Hip Hop/West").get().getVersion());

        // and back to the top level
        store.move("2001", "Hip Hop/West", "");
        assertTrue(Files.isRegularFile(root.resolve("2001.json")));

        assertEquals(Arrays.asList("Dr. Dre", "Hip Hop", "Hip Hop/West"), store.folders());
        assertEquals(Arrays.asList("|2001", "Dr. Dre|Old Mix"), names(store.tree()));
    }

    @Test
    public void createStampsOneInstant() throws Exception
    {
        // a real clock moves between calls: createdAt and updatedAt must still come from one reading
        AtomicLong reads = new AtomicLong();
        Clock advancing = new Clock()
        {
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
                return T0.plusMillis(reads.incrementAndGet());
            }
        };
        PlaylistStore ticking = new PlaylistStore(root, advancing);

        PlaylistFile created = ticking.create("Tick", "", FRED);
        assertEquals(created.getCreatedAt(), created.getUpdatedAt());
        PlaylistFile loaded = ticking.get("Tick", "").get();
        assertEquals(loaded.getCreatedAt(), loaded.getUpdatedAt());
        assertEquals(created.getCreatedAt(), loaded.getCreatedAt());

        // later changes are stamped later than the creation
        PlaylistFile changed = ticking.update("Tick", "", f -> f.setShuffle(true));
        assertEquals(created.getCreatedAt(), changed.getCreatedAt());
        assertTrue(changed.getUpdatedAt().isAfter(changed.getCreatedAt()));
    }

    @Test
    public void rejectsBadNames() throws Exception
    {
        String[] badNames = {"../x", "a/b", "a\\b", ".deleted", ".legacy-txt", ".DELETED", ".Legacy-Txt", "", "   ",
            ".", "..", "a\0b", null, repeat("a", 201), repeat("é", 101)};
        for(String bad : badNames)
        {
            String shown = String.valueOf(bad);
            assertThrows("pathFor " + shown, IllegalArgumentException.class, () -> store.pathFor(bad, ""));
            assertThrows("create " + shown, IllegalArgumentException.class, () -> store.create(bad, "", FRED));
            assertThrows("get " + shown, IllegalArgumentException.class, () -> store.get(bad, ""));
        }
        String[] badFolders = {"../x", "a/../b", "/abs", "a//b", "a/", ".deleted", ".legacy-txt/x", "x/.deleted", "a\\b",
            "   ", "a/ /b", "a/\0", ".", "./a", "a/.."};
        for(String bad : badFolders)
        {
            assertThrows("pathFor folder " + bad, IllegalArgumentException.class, () -> store.pathFor("ok", bad));
            assertThrows("create folder " + bad, IllegalArgumentException.class, () -> store.create("ok", bad, FRED));
            assertThrows("createFolder " + bad, IllegalArgumentException.class, () -> store.createFolder(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> store.createFolder(""));
        assertThrows(IllegalArgumentException.class, () -> store.createFolder(null));
        assertThrows(IllegalArgumentException.class, () -> store.rename("a", "", "b/c"));
        assertThrows(IllegalArgumentException.class, () -> store.move("a", "", "../x"));
        // a refused name leaves nothing behind
        assertFalse(Files.exists(root));

        // the limit is on UTF-8 bytes, not characters
        assertEquals(root.resolve(repeat("a", 200) + ".json"), store.pathFor(repeat("a", 200), ""));
        assertEquals(root.resolve(repeat("é", 100) + ".json"), store.pathFor(repeat("é", 100), ""));

        // accepted on every OS
        String friendly = "Ke$ha – Tik Tok 🎵 (feat. P!nk)";
        assertEquals(friendly, store.create(friendly, "", FRED).getName());
        assertEquals(friendly, store.get(friendly, "").get().getName());
        assertEquals(friendly, store.tree().get(0).getName());

        // quotes are legal on Linux (production) but not in Windows file names
        String quoted = "Ke$ha \"Tik\" 🎵";
        if(onWindows())
            assertThrows(IllegalArgumentException.class, () -> store.create(quoted, "", FRED));
        else
        {
            assertEquals(quoted, store.create(quoted, "", FRED).getName());
            assertEquals(quoted, store.get(quoted, "").get().getName());
        }
    }

    @Test
    public void reservedFoldersHidden() throws Exception
    {
        store.create("Visible", "", FRED);
        store.create("Nested", "Rock", FRED);
        String json = store.get("Visible", "").get().toJson();
        write(root.resolve(".deleted").resolve("20261013-Gone.json"), json);
        write(root.resolve(".deleted").resolve("sub").resolve("Deeper.json"), json);
        write(root.resolve(".legacy-txt").resolve("Old.txt"), "https://example.com/a\n");
        write(root.resolve(".legacy-txt").resolve("Rap").resolve("Kanye.txt"), "https://example.com/b\n");
        write(root.resolve("Rock").resolve(".deleted").resolve("Odd.json"), json);

        assertEquals(Arrays.asList("|Visible", "Rock|Nested"), names(store.tree()));
        assertEquals(Arrays.asList("Rock"), store.folders());
    }

    @Test
    public void likedFolderIsHiddenAndReserved() throws Exception
    {
        store.create("Visible", "", FRED);
        store.create("Nested", "Rock", FRED);
        String json = store.get("Visible", "").get().toJson();
        write(root.resolve(".liked").resolve("233362518045753345.json"), json);
        write(root.resolve(".liked").resolve("sub").resolve("Deeper.json"), json);
        write(root.resolve(".liked").resolve("Old.txt"), "https://example.com/a\n");
        write(root.resolve(".liked").resolve("1-Ancient.json"), json); // named like a trash file from long ago
        write(root.resolve("Rock").resolve(".liked").resolve("Odd.json"), json);

        assertEquals(Arrays.asList("|Visible", "Rock|Nested"), names(store.tree()));
        assertEquals(Arrays.asList("Rock"), store.folders());
        assertFalse(store.find(".liked/233362518045753345").isPresent());
        assertTrue("never offered to the converter", store.legacyEntries().isEmpty());
        assertEquals(0, store.moveLeftoverTxts());
        assertEquals("nor purged", 0, store.purgeExpired());
        assertTrue(Files.isRegularFile(root.resolve(".liked").resolve("1-Ancient.json")));

        for(String reserved : new String[]{".liked", ".LIKED", ".Liked"})
        {
            assertThrows("pathFor " + reserved, IllegalArgumentException.class, () -> store.pathFor(reserved, ""));
            assertThrows("create " + reserved, IllegalArgumentException.class, () -> store.create(reserved, "", FRED));
            assertThrows("get " + reserved, IllegalArgumentException.class, () -> store.get(reserved, ""));
            assertThrows("folder " + reserved, IllegalArgumentException.class, () -> store.pathFor("ok", reserved));
            assertThrows("create in " + reserved, IllegalArgumentException.class, () -> store.create("ok", reserved, FRED));
            assertThrows("createFolder " + reserved, IllegalArgumentException.class, () -> store.createFolder(reserved));
            assertThrows("deleteFolder " + reserved, IllegalArgumentException.class, () -> store.deleteFolder(reserved, FRED));
            assertThrows("rename to " + reserved, IllegalArgumentException.class, () -> store.rename("Visible", "", reserved));
            assertThrows("move to " + reserved, IllegalArgumentException.class, () -> store.move("Visible", "", reserved));
        }
        assertThrows(IllegalArgumentException.class, () -> store.pathFor("ok", ".liked/x"));
        assertThrows(IllegalArgumentException.class, () -> store.pathFor("ok", "x/.liked"));
        assertEquals("nothing moved", Arrays.asList("|Visible", "Rock|Nested"), names(store.tree()));
    }

    @Test
    public void writesAreAtomic() throws Exception
    {
        store.create("A", "F", FRED);
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));

        // a hand-placed file is replaced in place by the next change
        Path file = store.pathFor("A", "F");
        write(file, new JSONObject().put("format", 2).put("name", "A").put("version", 5).put("shuffle", false)
                .put("songs", new JSONArray()).toString());
        clock.set(T0.plusSeconds(10));
        PlaylistFile updated = store.update("A", "F", f -> f.setShuffle(true));
        assertEquals(6, updated.getVersion());
        assertTrue(updated.isShuffle());
        assertEquals(T0.plusSeconds(10), updated.getUpdatedAt());
        JSONObject onDisk = new JSONObject(read(file));
        assertEquals(6, onDisk.getInt("version"));
        assertTrue(onDisk.getBoolean("shuffle"));
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));

        // a change that fails leaves the file as it was
        String before = read(file);
        assertThrows(IllegalStateException.class, () -> store.update("A", "F", f ->
        {
            f.setShuffle(false);
            f.getSongs().add(song("half done"));
            throw new IllegalStateException("nope");
        }));
        assertEquals(before, read(file));
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
    }

    @Test
    public void failedWriteLeavesNoTempFile() throws Exception
    {
        // the target is a folder with something in it, so the final move cannot succeed
        Path target = root.resolve("F").resolve("Blocked.json");
        Files.createDirectories(target);
        Files.write(target.resolve("inside.txt"), new byte[]{1});
        PlaylistFile file = new PlaylistFile();
        file.setName("Blocked");

        assertThrows(UncheckedIOException.class, () -> store.write(target, file));
        assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
        assertTrue(Files.isDirectory(target));
    }

    @Test
    public void corruptFileSkipped() throws Exception
    {
        store.create("Good", "Rap", FRED);
        Path bad = root.resolve("Rap").resolve("Bad.json");
        write(bad, "{oops");
        write(root.resolve("Wrong.json"), "{\"format\": 1, \"songs\": []}");

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PlaylistStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected warnings out of the test output
        logger.addAppender(appender);
        try
        {
            assertEquals(Arrays.asList("Rap|Good"), names(store.tree()));
            assertEquals("one warning per corrupt file", 2, appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count());
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Bad.json")));
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("Wrong.json")));

            assertFalse(store.get("Bad", "Rap").isPresent());

            // the file stays on disk, untouched, and is never overwritten
            assertEquals("{oops", read(bad));
            assertEquals("Rap", store.folders().get(0));
            IllegalStateException clash = assertThrows(IllegalStateException.class, () -> store.create("Bad", "Rap", FRED));
            assertEquals(TAKEN, clash.getMessage());
            assertThrows(NoSuchElementException.class, () -> store.rename("Bad", "Rap", "Worse"));
            assertThrows(NoSuchElementException.class, () -> store.move("Bad", "Rap", ""));
            assertThrows(NoSuchElementException.class, () -> store.update("Bad", "Rap", f -> f.setShuffle(true)));
            assertEquals("{oops", read(bad));
        }
        finally
        {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
        }
    }

    @Test
    public void jsonWinsOverLegacyText() throws Exception
    {
        store.create("Mix", "", FRED);
        store.update("Mix", "", f -> f.getSongs().add(song("one")));
        write(root.resolve("Mix.txt"), "https://example.com/a\nhttps://example.com/b\nhttps://example.com/c\n");

        List<PlaylistStore.Entry> tree = store.tree();
        assertEquals(1, tree.size());
        assertFalse(tree.get(0).isLegacy());
        assertEquals(1, tree.get(0).getSongCount());
        PlaylistFile loaded = store.get("Mix", "").get();
        assertFalse(loaded.isLegacy());
        assertEquals(1, loaded.getSongs().size());
    }

    @Test
    public void legacyTextIsAReadOnlyView() throws Exception
    {
        Path txt = root.resolve("Rap").resolve("Old.txt");
        write(txt, "# my list\n//shuffle\n  https://example.com/a  \n\n   \nytsearch: café beat\n// another comment\r\nhttps://example.com/b\n");
        Files.setLastModifiedTime(txt, FileTime.from(Instant.parse("2026-01-02T03:04:05Z")));

        PlaylistFile view = store.get("Old", "Rap").get();
        assertTrue(view.isLegacy());
        assertEquals("Old", view.getName());
        assertTrue(view.isShuffle());
        assertNull(view.getCreatedBy());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), view.getUpdatedAt());
        List<String> lines = Arrays.asList("https://example.com/a", "ytsearch: café beat", "https://example.com/b");
        assertEquals(3, view.getSongs().size());
        Set<String> ids = new HashSet<>();
        for(int i = 0; i < 3; i++)
        {
            Song song = view.getSongs().get(i);
            assertEquals("in file order, not shuffled", lines.get(i), song.getUri());
            assertEquals(lines.get(i), song.getTitle());
            assertTrue(song.isUnresolved());
            assertTrue(song.getId().matches("[a-z0-9]{8}"));
            ids.add(song.getId());
        }
        assertEquals(3, ids.size());

        PlaylistStore.Entry entry = store.tree().get(0);
        assertEquals("Old", entry.getName());
        assertEquals("Rap", entry.getFolder());
        assertTrue(entry.isLegacy());
        assertEquals(3, entry.getSongCount());
        assertEquals(0, entry.getDurationMs());
        assertNull(entry.getCreatedBy());
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), entry.getUpdatedAt());

        // a comment-only or empty file is an empty playlist
        write(root.resolve("Empty.txt"), "# nothing here\n");
        assertTrue(store.get("Empty", "").get().getSongs().isEmpty());
        assertFalse(store.get("Empty", "").get().isShuffle());

        // every long line is cut, as everything else in a playlist file
        write(root.resolve("Long.txt"), repeat("x", 500) + "\n");
        assertEquals(PlaylistFile.MAX_TEXT, store.get("Long", "").get().getSongs().get(0).getUri().length());
    }

    @Test
    public void legacyMutationsRefused() throws Exception
    {
        Path txt = root.resolve("Rap").resolve("Old.txt");
        write(txt, "https://example.com/a\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.rename("Old", "Rap", "New"));
        assertEquals(UPGRADING, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.move("Old", "Rap", "Other"));
        assertEquals(UPGRADING, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.update("Old", "Rap", f -> f.setShuffle(true)));
        assertEquals(UPGRADING, e.getMessage());

        assertEquals("https://example.com/a\n", read(txt));
        try(Stream<Path> files = Files.list(root.resolve("Rap")))
        {
            assertEquals(1, files.count());
        }
        assertFalse(Files.exists(root.resolve("Other")));
    }

    @Test
    public void takenAndMissingNames() throws Exception
    {
        store.create("A", "", FRED);
        store.create("B", "", FRED);
        store.create("A", "Sub", FRED);
        write(root.resolve("Old.txt"), "https://example.com/a\n");
        String before = read(store.pathFor("A", ""));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.create("A", "", FRED));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.create("Old", "", FRED));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.rename("A", "", "B"));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.rename("A", "", "Old"));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.rename("A", "", "A"));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.move("A", "", "Sub"));
        assertEquals(TAKEN, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.move("A", "", ""));
        assertEquals(TAKEN, e.getMessage());
        assertEquals(before, read(store.pathFor("A", "")));
        assertEquals(1, store.get("A", "").get().getVersion());
        assertEquals(1, store.get("A", "Sub").get().getVersion());

        NoSuchElementException missing = assertThrows(NoSuchElementException.class, () -> store.rename("Nope", "", "X"));
        assertEquals("Playlist not found", missing.getMessage());
        missing = assertThrows(NoSuchElementException.class, () -> store.move("Nope", "", "X"));
        assertEquals("Playlist not found", missing.getMessage());
        assertThrows(NoSuchElementException.class, () -> store.rename("Nope", "No/Such/Folder", "X"));
        assertThrows(NoSuchElementException.class, () -> store.update("Nope", "", f -> f.setShuffle(true)));
        assertThrows(NoSuchElementException.class, () -> store.move("Nope", "", "Fresh"));
        assertFalse("a failed move makes no folder", Files.exists(root.resolve("Fresh")));
        assertEquals(Optional.empty(), store.get("Nope", ""));
        assertEquals(Optional.empty(), store.get("A", "No/Such/Folder"));
    }

    @Test
    public void treeAndFoldersAreSortedAndRecursive() throws Exception
    {
        store.create("b", "", FRED);
        store.create("A", "", FRED);
        store.create("C", "", FRED);
        store.create("z", "Beta/Sub", FRED);
        store.create("q", "alpha", FRED);
        store.create("Y", "Beta", FRED);
        store.createFolder("Empty/Deep");
        store.createFolder("Empty/Deep"); // already there: fine

        assertEquals(Arrays.asList("|A", "|b", "|C", "alpha|q", "Beta|Y", "Beta/Sub|z"), names(store.tree()));
        assertEquals(Arrays.asList("alpha", "Beta", "Beta/Sub", "Empty", "Empty/Deep"), store.folders());
    }

    @Test
    public void missingRootReadsAsEmptyAndIsCreatedOnFirstWrite() throws Exception
    {
        assertTrue(store.tree().isEmpty());
        assertTrue(store.folders().isEmpty());
        assertFalse(store.get("X", "").isPresent());
        assertFalse("reading does not create the folder", Files.exists(root));

        store.create("X", "", FRED);
        assertTrue(Files.isRegularFile(root.resolve("X.json")));
    }

    @Test
    public void strayFilesAreNotListed() throws Exception
    {
        store.create("Real", "F", FRED);
        String json = store.get("Real", "F").get().toJson();
        write(root.resolve("F").resolve(".pl-123.tmp"), json);
        write(root.resolve("F").resolve("Half.json.tmp"), json);
        write(root.resolve("F").resolve("Half.txt.tmp"), "https://example.com/a\n");
        write(root.resolve("F").resolve("notes.md"), "hi");
        write(root.resolve("F").resolve(".json"), json);
        write(root.resolve("F").resolve(".txt"), "https://example.com/a\n");
        Files.createDirectories(root.resolve("F").resolve("folder.json"));

        assertEquals(Arrays.asList("F|Real"), names(store.tree()));
        assertEquals(Arrays.asList("F", "F/folder.json"), store.folders());
    }

    @Test
    public void filesAreUtf8() throws Exception
    {
        store.create("Café", "", FRED);
        store.update("Café", "", f -> f.getSongs().add(song("Beyoncé 🎵")));

        byte[] bytes = Files.readAllBytes(store.pathFor("Café", ""));
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("Beyoncé 🎵"));
        assertEquals("Beyoncé 🎵", store.get("Café", "").get().getSongs().get(0).getTitle());

        write(root.resolve("Legacy café.txt"), "Beyoncé – Halo\n");
        assertEquals("Beyoncé – Halo", store.get("Legacy café", "").get().getSongs().get(0).getTitle());
    }

    @Test
    public void nameInsideTheFileDoesNotMatter() throws Exception
    {
        // the file name is the identity, even if someone edited the name inside
        write(root.resolve("Real.json"), new JSONObject().put("format", 2).put("name", "Something else").toString());
        assertEquals("Real", store.get("Real", "").get().getName());
        assertEquals("Real", store.tree().get(0).getName());
    }

    @Test
    public void entryTotalsTheSongDurations() throws Exception
    {
        store.create("Mix", "", FRED);
        store.update("Mix", "", f ->
        {
            Song a = song("a");
            a.setDurationMs(1000);
            Song b = song("b");
            b.setDurationMs(2500);
            f.getSongs().add(a);
            f.getSongs().add(b);
        });
        PlaylistStore.Entry entry = store.tree().get(0);
        assertEquals(2, entry.getSongCount());
        assertEquals(3500, entry.getDurationMs());
    }

    @Test
    public void oppositeRenamesDoNotDeadlock() throws Exception
    {
        store.create("A", "F", FRED);
        store.create("B", "F", FRED);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            CountDownLatch go = new CountDownLatch(1);
            Callable<Integer> forward = () -> refusedRenames(go, "A", "B");
            Callable<Integer> backward = () -> refusedRenames(go, "B", "A");
            Future<Integer> first = pool.submit(forward);
            Future<Integer> second = pool.submit(backward);
            go.countDown();
            // both names stay taken, so every rename is refused - after taking both locks
            assertEquals(500, (int) first.get(30, TimeUnit.SECONDS));
            assertEquals(500, (int) second.get(30, TimeUnit.SECONDS));
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    private int refusedRenames(CountDownLatch go, String from, String to) throws Exception
    {
        go.await();
        int refused = 0;
        for(int i = 0; i < 500; i++)
        {
            try
            {
                store.rename(from, "F", to);
            }
            catch(IllegalStateException e)
            {
                refused++;
            }
        }
        return refused;
    }

    @Test
    public void concurrentCreatesOfOneNameHaveOneWinner() throws Exception
    {
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
                        store.create("Same", "F", FRED);
                        return true;
                    }
                    catch(IllegalStateException e)
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
            assertEquals(1, store.get("Same", "F").get().getVersion());
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void updatesAreSerialized() throws Exception
    {
        store.create("Busy", "", FRED);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            List<Future<?>> results = new ArrayList<>();
            for(int i = 0; i < threads; i++)
            {
                int n = i;
                results.add(pool.submit(() ->
                {
                    for(int j = 0; j < 10; j++)
                        store.update("Busy", "", f -> f.getSongs().add(song("t" + n)));
                }));
            }
            for(Future<?> result : results)
                result.get(30, TimeUnit.SECONDS);
            PlaylistFile loaded = store.get("Busy", "").get();
            assertEquals("no lost updates", 80, loaded.getSongs().size());
            assertEquals(81, loaded.getVersion());
            assertEquals(new ArrayList<String>(), tmpFilesUnder(root));
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void listingsDuringWritesSeeWholeFiles() throws Exception
    {
        // reads take no lock; the atomic move means they never see a half-written file
        store.create("A", "", FRED);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> writer = pool.submit(() ->
            {
                for(int i = 0; i < 100; i++)
                    store.update("A", "", f -> f.setShuffle(!f.isShuffle()));
            });
            for(int i = 0; i < 100; i++)
            {
                assertEquals(1, store.tree().size());
                assertTrue(store.get("A", "").isPresent());
            }
            writer.get(30, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(101, store.get("A", "").get().getVersion());
    }
}
