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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upgrades the old {@code .txt} playlists to JSON playlists, one at a time and one network load at a
 * time. Each line is looked up by the {@link TrackResolver} (a line that fails is kept as an
 * {@code unresolved} song, never dropped, unless the sources themselves stop answering, in which case the
 * run pauses instead); the finished playlist is written first and only then is the
 * {@code .txt} moved to {@code Playlists/.legacy-txt/}, so a playlist is never lost and an interrupted
 * run simply picks up the rest next time. Until its turn a {@code .txt} keeps playing as it always did.
 */
public class PlaylistConverter
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistConverter.class);
    private static final String UNKNOWN_REASON = "unknown reason";

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
     * as failed and left as it was, to be tried again by the next call. When a line fails and the resolver is
     * not {@linkplain TrackResolver#healthy() healthy}, that is an outage and not a dead line: the playlist
     * counts as failed and is left as it was, and the run stops there, leaving the rest to the next call. A
     * line that is a file on disk is never checked: a missing file is dead whatever the sources are doing. A
     * run also stops early when the thread is interrupted. A playlist found already converted meanwhile is
     * counted as done in {@link #progress()}, but not in the number returned.
     */
    public synchronized int convertPending()
    {
        // a crash between "write the .json" and "move the .txt" leaves both: tidy up before any network use
        int tidied = store.moveLeftoverTxts();
        if(tidied > 0)
            LOG.info("Moved {} old playlist file(s) whose upgrade had already been saved to .legacy-txt", tidied);

        List<Legacy> pending = store.legacyEntries();
        int converted = 0;
        int done = 0;
        int failed = 0;
        progress = new Progress(pending.size(), done, failed);
        for(Legacy legacy : pending)
        {
            if(Thread.currentThread().isInterrupted())
                break;
            boolean paused = false;
            try
            {
                if(convert(legacy))
                    converted++;
                done++;
            }
            catch(SourcesDown e)
            {
                failed++;
                paused = true;
                LOG.warn("YouTube looks unreachable while converting '{}' (line: {}) — playlist conversion paused, will retry",
                        label(legacy), e.line);
            }
            catch(RuntimeException e)
            {
                failed++;
                LOG.warn("Could not convert '{}', it will be tried again later", label(legacy), e);
            }
            progress = new Progress(pending.size(), done, failed);
            if(paused)
                break;
        }
        return converted;
    }

    /** How far the current (or last) {@link #convertPending} run has got; all zero before the first run. Safe to read from any thread. */
    public Progress progress()
    {
        return progress;
    }

    /**
     * Looks every line up and saves the playlist; false when the store left it alone (it is already converted,
     * or its file is gone). Throws {@link SourcesDown} when a line fails while the resolver is not healthy.
     */
    private boolean convert(Legacy legacy)
    {
        List<Song> found = new ArrayList<>();
        for(String line : legacy.getLines())
        {
            if(found.size() >= PlaylistFile.MAX_SONGS)
                break;
            Resolved resolved = resolver.resolve(line);
            if(gaveNothing(resolved))
            {
                if(!LavaTrackResolver.isLocalFile(line.trim()) && !resolver.healthy())
                    throw new SourcesDown(line); // not a dead line: nothing is getting through
                LOG.info("Could not load '{}' in '{}': {}", line, label(legacy),
                        resolved.getError() == null ? UNKNOWN_REASON : resolved.getError());
            }
            if(resolved.getSongs().isEmpty())
                found.add(TrackResolver.unresolvedSong(line, LavaTrackResolver.identifierFor(line)));
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
            file.getSongs().add(PlaylistFile.copyForPlaylist(source, ids, null, legacy.getModified()));
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

    /** True when the line gave no song that loaded: an error, nothing, or only songs that could not be loaded. */
    private static boolean gaveNothing(Resolved resolved)
    {
        for(Song song : resolved.getSongs())
            if(!song.isUnresolved())
                return false;
        return true;
    }

    /** {@code Folder/Name}, or just the name at the top level. */
    private static String label(Legacy legacy)
    {
        return legacy.getFolder().isEmpty() ? legacy.getName() : legacy.getFolder() + "/" + legacy.getName();
    }

    /** Thrown to stop the run when a line fails and the sources are not answering; {@code line} is the one that failed. */
    private static final class SourcesDown extends RuntimeException
    {
        final String line;

        SourcesDown(String line)
        {
            super(null, null, false, false);
            this.line = line;
        }
    }

    /**
     * A snapshot of the conversion: how many old playlists the run found, how many it converted and how many it
     * gave up on. A run that was not cut short (by an outage or an interrupt) ends with {@code done + failed == total}.
     */
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

        /** Converted so far, including playlists found already converted by someone else. */
        public int getDone()
        {
            return done;
        }

        /** Given up on so far (left as they were, tried again by the next run). */
        public int getFailed()
        {
            return failed;
        }
    }
}
