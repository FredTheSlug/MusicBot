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
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class PlaylistConverterTest
{
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-09-01T12:00:00Z");
    private static final String ALBUM = "https://www.youtube.com/playlist?list=PLalbum";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private PlaylistStore store;
    private FakeResolver resolver;
    private PlaylistConverter converter;
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
        converter = new PlaylistConverter(store, resolver);

        // collect the converter's log lines instead of printing them
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PlaylistConverter.class);
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

    /** Answers from a map of line to result; a line it doesn't know fails; a line it was told to fail on throws. */
    private static final class FakeResolver implements TrackResolver
    {
        final Map<String, Resolved> answers = new HashMap<>();
        final Set<String> explode = new HashSet<>();
        final List<String> seen = new ArrayList<>();
        Consumer<String> onResolve = line -> { };
        boolean healthy = true;
        int healthChecks;

        @Override
        public boolean healthy()
        {
            healthChecks++;
            return healthy;
        }

        void answer(String line, Resolved resolved)
        {
            answers.put(line, resolved);
        }

        void answerOne(String line, String title)
        {
            answer(line, Resolved.ok(Collections.singletonList(song("https://example.test/" + title, title)), null));
        }

        @Override
        public Resolved resolve(String lineOrLink)
        {
            seen.add(lineOrLink);
            onResolve.accept(lineOrLink);
            if(explode.contains(lineOrLink))
                throw new RuntimeException("network down");
            Resolved resolved = answers.get(lineOrLink);
            return resolved != null ? resolved : Resolved.failed("No matches found");
        }
    }

    private static Song song(String uri, String title)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle(title);
        song.setAuthor("Artist");
        song.setDurationMs(1000);
        song.setArtworkUrl("https://img.test/" + title + ".jpg");
        return song;
    }

    private static List<Song> songs(String prefix, int count)
    {
        List<Song> list = new ArrayList<>();
        for(int i = 1; i <= count; i++)
            list.add(song("https://example.test/" + prefix + i, prefix + i));
        return list;
    }

    private Path txt(String folder, String name, String... lines) throws Exception
    {
        Path file = folder.isEmpty() ? root.resolve(name + ".txt") : root.resolve(folder).resolve(name + ".txt");
        Files.createDirectories(file.getParent());
        Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
        return file;
    }

    private static String read(Path file) throws Exception
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static List<String> titles(PlaylistFile file)
    {
        return file.getSongs().stream().map(Song::getTitle).collect(Collectors.toList());
    }

    private static void assertProgress(PlaylistConverter.Progress progress, int total, int done, int failed)
    {
        assertEquals("total", total, progress.getTotal());
        assertEquals("done", done, progress.getDone());
        assertEquals("failed", failed, progress.getFailed());
    }

    private List<String> logged(Level level)
    {
        return log.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    public void albumLinkExpandsToSongs() throws Exception
    {
        Path original = txt("Rock", "Chronic", ALBUM);
        String originalText = read(original);
        resolver.answer(ALBUM, Resolved.ok(Arrays.asList(song("https://www.youtube.com/watch?v=one", "One"),
                song("https://www.youtube.com/watch?v=two", "Two")), "The Album"));

        assertEquals(1, converter.convertPending());

        PlaylistFile file = store.get("Chronic", "Rock").get();
        assertFalse("now a real playlist, not the .txt view", file.isLegacy());
        assertEquals(Arrays.asList("One", "Two"), titles(file));
        assertEquals("https://www.youtube.com/watch?v=one", file.getSongs().get(0).getUri());
        assertEquals("Artist", file.getSongs().get(0).getAuthor());
        assertEquals(1000, file.getSongs().get(1).getDurationMs());
        assertEquals("https://img.test/Two.jpg", file.getSongs().get(1).getArtworkUrl());
        assertEquals("Chronic", file.getName());
        assertEquals(1, file.getVersion());
        assertNull(file.getCreatedBy());
        assertEquals("created when the .txt was last written", MODIFIED, file.getCreatedAt());
        assertEquals("updated now", NOW, file.getUpdatedAt());
        assertFalse(file.isShuffle());
        Set<String> ids = new HashSet<>();
        for(Song each : file.getSongs())
        {
            assertTrue("8 random characters: " + each.getId(), each.getId().matches("[a-z0-9]{8}"));
            assertTrue("unique", ids.add(each.getId()));
            assertNull(each.getAddedBy());
            assertEquals(MODIFIED, each.getAddedAt());
            assertFalse(each.isUnresolved());
        }

        Path json = root.resolve("Rock").resolve("Chronic.json");
        assertEquals(2, new JSONObject(read(json)).getInt("format"));
        assertFalse("the .txt left its place", Files.exists(original));
        Path kept = root.resolve(".legacy-txt").resolve("Rock").resolve("Chronic.txt");
        assertEquals("and was kept as it was", originalText, read(kept));
        assertEquals(Collections.singletonList("Rock|Chronic|false"),
                store.tree().stream().map(e -> e.getFolder() + "|" + e.getName() + "|" + e.isLegacy())
                        .collect(Collectors.toList()));
    }

    @Test
    public void failedLineBecomesUnresolved() throws Exception
    {
        String video = "https://www.youtube.com/watch?v=dead";
        txt("", "Mixed", ALBUM, "Dr Dre - Still", video, "ytsearch:Snoop", "weird");
        resolver.answer(ALBUM, Resolved.ok(songs("a", 2), "Album"));
        resolver.answer("weird", Resolved.ok(Collections.emptyList(), null)); // not an error, but nothing came back either

        assertEquals(1, converter.convertPending());

        PlaylistFile file = store.get("Mixed", "").get();
        assertEquals(Arrays.asList("a1", "a2", "Dr Dre - Still", video, "ytsearch:Snoop", "weird"), titles(file));
        assertEquals(Arrays.asList(false, false, true, true, true, true),
                file.getSongs().stream().map(Song::isUnresolved).collect(Collectors.toList()));
        assertEquals("a search line is retried as a search", "ytsearch:Dr Dre - Still", file.getSongs().get(2).getUri());
        assertEquals("a link is retried as that link", video, file.getSongs().get(3).getUri());
        assertEquals("a line that already searches is kept as written", "ytsearch:Snoop", file.getSongs().get(4).getUri());
        assertEquals("ytsearch:weird", file.getSongs().get(5).getUri());
        for(Song each : file.getSongs().subList(2, 6))
        {
            assertNull(each.getAuthor());
            assertEquals(0, each.getDurationMs());
            assertEquals(MODIFIED, each.getAddedAt());
            assertTrue(each.getId().matches("[a-z0-9]{8}"));
        }
    }

    @Test
    public void unresolvedSongsFromTheResolverAreKept() throws Exception
    {
        txt("", "Spotify", "https://open.spotify.com/album/x");
        List<Song> expanded = songs("s", 2);
        expanded.add(1, TrackResolver.unresolvedSong("Lost - Track", "ytsearch:Lost - Track"));
        resolver.answer("https://open.spotify.com/album/x", Resolved.ok(expanded, "Album"));

        assertEquals(1, converter.convertPending());

        PlaylistFile file = store.get("Spotify", "").get();
        assertEquals(Arrays.asList("s1", "Lost - Track", "s2"), titles(file));
        assertTrue(file.getSongs().get(1).isUnresolved());
        assertEquals("ytsearch:Lost - Track", file.getSongs().get(1).getUri());
        assertEquals(Collections.singletonList("Converted 'Spotify': 3 songs, 1 couldn't be loaded"), logged(Level.INFO));
    }

    @Test
    public void shuffleLineSetsFlag() throws Exception
    {
        txt("", "Mix", "# my favourites", "", "  l1  ", "#shuffle", "// another note", "l2", " // Shuffle ", "l3");
        resolver.answerOne("l1", "One");
        resolver.answerOne("l2", "Two");
        resolver.answerOne("l3", "Three");

        assertEquals(1, converter.convertPending());

        PlaylistFile file = store.get("Mix", "").get();
        assertTrue(file.isShuffle());
        assertEquals("the list itself is never shuffled", Arrays.asList("One", "Two", "Three"), titles(file));
        assertEquals("only real lines are looked up, trimmed", Arrays.asList("l1", "l2", "l3"), resolver.seen);
    }

    @Test
    public void emptyTxtBecomesEmptyPlaylist() throws Exception
    {
        txt("", "Empty", "#shuffle", "", "// nothing here");

        assertEquals(1, converter.convertPending());

        PlaylistFile file = store.get("Empty", "").get();
        assertFalse(file.isLegacy());
        assertTrue(file.getSongs().isEmpty());
        assertTrue(file.isShuffle());
        assertTrue(resolver.seen.isEmpty());
    }

    @Test
    public void rawLinesAreNotCutTo300() throws Exception
    {
        StringBuilder sb = new StringBuilder("https://example.test/");
        for(int i = 0; i < 400; i++)
            sb.append('a');
        String longLink = sb.toString();
        txt("", "Long", longLink);
        resolver.answerOne(longLink, "Found");

        assertEquals(1, converter.convertPending());

        assertEquals("the whole line is looked up, not the cut view of it", Collections.singletonList(longLink), resolver.seen);
        assertEquals(Collections.singletonList("Found"), titles(store.get("Long", "").get()));
    }

    @Test
    public void windowsFileWithByteOrderMarkReadsCleanly() throws Exception
    {
        Path file = root.resolve("Win.txt");
        Files.createDirectories(root);
        Files.write(file, ((char) 0xFEFF + "l1\r\n\r\nl2\r\n").getBytes(StandardCharsets.UTF_8));
        resolver.answerOne("l1", "One");
        resolver.answerOne("l2", "Two");

        assertEquals(1, converter.convertPending());

        assertEquals(Arrays.asList("l1", "l2"), resolver.seen);
        assertEquals(Arrays.asList("One", "Two"), titles(store.get("Win", "").get()));
    }

    @Test
    public void txtMovedOnlyAfterJsonWritten() throws Exception
    {
        Path original = txt("Rock", "Half", "one", "two", "three");
        resolver.answerOne("one", "One");
        resolver.answerOne("two", "Two");
        resolver.explode.add("two");

        assertEquals(0, converter.convertPending());

        assertTrue("the .txt is still in its place", Files.isRegularFile(original));
        assertEquals("one\ntwo\nthree", read(original));
        assertFalse("and nothing was written", Files.exists(root.resolve("Rock").resolve("Half.json")));
        assertFalse(Files.exists(root.resolve(".legacy-txt")));
        assertProgress(converter.progress(), 1, 0, 1);
        assertTrue("it keeps playing as the old view", store.get("Half", "Rock").get().isLegacy());
        assertEquals("one at a time, in order, and no more after the failure", Arrays.asList("one", "two"), resolver.seen);
    }

    @Test
    public void txtStaysWhenTheJsonCannotBeWritten() throws Exception
    {
        PlaylistStore diskFull = new PlaylistStore(root, Clock.fixed(NOW, ZoneOffset.UTC))
        {
            @Override
            void write(Path target, PlaylistFile file)
            {
                throw new UncheckedIOException("disk full", new IOException("no space left"));
            }
        };
        Path original = txt("Rock", "Full", "one");
        resolver.answerOne("one", "One");

        assertEquals(0, new PlaylistConverter(diskFull, resolver).convertPending());

        assertTrue("the .txt did not move", Files.isRegularFile(original));
        assertFalse(Files.exists(root.resolve("Rock").resolve("Full.json")));
        assertFalse(Files.exists(root.resolve(".legacy-txt")));
        assertEquals("and the next run converts it", 1, converter.convertPending());
        assertEquals(Collections.singletonList("One"), titles(store.get("Full", "Rock").get()));
    }

    @Test
    public void outageStopsTheRunAndLeavesEveryTxtAlone() throws Exception
    {
        Path a = txt("", "A", "a1", "a2");
        Path b = txt("Rock", "B", "b1");
        resolver.healthy = false; // every load fails: YouTube is not answering

        assertEquals(0, converter.convertPending());

        assertTrue("the .txt is still in its place", Files.isRegularFile(a));
        assertTrue(Files.isRegularFile(b));
        assertFalse(Files.exists(root.resolve("A.json")));
        assertFalse(Files.exists(root.resolve("Rock").resolve("B.json")));
        assertFalse(Files.exists(root.resolve(".legacy-txt")));
        assertTrue("both keep playing as before", store.get("A", "").get().isLegacy() && store.get("B", "Rock").get().isLegacy());
        assertProgress(converter.progress(), 2, 0, 1);
        assertEquals("gave up at the first failing line; the other playlist was not tried", Collections.singletonList("a1"), resolver.seen);
        assertEquals(1, resolver.healthChecks);
        assertEquals(Collections.singletonList("YouTube looks unreachable — playlist conversion paused, will retry"), logged(Level.WARN));

        resolver.healthy = true; // the next run, once YouTube is back
        resolver.answerOne("a1", "A1");
        resolver.answerOne("a2", "A2");
        resolver.answerOne("b1", "B1");
        assertEquals(2, converter.convertPending());
        assertEquals(Arrays.asList("A1", "A2"), titles(store.get("A", "").get()));
        assertEquals(Collections.singletonList("B1"), titles(store.get("B", "Rock").get()));
    }

    @Test
    public void deadLineWhileHealthyBecomesUnresolved() throws Exception
    {
        txt("", "Mix", "ok1", "dead", "ok2");
        resolver.answerOne("ok1", "One");
        resolver.answerOne("ok2", "Two");

        assertEquals(1, converter.convertPending());

        assertEquals("asked once, for the line that failed", 1, resolver.healthChecks);
        PlaylistFile file = store.get("Mix", "").get();
        assertEquals(Arrays.asList("One", "dead", "Two"), titles(file));
        assertEquals(Arrays.asList(false, true, false), file.getSongs().stream().map(Song::isUnresolved).collect(Collectors.toList()));
        assertEquals("ytsearch:dead", file.getSongs().get(1).getUri());
        assertTrue(logged(Level.WARN).isEmpty());
    }

    @Test
    public void collectionWhereEveryQueryFailedIsAFailedLineToo() throws Exception
    {
        String link = "https://open.spotify.com/album/x";
        Path original = txt("", "Sp", link);
        List<Song> allLost = Arrays.asList(TrackResolver.unresolvedSong("A - One", "ytsearch:A - One"),
                TrackResolver.unresolvedSong("A - Two", "ytsearch:A - Two"));
        resolver.answer(link, Resolved.ok(allLost, "Album"));

        resolver.healthy = false;
        assertEquals("an outage looks like this too", 0, converter.convertPending());
        assertTrue(Files.isRegularFile(original));
        assertProgress(converter.progress(), 1, 0, 1);

        resolver.healthy = true;
        assertEquals("YouTube is fine, so the songs really are missing and are kept as they are", 1, converter.convertPending());
        PlaylistFile file = store.get("Sp", "").get();
        assertEquals(Arrays.asList("A - One", "A - Two"), titles(file));
        assertEquals(Arrays.asList("ytsearch:A - One", "ytsearch:A - Two"),
                file.getSongs().stream().map(Song::getUri).collect(Collectors.toList()));
        assertTrue(file.getSongs().stream().allMatch(Song::isUnresolved));
    }

    @Test
    public void collectionWithSomeSongsLoadedIsNotChecked() throws Exception
    {
        String link = "https://open.spotify.com/album/x";
        txt("", "Sp", link);
        resolver.healthy = false; // would pause the run, if it were asked
        resolver.answer(link, Resolved.ok(Arrays.asList(song("https://example.test/a", "A"),
                TrackResolver.unresolvedSong("B", "ytsearch:B")), "Album"));

        assertEquals(1, converter.convertPending());

        assertEquals(0, resolver.healthChecks);
        assertEquals(Arrays.asList("A", "B"), titles(store.get("Sp", "").get()));
    }

    @Test
    public void localFileLineIsAResolvedSong() throws Exception
    {
        String path = "/home/cloutkings/music/Juice/Hollywood Dreams/Analog.m4a";
        txt("", "Local", path);
        Song loaded = song(path, "Analog");
        resolver.answer(path, Resolved.ok(Collections.singletonList(loaded), null));
        resolver.healthy = false; // a success never asks

        assertEquals(1, converter.convertPending());

        assertEquals("the whole path, untouched", Collections.singletonList(path), resolver.seen);
        assertEquals(0, resolver.healthChecks);
        Song song = store.get("Local", "").get().getSongs().get(0);
        assertFalse(song.isUnresolved());
        assertEquals(path, song.getUri());
        assertEquals("Analog", song.getTitle());
    }

    @Test
    public void missingLocalFileKeepsItsPath() throws Exception
    {
        String path = "/home/cloutkings/music/Gone/Missing (Live).mp3";
        txt("", "Local", path);

        assertEquals(1, converter.convertPending());

        Song song = store.get("Local", "").get().getSongs().get(0);
        assertTrue(song.isUnresolved());
        assertEquals(path, song.getTitle());
        assertEquals("retried as the file, not as a YouTube search", path, song.getUri());
    }

    @Test
    public void resolversAreHealthyUnlessTheySayOtherwise()
    {
        TrackResolver plain = line -> Resolved.failed("nope");

        assertTrue(plain.healthy());
    }

    @Test
    public void resumesAfterRestart() throws Exception
    {
        txt("", "A", "a1");
        txt("", "B", "b1");
        resolver.answerOne("a1", "FromA");
        resolver.explode.add("b1");

        assertEquals(1, converter.convertPending());
        assertProgress(converter.progress(), 2, 1, 1);
        assertEquals(Collections.singletonList("FromA"), titles(store.get("A", "").get()));
        assertTrue(store.get("B", "").get().isLegacy());

        FakeResolver after = new FakeResolver();
        after.answerOne("b1", "FromB");
        PlaylistConverter restarted = new PlaylistConverter(store, after);
        assertProgress(restarted.progress(), 0, 0, 0);

        assertEquals(1, restarted.convertPending());
        assertEquals("only the playlist that was left", Collections.singletonList("b1"), after.seen);
        assertProgress(restarted.progress(), 1, 1, 0);
        assertEquals(Collections.singletonList("FromB"), titles(store.get("B", "").get()));
        assertEquals(Collections.singletonList("FromA"), titles(store.get("A", "").get()));
        assertTrue(Files.isRegularFile(root.resolve(".legacy-txt").resolve("A.txt")));
        assertTrue(Files.isRegularFile(root.resolve(".legacy-txt").resolve("B.txt")));

        FakeResolver nothingLeft = new FakeResolver();
        assertEquals(0, new PlaylistConverter(store, nothingLeft).convertPending());
        assertTrue(nothingLeft.seen.isEmpty());
    }

    @Test
    public void progressCounts() throws Exception
    {
        assertProgress(converter.progress(), 0, 0, 0);
        txt("", "A", "a1");
        txt("", "B", "b1", "boom");
        txt("Dir", "C", "c1");
        resolver.answerOne("a1", "A1");
        resolver.answerOne("b1", "B1");
        resolver.answerOne("c1", "C1");
        resolver.explode.add("boom");
        List<PlaylistConverter.Progress> during = new ArrayList<>();
        resolver.onResolve = line -> during.add(converter.progress());

        assertEquals("returns what it converted", 2, converter.convertPending());

        assertEquals(4, during.size());
        assertProgress(during.get(0), 3, 0, 0); // a1
        assertProgress(during.get(1), 3, 1, 0); // b1
        assertProgress(during.get(2), 3, 1, 0); // boom
        assertProgress(during.get(3), 3, 1, 1); // c1
        PlaylistConverter.Progress snapshot = converter.progress();
        assertProgress(snapshot, 3, 2, 1);

        converter.convertPending(); // B is tried again and fails again: the counts restart for the new run
        assertProgress(converter.progress(), 1, 0, 1);
        assertProgress(snapshot, 3, 2, 1); // a snapshot already taken never changes
    }

    @Test
    public void nextRunStartsCountingAgain() throws Exception
    {
        txt("", "A", "a1");
        resolver.explode.add("a1");
        assertEquals(0, converter.convertPending());
        assertProgress(converter.progress(), 1, 0, 1);

        resolver.explode.clear();
        resolver.answerOne("a1", "A1");
        assertEquals(1, converter.convertPending());
        assertProgress(converter.progress(), 1, 1, 0);
    }

    @Test
    public void leftoverTxtBesideJsonIsMovedAside() throws Exception
    {
        store.create("Old", "Rock", null); // a crash after the .json was written but before the .txt moved
        Path leftover = txt("Rock", "Old", "x1");

        assertEquals(0, converter.convertPending());

        assertTrue("no network needed", resolver.seen.isEmpty());
        assertFalse(Files.exists(leftover));
        assertEquals("x1", read(root.resolve(".legacy-txt").resolve("Rock").resolve("Old.txt")));
        assertProgress(converter.progress(), 0, 0, 0);
        store.delete("Old", "Rock", null);
        assertFalse("deleting the playlist must not bring the old .txt back", store.get("Old", "Rock").isPresent());
        assertTrue(store.tree().isEmpty());
        assertEquals(0, new PlaylistConverter(store, resolver).convertPending());
        assertTrue(resolver.seen.isEmpty());
    }

    @Test
    public void legacyCopyNeverOverwritten() throws Exception
    {
        Path legacyDir = root.resolve(".legacy-txt");
        Files.createDirectories(legacyDir);
        Files.write(legacyDir.resolve("Name.txt"), "first".getBytes(StandardCharsets.UTF_8));
        Files.write(legacyDir.resolve("Name-2.txt"), "second".getBytes(StandardCharsets.UTF_8));
        txt("", "Name", "n1");
        resolver.answerOne("n1", "N1");

        assertEquals(1, converter.convertPending());

        assertEquals("first", read(legacyDir.resolve("Name.txt")));
        assertEquals("second", read(legacyDir.resolve("Name-2.txt")));
        assertEquals("n1", read(legacyDir.resolve("Name-3.txt")));
    }

    @Test
    public void existingJsonIsNeverOverwritten() throws Exception
    {
        Path original = txt("", "Raced", "r1");
        Path json = root.resolve("Raced.json");
        String theirs = "{\"format\": 2, \"name\": \"Raced\", \"version\": 5, \"songs\": []}";
        resolver.answerOne("r1", "R1");
        resolver.onResolve = line -> // someone else wrote the .json while the line was loading
        {
            try
            {
                Files.write(json, theirs.getBytes(StandardCharsets.UTF_8));
            }
            catch(IOException e)
            {
                throw new UncheckedIOException(e);
            }
        };

        assertEquals("nothing converted by this run", 0, converter.convertPending());

        assertEquals(theirs, read(json));
        assertFalse("the .txt must not come back later", Files.exists(original));
        assertTrue(Files.isRegularFile(root.resolve(".legacy-txt").resolve("Raced.txt")));
        assertProgress(converter.progress(), 1, 1, 0);
    }

    @Test
    public void skippedPlaylistsCountAsDone() throws Exception
    {
        txt("", "A", "a1");
        txt("", "B", "b1");
        txt("", "C", "c1");
        resolver.answerOne("a1", "A1");
        resolver.answerOne("b1", "B1");
        resolver.explode.add("c1");
        resolver.onResolve = line ->
        {
            if(!line.equals("b1"))
                return;
            try
            {
                Files.write(root.resolve("B.json"), "{\"format\": 2, \"name\": \"B\", \"songs\": []}".getBytes(StandardCharsets.UTF_8));
            }
            catch(IOException e)
            {
                throw new UncheckedIOException(e);
            }
        };

        assertEquals("only A was converted by this run", 1, converter.convertPending());

        PlaylistConverter.Progress progress = converter.progress();
        assertProgress(progress, 3, 2, 1);
        assertEquals("a finished run accounts for every playlist", progress.getTotal(), progress.getDone() + progress.getFailed());
    }

    @Test
    public void txtThatVanishedIsSkipped() throws Exception
    {
        Path original = txt("", "Gone", "g1");
        resolver.answerOne("g1", "G1");
        resolver.onResolve = line ->
        {
            try
            {
                Files.delete(original);
            }
            catch(IOException e)
            {
                throw new UncheckedIOException(e);
            }
        };

        assertEquals(0, converter.convertPending());

        assertFalse(Files.exists(root.resolve("Gone.json")));
        assertFalse(Files.exists(root.resolve(".legacy-txt").resolve("Gone.txt")));
        assertProgress(converter.progress(), 1, 1, 0);
    }

    @Test
    public void capsAt2000() throws Exception
    {
        txt("", "Big", "big", "more", "never");
        resolver.answer("big", Resolved.ok(songs("b", 1500), "Big"));
        resolver.answer("more", Resolved.ok(songs("m", 800), "More"));
        txt("", "Huge", "huge");
        resolver.answer("huge", Resolved.ok(songs("h", 2500), "Huge"));

        assertEquals(2, converter.convertPending());

        PlaylistFile big = store.get("Big", "").get();
        assertEquals(2000, big.getSongs().size());
        assertEquals("b1", big.getSongs().get(0).getTitle());
        assertEquals("m500", big.getSongs().get(1999).getTitle());
        assertEquals("it stops asking once it has 2000", Arrays.asList("big", "more", "huge"), resolver.seen);
        PlaylistFile huge = store.get("Huge", "").get();
        assertEquals(2000, huge.getSongs().size());
        assertEquals("h2000", huge.getSongs().get(1999).getTitle());
    }

    @Test
    public void logsOneLinePerPlaylist() throws Exception
    {
        txt("Rock", "Chronic", "c1", "c2");
        txt("", "Odd", "o1", "o2");
        txt("", "Broken", "boom");
        resolver.answerOne("c1", "C1");
        resolver.answerOne("c2", "C2");
        resolver.answerOne("o1", "O1");
        resolver.explode.add("boom");

        converter.convertPending();

        assertEquals(Arrays.asList("Converted 'Odd': 2 songs, 1 couldn't be loaded",
                "Converted 'Rock/Chronic': 2 songs"), logged(Level.INFO).stream().sorted().collect(Collectors.toList()));
        List<ILoggingEvent> warnings = log.list.stream().filter(e -> e.getLevel() == Level.WARN).collect(Collectors.toList());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).getFormattedMessage().contains("'Broken'"));
        assertNotNull("the exception is logged with it", warnings.get(0).getThrowableProxy());
        assertEquals("network down", warnings.get(0).getThrowableProxy().getMessage());
    }
}
