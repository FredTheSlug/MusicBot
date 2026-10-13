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

import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PlaylistStoreSongsTest
{
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final String NAME = "Mix";
    private static final String FOLDER = "Rock";
    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");
    private static final Person FRED = new Person("233362518045753345", "fred");
    private static final Person ANNA = new Person("111222333444555666", "anna");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private TestClock clock;
    private PlaylistStore store;

    @Before
    public void setUp() throws Exception
    {
        root = tmp.getRoot().toPath().resolve("Playlists");
        clock = new TestClock(T0);
        store = new PlaylistStore(root, clock);
        store.create(NAME, FOLDER, FRED);
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

    private static Song song(String title)
    {
        Song song = new Song();
        song.setId("callerid"); // the store must not keep this
        song.setTitle(title);
        song.setUri("https://example.com/" + title);
        song.setAuthor("Artist " + title);
        song.setDurationMs(1000L * title.length());
        song.setArtworkUrl("https://example.com/" + title + ".png");
        return song;
    }

    private static List<Song> songs(String... titles)
    {
        List<Song> songs = new ArrayList<>();
        for(String title : titles)
            songs.add(song(title));
        return songs;
    }

    private static List<String> titles(PlaylistFile file)
    {
        List<String> titles = new ArrayList<>();
        for(Song song : file.getSongs())
            titles.add(song.getTitle());
        return titles;
    }

    private static Song songTitled(PlaylistFile file, String title)
    {
        for(Song song : file.getSongs())
            if(title.equals(song.getTitle()))
                return song;
        throw new AssertionError("no song titled " + title + " in " + titles(file));
    }

    private static String idOf(PlaylistFile file, String title)
    {
        return songTitled(file, title).getId();
    }

    private PlaylistFile add(String... titles)
    {
        return store.addSongs(NAME, FOLDER, songs(titles), null, FRED);
    }

    private PlaylistFile stored()
    {
        return store.get(NAME, FOLDER).get();
    }

    private String fileText() throws Exception
    {
        return new String(Files.readAllBytes(store.pathFor(NAME, FOLDER)), StandardCharsets.UTF_8);
    }

    @Test
    public void addAtEndAndIndex() throws Exception
    {
        clock.set(T0.plusSeconds(60));
        PlaylistFile first = store.addSongs(NAME, FOLDER, songs("a", "b"), null, ANNA);
        assertEquals(Arrays.asList("a", "b"), titles(first));
        assertEquals(2, first.getVersion());
        assertEquals(T0.plusSeconds(60), first.getUpdatedAt());
        assertEquals("what is returned is what was stored", titles(first), titles(stored()));

        // an index puts the whole batch there, in order
        assertEquals(Arrays.asList("a", "c", "d", "b"), titles(store.addSongs(NAME, FOLDER, songs("c", "d"), 1, ANNA)));
        // 0 is the front, null the end; out of range is clamped to the ends
        assertEquals(Arrays.asList("e", "a", "c", "d", "b"), titles(store.addSongs(NAME, FOLDER, songs("e"), 0, ANNA)));
        assertEquals(Arrays.asList("e", "a", "c", "d", "b", "f"), titles(store.addSongs(NAME, FOLDER, songs("f"), null, ANNA)));
        assertEquals(Arrays.asList("e", "a", "c", "d", "b", "f", "g"), titles(store.addSongs(NAME, FOLDER, songs("g"), 99, ANNA)));
        PlaylistFile front = store.addSongs(NAME, FOLDER, songs("h"), -5, ANNA);
        assertEquals(Arrays.asList("h", "e", "a", "c", "d", "b", "f", "g"), titles(front));
        assertEquals(7, front.getVersion());
        assertEquals(titles(front), titles(stored()));
        assertEquals(7, stored().getVersion());
    }

    @Test
    public void addStampsFreshCopies() throws Exception
    {
        clock.set(T0.plusSeconds(60));
        Song mine = song("a");
        mine.setAddedBy(FRED);
        mine.setAddedAt(T0.minusSeconds(1));
        mine.setUnresolved(true);
        List<Song> batch = new ArrayList<>(Arrays.asList(mine, song("b"), song("a")));
        PlaylistFile file = store.addSongs(NAME, FOLDER, batch, null, ANNA);

        assertEquals(3, file.getSongs().size());
        Set<String> ids = new HashSet<>();
        for(Song added : file.getSongs())
        {
            assertTrue(added.getId().matches("[a-z0-9]{8}"));
            assertNotEquals("fresh id, not the caller's", "callerid", added.getId());
            ids.add(added.getId());
            assertEquals("ANNA is the one who added it", "111222333444555666", added.getAddedBy().getId());
            assertEquals("anna", added.getAddedBy().getName());
            assertEquals(T0.plusSeconds(60), added.getAddedAt());
        }
        assertEquals(3, ids.size());
        for(int i = 0; i < 3; i++)
            assertNotSame("a copy, not the caller's object", batch.get(i), file.getSongs().get(i));
        assertEquals("caller's song is left alone", FRED, mine.getAddedBy());
        assertEquals(T0.minusSeconds(1), mine.getAddedAt());

        Song copy = file.getSongs().get(0);
        assertEquals("https://example.com/a", copy.getUri());
        assertEquals("a", copy.getTitle());
        assertEquals("Artist a", copy.getAuthor());
        assertEquals(1000, copy.getDurationMs());
        assertEquals("https://example.com/a.png", copy.getArtworkUrl());
        assertTrue(copy.isUnresolved());
        assertFalse(file.getSongs().get(2).isUnresolved());

        Song loaded = stored().getSongs().get(0);
        assertEquals(copy.getId(), loaded.getId());
        assertEquals(T0.plusSeconds(60), loaded.getAddedAt());
        assertEquals("anna", loaded.getAddedBy().getName());

        // nobody in particular added these
        PlaylistFile anonymous = store.addSongs(NAME, FOLDER, songs("z"), null, null);
        assertNull(anonymous.getSongs().get(3).getAddedBy());
        assertNull(stored().getSongs().get(3).getAddedBy());
    }

    @Test
    public void addNothingIsRefused() throws Exception
    {
        add("a");
        String before = fileText();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.addSongs(NAME, FOLDER, new ArrayList<Song>(), null, FRED));
        assertEquals("No songs to add", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> store.addSongs(NAME, FOLDER, null, 0, FRED));
        assertEquals("No songs to add", e.getMessage());
        assertEquals(before, fileText());
    }

    @Test
    public void removeReturnsIndexForUndo() throws Exception
    {
        PlaylistFile start = add("a", "b", "c");
        Song b = songTitled(start, "b");
        clock.set(T0.plusSeconds(60));

        PlaylistStore.Removed removed = store.removeSong(NAME, FOLDER, b.getId());

        assertEquals("b", removed.getSong().getTitle());
        assertEquals(b.getId(), removed.getSong().getId());
        assertEquals("https://example.com/b", removed.getSong().getUri());
        assertEquals(1, removed.getIndex());
        assertEquals(Arrays.asList("a", "c"), titles(removed.getPlaylist()));
        assertEquals(3, removed.getPlaylist().getVersion());
        assertEquals(T0.plusSeconds(60), removed.getPlaylist().getUpdatedAt());
        assertEquals(Arrays.asList("a", "c"), titles(stored()));
        assertEquals(3, stored().getVersion());

        // Undo: put the very same song back where it was
        PlaylistFile undone = store.addSongs(NAME, FOLDER, Arrays.asList(removed.getSong()), removed.getIndex(), FRED);
        assertEquals(Arrays.asList("a", "b", "c"), titles(undone));
        assertEquals("https://example.com/b", undone.getSongs().get(1).getUri());

        // the first and the last song report their own places
        assertEquals(0, store.removeSong(NAME, FOLDER, idOf(stored(), "a")).getIndex());
        assertEquals(1, store.removeSong(NAME, FOLDER, idOf(stored(), "c")).getIndex());
        PlaylistStore.Removed last = store.removeSong(NAME, FOLDER, idOf(stored(), "b"));
        assertEquals(0, last.getIndex());
        assertTrue(last.getPlaylist().getSongs().isEmpty());
        assertTrue(stored().getSongs().isEmpty());
    }

    @Test
    public void moveClamps() throws Exception
    {
        PlaylistFile start = add("a", "b", "c", "d");
        String a = idOf(start, "a");
        clock.set(T0.plusSeconds(60));

        // toIndex is where the song ends up
        PlaylistFile moved = store.moveSong(NAME, FOLDER, a, 2);
        assertEquals(Arrays.asList("b", "c", "a", "d"), titles(moved));
        assertEquals(3, moved.getVersion());
        assertEquals(T0.plusSeconds(60), moved.getUpdatedAt());
        assertEquals(Arrays.asList("b", "c", "a", "d"), titles(stored()));

        // too big is the end, too small the front
        assertEquals(Arrays.asList("b", "c", "d", "a"), titles(store.moveSong(NAME, FOLDER, a, 99)));
        assertEquals(Arrays.asList("a", "b", "c", "d"), titles(store.moveSong(NAME, FOLDER, a, -3)));
        assertEquals(Arrays.asList("b", "c", "d", "a"), titles(store.moveSong(NAME, FOLDER, a, 3)));
        // to where it already is
        assertEquals(Arrays.asList("b", "c", "d", "a"), titles(store.moveSong(NAME, FOLDER, a, 3)));
        // a move towards the front puts it exactly there too
        assertEquals(Arrays.asList("b", "a", "c", "d"), titles(store.moveSong(NAME, FOLDER, a, 1)));
        assertEquals(Arrays.asList("b", "a", "c", "d"), titles(stored()));

        // nothing else about the songs changes
        for(String title : Arrays.asList("a", "b", "c", "d"))
            assertEquals(idOf(start, title), idOf(stored(), title));
    }

    @Test
    public void moveInASingleSongPlaylist() throws Exception
    {
        PlaylistFile start = add("only");
        String only = idOf(start, "only");
        assertEquals(Arrays.asList("only"), titles(store.moveSong(NAME, FOLDER, only, 0)));
        assertEquals(Arrays.asList("only"), titles(store.moveSong(NAME, FOLDER, only, 7)));
        assertEquals(Arrays.asList("only"), titles(store.moveSong(NAME, FOLDER, only, -7)));
    }

    @Test
    public void fullPlaylistRejectsMore() throws Exception
    {
        String[] many = new String[PlaylistFile.MAX_SONGS - 1];
        for(int i = 0; i < many.length; i++)
            many[i] = "s" + i;
        PlaylistFile nearlyFull = add(many);
        assertEquals(1999, nearlyFull.getSongs().size());
        long version = stored().getVersion();
        String before = fileText();

        // all or nothing: room for one more is not room for two
        IllegalStateException full = assertThrows(IllegalStateException.class, () -> add("x", "y"));
        assertEquals("Playlist is full", full.getMessage());
        assertEquals(before, fileText());
        assertEquals(1999, stored().getSongs().size());

        // exactly 2000 fits
        PlaylistFile atCap = add("last");
        assertEquals(2000, atCap.getSongs().size());
        assertEquals(version + 1, atCap.getVersion());

        before = fileText();
        full = assertThrows(IllegalStateException.class, () -> add("one too many"));
        assertEquals("Playlist is full", full.getMessage());
        full = assertThrows(IllegalStateException.class, () -> store.addSongs(NAME, FOLDER, songs("x"), 0, FRED));
        assertEquals("Playlist is full", full.getMessage());
        assertEquals(before, fileText());
        assertEquals(2000, stored().getSongs().size());

        // taking one out makes room for one
        store.removeSong(NAME, FOLDER, idOf(stored(), "s0"));
        assertEquals(2000, add("again").getSongs().size());
    }

    @Test
    public void oneBatchOverTheCapAddsNothing() throws Exception
    {
        String[] tooMany = new String[PlaylistFile.MAX_SONGS + 1];
        for(int i = 0; i < tooMany.length; i++)
            tooMany[i] = "s" + i;
        String before = fileText();
        IllegalStateException full = assertThrows(IllegalStateException.class, () -> add(tooMany));
        assertEquals("Playlist is full", full.getMessage());
        assertEquals(before, fileText());
        assertEquals(1, stored().getVersion());
    }

    @Test
    public void interleavedEditsKeepEverything() throws Exception
    {
        // both orders of the two friends give the same playlist
        assertEquals(Arrays.asList("A", "E", "B", "D", "Z"), twoFriendsEdit("AThenB", true));
        assertEquals(Arrays.asList("A", "E", "B", "D", "Z"), twoFriendsEdit("BThenA", false));
    }

    /**
     * Two friends open the playlist [A, B, C, D, E] at the same time. Friend one removes C; friend two moves
     * E to the second place and adds Z. Each picks songs from the snapshot they loaded. Returns the final titles.
     */
    private List<String> twoFriendsEdit(String name, boolean removerFirst) throws Exception
    {
        store.create(name, FOLDER, FRED);
        store.addSongs(name, FOLDER, songs("A", "B", "C", "D", "E"), null, FRED);
        PlaylistFile friendOne = store.get(name, FOLDER).get();
        PlaylistFile friendTwo = store.get(name, FOLDER).get();
        String x = idOf(friendOne, "C");
        String y = idOf(friendTwo, "E");

        if(removerFirst)
        {
            store.removeSong(name, FOLDER, x);
            store.moveSong(name, FOLDER, y, 1);
            store.addSongs(name, FOLDER, songs("Z"), null, ANNA);
        }
        else
        {
            store.moveSong(name, FOLDER, y, 1);
            store.addSongs(name, FOLDER, songs("Z"), null, ANNA);
            store.removeSong(name, FOLDER, x);
        }

        PlaylistFile result = store.get(name, FOLDER).get();
        assertEquals(1 + 1 + 3, result.getVersion());
        assertEquals(y, result.getSongs().get(1).getId());
        assertEquals("Z", result.getSongs().get(4).getTitle());
        for(String untouched : Arrays.asList("A", "B", "D", "E"))
        {
            Song before = songTitled(friendOne, untouched);
            Song after = songTitled(result, untouched);
            assertEquals(before.getId(), after.getId());
            assertEquals(before.getUri(), after.getUri());
            assertEquals(before.getAuthor(), after.getAuthor());
            assertEquals(before.getDurationMs(), after.getDurationMs());
            assertEquals(before.getAddedAt(), after.getAddedAt());
            assertEquals(before.getAddedBy().getId(), after.getAddedBy().getId());
        }
        for(Song song : result.getSongs())
            assertNotEquals(x, song.getId());
        return titles(result);
    }

    @Test
    public void concurrentAddsFromThreads() throws Exception
    {
        int threads = 8;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for(int i = 0; i < threads; i++)
            {
                int thread = i;
                results.add(pool.submit(() ->
                {
                    ready.countDown();
                    go.await();
                    for(int j = 0; j < each; j++)
                        store.addSongs(NAME, FOLDER, songs("t" + thread + "-" + j), null, FRED);
                    return null;
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            go.countDown();
            for(Future<?> result : results)
                result.get(60, TimeUnit.SECONDS); // a deadlock fails here instead of hanging the build
        }
        finally
        {
            pool.shutdownNow();
        }

        PlaylistFile loaded = stored();
        assertEquals(threads * each, loaded.getSongs().size());
        assertEquals(1 + threads * each, loaded.getVersion());
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for(Song song : loaded.getSongs())
        {
            ids.add(song.getId());
            names.add(song.getTitle());
        }
        assertEquals("unique ids", threads * each, ids.size());
        assertEquals("no lost adds", threads * each, names.size());
    }

    @Test
    public void shuffleSwitch() throws Exception
    {
        add("a");
        clock.set(T0.plusSeconds(60));
        PlaylistFile on = store.setShuffle(NAME, FOLDER, true);
        assertTrue(on.isShuffle());
        assertEquals(3, on.getVersion());
        assertEquals(T0.plusSeconds(60), on.getUpdatedAt());
        assertTrue(stored().isShuffle());
        assertEquals(Arrays.asList("a"), titles(stored()));

        assertFalse(store.setShuffle(NAME, FOLDER, false).isShuffle());
        assertFalse(stored().isShuffle());
        assertEquals(4, stored().getVersion());
    }

    @Test
    public void replaceSongKeepsIdAndWhoAddedIt() throws Exception
    {
        clock.set(T0.plusSeconds(10));
        Song lost = song("some words");
        lost.setUnresolved(true);
        store.addSongs(NAME, FOLDER, Arrays.asList(lost), null, ANNA);
        add("other");
        PlaylistFile start = stored();
        Song before = start.getSongs().get(0);
        assertTrue(before.isUnresolved());

        clock.set(T0.plusSeconds(60));
        Song resolved = new Song();
        resolved.setId("ignored1");
        resolved.setUri("https://example.com/found");
        resolved.setTitle("Found It");
        resolved.setAuthor("Somebody");
        resolved.setDurationMs(215000);
        resolved.setArtworkUrl("https://example.com/found.png");
        resolved.setAddedBy(FRED);
        resolved.setAddedAt(T0);
        PlaylistFile replaced = store.replaceSong(NAME, FOLDER, before.getId(), resolved);

        assertEquals(4, replaced.getVersion());
        assertEquals(T0.plusSeconds(60), replaced.getUpdatedAt());
        assertEquals(Arrays.asList("Found It", "other"), titles(replaced));
        for(PlaylistFile file : Arrays.asList(replaced, stored()))
        {
            Song now = file.getSongs().get(0);
            assertEquals("same entry", before.getId(), now.getId());
            assertEquals("anna", now.getAddedBy().getName());
            assertEquals(T0.plusSeconds(10), now.getAddedAt());
            assertEquals("https://example.com/found", now.getUri());
            assertEquals("Found It", now.getTitle());
            assertEquals("Somebody", now.getAuthor());
            assertEquals(215000, now.getDurationMs());
            assertEquals("https://example.com/found.png", now.getArtworkUrl());
            assertFalse(now.isUnresolved());
            assertEquals("the neighbour is not touched", start.getSongs().get(1).getId(), file.getSongs().get(1).getId());
            assertEquals("other", file.getSongs().get(1).getTitle());
        }

        // a retry that fails again stays marked
        Song stillLost = song("some words");
        stillLost.setUnresolved(true);
        assertTrue(store.replaceSong(NAME, FOLDER, before.getId(), stillLost).getSongs().get(0).isUnresolved());
        assertTrue(stored().getSongs().get(0).isUnresolved());
    }

    @Test
    public void unknownSongOrPlaylist() throws Exception
    {
        add("a", "b");
        String before = fileText();
        long version = stored().getVersion();

        NoSuchElementException e = assertThrows(NoSuchElementException.class, () -> store.removeSong(NAME, FOLDER, "nosuchid"));
        assertEquals("Song not found", e.getMessage());
        e = assertThrows(NoSuchElementException.class, () -> store.moveSong(NAME, FOLDER, "nosuchid", 0));
        assertEquals("Song not found", e.getMessage());
        e = assertThrows(NoSuchElementException.class, () -> store.replaceSong(NAME, FOLDER, "nosuchid", song("x")));
        assertEquals("Song not found", e.getMessage());
        e = assertThrows(NoSuchElementException.class, () -> store.removeSong(NAME, FOLDER, null));
        assertEquals("Song not found", e.getMessage());
        assertEquals("nothing written", before, fileText());
        assertEquals(version, stored().getVersion());

        e = assertThrows(NoSuchElementException.class, () -> store.addSongs("Nope", FOLDER, songs("a"), null, FRED));
        assertEquals("Playlist not found", e.getMessage());
        assertThrows(NoSuchElementException.class, () -> store.addSongs(NAME, "No/Such/Folder", songs("a"), null, FRED));
        assertThrows(NoSuchElementException.class, () -> store.removeSong("Nope", FOLDER, "nosuchid"));
        assertThrows(NoSuchElementException.class, () -> store.moveSong("Nope", FOLDER, "nosuchid", 0));
        assertThrows(NoSuchElementException.class, () -> store.setShuffle("Nope", FOLDER, true));
        assertThrows(NoSuchElementException.class, () -> store.replaceSong("Nope", FOLDER, "nosuchid", song("x")));
        assertFalse("no playlist is made by a stray edit", Files.exists(store.pathFor("Nope", FOLDER)));
        assertThrows(IllegalArgumentException.class, () -> store.addSongs("../x", FOLDER, songs("a"), null, FRED));
    }

    @Test
    public void legacyPlaylistRefusesEdits() throws Exception
    {
        Path txt = root.resolve("Rap").resolve("Old.txt");
        Files.createDirectories(txt.getParent());
        Files.write(txt, "https://example.com/a\n".getBytes(StandardCharsets.UTF_8));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.addSongs("Old", "Rap", songs("x"), null, FRED));
        assertEquals(UPGRADING, e.getMessage());
        String legacyId = store.get("Old", "Rap").get().getSongs().get(0).getId();
        e = assertThrows(IllegalStateException.class, () -> store.removeSong("Old", "Rap", legacyId));
        assertEquals(UPGRADING, e.getMessage());
        e = assertThrows(IllegalStateException.class, () -> store.setShuffle("Old", "Rap", true));
        assertEquals(UPGRADING, e.getMessage());

        assertEquals("https://example.com/a\n", new String(Files.readAllBytes(txt), StandardCharsets.UTF_8));
        assertFalse(Files.exists(store.pathFor("Old", "Rap")));
    }

    @Test
    public void reinsertSongRestoresTheRemovedSongExactly() throws Exception
    {
        clock.set(T0.plusSeconds(60));
        store.addSongs(NAME, FOLDER, songs("a", "b", "c"), null, ANNA);
        Song original = songTitled(stored(), "b");
        String id = original.getId();
        clock.set(T0.plusSeconds(120));
        PlaylistStore.Removed removed = store.removeSong(NAME, FOLDER, id);
        assertEquals(1, removed.getIndex());
        clock.set(T0.plusSeconds(180));

        PlaylistFile back = store.reinsertSong(NAME, FOLDER, removed.getSong(), removed.getIndex());

        assertEquals(Arrays.asList("a", "b", "c"), titles(back));
        Song again = back.getSongs().get(1);
        assertEquals("same id", id, again.getId());
        assertEquals("added by the same person", "111222333444555666", again.getAddedBy().getId());
        assertEquals("added when it was first added, not now", T0.plusSeconds(60), again.getAddedAt());
        assertEquals(original.getUri(), again.getUri());
        assertEquals(original.getAuthor(), again.getAuthor());
        assertEquals(original.getDurationMs(), again.getDurationMs());
        assertEquals(original.getArtworkUrl(), again.getArtworkUrl());
        assertEquals("one version per change: create, add, remove, reinsert", 4, back.getVersion());
        assertEquals(T0.plusSeconds(180), back.getUpdatedAt());
        assertEquals("what is returned is what was stored", titles(back), titles(stored()));
        assertEquals(id, stored().getSongs().get(1).getId());
    }

    @Test
    public void reinsertSongClampsTheIndex() throws Exception
    {
        add("a", "b");
        PlaylistStore.Removed first = store.removeSong(NAME, FOLDER, idOf(stored(), "a"));
        PlaylistStore.Removed second = store.removeSong(NAME, FOLDER, idOf(stored(), "b"));
        assertEquals(0, stored().getSongs().size());

        assertEquals(Arrays.asList("b"), titles(store.reinsertSong(NAME, FOLDER, second.getSong(), 5)));
        assertEquals(Arrays.asList("a", "b"), titles(store.reinsertSong(NAME, FOLDER, first.getSong(), 0)));
        assertEquals("a negative index is the front", Arrays.asList("c", "a", "b"),
                titles(store.reinsertSong(NAME, FOLDER, song("c"), -3)));
        assertEquals("past the end is the end", Arrays.asList("c", "a", "b", "d"),
                titles(store.reinsertSong(NAME, FOLDER, song("d"), 99)));
        assertEquals(titles(stored()), Arrays.asList("c", "a", "b", "d"));
    }

    @Test
    public void reinsertSongGivesAFreshIdOnlyWhenTheOldOneIsTaken() throws Exception
    {
        add("a", "b");
        Song taken = songTitled(stored(), "a");
        Song copy = new Song();
        copy.setId(taken.getId()); // another song took this id in the meantime
        copy.setUri("https://example.com/z");
        copy.setTitle("z");
        copy.setAddedBy(FRED);
        copy.setAddedAt(T0.minusSeconds(5));

        PlaylistFile file = store.reinsertSong(NAME, FOLDER, copy, 1);

        assertEquals(Arrays.asList("a", "z", "b"), titles(file));
        Song z = file.getSongs().get(1);
        assertNotEquals(taken.getId(), z.getId());
        assertTrue(z.getId().matches("[a-z0-9]{8}"));
        assertEquals("fred", z.getAddedBy().getName());
        assertEquals(T0.minusSeconds(5), z.getAddedAt());
        assertEquals("the song that held the id keeps it", taken.getId(), file.getSongs().get(0).getId());
        assertEquals("the caller's song is not touched", taken.getId(), copy.getId());
        Set<String> ids = new HashSet<>();
        for(Song each : file.getSongs())
            assertTrue("unique ids", ids.add(each.getId()));
    }

    @Test
    public void reinsertSongIntoAFullPlaylistIsRefused() throws Exception
    {
        List<Song> many = new ArrayList<>();
        for(int i = 0; i < PlaylistFile.MAX_SONGS; i++)
            many.add(song("s" + i));
        store.addSongs(NAME, FOLDER, many, null, FRED);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.reinsertSong(NAME, FOLDER, song("extra"), 0));

        assertEquals("Playlist is full", e.getMessage());
        assertEquals(PlaylistFile.MAX_SONGS, stored().getSongs().size());
        assertEquals("nothing was written", 2, stored().getVersion());
    }

    @Test
    public void reinsertSongRefusesWhatItCannotChange() throws Exception
    {
        assertThrows(IllegalArgumentException.class, () -> store.reinsertSong(NAME, FOLDER, null, 0));
        assertThrows(NoSuchElementException.class, () -> store.reinsertSong("Nope", FOLDER, song("x"), 0));
        assertThrows(IllegalArgumentException.class, () -> store.reinsertSong("../x", FOLDER, song("x"), 0));
        Path txt = root.resolve("Rap").resolve("Old.txt");
        Files.createDirectories(txt.getParent());
        Files.write(txt, "https://example.com/a\n".getBytes(StandardCharsets.UTF_8));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.reinsertSong("Old", "Rap", song("x"), 0));
        assertEquals(UPGRADING, e.getMessage());
    }
}
