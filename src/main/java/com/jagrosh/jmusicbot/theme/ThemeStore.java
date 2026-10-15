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
package com.jagrosh.jmusicbot.theme;

import com.jagrosh.jmusicbot.theme.ThemeFile.Background;
import com.jagrosh.jmusicbot.theme.ThemeFile.Person;
import com.jagrosh.jmusicbot.utils.AtomicFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns every read and write of the {@code Themes/} folder: {@code <id>.json} files (format 1) and, through
 * {@link ThemeImages}, the background pictures. It also decides who may see and change what: public themes
 * are everybody's to use, private ones only their creator's, only the creator edits or deletes, the bot
 * owner may delete or hide public ones (and delete or un-hide the ones he hid, which he finds with
 * {@link #hiddenByOwner}, by name only), and nobody has more than {@value #MAX_PER_USER} themes. Each
 * change runs under a per-theme lock and is written atomically; reads take no lock. A theme the bot owner
 * hid is private wherever it is read, whatever its file says. Files that can't be read as a theme are
 * skipped with a log warning and left alone.
 *
 * <p>Errors: {@link IllegalArgumentException} for values that aren't allowed or a picture that isn't
 * one, {@link PictureTooLargeException} for a picture over the limit, {@link ThemeForbiddenException}
 * when the person can see the theme but may not do that, {@link NoSuchElementException} for a theme that
 * doesn't exist or isn't visible to them, and {@link IllegalStateException} for the theme limit and for a
 * theme that changed since the editor loaded it.
 */
public class ThemeStore
{
    private static final Logger LOG = LoggerFactory.getLogger(ThemeStore.class);

    /** How many themes one person can have, public and private together. */
    public static final int MAX_PER_USER = 10;
    /** The biggest picture accepted: 8 MB. */
    public static final long MAX_PICTURE_BYTES = 8L * 1024 * 1024;

    private static final String JSON = ".json";
    /** Temp files of a theme being written: {@code .theme-<uuid>.tmp}, left behind only by a hard kill. */
    private static final String TEMP_GLOB = ".theme-*.tmp";
    /** A temp file older than this belongs to no write that is still going. */
    private static final Duration STALE_TEMP = Duration.ofHours(1);
    private static final Comparator<ThemeFile> NAME_ORDER = Comparator.comparing(ThemeFile::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ThemeFile::getName).thenComparing(ThemeFile::getId);

    private static final String NOT_FOUND = "Theme not found";
    private static final String LOG_IN = "Log in to create themes";
    private static final String ONLY_CREATOR_CHANGES = "Only the creator can change this theme";
    private static final String ONLY_CREATOR_DELETES = "Only the creator can delete this theme";
    private static final String ONLY_BOT_OWNER = "Only the bot owner can do that";
    private static final String HIDDEN = "Hidden by the bot owner";
    private static final String LIMIT = "You can have up to " + MAX_PER_USER + " themes";
    private static final String CHANGED = "This theme changed — reload it";
    private static final String INCOMPLETE = "The theme is incomplete";
    private static final String NEEDS_PICTURE = "Choose a picture or remove the background";
    private static final String PICTURE_AND_REMOVE = "Choose a picture or remove the background, not both";
    private static final String NO_BACKGROUND_SETTINGS = "Background settings are missing";

    private final Path dir;
    private final ThemeImages images;
    private final Clock clock;
    private final Random random = new SecureRandom();
    private final ConcurrentHashMap<String, Object> themeLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    /**
     * A store over {@code themesDir} (made when the first theme is written) whose pictures go to {@code images};
     * {@code clock} stamps every change. Deletes the temp files that a hard kill left behind in either folder.
     */
    public ThemeStore(Path themesDir, ThemeImages images, Clock clock)
    {
        this.dir = themesDir.toAbsolutePath().normalize();
        this.images = Objects.requireNonNull(images);
        this.clock = Objects.requireNonNull(clock);
        Instant stale = clock.instant().minus(STALE_TEMP);
        images.deleteStaleTemps(stale);
        ThemeImages.deleteOlderThan(dir, TEMP_GLOB, stale);
    }

    /** An uploaded picture: the stream to read it from (the caller closes it) and the size the upload announced, or a negative number when unknown. */
    public static final class PictureUpload
    {
        public final InputStream in;
        public final long declaredSize;

        public PictureUpload(InputStream in, long declaredSize)
        {
            this.in = Objects.requireNonNull(in);
            this.declaredSize = declaredSize;
        }
    }

    /** What the bot owner is told about a theme he hid: its id, name and the creator's name, and nothing more. */
    public static final class HiddenEntry
    {
        private final String id;
        private final String name;
        private final String creatorName;

        private HiddenEntry(String id, String name, String creatorName)
        {
            this.id = id;
            this.name = name;
            this.creatorName = creatorName;
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }

        public String getCreatorName()
        {
            return creatorName;
        }
    }

    /**
     * The themes this viewer can use: every public theme and the viewer's own, sorted by name without regard to
     * case. A viewer of {@code null} (nobody logged in) sees the public ones only.
     */
    public List<ThemeFile> visibleTo(String viewerId)
    {
        List<ThemeFile> visible = new ArrayList<>();
        for(ThemeFile theme : readAll())
            if(canSee(theme, viewerId))
                visible.add(theme);
        visible.sort(NAME_ORDER);
        return visible;
    }

    /** The theme, when it exists and this viewer can see it; empty for a missing, unreadable or invisible one. */
    public Optional<ThemeFile> get(String id, String viewerId)
    {
        return stored(id).filter(theme -> canSee(theme, viewerId));
    }

    /** How many themes this person has made, public, private and hidden ones together. */
    public int countOwnedBy(String userId)
    {
        int count = 0;
        for(ThemeFile theme : readAll())
            if(isCreator(theme, userId))
                count++;
        return count;
    }

    /**
     * Makes a new theme for {@code owner} from the values a browser may set (everything else in {@code values}
     * is ignored) and returns it as stored: version 1, stamped with the clock. The picture is saved first, then
     * the theme; if the theme can't be written the picture is deleted again. A theme with a background needs a
     * {@code picture}, and a picture needs a background.
     *
     * @throws ThemeForbiddenException when {@code owner} is null
     * @throws IllegalStateException when the owner already has {@value #MAX_PER_USER} themes
     */
    public ThemeFile create(ThemeFile values, Person owner, PictureUpload picture)
    {
        if(owner == null)
            throw new ThemeForbiddenException(LOG_IN);
        requireComplete(values);
        if(values.getBackground() != null && picture == null)
            throw new IllegalArgumentException(NEEDS_PICTURE);
        checkUpload(values, picture, false);

        // counting and creating happen under one lock per person, so two requests can't both take the last place
        synchronized(lockFor(userLocks, owner.getId()))
        {
            if(countOwnedBy(owner.getId()) >= MAX_PER_USER)
                throw new IllegalStateException(LIMIT);
            ThemeFile theme = new ThemeFile();
            theme.setId(newUnusedId());
            theme.setOwner(owner);
            theme.setHiddenByOwner(false);
            theme.setVersion(1);
            Instant now = clock.instant();
            theme.setCreatedAt(now);
            theme.setUpdatedAt(now);
            Background background = values.getBackground();
            copyValues(values, theme, background == null ? null : background.withImage(null));

            String added = null;
            boolean written = false;
            try
            {
                if(picture != null)
                {
                    added = images.save(picture.in, MAX_PICTURE_BYTES);
                    theme.setBackground(theme.getBackground().withImage(added));
                }
                write(pathOf(theme.getId()), theme);
                written = true;
                return theme;
            }
            finally
            {
                if(!written && added != null)
                    images.delete(added);
            }
        }
    }

    /**
     * Changes the creator's theme to {@code values} if it is still at {@code expectedVersion}, and returns it
     * as stored. The background follows the editor: with {@code removeBackground} it goes; with settings and a
     * new {@code picture} the picture is replaced; with settings and no picture the stored picture stays; with
     * no settings at all it goes too. Replaced and removed pictures are deleted after the theme was written.
     * A hidden theme stays hidden and can't be made public.
     *
     * @throws NoSuchElementException when the theme doesn't exist or isn't visible to the viewer
     * @throws ThemeForbiddenException when the viewer isn't its creator, or tries to publish a hidden theme
     * @throws IllegalStateException when the theme is no longer at {@code expectedVersion}
     */
    public ThemeFile update(String id, String viewerId, long expectedVersion, ThemeFile values, PictureUpload picture, boolean removeBackground)
    {
        requireComplete(values);
        checkUpload(values, picture, removeBackground);
        Path json = existing(id);
        synchronized(lockFor(themeLocks, id))
        {
            ThemeFile theme = stored(id).orElseThrow(() -> new NoSuchElementException(NOT_FOUND));
            if(!canSee(theme, viewerId))
                throw new NoSuchElementException(NOT_FOUND);
            if(!isCreator(theme, viewerId))
                throw new ThemeForbiddenException(ONLY_CREATOR_CHANGES);
            if(theme.getVersion() != expectedVersion)
                throw new IllegalStateException(CHANGED);
            if(theme.isHiddenByOwner() && ThemeFile.PUBLIC.equals(values.getVisibility()))
                throw new ThemeForbiddenException(HIDDEN);
            Background wanted = removeBackground ? null : values.getBackground();
            String previous = imageOf(theme);
            if(wanted != null && picture == null && previous == null)
                throw new IllegalArgumentException(NEEDS_PICTURE);

            String added = null;
            boolean written = false;
            try
            {
                if(picture != null)
                    added = images.save(picture.in, MAX_PICTURE_BYTES);
                copyValues(values, theme, wanted == null ? null : wanted.withImage(added != null ? added : previous));
                theme.setVersion(theme.getVersion() + 1);
                theme.setUpdatedAt(clock.instant());
                write(json, theme);
                written = true;
            }
            finally
            {
                if(!written && added != null)
                    images.delete(added);
            }
            if(previous != null && !previous.equals(imageOf(theme)))
                images.delete(previous);
            return theme;
        }
    }

    /**
     * Deletes the theme and its picture, for its creator, or for the bot owner when the theme is public or one he
     * hid. Any other private theme doesn't exist for the owner. A viewer of {@code null} is nobody.
     *
     * @throws NoSuchElementException when the theme doesn't exist or isn't visible to the viewer
     * @throws ThemeForbiddenException when the viewer can see the theme but may not delete it
     */
    public void delete(String id, String viewerId, boolean viewerIsOwner)
    {
        Path json = existing(id);
        synchronized(lockFor(themeLocks, id))
        {
            ThemeFile theme = stored(id).orElseThrow(() -> new NoSuchElementException(NOT_FOUND));
            boolean creator = isCreator(theme, viewerId);
            // somebody not logged in is no owner
            boolean moderator = viewerIsOwner && viewerId != null;
            if(!creator && !canSee(theme, viewerId) && !(moderator && theme.isHiddenByOwner()))
                throw new NoSuchElementException(NOT_FOUND);
            // what is left is a public theme, or one the owner hid, and those are the bot owner's to moderate
            if(!creator && !moderator)
                throw new ThemeForbiddenException(ONLY_CREATOR_DELETES);
            try
            {
                Files.deleteIfExists(json);
            }
            catch(IOException e)
            {
                throw new UncheckedIOException("Could not delete theme " + json.getFileName(), e);
            }
            // the picture only after the theme: nothing should ever name a picture that is gone
            images.delete(imageOf(theme));
        }
    }

    /**
     * For the bot owner: hides a public theme (it becomes private to its creator, flagged {@code hiddenByOwner}, and
     * its creator can't make it public) or, with {@code hidden} false, clears the flag from a hidden one, which stays
     * private. Nothing changes, and no new version is made, when it is already so. Plain private themes are not the
     * owner's to see. Returns nothing on purpose: once a theme is private, what its creator has put in it (a new
     * picture, say) is none of the owner's business.
     *
     * @throws ThemeForbiddenException when {@code viewerIsOwner} is false
     * @throws NoSuchElementException when the theme doesn't exist or is private (and not hidden)
     */
    public void hide(String id, boolean viewerIsOwner, boolean hidden)
    {
        if(!viewerIsOwner)
            throw new ThemeForbiddenException(ONLY_BOT_OWNER);
        Path json = existing(id);
        synchronized(lockFor(themeLocks, id))
        {
            ThemeFile theme = stored(id).orElseThrow(() -> new NoSuchElementException(NOT_FOUND));
            if(!ThemeFile.PUBLIC.equals(theme.getVisibility()) && !theme.isHiddenByOwner())
                throw new NoSuchElementException(NOT_FOUND);
            if(theme.isHiddenByOwner() == hidden)
                return;
            theme.setHiddenByOwner(hidden);
            theme.setVisibility(ThemeFile.PRIVATE);
            theme.setVersion(theme.getVersion() + 1);
            theme.setUpdatedAt(clock.instant());
            write(json, theme);
        }
    }

    /**
     * For the bot owner: the themes he hid, so that he can un-hide or delete them, by name (as their creators
     * last left it) and creator only, sorted by name without regard to case. Nothing else of a hidden theme
     * leaves the store.
     *
     * @throws ThemeForbiddenException when {@code viewerIsOwner} is false
     */
    public List<HiddenEntry> hiddenByOwner(boolean viewerIsOwner)
    {
        if(!viewerIsOwner)
            throw new ThemeForbiddenException(ONLY_BOT_OWNER);
        List<ThemeFile> hidden = new ArrayList<>();
        for(ThemeFile theme : readAll())
            if(theme.isHiddenByOwner())
                hidden.add(theme);
        hidden.sort(NAME_ORDER);
        List<HiddenEntry> entries = new ArrayList<>();
        for(ThemeFile theme : hidden)
            entries.add(new HiddenEntry(theme.getId(), theme.getName(), theme.getOwner().getName()));
        return entries;
    }

    // ---- access rules

    /** Public themes are everybody's; any other theme is its creator's alone. */
    private static boolean canSee(ThemeFile theme, String viewerId)
    {
        return ThemeFile.PUBLIC.equals(theme.getVisibility()) || isCreator(theme, viewerId);
    }

    private static boolean isCreator(ThemeFile theme, String viewerId)
    {
        return viewerId != null && viewerId.equals(theme.getOwner().getId());
    }

    // ---- checks on what an editor sent

    /** Values from {@link ThemeFile#valuesFromClient} are complete; anything else built by hand may not be. */
    private static void requireComplete(ThemeFile values)
    {
        if(values == null || values.getName() == null || values.getVisibility() == null || values.getBase() == null
                || values.getColors() == null || values.getFont() == null)
            throw new IllegalArgumentException(INCOMPLETE);
    }

    /** What can be said about an upload without reading it: it needs background settings, can't go with "remove", and must not announce more than the limit. */
    private static void checkUpload(ThemeFile values, PictureUpload picture, boolean removeBackground)
    {
        if(picture == null)
            return;
        if(removeBackground)
            throw new IllegalArgumentException(PICTURE_AND_REMOVE);
        if(values.getBackground() == null)
            throw new IllegalArgumentException(NO_BACKGROUND_SETTINGS);
        if(picture.declaredSize > MAX_PICTURE_BYTES)
            throw new PictureTooLargeException(ThemeImages.TOO_LARGE);
    }

    /** Copies the values an editor may set from {@code from} to {@code to}, with {@code background} as the background. */
    private static void copyValues(ThemeFile from, ThemeFile to, Background background)
    {
        to.setName(from.getName());
        to.setVisibility(from.getVisibility());
        to.setBase(from.getBase());
        to.setBackground(background);
        to.setEffects(from.isEffects());
        to.setColors(from.getColors());
        to.setRadiusScale(from.getRadiusScale());
        to.setFont(from.getFont());
        to.setMini(from.getMini());
    }

    private static String imageOf(ThemeFile theme)
    {
        return theme.getBackground() == null ? null : theme.getBackground().getImage();
    }

    // ---- files

    private Path pathOf(String id)
    {
        return dir.resolve(id + JSON);
    }

    /** The file of a theme that exists; anything that can't be a theme id, or has no file, is not found. Never takes a lock. */
    private Path existing(String id)
    {
        if(id == null || !ThemeFile.ID_PATTERN.matcher(id).matches() || !Files.isRegularFile(pathOf(id)))
            throw new NoSuchElementException(NOT_FOUND);
        return pathOf(id);
    }

    private String newUnusedId()
    {
        String id;
        do
        {
            id = ThemeFile.newId(random);
        }
        while(Files.exists(pathOf(id)));
        return id;
    }

    /** The stored theme with this id, read now; empty when there is none or its file isn't a valid theme. */
    private Optional<ThemeFile> stored(String id)
    {
        if(id == null || !ThemeFile.ID_PATTERN.matcher(id).matches())
            return Optional.empty();
        return readStored(pathOf(id));
    }

    /** Every theme file that can be read, in no particular order. A file that can't be is logged and skipped. */
    private List<ThemeFile> readAll()
    {
        List<ThemeFile> themes = new ArrayList<>();
        for(Path json : themeFiles())
        {
            try
            {
                readStored(json).ifPresent(themes::add);
            }
            catch(RuntimeException e)
            {
                LOG.warn("Skipping theme file {}: {}", json.getFileName(), e.toString());
            }
        }
        return themes;
    }

    /** The files named like a theme ({@code <12 characters>.json}); none when the folder isn't there. */
    private List<Path> themeFiles()
    {
        if(!Files.isDirectory(dir))
            return Collections.emptyList();
        List<Path> files = new ArrayList<>();
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + JSON))
        {
            for(Path file : stream)
            {
                String name = file.getFileName().toString();
                if(name.endsWith(JSON) && ThemeFile.ID_PATTERN.matcher(name.substring(0, name.length() - JSON.length())).matches())
                    files.add(file);
            }
        }
        catch(DirectoryIteratorException e)
        {
            throw new UncheckedIOException("Could not list the themes", e.getCause());
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not list the themes", e);
        }
        return files;
    }

    /**
     * The theme in this file, with a hidden theme made private whatever the file says. Empty when the file isn't
     * there, isn't a plain file, isn't a valid theme (logged), or holds a theme with another id than its name says.
     */
    private Optional<ThemeFile> readStored(Path json)
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
            throw new UncheckedIOException("Could not read theme " + json.getFileName(), e);
        }
        ThemeFile theme;
        try
        {
            theme = ThemeFile.fromJson(text);
        }
        catch(IllegalArgumentException e)
        {
            LOG.warn("Skipping theme file {}: {}", json.getFileName(), e.getMessage());
            return Optional.empty();
        }
        if(!json.getFileName().toString().equals(theme.getId() + JSON))
        {
            LOG.warn("Skipping theme file {}: it holds the theme {}", json.getFileName(), theme.getId());
            return Optional.empty();
        }
        if(theme.isHiddenByOwner())
            theme.setVisibility(ThemeFile.PRIVATE);
        return Optional.of(theme);
    }

    /** Writes the theme to {@code target} through a temp file in the same folder and an atomic move. */
    void write(Path target, ThemeFile theme)
    {
        Path temp = dir.resolve(".theme-" + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try
        {
            Files.createDirectories(dir);
            Files.write(temp, theme.toJson().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            AtomicFiles.replace(temp, target);
            moved = true;
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not write theme " + target.getFileName(), e);
        }
        finally
        {
            if(!moved)
                deleteQuietly(temp);
        }
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

    private static Object lockFor(ConcurrentHashMap<String, Object> locks, String key)
    {
        return locks.computeIfAbsent(key, k -> new Object());
    }
}
