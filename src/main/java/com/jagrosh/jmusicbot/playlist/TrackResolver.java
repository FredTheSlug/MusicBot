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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns one playlist line or link into songs with their real details: what the conversion of the old
 * {@code .txt} playlists and the link import both load through. Calls block until the answer is in. A
 * resolver never throws for a line that can't be loaded (it answers with an error); an exception means
 * the resolver itself is broken, and the caller gives up on its job.
 */
public interface TrackResolver
{
    /** Loads what the line or link points to. */
    Resolved resolve(String lineOrLink);

    /**
     * As {@link #resolve(String)}, handing the songs to {@code onBatch} as they come in, with how many
     * there will be in total. This default reports everything in one batch once it has loaded.
     */
    default Resolved resolve(String lineOrLink, Batches onBatch)
    {
        Resolved r = resolve(lineOrLink);
        onBatch.batch(r.getSongs(), r.getSongs().size());
        return r;
    }

    /** A song that could not be loaded: {@code title} holds the original text, {@code uri} the link or {@code ytsearch:} query to try again. */
    static Song unresolvedSong(String title, String uri)
    {
        Song song = new Song();
        song.setTitle(title);
        song.setUri(uri);
        song.setUnresolved(true);
        return song;
    }

    /** Receives the songs of a large link as they load. */
    interface Batches
    {
        /** {@code songs} are the newest ones; {@code total} is how many the link will give in all. */
        void batch(List<Song> songs, int total);
    }

    /** What a line or link gave: immutable. */
    final class Resolved
    {
        private final List<Song> songs;
        private final String title;
        private final String error;

        /**
         * @param songs the songs in order; a collection's items that failed are {@code unresolved} songs among them
         * @param title the album, playlist or collection title, or null
         * @param error null on success; with no songs, why the whole line failed
         */
        public Resolved(List<Song> songs, String title, String error)
        {
            this.songs = songs == null ? Collections.<Song>emptyList() : Collections.unmodifiableList(new ArrayList<>(songs));
            this.title = title;
            this.error = error;
        }

        /** A line that loaded. */
        public static Resolved ok(List<Song> songs, String title)
        {
            return new Resolved(songs, title, null);
        }

        /** A line that gave nothing. */
        public static Resolved failed(String error)
        {
            return new Resolved(null, null, error);
        }

        public List<Song> getSongs()
        {
            return songs;
        }

        /** The album, playlist or collection title; null for a single song or a search. */
        public String getTitle()
        {
            return title;
        }

        /** Null on success; with no songs, the reason the whole line failed. */
        public String getError()
        {
            return error;
        }
    }
}
