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
import com.jagrosh.jmusicbot.playlist.ImportJobs.Progress;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Batches;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class ImportJobsTest
{
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final String LINK = "https://www.youtube.com/playlist?list=PLroadtrip";
    private static final String HTTP_ONLY = "Import links must start with http:// or https://";
    private static final String ONE_PER_USER = "You already have an import running";
    private static final String TOO_MANY = "Too many imports are running — try again in a minute";
    private static final String FULL = "Playlist is full (2000 songs)";
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final Person ALICE = new Person("111", "Alice");
    private static final Person BOB = new Person("222", "Bob");
    private static final Person CARL = new Person("333", "Carl");
    private static final Person DAN = new Person("444", "Dan");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private PlaylistStore store;
    private FakeResolver resolver;
    private final List<Progress> progress = Collections.synchronizedList(new ArrayList<>());
    private final Executor direct = Runnable::run;
    private ImportJobs jobs;
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> log;
    private boolean additive;

    @Before
    public void setUp()
    {
        // not created: the store makes the folder when it first writes
        root = tmp.getRoot().toPath().resolve("Playlists");
        store = new PlaylistStore(root, Clock.fixed(NOW, ZoneOffset.UTC));
        resolver = new FakeResolver();
        jobs = new ImportJobs(store, resolver, direct, progress::add);

        // collect the jobs' log lines instead of printing them
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ImportJobs.class);
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

    /** Runs a script instead of loading anything: it hands out batches through the callback and answers like a resolver. */
    private static final class FakeResolver implements TrackResolver
    {
        final List<String> links = Collections.synchronizedList(new ArrayList<>());
        volatile BiFunction<String, Batches, Resolved> script = (link, batches) -> Resolved.failed("Not scripted");

        @Override
        public Resolved resolve(String lineOrLink)
        {
            return resolve(lineOrLink, (songs, total) -> { });
        }

        @Override
        public Resolved resolve(String lineOrLink, Batches onBatch)
        {
            links.add(lineOrLink);
            return script.apply(lineOrLink, onBatch);
        }

        /** Gives numbered songs in batches of the sizes given, each reporting {@code total}, then answers with the title. */
        void gives(String title, int total, int... batchSizes)
        {
            script = (link, batches) ->
            {
                List<Song> all = new ArrayList<>();
                for(int size : batchSizes)
                {
                    List<Song> batch = songs(all.size() + 1, size);
                    all.addAll(batch);
                    batches.batch(batch, total);
                }
                return Resolved.ok(all, title);
            };
        }
    }

    private static Song song(String uri, String title)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle(title);
        song.setAuthor("Artist");
        song.setDurationMs(1000);
        return song;
    }

    /** {@code count} songs titled "Song {from}" and up. */
    private static List<Song> songs(int from, int count)
    {
        List<Song> list = new ArrayList<>();
        for(int i = from; i < from + count; i++)
            list.add(song("https://example.test/song" + i, "Song " + i));
        return list;
    }

    private static List<String> titles(PlaylistFile file)
    {
        return file.getSongs().stream().map(Song::getTitle).collect(Collectors.toList());
    }

    private PlaylistFile playlist(String name, String folder)
    {
        return store.get(name, folder).orElseThrow(() -> new AssertionError("No playlist " + folder + "/" + name));
    }

    private Progress last()
    {
        return progress.get(progress.size() - 1);
    }

    private long finishedCount()
    {
        return progress.stream().filter(Progress::isFinished).count();
    }

    private static IllegalStateException refused(Runnable start)
    {
        return assertThrows(IllegalStateException.class, start::run);
    }

    // ---- what an import makes ----

    @Test
    public void importCreatesPlaylistNamedAfterSource()
    {
        resolver.gives("Road Trip", 3, 2, 1);

        String jobId = jobs.start(LINK, null, "Mixes", null, ALICE);

        assertTrue("8 characters of [a-z0-9]: " + jobId, jobId.matches("[a-z0-9]{8}"));
        assertEquals(Collections.singletonList(LINK), resolver.links);
        PlaylistFile file = playlist("Road Trip", "Mixes");
        assertEquals(Arrays.asList("Song 1", "Song 2", "Song 3"), titles(file));
        assertEquals("https://example.test/song2", file.getSongs().get(1).getUri());
        assertEquals("Artist", file.getSongs().get(1).getAuthor());
        assertEquals("Alice", file.getSongs().get(0).getAddedBy().getName());
        assertEquals(NOW, file.getSongs().get(0).getAddedAt());
        assertEquals("Alice", file.getCreatedBy().getName());
        assertEquals(1, store.tree().size());

        // a name-less import holds its songs until the title is known, so the playlist exists only at the end
        assertEquals(3, progress.size());
        assertEquals(jobId, progress.get(0).getJobId());
        assertNull(progress.get(0).getPlaylistName());
        assertFalse(progress.get(0).isFinished());
        assertEquals(2, progress.get(0).getDone());
        assertEquals(3, progress.get(0).getTotal());
        assertEquals(3, progress.get(1).getDone());
        assertNull(progress.get(1).getPlaylistName());
        Progress end = last();
        assertTrue(end.isFinished());
        assertNull(end.getError());
        assertEquals(3, end.getDone());
        assertEquals(3, end.getTotal());
        assertEquals(0, end.getFailedCount());
        assertEquals("Road Trip", end.getPlaylistName());
        assertEquals("Mixes", end.getFolder());
    }

    @Test
    public void importWithANameFillsTheNewPlaylistBatchByBatch()
    {
        resolver.script = (link, batches) ->
        {
            assertTrue("nothing exists before the first batch", store.tree().isEmpty());
            batches.batch(songs(1, 2), 5);
            assertEquals("the first batch is already in the playlist", 2, playlist("My Mix", "").getSongs().size());
            batches.batch(Collections.<Song>emptyList(), 5);
            batches.batch(songs(3, 3), 5);
            return Resolved.ok(songs(1, 5), "The Source Title");
        };

        jobs.start(LINK, "My Mix", null, null, BOB);

        assertEquals(5, playlist("My Mix", "").getSongs().size());
        assertEquals("the source title is not used", 1, store.tree().size());
        assertEquals(4, progress.size());
        for(Progress each : progress)
            assertEquals("My Mix", each.getPlaylistName());
        assertEquals(Arrays.asList(2, 2, 5, 5), progress.stream().map(Progress::getDone).collect(Collectors.toList()));
        assertTrue(last().isFinished());
    }

    @Test
    public void importIntoExistingAppends()
    {
        store.create("Favourites", "Rock", ALICE);
        store.addSongs("Favourites", "Rock", Arrays.asList(song("https://example.test/old1", "Old 1"),
                song("https://example.test/old2", "Old 2")), null, ALICE);
        resolver.gives("Some Album", 3, 1, 2);

        jobs.start(LINK, "Ignored Name", "Rock", "Favourites", BOB);

        assertEquals(Arrays.asList("Old 1", "Old 2", "Song 1", "Song 2", "Song 3"), titles(playlist("Favourites", "Rock")));
        assertEquals("Bob", playlist("Favourites", "Rock").getSongs().get(4).getAddedBy().getName());
        assertEquals("Alice", playlist("Favourites", "Rock").getSongs().get(0).getAddedBy().getName());
        assertEquals("no playlist was made", 1, store.tree().size());
        assertEquals("Favourites", progress.get(0).getPlaylistName());
        assertEquals("Rock", progress.get(0).getFolder());
        assertEquals("only the imported songs are counted", 1, progress.get(0).getDone());
        assertEquals(3, last().getDone());
        assertNull(last().getError());
    }

    @Test
    public void unresolvedSongsAreCountedAsFailed()
    {
        resolver.script = (link, batches) ->
        {
            List<Song> batch = new ArrayList<>(songs(1, 2));
            batch.add(TrackResolver.unresolvedSong("Lost one", "ytsearch:Lost one"));
            batch.add(TrackResolver.unresolvedSong("Lost two", "ytsearch:Lost two"));
            batches.batch(batch, 5);
            batches.batch(songs(5, 1), 5);
            return Resolved.ok(songs(1, 5), "Mixed");
        };

        jobs.start(LINK, "Mixed", null, null, ALICE);

        assertEquals(2, progress.get(0).getFailedCount());
        assertEquals(2, last().getFailedCount());
        assertEquals(5, last().getDone());
        assertTrue(playlist("Mixed", "").getSongs().get(2).isUnresolved());
    }

    @Test
    public void linkIsTrimmedBeforeItIsLoaded()
    {
        resolver.gives("Trimmed", 1, 1);

        jobs.start("  " + LINK + "\n", null, null, null, ALICE);

        assertEquals(Collections.singletonList(LINK), resolver.links);
    }

    // ---- names ----

    @Test
    public void namelessImportThatClashesGetsANumber()
    {
        store.create("Road Trip", "", ALICE);
        store.create("Road Trip (2)", "", ALICE);
        resolver.gives("Road Trip", 1, 1);

        jobs.start(LINK, null, null, null, ALICE);

        assertEquals("Road Trip (3)", last().getPlaylistName());
        assertEquals(1, playlist("Road Trip (3)", "").getSongs().size());
        assertEquals("the existing playlists are untouched", 0, playlist("Road Trip", "").getSongs().size());
    }

    @Test
    public void namedImportWhoseNameIsTakenMeanwhileGetsANumber()
    {
        resolver.script = (link, batches) ->
        {
            store.create("Mix", "", BOB); // somebody else makes it while the link loads
            batches.batch(songs(1, 2), 2);
            return Resolved.ok(songs(1, 2), null);
        };

        jobs.start(LINK, "Mix", null, null, ALICE);

        assertEquals("Mix (2)", last().getPlaylistName());
        assertEquals(2, playlist("Mix (2)", "").getSongs().size());
        assertEquals(0, playlist("Mix", "").getSongs().size());
    }

    @Test
    public void namelessImportCutsTheTitleToAUsableName()
    {
        resolver.gives("AC/DC \\ Rock\u0000Hits", 1, 1);
        jobs.start(LINK, null, null, null, ALICE);
        assertEquals("AC-DC - Rock Hits", last().getPlaylistName());

        progress.clear();
        StringBuilder long300 = new StringBuilder();
        for(int i = 0; i < 300; i++)
            long300.append('é');
        resolver.gives(long300.toString(), 1, 1);
        jobs.start(LINK, null, null, null, ALICE);
        String name = last().getPlaylistName();
        assertTrue(name.length() > 50 && name.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertEquals(1, playlist(name, "").getSongs().size());
    }

    @Test
    public void namelessImportWithoutAUsableTitleIsCalledImportedPlaylist()
    {
        for(String title : new String[]{null, "", "   ", "..", ".deleted"})
        {
            resolver.gives(title, 1, 1);
            progress.clear();
            jobs.start(LINK, null, null, null, ALICE);
            assertNull("title " + title, last().getError());
        }
        List<String> names = store.tree().stream().map(PlaylistStore.Entry::getName).collect(Collectors.toList());
        assertEquals(Arrays.asList("Imported playlist", "Imported playlist (2)", "Imported playlist (3)",
                "Imported playlist (4)", "Imported playlist (5)"), names);
    }

    // ---- when nothing comes in ----

    @Test
    public void failedImportCreatesNoPlaylist()
    {
        resolver.script = (link, batches) -> Resolved.failed("Playlist is private");
        jobs.start(LINK, null, null, null, ALICE);
        jobs.start(LINK, "Named", "Mixes", null, ALICE);

        assertTrue(store.tree().isEmpty());
        assertTrue(store.folders().isEmpty());
        assertEquals(2, finishedCount());
        assertEquals("Playlist is private", last().getError());
        assertNull(last().getPlaylistName());
        assertEquals(0, last().getDone());
    }

    @Test
    public void importThatFindsNothingSaysSo()
    {
        resolver.script = (link, batches) -> Resolved.ok(Collections.<Song>emptyList(), "Empty one");

        jobs.start(LINK, "Named", null, null, ALICE);

        assertTrue(store.tree().isEmpty());
        assertEquals(1, progress.size());
        assertTrue(last().isFinished());
        assertEquals("Nothing found at that link", last().getError());
    }

    @Test
    public void exceptionInTheResolverEndsTheJobWithAnError()
    {
        resolver.script = (link, batches) -> { throw new IllegalStateException("lavaplayer is down"); };

        String jobId = jobs.start(LINK, "Named", null, null, ALICE);

        assertTrue(store.tree().isEmpty());
        assertEquals(1, progress.size());
        assertEquals(jobId, last().getJobId());
        assertTrue(last().isFinished());
        assertNotNull(last().getError());
        assertFalse(last().getError().isEmpty());
        assertEquals(1, log.list.stream().filter(e -> e.getLevel() == Level.WARN).count());

        // the user's slot is free again
        resolver.gives("Fine", 1, 1);
        jobs.start(LINK, null, null, null, ALICE);
        assertNull(last().getError());
    }

    @Test
    public void songsAlreadyAddedStayWhenTheResolverFailsLater()
    {
        resolver.script = (link, batches) ->
        {
            batches.batch(songs(1, 3), 9);
            throw new RuntimeException("connection reset");
        };

        jobs.start(LINK, "Partial", null, null, ALICE);

        assertEquals(3, playlist("Partial", "").getSongs().size());
        assertTrue(last().isFinished());
        assertEquals(3, last().getDone());
        assertNotNull(last().getError());
        assertEquals("Partial", last().getPlaylistName());
    }

    @Test
    public void namelessImportThatFailsLaterLeavesNothingBehind()
    {
        resolver.script = (link, batches) ->
        {
            batches.batch(songs(1, 3), 9);
            throw new RuntimeException("connection reset");
        };

        jobs.start(LINK, null, null, null, ALICE);

        assertTrue(store.tree().isEmpty());
        assertNotNull(last().getError());
        assertEquals(0, last().getDone());
    }

    @Test
    public void importIntoAPlaylistDeletedMeanwhileEndsWithAnError()
    {
        store.create("Doomed", "", ALICE);
        resolver.script = (link, batches) ->
        {
            batches.batch(songs(1, 2), 4);
            store.delete("Doomed", "", ALICE);
            batches.batch(songs(3, 2), 4);
            return Resolved.ok(songs(1, 4), null);
        };

        jobs.start(LINK, null, null, "Doomed", ALICE);

        assertTrue(last().isFinished());
        assertEquals("Playlist not found", last().getError());
        assertEquals(2, last().getDone());
        assertEquals(1, finishedCount());
    }

    @Test
    public void finalProgressIsReportedOnceAndLast()
    {
        resolver.gives("Album", 6, 2, 2, 2);

        String jobId = jobs.start(LINK, null, null, null, ALICE);

        assertEquals(4, progress.size());
        assertEquals(1, finishedCount());
        assertTrue(last().isFinished());
        for(Progress each : progress)
            assertEquals(jobId, each.getJobId());
    }

    @Test
    public void aFailingProgressListenerDoesNotStopTheImport()
    {
        List<Progress> seen = new ArrayList<>();
        ImportJobs fragile = new ImportJobs(store, resolver, direct, p ->
        {
            seen.add(p);
            throw new RuntimeException("socket closed");
        });
        resolver.gives("Steady", 4, 2, 2);

        fragile.start(LINK, "Steady", null, null, ALICE);

        assertEquals(4, playlist("Steady", "").getSongs().size());
        assertEquals(3, seen.size());
        assertTrue(seen.get(2).isFinished());
        assertNull(seen.get(2).getError());
    }

    // ---- the 2000 cap ----

    @Test
    public void capsAt2000()
    {
        resolver.gives("Huge", 2500, 500, 500, 500, 500, 500);

        jobs.start(LINK, "Huge", null, null, ALICE);

        PlaylistFile file = playlist("Huge", "");
        assertEquals(2000, file.getSongs().size());
        assertEquals("Song 2000", file.getSongs().get(1999).getTitle());
        assertEquals(2000, last().getDone());
        assertEquals(2500, last().getTotal());
        assertEquals(FULL, last().getError());
        assertTrue(last().isFinished());
        assertEquals(1, finishedCount());
    }

    @Test
    public void capsAt2000WhenAddingToAnExistingPlaylist()
    {
        store.create("Big", "", ALICE);
        store.addSongs("Big", "", songs(1, 1990), null, ALICE);
        resolver.script = (link, batches) ->
        {
            List<Song> fifty = songs(5001, 50);
            batches.batch(fifty, 50);
            return Resolved.ok(fifty, "Fifty");
        };

        jobs.start(LINK, null, null, "Big", ALICE);

        PlaylistFile file = playlist("Big", "");
        assertEquals(2000, file.getSongs().size());
        assertEquals("the first ten of the batch", "Song 5010", file.getSongs().get(1999).getTitle());
        assertEquals(10, last().getDone());
        assertEquals(FULL, last().getError());
    }

    @Test
    public void exactly2000SongsIsNotAnError()
    {
        resolver.gives("Exact", 2000, 1000, 1000);

        jobs.start(LINK, "Exact", null, null, ALICE);

        assertEquals(2000, playlist("Exact", "").getSongs().size());
        assertNull(last().getError());
    }

    @Test
    public void importIntoAFullPlaylistAddsNothingAndStopsLoading()
    {
        store.create("Full", "", ALICE);
        store.addSongs("Full", "", songs(1, 2000), null, ALICE);
        int[] reached = new int[1];
        resolver.script = (link, batches) ->
        {
            for(int i = 0; i < 5; i++)
            {
                batches.batch(songs(9000 + i * 10, 10), 50);
                reached[0]++;
            }
            return Resolved.ok(songs(9000, 50), null);
        };

        jobs.start(LINK, null, null, "Full", ALICE);

        assertEquals(2000, playlist("Full", "").getSongs().size());
        assertEquals(0, last().getDone());
        assertEquals(FULL, last().getError());
        assertEquals("the load was cut off at the first batch", 0, reached[0]);
        assertEquals(1, finishedCount());
    }

    @Test
    public void namelessImportOverTheCapKeepsItsTitle()
    {
        resolver.gives("Too Many", 2400, 800, 800, 800);

        jobs.start(LINK, null, null, null, ALICE);

        assertEquals(2000, playlist("Too Many", "").getSongs().size());
        assertEquals("Too Many", last().getPlaylistName());
        assertEquals(FULL, last().getError());
    }

    // ---- what start refuses ----

    @Test
    public void rejectsNonHttp()
    {
        for(String link : new String[]{null, "", "   ", "ftp://example.test/list", "file:///etc/passwd",
                "javascript:alert(1)", "ytsearch:some song", "spotify:album:abc", "/home/cloutkings/music/a.m4a",
                "C:\\music\\a.m4a", "www.youtube.com/playlist?list=PL1", "httpx://example.test", "http:/example.test",
                "something https://example.test/list"})
        {
            IllegalArgumentException e = assertThrows("link " + link, IllegalArgumentException.class,
                    () -> jobs.start(link, null, null, null, ALICE));
            assertEquals(HTTP_ONLY, e.getMessage());
        }
        assertTrue("nothing was loaded", resolver.links.isEmpty());
        assertTrue("no job was announced", progress.isEmpty());

        // none of them took Alice's slot
        resolver.gives("Fine", 1, 1);
        jobs.start("http://example.test/list", null, null, null, ALICE);
        jobs.start("HTTPS://example.test/list", null, null, null, ALICE);
        assertEquals(2, finishedCount());
    }

    @Test
    public void rejectsBadNamesAndFolders()
    {
        resolver.gives("Fine", 1, 1);
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, "a/b", null, null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, "..", null, null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, ".deleted", null, null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, "ok", "../out", null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, null, "../out", null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, null, ".legacy-txt", null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, null, "", "a\\b", ALICE));
        assertThrows(IllegalArgumentException.class, () -> jobs.start(LINK, null, "../out", "Existing", ALICE));
        assertTrue(resolver.links.isEmpty());
        assertTrue(progress.isEmpty());
        assertFalse("nothing was written", Files.exists(root));
    }

    @Test
    public void rejectsANameThatIsTaken() throws Exception
    {
        store.create("Taken", "Rock", ALICE);
        Files.write(root.resolve("Old.txt"), "a song\n".getBytes(StandardCharsets.UTF_8));
        resolver.gives("Fine", 1, 1);

        IllegalStateException e = refused(() -> jobs.start(LINK, "Taken", "Rock", null, ALICE));
        assertEquals("A playlist with that name already exists", e.getMessage());
        e = refused(() -> jobs.start(LINK, "Old", null, null, ALICE));
        assertEquals("A playlist with that name already exists", e.getMessage());

        // the same name in another folder is free
        jobs.start(LINK, "Taken", null, null, ALICE);
        assertEquals(1, resolver.links.size());
    }

    @Test
    public void rejectsIntoThatDoesNotExistOrIsStillBeingUpgraded() throws Exception
    {
        Files.createDirectories(root);
        Files.write(root.resolve("Old.txt"), "a song\n".getBytes(StandardCharsets.UTF_8));
        resolver.gives("Fine", 1, 1);

        assertThrows(NoSuchElementException.class, () -> jobs.start(LINK, null, null, "Missing", ALICE));
        assertThrows(NoSuchElementException.class, () -> jobs.start(LINK, null, "Rock", "Missing", ALICE));
        IllegalStateException e = refused(() -> jobs.start(LINK, null, null, "Old", ALICE));
        assertEquals(UPGRADING, e.getMessage());
        assertTrue(resolver.links.isEmpty());

        // and none of those took the slot
        store.create("Real", "", ALICE);
        jobs.start(LINK, null, null, "Real", ALICE);
        assertEquals(1, playlist("Real", "").getSongs().size());
    }

    // ---- limits ----

    @Test
    public void onePerUserThreeOverall()
    {
        List<Runnable> queued = new ArrayList<>();
        ImportJobs held = new ImportJobs(store, resolver, queued::add, progress::add); // accepted, not started: running
        resolver.gives("Album", 1, 1);

        held.start(LINK, "A1", null, null, ALICE);
        IllegalStateException e = refused(() -> held.start(LINK, "A2", null, null, ALICE));
        assertEquals(ONE_PER_USER, e.getMessage());
        held.start(LINK, "B1", null, null, BOB);
        held.start(LINK, "C1", null, null, CARL);
        e = refused(() -> held.start(LINK, "D1", null, null, DAN));
        assertEquals(TOO_MANY, e.getMessage());
        e = refused(() -> held.start(LINK, "B2", null, null, BOB));
        assertEquals("one per user is checked first", ONE_PER_USER, e.getMessage());
        assertEquals(3, queued.size());

        // when one ends, its owner and the next user can start again
        queued.get(0).run();
        held.start(LINK, "D1", null, null, DAN);
        e = refused(() -> held.start(LINK, "A2", null, null, ALICE));
        assertEquals(TOO_MANY, e.getMessage());
        queued.get(1).run();
        held.start(LINK, "A2", null, null, ALICE);
        assertEquals(5, queued.size());
    }

    @Test
    public void usersAreToldApartById()
    {
        List<Runnable> queued = new ArrayList<>();
        ImportJobs held = new ImportJobs(store, resolver, queued::add, progress::add);

        held.start(LINK, null, null, null, new Person("111", "Alice"));
        refused(() -> held.start(LINK, null, null, null, new Person("111", "Alice with a new nickname")));
        held.start(LINK, null, null, null, new Person("112", "Alice"));
        assertEquals(2, queued.size());
    }

    @Test
    public void unknownUsersShareOneSlot()
    {
        List<Runnable> queued = new ArrayList<>();
        ImportJobs held = new ImportJobs(store, resolver, queued::add, progress::add);

        held.start(LINK, null, null, null, null);
        IllegalStateException e = refused(() -> held.start(LINK, null, null, null, null));
        assertEquals(ONE_PER_USER, e.getMessage());
        refused(() -> held.start(LINK, null, null, null, new Person(null, "No id")));
        held.start(LINK, null, null, null, ALICE);
        assertEquals(2, queued.size());
    }

    @Test
    public void slotIsFreeWhenTheExecutorRefusesTheJob()
    {
        Executor closed = command ->
        {
            throw new RejectedExecutionException("shut down");
        };
        ImportJobs shut = new ImportJobs(store, resolver, closed, progress::add);

        assertThrows(RejectedExecutionException.class,
                () -> shut.start(LINK, null, null, null, ALICE));
        assertThrows(RejectedExecutionException.class,
                () -> shut.start(LINK, null, null, null, ALICE)); // not "already running"
        assertTrue(progress.isEmpty());
    }

    @Test
    public void twoStartsAtOnceBySameUserLetExactlyOneIn() throws Exception
    {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try
        {
            for(int round = 0; round < 25; round++)
            {
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch ended = new CountDownLatch(1);
                resolver.script = (link, batches) ->
                {
                    try
                    {
                        assertTrue("the test never let go", release.await(10, TimeUnit.SECONDS));
                    }
                    catch(InterruptedException e)
                    {
                        throw new IllegalStateException(e);
                    }
                    return Resolved.failed("Done waiting");
                };
                ImportJobs racing = new ImportJobs(store, resolver, workers, p ->
                {
                    if(p.isFinished())
                        ended.countDown();
                });
                CyclicBarrier together = new CyclicBarrier(2);
                List<Future<String>> results = new ArrayList<>();
                for(int i = 0; i < 2; i++)
                    results.add(callers.submit(() ->
                    {
                        together.await(5, TimeUnit.SECONDS);
                        try
                        {
                            return racing.start(LINK, null, null, null, ALICE);
                        }
                        catch(IllegalStateException e)
                        {
                            return e.getMessage();
                        }
                    }));
                String first = results.get(0).get(10, TimeUnit.SECONDS);
                String second = results.get(1).get(10, TimeUnit.SECONDS);
                boolean firstWon = first.matches("[a-z0-9]{8}");
                boolean secondWon = second.matches("[a-z0-9]{8}");
                assertTrue("round " + round + ": " + first + " / " + second, firstWon != secondWon);
                assertEquals(ONE_PER_USER, firstWon ? second : first);

                release.countDown();
                assertTrue("the job never ended", ended.await(10, TimeUnit.SECONDS));
            }
        }
        finally
        {
            callers.shutdownNow();
            workers.shutdownNow();
        }
    }

    @Test
    public void slotsAreFreedWhateverHappensToTheJob()
    {
        List<Runnable> queued = new ArrayList<>();
        ImportJobs held = new ImportJobs(store, resolver, queued::add, progress::add);
        resolver.script = (link, batches) ->
        {
            if(link.endsWith("/nothing"))
                return Resolved.failed("Nope");
            if(link.endsWith("/boom"))
                throw new IllegalStateException("broken");
            batches.batch(songs(1, 1), 1);
            return Resolved.ok(songs(1, 1), null);
        };

        held.start("https://example.test/nothing", null, null, null, ALICE);
        held.start("https://example.test/boom", null, null, null, BOB);
        held.start("https://example.test/fine", "Fine", null, null, CARL);
        refused(() -> held.start(LINK, null, null, null, DAN));

        for(Runnable job : queued)
            job.run();
        assertEquals(3, finishedCount());

        held.start(LINK, null, null, null, ALICE);
        held.start(LINK, null, null, null, BOB);
        held.start(LINK, null, null, null, CARL);
        assertEquals(6, queued.size());
    }
}
