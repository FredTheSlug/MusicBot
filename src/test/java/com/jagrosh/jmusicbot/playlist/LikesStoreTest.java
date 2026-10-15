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
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class LikesStoreTest
{
    private static final String FULL = "You've liked 2000 songs — remove some to like more";
    private static final Instant T0 = Instant.parse("2026-10-14T18:00:00Z");
    private static final Person FRED = new Person("233362518045753345", "fred");
    private static final Person ANNA = new Person("111222333444555666", "anna");
    private static final Person BOB = new Person("987654321098765432", "Bob");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir;
    private TestClock clock;
    private LikesStore likes;
    private ListAppender<ILoggingEvent> log;
    private final Map<ch.qos.logback.classic.Logger, Boolean> wasAdditive = new LinkedHashMap<>();

    @Before
    public void setUp()
    {
        // not created: the first like makes the folder
        dir = tmp.getRoot().toPath().resolve("Playlists").resolve(".liked");
        clock = new TestClock(T0);
        likes = new LikesStore(dir, clock);

        // keep the expected warnings out of the test output, and look at them
        log = new ListAppender<>();
        log.start();
        for(Class<?> type : new Class<?>[]{PlaylistStore.class, LikesStore.class})
        {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
            log.setContext(logger.getLoggerContext());
            wasAdditive.put(logger, logger.isAdditive());
            logger.setAdditive(false);
            logger.addAppender(log);
        }
    }

    @After
    public void tearDown()
    {
        for(Map.Entry<ch.qos.logback.classic.Logger, Boolean> each : wasAdditive.entrySet())
        {
            each.getKey().detachAppender(log);
            each.getKey().setAdditive(each.getValue());
        }
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

    private static Song song(String key)
    {
        Song song = new Song();
        song.setId("callerid"); // the store must not keep this
        song.setUri("https://example.com/watch?v=" + key);
        song.setTitle("Title " + key);
        song.setAuthor("Artist " + key);
        song.setDurationMs(1000L * key.length());
        song.setArtworkUrl("https://example.com/" + key + ".png");
        return song;
    }

    private static String uri(String key)
    {
        return song(key).getUri();
    }

    /** The keys the songs were made with, in list order. */
    private static List<String> keys(List<Song> songs)
    {
        return songs.stream().map(s -> s.getUri().substring(s.getUri().indexOf("v=") + 2)).collect(Collectors.toList());
    }

    private List<String> listed(Person owner)
    {
        return keys(likes.get(owner.getId()).get().getSongs());
    }

    private Path fileOf(Person owner)
    {
        return dir.resolve(owner.getId() + ".json");
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

    private List<String> warnings()
    {
        return log.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** Puts a song into a list behind the LikesStore's back, as a hand edit or an older version might have. */
    private void addRaw(Person owner, Song song, int index)
    {
        new PlaylistStore(dir, clock).update(owner.getId(), "", f ->
        {
            Set<String> ids = new HashSet<>();
            for(Song each : f.getSongs())
                ids.add(each.getId());
            f.getSongs().add(index, PlaylistFile.copyForPlaylist(song, ids, owner, T0));
        });
    }

    /** A list of {@code count} songs {@code s0 … s<count-1>}, written without going through {@code like}. */
    private void seed(Person owner, int count)
    {
        PlaylistStore raw = new PlaylistStore(dir, clock);
        raw.create(owner.getId(), "", owner);
        raw.update(owner.getId(), "", f ->
        {
            Set<String> ids = new HashSet<>();
            for(int i = 0; i < count; i++)
                f.getSongs().add(PlaylistFile.copyForPlaylist(song("s" + i), ids, owner, T0));
        });
    }

    @Test
    public void nothingLikedYetMakesNothing() throws Exception
    {
        assertFalse(likes.get(FRED.getId()).isPresent());
        assertTrue(likes.uris(FRED.getId()).isEmpty());
        assertTrue(likes.people().isEmpty());
        assertTrue(likes.whoLiked(uri("a"), null).isEmpty());
        assertTrue(likes.everyone(new Random(1), 2000).isEmpty());
        assertFalse("reading never makes the folder", Files.exists(dir));
    }

    @Test
    public void firstLikeCreatesTheListAtTheTop() throws Exception
    {
        assertEquals(1, likes.like(FRED, song("a")).getCount());
        assertTrue(Files.isRegularFile(fileOf(FRED)));
        clock.set(T0.plusSeconds(60));
        assertEquals(2, likes.like(FRED, song("b")).getCount());

        PlaylistFile list = likes.get(FRED.getId()).get();
        assertEquals("newest first", Arrays.asList("b", "a"), keys(list.getSongs()));
        assertEquals(FRED.getId(), list.getName());
        assertEquals(FRED.getId(), list.getCreatedBy().getId());
        assertEquals("fred", list.getCreatedBy().getName());
        assertEquals(T0, list.getCreatedAt());
        assertEquals(T0.plusSeconds(60), list.getUpdatedAt());

        Song b = list.getSongs().get(0);
        assertTrue("a fresh id, not the caller's: " + b.getId(), b.getId().matches("[a-z0-9]{8}"));
        assertEquals("Title b", b.getTitle());
        assertEquals("Artist b", b.getAuthor());
        assertEquals(1000, b.getDurationMs());
        assertEquals("https://example.com/b.png", b.getArtworkUrl());
        assertEquals(FRED.getId(), b.getAddedBy().getId());
        assertEquals("fred", b.getAddedBy().getName());
        assertEquals(T0.plusSeconds(60), b.getAddedAt());
        assertEquals("the older like keeps its date", T0, list.getSongs().get(1).getAddedAt());

        assertEquals(new LinkedHashSet<>(Arrays.asList(uri("b"), uri("a"))), likes.uris(FRED.getId()));
        try(Stream<Path> files = Files.list(dir))
        {
            assertEquals("no temp files left behind", Collections.singletonList(FRED.getId() + ".json"),
                    files.map(p -> p.getFileName().toString()).collect(Collectors.toList()));
        }
    }

    @Test
    public void likingTwiceChangesNothing() throws Exception
    {
        likes.like(FRED, song("a"));
        likes.like(FRED, song("b"));
        byte[] before = Files.readAllBytes(fileOf(FRED));
        clock.set(T0.plusSeconds(60));

        Song again = song("a");
        again.setTitle("Another title for the same song");
        assertEquals(2, likes.like(FRED, again).getCount());

        assertArrayEquals("no write at all", before, Files.readAllBytes(fileOf(FRED)));
        assertEquals("and it stays where it was", Arrays.asList("b", "a"), listed(FRED));
        assertEquals("another person's like is their own", 1, likes.like(ANNA, song("a")).getCount());
    }

    @Test
    public void likeAndUnlikeSayWhetherTheyWrote() throws Exception
    {
        // what tells the panels about a change only when there is one
        assertTrue("the first like makes the list", likes.like(FRED, song("a")).isChanged());
        assertTrue(likes.like(FRED, song("b")).isChanged());
        LikesStore.Outcome again = likes.like(FRED, song("a"));
        assertFalse("already liked", again.isChanged());
        assertEquals(2, again.getCount());

        LikesStore.Outcome notLiked = likes.unlike(FRED, uri("zzz"));
        assertFalse("not liked", notLiked.isChanged());
        assertEquals(2, notLiked.getCount());
        LikesStore.Outcome noList = likes.unlike(ANNA, uri("a"));
        assertFalse("no list at all", noList.isChanged());
        assertEquals(0, noList.getCount());

        LikesStore.Outcome unliked = likes.unlike(FRED, uri("a"));
        assertTrue(unliked.isChanged());
        assertEquals(1, unliked.getCount());
    }

    @Test
    public void longUrisCompareAsTheyAreStored() throws Exception
    {
        String longUri = "https://example.com/watch?v=" + repeat("x", 400); // the store keeps 300 characters
        Song first = song("a");
        first.setUri(longUri);
        Song second = song("b");
        second.setUri(longUri);

        assertEquals(1, likes.like(FRED, first).getCount());
        assertEquals(1, likes.like(FRED, second).getCount());
        assertEquals(1, likes.uris(FRED.getId()).size());
        assertEquals("the same cut applies to the uri to unlike", 0, likes.unlike(FRED, longUri).getCount());
    }

    @Test
    public void unlikeRemovesEveryCopy() throws Exception
    {
        likes.like(FRED, song("a"));
        likes.like(FRED, song("b"));
        likes.like(FRED, song("c"));
        addRaw(FRED, song("a"), 0); // a copy made some other way: [a, c, b, a]
        assertEquals(Arrays.asList("a", "c", "b", "a"), listed(FRED));

        assertEquals(2, likes.unlike(FRED, uri("a")).getCount());
        assertEquals(Arrays.asList("c", "b"), listed(FRED));

        byte[] before = Files.readAllBytes(fileOf(FRED));
        clock.set(T0.plusSeconds(60));
        assertEquals("not liked: just the count", 2, likes.unlike(FRED, uri("zzz")).getCount());
        assertArrayEquals("no write", before, Files.readAllBytes(fileOf(FRED)));

        assertEquals("no list at all", 0, likes.unlike(ANNA, uri("a")).getCount());
        assertFalse("and none is made", Files.exists(fileOf(ANNA)));

        assertEquals(1, likes.unlike(FRED, uri("c")).getCount());
        assertEquals(0, likes.unlike(FRED, uri("b")).getCount());
        assertTrue("an emptied list stays, empty", likes.get(FRED.getId()).get().getSongs().isEmpty());
        assertTrue(likes.uris(FRED.getId()).isEmpty());
    }

    @Test
    public void ownerNameIsRefreshed() throws Exception
    {
        Person before = new Person("1", "old");
        Person after = new Person("1", "new");

        likes.like(before, song("a"));
        assertEquals("old", likes.get("1").get().getCreatedBy().getName());
        likes.like(after, song("b"));

        PlaylistFile list = likes.get("1").get();
        assertEquals("new", list.getCreatedBy().getName());
        assertEquals("1", list.getCreatedBy().getId());
    }

    @Test
    public void everyChangeRefreshesTheOwnerName() throws Exception
    {
        likes.like(new Person("1", "n0"), song("a"));
        likes.like(new Person("1", "n0"), song("b"));
        likes.like(new Person("1", "n0"), song("c")); // [c, b, a]

        likes.unlike(new Person("1", "n1"), uri("a"));
        assertEquals("n1", likes.get("1").get().getCreatedBy().getName());

        String idOfB = likes.get("1").get().getSongs().get(1).getId();
        PlaylistStore.Removed removed = likes.removeSong(new Person("1", "n2"), idOfB);
        assertEquals("n2", likes.get("1").get().getCreatedBy().getName());

        likes.reinsert(new Person("1", "n3"), removed.getSong(), removed.getIndex());
        assertEquals("n3", likes.get("1").get().getCreatedBy().getName());

        likes.move(new Person("1", "n4"), idOfB, 0);
        assertEquals("n4", likes.get("1").get().getCreatedBy().getName());

        assertEquals("a change that changes nothing writes nothing", 2, likes.unlike(new Person("1", "n5"), uri("zzz")).getCount());
        assertEquals("n4", likes.get("1").get().getCreatedBy().getName());
    }

    @Test
    public void fullListRefusesWithItsMessage() throws Exception
    {
        seed(FRED, 2000);
        byte[] before = Files.readAllBytes(fileOf(FRED));

        IllegalStateException full = assertThrows(IllegalStateException.class, () -> likes.like(FRED, song("new")));
        assertEquals(FULL, full.getMessage());
        assertEquals(FULL, LikesStore.FULL);
        assertArrayEquals("nothing was written", before, Files.readAllBytes(fileOf(FRED)));

        assertEquals("a song that is already in just answers", 2000, likes.like(FRED, song("s5")).getCount());
        IllegalStateException putBack = assertThrows(IllegalStateException.class, () -> likes.reinsert(FRED, song("old"), 0));
        assertEquals(FULL, putBack.getMessage());
        assertArrayEquals(before, Files.readAllBytes(fileOf(FRED)));

        assertEquals(1999, likes.unlike(FRED, uri("s5")).getCount());
        assertEquals("room again", 2000, likes.like(FRED, song("new")).getCount());
        assertEquals("new", listed(FRED).get(0));
    }

    @Test
    public void brokenFileIsKeptAndANewListStarts() throws Exception
    {
        Path file = fileOf(FRED);
        write(file, "{oops");

        assertFalse("a broken list reads as empty", likes.get(FRED.getId()).isPresent());
        assertTrue(likes.uris(FRED.getId()).isEmpty());
        assertTrue(likes.people().isEmpty());
        assertEquals("nothing to unlike in it, and nothing is touched", 0, likes.unlike(FRED, uri("a")).getCount());
        assertThrows(NoSuchElementException.class, () -> likes.removeSong(FRED, "abcdefgh"));
        assertThrows(NoSuchElementException.class, () -> likes.move(FRED, "abcdefgh", 0));
        assertThrows(NoSuchElementException.class, () -> likes.reinsert(FRED, song("a"), 0));
        assertEquals("{oops", read(file));
        assertTrue("the skipped file is warned about", warnings().stream().anyMatch(w -> w.contains(FRED.getId() + ".json")));

        assertEquals(1, likes.like(FRED, song("a")).getCount());
        assertEquals("the old file is kept", "{oops", read(dir.resolve(FRED.getId() + ".json.broken")));
        assertEquals(Collections.singletonList("a"), listed(FRED));
        assertEquals("fred", likes.get(FRED.getId()).get().getCreatedBy().getName());
        assertTrue("and the move is warned about", warnings().stream().anyMatch(w -> w.contains(".json.broken")));

        // broken again: the first copy is never overwritten, the new one takes the next free name
        write(file, "{oops again");
        assertEquals(1, likes.like(FRED, song("b")).getCount());
        assertEquals("{oops again", read(dir.resolve(FRED.getId() + ".json.broken-2")));
        assertEquals("{oops", read(dir.resolve(FRED.getId() + ".json.broken")));
        assertEquals(Collections.singletonList("b"), listed(FRED));
    }

    @Test
    public void peopleListsOnlyNonEmptyAndSkipsBroken() throws Exception
    {
        likes.like(BOB, song("b1"));
        likes.like(BOB, song("b2"));
        clock.set(T0.plusSeconds(10));
        likes.like(ANNA, song("a1"));
        likes.like(FRED, song("f1"));
        likes.unlike(FRED, uri("f1")); // an empty list is not a person with likes
        write(dir.resolve("444.json"), "{oops");
        write(dir.resolve("notes.json"), new PlaylistFile().toJson()); // not a user id
        write(dir.resolve("5.json.broken"), "{old");
        write(dir.resolve("777.json"), "{\"format\": 2, \"songs\": [{\"uri\": \"https://example.com/watch?v=n\", \"durationMs\": 5000}]}");
        log.list.clear();

        List<LikesStore.Liker> people = likes.people();

        assertEquals("by name, ignoring case; a list with no owner name shows its id",
                Arrays.asList("777", ANNA.getId(), BOB.getId()), people.stream().map(LikesStore.Liker::getId).collect(Collectors.toList()));
        LikesStore.Liker nameless = people.get(0);
        assertEquals("777", nameless.getName());
        assertEquals(1, nameless.getCount());
        assertEquals(5000, nameless.getDurationMs());
        LikesStore.Liker anna = people.get(1);
        assertEquals("anna", anna.getName());
        assertEquals(1, anna.getCount());
        assertEquals(2000, anna.getDurationMs());
        assertEquals(T0.plusSeconds(10), anna.getUpdatedAt());
        LikesStore.Liker bob = people.get(2);
        assertEquals("Bob", bob.getName());
        assertEquals(2, bob.getCount());
        assertEquals(4000, bob.getDurationMs());
        assertEquals(T0, bob.getUpdatedAt());

        List<String> warned = warnings();
        assertEquals("the broken list is warned about once: " + warned, 1, warned.size());
        assertTrue(warned.get(0).contains("444.json"));
        assertEquals("{oops", read(dir.resolve("444.json")));
    }

    @Test
    public void whoLikedExcludesTheAsker() throws Exception
    {
        likes.like(ANNA, song("u1"));
        likes.like(ANNA, song("u2"));
        likes.like(BOB, song("u1"));
        likes.like(FRED, song("u1"));
        write(dir.resolve("444.json"), "{oops");

        assertEquals("by name", Arrays.asList("anna", "Bob"), likes.whoLiked(uri("u1"), FRED.getId()));
        assertEquals(Collections.singletonList("anna"), likes.whoLiked(uri("u2"), FRED.getId()));
        assertTrue(likes.whoLiked(uri("nobody"), FRED.getId()).isEmpty());
        assertEquals(Arrays.asList("anna", "Bob", "fred"), likes.whoLiked(uri("u1"), null));
        assertEquals(Arrays.asList("anna", "Bob", "fred"), likes.whoLiked(uri("u1"), "5"));
    }

    @Test
    public void allUrisHasEveryLikeOfEveryReadableList() throws Exception
    {
        assertTrue("nothing liked yet", likes.allUris().isEmpty());
        likes.like(ANNA, song("u1"));
        likes.like(ANNA, song("u2"));
        likes.like(BOB, song("u1"));
        likes.like(FRED, song("u3"));
        likes.unlike(FRED, uri("u3"));
        likes.like(FRED, song("u4"));
        write(dir.resolve("444.json"), "{oops");
        log.list.clear();

        assertEquals(new HashSet<>(Arrays.asList(uri("u1"), uri("u2"), uri("u4"))), likes.allUris());
        assertEquals("each list is read once: the broken one is warned about once", 1, warnings().size());
    }

    @Test
    public void aBrokenListIsWarnedAboutOnceUntilItChanges() throws Exception
    {
        // who is asked at every song change by every panel: one broken file must not fill the log
        likes.like(ANNA, song("u1"));
        Path broken = dir.resolve("444.json");
        write(broken, "{oops");
        log.list.clear();

        for(int i = 0; i < 3; i++)
        {
            assertEquals(1, likes.people().size());
            assertEquals(Collections.singletonList("anna"), likes.whoLiked(uri("u1"), null));
            assertEquals(Collections.singleton(uri("u1")), likes.allUris());
            assertFalse(likes.get("444").isPresent());
        }
        List<String> warned = warnings();
        assertEquals("one broken file, one warning: " + warned, 1, warned.size());
        assertTrue(warned.get(0).contains("444.json"));

        // changed, and still broken: warned about again, once
        write(broken, "{oops, still");
        Files.setLastModifiedTime(broken, FileTime.from(Files.getLastModifiedTime(broken).toInstant().plusSeconds(60)));
        likes.people();
        likes.whoLiked(uri("u1"), null);
        assertEquals("the changed file is warned about once more: " + warnings(), 2, warnings().size());

        // a playlist store of its own still warns at every read, as it always did
        PlaylistStore plain = new PlaylistStore(dir, clock);
        plain.get("444", "");
        plain.get("444", "");
        assertEquals(4, warnings().size());
    }

    @Test
    public void everyoneMergesWithoutDuplicates() throws Exception
    {
        likes.like(ANNA, song("s1"));
        likes.like(ANNA, song("s2")); // [s2, s1]
        likes.like(BOB, song("s2"));
        likes.like(BOB, song("s3")); // [s3, s2]
        likes.like(FRED, song("s1"));
        likes.like(FRED, song("s4")); // [s4, s1]

        List<Song> all = likes.everyone(new Random(1), 2000);

        assertEquals(4, all.size());
        assertEquals("every uri once", new HashSet<>(Arrays.asList("s1", "s2", "s3", "s4")), new HashSet<>(keys(all)));
        Map<String, String> addedBy = new LinkedHashMap<>();
        for(Song song : all)
            addedBy.put(keys(Collections.singletonList(song)).get(0), song.getAddedBy().getId());
        assertEquals("the copy kept is the first one, in name order", ANNA.getId(), addedBy.get("s2"));
        assertEquals(ANNA.getId(), addedBy.get("s1"));
        assertEquals(BOB.getId(), addedBy.get("s3"));
        assertEquals(FRED.getId(), addedBy.get("s4"));

        for(int cap : new int[]{3, 1})
        {
            List<String> some = keys(likes.everyone(new Random(2), cap));
            assertEquals("cap " + cap, cap, some.size());
            assertEquals("cap " + cap + " without repeats", cap, new HashSet<>(some).size());
            assertTrue(new HashSet<>(Arrays.asList("s1", "s2", "s3", "s4")).containsAll(some));
        }
        assertTrue(likes.everyone(new Random(2), 0).isEmpty());
        assertTrue("a cap below zero is nothing", likes.everyone(new Random(2), -1).isEmpty());
        assertEquals("room to spare", 4, likes.everyone(new Random(2), 5000).size());
    }

    @Test
    public void everyoneIsShuffledWithTheRandomItIsGiven() throws Exception
    {
        for(int i = 0; i < 6; i++)
            likes.like(ANNA, song("a" + i));

        Set<List<String>> orders = new HashSet<>();
        for(int seed = 0; seed < 20; seed++)
            orders.add(keys(likes.everyone(new Random(seed), 2000)));

        assertTrue("different seeds, different orders: " + orders.size(), orders.size() > 1);
        assertNotEquals("not the order of the list", Arrays.asList("a5", "a4", "a3", "a2", "a1", "a0"), keys(likes.everyone(new Random(3), 2000)));
        assertEquals("same seed, same order", keys(likes.everyone(new Random(7), 2000)), keys(likes.everyone(new Random(7), 2000)));
    }

    @Test
    public void everyoneCutsAfterTheShuffleNotInNameOrder() throws Exception
    {
        for(int i = 0; i < 5; i++)
        {
            likes.like(ANNA, song("a" + i));
            likes.like(BOB, song("b" + i)); // "anna" comes before "Bob" in name order
        }

        Set<String> everSeen = new HashSet<>();
        boolean bobMadeTheCut = false;
        for(int seed = 0; seed < 20; seed++)
        {
            List<String> some = keys(likes.everyone(new Random(seed), 4)); // fewer than anna's five
            assertEquals(4, some.size());
            assertEquals("no repeats", 4, new HashSet<>(some).size());
            everSeen.addAll(some);
            for(String key : some)
                bobMadeTheCut |= key.startsWith("b");
        }
        assertTrue("songs of the last person in name order can make the cut", bobMadeTheCut);
        assertTrue("and it is not the same few songs every time: " + everSeen, everSeen.size() > 4);
    }

    @Test
    public void concurrentLikesOfOneSongMakeOneEntry() throws Exception
    {
        List<LikesStore.Outcome> outcomes = race(8, i -> likes.like(FRED, song("same")));

        assertEquals(Collections.nCopies(8, 1), outcomes.stream().map(LikesStore.Outcome::getCount).collect(Collectors.toList()));
        assertEquals("one of them wrote", 1, outcomes.stream().filter(LikesStore.Outcome::isChanged).count());
        assertEquals(Collections.singletonList("same"), listed(FRED));
    }

    @Test
    public void concurrentFirstLikesOfDifferentSongsAllLand() throws Exception
    {
        // none of them finds the list: they all race to make it
        race(8, i -> likes.like(FRED, song("s" + i)));

        assertEquals(8, listed(FRED).size());
        assertEquals(8, new HashSet<>(listed(FRED)).size());
    }

    @Test
    public void invalidUserIdsAreRefused() throws Exception
    {
        String[] bad = {"../x", "a", "", " ", "12 3", "-1", "1.5", "٣", repeat("1", 21), "1/2", "1\\2", null};
        for(String id : bad)
        {
            String shown = String.valueOf(id);
            Person person = new Person(id, "x");
            assertThrows("get " + shown, IllegalArgumentException.class, () -> likes.get(id));
            assertThrows("uris " + shown, IllegalArgumentException.class, () -> likes.uris(id));
            assertThrows("like " + shown, IllegalArgumentException.class, () -> likes.like(person, song("a")));
            assertThrows("unlike " + shown, IllegalArgumentException.class, () -> likes.unlike(person, uri("a")));
            assertThrows("removeSong " + shown, IllegalArgumentException.class, () -> likes.removeSong(person, "abcdefgh"));
            assertThrows("reinsert " + shown, IllegalArgumentException.class, () -> likes.reinsert(person, song("a"), 0));
            assertThrows("move " + shown, IllegalArgumentException.class, () -> likes.move(person, "abcdefgh", 0));
        }
        assertThrows(IllegalArgumentException.class, () -> likes.like(null, song("a")));
        assertThrows(IllegalArgumentException.class, () -> likes.like(FRED, null));
        assertThrows(IllegalArgumentException.class, () -> likes.like(FRED, new Song()));
        assertThrows(IllegalArgumentException.class, () -> likes.unlike(FRED, null));
        assertThrows(IllegalArgumentException.class, () -> likes.unlike(null, uri("a")));
        assertFalse("a refused id leaves nothing behind", Files.exists(dir));

        // 20 digits is the longest a Discord id can be
        Person longest = new Person(repeat("9", 20), "max");
        assertEquals(1, likes.like(longest, song("a")).getCount());
        assertTrue(Files.isRegularFile(dir.resolve(repeat("9", 20) + ".json")));
    }

    @Test
    public void removedSongComesBackExactly() throws Exception
    {
        for(String key : new String[]{"a", "b", "c", "d"})
        {
            likes.like(FRED, song(key));
            clock.set(clock.instant().plusSeconds(60));
        }
        Song c = likes.get(FRED.getId()).get().getSongs().get(1); // [d, c, b, a]

        PlaylistStore.Removed removed = likes.removeSong(FRED, c.getId());
        assertEquals(c.getId(), removed.getSong().getId());
        assertEquals(1, removed.getIndex());
        assertEquals(Arrays.asList("d", "b", "a"), keys(removed.getPlaylist().getSongs()));

        clock.set(T0.plusSeconds(3600));
        PlaylistFile back = likes.reinsert(FRED, removed.getSong(), removed.getIndex());

        assertEquals(Arrays.asList("d", "c", "b", "a"), keys(back.getSongs()));
        Song restored = back.getSongs().get(1);
        assertEquals("same id", c.getId(), restored.getId());
        assertEquals("same date", c.getAddedAt(), restored.getAddedAt());
        assertEquals(FRED.getId(), restored.getAddedBy().getId());
        assertEquals(back.getSongs().size(), likes.get(FRED.getId()).get().getSongs().size());

        assertThrows(NoSuchElementException.class, () -> likes.removeSong(FRED, "nope"));
    }

    @Test
    public void reinsertDoesNotDuplicateASongLikedAgainMeanwhile() throws Exception
    {
        likes.like(FRED, song("a"));
        likes.like(FRED, song("b")); // [b, a]
        PlaylistStore.Removed removed = likes.removeSong(FRED, likes.get(FRED.getId()).get().getSongs().get(1).getId());
        likes.like(FRED, song("a")); // liked again from somewhere else: [a, b]
        byte[] before = Files.readAllBytes(fileOf(FRED));

        PlaylistFile after = likes.reinsert(FRED, removed.getSong(), removed.getIndex());

        assertEquals("one entry per uri", Arrays.asList("a", "b"), keys(after.getSongs()));
        assertArrayEquals("and nothing is written", before, Files.readAllBytes(fileOf(FRED)));
    }

    @Test
    public void moveGoesToTheFinalPosition() throws Exception
    {
        for(String key : new String[]{"a", "b", "c", "d"})
            likes.like(FRED, song(key)); // [d, c, b, a]
        String idOfA = likes.get(FRED.getId()).get().getSongs().get(3).getId();

        assertEquals(Arrays.asList("a", "d", "c", "b"), keys(likes.move(FRED, idOfA, 0).getSongs()));
        assertEquals(Arrays.asList("d", "a", "c", "b"), keys(likes.move(FRED, idOfA, 1).getSongs()));
        assertEquals("past the end goes last", Arrays.asList("d", "c", "b", "a"), keys(likes.move(FRED, idOfA, 99).getSongs()));
        assertEquals("before the start goes first", Arrays.asList("a", "d", "c", "b"), keys(likes.move(FRED, idOfA, -5).getSongs()));
        assertEquals(Arrays.asList("a", "d", "c", "b"), listed(FRED));
        assertThrows(NoSuchElementException.class, () -> likes.move(FRED, "nope", 0));
    }

    @Test
    public void onlyTheOwnersListChanges() throws Exception
    {
        likes.like(FRED, song("a"));
        likes.like(ANNA, song("x"));
        String fredsSong = likes.get(FRED.getId()).get().getSongs().get(0).getId();
        byte[] fredBefore = Files.readAllBytes(fileOf(FRED));

        // Anna names Fred's song id: it is looked for in her own list only
        assertThrows(NoSuchElementException.class, () -> likes.removeSong(ANNA, fredsSong));
        assertThrows(NoSuchElementException.class, () -> likes.move(ANNA, fredsSong, 0));
        assertEquals(1, likes.unlike(ANNA, uri("a")).getCount());

        assertArrayEquals(Files.readAllBytes(fileOf(FRED)), fredBefore);
        assertEquals(Collections.singletonList("a"), listed(FRED));
        assertEquals(Collections.singletonList("x"), listed(ANNA));
    }

    /** Runs {@code action} on {@code threads} threads released at the same moment; the results in thread order. */
    private static <T> List<T> race(int threads, IndexedAction<T> action) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for(int i = 0; i < threads; i++)
            {
                int index = i;
                Callable<T> task = () ->
                {
                    ready.countDown();
                    go.await();
                    return action.run(index);
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for(Future<T> future : futures)
                results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    private interface IndexedAction<T>
    {
        T run(int index) throws Exception;
    }
}
