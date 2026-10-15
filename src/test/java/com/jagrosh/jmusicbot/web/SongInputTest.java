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

import com.jagrosh.jmusicbot.playlist.LikesStore;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** The rules for what a web client may put in a playlist or its likes, as {@link SongInput} holds them. */
public class SongInputTest
{
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"), ZoneOffset.UTC);
    private static final Person ANNA = new Person("1", "anna");
    private static final String LOCAL = "/home/cloutkings/music/Artist/song.m4a";
    private static final String REFUSED = "That song can't be taken";
    private static final Predicate<String> NOTHING_STORED = uri -> false;
    /** For the links that must never make anyone read the stores. */
    private static final Predicate<String> MUST_NOT_ASK = uri ->
    {
        throw new AssertionError("looked up " + uri);
    };

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void webLinksAndSearchesAreAllowedWithoutLookingAnythingUp()
    {
        String[] allowed = {"https://youtu.be/x", "HTTP://example.com/a.mp3", "http://example.com:8080/path?q=1#frag",
                "ytsearch:lofi beats", "ytmsearch:song name", "scsearch:remix"};
        for(String uri : allowed)
            assertTrue(uri, SongInput.allowed(uri, MUST_NOT_ASK));
    }

    @Test
    public void everythingElseIsRefusedUnlessAlreadyStored()
    {
        String[] refused = {"file:///etc/passwd", "/etc/passwd", "C:\\Windows\\win.ini", "../../secret.m4a", "javascript:alert(1)",
                "ftp://example.com/a.mp3", "http://", "https://", "https:///nohost", "http://example.com/a b", "data:text/html,hi",
                "ytsearch:", "ytsearch:   ", "  ", "", "YTSEARCH-nope", LOCAL};
        for(String uri : refused)
            assertFalse(uri, SongInput.allowed(uri, NOTHING_STORED));

        Predicate<String> localStored = LOCAL::equals;
        assertTrue("a local file that is already stored", SongInput.allowed(LOCAL, localStored));
        for(String nearMiss : new String[]{LOCAL + "x", LOCAL.toUpperCase(), "/home/cloutkings/music/Artist/other.m4a", "/etc/passwd"})
            assertFalse(nearMiss, SongInput.allowed(nearMiss, localStored));
    }

    @Test
    public void controlCharactersAreRefusedEvenWhenStored()
    {
        Predicate<String> everythingStored = uri -> true;
        for(String uri : new String[]{"ytsearch:line\nbreak", "https://example.com/a\u0000b", LOCAL + "\r", "\u001b" + LOCAL, "\u0085scsearch:x"})
            assertFalse(uri, SongInput.allowed(uri, everythingStored));
    }

    @Test
    public void aSongIsCleaned()
    {
        JSONObject json = new JSONObject().put("uri", "  https://example.com/a  ").put("title", "Title").put("author", "Artist")
                .put("durationMs", 215000).put("artworkUrl", " https://example.com/a.png ")
                .put("id", "forgedid1").put("addedBy", new JSONObject().put("id", "666").put("name", "mallory"))
                .put("addedAt", "2001-01-01T00:00:00Z").put("unresolved", true);
        Song song = SongInput.song(json, MUST_NOT_ASK, REFUSED);
        assertEquals("https://example.com/a", song.getUri());
        assertEquals("Title", song.getTitle());
        assertEquals("Artist", song.getAuthor());
        assertEquals(215000, song.getDurationMs());
        assertEquals("https://example.com/a.png", song.getArtworkUrl());
        assertNull("the store gives the id", song.getId());
        assertNull("and says who added it", song.getAddedBy());
        assertNull("and when", song.getAddedAt());
        assertFalse("a client can't mark a song broken", song.isUnresolved());

        Song bare = SongInput.song(new JSONObject().put("uri", "https://example.com/bare"), MUST_NOT_ASK, REFUSED);
        assertEquals("a song with no title is shown by its link", "https://example.com/bare", bare.getTitle());
        assertNull(bare.getAuthor());
        assertEquals(0, bare.getDurationMs());
        assertNull(bare.getArtworkUrl());

        assertEquals("a negative length is none", 0, songWith("durationMs", -5).getDurationMs());
        assertEquals("so is one that is no number", 0, songWith("durationMs", "soon").getDurationMs());
        assertEquals("a blank title is shown by the link", "https://example.com/x", songWith("title", "   ").getTitle());
        assertNull("an author that is no text is left out", songWith("author", 5).getAuthor());
        StringBuilder longTitle = new StringBuilder();
        for(int i = 0; i < 400; i++)
            longTitle.append('t');
        assertEquals("text is cut", PlaylistFile.MAX_TEXT, songWith("title", longTitle.toString()).getTitle().length());
    }

    @Test
    public void artworkIsKeptOnlyWhenItIsAWebLink()
    {
        assertNull("javascript: is dropped", songWith("artworkUrl", "javascript:alert(1)").getArtworkUrl());
        assertNull("so is data:", songWith("artworkUrl", "data:image/png;base64,AAAA").getArtworkUrl());
        assertNull("and a file", songWith("artworkUrl", "file:///etc/passwd").getArtworkUrl());
        assertNull("and a link with a line break", songWith("artworkUrl", "https://example.com/a\n.png").getArtworkUrl());

        // a YouTube video with no artwork, or a refused one, gets its standard thumbnail
        String video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        String thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg";
        assertEquals(thumbnail, SongInput.song(new JSONObject().put("uri", video), MUST_NOT_ASK, REFUSED).getArtworkUrl());
        assertEquals(thumbnail, SongInput.song(new JSONObject().put("uri", video).put("artworkUrl", "javascript:alert(1)"),
                MUST_NOT_ASK, REFUSED).getArtworkUrl());
        assertEquals("the client's own artwork wins", "https://example.com/own.png",
                SongInput.song(new JSONObject().put("uri", video).put("artworkUrl", "https://example.com/own.png"), MUST_NOT_ASK, REFUSED)
                        .getArtworkUrl());
        assertNull("a search has none", SongInput.song(new JSONObject().put("uri", "ytsearch:Dr Dre - Still"), MUST_NOT_ASK, REFUSED)
                .getArtworkUrl());
    }

