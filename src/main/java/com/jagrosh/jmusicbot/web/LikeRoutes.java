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

import com.jagrosh.jmusicbot.playlist.LikesStore;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Person;
import com.jagrosh.jmusicbot.playlist.PlaylistFile.Song;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The liked-songs endpoints of the web panel (round 2's spec section 3), over a {@link LikesStore}. Self-contained like
 * {@link PlaylistRoutes}, so it runs on a bare Javalin: who is asking, the WebSocket broadcast and the playing come in
 * through the constructor. Register the routes so the {@code /api/*} auth middleware covers them, as {@link WebServer}
 * does.
 *
 * <p>Only the caller's own likes ever change: whose list a change is made to comes from the login session, never from
 * the request. With nobody logged in (web login off) the changes answer 401 and {@code mine} is empty; everything else
 * can be read and played by anyone who may use the panel. Bodies and answers are JSON, and errors are mapped as for the
 * playlists ({@code {"error": message}}, 400 / 404 / 409 for a full list / 500). Every change is announced to all
 * connected panels with a {@code likes} message, {@code {"userId": <whose likes>}}, and nothing about the songs; a like
 * of a song already liked, or an unlike of one that isn't, changes nothing and is not announced.
 */
public class LikeRoutes
{
    private static final Logger LOG = LoggerFactory.getLogger(LikeRoutes.class);

    /** The {@code userId} to play that means every person's likes, merged. */
    static final String EVERYONE = "everyone";

    private static final String LOG_IN = "Log in with Discord to like songs";
    private static final String BAD_SONG = "That song can't be liked";
    private static final String NO_LIKES = "No liked songs";
    private static final String NOTHING_TO_PLAY = "No liked songs to play";
    private static final String SONG_NOT_FOUND = "Song not found";

    private final LikesStore likes;
    private final Predicate<String> uriAlreadyStored;
    private final Function<Context, Person> currentUser;
    private final BiConsumer<String, Object> broadcastAll;
    private final LikesPlayHook play;
    /** Songs taken out of someone's likes that they can still put back. */
    private final UndoTokens<Removal> undos;

    /**
     * @param uriAlreadyStored whether a song with this uri is in some playlist or someone's likes: a local file may be
     *        liked only then
     * @param currentUser who is asking, or null when nobody is logged in (web login off)
     * @param broadcastAll sends a message of this type with this data (an {@link JSONObject}) to every connected panel
     * @param play queues a list of likes on a server; it answers the request itself
     * @param clock times the Undo window
     */
    public LikeRoutes(LikesStore likes, Predicate<String> uriAlreadyStored, Function<Context, Person> currentUser,
            BiConsumer<String, Object> broadcastAll, LikesPlayHook play, Clock clock)
    {
        this.likes = likes;
        this.uriAlreadyStored = uriAlreadyStored;
        this.currentUser = currentUser;
        this.broadcastAll = broadcastAll;
        this.play = play;
        this.undos = new UndoTokens<>(clock);
    }

    /** Joins a voice channel if need be and starts loading a list of likes into a server's queue. */
    public interface LikesPlayHook
    {
        /**
         * Plays the list and writes the response to {@code ctx}: {@code {"message": loadingMessage(list)}}. May throw the
         * same exceptions as the routes, as long as it has not written anything yet.
         *
         * @param list a person's likes as stored, or everyone's mix ({@link #EVERYONE}), made for this request only
         * @param mode {@code all}, {@code shuffle} or {@code from}
         * @param songId for {@code from}: the song to start with, which the list holds; otherwise null
         */
        void play(Context ctx, String guildId, PlaylistFile list, String mode, String songId);
    }

    /** A change to the likes of {@code me}, the person logged in; true when the list was written. */
    private interface Change
    {
        boolean handle(Context ctx, Person me);
    }

    /** Adds the routes to the app. */
    public void register(Javalin app)
    {
        // the fixed paths before /api/likes/{userId}, which would take them otherwise
        app.get("/api/likes/mine", guarded(this::mine));
        app.get("/api/likes/people", guarded(this::people));
        app.get("/api/likes/who", guarded(this::who));
        app.get("/api/likes/{userId}", guarded(this::list));
        app.post("/api/likes/add", change(this::add));
        app.post("/api/likes/remove", change(this::remove));
        app.post("/api/likes/remove-song", change(this::removeSong));
        app.post("/api/likes/undo", change(this::undo));
        app.post("/api/likes/move", change(this::move));
        app.post("/api/guild/{guildId}/likes/play", guarded(this::play));
    }

    /** What the play hook answers: "Loading anna's likes...", or "Loading everyone's likes..." for the mix. */
    static String loadingMessage(PlaylistFile list)
    {
        return "Loading " + (EVERYONE.equals(list.getName()) ? EVERYONE : LikesStore.ownerName(list)) + "'s likes...";
    }

    // ---- errors and who is asking ------------------------------------------------------------------------------

    private static Handler guarded(PlaylistRoutes.Route route)
    {
        return PlaylistRoutes.guarded(LOG, route);
    }

    /** A change: 401 when nobody is logged in; once it was written, the panels are told whose likes changed. */
    private Handler change(Change change)
    {
        return guarded(ctx ->
        {
            Person me = currentUser.apply(ctx);
            if(me == null)
            {
                PlaylistRoutes.fail(ctx, 401, LOG_IN, LOG_IN);
                return;
            }
            if(change.handle(ctx, me))
                announce(me);
        });
    }

    private void announce(Person me)
    {
        try
        {
            broadcastAll.accept("likes", new JSONObject().put("userId", me.getId()));
        }
        catch(RuntimeException e)
        {
            LOG.warn("Could not tell the panels about a change of likes: {}", e.toString()); // the change itself went through
        }
    }

    private static JSONObject counted(PlaylistFile list)
    {
        return new JSONObject().put("count", list.getSongs().size()).put("version", list.getVersion());
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    /** {@code {uris, count, max}}: what the ♥ buttons need. Empty for nobody. */
    private void mine(Context ctx)
    {
        Person me = currentUser.apply(ctx);
        PlaylistFile list = me == null ? null : likes.get(me.getId()).orElse(null);
        PlaylistRoutes.send(ctx, new JSONObject().put("uris", new JSONArray(LikesStore.uris(list)))
                .put("count", list == null ? 0 : list.getSongs().size()).put("max", PlaylistFile.MAX_SONGS));
    }

    /** Everyone with at least one like: the caller first, then by name. */
    private void people(Context ctx)
    {
        Person me = currentUser.apply(ctx);
        List<JSONObject> people = new ArrayList<>();
        for(LikesStore.Liker liker : likes.people())
        {
            JSONObject person = new JSONObject()
                    .put("id", liker.getId())
                    .put("name", liker.getName())
                    .put("count", liker.getCount())
                    .put("durationMs", liker.getDurationMs())
                    .put("updatedAt", PlaylistRoutes.instantJson(liker.getUpdatedAt()));
            if(me != null && liker.getId().equals(me.getId()))
                people.add(0, person);
            else
                people.add(person);
        }
        PlaylistRoutes.send(ctx, new JSONArray(people));
    }

    /** {@code {names}}: who else liked the song with this {@code uri}. */
    private void who(Context ctx)
    {
        String uri = ctx.queryParam("uri");
        if(uri == null || uri.trim().isEmpty())
            throw new IllegalArgumentException("uri is required");
        Person me = currentUser.apply(ctx);
        PlaylistRoutes.send(ctx, new JSONObject().put("names", new JSONArray(likes.whoLiked(uri.trim(), me == null ? null : me.getId()))));
    }

    /** A person's likes in the playlist page shape, with whose they are; anyone may read them. */
    private void list(Context ctx)
    {
        String userId = ctx.pathParam("userId");
        PlaylistFile list = likes.get(userId).orElseThrow(() -> new NoSuchElementException(NO_LIKES));
        Person me = currentUser.apply(ctx);
        PlaylistRoutes.send(ctx, PlaylistRoutes.page(list, "")
                .put("owner", new JSONObject().put("id", list.getName()).put("name", LikesStore.ownerName(list)))
                .put("mine", me != null && userId.equals(me.getId())));
    }

    // ---- changes of your own likes -----------------------------------------------------------------------------

    private boolean add(Context ctx, Person me)
    {
        Object song = PlaylistRoutes.body(ctx).opt("song");
        if(!(song instanceof JSONObject))
            throw new IllegalArgumentException(BAD_SONG);
        LikesStore.Outcome liked = likes.like(me, SongInput.song((JSONObject) song, uriAlreadyStored, BAD_SONG));
        PlaylistRoutes.send(ctx, new JSONObject().put("liked", true).put("count", liked.getCount()));
        return liked.isChanged();
    }

    private boolean remove(Context ctx, Person me)
    {
        String uri = PlaylistRoutes.required(PlaylistRoutes.body(ctx), "uri", "uri");
        LikesStore.Outcome unliked = likes.unlike(me, uri.trim());
        PlaylistRoutes.send(ctx, new JSONObject().put("liked", false).put("count", unliked.getCount()));
        return unliked.isChanged();
    }

    private boolean removeSong(Context ctx, Person me)
    {
        String songId = PlaylistRoutes.required(PlaylistRoutes.body(ctx), "songId", "Song id");
        PlaylistStore.Removed removed = likes.removeSong(me, songId);
        String token = undos.remember(new Removal(me.getId(), removed.getSong(), removed.getIndex()));
        PlaylistRoutes.send(ctx, counted(removed.getPlaylist()).put("undo", token));
        return true;
    }

    private boolean undo(Context ctx, Person me)
    {
        // Only the token is read from the request: the song and its place were remembered when it went.
        String token = PlaylistRoutes.required(PlaylistRoutes.body(ctx), "token", "Undo token");
        PlaylistFile list = undos.redeem(token, removal -> removal.ownerId.equals(me.getId()),
                removal -> likes.reinsert(me, removal.song, removal.index));
        PlaylistRoutes.send(ctx, counted(list));
        return true;
    }

    private boolean move(Context ctx, Person me)
    {
        JSONObject body = PlaylistRoutes.body(ctx);
        String songId = PlaylistRoutes.required(body, "songId", "Song id");
        int toIndex = PlaylistRoutes.requiredInt(body, "toIndex");
        PlaylistRoutes.send(ctx, counted(likes.move(me, songId, toIndex)));
        return true;
    }

    // ---- playing -----------------------------------------------------------------------------------------------

    private void play(Context ctx)
    {
        JSONObject body = PlaylistRoutes.body(ctx);
        String userId = PlaylistRoutes.required(body, "userId", "userId");
        String mode = PlaylistRoutes.playMode(body);
        String songId = PlaylistRoutes.startSong(body, mode);
        PlaylistFile list = EVERYONE.equals(userId) ? everyone() : likes.get(userId).orElse(null);
        if(list == null || !PlaylistPlayer.hasPlayable(list))
            throw new NoSuchElementException(NOTHING_TO_PLAY);
        if(songId != null && list.getSongs().stream().noneMatch(song -> songId.equals(song.getId())))
            throw new NoSuchElementException(SONG_NOT_FOUND);
        play.play(ctx, ctx.pathParam("guildId"), list, mode, songId);
    }

    /** Everyone's likes in one list, for one play: each uri once, shuffled, at most 2000 songs. Not saved anywhere. */
    private PlaylistFile everyone()
    {
        PlaylistFile mix = new PlaylistFile();
        mix.setName(EVERYONE);
        mix.getSongs().addAll(likes.everyone(ThreadLocalRandom.current(), PlaylistFile.MAX_SONGS));
        return mix;
    }

    // ---- Undo of a removed song --------------------------------------------------------------------------------

    /** A song taken out of someone's likes, whose likes, and where it was. */
    private static final class Removal
    {
        final String ownerId;
        final Song song;
        final int index;

        Removal(String ownerId, Song song, int index)
        {
            this.ownerId = ownerId;
            this.song = song;
            this.index = index;
        }
    }
}
