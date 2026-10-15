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
import com.jagrosh.jmusicbot.utils.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every read and write of the {@code Playlists/} folder: {@code <folder>/<name>.json} files
 * (format 2) and, until they are converted, the old {@code <name>.txt} playlists, which are only
 * readable. Each change runs under a per-playlist lock and is written atomically; reads take no
 * lock. The file name is a playlist's identity, folders are {@code /}-separated relative paths
 * ({@code ""} is the top level). Deleting moves a playlist to {@code Playlists/.deleted/}, where it
 * stays for 7 days and can be restored.
 */
public class PlaylistStore
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistStore.class);

    private static final String JSON = ".json";
    private static final String TXT = ".txt";
    private static final String DELETED_DIR = ".deleted";
    private static final String LEGACY_DIR = ".legacy-txt";
    /** Where {@link LikesStore} keeps everyone's liked songs; never part of the playlists a person sees. */
    private static final String LIKED_DIR = ".liked";
    private static final String[] RESERVED = {DELETED_DIR, LEGACY_DIR, LIKED_DIR};
    private static final char BYTE_ORDER_MARK = 0xFEFF;
    private static final int MAX_NAME_BYTES = 200;
    /** How long a deleted playlist can be restored. */
    private static final Duration RETENTION = Duration.ofDays(7);
    /** A trash file name: {@code <epochMillis>-<name>}, plus {@code -2}, {@code -3}… when that name was taken. */
    private static final Pattern TRASH_STAMP = Pattern.compile("(\\d{1,15})-(.+)");
    static final String TAKEN = "A playlist with that name already exists";
    static final String NOT_FOUND = "Playlist not found";
    private static final String FOLDER_NOT_FOUND = "Folder not found";
    private static final String DELETED_NOT_FOUND = "Deleted playlist not found";
    /** The message of every change refused because the playlist is still an old {@code .txt}. */
    public static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final String FULL = "Playlist is full";
    private static final String SONG_NOT_FOUND = "Song not found";
    /** The extra edit of the song operations that the public ones don't make. */
    private static final Consumer<PlaylistFile> NO_MORE = file -> { };
    private static final Comparator<String> TEXT_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.<String>naturalOrder());
    private static final Comparator<Entry> ENTRY_ORDER =
            Comparator.comparing(Entry::getFolder, TEXT_ORDER).thenComparing(Entry::getName, TEXT_ORDER);
    private static final Comparator<Deleted> NEWEST_FIRST =
            Comparator.comparing(Deleted::getDeletedAt, Comparator.<Instant>reverseOrder())
                    .thenComparing(Deleted::getId, Comparator.<String>reverseOrder());

    private final Path root;
    private final Clock clock;
    /** Told about every {@code .json} file that is not a playlist when it is read: the file and why. */
    private final BiConsumer<Path, String> unreadable;
    private final ConcurrentHashMap<Path, Object> locks = new ConcurrentHashMap<>();

    /**
     * A store over {@code root} (made when the first playlist is written); {@code clock} stamps every change. A file
     * that is not a playlist is skipped with a warning at every read.
     */
    public PlaylistStore(Path root, Clock clock)
    {
        this(root, clock, (file, why) -> LOG.warn("Skipping playlist file {}: {}", file, why));
    }

    /**
     * As {@link #PlaylistStore(Path, Clock)}, except that each file that is not a playlist is handed to {@code unreadable}
     * (the file and why) instead of the warning.
     */
    PlaylistStore(Path root, Clock clock, BiConsumer<Path, String> unreadable)
    {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.unreadable = unreadable;
    }

    /** Every playlist in every folder, sorted by folder then name. Unreadable (corrupt) files are skipped. */
    public List<Entry> tree()
    {
        List<Entry> entries = new ArrayList<>();
        collectEntries(root, "", entries);
        entries.sort(ENTRY_ORDER);
        return entries;
    }

    /** Every folder path below the top level (nested ones included), sorted. */
    public List<String> folders()
    {
        List<String> folders = new ArrayList<>();
        collectFolders(root, "", folders);
        folders.sort(TEXT_ORDER);
        return folders;
    }

    /**
     * The one playlist that text typed by a person means, as {@link #findAll} reads it; empty when it
     * means none or more than one. Never throws for odd input: text that is no usable name finds nothing.
     */
    public Optional<Entry> find(String nameOrPath)
    {
        List<Entry> found = findAll(nameOrPath);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * The playlists that text typed by a person could mean. {@code Folder/Name} (any depth: the part after
     * the last {@code /} is the name, the rest the folder) is that playlist. A bare name is the playlist of
     * that name at the top level, which has no folder to type, if there is one; otherwise every playlist with
     * that name, in any folder. Playlists that match exactly are returned if there are any; only when none
     * does, the ones that differ in upper or lower case. More than one result means the text is ambiguous.
     * Empty for text that names nothing, including text that is no usable name. So {@code find(entry.getPath())}
     * is always {@code entry}.
     */
    public List<Entry> findAll(String nameOrPath)
    {
        if(nameOrPath == null)
            return Collections.emptyList();
        String typed = nameOrPath.trim();
        int slash = typed.lastIndexOf('/');
        String folder = slash < 0 ? null : typed.substring(0, slash); // null: any folder
        String name = typed.substring(slash + 1);
        if(name.isEmpty())
            return Collections.emptyList();
        List<Entry> entries = tree();
        if(folder == null)
        {
            List<Entry> topLevel = matching(entries, "", name, false); // the one playlist whose path is just its name
            if(!topLevel.isEmpty())
                return topLevel;
        }
        List<Entry> exact = matching(entries, folder, name, false);
        return exact.isEmpty() ? matching(entries, folder, name, true) : exact;
    }

    private static List<Entry> matching(List<Entry> entries, String folder, String name, boolean ignoreCase)
    {
        List<Entry> found = new ArrayList<>();
        for(Entry entry : entries)
            if(same(entry.getName(), name, ignoreCase) && (folder == null || same(entry.getFolder(), folder, ignoreCase)))
                found.add(entry);
        return found;
    }

    private static boolean same(String a, String b, boolean ignoreCase)
    {
        return ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    /**
     * The playlist, or a read-only view of the old {@code .txt} when there is no {@code .json} (JSON
     * wins when both exist). Empty when missing or corrupt; throws {@link IllegalArgumentException}
     * for an invalid name or folder.
     */
    public Optional<PlaylistFile> get(String name, String folder)
    {
        Path json = pathFor(name, folder);
        if(Files.isRegularFile(json))
            return readJson(json);
        Path txt = txtOf(json);
        if(Files.isRegularFile(txt))
        {
            Optional<PlaylistFile> view = readLegacy(txt);
            if(view.isPresent())
                return view;
            // the converter moved the .txt away after writing the .json
            return readJson(json);
        }
        return Optional.empty();
    }

    /** Makes an empty playlist; {@code by} may be null. A name taken by a {@code .json} or a {@code .txt} is refused. */
    public PlaylistFile create(String name, String folder, Person by)
    {
        Path json = pathFor(name, folder);
        synchronized(lockFor(json))
        {
            if(Files.exists(json) || Files.exists(txtOf(json)))
                throw new IllegalStateException(TAKEN);
            Instant now = clock.instant();
            PlaylistFile file = new PlaylistFile();
            file.setCreatedBy(by);
            file.setCreatedAt(now);
            file.setVersion(0); // saved as 1
            return save(json, file, now);
        }
    }

    /** Renames a playlist within its folder. */
    public PlaylistFile rename(String name, String folder, String newName)
    {
        return relocate(pathFor(name, folder), pathFor(newName, folder));
    }

    /** Moves a playlist to another folder (made when missing), keeping its name. */
    public PlaylistFile move(String name, String folder, String toFolder)
    {
        return relocate(pathFor(name, folder), pathFor(name, toFolder));
    }

    /** Makes a folder, with any missing parents; does nothing when it exists. */
    public void createFolder(String path)
    {
        if(path == null || path.isEmpty())
            throw new IllegalArgumentException("Folder name is required");
        Path dir = folderPath(path);
        try
        {
            Files.createDirectories(dir);
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not create folder " + path, e);
        }
    }

    /**
     * Moves a playlist to the Deleted folder as {@code .deleted/<epochMillis>-<name>.json}, stamped with who
     * deleted it ({@code by} may be null), when and from which folder. The playlist stays restorable for
     * 7 days. An unconverted {@code .txt} is refused, as every change is.
     */
    public Deleted delete(String name, String folder, Person by)
    {
        Path json = pathFor(name, folder);
        synchronized(lockFor(json))
        {
            return sendToDeleted(json, current(json), folder == null ? "" : folder, by);
        }
    }

    /**
     * Sends every playlist in the folder and its subfolders to the Deleted folder (each remembers its own
     * folder), then removes the folders that are left empty. Playlists the store can't read, and the folders
     * holding them, stay where they are. Returns how many playlists were moved. The top level can't be
     * deleted; a folder holding an unconverted {@code .txt} anywhere in it is refused before anything moves.
     */
    public int deleteFolder(String path, Person by)
    {
        if(path == null || path.isEmpty())
            throw new IllegalArgumentException("The top-level folder can't be deleted");
        Path dir = folderPath(path);
        if(!Files.isDirectory(dir))
            throw new NoSuchElementException(FOLDER_NOT_FOUND);
        List<Path> dirs = new ArrayList<>();
        collectDirs(dir, dirs);
        for(Path each : dirs)
            if(holdsLegacy(children(each)))
                throw new IllegalStateException(UPGRADING);
        int moved = 0;
        for(Path each : dirs)
        {
            String folder = folderOf(each);
            for(Path child : children(each))
                if(isPlaylistFile(child, JSON) && deleteFile(child, folder, by))
                    moved++;
        }
        for(int i = dirs.size() - 1; i >= 0; i--) // subfolders before the folders holding them
            removeIfEmpty(dirs.get(i));
        return moved;
    }

    /** What is in the Deleted folder, newest first. Files that can't be read are skipped (and logged). */
    public List<Deleted> deleted()
    {
        Instant now = clock.instant();
        List<Deleted> entries = new ArrayList<>();
        for(Path trash : trashFiles())
        {
            PlaylistFile file = readTrash(trash).orElse(null);
            if(file == null)
                continue;
            Instant deletedAt = deletionTime(trash, file);
            if(deletedAt == null)
                LOG.warn("Skipping deleted playlist file {}: no deletion time", trash);
            else
                entries.add(deletedEntry(trash, file, deletedAt, now));
        }
        entries.sort(NEWEST_FIRST);
        return entries;
    }

    /**
     * Puts a deleted playlist back in the folder it came from (made again when missing; the top level when
     * that folder is no longer usable). A name taken there, by a {@code .json} or a {@code .txt}, gets
     * {@code (restored)}, then {@code (restored 2)}… added. Throws {@link IllegalArgumentException} for an
     * id that is not the name of a file in the Deleted folder and {@link NoSuchElementException} when there
     * is no such file (or it can't be read).
     */
    public PlaylistFile restore(String deletedId)
    {
        return restoreWithFolder(deletedId).getPlaylist();
    }

    /** As {@link #restore}, and also says which folder the playlist landed in (it may differ from its original folder). */
    public Restored restoreWithFolder(String deletedId)
    {
        Path trash = trashFile(deletedId);
        while(true)
        {
            PlaylistFile file = readTrash(trash).orElseThrow(() -> new NoSuchElementException(DELETED_NOT_FOUND));
            Path target = restoreTarget(trash, file);
            PlaylistFile restored = restoreInto(trash, target, file);
            if(restored != null)
                return new Restored(restored, folderOf(target.getParent()));
            // the name was taken while this waited for the locks: look for the next free one
        }
    }

    /**
     * Deletes the entries of the Deleted folder that are more than 7 days old, by the store's clock, and
     * returns how many went. A file that can't be read counts from the time in its name; with neither it stays.
     */
    public int purgeExpired()
    {
        Instant limit = clock.instant().minus(RETENTION);
        int purged = 0;
        for(Path trash : trashFiles())
        {
            synchronized(lockFor(trash)) // a restore of this file is either done or not started
            {
                Instant deletedAt = deletionTime(trash, readTrash(trash).orElse(null));
                if(deletedAt == null || !deletedAt.isBefore(limit))
                    continue;
                try
                {
                    if(Files.deleteIfExists(trash))
                        purged++;
                }
                catch(IOException e)
                {
                    LOG.warn("Could not purge deleted playlist file {}: {}", trash, e.getMessage());
                }
            }
        }
        return purged;
    }

    /**
     * The old {@code .txt} playlists that have no {@code .json} yet, sorted by folder then name, with their
     * lines read from disk (whole, unlike the cut view of {@link #get}). A file that can't be read, or whose
     * name or folder the store does not allow, is skipped (and logged).
     */
    List<Legacy> legacyEntries()
    {
        List<Path> dirs = new ArrayList<>();
        collectDirs(root, dirs);
        List<Legacy> found = new ArrayList<>();
        for(Path dir : dirs)
        {
            String folder = folderOf(dir);
            for(Path txt : txtFiles(children(dir), false))
            {
                try
                {
                    pathFor(baseName(txt, TXT), folder); // a name the store would refuse can't be converted
                    Legacy legacy = readLegacyFile(txt, folder);
                    if(legacy != null)
                        found.add(legacy);
                }
                catch(IllegalArgumentException | UncheckedIOException e)
                {
                    LOG.warn("Skipping old playlist file {}: {}", txt, e.getMessage());
                }
            }
        }
        found.sort(Comparator.comparing(Legacy::getFolder, TEXT_ORDER).thenComparing(Legacy::getName, TEXT_ORDER));
        return found;
    }

    /**
     * Moves every {@code .txt} that has a {@code .json} beside it to {@code .legacy-txt/<folder>/}. That
     * happens when the process stopped after a converted playlist was written and before its {@code .txt}
     * moved; left in place, it would come back as the playlist once the {@code .json} was deleted. Returns
     * how many were moved; one that can't be moved is logged and left.
     */
    int moveLeftoverTxts()
    {
        List<Path> dirs = new ArrayList<>();
        collectDirs(root, dirs);
        int moved = 0;
        for(Path dir : dirs)
        {
            String folder = folderOf(dir);
            for(Path txt : txtFiles(children(dir), true))
            {
                try
                {
                    Path json = pathFor(baseName(txt, TXT), folder);
                    synchronized(lockFor(json))
                    {
                        if(Files.isRegularFile(json) && Files.isRegularFile(txt))
                        {
                            moveToLegacyFolder(txt, folder);
                            moved++;
                        }
                    }
                }
                catch(IllegalArgumentException | UncheckedIOException e)
                {
                    LOG.warn("Could not move aside old playlist file {}: {}", txt, e.getMessage());
                }
            }
        }
        return moved;
    }

    /**
     * Saves a playlist converted from its {@code .txt} and only then moves the {@code .txt} to
     * {@code .legacy-txt/<folder>/}, all under the playlist's lock. The file is named after the playlist and
     * stamped as version 1 and updated now. A {@code .json} that already exists is not touched (the
     * {@code .txt} is just moved away). Returns true when this call wrote the {@code .json}; false when it
     * did not, including when the {@code .txt} is no longer there.
     */
    boolean writeConverted(String name, String folder, PlaylistFile converted)
    {
        Path json = pathFor(name, folder);
        Path txt = txtOf(json);
        synchronized(lockFor(json))
        {
            if(!Files.isRegularFile(txt))
                return false;
            boolean written = !Files.exists(json);
            if(written)
            {
                converted.setVersion(0); // saved as 1
                save(json, converted, clock.instant());
            }
            moveToLegacyFolder(txt, folder == null ? "" : folder); // only now that the .json is on disk
            return written;
        }
    }

    /**
     * Moves the {@code .txt} into {@code .legacy-txt/<folder>/}, as {@code <name>-2.txt}, {@code -3}… when
     * that name is taken: an earlier copy is never overwritten. The folder is one the caller has already checked.
     */
    private void moveToLegacyFolder(Path txt, String folder)
    {
        Path dir = root.resolve(LEGACY_DIR);
        if(!folder.isEmpty())
            for(String segment : folder.split("/", -1))
                dir = child(dir, segment);
        String name = baseName(txt, TXT);
        try
        {
            Files.createDirectories(dir);
            for(int n = 1; ; n++)
            {
                Path target = child(dir, (n == 1 ? name : name + "-" + n) + TXT);
                try
                {
                    Files.move(txt, target); // refuses to replace an existing file
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
            throw new UncheckedIOException("Could not move " + txt.getFileName() + " to " + LEGACY_DIR, e);
        }
    }

    /** The {@code .json} file of a playlist; throws {@link IllegalArgumentException} when the name or folder is not allowed. */
    public Path pathFor(String name, String folder)
    {
        checkSegment(name, "Playlist name");
        return child(folderPath(folder), name + JSON);
    }

    /**
     * Adds copies of {@code songs} at {@code index} (clamped to the list; null = the end). Each copy gets a
     * new id, {@code addedBy = by} (may be null) and {@code addedAt = now}. All or nothing: a batch that
     * would take the playlist past {@link PlaylistFile#MAX_SONGS} adds nothing and is refused.
     */
    public PlaylistFile addSongs(String name, String folder, List<Song> songs, Integer index, Person by)
    {
        return addSongs(name, folder, songs, index, by, NO_MORE);
    }

    /** As {@link #addSongs(String, String, List, Integer, Person)}; {@code also} edits the playlist after the songs went in, in the same write. */
    PlaylistFile addSongs(String name, String folder, List<Song> songs, Integer index, Person by, Consumer<PlaylistFile> also)
    {
        if(songs == null || songs.isEmpty())
            throw new IllegalArgumentException("No songs to add");
        return update(name, folder, file ->
        {
            List<Song> list = file.getSongs();
            if(list.size() + songs.size() > PlaylistFile.MAX_SONGS)
                throw new IllegalStateException(FULL);
            Set<String> ids = new HashSet<>();
            for(Song existing : list)
                ids.add(existing.getId());
            Instant now = clock.instant();
            List<Song> added = new ArrayList<>(songs.size());
            for(Song source : songs)
                added.add(PlaylistFile.copyForPlaylist(source, ids, by, now));
            list.addAll(index == null ? list.size() : Math.max(0, Math.min(index, list.size())), added);
            also.accept(file);
        });
    }

    /** Takes the song out of the playlist; the result holds it and its place, so an Undo can add it back there. */
    public Removed removeSong(String name, String folder, String songId)
    {
        return removeSong(name, folder, songId, NO_MORE);
    }

    /** As {@link #removeSong(String, String, String)}; {@code also} edits the playlist after the song went out, in the same write. */
    Removed removeSong(String name, String folder, String songId, Consumer<PlaylistFile> also)
    {
        Song[] song = new Song[1];
        int[] index = new int[1];
        PlaylistFile file = update(name, folder, f ->
        {
            index[0] = indexOfSong(f, songId);
            song[0] = f.getSongs().remove(index[0]);
            also.accept(f);
        });
        return new Removed(song[0], index[0], file);
    }

    /**
     * Puts a song that was taken out by {@link #removeSong} back exactly as it was: same {@code addedBy} and
     * {@code addedAt}, and the same id unless another song has taken it since (then it gets a fresh one). It goes
     * to {@code index} (clamped to the list). A playlist already at {@link PlaylistFile#MAX_SONGS} refuses it.
     * {@code song} is copied; the caller's object is left as it is.
     */
    public PlaylistFile reinsertSong(String name, String folder, Song song, int index)
    {
        return reinsertSong(name, folder, song, index, NO_MORE);
    }

    /** As {@link #reinsertSong(String, String, Song, int)}; {@code also} edits the playlist after the song went in, in the same write. */
    PlaylistFile reinsertSong(String name, String folder, Song song, int index, Consumer<PlaylistFile> also)
    {
        if(song == null)
            throw new IllegalArgumentException("No song to put back");
        return update(name, folder, file ->
        {
            List<Song> list = file.getSongs();
            if(list.size() >= PlaylistFile.MAX_SONGS)
                throw new IllegalStateException(FULL);
            Set<String> ids = new HashSet<>();
            for(Song existing : list)
                ids.add(existing.getId());
            boolean idFree = song.getId() != null && !ids.contains(song.getId());
            Song copy = PlaylistFile.copyForPlaylist(song, ids, song.getAddedBy(), song.getAddedAt());
            if(idFree)
                copy.setId(song.getId());
            list.add(Math.max(0, Math.min(index, list.size())), copy);
            also.accept(file);
        });
    }

    /** Moves the song so that it ends up at {@code toIndex} (clamped to the list). */
    public PlaylistFile moveSong(String name, String folder, String songId, int toIndex)
    {
        return moveSong(name, folder, songId, toIndex, NO_MORE);
    }

    /** As {@link #moveSong(String, String, String, int)}; {@code also} edits the playlist after the song moved, in the same write. */
    PlaylistFile moveSong(String name, String folder, String songId, int toIndex, Consumer<PlaylistFile> also)
    {
        return update(name, folder, f ->
        {
            List<Song> list = f.getSongs();
            Song song = list.remove(indexOfSong(f, songId));
            list.add(Math.max(0, Math.min(toIndex, list.size())), song);
            also.accept(f);
        });
    }

    /** Turns the playlist's shuffle flag on or off. */
    public PlaylistFile setShuffle(String name, String folder, boolean on)
    {
        return update(name, folder, f -> f.setShuffle(on));
    }

    /**
     * Fills in an entry that could not be loaded earlier: takes {@code uri, title, author, durationMs,
     * artworkUrl, unresolved} from {@code resolved}; the entry keeps its id, {@code addedBy} and {@code addedAt}.
     */
    public PlaylistFile replaceSong(String name, String folder, String songId, Song resolved)
    {
        return update(name, folder, f ->
        {
            Song entry = f.getSongs().get(indexOfSong(f, songId));
            entry.setUri(resolved.getUri());
            entry.setTitle(resolved.getTitle());
            entry.setAuthor(resolved.getAuthor());
            entry.setDurationMs(resolved.getDurationMs());
            entry.setArtworkUrl(resolved.getArtworkUrl());
            entry.setUnresolved(resolved.isUnresolved());
        });
    }

    /**
     * Locks the playlist, reads its current JSON, lets {@code change} edit it, then bumps
     * {@code version} and {@code updatedAt} and writes it. Nothing is written when {@code change} throws.
     * Throws {@link NoSuchElementException} for an unknown playlist and {@link IllegalStateException}
     * for an unconverted {@code .txt}.
     */
    PlaylistFile update(String name, String folder, Consumer<PlaylistFile> change)
    {
        Path json = pathFor(name, folder);
        synchronized(lockFor(json))
        {
            PlaylistFile file = current(json);
            change.accept(file);
            return save(json, file, clock.instant());
        }
    }

    /** Writes the file to {@code target} through a temp file in the same folder and an atomic move. */
    void write(Path target, PlaylistFile file)
    {
        Path dir = target.getParent();
        Path temp = dir.resolve(".pl-" + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try
        {
            Files.createDirectories(dir);
            Files.write(temp, file.toJson().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            AtomicFiles.replace(temp, target);
            moved = true;
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not write playlist " + target.getFileName(), e);
        }
        finally
        {
            if(!moved)
                deleteQuietly(temp);
        }
    }

    /**
     * The playlist's current JSON, for a change. Call with the playlist's lock held. A playlist that
     * only exists as {@code .txt} is refused (every change goes through here); a corrupt file counts as missing.
     */
    private PlaylistFile current(Path json)
    {
        Optional<PlaylistFile> file = readJson(json);
        if(file.isPresent())
            return file.get();
        if(!Files.exists(json) && Files.isRegularFile(txtOf(json)))
            throw new IllegalStateException(UPGRADING);
        throw new NoSuchElementException(NOT_FOUND);
    }

    /** Where the song with this id is in the list; {@link NoSuchElementException} when there is none. */
    private static int indexOfSong(PlaylistFile file, String songId)
    {
        List<Song> songs = file.getSongs();
        for(int i = 0; i < songs.size(); i++)
            if(songs.get(i).getId().equals(songId))
                return i;
        throw new NoSuchElementException(SONG_NOT_FOUND);
    }

    /** Bumps {@code version}, stamps {@code updatedAt} with {@code now}, names the file after its path and writes it. */
    private PlaylistFile save(Path json, PlaylistFile file, Instant now)
    {
        file.setName(baseName(json, JSON));
        file.setVersion(file.getVersion() + 1);
        file.setUpdatedAt(now);
        write(json, file);
        return file;
    }

    /** Moves the playlist's file to {@code target} (a new name, folder or both) under both paths' locks. */
    private PlaylistFile relocate(Path source, Path target)
    {
        return underBothLocks(source, target, () ->
        {
            PlaylistFile file = current(source);
            if(Files.exists(target) || Files.exists(txtOf(target)))
                throw new IllegalStateException(TAKEN);
            // the new file first: a failure part way leaves the playlist where it was, never lost
            save(target, file, clock.instant());
            try
            {
                Files.delete(source);
            }
            catch(IOException e)
            {
                deleteQuietly(target);
                throw new UncheckedIOException("Could not remove the old playlist file " + source.getFileName(), e);
            }
            return file;
        });
    }

    /**
     * Stamps the playlist as deleted and moves it to the Deleted folder. Call with the playlist's lock held.
     * The trash file is written first and the original removed after, so a failure never loses the playlist.
     */
    private Deleted sendToDeleted(Path json, PlaylistFile file, String folder, Person by)
    {
        Instant now = clock.instant();
        file.setDeletedBy(by);
        file.setDeletedAt(now);
        file.setOriginalFolder(folder);
        Path trash;
        synchronized(lockFor(trashDir())) // picks and fills the name in one step; never held while waiting for another lock
        {
            trash = freeTrashPath(now, baseName(json, JSON));
            write(trash, file);
        }
        try
        {
            Files.delete(json);
        }
        catch(IOException e)
        {
            deleteQuietly(trash);
            throw new UncheckedIOException("Could not remove the old playlist file " + json.getFileName(), e);
        }
        return deletedEntry(trash, file, now, now);
    }

    /** Sends one playlist of a folder to the Deleted folder; false (and left alone) when it can't be read. */
    private boolean deleteFile(Path json, String folder, Person by)
    {
        synchronized(lockFor(json))
        {
            PlaylistFile file;
            try
            {
                file = current(json);
            }
            catch(NoSuchElementException e)
            {
                return false; // corrupt, or removed since the folder was listed
            }
            sendToDeleted(json, file, folder, by);
            return true;
        }
    }

    /** {@code .deleted/<epochMillis>-<name>.json}, or the same with {@code -2}, {@code -3}… when that file exists. */
    private Path freeTrashPath(Instant at, String name)
    {
        String stem = at.toEpochMilli() + "-" + name;
        Path trash = child(trashDir(), stem + JSON);
        for(int n = 2; Files.exists(trash); n++)
            trash = child(trashDir(), stem + "-" + n + JSON);
        return trash;
    }

    /** The entry of the Deleted folder for this file, with its days left as of {@code now}. */
    private static Deleted deletedEntry(Path trash, PlaylistFile file, Instant deletedAt, Instant now)
    {
        String folder = file.getOriginalFolder() == null ? "" : file.getOriginalFolder();
        return new Deleted(trash.getFileName().toString(), trashedName(trash, file), folder, file.getDeletedBy(),
                deletedAt, daysLeft(deletedAt, now));
    }

    /** Whole days left before the purge, rounded up; 0 once the 7 days are over. */
    private static int daysLeft(Instant deletedAt, Instant now)
    {
        Duration left = Duration.between(now.minus(RETENTION), deletedAt);
        if(left.compareTo(RETENTION) > 0)
            left = RETENTION; // stamped in the future (the clock was set back)
        if(left.isNegative() || left.isZero())
            return 0;
        long days = left.toDays();
        return (int) (left.equals(Duration.ofDays(days)) ? days : days + 1);
    }

    /** The playlist's own name: from the file, else from the file name without its time stamp. */
    private static String trashedName(Path trash, PlaylistFile file)
    {
        if(file.getName() != null)
            return file.getName();
        String stem = baseName(trash, JSON);
        Matcher stamped = TRASH_STAMP.matcher(stem);
        return stamped.matches() ? stamped.group(2) : stem;
    }

    /** When the playlist was deleted: from its file, else from the time in the file name; null when neither has one. */
    private static Instant deletionTime(Path trash, PlaylistFile file)
    {
        if(file != null && file.getDeletedAt() != null)
            return file.getDeletedAt();
        Matcher stamped = TRASH_STAMP.matcher(baseName(trash, JSON));
        return stamped.matches() ? Instant.ofEpochMilli(Long.parseLong(stamped.group(1))) : null;
    }

    /** The file of a deleted playlist, from an id the caller supplied: only a plain {@code .json} file name qualifies. */
    private Path trashFile(String id)
    {
        if(id == null || id.isEmpty())
            throw new IllegalArgumentException("Deleted playlist id is required");
        if(id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid deleted playlist id");
        if(!id.endsWith(JSON) || id.length() == JSON.length())
            throw new IllegalArgumentException("Invalid deleted playlist id");
        Path trash = child(trashDir(), id); // exactly a file directly inside .deleted
        if(!Files.isRegularFile(trash))
            throw new NoSuchElementException(DELETED_NOT_FOUND);
        return trash;
    }

    /** Where a deleted playlist goes back to: its folder and name, or the first free {@code Name (restored)} variant. */
    private Path restoreTarget(Path trash, PlaylistFile file)
    {
        String name = trashedName(trash, file);
        checkSegment(name, "Playlist name");
        Path dir;
        try
        {
            dir = folderPath(file.getOriginalFolder());
        }
        catch(IllegalArgumentException e)
        {
            dir = root; // the folder is not usable any more
        }
        for(int n = 0; ; n++)
        {
            String candidate = n == 0 ? name : withSuffix(name, n == 1 ? " (restored)" : " (restored " + n + ")");
            Path json = child(dir, candidate + JSON);
            if(!Files.exists(json) && !Files.exists(txtOf(json)))
                return json;
        }
    }

    /** {@code name + suffix}, with the name cut short (never in the middle of a character) if the result would pass the byte limit. */
    static String withSuffix(String name, String suffix)
    {
        int room = MAX_NAME_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        for(int i = 0; i < name.length(); )
        {
            int codePoint = name.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            bytes += character.getBytes(StandardCharsets.UTF_8).length;
            if(bytes > room)
                break;
            kept.append(character);
            i += character.length();
        }
        return kept + suffix;
    }

    /**
     * Writes the trash file's playlist to {@code target} (cleared of its deleted fields) and removes the trash
     * file, holding the locks of both. Null when {@code target} was taken in the meantime, so the caller picks again.
     */
    private PlaylistFile restoreInto(Path trash, Path target, PlaylistFile planned)
    {
        return underBothLocks(trash, target, () ->
        {
            // read again under the lock: a racing restore may have removed it, and the name may even have been reused
            PlaylistFile file = readTrash(trash).orElseThrow(() -> new NoSuchElementException(DELETED_NOT_FOUND));
            if(!trashedName(trash, file).equals(trashedName(trash, planned))
                    || !Objects.equals(file.getOriginalFolder(), planned.getOriginalFolder())
                    || Files.exists(target) || Files.exists(txtOf(target)))
                return null;
            file.setDeletedBy(null);
            file.setDeletedAt(null);
            file.setOriginalFolder(null);
            save(target, file, clock.instant());
            try
            {
                Files.delete(trash);
            }
            catch(IOException e)
            {
                deleteQuietly(target);
                throw new UncheckedIOException("Could not remove the deleted playlist file " + trash.getFileName(), e);
            }
            return file;
        });
    }

    /** Runs {@code action} holding the locks of both paths, always taken in the same order so no two callers can wait for each other. */
    private <T> T underBothLocks(Path a, Path b, Supplier<T> action)
    {
        Path lower = a.compareTo(b) <= 0 ? a : b;
        Path higher = lower == a ? b : a;
        synchronized(lockFor(lower))
        {
            synchronized(lockFor(higher))
            {
                return action.get();
            }
        }
    }

    private Path trashDir()
    {
        return root.resolve(DELETED_DIR);
    }

    /** The playlist files of the Deleted folder (none when it does not exist). */
    private List<Path> trashFiles()
    {
        List<Path> files = new ArrayList<>();
        for(Path child : children(trashDir()))
            if(isPlaylistFile(child, JSON))
                files.add(child);
        return files;
    }

    private Object lockFor(Path json)
    {
        return locks.computeIfAbsent(json, key -> new Object());
    }

    private Optional<PlaylistFile> readJson(Path json)
    {
        Optional<PlaylistFile> file = readParsed(json);
        file.ifPresent(parsed -> parsed.setName(baseName(json, JSON)));
        return file;
    }

    /** A file of the Deleted folder: unlike {@link #readJson} it keeps the name written in the file, and never throws for an I/O failure. */
    private Optional<PlaylistFile> readTrash(Path trash)
    {
        try
        {
            return readParsed(trash);
        }
        catch(UncheckedIOException e)
        {
            LOG.warn("Skipping deleted playlist file {}: {}", trash, e.getMessage());
            return Optional.empty();
        }
    }

    /** The playlist parsed from the file, with the name written in it; empty when missing or not a playlist file. */
    private Optional<PlaylistFile> readParsed(Path json)
    {
        if(!Files.isRegularFile(json))
            return Optional.empty();
        String text;
        try
        {
            text = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
        }
        catch(NoSuchFileException e)
        {
            return Optional.empty();
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not read playlist " + json.getFileName(), e);
        }
        try
        {
            return Optional.of(PlaylistFile.fromJson(text));
        }
        catch(IllegalArgumentException e)
        {
            unreadable.accept(json, e.getMessage());
            return Optional.empty();
        }
    }

    /** The old line format as {@code PlaylistLoader} reads it, except that the list is not shuffled. */
    private Optional<PlaylistFile> readLegacy(Path txt)
    {
        Legacy legacy = readLegacyFile(txt, ""); // the view has no use for the folder
        if(legacy == null)
            return Optional.empty();
        PlaylistFile view = new PlaylistFile();
        view.setName(legacy.getName());
        view.setLegacy(true);
        view.setCreatedAt(legacy.getModified());
        view.setUpdatedAt(legacy.getModified());
        view.setShuffle(legacy.isShuffle());
        Set<String> ids = new HashSet<>();
        for(String line : legacy.getLines())
        {
            Song song = new Song();
            song.setId(PlaylistFile.newSongId(ThreadLocalRandom.current(), ids));
            song.setUri(line);
            song.setTitle(line);
            song.setUnresolved(true);
            view.getSongs().add(song);
        }
        return Optional.of(view);
    }

    /** The old {@code .txt} read from disk, whole lines and all; null when the file is gone. */
    private static Legacy readLegacyFile(Path txt, String folder)
    {
        String text;
        Instant modified;
        try
        {
            text = new String(Files.readAllBytes(txt), StandardCharsets.UTF_8);
            modified = Files.getLastModifiedTime(txt).toInstant();
        }
        catch(NoSuchFileException e)
        {
            return null;
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not read playlist " + txt.getFileName(), e);
        }
        List<String> lines = new ArrayList<>();
        boolean shuffle = parseLegacyLines(text, lines);
        return new Legacy(baseName(txt, TXT), folder, modified, lines, shuffle);
    }

    /**
     * Reads the old line format as {@code PlaylistLoader} does: every song line, trimmed, is added to
     * {@code lines}; blank lines and comments ({@code #}, {@code //}) are skipped. Returns true when a
     * {@code #shuffle} or {@code //shuffle} line is present. A byte order mark at the start is ignored.
     */
    private static boolean parseLegacyLines(String text, List<String> lines)
    {
        boolean shuffle = false;
        boolean first = true;
        for(String raw : text.split("\r\n|\r|\n"))
        {
            String line = raw.trim();
            if(first && !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK)
                line = line.substring(1).trim();
            first = false;
            if(line.isEmpty())
                continue;
            if(line.startsWith("#") || line.startsWith("//"))
            {
                String squeezed = line.replaceAll("\\s+", "");
                if(squeezed.equalsIgnoreCase("#shuffle") || squeezed.equalsIgnoreCase("//shuffle"))
                    shuffle = true;
                continue;
            }
            lines.add(line);
        }
        return shuffle;
    }

    private void collectEntries(Path dir, String folder, List<Entry> entries)
    {
        List<Path> children = children(dir);
        Set<String> jsonNames = new HashSet<>();
        for(Path child : children)
        {
            if(!isPlaylistFile(child, JSON))
                continue;
            String name = baseName(child, JSON);
            jsonNames.add(name); // even a corrupt file holds its name: JSON wins over a .txt
            readJson(child).ifPresent(file -> entries.add(entryOf(file, folder)));
        }
        for(Path child : children)
        {
            if(!isPlaylistFile(child, TXT))
                continue;
            if(!jsonNames.contains(baseName(child, TXT)))
                readLegacy(child).ifPresent(view -> entries.add(entryOf(view, folder)));
        }
        for(Path sub : subfolders(children))
            collectEntries(sub, joinFolder(folder, sub.getFileName().toString()), entries);
    }

    /** {@code dir} and every folder below it, each folder before the ones inside it. */
    private void collectDirs(Path dir, List<Path> dirs)
    {
        dirs.add(dir);
        for(Path sub : subfolders(children(dir)))
            collectDirs(sub, dirs);
    }

    /** True when one of the files is an old {@code .txt} playlist that has no {@code .json} yet. */
    private static boolean holdsLegacy(List<Path> children)
    {
        return !txtFiles(children, false).isEmpty();
    }

    /**
     * The old {@code .txt} playlists among the files: those with no {@code .json} of the same name
     * ({@code withJson} false, still to be converted) or with one beside them (true, left over).
     */
    private static List<Path> txtFiles(List<Path> children, boolean withJson)
    {
        Set<String> jsonNames = new HashSet<>();
        for(Path child : children)
            if(isPlaylistFile(child, JSON))
                jsonNames.add(baseName(child, JSON));
        List<Path> found = new ArrayList<>();
        for(Path child : children)
            if(isPlaylistFile(child, TXT) && jsonNames.contains(baseName(child, TXT)) == withJson)
                found.add(child);
        return found;
    }

    /** A regular file named {@code <something><extension>}. */
    private static boolean isPlaylistFile(Path child, String extension)
    {
        String fileName = child.getFileName().toString();
        return fileName.endsWith(extension) && fileName.length() > extension.length() && Files.isRegularFile(child);
    }

    /** The folder path ({@code a/b}) of a directory below the root. */
    private String folderOf(Path dir)
    {
        List<String> names = new ArrayList<>();
        for(Path part : root.relativize(dir))
            names.add(part.toString());
        return String.join("/", names);
    }

    /** Removes the directory unless something is still in it. */
    private void removeIfEmpty(Path dir)
    {
        try
        {
            Files.delete(dir);
        }
        catch(DirectoryNotEmptyException e)
        {
            // it holds a file the store does not handle (or one that was just added): that stays
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not remove folder " + folderOf(dir), e);
        }
    }

    private void collectFolders(Path dir, String folder, List<String> folders)
    {
        for(Path sub : subfolders(children(dir)))
        {
            String path = joinFolder(folder, sub.getFileName().toString());
            folders.add(path);
            collectFolders(sub, path, folders);
        }
    }

    private static Entry entryOf(PlaylistFile file, String folder)
    {
        return new Entry(file.getName(), folder, file.getSongs().size(), durationOf(file), file.getCreatedBy(),
                file.getUpdatedAt(), file.isLegacy());
    }

    /** Total length of the playlist's songs in milliseconds. */
    static long durationOf(PlaylistFile file)
    {
        long duration = 0;
        for(Song song : file.getSongs())
            duration += song.getDurationMs();
        return duration;
    }

    private static List<Path> subfolders(List<Path> children)
    {
        List<Path> folders = new ArrayList<>();
        for(Path child : children)
            if(Files.isDirectory(child) && !isReserved(child.getFileName().toString()))
                folders.add(child);
        return folders;
    }

    /** The directory's entries; none when it does not exist (the root is only made by the first write). */
    private static List<Path> children(Path dir)
    {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            List<Path> children = new ArrayList<>();
            for(Path child : stream)
                children.add(child);
            return children;
        }
        catch(NoSuchFileException | NotDirectoryException e)
        {
            return Collections.emptyList();
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not list " + dir.getFileName(), e);
        }
        catch(DirectoryIteratorException e)
        {
            throw new UncheckedIOException("Could not list " + dir.getFileName(), e.getCause());
        }
    }

    private Path folderPath(String folder)
    {
        Path dir = root;
        if(folder == null || folder.isEmpty())
            return dir;
        for(String segment : folder.split("/", -1))
        {
            checkSegment(segment, "Folder name");
            dir = child(dir, segment);
        }
        return dir;
    }

    /** {@code dir/segment}, checked to be exactly that: names the OS reads differently never get out of {@code dir}. */
    private static Path child(Path dir, String segment)
    {
        Path child = dir.resolve(segment); // an InvalidPathException (an IllegalArgumentException) for names the OS cannot use
        Path name = child.getFileName();
        if(!dir.equals(child.getParent()) || name == null || !segment.equals(name.toString()))
            throw new IllegalArgumentException("Invalid name: " + segment);
        return child;
    }

    private static void checkSegment(String segment, String what)
    {
        if(segment == null || segment.isBlank())
            throw new IllegalArgumentException(what + " is required");
        if(segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0)
            throw new IllegalArgumentException(what + " can't contain / \\ or null characters");
        if(segment.equals(".") || segment.equals(".."))
            throw new IllegalArgumentException(what + " can't be . or ..");
        if(isReserved(segment))
            throw new IllegalArgumentException(what + " is reserved: " + segment);
        if(segment.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES)
            throw new IllegalArgumentException(what + " is too long (" + MAX_NAME_BYTES + " bytes at most)");
    }

    private static boolean isReserved(String segment)
    {
        for(String reserved : RESERVED)
            if(reserved.equalsIgnoreCase(segment))
                return true;
        return false;
    }

    private static Path txtOf(Path json)
    {
        return json.resolveSibling(baseName(json, JSON) + TXT);
    }

    private static String baseName(Path file, String extension)
    {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - extension.length());
    }

    private static String joinFolder(String folder, String name)
    {
        return folder.isEmpty() ? name : folder + "/" + name;
    }

    private static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch(IOException ignore)
        {
            // nothing more to do; the caller is already reporting the real failure
        }
    }

    /** One line of {@link #tree()}: what the panel lists for a playlist. */
    public static final class Entry
    {
        private final String name;
        private final String folder;
        private final int songCount;
        private final long durationMs;
        private final Person createdBy;
        private final Instant updatedAt;
        private final boolean legacy;

        Entry(String name, String folder, int songCount, long durationMs, Person createdBy, Instant updatedAt,
                boolean legacy)
        {
            this.name = name;
            this.folder = folder;
            this.songCount = songCount;
            this.durationMs = durationMs;
            this.createdBy = createdBy;
            this.updatedAt = updatedAt;
            this.legacy = legacy;
        }

        public String getName()
        {
            return name;
        }

        /** The folder path, {@code ""} for the top level. */
        public String getFolder()
        {
            return folder;
        }

        /** The playlist as a person types it: {@code Folder/Name}, or just the name at the top level. */
        public String getPath()
        {
            return folder.isEmpty() ? name : folder + "/" + name;
        }

        public int getSongCount()
        {
            return songCount;
        }

        /** Total length of the songs; 0 for a legacy playlist, whose lengths are unknown. */
        public long getDurationMs()
        {
            return durationMs;
        }

        /** Null when unknown (a converted or legacy playlist). */
        public Person getCreatedBy()
        {
            return createdBy;
        }

        public Instant getUpdatedAt()
        {
            return updatedAt;
        }

        /** True for a {@code .txt} playlist that has not been converted yet. */
        public boolean isLegacy()
        {
            return legacy;
        }
    }

    /** One entry of the Deleted folder, as {@link #deleted()} lists it. */
    public static final class Deleted
    {
        private final String id;
        private final String name;
        private final String originalFolder;
        private final Person deletedBy;
        private final Instant deletedAt;
        private final int daysLeft;

        Deleted(String id, String name, String originalFolder, Person deletedBy, Instant deletedAt, int daysLeft)
        {
            this.id = id;
            this.name = name;
            this.originalFolder = originalFolder;
            this.deletedBy = deletedBy;
            this.deletedAt = deletedAt;
            this.daysLeft = daysLeft;
        }

        /** The file name inside {@code .deleted/}; what {@link #restore} takes. */
        public String getId()
        {
            return id;
        }

        /** The playlist's name from before it was deleted. */
        public String getName()
        {
            return name;
        }

        /** The folder it was deleted from, {@code ""} for the top level. */
        public String getOriginalFolder()
        {
            return originalFolder;
        }

        /** Null when unknown. */
        public Person getDeletedBy()
        {
            return deletedBy;
        }

        public Instant getDeletedAt()
        {
            return deletedAt;
        }

        /** Whole days left before the purge, rounded up; 0 once the 7 days are over. */
        public int getDaysLeft()
        {
            return daysLeft;
        }
    }

    /** What {@link #restoreWithFolder} brought back: the playlist (under its final name) and the folder it is in now. */
    public static final class Restored
    {
        private final PlaylistFile playlist;
        private final String folder;

        Restored(PlaylistFile playlist, String folder)
        {
            this.playlist = playlist;
            this.folder = folder;
        }

        /** The playlist as saved by the restore; its name carries {@code (restored)} when the old one was taken. */
        public PlaylistFile getPlaylist()
        {
            return playlist;
        }

        /** The folder path it landed in, {@code ""} for the top level. */
        public String getFolder()
        {
            return folder;
        }
    }

    /** An old {@code .txt} playlist as the converter reads it: the lines as written on disk, not the cut view of {@link #get}. */
    static final class Legacy
    {
        private final String name;
        private final String folder;
        private final Instant modified;
        private final List<String> lines;
        private final boolean shuffle;

        Legacy(String name, String folder, Instant modified, List<String> lines, boolean shuffle)
        {
            this.name = name;
            this.folder = folder;
            this.modified = modified;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.shuffle = shuffle;
        }

        String getName()
        {
            return name;
        }

        /** The folder path, {@code ""} for the top level. */
        String getFolder()
        {
            return folder;
        }

        /** When the file was last written. */
        Instant getModified()
        {
            return modified;
        }

        /** The song lines, trimmed, in file order: no blanks, no comments. */
        List<String> getLines()
        {
            return lines;
        }

        /** True when the file has a {@code #shuffle} (or {@code //shuffle}) line. */
        boolean isShuffle()
        {
            return shuffle;
        }
    }

    /** What {@link #removeSong} took out: the song, the index it had and the playlist after the removal. */
    public static final class Removed
    {
        private final Song song;
        private final int index;
        private final PlaylistFile playlist;

        Removed(Song song, int index, PlaylistFile playlist)
        {
            this.song = song;
            this.index = index;
            this.playlist = playlist;
        }

        public Song getSong()
        {
            return song;
        }

        /** Where the song was; adding it back at this index restores the order. */
        public int getIndex()
        {
            return index;
        }

        /** The playlist as saved after the removal (its new version included). */
        public PlaylistFile getPlaylist()
        {
            return playlist;
        }
    }
}
