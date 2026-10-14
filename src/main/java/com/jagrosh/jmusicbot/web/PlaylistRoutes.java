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
package com.jagrosh.jmusicbot.web;

import com.jagrosh.jmusicbot.playlist.ImportJobs;
import com.jagrosh.jmusicbot.playlist.LavaTrackResolver;
import com.jagrosh.jmusicbot.playlist.PlaylistConverter;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.TrackResolver;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpResponseException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every playlist endpoint of the web panel: the new ones of spec section 4, and the old ones
 * ({@code /api/playlist/*}, {@code /api/playlists-tree}, {@code playplaylist}, the folder endpoints) as thin wrappers
 * over the same {@link PlaylistStore}. Self-contained so it can run on a bare Javalin: what needs the bot (who is
 * asking, the WebSocket broadcast, joining voice and queueing) comes in through the constructor. Register the routes
 * so the {@code /api/*} auth middleware covers them, as {@link WebServer} does.
 *
 * <p>Bodies are JSON objects, answers are JSON. Errors are {@code {"error": message}}: 400 for a request that is
 * wrong ({@link IllegalArgumentException}), 404 for what is not there ({@link NoSuchElementException}), 409 for what
 * can't be done now ({@link IllegalStateException}), 500 for a disk error. Every change is announced to all
 * connected panels with a {@code playlists} message: {@code {name, folder, version}} for a change inside a playlist,
 * {@code {tree: true}} for anything that changes the tree.
 */
public class PlaylistRoutes
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistRoutes.class);

    private static final String NOT_FOUND = "Playlist not found";
    private static final String SONG_NOT_FOUND = "Song not found";
    private static final String BAD_SONG = "That song can't be added to a playlist";
    private static final String BAD_SONG_LIST = "Songs must be a list of 1 to " + PlaylistFile.MAX_SONGS + " songs";
    private static final String TOO_LATE = "Too late to undo";
    private static final String RELOAD = "Reload the page to edit playlists";
    private static final String STILL_BAD = "Still couldn't load this song";
    private static final String NOT_BROKEN = "This song doesn't need a retry";
    private static final String SAVE_FAILED = "Couldn't save — try again";
    private static final String UNEXPECTED = "Something went wrong";
    private static final String JSON = "application/json";
    /** How long a removed song can be put back. */
    private static final Duration UNDO_WINDOW = Duration.ofSeconds(60);
    /** Removals remembered for Undo at once; the one that expires first makes room. */
    private static final int MAX_UNDO = 1000;
    private static final Pattern WEB_LINK = Pattern.compile("https?://[^\\s/?#]+\\S*", Pattern.CASE_INSENSITIVE);
    private static final String[] SEARCH_PREFIXES = {"ytsearch:", "ytmsearch:", "scsearch:"};

    private final PlaylistStore store;
    private final PlaylistConverter converter;
    private final ImportJobs imports;
    private final TrackResolver resolver;
    private final Function<Context, Person> currentUser;
    private final BiConsumer<String, Object> broadcastAll;
    private final PlayHook play;
    private final Clock clock;
    /** Removed songs that can still be put back, by token. Guarded by itself. */
    private final Map<String, Undo> undos = new HashMap<>();

    /**
     * @param currentUser who is asking, or null when nobody is logged in (web login off); credited for what they add
     * @param broadcastAll sends a message of this type with this data (an {@link JSONObject}) to every connected panel
     * @param play queues a playlist on a server; it answers the request itself
     * @param clock times the Undo window
     */
    public PlaylistRoutes(PlaylistStore store, PlaylistConverter converter, ImportJobs imports, TrackResolver resolver,
            Function<Context, Person> currentUser, BiConsumer<String, Object> broadcastAll, PlayHook play, Clock clock)
    {
        this.store = store;
        this.converter = converter;
        this.imports = imports;
        this.resolver = resolver;
        this.currentUser = currentUser;
        this.broadcastAll = broadcastAll;
        this.play = play;
        this.clock = clock;
    }

    /** Joins a voice channel if need be and starts loading a playlist into a server's queue. */
    public interface PlayHook
    {
        /**
         * Plays the playlist and writes the response to {@code ctx}. May throw the same exceptions as the routes
         * (not found, bad request), as long as it has not written anything yet.
         *
         * @param mode {@code all}, {@code shuffle} or {@code from}
         * @param songId for {@code from}: the song to start with; otherwise null
         */
        void play(Context ctx, String guildId, String name, String folder, String mode, String songId);
    }

    /** One request handler; may throw the exceptions {@link #guarded} turns into answers. */
    private interface Route
    {
        void handle(Context ctx);
    }

    /** Adds the routes to the app. */
    public void register(Javalin app)
    {
        app.get("/api/playlists/tree", guarded(this::tree));
        app.get("/api/playlists/item", guarded(this::item));
        app.post("/api/playlists", guarded(this::create));
        app.post("/api/playlists/rename", guarded(this::rename));
        app.post("/api/playlists/move", guarded(this::move));
        app.post("/api/playlists/delete", guarded(this::delete));
        app.post("/api/playlists/restore", guarded(this::restore));
        app.post("/api/playlists/folder", guarded(this::createFolder));
        app.post("/api/playlists/folder/delete", guarded(this::deleteFolder));
        app.post("/api/playlists/songs/add", guarded(this::addSongs));
        app.post("/api/playlists/songs/remove", guarded(this::removeSong));
        app.post("/api/playlists/songs/undo", guarded(this::undoRemove));
        app.post("/api/playlists/songs/move", guarded(this::moveSong));
        app.post("/api/playlists/songs/retry", guarded(this::retrySong));
        app.post("/api/playlists/shuffle", guarded(this::setShuffle));
        app.post("/api/playlists/import", guarded(this::startImport));
        app.get("/api/playlists/migration", guarded(this::migration));
        app.post("/api/guild/{guildId}/playlists/play", guarded(this::playPlaylist));

        // the old endpoints, for the 0.3.0 desktop app and open panels: same paths and shapes, the store behind them
        app.get("/api/playlists", guarded(this::oldList));
        app.get("/api/playlist/{name}", guarded(this::oldGet));
        app.post("/api/playlist/{name}", guarded(this::oldSave));
        app.delete("/api/playlist/{name}", guarded(this::oldDelete));
        app.post("/api/guild/{guildId}/playplaylist/{name}", guarded(this::oldPlay));
        app.get("/api/playlist-folders", guarded(this::oldFolders));
        app.post("/api/playlist-folder", guarded(this::oldCreateFolder));
        app.delete("/api/playlist-folder", guarded(this::oldDeleteFolder));
        app.put("/api/playlist-move", guarded(this::oldMove));
        app.get("/api/playlists-tree", guarded(this::oldTree));
        app.get("/api/playlist-detail/{name}", guarded(this::oldDetail));
        app.put("/api/playlist-rename/{name}", guarded(this::oldRename));
    }

    /** The WebSocket {@code playlist-import} message for how an import stands. */
    public static JSONObject importProgress(ImportJobs.Progress progress)
    {
        return new JSONObject()
                .put("jobId", progress.getJobId())
                .put("done", progress.getDone())
                .put("total", progress.getTotal())
                .put("failedCount", progress.getFailedCount())
                .put("playlist", orNull(progress.getPlaylistName()))
                .put("folder", progress.getFolder() == null ? "" : progress.getFolder())
                .put("finished", progress.isFinished())
                .put("error", orNull(progress.getError()));
    }

    // ---- errors ------------------------------------------------------------------------------------------------

    /** Runs the route and turns what it throws into the JSON answers described on the class. */
    private Handler guarded(Route route)
    {
        return ctx ->
        {
            try
            {
                route.handle(ctx);
            }
            catch(IllegalArgumentException e)
            {
                fail(ctx, 400, e.getMessage(), "Invalid request");
            }
            catch(NoSuchElementException e)
            {
                fail(ctx, 404, e.getMessage(), NOT_FOUND);
            }
            catch(IllegalStateException e)
            {
                fail(ctx, 409, e.getMessage(), "That can't be done right now");
            }
            catch(UncheckedIOException e)
            {
                LOG.error("Playlist request {} {} failed", ctx.method(), ctx.path(), e);
                fail(ctx, 500, SAVE_FAILED, SAVE_FAILED);
            }
            catch(HttpResponseException e)
            {
                throw e; // Javalin's own answers are not ours to change
            }
            catch(RuntimeException e)
            {
                LOG.error("Playlist request {} {} failed", ctx.method(), ctx.path(), e);
                fail(ctx, 500, UNEXPECTED, UNEXPECTED);
            }
        };
    }

    private static void fail(Context ctx, int status, String message, String fallback)
    {
        String text = message == null || message.trim().isEmpty() ? fallback : message;
        ctx.status(status).contentType(JSON).result(new JSONObject().put("error", text).toString());
    }

    // ---- reading requests --------------------------------------------------------------------------------------

    private static JSONObject body(Context ctx)
    {
        try
        {
            return new JSONObject(ctx.body());
        }
        catch(JSONException e)
        {
            throw new IllegalArgumentException("The request body must be a JSON object");
        }
    }

    /** A text field; null when it is missing or not text. */
    private static String text(JSONObject json, String key)
    {
        Object value = json.opt(key);
        return value instanceof String ? (String) value : null;
    }

    /** A text field that may be left out (null); anything else than text is refused. */
    private static String optionalText(JSONObject json, String key)
    {
        if(json.isNull(key))
            return null;
        String value = text(json, key);
        if(value == null)
            throw new IllegalArgumentException(key + " must be text");
        return value;
    }

    private static String required(JSONObject json, String key, String what)
    {
        String value = text(json, key);
        if(value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(what + " is required");
        return value;
    }

    /** A folder path; a missing or null one is the top level, {@code ""}. */
    private static String folder(JSONObject json, String key)
    {
        String value = optionalText(json, key);
        return value == null ? "" : value;
    }

    private static Integer optionalInt(JSONObject json, String key)
    {
        if(json.isNull(key))
            return null;
        Object value = json.opt(key);
        if(!(value instanceof Number))
            throw new IllegalArgumentException(key + " must be a number");
        long number = ((Number) value).longValue();
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
    }

    private static int requiredInt(JSONObject json, String key)
    {
        Integer value = optionalInt(json, key);
        if(value == null)
            throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String folderParam(Context ctx)
    {
        String folder = ctx.queryParam("folder");
        return folder == null ? "" : folder;
    }

    private Person user(Context ctx)
    {
        return currentUser.apply(ctx);
    }

    // ---- writing answers ---------------------------------------------------------------------------------------

    private static void send(Context ctx, JSONObject json)
    {
        ctx.contentType(JSON).result(json.toString());
    }

    private static void send(Context ctx, JSONArray json)
    {
        ctx.contentType(JSON).result(json.toString());
    }

    /** What every change answers (and announces for a change inside a playlist). */
    private static JSONObject describe(PlaylistFile file, String folder)
    {
        return new JSONObject().put("name", file.getName()).put("folder", folder).put("version", file.getVersion());
    }

    private void announceContent(PlaylistFile file, String folder)
    {
        announce(describe(file, folder));
    }

    private void announceTree()
    {
        announce(new JSONObject().put("tree", true));
    }

    private void announce(JSONObject data)
    {
        try
        {
            broadcastAll.accept("playlists", data);
        }
        catch(RuntimeException e)
        {
            LOG.warn("Could not tell the panels about a playlist change: {}", e.toString()); // the change itself went through
        }
    }

    private static Object orNull(Object value)
    {
        return value == null ? JSONObject.NULL : value;
    }

    private static Object personJson(Person person)
    {
        return person == null ? JSONObject.NULL : new JSONObject().put("id", orNull(person.getId())).put("name", orNull(person.getName()));
    }

    private static Object instantJson(Instant instant)
    {
        return instant == null ? JSONObject.NULL : instant.toString();
    }

    // ---- the new endpoints: reading ----------------------------------------------------------------------------

    private void tree(Context ctx)
    {
        JSONArray playlists = new JSONArray();
        for(PlaylistStore.Entry entry : store.tree())
            playlists.put(new JSONObject()
                    .put("name", entry.getName())
                    .put("folder", entry.getFolder())
                    .put("songCount", entry.getSongCount())
                    .put("durationMs", entry.getDurationMs())
                    .put("createdBy", personJson(entry.getCreatedBy()))
                    .put("updatedAt", instantJson(entry.getUpdatedAt()))
                    .put("legacy", entry.isLegacy()));
        JSONArray deleted = new JSONArray();
        for(PlaylistStore.Deleted gone : store.deleted())
            deleted.put(new JSONObject()
                    .put("id", gone.getId())
                    .put("name", gone.getName())
                    .put("originalFolder", gone.getOriginalFolder())
                    .put("deletedBy", personJson(gone.getDeletedBy()))
                    .put("deletedAt", instantJson(gone.getDeletedAt()))
                    .put("daysLeft", gone.getDaysLeft()));
        send(ctx, new JSONObject().put("folders", new JSONArray(store.folders())).put("playlists", playlists).put("deleted", deleted));
    }

    private void item(Context ctx)
    {
        String name = ctx.queryParam("name");
        if(name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Playlist name is required");
        String folder = folderParam(ctx);
        PlaylistFile file = playlist(name, folder);
        send(ctx, new JSONObject(file.toJson()).put("folder", folder).put("legacy", file.isLegacy()));
    }

    private PlaylistFile playlist(String name, String folder)
    {
        return store.get(name, folder).orElseThrow(() -> new NoSuchElementException(NOT_FOUND));
    }

    private void migration(Context ctx)
    {
        PlaylistConverter.Progress progress = converter.progress();
        send(ctx, new JSONObject().put("total", progress.getTotal()).put("done", progress.getDone()).put("failed", progress.getFailed()));
    }

    // ---- the new endpoints: playlists and folders --------------------------------------------------------------

    private void create(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name").trim();
        String folder = folder(body, "folder");
        PlaylistFile file = store.create(name, folder, user(ctx));
        LOG.info("Web playlist: created '{}' in folder '{}'", name, folder);
        send(ctx, describe(file, folder));
        announceTree();
    }

    private void rename(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        String newName = required(body, "newName", "New name").trim();
        PlaylistFile file = store.rename(name, folder, newName);
        LOG.info("Web playlist: renamed '{}' to '{}' in folder '{}'", name, newName, folder);
        send(ctx, describe(file, folder));
        announceTree();
    }

    private void move(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        if(!body.has("toFolder"))
            throw new IllegalArgumentException("toFolder is required");
        String toFolder = folder(body, "toFolder");
        PlaylistFile file = store.move(name, folder, toFolder);
        LOG.info("Web playlist: moved '{}' from '{}' to '{}'", name, folder, toFolder);
        send(ctx, describe(file, toFolder));
        announceTree();
    }

    private void delete(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        PlaylistStore.Deleted gone = store.delete(name, folder, user(ctx));
        LOG.info("Web playlist: deleted '{}' from folder '{}'", name, folder);
        send(ctx, new JSONObject().put("name", name).put("folder", folder).put("id", gone.getId()));
        announceTree();
    }

    private void restore(Context ctx)
    {
        String id = required(body(ctx), "id", "Deleted playlist id");
        PlaylistStore.Restored restored = store.restoreWithFolder(id);
        LOG.info("Web playlist: restored '{}' to folder '{}'", restored.getPlaylist().getName(), restored.getFolder());
        send(ctx, describe(restored.getPlaylist(), restored.getFolder()));
        announceTree();
    }

    private void createFolder(Context ctx)
    {
        String path = required(body(ctx), "path", "Folder path").trim();
        store.createFolder(path);
        LOG.info("Web playlist: created folder '{}'", path);
        send(ctx, new JSONObject().put("path", path));
        announceTree();
    }

    private void deleteFolder(Context ctx)
    {
        String path = required(body(ctx), "path", "Folder path").trim();
        int moved = store.deleteFolder(path, user(ctx));
        LOG.info("Web playlist: deleted folder '{}' ({} playlist(s) to Deleted)", path, moved);
        send(ctx, new JSONObject().put("path", path).put("moved", moved));
        announceTree();
    }

    // ---- the new endpoints: songs ------------------------------------------------------------------------------

    private void addSongs(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        List<Song> songs = songsFrom(body.opt("songs"));
        Integer index = optionalInt(body, "index");
        PlaylistFile file = store.addSongs(name, folder, songs, index, user(ctx));
        send(ctx, describe(file, folder));
        announceContent(file, folder);
    }

    private void removeSong(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        String songId = required(body, "songId", "Song id");
        PlaylistStore.Removed removed = store.removeSong(name, folder, songId);
        String token = rememberRemoval(name, folder, removed.getSong(), removed.getIndex());
        send(ctx, describe(removed.getPlaylist(), folder).put("undo", token));
        announceContent(removed.getPlaylist(), folder);
    }

    private void undoRemove(Context ctx)
    {
        // Only the token is read from the request: the song, its place and who added it were remembered when it went.
        String token = required(body(ctx), "token", "Undo token");
        Undo undo = takeRemoval(token);
        if(undo == null)
            throw new NoSuchElementException(TOO_LATE);
        PlaylistFile file;
        try
        {
            file = store.reinsertSong(undo.name, undo.folder, undo.song, undo.index);
        }
        catch(RuntimeException e)
        {
            returnRemoval(token, undo); // the playlist may come back, or have room again, before the minute is up
            throw e;
        }
        send(ctx, describe(file, undo.folder));
        announceContent(file, undo.folder);
    }

    private void moveSong(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        String songId = required(body, "songId", "Song id");
        int toIndex = requiredInt(body, "toIndex");
        PlaylistFile file = store.moveSong(name, folder, songId, toIndex);
        send(ctx, describe(file, folder));
        announceContent(file, folder);
    }

    private void retrySong(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        String songId = required(body, "songId", "Song id");
        PlaylistFile current = playlist(name, folder);
        if(current.isLegacy())
            throw new IllegalStateException(PlaylistStore.UPGRADING);
        Song song = null;
        for(Song each : current.getSongs())
            if(songId.equals(each.getId()))
                song = each;
        if(song == null)
            throw new NoSuchElementException(SONG_NOT_FOUND);
        if(!song.isUnresolved())
            throw new IllegalStateException(NOT_BROKEN);
        Song found = song.getUri() == null || song.getUri().trim().isEmpty() ? null : firstLoaded(resolver.resolve(song.getUri()));
        if(found == null)
            throw new IllegalStateException(STILL_BAD);
        PlaylistFile file = store.replaceSong(name, folder, songId, found);
        send(ctx, describe(file, folder));
        announceContent(file, folder);
    }

    private static Song firstLoaded(TrackResolver.Resolved resolved)
    {
        for(Song song : resolved.getSongs())
            if(!song.isUnresolved())
                return song;
        return null;
    }

    private void setShuffle(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        Object on = body.opt("on");
        if(!(on instanceof Boolean))
            throw new IllegalArgumentException("on must be true or false");
        PlaylistFile file = store.setShuffle(name, folder, (Boolean) on);
        send(ctx, describe(file, folder));
        announceContent(file, folder);
    }

    // ---- the new endpoints: import and play --------------------------------------------------------------------

    private void startImport(Context ctx)
    {
        JSONObject body = body(ctx);
        String link = required(body, "link", "Link");
        String name = optionalText(body, "name");
        String into = optionalText(body, "into");
        String folder = folder(body, "folder");
        String jobId = imports.start(link, name == null ? null : name.trim(), folder, into, user(ctx));
        LOG.info("Web playlist: import {} started", jobId);
        send(ctx, new JSONObject().put("jobId", jobId));
    }

    private void playPlaylist(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name");
        String folder = folder(body, "folder");
        String mode = required(body, "mode", "Play mode");
        if(!PlaylistPlayer.ALL.equals(mode) && !PlaylistPlayer.SHUFFLE.equals(mode) && !PlaylistPlayer.FROM.equals(mode))
            throw new IllegalArgumentException("Play mode must be all, shuffle or from");
        String songId = optionalText(body, "songId");
        if(PlaylistPlayer.FROM.equals(mode) && (songId == null || songId.trim().isEmpty()))
            throw new IllegalArgumentException("songId is required to play from a song");
        play.play(ctx, ctx.pathParam("guildId"), name, folder, mode, PlaylistPlayer.FROM.equals(mode) ? songId : null);
    }

    // ---- what a client may put in a playlist (spec section 9, R16) ---------------------------------------------

    /** The songs of a {@code songs} array, cleaned; the whole request is refused when any song can't be taken. */
    private List<Song> songsFrom(Object value)
    {
        if(!(value instanceof JSONArray) || ((JSONArray) value).length() == 0 || ((JSONArray) value).length() > PlaylistFile.MAX_SONGS)
            throw new IllegalArgumentException(BAD_SONG_LIST);
        JSONArray array = (JSONArray) value;
        StoredUris stored = new StoredUris();
        List<Song> songs = new ArrayList<>(array.length());
        for(int i = 0; i < array.length(); i++)
        {
            JSONObject entry = array.optJSONObject(i);
            if(entry == null)
                throw new IllegalArgumentException(BAD_SONG_LIST);
            String uri = text(entry, "uri");
            uri = uri == null ? "" : uri.trim();
            if(!allowed(uri, stored))
                throw new IllegalArgumentException(BAD_SONG);
            Song song = new Song();
            song.setUri(uri);
            String title = text(entry, "title");
            song.setTitle(title == null || title.trim().isEmpty() ? uri : title);
            song.setAuthor(text(entry, "author"));
            Object duration = entry.opt("durationMs");
            song.setDurationMs(duration instanceof Number ? Math.max(0L, ((Number) duration).longValue()) : 0L);
            String artwork = text(entry, "artworkUrl");
            // the queue, search and history rows carry no artwork: a YouTube video gets its standard thumbnail
            song.setArtworkUrl(artwork != null && isWebLink(artwork.trim()) ? artwork.trim()
                    : LavaTrackResolver.youtubeThumbnail(LavaTrackResolver.youtubeIdOf(uri), uri));
            songs.add(song);
        }
        return songs;
    }

    /**
     * True when a web client may put this uri in a playlist: an http(s) link, a {@code ytsearch:} /
     * {@code ytmsearch:} / {@code scsearch:} search, or a local file that some playlist already holds (those come
     * from the old playlists; a client can't make the bot read a file of its choosing).
     */
    private static boolean allowed(String uri, StoredUris stored)
    {
        if(uri.isEmpty() || hasControlCharacters(uri))
            return false;
        return isWebLink(uri) || isSearch(uri) || stored.contains(uri);
    }

    private static boolean isWebLink(String uri)
    {
        return !hasControlCharacters(uri) && WEB_LINK.matcher(uri).matches();
    }

    private static boolean isSearch(String uri)
    {
        for(String prefix : SEARCH_PREFIXES)
            if(uri.startsWith(prefix))
                return !uri.substring(prefix.length()).trim().isEmpty();
        return false;
    }

    private static boolean hasControlCharacters(String text)
    {
        for(int i = 0; i < text.length(); i++)
            if(Character.isISOControl(text.charAt(i)))
                return true;
        return false;
    }

    /** The uris stored in any playlist, read when first asked: most requests have no local file in them. */
    private final class StoredUris
    {
        private Set<String> uris;

        boolean contains(String uri)
        {
            if(uris == null)
            {
                uris = new HashSet<>();
                for(PlaylistStore.Entry entry : store.tree())
                    for(Song song : songsOf(entry))
                        if(song.getUri() != null)
                            uris.add(song.getUri());
            }
            return uris.contains(PlaylistFile.cut(uri));
        }
    }

    /** The songs of a listed playlist; none for one that has gone since, or whose name the store would not open. */
    private List<Song> songsOf(PlaylistStore.Entry entry)
    {
        try
        {
            return store.get(entry.getName(), entry.getFolder()).map(PlaylistFile::getSongs).orElse(new ArrayList<Song>());
        }
        catch(IllegalArgumentException e)
        {
            return new ArrayList<>();
        }
    }

    // ---- Undo of a removed song --------------------------------------------------------------------------------

    /** A removed song, where it was, and until when it can come back. */
    private static final class Undo
    {
        final String name;
        final String folder;
        final Song song;
        final int index;
        final Instant expires;

        Undo(String name, String folder, Song song, int index, Instant expires)
        {
            this.name = name;
            this.folder = folder;
            this.song = song;
            this.index = index;
            this.expires = expires;
        }
    }

    /** Remembers the song for a minute; the token that comes back is the only way to ask for it. */
    private String rememberRemoval(String name, String folder, Song song, int index)
    {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString(); // from a SecureRandom
        synchronized(undos)
        {
            undos.values().removeIf(undo -> !now.isBefore(undo.expires));
            while(undos.size() >= MAX_UNDO)
            {
                String first = null;
                for(Map.Entry<String, Undo> each : undos.entrySet())
                    if(first == null || each.getValue().expires.isBefore(undos.get(first).expires))
                        first = each.getKey();
                undos.remove(first);
            }
            undos.put(token, new Undo(name, folder, song, index, now.plus(UNDO_WINDOW)));
        }
        return token;
    }

    /** The removal for this token, once: null when there is none, or its minute is over. */
    private Undo takeRemoval(String token)
    {
        Undo undo;
        synchronized(undos)
        {
            undo = undos.remove(token);
        }
        return undo != null && clock.instant().isBefore(undo.expires) ? undo : null;
    }

    private void returnRemoval(String token, Undo undo)
    {
        synchronized(undos)
        {
            undos.put(token, undo);
        }
    }

    // ---- the old endpoints -------------------------------------------------------------------------------------

    /** {@code {name, folder, trackCount, items}}: the shape the old panel and the desktop app read. */
    private JSONObject oldPlaylist(PlaylistStore.Entry entry)
    {
        JSONArray items = new JSONArray();
        for(Song song : songsOf(entry))
            items.put(song.getUri() == null ? "" : song.getUri());
        return new JSONObject().put("name", entry.getName()).put("folder", entry.getFolder())
                .put("trackCount", items.length()).put("items", items);
    }

    private static JSONArray oldItems(PlaylistFile file)
    {
        JSONArray items = new JSONArray();
        for(Song song : file.getSongs())
            items.put(song.getUri() == null ? "" : song.getUri());
        return items;
    }

    private void oldList(Context ctx)
    {
        String folder = folderParam(ctx);
        JSONArray result = new JSONArray();
        for(PlaylistStore.Entry entry : store.tree())
            if(entry.getFolder().equals(folder))
                result.put(oldPlaylist(entry));
        send(ctx, result);
    }

    private void oldGet(Context ctx)
    {
        String folder = folderParam(ctx);
        PlaylistFile file = playlist(ctx.pathParam("name"), folder);
        send(ctx, new JSONObject().put("name", file.getName()).put("folder", folder)
                .put("items", oldItems(file)).put("shuffle", file.isShuffle()));
    }

    private void oldDetail(Context ctx)
    {
        String folder = folderParam(ctx);
        PlaylistFile file = playlist(ctx.pathParam("name"), folder);
        send(ctx, new JSONObject().put("name", file.getName()).put("folder", folder).put("items", oldItems(file))
                .put("shuffle", file.isShuffle()).put("trackCount", file.getSongs().size()));
    }

    /** Saving a whole list: a new name is made with those items as songs; an existing playlist is edited in the panel's new way. */
    private void oldSave(Context ctx)
    {
        String name = ctx.pathParam("name").trim();
        JSONObject body = body(ctx);
        String folder = folder(body, "folder");
        Object items = body.opt("items");
        if(!(items instanceof JSONArray))
            throw new IllegalArgumentException("Items are required");
        if(store.get(name, folder).isPresent())
            throw new IllegalStateException(RELOAD);

        StoredUris stored = new StoredUris();
        List<Song> songs = new ArrayList<>();
        int skipped = 0;
        JSONArray array = (JSONArray) items;
        for(int i = 0; i < array.length(); i++)
        {
            Object item = array.get(i);
            String line = item instanceof String ? ((String) item).trim() : null;
            if(JSONObject.NULL.equals(item) || "".equals(line))
                continue; // a blank line was never a song
            if(line == null || !allowed(line, stored))
            {
                skipped++;
                continue;
            }
            Song song = new Song();
            song.setUri(line);
            song.setTitle(line);
            songs.add(song);
        }
        if(songs.size() > PlaylistFile.MAX_SONGS)
            throw new IllegalArgumentException(BAD_SONG_LIST);

        Person by = user(ctx);
        store.create(name, folder, by);
        if(!songs.isEmpty())
            store.addSongs(name, folder, songs, null, by);
        LOG.info("Web playlist: saved '{}' in folder '{}'", name, folder);
        send(ctx, new JSONObject().put("message", "Playlist saved").put("name", name).put("folder", folder).put("skipped", skipped));
        announceTree();
    }

    private void oldDelete(Context ctx)
    {
        String name = ctx.pathParam("name");
        String folder = folderParam(ctx);
        store.delete(name, folder, user(ctx));
        LOG.info("Web playlist: deleted '{}' from folder '{}'", name, folder);
        send(ctx, new JSONObject().put("message", "Playlist deleted").put("name", name));
        announceTree();
    }

    private void oldPlay(Context ctx)
    {
        play.play(ctx, ctx.pathParam("guildId"), ctx.pathParam("name"), folderParam(ctx), PlaylistPlayer.ALL, null);
    }

    private void oldFolders(Context ctx)
    {
        String parent = ctx.queryParam("parent");
        if(parent == null)
            parent = "";
        List<PlaylistStore.Entry> entries = store.tree();
        List<String> folders = store.folders();
        JSONArray result = new JSONArray();
        for(String path : folders)
            if(parentOf(path).equals(parent))
                result.put(new JSONObject().put("name", nameOf(path)).put("path", path)
                        .put("playlistCount", playlistsIn(entries, path)).put("subFolderCount", subFoldersOf(folders, path).size()));
        send(ctx, result);
    }

    private void oldTree(Context ctx)
    {
        Map<String, List<PlaylistStore.Entry>> byFolder = new HashMap<>();
        for(PlaylistStore.Entry entry : store.tree())
            byFolder.computeIfAbsent(entry.getFolder(), key -> new ArrayList<>()).add(entry);
        send(ctx, oldNode("", byFolder, store.folders()));
    }

    /** One folder of the old nested tree, with its playlists and (named) subfolders. */
    private JSONObject oldNode(String path, Map<String, List<PlaylistStore.Entry>> byFolder, List<String> folders)
    {
        JSONArray playlists = new JSONArray();
        for(PlaylistStore.Entry entry : byFolder.getOrDefault(path, new ArrayList<PlaylistStore.Entry>()))
            playlists.put(oldPlaylist(entry));
        JSONArray children = new JSONArray();
        for(String sub : subFoldersOf(folders, path))
            children.put(oldNode(sub, byFolder, folders).put("name", nameOf(sub)));
        return new JSONObject().put("folder", path).put("playlists", playlists).put("playlistCount", playlists.length())
                .put("folders", children).put("subFolderCount", children.length());
    }

    private static List<String> subFoldersOf(List<String> folders, String path)
    {
        List<String> subs = new ArrayList<>();
        for(String folder : folders)
            if(parentOf(folder).equals(path))
                subs.add(folder);
        return subs;
    }

    private static int playlistsIn(List<PlaylistStore.Entry> entries, String path)
    {
        int count = 0;
        for(PlaylistStore.Entry entry : entries)
            if(entry.getFolder().equals(path))
                count++;
        return count;
    }

    private static String parentOf(String path)
    {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String nameOf(String path)
    {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void oldCreateFolder(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Folder name").trim();
        String parent = folder(body, "parent");
        String path = parent.isEmpty() ? name : parent + "/" + name;
        store.createFolder(path);
        LOG.info("Web playlist: created folder '{}'", path);
        send(ctx, new JSONObject().put("message", "Folder created").put("path", path));
        announceTree();
    }

    private void oldDeleteFolder(Context ctx)
    {
        String path = required(body(ctx), "path", "Folder path").trim();
        int moved = store.deleteFolder(path, user(ctx));
        LOG.info("Web playlist: deleted folder '{}' ({} playlist(s) to Deleted)", path, moved);
        send(ctx, new JSONObject().put("message", "Folder deleted").put("path", path));
        announceTree();
    }

    private void oldMove(Context ctx)
    {
        JSONObject body = body(ctx);
        String name = required(body, "name", "Playlist name").trim();
        String fromFolder = folder(body, "fromFolder");
        String toFolder = folder(body, "toFolder");
        store.move(name, fromFolder, toFolder);
        LOG.info("Web playlist: moved '{}' from '{}' to '{}'", name, fromFolder, toFolder);
        send(ctx, new JSONObject().put("message", "Playlist moved").put("name", name).put("folder", toFolder));
        announceTree();
    }

    private void oldRename(Context ctx)
    {
        String oldName = ctx.pathParam("name");
        JSONObject body = body(ctx);
        String newName = required(body, "newName", "New name").trim();
        String folder = folder(body, "folder");
        store.rename(oldName, folder, newName);
        LOG.info("Web playlist: renamed '{}' to '{}' in folder '{}'", oldName, newName, folder);
        send(ctx, new JSONObject().put("message", "Playlist renamed").put("name", newName).put("folder", folder));
        announceTree();
    }
}
