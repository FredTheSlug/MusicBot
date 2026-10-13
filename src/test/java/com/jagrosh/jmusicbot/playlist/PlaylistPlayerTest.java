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

import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer.LoadError;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer.Result;
import com.jagrosh.jmusicbot.playlist.PlaylistStore.Entry;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** The pure parts of playing a playlist: the order songs are queued in, finding a playlist by name, and how loads are collected. */
public class PlaylistPlayerTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- order

    private static Song song(String id, boolean unresolved)
    {
        Song song = new Song();
        song.setId(id);
        song.setUri("https://example.test/" + id);
        song.setTitle("Title " + id);
        song.setUnresolved(unresolved);
        return song;
    }

    private static PlaylistFile playlist(boolean shuffle, String... ids)
    {
        PlaylistFile file = new PlaylistFile();
        file.setShuffle(shuffle);
        for(String id : ids)
            file.getSongs().add(song(id, false));
        return file;
    }

    private static List<String> ids(List<Song> songs)
    {
        List<String> ids = new ArrayList<>();
        for(Song each : songs)
            ids.add(each.getId());
        return ids;
    }

    @Test
    public void orderAllShuffleFrom()
    {
        PlaylistFile plain = playlist(false, "a", "b", "c", "d", "e");
        PlaylistFile shuffled = playlist(true, "a", "b", "c", "d", "e");

        // all: the file's order; the Random is not needed
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), ids(PlaylistPlayer.order(plain, "all", null, new Random(42))));

        // shuffle: Collections.shuffle with the given Random (seed 42 turns 1..5 into 2 3 4 5 1)
        assertEquals(Arrays.asList("b", "c", "d", "e", "a"), ids(PlaylistPlayer.order(plain, "shuffle", null, new Random(42))));
        assertEquals("the song id means nothing to shuffle",
                Arrays.asList("b", "c", "d", "e", "a"), ids(PlaylistPlayer.order(plain, "shuffle", "c", new Random(42))));

        // the playlist's own shuffle flag shuffles "all" in the same way
        assertEquals(Arrays.asList("b", "c", "d", "e", "a"), ids(PlaylistPlayer.order(shuffled, "all", null, new Random(42))));
        assertEquals(Arrays.asList("b", "c", "d", "e", "a"), ids(PlaylistPlayer.order(shuffled, "shuffle", null, new Random(42))));

        // from: that song, the ones after it, then the ones before it
        assertEquals(Arrays.asList("c", "d", "e", "a", "b"), ids(PlaylistPlayer.order(plain, "from", "c", new Random(42))));
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), ids(PlaylistPlayer.order(plain, "from", "a", new Random(42))));
        assertEquals(Arrays.asList("e", "a", "b", "c", "d"), ids(PlaylistPlayer.order(plain, "from", "e", new Random(42))));

        // from with the shuffle flag on: the song stays first, the rest (d e a b) are shuffled (seed 42 turns 1..4 into 4 2 1 3)
        assertEquals(Arrays.asList("c", "b", "e", "d", "a"), ids(PlaylistPlayer.order(shuffled, "from", "c", new Random(42))));
    }

    @Test
    public void orderIsACopyAndLeavesThePlaylistAlone()
    {
        PlaylistFile file = playlist(true, "a", "b", "c");
        List<Song> ordered = PlaylistPlayer.order(file, "shuffle", null, new Random(7));
        ordered.clear();
        assertEquals(Arrays.asList("a", "b", "c"), ids(file.getSongs()));
    }

    @Test
    public void orderRefusesWhatItDoesNotKnow()
    {
        PlaylistFile file = playlist(false, "a", "b");
        try
        {
            PlaylistPlayer.order(file, "from", "zzzzzzzz", new Random(1));
            fail("an unknown song id must be refused");
        }
        catch(NoSuchElementException e)
        {
            assertEquals("Song not found", e.getMessage());
        }
        try
        {
            PlaylistPlayer.order(file, "from", null, new Random(1));
            fail("from needs a song id");
        }
        catch(NoSuchElementException e)
        {
            assertEquals("Song not found", e.getMessage());
        }
        for(String mode : new String[]{"backwards", "ALL", "", null})
        {
            try
            {
                PlaylistPlayer.order(file, mode, null, new Random(1));
                fail("mode " + mode + " must be refused");
            }
            catch(IllegalArgumentException e)
            {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Unknown play mode"));
            }
        }
    }

    @Test
    public void skipsUnresolved()
    {
        PlaylistFile file = new PlaylistFile();
        file.getSongs().add(song("a", false));
        file.getSongs().add(song("b", true));
        file.getSongs().add(song("c", false));
        Song noUri = song("d", false);
        noUri.setUri(null);
        file.getSongs().add(noUri);
        Song blankUri = song("e", false);
        blankUri.setUri("  ");
        file.getSongs().add(blankUri);
        file.getSongs().add(song("f", false));

        assertEquals(Arrays.asList("a", "c", "f"), ids(PlaylistPlayer.order(file, "all", null, new Random(1))));
        assertEquals("shuffled songs are still only the playable ones",
                3, PlaylistPlayer.order(file, "shuffle", null, new Random(1)).size());
        assertEquals(Arrays.asList("c", "f", "a"), ids(PlaylistPlayer.order(file, "from", "c", new Random(1))));

        // starting from a song that can't play starts at its place in the list
        assertEquals(Arrays.asList("c", "f", "a"), ids(PlaylistPlayer.order(file, "from", "b", new Random(1))));

        // with the shuffle flag on and a first song that can't play, all the playable ones are shuffled
        file.setShuffle(true);
        List<String> fromUnplayable = ids(PlaylistPlayer.order(file, "from", "b", new Random(1)));
        assertEquals(3, fromUnplayable.size());
        assertTrue(fromUnplayable.containsAll(Arrays.asList("a", "c", "f")));
    }

    @Test
    public void nothingPlayableGivesNothing()
    {
        PlaylistFile file = new PlaylistFile();
        assertTrue(PlaylistPlayer.order(file, "all", null, new Random(1)).isEmpty());
        assertFalse(PlaylistPlayer.hasPlayable(file));
        file.getSongs().add(song("a", true));
        assertTrue(PlaylistPlayer.order(file, "all", null, new Random(1)).isEmpty());
        assertFalse(PlaylistPlayer.hasPlayable(file));
        file.getSongs().add(song("b", false));
        assertTrue(PlaylistPlayer.hasPlayable(file));
    }

    @Test
    public void anUnconvertedPlaylistCountsAsPlayable()
    {
        // the legacy view marks every line unresolved, but the old loader plays every line
        PlaylistFile view = new PlaylistFile();
        view.setLegacy(true);
        view.getSongs().add(song("a", true));
        assertTrue(PlaylistPlayer.hasPlayable(view));
        view.getSongs().clear();
        assertFalse("an empty one has nothing to play", PlaylistPlayer.hasPlayable(view));
    }

    // ---- find

    private PlaylistStore store() throws Exception
    {
        return new PlaylistStore(tmp.getRoot().toPath().resolve("Playlists"), Clock.systemUTC());
    }

    private static String found(PlaylistStore store, String typed)
    {
        Optional<Entry> entry = store.find(typed);
        return entry.isPresent() ? entry.get().getPath() : null;
    }

    @Test
    public void findByFolderPathOrUniqueName() throws Exception
    {
        PlaylistStore store = store();
        store.create("Mix", "Rock", null);
        store.create("Mix", "Pop", null);
        store.create("Solo", "Rock", null);
        store.create("Top", "", null);
        store.create("Deep", "Rock/Sub", null);

        // a full path
        assertEquals("Rock/Mix", found(store, "Rock/Mix"));
        assertEquals("Pop/Mix", found(store, "Pop/Mix"));
        assertEquals("Rock/Sub/Deep", found(store, "Rock/Sub/Deep"));
        assertEquals("Top", found(store, "Top"));

        // a bare name that only one playlist has, wherever it is
        assertEquals("Rock/Solo", found(store, "Solo"));
        assertEquals("Rock/Sub/Deep", found(store, "Deep"));

        // a bare name that two playlists have: ambiguous
        assertNull(found(store, "Mix"));
        assertEquals(2, store.findAll("Mix").size());

        // not there
        assertNull(found(store, "Nope"));
        assertNull(found(store, "Rock/Nope"));
        assertNull(found(store, "Jazz/Mix"));
        assertNull("a folder is not a playlist", found(store, "Rock"));
        assertNull(found(store, "Rock/Sub"));
        assertEquals(0, store.findAll("Nope").size());

        // what comes back is the playlist's entry
        Entry entry = store.find("Rock/Solo").get();
        assertEquals("Solo", entry.getName());
        assertEquals("Rock", entry.getFolder());
        assertEquals(0, entry.getSongCount());
        assertFalse(entry.isLegacy());
    }

    @Test
    public void aBareNamePrefersThePlaylistAtTheTopLevel() throws Exception
    {
        PlaylistStore store = store();
        store.create("Mix", "", null);
        store.create("Mix", "Rock", null);
        store.create("Mix", "Jazz", null);
        store.create("Mix", "Rock/Sub", null);

        // the top level has no folder to type, so the bare name means it
        assertEquals("Mix", found(store, "Mix"));
        assertEquals(1, store.findAll("Mix").size());
        assertEquals("/Mix is the top level too", "Mix", found(store, "/Mix"));
        // the others are reached by their folder, as before
        assertEquals("Rock/Mix", found(store, "Rock/Mix"));
        assertEquals("Jazz/Mix", found(store, "Jazz/Mix"));
        assertEquals("Rock/Sub/Mix", found(store, "Rock/Sub/Mix"));

        // an unconverted .txt at the top level counts the same way
        Path root = tmp.getRoot().toPath().resolve("Playlists");
        Files.write(root.resolve("Old.txt"), "ytsearch:one\r\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("Rock"));
        Files.write(root.resolve("Rock").resolve("Old.txt"), "ytsearch:two\r\n".getBytes(StandardCharsets.UTF_8));
        Entry old = store.find("Old").get();
        assertEquals("", old.getFolder());
        assertTrue(old.isLegacy());
        assertEquals("Rock/Old", found(store, "Rock/Old"));
    }

    @Test
    public void aBareNameStillNeedsItsFolderWhenNoneIsAtTheTopLevel() throws Exception
    {
        PlaylistStore store = store();
        store.create("Mix", "Rock", null);
        store.create("Mix", "Jazz", null);
        store.create("Top", "", null);

        assertNull("two folders, none at the top level", found(store, "Mix"));
        assertEquals(2, store.findAll("Mix").size());
        // the Discord reply for that case asks for Folder/Name
        assertEquals(Arrays.asList("Jazz/Mix", "Rock/Mix"), paths(store.findAll("Mix")));

        // a loose match is no better off: the top level only wins an exact match
        store.create("Solo", "", null);
        store.create("solo", "Rock", null);
        assertEquals("Solo", found(store, "Solo"));
        assertEquals("solo", "Rock/solo", found(store, "solo"));
        assertNull("SOLO fits both loosely", found(store, "SOLO"));
    }

    private static List<String> paths(List<Entry> entries)
    {
        List<String> paths = new ArrayList<>();
        for(Entry each : entries)
            paths.add(each.getPath());
        Collections.sort(paths);
        return paths;
    }

    @Test
    public void findGivesBackEveryPlaylistOfTheTreeByItsPath() throws Exception
    {
        PlaylistStore store = store();
        store.create("Mix", "", null);
        store.create("Mix", "Rock", null);
        store.create("Mix", "Pop", null);
        store.create("Mix", "Rock/Sub", null);
        store.create("Solo", "Rock", null);
        store.create("Top", "", null);
        store.create("Beta", "A", null);
        store.create("beta", "B", null);
        Path root = tmp.getRoot().toPath().resolve("Playlists");
        Files.write(root.resolve("Old.txt"), "ytsearch:one\r\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("Rock").resolve("Old.txt"), "ytsearch:two\r\n".getBytes(StandardCharsets.UTF_8));

        List<Entry> tree = store.tree();
        assertEquals(10, tree.size());
        for(Entry each : tree)
        {
            Optional<Entry> back = store.find(each.getPath());
            assertTrue(each.getPath(), back.isPresent());
            assertEquals(each.getFolder(), back.get().getFolder());
            assertEquals(each.getName(), back.get().getName());
            assertEquals(each.isLegacy(), back.get().isLegacy());
        }
    }

    @Test
    public void findIgnoresCaseOnlyWhenNothingMatchesExactly() throws Exception
    {
        PlaylistStore store = store();
        store.create("Beta", "A", null);
        store.create("beta", "B", null);
        store.create("Solo", "Rock", null);
        store.create("Alpha", "A", null);
        store.create("alpha", "B", null);

        assertEquals("an exact match wins", "A/Beta", found(store, "Beta"));
        assertEquals("an exact match wins", "B/beta", found(store, "beta"));
        assertEquals("A/Beta", found(store, "A/Beta"));
        assertEquals("a loose match when it is the only one", "Rock/Solo", found(store, "solo"));
        assertEquals("Rock/Solo", found(store, "SOLO"));
        assertEquals("loose in the folder too", "Rock/Solo", found(store, "rock/SOLO"));
        assertNull("a loose match that fits two playlists is ambiguous", found(store, "ALPHA"));
        assertEquals(2, store.findAll("ALPHA").size());
        assertNull(found(store, "rock/alpha"));
    }

    @Test
    public void findSeesUnconvertedPlaylistsButNotDeletedOnes() throws Exception
    {
        PlaylistStore store = store();
        Path folder = tmp.getRoot().toPath().resolve("Playlists").resolve("Rock");
        Files.createDirectories(folder);
        Files.write(folder.resolve("Old.txt"), "ytsearch:one\r\nytsearch:two\r\n".getBytes(StandardCharsets.UTF_8));
        store.create("Gone", "Rock", null);
        store.delete("Gone", "Rock", null);

        Entry old = store.find("Old").get();
        assertTrue(old.isLegacy());
        assertEquals("Rock", old.getFolder());
        assertEquals(2, old.getSongCount());
        assertTrue(store.find("Rock/Old").isPresent());
        assertFalse(store.find("Gone").isPresent());
        assertFalse(store.find(".deleted/Gone").isPresent());
    }

    @Test
    public void findNeverThrowsForBadInput() throws Exception
    {
        PlaylistStore store = store();
        store.create("Mix", "Rock", null);
        String[] bad = {null, "", "   ", "/", "//", "Rock/", "/Nope", "../Mix", "Rock/../Mix", "..", ".", "a\0b",
            ".deleted/Mix", ".legacy-txt/Mix", "C:\\Windows\\x", "Rock\\Mix", "x/".repeat(500)};
        for(String typed : bad)
        {
            assertFalse("'" + typed + "'", store.find(typed).isPresent());
            assertTrue("'" + typed + "'", store.findAll(typed).isEmpty());
        }
        assertEquals("surrounding spaces are not part of the name", "Rock/Mix", found(store, "  Rock/Mix  "));
    }

    @Test
    public void findOnAStoreWithNoFolderYet() throws Exception
    {
        assertFalse(store().find("Anything").isPresent());
    }

    // ---- how loads are collected

    /** A track that only carries its details. */
    private static final class InfoTrack extends BaseAudioTrack
    {
        InfoTrack(String title)
        {
            super(new AudioTrackInfo(title, "Artist", 1000, title, false, "https://example.test/" + title, null, null));
        }

        @Override
        public void process(LocalAudioTrackExecutor executor)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static AudioTrack track(String title)
    {
        return new InfoTrack(title);
    }

    private static List<AudioTrack> tracks(String... titles)
    {
        List<AudioTrack> list = new ArrayList<>();
        for(String title : titles)
            list.add(track(title));
        return list;
    }

    private static List<String> titles(List<AudioTrack> tracks)
    {
        List<String> titles = new ArrayList<>();
        for(AudioTrack each : tracks)
            titles.add(each.getInfo().title);
        return titles;
    }

    @Test
    public void aSearchResultGivesItsFirstTrack()
    {
        AudioPlaylist search = new BasicAudioPlaylist("Search results for: x", tracks("t1", "t2", "t3"), null, true);
        assertEquals(Collections.singletonList("t1"), titles(PlaylistPlayer.tracksOf(search)));
    }

    @Test
    public void aPlaylistWithASelectedTrackGivesThatTrack()
    {
        List<AudioTrack> all = tracks("t1", "t2", "t3");
        AudioPlaylist mix = new BasicAudioPlaylist("A mix", all, all.get(1), false);
        assertEquals(Collections.singletonList("t2"), titles(PlaylistPlayer.tracksOf(mix)));
    }

    @Test
    public void anyOtherPlaylistGivesAllItsTracks()
    {
        AudioPlaylist album = new BasicAudioPlaylist("An album", tracks("t1", "t2", "t3"), null, false);
        assertEquals(Arrays.asList("t1", "t2", "t3"), titles(PlaylistPlayer.tracksOf(album)));
        assertTrue(PlaylistPlayer.tracksOf(new BasicAudioPlaylist("Empty", new ArrayList<>(), null, false)).isEmpty());
        assertTrue(PlaylistPlayer.tracksOf(new BasicAudioPlaylist("No hits", new ArrayList<>(), null, true)).isEmpty());
    }

    /** What a {@link PlaylistPlayer.LoadRun} reported. */
    private static final class Seen
    {
        final List<AudioTrack> queued = new ArrayList<>();
        final List<Result> finished = new ArrayList<>();
    }

    private static List<Song> songs(String... titles)
    {
        List<Song> songs = new ArrayList<>();
        for(String title : titles)
        {
            Song song = song("id" + title, false);
            song.setTitle(title);
            songs.add(song);
        }
        return songs;
    }

    private static PlaylistPlayer.LoadRun run(Seen seen, List<Song> songs, String tooLongTitle)
    {
        return new PlaylistPlayer.LoadRun(songs, track -> track.getInfo().title.equals(tooLongTitle),
                seen.queued::add, seen.finished::add);
    }

    private static List<String> errors(Result result)
    {
        List<String> errors = new ArrayList<>();
        for(LoadError each : result.getErrors())
            errors.add(each.getIndex() + "|" + each.getItem() + "|" + each.getReason());
        return errors;
    }

    @Test
    public void aRunQueuesTracksInOrderAndFinishesOnceAfterTheLastLoad()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("one", "two", "three"), null);

        run.handler(0).trackLoaded(track("one"));
        assertEquals(Collections.singletonList("one"), titles(seen.queued));
        assertTrue("not finished yet", seen.finished.isEmpty());

        run.handler(1).playlistLoaded(new BasicAudioPlaylist("Search results for: two", tracks("two-a", "two-b"), null, true));
        assertEquals(Arrays.asList("one", "two-a"), titles(seen.queued));
        assertTrue(seen.finished.isEmpty());

        run.handler(2).noMatches();
        assertEquals(1, seen.finished.size());
        Result result = seen.finished.get(0);
        assertEquals(2, result.getLoaded());
        assertEquals(3, result.getTotal());
        assertEquals(Collections.singletonList("2|three|No matches found."), errors(result));
    }

    @Test
    public void aRunAnswersAPlaylistLinkWithAllItsTracks()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("album"), null);
        run.handler(0).playlistLoaded(new BasicAudioPlaylist("An album", tracks("t1", "t2", "t3"), null, false));
        assertEquals(Arrays.asList("t1", "t2", "t3"), titles(seen.queued));
        assertEquals(1, seen.finished.size());
        assertEquals(3, seen.finished.get(0).getLoaded());
        assertEquals("one song was asked for", 1, seen.finished.get(0).getTotal());
        assertTrue(seen.finished.get(0).getErrors().isEmpty());
    }

    @Test
    public void aRunReportsAFailureAndAnEmptyPlaylistAsErrors()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("one", "two", "three"), null);
        run.handler(0).loadFailed(new FriendlyException("Video unavailable", FriendlyException.Severity.COMMON, null));
        run.handler(1).playlistLoaded(new BasicAudioPlaylist("Empty", new ArrayList<>(), null, false));
        run.handler(2).trackLoaded(track("three"));

        Result result = seen.finished.get(0);
        assertEquals(1, result.getLoaded());
        assertEquals(Arrays.asList("0|one|Failed to load track: Video unavailable", "1|two|No matches found."), errors(result));
    }

    @Test
    public void aRunSkipsTracksThatAreTooLong()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("one", "two"), "huge");
        run.handler(0).trackLoaded(track("huge"));
        run.handler(1).playlistLoaded(new BasicAudioPlaylist("An album", tracks("ok", "huge", "fine"), null, false));

        assertEquals(Arrays.asList("ok", "fine"), titles(seen.queued));
        Result result = seen.finished.get(0);
        assertEquals(2, result.getLoaded());
        assertEquals(Arrays.asList("0|huge|This track is longer than the allowed maximum",
                "1|huge|This track is longer than the allowed maximum"), errors(result));
    }

    @Test
    public void aRunFinishesEvenWhenQueueingATrackFails()
    {
        List<Result> finished = new ArrayList<>();
        PlaylistPlayer.LoadRun run = new PlaylistPlayer.LoadRun(songs("one", "two"), track -> false,
                track ->
                {
                    if(track.getInfo().title.equals("one"))
                        throw new IllegalStateException("queue is gone");
                },
                finished::add);
        run.handler(0).trackLoaded(track("one"));
        run.handler(1).trackLoaded(track("two"));

        assertEquals(1, finished.size());
        assertEquals(1, finished.get(0).getLoaded());
        assertEquals(Collections.singletonList("0|one|Could not queue it: queue is gone"), errors(finished.get(0)));
    }

    @Test
    public void aRunCountsEachSongOnce()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("one", "two"), null);
        AudioLoadResultHandler first = run.handler(0);
        first.trackLoaded(track("one"));
        first.trackLoaded(track("one-again"));
        first.noMatches();
        assertTrue("the second song is still out", seen.finished.isEmpty());
        assertEquals(Collections.singletonList("one"), titles(seen.queued));

        run.handler(1).noMatches();
        assertEquals(1, seen.finished.size());
        assertEquals(1, seen.finished.get(0).getLoaded());
        assertEquals(1, seen.finished.get(0).getErrors().size());
    }

    @Test
    public void aRunNamesASongByItsTitleOrElseItsUri()
    {
        Seen seen = new Seen();
        List<Song> songs = songs("one", "two");
        songs.get(1).setTitle("  ");
        PlaylistPlayer.LoadRun run = run(seen, songs, null);
        run.handler(0).noMatches();
        run.handler(1).noMatches();
        assertEquals(Arrays.asList("0|one|No matches found.", "1|" + songs.get(1).getUri() + "|No matches found."),
                errors(seen.finished.get(0)));
    }

    @Test
    public void aResultCannotBeChangedAfterwards()
    {
        Seen seen = new Seen();
        PlaylistPlayer.LoadRun run = run(seen, songs("one"), null);
        run.handler(0).noMatches();
        try
        {
            seen.finished.get(0).getErrors().clear();
            fail("the errors of a result are read only");
        }
        catch(UnsupportedOperationException expected)
        {
            // as intended
        }
    }
}
