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

import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.theme.PictureTooLargeException;
import com.jagrosh.jmusicbot.theme.ThemeFile;
import com.jagrosh.jmusicbot.theme.ThemeForbiddenException;
import com.jagrosh.jmusicbot.theme.ThemeImages;
import com.jagrosh.jmusicbot.theme.ThemeStore;
import com.jagrosh.jmusicbot.theme.ThemeStore.PictureUpload;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpResponseException;
import io.javalin.http.util.MultipartUtil;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every custom-theme endpoint of the web panel, over a {@link ThemeStore}. Self-contained so it can run on a bare
 * Javalin: who is asking, who the bot owner is and the WebSocket broadcast come in through the constructor.
 * {@link WebServer} registers it so the {@code /api/*} auth middleware covers every {@code /api/themes} route.
 *
 * <p>Bodies are JSON, except create and edit, which are multipart: a {@code theme} part holding the JSON values, an
 * optional {@code background} file and, for an edit, {@code version} and {@code removeBackground}. Answers are JSON.
 * Errors are {@code {"error": message}}: 400 for values that aren't allowed ({@link IllegalArgumentException}), 403 for
 * what the person may not do ({@link ThemeForbiddenException}), 404 for a theme that doesn't exist or isn't visible to
 * them ({@link NoSuchElementException}, always the same words), 409 for the theme limit or a theme that changed
 * meanwhile ({@link IllegalStateException}), 413 for a picture, or an upload as a whole, that is too big
 * ({@link PictureTooLargeException}; see {@link #MAX_UPLOAD_BYTES}),
 * 500 for a disk error. Every change is announced to all connected panels with a {@code themes} message that holds
 * the theme's id and nothing else: a panel asks for the list again, which shows it only what it may see.
 *
 * <p>The pictures are the one thing served without a login ({@code GET /theme-images/{name}}, outside {@code /api/}):
 * the secret name is the only access control, and a private theme's name is only ever sent to its creator.
 */
public class ThemeRoutes
{
    private static final Logger LOG = LoggerFactory.getLogger(ThemeRoutes.class);

    /** Where the pictures are served; a theme's {@code background.url} starts with it. */
    static final String PICTURES = "/theme-images/";
    /** The biggest {@code theme} part taken, in bytes. A real theme is about 2 KB. */
    static final int MAX_THEME_BYTES = 64 * 1024;
    /**
     * The biggest upload taken, in bytes: the biggest picture plus room for the theme and the multipart framing. Checked
     * against the announced length before anything is read, and enforced while reading for uploads that announce none.
     */
    static final long MAX_UPLOAD_BYTES = ThemeStore.MAX_PICTURE_BYTES + 256 * 1024;
    /** Parts up to this size are kept in memory while an upload is read; bigger ones go to a temp file. */
    private static final int UPLOAD_IN_MEMORY_BYTES = 64 * 1024;
    /** The biggest {@code version} or {@code removeBackground} part taken, in bytes. */
    private static final int MAX_FIELD_BYTES = 64;
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,18}");

    private static final String JSON = "application/json";
    private static final String NOT_FOUND = "Theme not found";
    private static final String FORBIDDEN = "That isn't allowed";
    private static final String SAVE_FAILED = "Couldn't save — try again";
    private static final String UNEXPECTED = "Something went wrong";
    private static final String TOO_LARGE = "Pictures can be up to " + (ThemeStore.MAX_PICTURE_BYTES >> 20) + " MB";
    private static final String NOT_A_FORM = "Send the theme as a form upload";
    private static final String BAD_UPLOAD = "The upload couldn't be read";
    private static final String NO_THEME = "The theme is missing";
    private static final String THEME_TOO_BIG = "The theme is too big";
    private static final String BAD_THEME = "The theme must be a JSON object";
    private static final String BAD_VERSION = "The theme's version is missing";
    private static final String BAD_REMOVE = "Remove background must be true or false";
    private static final String BAD_HIDDEN = "hidden must be true or false";
    private static final String NOT_TEXT = "The upload must be text";

    private final ThemeStore store;
    private final ThemeImages images;
    private final Function<Context, PlaylistFile.Person> currentUser;
    private final LongSupplier ownerId;
    private final BiConsumer<String, Object> broadcastAll;

    /**
     * @param images the store's pictures, for serving them
     * @param currentUser who is asking, or null when nobody is logged in (web login off): they see public themes only
     * @param ownerId the Discord id of the bot owner, who moderates the public themes
     * @param broadcastAll sends a message of this type with this data (an {@link JSONObject}) to every connected panel
     */
    public ThemeRoutes(ThemeStore store, ThemeImages images, Function<Context, PlaylistFile.Person> currentUser,
            LongSupplier ownerId, BiConsumer<String, Object> broadcastAll)
    {
        this.store = store;
        this.images = images;
        this.currentUser = currentUser;
        this.ownerId = ownerId;
        this.broadcastAll = broadcastAll;
    }

    /** One request handler; may throw the exceptions {@link #guarded} turns into answers. */
    private interface Route
    {
        void handle(Context ctx);
    }

    /** Saves a theme with the picture that came with it, if any. */
    private interface Saving
    {
        ThemeFile save(PictureUpload picture);
    }

    /** Adds the routes to the app. */
    public void register(Javalin app)
    {
        app.get("/api/themes", guarded(this::list));
        app.post("/api/themes", guarded(this::create));
        app.post("/api/themes/{id}", guarded(this::edit));
        app.post("/api/themes/{id}/delete", guarded(this::delete));
        app.post("/api/themes/{id}/hide", guarded(this::hide));
        // outside /api/ on purpose: the auth middleware doesn't cover it, so the secret name is all it takes
        app.get(PICTURES + "{name}", this::picture);
    }

    // ---- errors

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
            catch(ThemeForbiddenException e)
            {
                fail(ctx, 403, e.getMessage(), FORBIDDEN);
            }
            catch(NoSuchElementException e)
            {
                fail(ctx, 404, NOT_FOUND, NOT_FOUND); // always the same words, so a hidden theme can't be told from a missing one
            }
            catch(IllegalStateException e)
            {
                fail(ctx, 409, e.getMessage(), "That can't be done right now");
            }
            catch(PictureTooLargeException e)
            {
                fail(ctx, 413, e.getMessage(), TOO_LARGE);
            }
            catch(UncheckedIOException e)
            {
                LOG.error("Theme request {} {} failed", ctx.method(), ctx.path(), e);
                fail(ctx, 500, SAVE_FAILED, SAVE_FAILED);
            }
            catch(HttpResponseException e)
            {
                throw e; // Javalin's own answers are not ours to change
            }
            catch(RuntimeException e)
            {
                LOG.error("Theme request {} {} failed", ctx.method(), ctx.path(), e);
                fail(ctx, 500, UNEXPECTED, UNEXPECTED);
            }
        };
    }

    private static void fail(Context ctx, int status, String message, String fallback)
    {
        String text = message == null || message.trim().isEmpty() ? fallback : message;
        ctx.status(status).contentType(JSON).result(new JSONObject().put("error", text).toString());
    }

    // ---- who is asking

    private String viewerId(Context ctx)
    {
        PlaylistFile.Person user = currentUser.apply(ctx);
        return user == null ? null : user.getId();
    }

    /** Somebody who isn't logged in is no bot owner, whatever the owner's id is. */
    private boolean isOwner(String viewerId)
    {
        return viewerId != null && viewerId.equals(String.valueOf(ownerId.getAsLong()));
    }

    private ThemeFile.Person person(Context ctx)
    {
        PlaylistFile.Person user = currentUser.apply(ctx);
        return user == null ? null : new ThemeFile.Person(user.getId(), user.getName());
    }

    // ---- reading requests

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

    /**
     * Checks that this is an upload of an allowed size and sets how it is read, before anything of it is read. Javalin's own
     * multipart settings have no limit at all, and {@code maxRequestSize} only covers {@code ctx.body()}, so without this
     * any member could write as much as they like to the temp folder before the store got to refuse the picture. An upload
     * that announces more than {@link #MAX_UPLOAD_BYTES} is refused unread; the limit is also given to Jetty, which stops
     * reading at it, for an upload that announces no length (it is then refused by {@link #part} just the same).
     */
    private static void requireForm(Context ctx)
    {
        if(!ctx.isMultipartFormData())
            throw new IllegalArgumentException(NOT_A_FORM);
        if(ctx.req().getContentLengthLong() > MAX_UPLOAD_BYTES)
            throw new PictureTooLargeException(TOO_LARGE);
        ctx.req().setAttribute(MultipartUtil.MULTIPART_CONFIG_ATTRIBUTE,
                new MultipartConfigElement(System.getProperty("java.io.tmpdir"), -1L, MAX_UPLOAD_BYTES, UPLOAD_IN_MEMORY_BYTES));
    }

    /**
     * The part of the upload with this name, or null. The first call has Jetty read the whole upload (under the limits set
     * by {@link #requireForm}); the parts are then looked up in what was read. Looking a text part up as a {@link Part}
     * instead of a form parameter is what lets it be given a size limit before it is read.
     */
    private static Part part(Context ctx, String name)
    {
        try
        {
            return ctx.req().getPart(name);
        }
        catch(IllegalStateException e)
        {
            // with no per-part limit and a config always set, this is Jetty stopping an upload that went over MAX_UPLOAD_BYTES
            throw new PictureTooLargeException(TOO_LARGE);
        }
        catch(IOException | ServletException | RuntimeException e)
        {
            throw new IllegalArgumentException(BAD_UPLOAD, e);
        }
    }

    /** A text part as UTF-8, refused with {@code tooBig} when it is longer than {@code max} bytes; no more than that is ever read. */
    private static String text(Part part, int max, String tooBig)
    {
        try(InputStream in = part.getInputStream())
        {
            byte[] bytes = in.readNBytes(max + 1);
            if(bytes.length > max)
                throw new IllegalArgumentException(tooBig);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch(CharacterCodingException e)
        {
            throw new IllegalArgumentException(NOT_TEXT, e);
        }
        catch(IOException e)
        {
            throw new IllegalArgumentException(BAD_UPLOAD, e);
        }
    }

    /** The values a browser may set, from the {@code theme} part; everything else in it is ignored. */
    private static ThemeFile valuesFrom(Context ctx)
    {
        Part part = part(ctx, "theme");
        if(part == null)
            throw new IllegalArgumentException(NO_THEME);
        String text = text(part, MAX_THEME_BYTES, THEME_TOO_BIG);
        JSONObject json;
        try
        {
            json = new JSONObject(text);
        }
        catch(JSONException e)
        {
            throw new IllegalArgumentException(BAD_THEME, e);
        }
        return ThemeFile.valuesFromClient(json);
    }

    private static long versionFrom(Context ctx)
    {
        Part part = part(ctx, "version");
        String text = part == null ? "" : text(part, MAX_FIELD_BYTES, BAD_VERSION).trim();
        if(!VERSION.matcher(text).matches())
            throw new IllegalArgumentException(BAD_VERSION);
        return Long.parseLong(text);
    }

    private static boolean removeBackgroundFrom(Context ctx)
    {
        Part part = part(ctx, "removeBackground");
        if(part == null)
            return false;
        String text = text(part, MAX_FIELD_BYTES, BAD_REMOVE).trim();
        if(text.equals("true"))
            return true;
        if(text.isEmpty() || text.equals("false"))
            return false;
        throw new IllegalArgumentException(BAD_REMOVE);
    }

    /** The {@code background} file, or null when there is none (an empty file, which is what a browser sends for no choice, is none). */
    private static Part pictureFrom(Context ctx)
    {
        Part part = part(ctx, "background");
        return part == null || part.getSize() == 0 ? null : part;
    }

    /** Saves with the picture's stream open; the picture itself is checked, size-limited and kept by the store. */
    private static ThemeFile saveWith(Part picture, Saving saving)
    {
        if(picture == null)
            return saving.save(null);
        try(InputStream in = picture.getInputStream())
        {
            return saving.save(new PictureUpload(in, picture.getSize()));
        }
        catch(IOException e)
        {
            throw new UncheckedIOException("Could not read the uploaded picture", e);
        }
    }

    // ---- writing answers

    private static void send(Context ctx, JSONObject json)
    {
        ctx.contentType(JSON).result(json.toString());
    }

    /** A theme as the panel gets it. A private theme's picture name is in here, so this goes to its creator only. */
    private static JSONObject themeJson(ThemeFile theme, String viewerId)
    {
        ThemeFile.Person owner = theme.getOwner();
        ThemeFile.Background background = theme.getBackground();
        ThemeFile.Mini mini = theme.getMini();
        return new JSONObject()
                .put("id", theme.getId())
                .put("name", theme.getName())
                .put("owner", new JSONObject().put("id", owner.getId()).put("name", owner.getName()))
                .put("visibility", theme.getVisibility())
                .put("hiddenByOwner", theme.isHiddenByOwner())
                .put("mine", isCreator(owner, viewerId))
                .put("version", theme.getVersion())
                .put("updatedAt", theme.getUpdatedAt() == null ? JSONObject.NULL : theme.getUpdatedAt().toString())
                .put("base", theme.getBase())
                .put("effects", theme.isEffects())
                .put("colors", new JSONObject(theme.getColors()))
                .put("radiusScale", theme.getRadiusScale())
                .put("font", theme.getFont())
                .put("background", background == null ? JSONObject.NULL : new JSONObject()
                        .put("url", PICTURES + background.getImage())
                        .put("dim", background.getDim())
                        .put("blur", background.getBlur())
                        .put("fit", background.getFit()))
                .put("mini", mini == null ? JSONObject.NULL : new JSONObject()
                        .put("colors", new JSONObject(mini.getColors()))
                        // without a picture there is none to show, whatever was saved
                        .put("useImage", mini.isUseImage() && background != null)
                        .put("dim", mini.getDim()));
    }

    private static boolean isCreator(ThemeFile.Person owner, String viewerId)
    {
        return viewerId != null && viewerId.equals(owner.getId());
    }

    /** Tells every panel that this theme changed, by id only. A broadcast that fails doesn't undo the change. */
    private void announce(String id)
    {
        try
        {
            broadcastAll.accept("themes", new JSONObject().put("id", id));
        }
        catch(RuntimeException e)
        {
            LOG.warn("Could not tell the panels about a theme change: {}", e.toString()); // the change itself went through
        }
    }

    // ---- the endpoints

    private void list(Context ctx)
    {
        String viewer = viewerId(ctx);
        JSONArray themes = new JSONArray();
        int used = 0;
        for(ThemeFile theme : store.visibleTo(viewer))
        {
            if(isCreator(theme.getOwner(), viewer))
                used++;
            themes.put(themeJson(theme, viewer));
        }
        JSONObject answer = new JSONObject()
                .put("themes", themes)
                .put("me", new JSONObject().put("id", viewer == null ? JSONObject.NULL : viewer).put("isOwner", isOwner(viewer)))
                .put("limit", new JSONObject().put("max", ThemeStore.MAX_PER_USER).put("used", used));
        if(isOwner(viewer))
        {
            // the themes he hid, so that he can un-hide or delete them: a name and a creator, nothing of what is in them
            JSONArray hidden = new JSONArray();
            for(ThemeStore.HiddenEntry entry : store.hiddenByOwner(true))
                hidden.put(new JSONObject().put("id", entry.getId()).put("name", entry.getName()).put("creator", entry.getCreatorName()));
            answer.put("hidden", hidden);
        }
        send(ctx, answer);
    }

    private void create(Context ctx)
    {
        requireForm(ctx);
        ThemeFile values = valuesFrom(ctx);
        ThemeFile.Person owner = person(ctx);
        ThemeFile saved = saveWith(pictureFrom(ctx), picture -> store.create(values, owner, picture));
        LOG.info("Web theme: {} created {}", saved.getOwner().getId(), saved.getId());
        send(ctx, themeJson(saved, owner == null ? null : owner.getId()));
        announce(saved.getId());
    }

    private void edit(Context ctx)
    {
        requireForm(ctx);
        long version = versionFrom(ctx);
        boolean removeBackground = removeBackgroundFrom(ctx);
        ThemeFile values = valuesFrom(ctx);
        String id = ctx.pathParam("id");
        String viewer = viewerId(ctx);
        ThemeFile saved = saveWith(pictureFrom(ctx), picture -> store.update(id, viewer, version, values, picture, removeBackground));
        LOG.info("Web theme: {} changed {} (now version {})", viewer, saved.getId(), saved.getVersion());
        send(ctx, themeJson(saved, viewer));
        announce(saved.getId());
    }

    private void delete(Context ctx)
    {
        String id = ctx.pathParam("id");
        String viewer = viewerId(ctx);
        store.delete(id, viewer, isOwner(viewer));
        LOG.info("Web theme: {} deleted {}", viewer, id);
        send(ctx, new JSONObject().put("id", id));
        announce(id);
    }

    private void hide(Context ctx)
    {
        Object asked = body(ctx).opt("hidden");
        if(!(asked instanceof Boolean))
            throw new IllegalArgumentException(BAD_HIDDEN);
        boolean hidden = (Boolean) asked;
        String id = ctx.pathParam("id");
        String viewer = viewerId(ctx);
        store.hide(id, isOwner(viewer), hidden);
        LOG.info("Web theme: {} {} {}", viewer, hidden ? "hid" : "un-hid", id);
        // only this: once a theme is private, what is in it is none of the owner's business
        send(ctx, new JSONObject().put("id", id).put("hidden", hidden));
        announce(id);
    }

    /** A stored picture by its secret name. No login: the name is the access rule. Anything else is a bare 404. */
    private void picture(Context ctx)
    {
        Optional<Path> file = images.resolve(ctx.pathParam("name"));
        if(!file.isPresent())
        {
            ctx.status(404);
            return;
        }
        InputStream in;
        try
        {
            in = Files.newInputStream(file.get());
        }
        catch(NoSuchFileException e)
        {
            ctx.status(404); // deleted since it was looked up
            return;
        }
        catch(IOException e)
        {
            LOG.warn("Could not open theme picture {}: {}", file.get().getFileName(), e.toString());
            ctx.status(404);
            return;
        }
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Content-Security-Policy", "default-src 'none'; sandbox");
        ctx.header("Cache-Control", "public, max-age=31536000, immutable"); // a new picture gets a new name
        ctx.contentType(ThemeImages.contentType(file.get().getFileName().toString()));
        ctx.result(in);
    }
}
