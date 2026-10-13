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

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistFileTest
{
    private static String repeat(char c, int count)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < count; i++)
            sb.append(c);
        return sb.toString();
    }

    private static String playlistWithSongs(JSONArray songs)
    {
        return new JSONObject().put("format", 2).put("name", "n").put("songs", songs).toString();
    }

    @Test
    public void roundTrip()
    {
        PlaylistFile file = new PlaylistFile();
        file.setName("The Chronic");
        file.setVersion(17);
        file.setCreatedBy(new PlaylistFile.Person("233362518045753345", "fred"));
        file.setCreatedAt(Instant.parse("2026-10-13T18:00:00Z"));
        file.setUpdatedAt(Instant.parse("2026-10-13T18:05:00Z"));
        file.setShuffle(true);

        PlaylistFile.Song resolved = new PlaylistFile.Song();
        resolved.setId("s7f3k2q9");
        resolved.setUri("https://www.youtube.com/watch?v=abc");
        resolved.setTitle("Nuthin' But A G Thang");
        resolved.setAuthor("Dr. Dre");
        resolved.setDurationMs(238000L);
        resolved.setArtworkUrl("https://i.ytimg.com/vi/abc/hqdefault.jpg");
        resolved.setAddedBy(new PlaylistFile.Person("42", "snoop"));
        resolved.setAddedAt(Instant.parse("2026-10-13T18:01:00Z"));
        file.getSongs().add(resolved);

        PlaylistFile.Song unresolved = new PlaylistFile.Song();
        unresolved.setId("u1u2u3u4");
        unresolved.setUri("ytsearch:some old line");
        unresolved.setTitle("some old line");
        unresolved.setUnresolved(true);
        file.getSongs().add(unresolved);

        String json = file.toJson();
        JSONObject raw = new JSONObject(json);
        assertEquals(2, raw.getInt("format"));
        assertEquals("2026-10-13T18:00:00Z", raw.getString("createdAt"));
        assertEquals("fred", raw.getJSONObject("createdBy").getString("name"));
        assertEquals(238000L, raw.getJSONArray("songs").getJSONObject(0).getLong("durationMs"));
        assertTrue(json.contains("\n  \"format\""));

        PlaylistFile back = PlaylistFile.fromJson(json);
        assertEquals("The Chronic", back.getName());
        assertEquals(17L, back.getVersion());
        assertEquals("233362518045753345", back.getCreatedBy().getId());
        assertEquals("fred", back.getCreatedBy().getName());
        assertEquals(Instant.parse("2026-10-13T18:00:00Z"), back.getCreatedAt());
        assertEquals(Instant.parse("2026-10-13T18:05:00Z"), back.getUpdatedAt());
        assertTrue(back.isShuffle());
        assertEquals(2, back.getSongs().size());

        PlaylistFile.Song first = back.getSongs().get(0);
        assertEquals("s7f3k2q9", first.getId());
        assertEquals("https://www.youtube.com/watch?v=abc", first.getUri());
        assertEquals("Nuthin' But A G Thang", first.getTitle());
        assertEquals("Dr. Dre", first.getAuthor());
        assertEquals(238000L, first.getDurationMs());
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", first.getArtworkUrl());
        assertEquals("42", first.getAddedBy().getId());
        assertEquals("snoop", first.getAddedBy().getName());
        assertEquals(Instant.parse("2026-10-13T18:01:00Z"), first.getAddedAt());
        assertFalse(first.isUnresolved());

        PlaylistFile.Song second = back.getSongs().get(1);
        assertEquals("u1u2u3u4", second.getId());
        assertEquals("ytsearch:some old line", second.getUri());
        assertNull(second.getAuthor());
        assertNull(second.getAddedBy());
        assertNull(second.getAddedAt());
        assertTrue(second.isUnresolved());
    }

    @Test
    public void capsAndDefaults()
    {
        PlaylistFile minimal = PlaylistFile.fromJson("{\"format\":2,\"songs\":[{\"uri\":\"u\"}]}");
        assertNull(minimal.getName());
        assertEquals(0L, minimal.getVersion());
        assertNull(minimal.getCreatedBy());
        assertNull(minimal.getCreatedAt());
        assertNull(minimal.getUpdatedAt());
        assertFalse(minimal.isShuffle());
        PlaylistFile.Song song = minimal.getSongs().get(0);
        assertEquals("u", song.getUri());
        assertNull(song.getTitle());
        assertNull(song.getAuthor());
        assertEquals(0L, song.getDurationMs());
        assertNull(song.getArtworkUrl());
        assertNull(song.getAddedBy());
        assertNull(song.getAddedAt());
        assertFalse(song.isUnresolved());
        assertTrue(PlaylistFile.fromJson("{\"format\":2}").getSongs().isEmpty());

        String long400 = repeat('t', 400);
        String json = new JSONObject().put("format", 2).put("name", long400)
                .put("createdBy", new JSONObject().put("id", "1").put("name", long400))
                .put("songs", new JSONArray().put(new JSONObject().put("uri", long400).put("title", long400)
                        .put("author", long400).put("artworkUrl", long400))).toString();
        PlaylistFile capped = PlaylistFile.fromJson(json);
        assertEquals(repeat('t', 300), capped.getName());
        assertEquals(repeat('t', 300), capped.getCreatedBy().getName());
        PlaylistFile.Song cappedSong = capped.getSongs().get(0);
        assertEquals(repeat('t', 300), cappedSong.getUri());
        assertEquals(repeat('t', 300), cappedSong.getTitle());
        assertEquals(repeat('t', 300), cappedSong.getAuthor());
        assertEquals(repeat('t', 300), cappedSong.getArtworkUrl());

        JSONArray many = new JSONArray();
        for(int i = 0; i < 2005; i++)
            many.put(new JSONObject().put("uri", "u" + i));
        PlaylistFile big = PlaylistFile.fromJson(playlistWithSongs(many));
        assertEquals(2000, big.getSongs().size());
        assertEquals("u0", big.getSongs().get(0).getUri());
        assertEquals("u1999", big.getSongs().get(1999).getUri());
    }

    @Test
    public void settersCutText()
    {
        String long400 = repeat('x', 400);
        PlaylistFile file = new PlaylistFile();
        file.setName(long400);
        assertEquals(300, file.getName().length());
        PlaylistFile.Song song = new PlaylistFile.Song();
        song.setUri(long400);
        song.setTitle(long400);
        song.setAuthor(long400);
        song.setArtworkUrl(long400);
        assertEquals(300, song.getUri().length());
        assertEquals(300, song.getTitle().length());
        assertEquals(300, song.getAuthor().length());
        assertEquals(300, song.getArtworkUrl().length());
        PlaylistFile.Person person = new PlaylistFile.Person(long400, long400);
        assertEquals(300, person.getName().length());

        assertNull(PlaylistFile.cut(null));
        assertEquals("short", PlaylistFile.cut("short"));
        assertEquals(repeat('x', 300), PlaylistFile.cut(repeat('x', 300)));
        assertEquals(repeat('x', 300), PlaylistFile.cut(repeat('x', 301)));
        // an emoji (two chars) straddling the limit is dropped whole rather than split in half
        assertEquals(repeat('x', 299), PlaylistFile.cut(repeat('x', 299) + "😀 and more"));
    }

    @Test
    public void rejectsJunk()
    {
        String[] junk = {"x", "\"x\"", "[]", "", "{\"format\":1}", "{\"format\":3,\"songs\":[]}", "{}", "{\"name\":\"n\"}"};
        for(String text : junk)
        {
            try
            {
                PlaylistFile.fromJson(text);
                fail("accepted: " + text);
            }
            catch(IllegalArgumentException expected)
            {
            }
        }
        try
        {
            PlaylistFile.fromJson(null);
            fail("accepted null");
        }
        catch(IllegalArgumentException expected)
        {
        }
    }

    @Test
    public void songIdsAreEightLowercaseAlnum()
    {
        Random random = new Random(7);
        Set<String> seen = new HashSet<>();
        for(int i = 0; i < 500; i++)
        {
            String id = PlaylistFile.newSongId(random);
            assertTrue(id, id.matches("[a-z0-9]{8}"));
            seen.add(id);
        }
        assertTrue(seen.size() > 495);
    }

    /** Draws 0 for the first 8 characters asked for (the id "aaaaaaaa"), 1 for the next 8 ("bbbbbbbb"), and so on. */
    private static final class CountingRandom extends Random
    {
        private int asked;

        @Override
        public int nextInt(int bound)
        {
            return asked++ / 8;
        }
    }

    @Test
    public void newSongIdSkipsTakenIdsAndTakesTheOneItReturns()
    {
        Set<String> taken = new HashSet<>();
        taken.add("aaaaaaaa");
        taken.add("bbbbbbbb");

        String id = PlaylistFile.newSongId(new CountingRandom(), taken);

        assertEquals("the first two draws were taken", "cccccccc", id);
        assertTrue("and it is now taken itself", taken.contains("cccccccc"));
        assertEquals(3, taken.size());
    }

    @Test
    public void copyForPlaylistGivesAFreshIdAndTheNewAdder()
    {
        PlaylistFile.Song source = new PlaylistFile.Song();
        source.setId("srcsrc12");
        source.setUri("https://example.test/a");
        source.setTitle("Title");
        source.setAuthor("Author");
        source.setDurationMs(1234);
        source.setArtworkUrl("https://img.test/a.jpg");
        source.setUnresolved(true);
        source.setAddedBy(new PlaylistFile.Person("1", "old"));
        source.setAddedAt(Instant.parse("2020-01-01T00:00:00Z"));
        Set<String> taken = new HashSet<>();
        taken.add("srcsrc12");
        PlaylistFile.Person by = new PlaylistFile.Person("2", "new");
        Instant at = Instant.parse("2026-10-13T18:00:00Z");

        PlaylistFile.Song copy = PlaylistFile.copyForPlaylist(source, taken, by, at);

        assertNotSame(source, copy);
        assertTrue(copy.getId(), copy.getId().matches("[a-z0-9]{8}"));
        assertNotEquals("srcsrc12", copy.getId());
        assertTrue("the id is now taken", taken.contains(copy.getId()));
        assertEquals("https://example.test/a", copy.getUri());
        assertEquals("Title", copy.getTitle());
        assertEquals("Author", copy.getAuthor());
        assertEquals(1234, copy.getDurationMs());
        assertEquals("https://img.test/a.jpg", copy.getArtworkUrl());
        assertTrue(copy.isUnresolved());
        assertSame(by, copy.getAddedBy());
        assertEquals(at, copy.getAddedAt());
        assertEquals("the source is left alone", "srcsrc12", source.getId());
        assertEquals("old", source.getAddedBy().getName());

        assertNull("nobody as the adder", PlaylistFile.copyForPlaylist(source, taken, null, at).getAddedBy());
    }

    @Test
    public void badMissingAndDuplicateSongIdsGetFreshOnes()
    {
        JSONArray songs = new JSONArray()
                .put(new JSONObject().put("id", "abcd1234").put("uri", "first"))
                .put(new JSONObject().put("uri", "no id"))
                .put(new JSONObject().put("id", "ABCD1234").put("uri", "upper case"))
                .put(new JSONObject().put("id", "short").put("uri", "too short"))
                .put(new JSONObject().put("id", "abcd1234").put("uri", "duplicate"))
                .put(new JSONObject().put("id", "zzzz9999").put("uri", "last"))
                .put("not a song object");
        PlaylistFile file = PlaylistFile.fromJson(playlistWithSongs(songs));
        assertEquals(6, file.getSongs().size());
        Set<String> ids = new HashSet<>();
        for(PlaylistFile.Song song : file.getSongs())
        {
            assertTrue(song.getId(), song.getId().matches("[a-z0-9]{8}"));
            assertTrue("unique " + song.getId(), ids.add(song.getId()));
        }
        assertEquals("abcd1234", file.getSongs().get(0).getId());
        assertEquals("duplicate", file.getSongs().get(4).getUri());
        assertNotEquals("abcd1234", file.getSongs().get(4).getId());
        assertEquals("zzzz9999", file.getSongs().get(5).getId());
    }

    @Test
    public void legacyFlagIsNeverPersisted()
    {
        PlaylistFile file = new PlaylistFile();
        assertFalse(file.isLegacy());
        file.setLegacy(true);
        assertTrue(file.isLegacy());
        assertFalse(file.toJson().contains("legacy"));
        assertFalse(PlaylistFile.fromJson(file.toJson()).isLegacy());
        assertFalse(PlaylistFile.fromJson("{\"format\":2,\"legacy\":true}").isLegacy());
    }

    @Test
    public void deletedFieldsRoundTrip()
    {
        PlaylistFile file = new PlaylistFile();
        file.setName("Gone");
        file.setDeletedBy(new PlaylistFile.Person("42", "snoop"));
        file.setDeletedAt(Instant.parse("2026-10-13T19:00:00Z"));
        file.setOriginalFolder("Rock/Metal");

        JSONObject raw = new JSONObject(file.toJson());
        assertEquals("snoop", raw.getJSONObject("deletedBy").getString("name"));
        assertEquals("42", raw.getJSONObject("deletedBy").getString("id"));
        assertEquals("2026-10-13T19:00:00Z", raw.getString("deletedAt"));
        assertEquals("Rock/Metal", raw.getString("originalFolder"));

        PlaylistFile back = PlaylistFile.fromJson(file.toJson());
        assertEquals("42", back.getDeletedBy().getId());
        assertEquals("snoop", back.getDeletedBy().getName());
        assertEquals(Instant.parse("2026-10-13T19:00:00Z"), back.getDeletedAt());
        assertEquals("Rock/Metal", back.getOriginalFolder());

        // the top level is a real value, not "missing"
        file.setOriginalFolder("");
        assertEquals("", PlaylistFile.fromJson(file.toJson()).getOriginalFolder());
    }

    @Test
    public void deletedFieldsAreAbsentWhenNull()
    {
        PlaylistFile file = new PlaylistFile();
        file.setName("Alive");
        JSONObject raw = new JSONObject(file.toJson());
        assertFalse(raw.has("deletedBy"));
        assertFalse(raw.has("deletedAt"));
        assertFalse(raw.has("originalFolder"));

        PlaylistFile back = PlaylistFile.fromJson(file.toJson());
        assertNull(back.getDeletedBy());
        assertNull(back.getDeletedAt());
        assertNull(back.getOriginalFolder());

        file.setOriginalFolder(repeat('f', 400));
        assertEquals("the folder is cut like every text field", 300, file.getOriginalFolder().length());
    }
}
