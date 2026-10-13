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
import com.jagrosh.jmusicbot.playlist.TrackResolver.Resolved;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background jobs that load a YouTube, SoundCloud or Spotify playlist or album link through the
 * {@link TrackResolver} and put its songs in a playlist, a batch at a time as they load, so the playlist
 * fills in while the job runs. The job either makes a new playlist (named by the caller, else after the
 * source's title) or appends to an existing one. Every user can have one job running and at most 3 run at
 * once. Progress goes to a listener after each batch and once more when the job ends, however it ends.
 */
public class ImportJobs
{
    private static final Logger LOG = LoggerFactory.getLogger(ImportJobs.class);

    /** Jobs that may run (or wait for a thread) at the same time. */
    private static final int MAX_RUNNING = 3;
    /** How many {@code Name (2)}, {@code Name (3)}… are tried before giving up on finding a free name. */
    private static final int NAME_TRIES = 1000;
    private static final String ANONYMOUS = "anonymous";
    private static final String USER_PREFIX = "user:";
    private static final String HTTP_ONLY = "Import links must start with http:// or https://";
    private static final String ONE_PER_USER = "You already have an import running";
    private static final String TOO_MANY = "Too many imports are running — try again in a minute";
    private static final String FULL = "Playlist is full (" + PlaylistFile.MAX_SONGS + " songs)";
    private static final String NOTHING = "Nothing found at that link";
    private static final String FAILED = "The import failed — see the bot's log";
    private static final String FALLBACK_NAME = "Imported playlist";
    /** Characters that can't stay in a playlist name made from a title, and what they become. */
    private static final String SPACES = "[\\p{Cntrl}\\s]+";
    private static final String SEPARATORS = "[/\\\\]";
    /** Characters Windows does not allow in file names; they are only replaced when the store refuses the name. */
    private static final String WINDOWS_ILLEGAL = "[<>:\"|?*]";

    private final PlaylistStore store;
    private final TrackResolver resolver;
    private final Executor executor;
    private final Consumer<Progress> onProgress;
    private final Object lock = new Object();
    /** The user of every running job, and the id of that job. Guarded by {@link #lock}. */
    private final Map<String, String> running = new HashMap<>();

    /**
     * @param executor runs the jobs; it should have a thread for each of the 3 jobs that can run at once
     * @param onProgress gets a {@link Progress} after every batch and one that is {@code finished}; it runs on
     *                   the job's thread, and an exception it throws is logged and does not stop the job
     */
    public ImportJobs(PlaylistStore store, TrackResolver resolver, Executor executor, Consumer<Progress> onProgress)
    {
        this.store = store;
        this.resolver = resolver;
        this.executor = executor;
        this.onProgress = onProgress;
    }

    /**
     * Checks the request and starts the job, returning its id (8 characters of {@code [a-z0-9]}); the work
     * happens on the executor. Nothing is started when this throws.
     *
     * @param link a YouTube, SoundCloud or Spotify playlist or album link; {@code http://} or {@code https://} only
     * @param name for a new playlist; null or blank = the title of the source. Ignored with {@code into}
     * @param folder the folder of the new or existing playlist; null = the top level
     * @param into the existing playlist to append to; null or blank = make a new one
     * @param by who asked (may be null); added songs and a new playlist are credited to them, and they can run one job at a time
     * @throws IllegalArgumentException for a link that is not http(s) and for a name or folder the store does not allow
     * @throws NoSuchElementException when {@code into} does not exist
     * @throws IllegalStateException when {@code into} is an unconverted {@code .txt} playlist, when {@code name} is
     *         taken, when {@code by} already has a job running or when 3 jobs are running
     */
    public String start(String link, String name, String folder, String into, Person by)
    {
        String url = link == null ? "" : link.trim();
        if(!isHttp(url))
            throw new IllegalArgumentException(HTTP_ONLY);
        String where = folder == null ? "" : folder;
        String existing = isBlank(into) ? null : into;
        String wanted = existing != null || isBlank(name) ? null : name;
        if(existing != null)
        {
            PlaylistFile target = store.get(existing, where).orElseThrow(() -> new NoSuchElementException(PlaylistStore.NOT_FOUND));
            if(target.isLegacy())
                throw new IllegalStateException(PlaylistStore.UPGRADING);
        }
        else if(wanted != null)
        {
            if(Files.exists(store.pathFor(wanted, where)) || store.get(wanted, where).isPresent())
                throw new IllegalStateException(PlaylistStore.TAKEN);
        }
        else
            store.pathFor(FALLBACK_NAME, where); // the folder must be one the store allows

        Job job;
        synchronized(lock) // check and register together: two starts by one user can't both get in
        {
            String user = by == null || by.getId() == null ? ANONYMOUS : USER_PREFIX + by.getId();
            if(running.containsKey(user))
                throw new IllegalStateException(ONE_PER_USER);
            if(running.size() >= MAX_RUNNING)
                throw new IllegalStateException(TOO_MANY);
            String id = PlaylistFile.newSongId(ThreadLocalRandom.current(), new HashSet<>(running.values()));
            running.put(user, id);
            job = new Job(id, user, url, wanted, where, existing, by);
        }
        try
        {
            executor.execute(job::run);
        }
        catch(RuntimeException e)
        {
            release(job); // the job will never run
            throw e;
        }
        return job.id;
    }

    private void release(Job job)
    {
        synchronized(lock)
        {
            running.remove(job.user, job.id);
        }
    }

    private void publish(Progress progress)
    {
        try
        {
            onProgress.accept(progress);
        }
        catch(RuntimeException e)
        {
            LOG.warn("Import progress listener failed", e);
        }
    }

    private static boolean isHttp(String url)
    {
        return url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8);
    }

    private static boolean isBlank(String text)
    {
        return text == null || text.trim().isEmpty();
    }

    /** What goes to the user when a job dies of an exception: the message if it is one meant for people, else a general one. */
    private static String describe(RuntimeException e)
    {
        boolean forPeople = e instanceof IllegalStateException || e instanceof IllegalArgumentException
                || e instanceof NoSuchElementException;
        return forPeople && !isBlank(e.getMessage()) ? PlaylistFile.cut(e.getMessage()) : FAILED;
    }

    /** Thrown from a batch to stop the resolver loading what would not fit anyway. */
    private static final class Stop extends RuntimeException
    {
        Stop()
        {
            super(null, null, false, false);
        }
    }

    /** One import: the request, and the state of the work while it runs. Used by the one thread that runs it. */
    private final class Job
    {
        final String id;
        final String user;
        final String link;
        /** The name asked for a new playlist; null = {@code into}, or named after the source. */
        final String preferred;
        final String folder;
        final String into;
        final Person by;
        /** A new playlist named after the source: its songs are held until the title is known. */
        final boolean titleNeeded;

        /** The playlist's name; null until it exists. */
        String name;
        /** Songs in the playlist, or held. */
        int size;
        int added;
        int addedFailed;
        final List<Song> held = new ArrayList<>();
        int heldFailed;
        /** The total the resolver last reported. */
        int reported;
        /** True once a song did not fit. */
        boolean full;

        Job(String id, String user, String link, String preferred, String folder, String into, Person by)
        {
            this.id = id;
            this.user = user;
            this.link = link;
            this.preferred = preferred;
            this.folder = folder;
            this.into = into;
            this.by = by;
            this.titleNeeded = preferred == null && into == null;
            this.name = into;
        }

        /** Runs on the executor. Never throws an exception, and frees the user's slot before it reports the end. */
        void run()
        {
            String error;
            try
            {
                error = load();
            }
            catch(RuntimeException e)
            {
                LOG.warn("Import {} of {} failed", id, link, e);
                error = describe(e);
            }
            finally
            {
                release(this);
            }
            LOG.info("Import {} of {} ended: {} song(s) in {}{}", id, link, added,
                    name == null ? "no playlist" : "'" + name + "'", error == null ? "" : " (" + error + ")");
            publish(progress(true, error));
        }

        /** Does the import; returns why it ended early, or null when it got everything. */
        private String load()
        {
            if(into != null)
                size = store.get(into, folder).orElseThrow(() -> new NoSuchElementException(PlaylistStore.NOT_FOUND)).getSongs().size();
            Resolved resolved;
            try
            {
                resolved = resolver.resolve(link, this::batch);
            }
            catch(Stop stop)
            {
                return FULL;
            }
            if(titleNeeded)
                flush(resolved.getTitle());
            if(full)
                return FULL;
            if(added == 0)
                return resolved.getError() != null ? resolved.getError() : NOTHING;
            return null;
        }

        /** Takes one batch from the resolver. */
        private void batch(List<Song> songs, int total)
        {
            if(total > 0)
                reported = total;
            if(!full)
                take(songs);
            publish(progress(false, null));
            if(full && !titleNeeded)
                throw new Stop(); // the rest would not fit; with a title to wait for, the resolver is let finish
        }

        /** Puts what fits of the batch in the playlist (or holds it). */
        private void take(List<Song> songs)
        {
            int room = Math.max(0, PlaylistFile.MAX_SONGS - size);
            List<Song> fit = songs.size() > room ? songs.subList(0, room) : songs;
            full = fit.size() < songs.size();
            if(fit.isEmpty())
                return;
            if(titleNeeded)
            {
                held.addAll(fit);
                heldFailed += unresolved(fit);
                size = held.size();
                return;
            }
            if(name == null)
                name = create(preferred); // not before there is something to put in it
            size = store.addSongs(name, folder, fit, null, by).getSongs().size();
            added += fit.size();
            addedFailed += unresolved(fit);
        }

        /** Makes the playlist named after the source and puts the held songs in it. */
        private void flush(String title)
        {
            if(held.isEmpty())
                return;
            name = create(nameFrom(title));
            store.addSongs(name, folder, held, null, by);
            added = held.size();
            addedFailed = heldFailed;
            held.clear();
            heldFailed = 0;
        }

        /** Makes an empty playlist called {@code base}, or {@code base (2)}, {@code base (3)}… when that is taken; returns its name. */
        private String create(String base)
        {
            for(int n = 1; n <= NAME_TRIES; n++)
            {
                String candidate = n == 1 ? base : PlaylistStore.withSuffix(base, " (" + n + ")");
                try
                {
                    store.create(candidate, folder, by);
                    return candidate;
                }
                catch(IllegalStateException taken)
                {
                    // the store only refuses a taken name: try the next number
                }
            }
            throw new IllegalStateException(PlaylistStore.TAKEN);
        }

        /** The title as a playlist name: no separators or control characters, cut to the length allowed; {@code Imported playlist} when none is left. */
        private String nameFrom(String title)
        {
            if(title != null)
            {
                String cleaned = title.replaceAll(SPACES, " ").replaceAll(SEPARATORS, "-").trim();
                cleaned = PlaylistStore.withSuffix(cleaned, "").trim();
                if(allowed(cleaned))
                    return cleaned;
                String portable = cleaned.replaceAll(WINDOWS_ILLEGAL, "_");
                if(allowed(portable))
                    return portable;
            }
            return FALLBACK_NAME;
        }

        private boolean allowed(String candidate)
        {
            try
            {
                store.pathFor(candidate, folder);
                return true;
            }
            catch(IllegalArgumentException e)
            {
                return false;
            }
        }

        private int unresolved(List<Song> songs)
        {
            int count = 0;
            for(Song song : songs)
                if(song.isUnresolved())
                    count++;
            return count;
        }

        /** Where the job stands; the songs held for a name are counted as they come in, but not once the job has ended without using them. */
        private Progress progress(boolean finished, String error)
        {
            int done = added + (finished ? 0 : held.size());
            int failed = addedFailed + (finished ? 0 : heldFailed);
            return new Progress(id, done, Math.max(reported, done), failed, name, folder, finished, error);
        }
    }

    /** How an import stands: immutable. */
    public static final class Progress
    {
        private final String jobId;
        private final int done;
        private final int total;
        private final int failedCount;
        private final String playlistName;
        private final String folder;
        private final boolean finished;
        private final String error;

        public Progress(String jobId, int done, int total, int failedCount, String playlistName, String folder,
                boolean finished, String error)
        {
            this.jobId = jobId;
            this.done = done;
            this.total = total;
            this.failedCount = failedCount;
            this.playlistName = playlistName;
            this.folder = folder;
            this.finished = finished;
            this.error = error;
        }

        public String getJobId()
        {
            return jobId;
        }

        /** Songs taken in so far: added to the playlist, or (for a playlist named after the source) held until it can be made. */
        public int getDone()
        {
            return done;
        }

        /** Songs the source will give in all, as the resolver reported it; at least {@link #getDone()}. */
        public int getTotal()
        {
            return total;
        }

        /** Of the songs counted in {@link #getDone()}, those that could not be loaded (added as {@code unresolved}). */
        public int getFailedCount()
        {
            return failedCount;
        }

        /** The playlist the songs go to; null until it exists. */
        public String getPlaylistName()
        {
            return playlistName;
        }

        /** The folder of the playlist, {@code ""} for the top level. */
        public String getFolder()
        {
            return folder;
        }

        /** True for the last report of a job. */
        public boolean isFinished()
        {
            return finished;
        }

        /** Why the job ended before it got everything; null while it runs and when it succeeded. */
        public String getError()
        {
            return error;
        }
    }
}
