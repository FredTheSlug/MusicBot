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

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One playlist as stored in {@code Playlists/<folder>/<name>.json} (format 2). A plain mutable data
 * class: it only converts to and from JSON text, the store owns all file access and locking.
 */
public class PlaylistFile
{
    /** The only file format this class reads and writes. */
    public static final int FORMAT = 2;
    /** Songs beyond this many are dropped when a file is read. */
    public static final int MAX_SONGS = 2000;
    /** Longest text kept in any field. */
    public static final int MAX_TEXT = 300;

    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 8;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]{8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private String name;
    private long version;
    private Person createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private boolean shuffle;
    private final List<Song> songs = new ArrayList<>();
    private boolean legacy;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = cut(name);
    }

    public long getVersion()
    {
        return version;
    }

    public void setVersion(long version)
    {
        this.version = version;
    }

    public Person getCreatedBy()
    {
        return createdBy;
    }

    public void setCreatedBy(Person createdBy)
    {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt)
    {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt)
    {
        this.updatedAt = updatedAt;
    }

    public boolean isShuffle()
    {
        return shuffle;
    }

    public void setShuffle(boolean shuffle)
    {
        this.shuffle = shuffle;
    }

    /** The live song list; callers edit it directly. */
    public List<Song> getSongs()
    {
        return songs;
    }

    /** True for a read-only view of an unconverted {@code .txt} playlist. Never stored in the file. */
    public boolean isLegacy()
    {
        return legacy;
    }

    public void setLegacy(boolean legacy)
    {
        this.legacy = legacy;
    }

    /** Parses file text; throws {@link IllegalArgumentException} when it is not a format 2 playlist. */
    public static PlaylistFile fromJson(String text)
    {
        if(text == null)
            throw new IllegalArgumentException("No playlist text");
        JSONObject root;
        try
        {
            root = new JSONObject(text);
        }
        catch(JSONException e)
        {
            throw new IllegalArgumentException("Not a playlist file: " + e.getMessage(), e);
        }
        if(root.optInt("format", -1) != FORMAT)
            throw new IllegalArgumentException("Unsupported playlist format");

        PlaylistFile file = new PlaylistFile();
        file.setName(text(root, "name"));
        file.setVersion(root.optLong("version", 0L));
        file.setCreatedBy(readPerson(root.optJSONObject("createdBy")));
        file.setCreatedAt(instant(root, "createdAt"));
        file.setUpdatedAt(instant(root, "updatedAt"));
        file.setShuffle(root.optBoolean("shuffle", false));

        JSONArray array = root.optJSONArray("songs");
        if(array != null)
        {
            Set<String> usedIds = new HashSet<>();
            for(int i = 0; i < array.length() && file.songs.size() < MAX_SONGS; i++)
            {
                JSONObject entry = array.optJSONObject(i);
                if(entry != null)
                    file.songs.add(readSong(entry, usedIds));
            }
        }
        return file;
    }

    /** The file text: pretty printed with 2-space indent. */
    public String toJson()
    {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("name", name);
        root.put("version", version);
        root.put("createdBy", createdBy == null ? null : createdBy.toJson());
        root.put("createdAt", createdAt == null ? null : createdAt.toString());
        root.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        root.put("shuffle", shuffle);
        JSONArray array = new JSONArray();
        for(Song song : songs)
            array.put(song.toJson());
        root.put("songs", array);
        return root.toString(2);
    }

    /** A fresh random song id: 8 characters from {@code [a-z0-9]}. */
    public static String newSongId(Random random)
    {
        char[] id = new char[ID_LENGTH];
        for(int i = 0; i < ID_LENGTH; i++)
            id[i] = ID_CHARS.charAt(random.nextInt(ID_CHARS.length()));
        return new String(id);
    }

    /** Text cut to {@link #MAX_TEXT} characters; null stays null. */
    public static String cut(String text)
    {
        if(text == null || text.length() <= MAX_TEXT)
            return text;
        // never leave half of a surrogate pair at the end: it would be written out as "?"
        int end = Character.isHighSurrogate(text.charAt(MAX_TEXT - 1)) ? MAX_TEXT - 1 : MAX_TEXT;
        return text.substring(0, end);
    }

    private static Person readPerson(JSONObject json)
    {
        if(json == null)
            return null;
        String id = text(json, "id");
        String personName = text(json, "name");
        return id == null && personName == null ? null : new Person(id, personName);
    }

    private static Song readSong(JSONObject json, Set<String> usedIds)
    {
        Song song = new Song();
        String id = text(json, "id");
        if(id == null || !ID_PATTERN.matcher(id).matches() || !usedIds.add(id))
        {
            do
            {
                id = newSongId(RANDOM);
            }
            while(!usedIds.add(id));
        }
        song.setId(id);
        song.setUri(text(json, "uri"));
        song.setTitle(text(json, "title"));
        song.setAuthor(text(json, "author"));
        song.setDurationMs(json.optLong("durationMs", 0L));
        song.setArtworkUrl(text(json, "artworkUrl"));
        song.setAddedBy(readPerson(json.optJSONObject("addedBy")));
        song.setAddedAt(instant(json, "addedAt"));
        song.setUnresolved(json.optBoolean("unresolved", false));
        return song;
    }

    /** A string (or number) field cut to size; null when missing or of another type. */
    private static String text(JSONObject json, String key)
    {
        Object value = json.opt(key);
        return value instanceof String || value instanceof Number ? cut(value.toString()) : null;
    }

    private static Instant instant(JSONObject json, String key)
    {
        Object value = json.opt(key);
        if(!(value instanceof String))
            return null;
        try
        {
            return Instant.parse((String) value);
        }
        catch(DateTimeParseException e)
        {
            return null;
        }
    }

    /** A Discord user: id and display name. */
    public static final class Person
    {
        private final String id;
        private final String name;

        public Person(String id, String name)
        {
            this.id = cut(id);
            this.name = cut(name);
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }

        JSONObject toJson()
        {
            return new JSONObject().put("id", id).put("name", name);
        }
    }

    /** One entry of the playlist; edits address it by {@link #getId() id}. */
    public static final class Song
    {
        private String id;
        private String uri;
        private String title;
        private String author;
        private long durationMs;
        private String artworkUrl;
        private Person addedBy;
        private Instant addedAt;
        private boolean unresolved;

        public String getId()
        {
            return id;
        }

        public void setId(String id)
        {
            this.id = id;
        }

        public String getUri()
        {
            return uri;
        }

        public void setUri(String uri)
        {
            this.uri = cut(uri);
        }

        public String getTitle()
        {
            return title;
        }

        public void setTitle(String title)
        {
            this.title = cut(title);
        }

        public String getAuthor()
        {
            return author;
        }

        public void setAuthor(String author)
        {
            this.author = cut(author);
        }

        public long getDurationMs()
        {
            return durationMs;
        }

        public void setDurationMs(long durationMs)
        {
            this.durationMs = durationMs;
        }

        public String getArtworkUrl()
        {
            return artworkUrl;
        }

        public void setArtworkUrl(String artworkUrl)
        {
            this.artworkUrl = cut(artworkUrl);
        }

        public Person getAddedBy()
        {
            return addedBy;
        }

        public void setAddedBy(Person addedBy)
        {
            this.addedBy = addedBy;
        }

        public Instant getAddedAt()
        {
            return addedAt;
        }

        public void setAddedAt(Instant addedAt)
        {
            this.addedAt = addedAt;
        }

        /** True when conversion or import could not load the line; {@code title} then holds its text. */
        public boolean isUnresolved()
        {
            return unresolved;
        }

        public void setUnresolved(boolean unresolved)
        {
            this.unresolved = unresolved;
        }

        JSONObject toJson()
        {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("uri", uri);
            json.put("title", title);
            json.put("author", author);
            json.put("durationMs", durationMs);
            json.put("artworkUrl", artworkUrl);
            json.put("addedBy", addedBy == null ? null : addedBy.toJson());
            json.put("addedAt", addedAt == null ? null : addedAt.toString());
            json.put("unresolved", unresolved);
            return json;
        }
    }
}
