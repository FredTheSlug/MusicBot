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
import com.jagrosh.jmusicbot.playlist.PlaylistStore.Legacy;
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upgrades the old {@code .txt} playlists to JSON playlists, one at a time and one network load at a
 * time. Each line is looked up by the {@link TrackResolver} (a line that fails is kept as an
 * {@code unresolved} song, never dropped); the finished playlist is written first and only then is the
 * {@code .txt} moved to {@code Playlists/.legacy-txt/}, so a playlist is never lost and an interrupted
 * run simply picks up the rest next time. Until its turn a {@code .txt} keeps playing as it always did.
 */
public class PlaylistConverter
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistConverter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlaylistStore store;
    private final TrackResolver resolver;
    private volatile Progress progress = new Progress(0, 0, 0);

    public PlaylistConverter(PlaylistStore store, TrackResolver resolver)
    {
        this.store = store;
        this.resolver = resolver;
    }

    /**
     * Converts every {@code .txt} playlist that has no {@code .json} and returns how many it converted. A
     * playlist whose conversion fails with an exception (a broken resolver, a disk error) is logged, counted
     * as failed and left as it was, to be tried again at the next start. Stops early when the thread is interrupted.
     */
    public synchronized int convertPending()
    {
        // a crash between "write the .json" and "move the .txt" leaves both: tidy up before any network use
        int tidied = store.moveLeftoverTxts();
        if(tidied > 0)
            LOG.info("Moved {} old playlist file(s) whose upgrade had already been saved to .legacy-txt", tidied);

        List<Legacy> pending = store.legacyEntries();
        int done = 0;
        int failed = 0;
        progress = new Progress(pending.size(), done, failed);
        for(Legacy legacy : pending)
        {
            if(Thread.currentThread().isInterrupted())
                break;
            try
            {
                if(convert(legacy))
                    done++;
            }
            catch(RuntimeException e)
            {
                failed++;
                LOG.warn("Could not convert '{}', it will be tried again at the next start", label(legacy), e);
            }
            progress = new Progress(pending.size(), done, failed);
        }
        return done;
    }

    /** How far the current (or last) {@link #convertPending} run has got; all zero before the first run. Safe to read from any thread. */
    public Progress progress()
    {
        return progress;
    }

    /** Looks every line up and saves the playlist; false when the store left it alone (it is already converted, or its file is gone). */
    private boolean convert(Legacy legacy)
    {
        List<Song> found = new ArrayList<>();
        for(String line : legacy.getLines())
        {
            if(found.size() >= PlaylistFile.MAX_SONGS)
                break;
            Resolved resolved = resolver.resolve(line);
            if(resolved.getSongs().isEmpty())
            {
                LOG.debug("Could not load '{}' in '{}': {}", line, label(legacy), resolved.getError());
                found.add(TrackResolver.unresolvedSong(line, LavaTrackResolver.identifierFor(line)));
            }
            else
                found.addAll(resolved.getSongs());
        }
        if(found.size() > PlaylistFile.MAX_SONGS)
            found.subList(PlaylistFile.MAX_SONGS, found.size()).clear();

        PlaylistFile file = new PlaylistFile();
        file.setName(legacy.getName());
        file.setCreatedAt(legacy.getModified());
        file.setShuffle(legacy.isShuffle());
        Set<String> ids = new HashSet<>();
        int unresolved = 0;
        for(Song source : found)
        {
            file.getSongs().add(copyOf(source, ids, legacy.getModified()));
            if(source.isUnresolved())
                unresolved++;
        }
        if(!store.writeConverted(legacy.getName(), legacy.getFolder(), file))
        {
            LOG.info("Left '{}' alone: it was already converted or its file is gone", label(legacy));
            return false;
        }
        LOG.info("Converted '{}': {} song{}{}", label(legacy), found.size(), found.size() == 1 ? "" : "s",
                unresolved == 0 ? "" : ", " + unresolved + " couldn't be loaded");
        return true;
    }

    /** A copy for the new playlist: a fresh id, nobody as the one who added it, added when the old file was written. */
    private static Song copyOf(Song source, Set<String> ids, Instant addedAt)
    {
        String id;
        do
        {
            id = PlaylistFile.newSongId(RANDOM);
        }
        while(!ids.add(id));
        Song copy = new Song();
        copy.setId(id);
        copy.setUri(source.getUri());
        copy.setTitle(source.getTitle());
        copy.setAuthor(source.getAuthor());
        copy.setDurationMs(source.getDurationMs());
        copy.setArtworkUrl(source.getArtworkUrl());
        copy.setUnresolved(source.isUnresolved());
        copy.setAddedAt(addedAt);
        return copy;
    }

    /** {@code Folder/Name}, or just the name at the top level. */
    private static String label(Legacy legacy)
    {
        return legacy.getFolder().isEmpty() ? legacy.getName() : legacy.getFolder() + "/" + legacy.getName();
    }

    /** A snapshot of the conversion: how many old playlists the run found, how many it converted and how many it gave up on. */
    public static final class Progress
    {
        private final int total;
        private final int done;
        private final int failed;

        Progress(int total, int done, int failed)
        {
            this.total = total;
            this.done = done;
            this.failed = failed;
        }

        /** Old playlists found when the run started. */
        public int getTotal()
        {
            return total;
        }

        /** Converted so far. */
        public int getDone()
        {
            return done;
        }

        /** Given up on so far (left as they were, tried again at the next start). */
        public int getFailed()
        {
            return failed;
        }
    }
}
