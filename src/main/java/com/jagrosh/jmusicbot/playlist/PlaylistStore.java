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
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every read and write of the {@code Playlists/} folder: {@code <folder>/<name>.json} files
 * (format 2) and, until they are converted, the old {@code <name>.txt} playlists, which are only
 * readable. Each change runs under a per-playlist lock and is written atomically; reads take no
 * lock. The file name is a playlist's identity, folders are {@code /}-separated relative paths
 * ({@code ""} is the top level).
 */
public class PlaylistStore
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistStore.class);

    private static final String JSON = ".json";
    private static final String TXT = ".txt";
    private static final String[] RESERVED = {".deleted", ".legacy-txt"};
    private static final int MAX_NAME_BYTES = 200;
    private static final int MOVE_ATTEMPTS = 6;
    private static final String TAKEN = "A playlist with that name already exists";
    private static final String NOT_FOUND = "Playlist not found";
    private static final String UPGRADING = "This playlist is still being upgraded — try again in a minute";
    private static final Comparator<String> TEXT_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.<String>naturalOrder());
    private static final Comparator<Entry> ENTRY_ORDER =
            Comparator.comparing(Entry::getFolder, TEXT_ORDER).thenComparing(Entry::getName, TEXT_ORDER);

    private final Path root;
    private final Clock clock;
    private final ConcurrentHashMap<Path, Object> locks = new ConcurrentHashMap<>();

    /** A store over {@code root} (made when the first playlist is written); {@code clock} stamps every change. */
    public PlaylistStore(Path root, Clock clock)
    {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
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
            file.setUpdatedAt(now);
            file.setVersion(0); // saved as 1
            return save(json, file);
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

    /** The {@code .json} file of a playlist; throws {@link IllegalArgumentException} when the name or folder is not allowed. */
    public Path pathFor(String name, String folder)
    {
        checkSegment(name, "Playlist name");
        return child(folderPath(folder), name + JSON);
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
            return save(json, file);
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
            moveIntoPlace(temp, target);
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

    /** Atomic move over the target; plain replace on file systems without atomic moves. */
    private static void moveIntoPlace(Path temp, Path target) throws IOException
    {
        for(int attempt = 1; ; attempt++)
        {
            try
            {
                try
                {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                }
                catch(AtomicMoveNotSupportedException e)
                {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
            catch(AccessDeniedException e)
            {
                // Windows refuses to replace a file while a reader has it open, for a few milliseconds
                if(attempt == MOVE_ATTEMPTS)
                    throw e;
                try
                {
                    Thread.sleep(10L * attempt);
                }
                catch(InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
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

    /** Bumps {@code version} and {@code updatedAt}, names the file after its path and writes it. */
    private PlaylistFile save(Path json, PlaylistFile file)
    {
        file.setName(baseName(json, JSON));
        file.setVersion(file.getVersion() + 1);
        file.setUpdatedAt(clock.instant());
        write(json, file);
        return file;
    }

    /** Moves the playlist's file to {@code target} (a new name, folder or both) under both paths' locks. */
    private PlaylistFile relocate(Path source, Path target)
    {
        Object first = lockFor(source);
        Object second = lockFor(target);
        if(source.toString().compareTo(target.toString()) > 0)
        {
            // one fixed order, so two opposite renames cannot wait for each other
            Object swap = first;
            first = second;
            second = swap;
        }
        synchronized(first)
        {
            synchronized(second)
            {
                PlaylistFile file = current(source);
                if(Files.exists(target) || Files.exists(txtOf(target)))
                    throw new IllegalStateException(TAKEN);
                // the new file first: a failure part way leaves the playlist where it was, never lost
                save(target, file);
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
            }
        }
    }

    private Object lockFor(Path json)
    {
        return locks.computeIfAbsent(json, key -> new Object());
    }

    private Optional<PlaylistFile> readJson(Path json)
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
            PlaylistFile file = PlaylistFile.fromJson(text);
            file.setName(baseName(json, JSON));
            return Optional.of(file);
        }
        catch(IllegalArgumentException e)
        {
            LOG.warn("Skipping playlist file {}: {}", json, e.getMessage());
            return Optional.empty();
        }
    }

    /** The old line format as {@code PlaylistLoader} reads it, except that the list is not shuffled. */
    private Optional<PlaylistFile> readLegacy(Path txt)
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
            return Optional.empty();
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not read playlist " + txt.getFileName(), e);
        }
        PlaylistFile view = new PlaylistFile();
        view.setName(baseName(txt, TXT));
        view.setLegacy(true);
        view.setCreatedAt(modified);
        view.setUpdatedAt(modified);
        Set<String> ids = new HashSet<>();
        for(String raw : text.split("\r\n|\r|\n"))
        {
            String line = raw.trim();
            if(line.isEmpty())
                continue;
            if(line.startsWith("#") || line.startsWith("//"))
            {
                String squeezed = line.replaceAll("\\s+", "");
                if(squeezed.equalsIgnoreCase("#shuffle") || squeezed.equalsIgnoreCase("//shuffle"))
                    view.setShuffle(true);
                continue;
            }
            Song song = new Song();
            String id;
            do
            {
                id = PlaylistFile.newSongId(ThreadLocalRandom.current());
            }
            while(!ids.add(id));
            song.setId(id);
            song.setUri(line);
            song.setTitle(line);
            song.setUnresolved(true);
            view.getSongs().add(song);
        }
        return Optional.of(view);
    }

    private void collectEntries(Path dir, String folder, List<Entry> entries)
    {
        List<Path> children = children(dir);
        Set<String> jsonNames = new HashSet<>();
        for(Path child : children)
        {
            String fileName = child.getFileName().toString();
            if(!fileName.endsWith(JSON) || fileName.length() == JSON.length() || !Files.isRegularFile(child))
                continue;
            String name = baseName(child, JSON);
            jsonNames.add(name); // even a corrupt file holds its name: JSON wins over a .txt
            readJson(child).ifPresent(file -> entries.add(entryOf(file, folder)));
        }
        for(Path child : children)
        {
            String fileName = child.getFileName().toString();
            if(!fileName.endsWith(TXT) || fileName.length() == TXT.length() || !Files.isRegularFile(child))
                continue;
            if(!jsonNames.contains(baseName(child, TXT)))
                readLegacy(child).ifPresent(view -> entries.add(entryOf(view, folder)));
        }
        for(Path sub : subfolders(children))
            collectEntries(sub, joinFolder(folder, sub.getFileName().toString()), entries);
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
        long duration = 0;
        for(Song song : file.getSongs())
            duration += song.getDurationMs();
        return new Entry(file.getName(), folder, file.getSongs().size(), duration, file.getCreatedBy(),
                file.getUpdatedAt(), file.isLegacy());
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
}
