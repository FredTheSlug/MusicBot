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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everyone's liked songs. Each person's likes are one playlist file (format 2) named after their Discord
 * id, {@code <likesDir>/<id>.json}, made by their first like, in a {@link PlaylistStore} of its own, so
 * likes get the same atomic writes, song ids and song operations as any playlist. Only the owner's list is
 * ever changed by the methods that take a {@link Person}; the other lists are only read. All changes to one
 * person's list run under a lock of this class, so that making the list and adding to it can't race.
 * <p>
 * A new like goes to the top and a song counts once: liking a uri that is already liked changes nothing.
 * A list holds at most {@link PlaylistFile#MAX_SONGS} songs. The {@code createdBy} of the list is the owner,
 * set again by every change so that the display name stays current. A list that can't be read is treated as
 * empty (and left alone) until its owner likes something, which sets it aside as {@code <id>.json.broken}
 * and starts a new list.
 */
public class LikesStore
{
    private static final Logger LOG = LoggerFactory.getLogger(LikesStore.class);

    /** The message of a like (or an Undo) refused because the list already holds as many songs as it can. */
    public static final String FULL = "You've liked 2000 songs — remove some to like more";

    private static final String JSON = ".json";
    private static final String BROKEN = ".broken";
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,20}");
    private static final Comparator<String> TEXT_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.<String>naturalOrder());
    private static final Comparator<PlaylistFile> OWNER_ORDER =
            Comparator.comparing(LikesStore::ownerName, TEXT_ORDER).thenComparing(PlaylistFile::getName);

    private final Path dir;
    private final PlaylistStore store;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** The lists that can't be read and were warned about, each with its {@link #stamp} as of the warning. */
    private final ConcurrentHashMap<Path, String> warned = new ConcurrentHashMap<>();

    /**
     * Likes kept in {@code likesDir} (made by the first like); {@code clock} dates every like and change.
     * A person's list is the playlist named after their Discord id, at the top level of that folder.
     */
    public LikesStore(Path likesDir, Clock clock)
    {
        this.dir = likesDir.toAbsolutePath().normalize();
        this.store = new PlaylistStore(likesDir, clock, this::unreadable);
    }

    /**
     * The person's list, newest like first; empty when they have none or the file can't be read (warned about
     * once, see {@link #unreadable}). Throws {@link IllegalArgumentException} for a user id that is not 1 to 20 digits.
     */
    public Optional<PlaylistFile> get(String userId)
    {
        return store.get(checkUserId(userId), "");
    }

    /** The uris the person has liked, newest first; none when they have no list. */
    public Set<String> uris(String userId)
    {
        return uris(get(userId).orElse(null));
    }

    /** The uris of a list of likes, newest first, each once; none for no list (null). */
    public static Set<String> uris(PlaylistFile list)
    {
        Set<String> uris = new LinkedHashSet<>();
        if(list != null)
            for(Song song : list.getSongs())
                if(song.getUri() != null)
                    uris.add(song.getUri());
        return uris;
    }

    /**
     * Likes the song for {@code owner}: a copy goes to the top of their list ({@code addedBy} the owner,
     * {@code addedAt} now), and the list is made if there is none. A song whose uri is already liked changes
     * nothing and writes nothing. Returns how many songs the list holds afterwards, and whether it was written. A
     * list already at {@link PlaylistFile#MAX_SONGS} refuses a new song with {@link IllegalStateException}({@link #FULL}).
     */
    public Outcome like(Person owner, Song song)
    {
        String id = ownerId(owner);
        if(song == null || song.getUri() == null || song.getUri().isBlank())
            throw new IllegalArgumentException("No song to like");
        synchronized(lockFor(id))
        {
            PlaylistFile current = store.get(id, "").orElse(null);
            if(current == null)
            {
                setAsideBroken(id);
                current = store.create(id, "", owner);
            }
            if(contains(current, song.getUri()))
                return new Outcome(current.getSongs().size(), false);
            if(current.getSongs().size() >= PlaylistFile.MAX_SONGS)
                throw new IllegalStateException(FULL);
            return new Outcome(store.addSongs(id, "", Collections.singletonList(song), 0, owner, list -> list.setCreatedBy(owner))
                    .getSongs().size(), true);
        }
    }

    /**
     * Takes every song with this uri out of the owner's list. Returns how many songs the list holds afterwards, and
     * whether it was written: a uri that is not liked, or no list at all (0 songs), writes nothing.
     */
    public Outcome unlike(Person owner, String uri)
    {
        String id = ownerId(owner);
        if(uri == null || uri.isBlank())
            throw new IllegalArgumentException("No song to unlike");
        String wanted = PlaylistFile.cut(uri); // as the uri of a stored song was cut
        synchronized(lockFor(id))
        {
            PlaylistFile current = store.get(id, "").orElse(null);
            if(current == null)
                return new Outcome(0, false);
            if(!contains(current, wanted))
                return new Outcome(current.getSongs().size(), false);
            return new Outcome(store.update(id, "", list ->
            {
                list.setCreatedBy(owner);
                list.getSongs().removeIf(each -> wanted.equals(each.getUri()));
            }).getSongs().size(), true);
        }
    }

    /**
     * Takes the song with this id out of the owner's list; the result holds it and its place, so an Undo can
     * {@link #reinsert} it there. Throws {@link NoSuchElementException} when the owner has no list or no such song.
     */
    public PlaylistStore.Removed removeSong(Person owner, String songId)
    {
        String id = ownerId(owner);
        synchronized(lockFor(id))
        {
            return store.removeSong(id, "", songId, list -> list.setCreatedBy(owner));
        }
    }

    /**
     * Puts a song taken out by {@link #removeSong} back as it was (same id, {@code addedBy} and {@code addedAt};
     * see {@link PlaylistStore#reinsertSong}) at {@code index}, clamped to the list. A song whose uri was liked
     * again meanwhile is not added twice: the list is returned as it is, unwritten. A list already at
     * {@link PlaylistFile#MAX_SONGS} refuses it with {@link IllegalStateException}({@link #FULL}); the owner
     * having no list is a {@link NoSuchElementException}.
     */
    public PlaylistFile reinsert(Person owner, Song song, int index)
    {
        String id = ownerId(owner);
        if(song == null)
            throw new IllegalArgumentException("No song to put back");
        synchronized(lockFor(id))
        {
            PlaylistFile current = store.get(id, "").orElse(null);
            if(current != null)
            {
                if(contains(current, song.getUri()))
                    return current;
                if(current.getSongs().size() >= PlaylistFile.MAX_SONGS)
                    throw new IllegalStateException(FULL);
            }
            return store.reinsertSong(id, "", song, index, list -> list.setCreatedBy(owner));
        }
    }

    /**
     * Moves the song with this id so that it ends up at {@code toIndex} (clamped to the list). Throws
     * {@link NoSuchElementException} when the owner has no list or no such song.
     */
    public PlaylistFile move(Person owner, String songId, int toIndex)
    {
        String id = ownerId(owner);
        synchronized(lockFor(id))
        {
            return store.moveSong(id, "", songId, toIndex, list -> list.setCreatedBy(owner));
        }
    }

    /**
     * Everyone with at least one like, sorted by name (ignoring case; a list with no owner name goes by its id).
     * Lists that can't be read are skipped (with one warning per broken file); so are files that are not named after a
     * user id.
     */
    public List<Liker> people()
    {
        List<Liker> people = new ArrayList<>();
        for(PlaylistFile list : lists())
        {
            if(list.getSongs().isEmpty())
                continue;
            people.add(new Liker(list.getName(), ownerName(list), list.getSongs().size(), PlaylistStore.durationOf(list),
                    list.getUpdatedAt()));
        }
        return people;
    }

    /**
     * The names of the people who liked this uri, in the order of {@link #people}, leaving out the one with
     * the id {@code exceptUserId} (may be null: nobody is left out).
     */
    public List<String> whoLiked(String uri, String exceptUserId)
    {
        List<String> names = new ArrayList<>();
        if(uri == null)
            return names;
        String wanted = PlaylistFile.cut(uri);
        for(PlaylistFile list : lists())
            if(!list.getName().equals(exceptUserId) && contains(list, wanted))
                names.add(ownerName(list));
        return names;
    }

    /**
     * Every uri anyone has liked, as stored (cut to size), from one read of each list; lists that can't be read are
     * skipped, as in {@link #people}. For telling many uris apart at once, such as the local files of one request.
     */
    public Set<String> allUris()
    {
        Set<String> uris = new HashSet<>();
        for(PlaylistFile list : lists())
            for(Song song : list.getSongs())
                if(song.getUri() != null)
                    uris.add(song.getUri());
        return uris;
    }

    /**
     * Every person's likes in one list: the lists in the order of {@link #people}, only the first song of each uri
     * (the one from the earliest list, newest first), then shuffled with {@code random} and cut to {@code cap}
     * songs, so that whose songs make the cut doesn't depend on the order of the names. The songs are the copies
     * read from disk.
     */
    public List<Song> everyone(Random random, int cap)
    {
        List<Song> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(PlaylistFile list : lists())
            for(Song song : list.getSongs())
                if(song.getUri() != null && seen.add(song.getUri()))
                    merged.add(song);
        Collections.shuffle(merged, random);
        if(cap < merged.size())
            merged.subList(Math.max(0, cap), merged.size()).clear();
        return merged;
    }

    /** Every list that can be read, each read once, in the order of {@link #people}. */
    private List<PlaylistFile> lists()
    {
        List<PlaylistFile> lists = new ArrayList<>();
        for(String id : userIds())
            store.get(id, "").ifPresent(lists::add);
        lists.sort(OWNER_ORDER);
        return lists;
    }

    /** The ids that have a {@code .json} file in the likes folder (none when it does not exist yet). */
    private List<String> userIds()
    {
        List<String> ids = new ArrayList<>();
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for(Path child : stream)
            {
                String file = child.getFileName().toString();
                String id = file.endsWith(JSON) ? file.substring(0, file.length() - JSON.length()) : "";
                if(USER_ID.matcher(id).matches() && Files.isRegularFile(child))
                    ids.add(id);
            }
        }
        catch(NoSuchFileException | NotDirectoryException e)
        {
            return ids;
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not list the liked songs", e);
        }
        catch(DirectoryIteratorException e)
        {
            throw new UncheckedIOException("Could not list the liked songs", e.getCause());
        }
        return ids;
    }

    /**
     * Moves the person's file, when there is one that {@link #get} could not read, to {@code <id>.json.broken}
     * ({@code .broken-2}, {@code .broken-3}… when that name is taken, so an earlier copy is never overwritten).
     * Call with the person's lock held.
     */
    private void setAsideBroken(String id)
    {
        Path file = store.pathFor(id, "");
        if(!Files.isRegularFile(file))
            return;
        try
        {
            for(int n = 1; ; n++)
            {
                Path target = file.resolveSibling(file.getFileName() + BROKEN + (n == 1 ? "" : "-" + n));
                try
                {
                    Files.move(file, target); // refuses to replace an existing file
                    warned.remove(file); // a broken file that ever takes its place is a new one
                    LOG.warn("Liked songs file {} can't be read: kept as {} and a new list started", file, target);
                    return;
                }
                catch(FileAlreadyExistsException taken)
                {
                    // try the next number
                }
            }
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not set aside the unreadable liked songs file " + file.getFileName(), e);
        }
    }

    /**
     * A list that can't be read, as the store finds it: warned about once, not at every read (every song change has
     * every panel ask who liked the song, which reads every list), and again only when the file changes.
     */
    private void unreadable(Path file, String why)
    {
        String stamp = stamp(file);
        if(!stamp.equals(warned.put(file, stamp)))
            LOG.warn("Skipping liked songs file {}: {} (not warned about again until it changes)", file, why);
    }

    /** What tells one version of a file from the next: its last-modified time and size ("" when they can't be read). */
    private static String stamp(Path file)
    {
        try
        {
            return Files.getLastModifiedTime(file) + " " + Files.size(file);
        }
        catch(IOException e)
        {
            return "";
        }
    }

    /** True when a song of the list has this uri. */
    private static boolean contains(PlaylistFile list, String uri)
    {
        if(uri == null)
            return false;
        for(Song song : list.getSongs())
            if(uri.equals(song.getUri()))
                return true;
        return false;
    }

    /** The name to show for the list's owner: the one in the file, else the id the file is named after. */
    public static String ownerName(PlaylistFile list)
    {
        Person owner = list.getCreatedBy();
        return owner == null || owner.getName() == null || owner.getName().isBlank() ? list.getName() : owner.getName();
    }

    private static String ownerId(Person owner)
    {
        if(owner == null)
            throw new IllegalArgumentException("Who likes is required");
        return checkUserId(owner.getId());
    }

    /** The id itself when it is 1 to 20 digits (what the file name is made of); else {@link IllegalArgumentException}. */
    private static String checkUserId(String userId)
    {
        if(userId == null || !USER_ID.matcher(userId).matches())
            throw new IllegalArgumentException("Invalid user id");
        return userId;
    }

    private Object lockFor(String userId)
    {
        return locks.computeIfAbsent(userId, key -> new Object());
    }

    /** What a {@link #like} or {@link #unlike} did. */
    public static final class Outcome
    {
        private final int count;
        private final boolean changed;

        Outcome(int count, boolean changed)
        {
            this.count = count;
            this.changed = changed;
        }

        /** How many songs the list holds afterwards. */
        public int getCount()
        {
            return count;
        }

        /** Whether the list was written; false when it already was as asked (or there is none to unlike from). */
        public boolean isChanged()
        {
            return changed;
        }
    }

    /** One person with likes, as {@link #people} lists them. */
    public static final class Liker
    {
        private final String id;
        private final String name;
        private final int count;
        private final long durationMs;
        private final Instant updatedAt;

        Liker(String id, String name, int count, long durationMs, Instant updatedAt)
        {
            this.id = id;
            this.name = name;
            this.count = count;
            this.durationMs = durationMs;
            this.updatedAt = updatedAt;
        }

        /** The Discord user id. */
        public String getId()
        {
            return id;
        }

        /** The owner's display name as of their last change; the id when the list carries none. */
        public String getName()
        {
            return name;
        }

        /** How many songs they like; at least 1. */
        public int getCount()
        {
            return count;
        }

        /** Total length of the songs. */
        public long getDurationMs()
        {
            return durationMs;
        }

        /** When the list last changed; null when unknown. */
        public Instant getUpdatedAt()
        {
            return updatedAt;
        }
    }
}