    @Test
    public void aRefusedSongThrowsTheGivenMessage()
    {
        for(JSONObject json : new JSONObject[]{new JSONObject().put("uri", "file:///etc/passwd"), new JSONObject().put("uri", "/etc/passwd"),
                new JSONObject().put("uri", LOCAL), new JSONObject().put("uri", 5), new JSONObject().put("title", "no uri"),
                new JSONObject().put("uri", "ytsearch:a\nb")})
        {
            try
            {
                SongInput.song(json, NOTHING_STORED, REFUSED);
                fail("took " + json);
            }
            catch(IllegalArgumentException e)
            {
                assertEquals(REFUSED, e.getMessage());
            }
        }
        assertEquals("a local file that is stored is taken", LOCAL, SongInput.song(new JSONObject().put("uri", LOCAL), LOCAL::equals, REFUSED).getUri());
    }

    @Test
    public void storedInReadsEachStoreOnceAndOnlyWhenAsked()
    {
        CountingStore playlists = new CountingStore(tmp.getRoot().toPath().resolve("Playlists"));
        playlists.create("Library", "Rock", ANNA);
        StringBuilder longPath = new StringBuilder("/home/cloutkings/music/");
        while(longPath.length() < 400)
            longPath.append('x');
        playlists.addSongs("Library", "Rock", List.of(song(LOCAL), song(longPath.toString())), null, ANNA);
        playlists.create("Gone", "", ANNA);
        playlists.addSongs("Gone", "", List.of(song("/home/cloutkings/music/gone.m4a")), null, ANNA);
        playlists.delete("Gone", "", ANNA);
        AtomicInteger likesReads = new AtomicInteger();
        Supplier<Set<String>> likedUris = () ->
        {
            likesReads.incrementAndGet();
            return new HashSet<>(List.of("/home/cloutkings/music/liked.m4a"));
        };

        Predicate<String> stored = SongInput.storedIn(playlists, likedUris);
        assertEquals("nothing is read before it is asked", 0, playlists.reads.get() + likesReads.get());
        assertTrue(SongInput.allowed("https://example.com/a", stored));
        assertTrue(SongInput.allowed("ytsearch:lofi", stored));
        assertEquals("a link or a search reads nothing", 0, playlists.reads.get() + likesReads.get());

        assertTrue("in a playlist", stored.test(LOCAL));
        assertTrue("compared as it was stored (cut)", stored.test(longPath.toString()));
        assertEquals("the likes are not read for what a playlist has", 0, likesReads.get());
        assertTrue("in someone's likes", stored.test("/home/cloutkings/music/liked.m4a"));
        assertFalse("a deleted playlist's songs don't count", stored.test("/home/cloutkings/music/gone.m4a"));
        for(int i = 0; i < 1000; i++)
            assertFalse(stored.test("/home/cloutkings/music/made-up-" + i + ".m4a"));
        assertEquals("the playlists are read once", 1, playlists.reads.get());
        assertEquals("and the likes once, however many uris are asked about", 1, likesReads.get());

        assertFalse("a fresh one reads again", SongInput.storedIn(playlists, likedUris).test("/nope"));
        assertEquals(2, playlists.reads.get());
        assertEquals(2, likesReads.get());
    }

    @Test
    public void storedInSeesEveryonesLikes()
    {
        Path root = tmp.getRoot().toPath().resolve("Playlists");
        PlaylistStore playlists = new PlaylistStore(root, CLOCK);
        LikesStore likes = new LikesStore(root.resolve(".liked"), CLOCK);
        assertFalse("nobody has liked anything yet", SongInput.storedIn(playlists, likes::allUris).test(LOCAL));
        likes.like(ANNA, song(LOCAL));
        likes.like(new Person("2", "fred"), song("/home/cloutkings/music/fred.m4a"));
        Predicate<String> stored = SongInput.storedIn(playlists, likes::allUris);
        assertTrue(stored.test(LOCAL));
        assertTrue(stored.test("/home/cloutkings/music/fred.m4a"));
        assertFalse(stored.test("/home/cloutkings/music/other.m4a"));
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private static Song songWith(String key, Object value)
    {
        return SongInput.song(new JSONObject().put("uri", "https://example.com/x").put("title", "x").put(key, value), MUST_NOT_ASK, REFUSED);
    }

    private static Song song(String uri)
    {
        Song song = new Song();
        song.setUri(uri);
        song.setTitle(uri);
        return song;
    }

    /** A playlist store that counts how often the whole tree is read. */
    private static final class CountingStore extends PlaylistStore
    {
        final AtomicInteger reads = new AtomicInteger();

        CountingStore(Path root)
        {
            super(root, CLOCK);
        }

        @Override
        public List<Entry> tree()
        {
            reads.incrementAndGet();
            return super.tree();
        }
    }
}
