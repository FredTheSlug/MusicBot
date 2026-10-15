/*
 * Copyright 2026 Fred (https://github.com/FredTheSlug)
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

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import com.jagrosh.jmusicbot.playlist.ImportJobs;
import com.jagrosh.jmusicbot.playlist.PlaylistFile;
import com.jagrosh.jmusicbot.playlist.PlaylistPlayer;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.PlayCountStore;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.theme.ThemeImages;
import com.jagrosh.jmusicbot.theme.ThemeStore;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.jagrosh.jmusicbot.utils.SoundCloudUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.javalin.websocket.WsContext;
import org.json.JSONObject;

public class WebServer
{
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final long SESSION_TTL_MS = TimeUnit.DAYS.toMillis(30);
    private static final int SESSION_COOKIE_MAX_AGE_SEC = (int) TimeUnit.DAYS.toSeconds(30);
    private static final long OAUTH_STATE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final Bot bot;
    private final Gson gson = new Gson();
    private Javalin app;
    private final Map<WsContext, String> wsClients = new ConcurrentHashMap<>();
    private final Map<WsContext, AuthSession> wsSessions = new ConcurrentHashMap<>();
    private final Map<String, AuthSession> authSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> oauthPendingStates = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> sessionCleanupTask;
    private volatile ExecutorService playlistImportPool;

    private static final class AuthSession
    {
        static final String CTX_KEY = "webAuthSession";

        volatile long lastUsedMs;
        final long discordUserId;
        final String displayName;
        final String discriminator;
        final String avatarUrl;
        final Set<Long> oauthGuildIds;

        AuthSession(long lastUsedMs, long discordUserId, String displayName, String discriminator, String avatarUrl, Set<Long> oauthGuildIds)
        {
            this.lastUsedMs = lastUsedMs;
            this.discordUserId = discordUserId;
            this.displayName = displayName;
            this.discriminator = discriminator;
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
            if (oauthGuildIds == null || oauthGuildIds.isEmpty())
                this.oauthGuildIds = Set.of();
            else
                this.oauthGuildIds = Collections.unmodifiableSet(new HashSet<>(oauthGuildIds));
        }
    }

    public WebServer(Bot bot)
    {
        this.bot = bot;
    }

    public void start()
    {
        int port = bot.getConfig().getWebPort();
        app = Javalin.create(config ->
        {
            config.staticFiles.add(spec ->
            {
                spec.hostedPath = "/web";
                spec.directory = "/web";
                spec.location = Location.CLASSPATH;
                spec.headers = Map.of("Cache-Control", "no-cache, must-revalidate, max-age=0");
            });
            config.http.maxRequestSize = 104857600L;
        });

        app.get("/", ctx -> ctx.redirect("/web/index.html"));
        app.get("/favicon.ico", this::handleFavicon);
        app.exception(IllegalArgumentException.class, (e, ctx) ->
        {
            log.debug("Web: rejected request to {}: {}", ctx.path(), e.getMessage());
            ctx.status(400).json(Map.of("error", "Invalid request"));
        });

        app.ws("/ws", ws ->
        {
            ws.onConnect(ctx ->
            {
                if (bot.getConfig().isWebAuthEnabled())
                {
                    AuthSession session = findSession(ctx.cookie("JMBSESSION"), ctx.queryParam("token"));
                    if (session == null)
                    {
                        ctx.closeSession(4401, "Authentication required");
                        return;
                    }
                    wsSessions.put(ctx, session);
                }
                subscribeWs(ctx, ctx.queryParam("guild"));
                // Jetty closes idle sockets after 30 s. Protocol pings are answered by the
                // browser itself, so this works even when a hidden page's timers are throttled.
                ctx.enableAutomaticPings(15, TimeUnit.SECONDS);
            });
            ws.onClose(ctx ->
            {
                ctx.disableAutomaticPings();
                wsClients.remove(ctx);
                wsSessions.remove(ctx);
            });
            ws.onMessage(ctx ->
            {
                String msg = ctx.message();
                if ("ping".equals(msg))
                {
                    ctx.send("pong");
                }
                else if (msg != null && msg.startsWith("guild:"))
                {
                    subscribeWs(ctx, msg.substring("guild:".length()));
                }
            });
        });

        app.get("/api/me", this::handleMe);
        app.get("/api/guilds", this::handleGetGuilds);
        app.get("/api/guild/{guildId}/nowplaying", this::handleGetNowPlaying);
        app.get("/api/guild/{guildId}/queue", this::handleGetQueue);
        app.post("/api/guild/{guildId}/play", this::handlePlay);
        app.post("/api/guild/{guildId}/pause", this::handlePause);
        app.post("/api/guild/{guildId}/skip", this::handleSkip);
        app.post("/api/guild/{guildId}/stop", this::handleStop);
        app.post("/api/guild/{guildId}/volume", this::handleVolume);
        app.post("/api/guild/{guildId}/shuffle", this::handleShuffle);
        app.post("/api/guild/{guildId}/repeat", this::handleRepeat);
        app.post("/api/guild/{guildId}/autoplay", this::handleAutoplay);
        app.post("/api/guild/{guildId}/sponsorblock", this::handleSponsorBlock);
        app.post("/api/guild/{guildId}/seek", this::handleSeek);
        app.post("/api/guild/{guildId}/upload", this::handleUpload);
        registerPlaylistRoutes();
        registerThemeRoutes();
        app.get("/api/guild/{guildId}/search", this::handleSearch);
        app.post("/api/guild/{guildId}/resolve-playlist-url", this::handleResolvePlaylistUrl);
        app.post("/api/guild/{guildId}/reorder", this::handleReorder);
        app.post("/api/guild/{guildId}/remove/{index}", this::handleRemove);
        app.get("/api/guild/{guildId}/history", this::handleGetHistory);
        app.post("/api/guild/{guildId}/clear-history", this::handleClearHistory);
        app.get("/api/guild/{guildId}/frequent", this::handleGetFrequent);
        app.post("/api/guild/{guildId}/clear-frequent", this::handleClearFrequent);
        app.post("/api/guild/{guildId}/clear-queue", this::handleClearQueue);
        app.get("/api/guild/{guildId}/lyrics", this::handleGetLyrics);
        app.get("/api/auth/discord", this::handleDiscordAuthStart);
        app.get("/api/auth/discord/callback", this::handleDiscordAuthCallback);
        app.post("/api/logout", this::handleLogout);

        if (bot.getConfig().isWebAuthEnabled())
        {
            app.before("/api/*", this::authMiddleware);
        }

        String host = bot.getConfig().getWebHost();
        app.start(host, port);
        log.info("Web interface started on {}:{}", host, port);

        if (bot.getJDA() != null)
        {
            sessionCleanupTask = bot.getJDA().getRateLimitPool().scheduleAtFixedRate(
                    this::pruneAuthSessions, 1, 1, TimeUnit.HOURS);
        }
    }

    public void stop()
    {
        ScheduledFuture<?> cleanup = sessionCleanupTask;
        sessionCleanupTask = null;
        if (cleanup != null)
        {
            cleanup.cancel(false);
        }
        if (app != null)
        {
            app.stop();
            app = null;
        }
        if (playlistImportPool != null)
        {
            playlistImportPool.shutdownNow();
            playlistImportPool = null;
        }
    }

    /**
     * Builds the playlist routes over the bot's playlist store and the liked-songs routes over its likes, and registers
     * them (the auth middleware is added after, and covers them).
     */
    private void registerPlaylistRoutes()
    {
        // Imports load slowly, so they get their own threads: up to 3 run at once (ImportJobs enforces that).
        playlistImportPool = Executors.newFixedThreadPool(3, runnable ->
        {
            Thread thread = new Thread(runnable, "playlist-import");
            thread.setDaemon(true);
            return thread;
        });
        ImportJobs imports = new ImportJobs(bot.getPlaylistStore(), bot.getTrackResolver(), playlistImportPool,
                progress -> broadcastAll("playlist-import", PlaylistRoutes.importProgress(progress)));
        // a local file may go into a playlist or the likes when it is already in one or the other
        Predicate<String> inLikes = SongInput.likedByAnyone(bot.getLikesStore());
        new PlaylistRoutes(bot.getPlaylistStore(), bot.getPlaylistConverter(), imports, bot.getTrackResolver(),
                this::currentUser, this::broadcastAll, this::playPlaylist, Clock.systemUTC(), inLikes).register(app);
        new LikeRoutes(bot.getLikesStore(), uri -> SongInput.storedIn(bot.getPlaylistStore(), inLikes).test(uri),
                this::currentUser, this::broadcastAll, this::playLikes, Clock.systemUTC()).register(app);
    }

    /**
     * Builds the theme routes over a {@code Themes} folder next to the playlists folder, with the pictures in its
     * {@code images} folder, and registers them. {@code /theme-images/{name}} is outside {@code /api/} on purpose,
     * so the auth middleware (added after) covers the {@code /api/themes} routes and not the pictures.
     */
    private void registerThemeRoutes()
    {
        Path themes = themesFolder(bot.getConfig().getPlaylistsFolder());
        ThemeImages images = new ThemeImages(themes.resolve("images"));
        ThemeStore store = new ThemeStore(themes, images, Clock.systemUTC());
        new ThemeRoutes(store, images, this::currentUser, bot.getConfig()::getOwnerId, this::broadcastAll).register(app);
    }

    /** The {@code Themes} folder: next to the playlists folder, in the same place as the bot resolves that one. */
    static Path themesFolder(String playlistsFolder)
    {
        Path playlists = OtherUtil.getPath(playlistsFolder).toAbsolutePath().normalize();
        Path parent = playlists.getParent();
        return (parent == null ? playlists : parent).resolve("Themes");
    }

    /** Browsers request /favicon.ico at the site root; Jetty also ships a default one. */
    private void handleFavicon(Context ctx)
    {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/favicon.png"))
        {
            if (in == null)
            {
                ctx.status(404).result("Favicon not found");
                return;
            }
            ctx.contentType("image/png");
            ctx.header("Cache-Control", "no-cache, must-revalidate, max-age=0");
            ctx.result(in.readAllBytes());
        }
        catch (IOException e)
        {
            log.warn("Failed to serve favicon", e);
            ctx.status(500);
        }
    }

    public void broadcastUpdate(String guildId, String type)
    {
        Guild guild = bot.getJDA().getGuildById(guildId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", type);
        payload.put("guildId", guildId);
        if (guild != null)
        {
            if ("player".equals(type))
            {
                payload.put("data", buildNowPlayingData(guild));
            }
            else if ("queue".equals(type))
            {
                payload.put("data", buildQueueData(guild));
            }
            else if ("history".equals(type))
            {
                payload.put("data", buildHistoryData(guild));
            }
            else if ("frequent".equals(type))
            {
                payload.put("data", buildFrequentData(guild));
            }
        }
        String message = gson.toJson(payload);
        sendToGuildWsClients(guildId, message);
    }

    public void broadcastLoadError(String guildId, String code, String message)
    {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "error");
        payload.put("guildId", guildId);
        payload.put("code", code);
        payload.put("message", message);
        sendToGuildWsClients(guildId, gson.toJson(payload));
    }

    /**
     * Sends a message to every connected panel, whatever server it watches, with that client's own server id in
     * the {@code guildId} field (the client files messages by it). Playlists are shared by all servers.
     *
     * @param data an {@link JSONObject} or a Map; sent as a JSON object
     */
    public void broadcastAll(String type, Object data)
    {
        List<WsContext> toRemove = new ArrayList<>();
        wsClients.forEach((ctx, guildId) ->
        {
            try
            {
                ctx.send(broadcastMessage(type, guildId, data));
            }
            catch (Exception e)
            {
                toRemove.add(ctx);
            }
        });
        toRemove.forEach(wsClients::remove);
    }

    /** The WebSocket text for one client: {@code {"type", "guildId", "data"}}, where data is real JSON, not a string of it. */
    static String broadcastMessage(String type, String guildId, Object data)
    {
        return new JSONObject().put("type", type).put("guildId", guildId).put("data", JSONObject.wrap(data)).toString();
    }

    /** Who is asking, for crediting playlist changes; null when web login is off or the request has no session. */
    PlaylistFile.Person currentUser(Context ctx)
    {
        if (!bot.getConfig().isWebAuthEnabled())
            return null;
        AuthSession session = ctx.attribute(AuthSession.CTX_KEY);
        return session == null ? null : new PlaylistFile.Person(String.valueOf(session.discordUserId), session.displayName);
    }

    private void sendToGuildWsClients(String guildId, String json)
    {
        List<WsContext> toRemove = new ArrayList<>();
        wsClients.forEach((ctx, gid) ->
        {
            if (gid.equals(guildId))
            {
                try
                {
                    ctx.send(json);
                }
                catch (Exception e)
                {
                    toRemove.add(ctx);
                }
            }
        });
        toRemove.forEach(wsClients::remove);
    }

    /** Discord login is open to any Discord account, so only the owner and members of a guild the bot is in get a session. */
    static boolean mayUseWebPanel(long userId, long ownerId, Set<Long> userGuildIds, Set<Long> botGuildIds)
    {
        if (userId == ownerId)
            return true;
        for (Long id : userGuildIds)
        {
            if (botGuildIds.contains(id))
                return true;
        }
        return false;
    }

    /** @param allowedGuildIds the session's guilds, or null when the socket is not logged in */
    static boolean mayWatchGuild(boolean authEnabled, Set<Long> allowedGuildIds, String guildId)
    {
        if (!authEnabled)
            return true;
        if (allowedGuildIds == null)
            return false;
        try
        {
            return allowedGuildIds.contains(Long.parseUnsignedLong(guildId));
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /** True when guildId is the server named by the webdefaultguild setting (blank = none). */
    static boolean isDefaultGuild(String guildId, String configured)
    {
        return configured != null && !configured.isBlank() && configured.trim().equals(guildId);
    }

    /** Adds requestedBy (name, or fallbackName) and requestedById (unsigned id string, or null) to a track payload. */
    static void putRequester(Map<String, Object> target, RequestMetadata rm, String fallbackName)
    {
        boolean known = rm != null && rm.user != null;
        target.put("requestedBy", known ? rm.user.username : fallbackName);
        target.put("requestedById", known && rm.user.id != 0L ? Long.toUnsignedString(rm.user.id) : null);
    }

    static Map<String, Object> mePayload(long discordUserId, String name, String avatarUrl)
    {
        Map<String, Object> me = new HashMap<>();
        me.put("id", Long.toUnsignedString(discordUserId));
        me.put("name", name);
        me.put("avatarUrl", avatarUrl);
        return me;
    }

    private void subscribeWs(WsContext ctx, String guildId)
    {
        if (guildId == null)
            return;
        AuthSession session = wsSessions.get(ctx);
        if (mayWatchGuild(bot.getConfig().isWebAuthEnabled(), session == null ? null : session.oauthGuildIds, guildId))
            wsClients.put(ctx, guildId);
        else
            wsClients.remove(ctx);
    }

    private void pruneAuthSessions()
    {
        long now = System.currentTimeMillis();
        authSessions.entrySet().removeIf(e -> now - e.getValue().lastUsedMs > SESSION_TTL_MS);
        oauthPendingStates.entrySet().removeIf(e -> now - e.getValue() > OAUTH_STATE_TTL_MS);
    }

    private Map<String, Object> buildNowPlayingData(Guild guild)
    {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());

        Map<String, Object> result = new HashMap<>();
        result.put("playing", false);
        result.put("paused", false);
        result.put("volume", settings != null ? settings.getVolume() : 100);
        result.put("repeatMode", settings != null ? settings.getRepeatMode().name() : RepeatMode.OFF.name());
        result.put("shuffleMode", settings != null && settings.getShuffleMode());
        result.put("autoplay", settings != null && settings.getAutoplay());
        result.put("sponsorBlock", settings == null || settings.getSponsorBlock());

        if (handler != null)
        {
            result.put("paused", handler.getPlayer().isPaused());
            result.put("volume", handler.getPlayer().getVolume());

            if (handler.isMusicPlaying(bot.getJDA()))
            {
                AudioTrack track = handler.getPlayer().getPlayingTrack();
                result.put("playing", true);
                result.put("title", track.getInfo().title);
                result.put("author", track.getInfo().author);
                result.put("uri", track.getInfo().uri);
                result.put("identifier", track.getIdentifier());
                result.put("artworkUrl", resolveArtworkUrl(track));
                result.put("position", track.getPosition());
                result.put("duration", track.getDuration());
                result.put("positionFormatted", TimeUtil.formatTime(track.getPosition()));
                result.put("durationFormatted", track.getInfo().isStream ? "LIVE" : TimeUtil.formatTime(track.getDuration()));
                result.put("isStream", track.getInfo().isStream);

                putRequester(result, handler.getRequestMetadata(), "");
            }
        }
        return result;
    }

    /**
     * Lavaplayer SoundCloud fills artworkUrl; youtube-source only does with *WithThumbnail clients,
     * which we don't use for OAuth/TV playback. Fall back to the standard ytimg URL for YouTube IDs.
     */
    private static String resolveArtworkUrl(AudioTrack track)
    {
        if (track == null || track.getInfo() == null)
            return null;

        String artwork = track.getInfo().artworkUrl;
        if (artwork != null && !artwork.isBlank())
            return artwork;

        String id = track.getIdentifier();
        if (id == null || !id.matches("[\\w-]{11}"))
            return null;

        String source = track.getSourceManager() != null ? track.getSourceManager().getSourceName() : null;
        String uri = track.getInfo().uri;
        boolean youtube = "youtube".equalsIgnoreCase(source)
                || (uri != null && (uri.contains("youtube.com") || uri.contains("youtu.be") || uri.contains("music.youtube")));
        if (!youtube)
            return null;

        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }

    private Map<String, Object> buildQueueData(Guild guild)
    {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Map<String, Object> result = new HashMap<>();

        if (handler == null || handler.getQueue() == null)
        {
            result.put("tracks", List.of());
            result.put("size", 0);
            return result;
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (QueuedTrack qt : handler.getQueue().getList())
        {
            Map<String, Object> t = new HashMap<>();
            AudioTrack at = qt.getTrack();
            t.put("title", at.getInfo().title);
            t.put("author", at.getInfo().author);
            t.put("uri", at.getInfo().uri);
            t.put("artworkUrl", resolveArtworkUrl(at));
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            putRequester(t, rm, "Web");
            tracks.add(t);
        }
        result.put("tracks", tracks);
        result.put("size", tracks.size());
        return result;
    }

    private Map<String, Object> buildHistoryData(Guild guild)
    {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Map<String, Object> result = new HashMap<>();

        if (handler == null)
        {
            result.put("tracks", List.of());
            result.put("size", 0);
            return result;
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (QueuedTrack qt : handler.getHistory())
        {
            Map<String, Object> t = new HashMap<>();
            AudioTrack at = qt.getTrack();
            t.put("title", at.getInfo().title);
            t.put("author", at.getInfo().author);
            t.put("uri", at.getInfo().uri);
            t.put("artworkUrl", resolveArtworkUrl(at));
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            putRequester(t, rm, "");
            tracks.add(t);
        }
        result.put("tracks", tracks);
        result.put("size", tracks.size());
        return result;
    }

    private Map<String, Object> buildFrequentData(Guild guild)
    {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (PlayCountStore.Entry e : bot.getPlayCountStore().topForGuild(guild.getIdLong(), PlayCountStore.TOP_N))
        {
            Map<String, Object> t = e.toMap();
            t.put("durationFormatted", TimeUtil.formatTime(e.getDuration()));
            tracks.add(t);
        }
        result.put("tracks", tracks);
        result.put("size", tracks.size());
        return result;
    }

    private Guild resolveGuild(Context ctx)
    {
        String guildId = ctx.pathParam("guildId");
        Guild guild = bot.getJDA().getGuildById(guildId);
        if (guild == null)
        {
            ctx.status(404).json(Map.of("error", "Guild not found"));
            return null;
        }
        if (bot.getConfig().isWebAuthEnabled())
        {
            AuthSession session = ctx.attribute(AuthSession.CTX_KEY);
            long gid = Long.parseUnsignedLong(guild.getId());
            if (session == null || !session.oauthGuildIds.contains(gid))
            {
                ctx.status(403).json(Map.of("error", "You do not have access to this server"));
                return null;
            }
        }
        return guild;
    }

    private void handleMe(Context ctx)
    {
        AuthSession session = bot.getConfig().isWebAuthEnabled() ? ctx.attribute(AuthSession.CTX_KEY) : null;
        if (session == null)
        {
            ctx.status(404).json(Map.of("error", "Web authentication is disabled"));
            return;
        }
        ctx.json(mePayload(session.discordUserId, session.displayName, session.avatarUrl));
    }

    private void handleGetGuilds(Context ctx)
    {
        List<Map<String, Object>> guilds = new ArrayList<>();
        AuthSession webSession = bot.getConfig().isWebAuthEnabled() ? ctx.attribute(AuthSession.CTX_KEY) : null;
        for (Guild guild : bot.getJDA().getGuilds())
        {
            try
            {
                if (webSession != null && !webSession.oauthGuildIds.contains(Long.parseUnsignedLong(guild.getId())))
                    continue;
                Map<String, Object> g = new HashMap<>();
                g.put("id", guild.getId());
                g.put("name", guild.getName());
                g.put("iconUrl", guild.getIconUrl());
                g.put("isDefault", isDefaultGuild(guild.getId(), bot.getConfig().getWebDefaultGuild()));

                AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
                g.put("playing", handler != null && handler.isMusicPlaying(bot.getJDA()));

                AudioChannel audioCh = guild.getSelfMember().getVoiceState() != null
                        ? guild.getSelfMember().getVoiceState().getChannel() : null;
                g.put("voiceChannel", audioCh != null ? audioCh.getName() : null);

                guilds.add(g);
            }
            catch (Exception e)
            {
                log.warn("Web /api/guilds: skipped guild {} ({})", guild.getId(), e.toString());
            }
        }
        ctx.json(guilds);
    }

    private void handleGetNowPlaying(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;
        ctx.json(buildNowPlayingData(guild));
    }

    private void handleGetQueue(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Map<String, Object> result = new HashMap<>();

        if (handler == null || handler.getQueue() == null)
        {
            result.put("tracks", List.of());
            result.put("size", 0);
            ctx.json(result);
            return;
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (QueuedTrack qt : handler.getQueue().getList())
        {
            Map<String, Object> t = new HashMap<>();
            AudioTrack at = qt.getTrack();
            t.put("title", at.getInfo().title);
            t.put("author", at.getInfo().author);
            t.put("uri", at.getInfo().uri);
            t.put("artworkUrl", resolveArtworkUrl(at));
            t.put("identifier", at.getIdentifier());
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            putRequester(t, rm, "Web");
            tracks.add(t);
        }
        result.put("tracks", tracks);
        result.put("size", tracks.size());
        ctx.json(result);
    }

    private static final RequestMetadata WEB_REQUEST = new RequestMetadata(
            (User) null, new RequestMetadata.RequestInfo("", "")
    );

    private AuthSession validateAndTouchSession(Context ctx)
    {
        String authHeader = ctx.header("Authorization");
        String bearer = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7) : null;
        return findSession(ctx.cookie("JMBSESSION"), bearer);
    }

    /** Looks up a live session by cookie value, then by bearer token; refreshes its last-used time. */
    private AuthSession findSession(String cookieValue, String bearerToken)
    {
        long now = System.currentTimeMillis();
        for (String id : new String[]{cookieValue, bearerToken})
        {
            if (id == null || id.isEmpty())
                continue;
            AuthSession s = authSessions.get(id);
            if (s != null && now - s.lastUsedMs <= SESSION_TTL_MS)
            {
                s.lastUsedMs = now;
                return s;
            }
            authSessions.remove(id);
        }
        return null;
    }

    private RequestMetadata requestMetadataForWeb(Context ctx)
    {
        if (!bot.getConfig().isWebAuthEnabled())
            return WEB_REQUEST;
        AuthSession s = ctx.attribute(AuthSession.CTX_KEY);
        if (s == null)
            s = validateAndTouchSession(ctx);
        if (s == null)
            return WEB_REQUEST;
        return RequestMetadata.fromDiscordWebUser(s.discordUserId, s.displayName, s.discriminator, s.avatarUrl,
                new RequestMetadata.RequestInfo("", ""));
    }

    private static String urlEnc(String s)
    {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String discordAvatarUrl(String userIdStr, String avatarHash)
    {
        if (avatarHash != null && !avatarHash.isBlank())
        {
            String ext = avatarHash.startsWith("a_") ? "gif" : "png";
            return "https://cdn.discordapp.com/avatars/" + userIdStr + "/" + avatarHash + "." + ext + "?size=128";
        }
        try
        {
            long uid = Long.parseUnsignedLong(userIdStr);
            int idx = (int) ((uid >> 22) % 6);
            return "https://cdn.discordapp.com/embed/avatars/" + idx + ".png";
        }
        catch (NumberFormatException e)
        {
            return "";
        }
    }

    private static String mapStr(Map<?, ?> m, String key)
    {
        if (m == null) return "";
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private void issueSessionCookie(Context ctx, String sessionId)
    {
        // Behind a reverse proxy (Caddy) the bot itself sees plain http.
        boolean secureCookie = "https".equalsIgnoreCase(ctx.scheme())
                || "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto"));
        ctx.cookie(new Cookie(
                "JMBSESSION",
                sessionId,
                "/",
                SESSION_COOKIE_MAX_AGE_SEC,
                secureCookie,
                0,
                true,
                null,
                null,
                SameSite.LAX));
    }

    private AuthSession completeDiscordOAuth(String code) throws IOException, InterruptedException
    {
        String clientId = bot.getConfig().getWebDiscordClientId();
        String clientSecret = bot.getConfig().getWebDiscordClientSecret();
        String redirectUri = bot.getConfig().getWebDiscordRedirectUri();

        String form = "client_id=" + urlEnc(clientId)
                + "&client_secret=" + urlEnc(clientSecret)
                + "&grant_type=authorization_code"
                + "&code=" + urlEnc(code)
                + "&redirect_uri=" + urlEnc(redirectUri);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> tokenResp = HTTP.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        if (tokenResp.statusCode() / 100 != 2)
        {
            log.warn("Discord token exchange failed: {} {}", tokenResp.statusCode(), tokenResp.body());
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenJson = gson.fromJson(tokenResp.body(), Map.class);
        String accessToken = mapStr(tokenJson, "access_token");
        if (accessToken.isEmpty())
            return null;

        HttpRequest meReq = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/users/@me"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> meResp = HTTP.send(meReq, HttpResponse.BodyHandlers.ofString());
        if (meResp.statusCode() / 100 != 2)
        {
            log.warn("Discord @me failed: {} {}", meResp.statusCode(), meResp.body());
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> me = gson.fromJson(meResp.body(), Map.class);
        String idStr = mapStr(me, "id");
        if (idStr.isEmpty())
            return null;
        long userId = Long.parseUnsignedLong(idStr);
        String globalName = mapStr(me, "global_name");
        String username = mapStr(me, "username");
        String display = !globalName.isBlank() ? globalName : username;
        if (display.isBlank())
            display = "user";
        String discrim = mapStr(me, "discriminator");
        if (discrim.isBlank())
            discrim = "0";
        String avatarHash = mapStr(me, "avatar");
        if ("null".equals(avatarHash))
            avatarHash = "";
        String avatarUrl = discordAvatarUrl(idStr, avatarHash);
        Set<Long> userGuildIds = Set.of();
        try
        {
            userGuildIds = fetchMyGuildIds(accessToken);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch user's Discord guild list for web session", e);
        }
        long now = System.currentTimeMillis();
        return new AuthSession(now, userId, display, discrim, avatarUrl, userGuildIds);
    }

    private Set<Long> fetchMyGuildIds(String accessToken) throws IOException, InterruptedException
    {
        Set<Long> ids = new HashSet<>();
        String after = null;
        for (int page = 0; page < 100; page++)
        {
            StringBuilder sb = new StringBuilder("https://discord.com/api/users/@me/guilds?limit=200");
            if (after != null)
                sb.append("&after=").append(urlEnc(after));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sb.toString()))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
            {
                log.warn("Discord @me/guilds failed: HTTP {}", resp.statusCode());
                break;
            }
            @SuppressWarnings("unchecked")
            List<Object> arr = gson.fromJson(resp.body(), List.class);
            if (arr == null || arr.isEmpty())
                break;
            String lastId = null;
            for (Object o : arr)
            {
                if (o instanceof Map)
                {
                    String id = mapStr((Map<?, ?>) o, "id");
                    if (!id.isEmpty())
                    {
                        ids.add(Long.parseUnsignedLong(id));
                        lastId = id;
                    }
                }
            }
            if (arr.size() < 200)
                break;
            after = lastId;
            if (after == null)
                break;
        }
        return ids;
    }

    private static final int WEB_SEARCH_MAX_PER_SOURCE = 8;
    private static final int WEB_PLAYLIST_EXPAND_MAX = 150;
    static final long MAX_AUDIO_UPLOAD_BYTES = 100L * 1024 * 1024;
    static final String AUDIO_TOO_LARGE = "Audio files can be up to 100 MB";

    private static boolean isHttpUrl(String q)
    {
        if (q == null) return false;
        String s = q.trim();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static String normalizeSoundCloudPlaybackUrl(String url)
    {
        return SoundCloudUtil.normalizePlaybackUrl(url);
    }

    private static String soundCloudUrlToSearchQuery(String url)
    {
        try
        {
            URI u = URI.create(url.trim());
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path))
                return null;
            String[] segments = path.split("/");
            List<String> parts = new ArrayList<>();
            for (String s : segments)
            {
                if (s != null && !s.isEmpty())
                    parts.add(s);
            }
            if (parts.size() < 2)
                return null;
            String uploader = parts.get(parts.size() - 2).replace('-', ' ');
            String trackSlug = parts.get(parts.size() - 1).replace('-', ' ');
            return uploader + " " + trackSlug;
        }
        catch (IllegalArgumentException ignored)
        {
            return null;
        }
    }

    private static String firstPlaybackLoadString(String query)
    {
        String q = query.trim();
        if (isHttpUrl(q))
        {
            if (q.toLowerCase(Locale.ROOT).contains("soundcloud.com"))
                return normalizeSoundCloudPlaybackUrl(q);
            return q;
        }
        return "ytsearch:" + q;
    }

    private void dispatchWebPlayLoad(PlayerManager manager, Guild guild, AudioHandler handler, String guildId, String originalQuery, String loadString, boolean trySoundCloudSearchFallback, boolean allowSoundCloudUrlSearchFallback, RequestMetadata requester)
    {
        log.info("Web play: loading '{}' for guild {}", loadString, guildId);
        manager.loadItemOrdered(guild, loadString, new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                handler.addTrack(new QueuedTrack(track, requester));
                log.info("Web play: added {}", track.getInfo().title);
                broadcastUpdate(guildId, "queue");
                broadcastUpdate(guildId, "player");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                if (playlist.isSearchResult() && !playlist.getTracks().isEmpty())
                {
                    AudioTrack track = playlist.getTracks().get(0);
                    handler.addTrack(new QueuedTrack(track, requester));
                    log.info("Web play: added {} (search)", track.getInfo().title);
                }
                else if (!playlist.getTracks().isEmpty())
                {
                    int count = 0;
                    for (AudioTrack track : playlist.getTracks())
                    {
                        if (!bot.getConfig().isTooLong(track))
                        {
                            handler.addTrack(new QueuedTrack(track, requester));
                            count++;
                        }
                    }
                    log.info("Web play: added {} tracks from playlist {}", count, playlist.getName());
                }
                broadcastUpdate(guildId, "queue");
                broadcastUpdate(guildId, "player");
            }

            @Override
            public void noMatches()
            {
                if (trySoundCloudSearchFallback && !isHttpUrl(originalQuery))
                {
                    log.info("Web play: no YouTube matches, trying SoundCloud search for '{}'", originalQuery);
                    dispatchWebPlayLoad(manager, guild, handler, guildId, originalQuery, "scsearch:" + originalQuery.trim(), false, false, requester);
                    return;
                }
                log.warn("Web play: no matches for {}", originalQuery);
                broadcastLoadError(guildId, "NO_MATCHES", "No results found for that query.");
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                if (trySoundCloudSearchFallback && !isHttpUrl(originalQuery))
                {
                    log.warn("Web play: YouTube load failed ({}), trying SoundCloud search", exception.getMessage());
                    dispatchWebPlayLoad(manager, guild, handler, guildId, originalQuery, "scsearch:" + originalQuery.trim(), false, false, requester);
                    return;
                }
                if (isHttpUrl(originalQuery) && originalQuery.toLowerCase(Locale.ROOT).contains("soundcloud.com"))
                {
                    String norm = normalizeSoundCloudPlaybackUrl(originalQuery);
                    if (!norm.equals(originalQuery.trim()))
                    {
                        log.info("Web play: SoundCloud load failed, retrying normalized URL");
                        dispatchWebPlayLoad(manager, guild, handler, guildId, originalQuery, norm, false, allowSoundCloudUrlSearchFallback, requester);
                        return;
                    }
                    if (allowSoundCloudUrlSearchFallback)
                    {
                        String scQuery = soundCloudUrlToSearchQuery(originalQuery);
                        if (scQuery != null && !scQuery.isBlank())
                        {
                            log.info("Web play: SoundCloud URL load failed, trying scsearch '{}'", scQuery);
                            dispatchWebPlayLoad(manager, guild, handler, guildId, originalQuery, "scsearch:" + scQuery.trim(), false, false, requester);
                            return;
                        }
                    }
                }
                log.warn("Web play: load failed for {}", originalQuery, exception);
                broadcastLoadError(guildId, "LOAD_FAILED", "Could not load that track or playlist.");
            }
        });
    }

    private void handlePlay(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Map<String, Object> body;
        try
        {
            body = ctx.bodyAsClass(Map.class);
        }
        catch (Exception e)
        {
            log.debug("Web play: invalid JSON body", e);
            ctx.status(400).json(Map.of("error", "Invalid JSON body"));
            return;
        }
        if (body == null)
        {
            body = Map.of();
        }
        Object qObj = body.get("query");
        String query = qObj == null ? "" : String.valueOf(qObj).trim();
        if (query.isEmpty() || "null".equals(query))
        {
            ctx.status(400).json(Map.of("error", "Query is required"));
            return;
        }

        String guildId = guild.getId();

        PlayerManager manager = bot.getPlayerManager();
        AudioHandler handler;
        try
        {
            handler = manager.setUpHandler(guild);

            if (!guild.getSelfMember().getVoiceState().inAudioChannel())
            {
                AudioChannel connectTo = findUserVoiceChannel(guild, body);
                if (connectTo == null) connectTo = findConfiguredVoiceChannel(guild);
                if (connectTo == null && !guild.getVoiceChannels().isEmpty())
                    connectTo = guild.getVoiceChannels().get(0);
                if (connectTo == null)
                {
                    ctx.json(Map.of("message", "No voice channel available"));
                    return;
                }
                guild.getAudioManager().openAudioConnection(connectTo);
            }
        }
        catch (Exception e)
        {
            log.error("Web play: voice/setup failed for guild {}", guildId, e);
            ctx.status(500).json(Map.of("error", "Could not join a voice channel. Check bot permissions and try again."));
            return;
        }

        ctx.json(Map.of("message", "Loading..."));

        try
        {
            RequestMetadata requester = requestMetadataForWeb(ctx);
            if(SpotifyResolver.looksLikeSpotifyUrl(query))
            {
                SpotifyResolver spotify = bot.getSpotifyResolver();
                if(spotify == null)
                {
                    broadcastLoadError(guildId, "SPOTIFY_NOT_CONFIGURED", "Spotify isn't set up in config.txt.");
                    return;
                }
                spotify.resolve(query).whenComplete((result, ex) ->
                {
                    if(ex != null)
                    {
                        broadcastLoadError(guildId, "SPOTIFY_RESOLVE", ex.getMessage());
                        return;
                    }
                    if(!result.isSuccess())
                    {
                        broadcastLoadError(guildId, "SPOTIFY_RESOLVE", result.getError());
                        return;
                    }
                    for(String search : result.searchQueries())
                        dispatchWebPlayLoad(manager, guild, handler, guildId, search, "ytsearch:" + search, false, false, requester);
                });
                return;
            }
            String first = firstPlaybackLoadString(query);
            boolean scFallback = !isHttpUrl(query);
            dispatchWebPlayLoad(manager, guild, handler, guildId, query, first, scFallback, true, requester);
        }
        catch (Exception e)
        {
            log.error("Web play: failed to dispatch load for guild {}", guildId, e);
            broadcastLoadError(guildId, "LOAD_FAILED", "Could not start loading that track.");
        }
    }

    private void handlePause(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
        {
            ctx.json(Map.of("paused", false));
            return;
        }
        boolean newState = !handler.getPlayer().isPaused();
        handler.getPlayer().setPaused(newState);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("paused", newState));
    }

    private void handleSkip(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
        {
            ctx.json(Map.of("skipped", false));
            return;
        }
        handler.getPlayer().stopTrack();
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("skipped", true));
    }

    private void handleStop(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
        {
            ctx.json(Map.of("stopped", false));
            return;
        }
        handler.stopAndClear();
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("stopped", true));
    }

    private void handleVolume(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object volObj = body.get("volume");
        if (volObj == null)
        {
            ctx.status(400).result("Volume is required");
            return;
        }
        int volume = ((Number) volObj).intValue();
        volume = Math.max(0, Math.min(150, volume));
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler != null)
        {
            handler.getPlayer().setVolume(volume);
        }
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings != null)
        {
            settings.setVolume(volume);
        }
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("volume", volume));
    }

    private void handleAutoplay(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings == null)
        {
            ctx.status(400).json(Map.of("error", "No guild settings"));
            return;
        }
        boolean next = !settings.getAutoplay();
        settings.setAutoplay(next);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("autoplay", next));
    }

    private void handleSponsorBlock(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings == null)
        {
            ctx.status(400).json(Map.of("error", "No guild settings"));
            return;
        }
        boolean next = !settings.getSponsorBlock();
        settings.setSponsorBlock(next);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("sponsorBlock", next));
    }

    private void handleShuffle(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings == null)
        {
            ctx.status(400).json(Map.of("error", "No guild settings"));
            return;
        }
        boolean next = !settings.getShuffleMode();
        settings.setShuffleMode(next);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("shuffleMode", next));
    }

    private void handleRepeat(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings == null)
        {
            ctx.status(400).json(Map.of("error", "No guild settings"));
            return;
        }
        RepeatMode current = settings.getRepeatMode();
        RepeatMode next;
        switch (current)
        {
            case OFF:
                next = RepeatMode.ALL;
                break;
            case ALL:
                next = RepeatMode.SINGLE;
                break;
            case SINGLE:
            default:
                next = RepeatMode.OFF;
                break;
        }
        settings.setRepeatMode(next);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("repeatMode", next.name()));
    }

    private void handleSeek(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null)
        {
            ctx.status(400).result("No track playing");
            return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object posObj = body.get("position");
        if (posObj == null)
        {
            ctx.status(400).result("Position is required");
            return;
        }
        long position = ((Number) posObj).longValue();
        AudioTrack track = handler.getPlayer().getPlayingTrack();
        if (track == null || !track.isSeekable())
        {
            ctx.status(400).result("Cannot seek on current track");
            return;
        }
        track.setPosition(position);
        broadcastUpdate(guild.getId(), "player");
        ctx.json(Map.of("position", position));
    }

    private static final java.util.Set<String> AUDIO_EXTENSIONS = java.util.Set.of(
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus",
            "mp4", "mkv", "webm", "avi", "mov", "aiff", "alac"
    );

    void handleUpload(Context ctx)
    {
        // before anything else reads the request: an oversized upload is refused unread, and one that announces no
        // length is cut off at the cap instead of being spooled to disk in full
        if (UploadLimit.tooLarge(ctx, MAX_AUDIO_UPLOAD_BYTES))
        {
            ctx.status(413).result(AUDIO_TOO_LARGE);
            return;
        }
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        String guildId = guild.getId();
        UploadedFile upload;
        try
        {
            upload = ctx.uploadedFile("file");
        }
        catch (IllegalStateException overTheCap)
        {
            ctx.status(413).result(AUDIO_TOO_LARGE);
            return;
        }
        if (upload == null)
        {
            ctx.status(400).result("No file uploaded");
            return;
        }

        String filename = upload.filename();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!AUDIO_EXTENSIONS.contains(ext))
        {
            ctx.status(400).result("Unsupported file type: " + ext);
            return;
        }

        Path tempFile;
        try
        {
            Path uploadDir = Path.of(System.getProperty("java.io.tmpdir"), "jmusicbot-uploads");
            Files.createDirectories(uploadDir);
            tempFile = Files.createTempFile(uploadDir, "upload-", "." + ext);
            Files.copy(upload.content(), tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            log.error("Web upload: failed to save file", e);
            ctx.status(500).result("Failed to save uploaded file");
            return;
        }

        PlayerManager manager = bot.getPlayerManager();
        AudioHandler handler;
        try
        {
            handler = manager.setUpHandler(guild);

            if (!guild.getSelfMember().getVoiceState().inAudioChannel())
            {
                AudioChannel connectTo = findConfiguredVoiceChannel(guild);
                if (connectTo == null && !guild.getVoiceChannels().isEmpty())
                    connectTo = guild.getVoiceChannels().get(0);
                if (connectTo == null)
                {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                    ctx.json(Map.of("message", "No voice channel available"));
                    return;
                }
                guild.getAudioManager().openAudioConnection(connectTo);
            }
        }
        catch (Exception e)
        {
            log.error("Web upload: voice/setup failed for guild {}", guildId, e);
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            ctx.status(500).json(Map.of("error", "Could not join a voice channel. Check bot permissions and try again."));
            return;
        }

        ctx.json(Map.of("message", "Loading " + filename + "..."));

        String localPath = tempFile.toAbsolutePath().toString();
        log.info("Web upload: loading '{}' for guild {}", localPath, guildId);

        scheduleTempFileCleanup(tempFile);

        final RequestMetadata uploadRequester = requestMetadataForWeb(ctx);

        try
        {
            manager.loadItemOrdered(guild, localPath, new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                handler.addTrack(new QueuedTrack(track, uploadRequester));
                log.info("Web upload: added {}", track.getInfo().title);
                broadcastUpdate(guildId, "queue");
                broadcastUpdate(guildId, "player");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                if (!playlist.getTracks().isEmpty())
                {
                    int count = 0;
                    for (AudioTrack track : playlist.getTracks())
                    {
                        if (!bot.getConfig().isTooLong(track))
                        {
                            handler.addTrack(new QueuedTrack(track, uploadRequester));
                            count++;
                        }
                    }
                    log.info("Web upload: added {} tracks from {}", count, filename);
                }
                broadcastUpdate(guildId, "queue");
                broadcastUpdate(guildId, "player");
            }

            @Override
            public void noMatches()
            {
                log.warn("Web upload: no matches for {}", filename);
                broadcastLoadError(guildId, "NO_MATCHES", "Could not read that file as audio.");
            }

            @Override
            public void loadFailed(FriendlyException exception)
            {
                log.warn("Web upload: load failed for {}", filename, exception);
                broadcastLoadError(guildId, "LOAD_FAILED", "Could not load the uploaded file.");
            }
        });
        }
        catch (Exception e)
        {
            log.error("Web upload: failed to dispatch load for guild {}", guildId, e);
            broadcastLoadError(guildId, "LOAD_FAILED", "Could not start loading the upload.");
        }
    }

    private void scheduleTempFileCleanup(Path tempFile)
    {
        bot.getJDA().getRateLimitPool().schedule(() ->
        {
            try
            {
                Files.deleteIfExists(tempFile);
                log.debug("Cleaned up temp file: {}", tempFile);
            }
            catch (IOException ignored) {}
        }, 30, TimeUnit.MINUTES);
    }

    /**
     * What PlaylistRoutes calls to play a playlist: joins a voice channel if the bot is in none, hands the playlist to
     * the PlaylistPlayer, which queues its songs in order as they load, and answers the request. The playlist is checked
     * before the bot joins anything, so a wrong name costs no voice connection.
     */
    private void playPlaylist(Context ctx, String guildId, String name, String folder, String mode, String songId)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        if (bot.getPlaylistStore().get(name, folder).isEmpty())
        {
            ctx.status(404).json(Map.of("error", "Playlist not found"));
            return;
        }

        Consumer<AudioTrack> queue = queueForWeb(ctx, guild);
        if (queue == null) return;

        log.info("Web playlist: loading '{}' from folder '{}' ({}) for guild {}", name, folder, mode, guildId);
        // Not found / bad mode are thrown here, before anything is written to the response.
        bot.getPlaylistPlayer().load(guild, name, folder, mode, songId, queue, finishedLoading(guildId, "'" + name + "'"));
        ctx.json(Map.of("message", "Loading playlist " + name + "..."));
    }

    /**
     * What LikeRoutes calls to play someone's likes, or everyone's mix: as {@link #playPlaylist}, with the list already
     * read (and checked) by LikeRoutes.
     */
    private void playLikes(Context ctx, String guildId, PlaylistFile list, String mode, String songId)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Consumer<AudioTrack> queue = queueForWeb(ctx, guild);
        if (queue == null) return;

        log.info("Web likes: loading the likes of {} ({}) for guild {}", list.getName(), mode, guildId);
        bot.getPlaylistPlayer().loadFile(list, "likes:" + guild.getId() + ":" + list.getName(), mode, songId, queue,
                finishedLoading(guildId, "the likes of " + list.getName()));
        ctx.json(Map.of("message", LikeRoutes.loadingMessage(list)));
    }

    /**
     * Gets a server ready for songs from the panel: joins a voice channel if the bot is in none (the configured one,
     * else the first). Returns what queues a track as requested by whoever is asking; null when the bot can't join,
     * after answering the request itself.
     */
    private Consumer<AudioTrack> queueForWeb(Context ctx, Guild guild)
    {
        AudioHandler handler;
        try
        {
            handler = bot.getPlayerManager().setUpHandler(guild);

            if (!guild.getSelfMember().getVoiceState().inAudioChannel())
            {
                AudioChannel connectTo = findConfiguredVoiceChannel(guild);
                if (connectTo == null && !guild.getVoiceChannels().isEmpty())
                    connectTo = guild.getVoiceChannels().get(0);
                if (connectTo == null)
                {
                    ctx.json(Map.of("message", "No voice channel available"));
                    return null;
                }
                guild.getAudioManager().openAudioConnection(connectTo);
            }
        }
        catch (Exception e)
        {
            log.error("Web playlist: voice/setup failed for guild {}", guild.getId(), e);
            ctx.status(500).json(Map.of("error", "Could not join a voice channel. Check bot permissions and try again."));
            return null;
        }

        final RequestMetadata requester = requestMetadataForWeb(ctx);
        return track -> handler.addTrack(new QueuedTrack(track, requester));
    }

    /** What runs once a playlist (or a list of likes) has loaded: refreshes the panels' queue and player, and logs it. */
    private Consumer<PlaylistPlayer.Result> finishedLoading(String guildId, String what)
    {
        return result ->
        {
            broadcastUpdate(guildId, "queue");
            broadcastUpdate(guildId, "player");
            log.info("Web playlist: finished loading {}: {} queued, {} failed", what, result.getLoaded(), result.getErrors().size());
        };
    }

    private void handleResolvePlaylistUrl(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object urlObj = body != null ? body.get("url") : null;
        String url = urlObj != null ? urlObj.toString().trim() : "";
        if (url.isEmpty())
        {
            ctx.status(400).json(Map.of("error", "url is required"));
            return;
        }
        if (!isHttpUrl(url))
        {
            ctx.status(400).json(Map.of("error", "url must be an http(s) URL"));
            return;
        }

        final String loadUrl = url.toLowerCase(Locale.ROOT).contains("soundcloud.com")
                ? normalizeSoundCloudPlaybackUrl(url) : url;
        final String guildId = guild.getId();

        ctx.future(() -> CompletableFuture.supplyAsync(() ->
        {
            List<Map<String, Object>> tracks = new ArrayList<>();
            final int[] totalAvailable = {0};
            final boolean[] truncated = {false};
            final String[] kind = {""};
            final String[] title = {null};
            final boolean[] failed = {false};
            CountDownLatch latch = new CountDownLatch(1);
            bot.getPlayerManager().loadItemOrdered(guild, loadUrl, new AudioLoadResultHandler()
            {
                @Override
                public void trackLoaded(AudioTrack track)
                {
                    kind[0] = "track";
                    totalAvailable[0] = 1;
                    truncated[0] = false;
                    tracks.add(buildTrackMap(track));
                    latch.countDown();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist)
                {
                    kind[0] = "playlist";
                    title[0] = playlist.getName();
                    int full = playlist.getTracks().size();
                    totalAvailable[0] = full;
                    addTracksUpTo(tracks, playlist, WEB_PLAYLIST_EXPAND_MAX, null);
                    truncated[0] = full > WEB_PLAYLIST_EXPAND_MAX;
                    latch.countDown();
                }

                @Override
                public void noMatches()
                {
                    failed[0] = true;
                    latch.countDown();
                }

                @Override
                public void loadFailed(FriendlyException exception)
                {
                    log.debug("Web resolve-playlist-url failed: {}", exception.getMessage());
                    failed[0] = true;
                    latch.countDown();
                }
            });
            awaitSearchLatch(latch, guildId, "resolve-playlist-url");

            Map<String, Object> result = new HashMap<>();
            if (failed[0] && tracks.isEmpty())
            {
                result.put("error", "Could not resolve URL");
                return result;
            }
            if (tracks.isEmpty())
            {
                result.put("error", "No tracks found");
                return result;
            }
            if (kind[0].isEmpty())
            {
                kind[0] = "track";
            }
            result.put("kind", kind[0]);
            if (title[0] != null && !title[0].trim().isEmpty())
            {
                result.put("title", title[0].trim());
            }
            result.put("tracks", tracks);
            result.put("totalAvailable", totalAvailable[0]);
            result.put("truncated", truncated[0]);
            return result;
        }).thenAccept(result ->
        {
            if (result.containsKey("error"))
            {
                ctx.status(404).json(result);
            }
            else
            {
                ctx.json(result);
            }
        }).exceptionally(ex -> resolvePlaylistUrlException(ctx, guild, ex)));
    }

    private Void resolvePlaylistUrlException(Context ctx, Guild guild, Throwable ex)
    {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.warn("Web resolve-playlist-url failed for guild {}", guild.getId(), cause);
        ctx.status(500).json(Map.of("error", "Failed to resolve URL"));
        return null;
    }

    private void handleSearch(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        String query = ctx.queryParam("q");
        if (query == null || query.isEmpty())
        {
            ctx.json(List.of());
            return;
        }

        final String qTrim = query.trim();
        if (isHttpUrl(qTrim))
        {
            final String searchQuery = qTrim.toLowerCase(Locale.ROOT).contains("soundcloud.com")
                    ? normalizeSoundCloudPlaybackUrl(qTrim) : qTrim;
            ctx.future(() -> CompletableFuture.supplyAsync(() ->
            {
                List<Map<String, Object>> results = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(1);
                bot.getPlayerManager().loadItemOrdered(guild, searchQuery, new AudioLoadResultHandler()
                {
                    @Override
                    public void trackLoaded(AudioTrack track)
                    {
                        results.add(buildTrackMap(track));
                        latch.countDown();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist)
                    {
                        addTracksUpTo(results, playlist, WEB_SEARCH_MAX_PER_SOURCE, null);
                        latch.countDown();
                    }

                    @Override
                    public void noMatches()
                    {
                        latch.countDown();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception)
                    {
                        latch.countDown();
                    }
                });
                awaitSearchLatch(latch, guild.getId(), "url");
                return results;
            }).thenAccept(results -> writeSearchJson(ctx, results))
                    .exceptionally(ex -> searchException(ctx, guild, ex)));
            return;
        }

        ctx.future(() -> CompletableFuture.supplyAsync(() ->
        {
            List<Map<String, Object>> yt = Collections.synchronizedList(new ArrayList<>());
            List<Map<String, Object>> sc = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(2);

            bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + qTrim, new AudioLoadResultHandler()
            {
                @Override
                public void trackLoaded(AudioTrack track)
                {
                    yt.add(buildTrackMap(track, "youtube"));
                    latch.countDown();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist)
                {
                    addTracksUpTo(yt, playlist, WEB_SEARCH_MAX_PER_SOURCE, "youtube");
                    latch.countDown();
                }

                @Override
                public void noMatches()
                {
                    latch.countDown();
                }

                @Override
                public void loadFailed(FriendlyException exception)
                {
                    log.debug("Web search YouTube failed: {}", exception.getMessage());
                    latch.countDown();
                }
            });

            bot.getPlayerManager().loadItemOrdered(guild, "scsearch:" + qTrim, new AudioLoadResultHandler()
            {
                @Override
                public void trackLoaded(AudioTrack track)
                {
                    sc.add(buildTrackMap(track, "soundcloud"));
                    latch.countDown();
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist)
                {
                    addTracksUpTo(sc, playlist, WEB_SEARCH_MAX_PER_SOURCE, "soundcloud");
                    latch.countDown();
                }

                @Override
                public void noMatches()
                {
                    latch.countDown();
                }

                @Override
                public void loadFailed(FriendlyException exception)
                {
                    log.debug("Web search SoundCloud failed: {}", exception.getMessage());
                    latch.countDown();
                }
            });

            awaitSearchLatch(latch, guild.getId(), "parallel");
            return mergeInterleavedUnique(yt, sc, 16);
        }).thenAccept(results -> writeSearchJson(ctx, results))
                .exceptionally(ex -> searchException(ctx, guild, ex)));
    }

    private void writeSearchJson(Context ctx, List<Map<String, Object>> results)
    {
        ctx.contentType("application/json");
        ctx.result(gson.toJson(results));
    }

    private Void searchException(Context ctx, Guild guild, Throwable ex)
    {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.warn("Web search failed for guild {}", guild.getId(), cause);
        ctx.status(500);
        ctx.contentType("application/json");
        ctx.result("[]");
        return null;
    }

    private static void awaitSearchLatch(CountDownLatch latch, String guildId, String kind)
    {
        try
        {
            if (!latch.await(20, TimeUnit.SECONDS))
                log.warn("Web search ({}): timed out for guild {}", kind, guildId);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void addTracksUpTo(List<Map<String, Object>> out, AudioPlaylist playlist, int max, String source)
    {
        int n = 0;
        for (AudioTrack track : playlist.getTracks())
        {
            if (n >= max) break;
            out.add(source != null ? buildTrackMap(track, source) : buildTrackMap(track));
            n++;
        }
    }

    private static List<Map<String, Object>> mergeInterleavedUnique(List<Map<String, Object>> a, List<Map<String, Object>> b, int maxTotal)
    {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int maxRounds = Math.max(a.size(), b.size());
        for (int i = 0; i < maxRounds && merged.size() < maxTotal; i++)
        {
            if (i < a.size())
                addSearchRowIfUnique(merged, seen, a.get(i), maxTotal);
            if (merged.size() < maxTotal && i < b.size())
                addSearchRowIfUnique(merged, seen, b.get(i), maxTotal);
        }
        return merged;
    }

    private static void addSearchRowIfUnique(List<Map<String, Object>> out, Set<String> seen, Map<String, Object> row, int maxTotal)
    {
        if (out.size() >= maxTotal) return;
        Object u = row.get("uri");
        String key = u != null ? u.toString() : String.valueOf(row.get("identifier"));
        if (key == null || key.isEmpty() || "null".equals(key))
            key = String.valueOf(row.get("title")) + "|" + row.get("author");
        if (seen.add(key))
            out.add(row);
    }

    private static String inferTrackSource(AudioTrack track)
    {
        String u = track.getInfo().uri;
        if (u != null && u.toLowerCase(Locale.ROOT).contains("soundcloud.com"))
            return "soundcloud";
        return "youtube";
    }

    private static Map<String, Object> buildTrackMap(AudioTrack track)
    {
        return buildTrackMap(track, null);
    }

    private static Map<String, Object> buildTrackMap(AudioTrack track, String source)
    {
        if (source == null)
            source = inferTrackSource(track);
        Map<String, Object> t = new HashMap<>();
        t.put("title", track.getInfo().title);
        t.put("author", track.getInfo().author);
        t.put("uri", track.getInfo().uri);
        t.put("artworkUrl", resolveArtworkUrl(track));
        t.put("identifier", track.getIdentifier());
        t.put("duration", track.getDuration());
        t.put("durationFormatted", TimeUtil.formatTime(track.getDuration()));
        t.put("isStream", track.getInfo().isStream);
        t.put("source", source);
        return t;
    }

    private void handleReorder(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null || handler.getQueue() == null)
        {
            ctx.status(400).result("No queue");
            return;
        }

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object fromObj = body.get("from");
        Object toObj = body.get("to");
        if (fromObj == null || toObj == null)
        {
            ctx.status(400).result("from and to are required");
            return;
        }

        int from = ((Number) fromObj).intValue();
        int to = ((Number) toObj).intValue();

        if (from < 0 || from >= handler.getQueue().size() || to < 0 || to >= handler.getQueue().size())
        {
            ctx.status(400).result("Index out of bounds");
            return;
        }

        handler.getQueue().moveItem(from, to);
        broadcastUpdate(guild.getId(), "queue");
        ctx.json(Map.of("message", "Reordered", "from", from, "to", to));
    }

    private void handleRemove(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null || handler.getQueue() == null)
        {
            ctx.status(400).result("No queue");
            return;
        }

        int index;
        try
        {
            index = Integer.parseInt(ctx.pathParam("index"));
        }
        catch (NumberFormatException e)
        {
            ctx.status(400).result("Invalid index");
            return;
        }

        if (index < 0 || index >= handler.getQueue().size())
        {
            ctx.status(400).result("Index out of bounds");
            return;
        }

        QueuedTrack removed = handler.getQueue().remove(index);
        broadcastUpdate(guild.getId(), "queue");
        ctx.json(Map.of("message", "Removed", "title", removed != null ? removed.getTrack().getInfo().title : ""));
    }

    private VoiceChannel findUserVoiceChannel(Guild guild, Map<String, Object> body)
    {
        Object vcId = body.get("voiceChannelId");
        if (vcId != null)
        {
            try
            {
                VoiceChannel vc = guild.getVoiceChannelById(vcId.toString());
                if (vc != null && guild.getSelfMember().hasPermission(vc, net.dv8tion.jda.api.Permission.VOICE_CONNECT))
                {
                    return vc;
                }
            }
            catch (Exception ignored) {}
        }
        return null;
    }

    private VoiceChannel findConfiguredVoiceChannel(Guild guild)
    {
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());
        if (settings != null)
        {
            VoiceChannel vc = settings.getVoiceChannel(guild);
            if (vc != null) return vc;
        }
        return null;
    }

    void authMiddleware(Context ctx)
    {
        String path = ctx.path();
        if ("/api/logout".equals(path)) return;
        if ("/api/auth/discord".equals(path) || "/api/auth/discord/callback".equals(path)) return;

        AuthSession session = validateAndTouchSession(ctx);
        if (session != null)
        {
            ctx.attribute(AuthSession.CTX_KEY, session);
            return;
        }

        // Setting a status alone does not stop Javalin from running the endpoint.
        ctx.status(401).json(Map.of("error", "Authentication required"));
        ctx.skipRemainingHandlers();
    }

    private void handleDiscordAuthStart(Context ctx)
    {
        if (!bot.getConfig().isWebAuthEnabled())
        {
            ctx.status(400).result("Web authentication is disabled");
            return;
        }
        String state = UUID.randomUUID().toString();
        oauthPendingStates.put(state, System.currentTimeMillis());
        String clientId = bot.getConfig().getWebDiscordClientId();
        String redirectUri = bot.getConfig().getWebDiscordRedirectUri();
        try
        {
            String url = "https://discord.com/api/oauth2/authorize?client_id=" + urlEnc(clientId)
                    + "&redirect_uri=" + urlEnc(redirectUri)
                    + "&response_type=code&scope=" + urlEnc("identify guilds")
                    + "&state=" + urlEnc(state);
            ctx.redirect(url);
        }
        catch (Exception e)
        {
            log.error("Discord OAuth redirect failed", e);
            ctx.status(500).result("OAuth configuration error");
        }
    }

    private void handleDiscordAuthCallback(Context ctx)
    {
        String err = ctx.queryParam("error");
        if (err != null)
        {
            log.info("Discord OAuth error: {} {}", err, ctx.queryParam("error_description"));
            ctx.redirect("/web/index.html?login=error");
            return;
        }
        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        if (code == null || code.isBlank() || state == null || state.isBlank())
        {
            ctx.redirect("/web/index.html?login=error");
            return;
        }
        Long created = oauthPendingStates.remove(state);
        long now = System.currentTimeMillis();
        if (created == null || now - created > OAUTH_STATE_TTL_MS)
        {
            log.warn("Discord OAuth invalid or expired state");
            ctx.redirect("/web/index.html?login=error");
            return;
        }

        try
        {
            AuthSession sessionUser = completeDiscordOAuth(code);
            if (sessionUser == null)
            {
                ctx.redirect("/web/index.html?login=error");
                return;
            }
            Set<Long> botGuildIds = new HashSet<>();
            bot.getJDA().getGuilds().forEach(g -> botGuildIds.add(g.getIdLong()));
            if (!mayUseWebPanel(sessionUser.discordUserId, bot.getConfig().getOwnerId(), sessionUser.oauthGuildIds, botGuildIds))
            {
                log.warn("Web login denied for Discord user {} ({}): not in any server with the bot",
                        sessionUser.displayName, Long.toUnsignedString(sessionUser.discordUserId));
                ctx.redirect("/web/index.html?login=denied");
                return;
            }
            String sessionId = UUID.randomUUID().toString();
            authSessions.put(sessionId, sessionUser);
            issueSessionCookie(ctx, sessionId);
            ctx.redirect("/web/index.html");
        }
        catch (Exception e)
        {
            log.error("Discord OAuth callback failed", e);
            ctx.redirect("/web/index.html?login=error");
        }
    }

    private void handleLogout(Context ctx)
    {
        String session = ctx.cookie("JMBSESSION");
        if (session != null) authSessions.remove(session);
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer "))
        {
            authSessions.remove(authHeader.substring(7));
        }
        ctx.removeCookie("JMBSESSION", "/");
        ctx.json(Map.of("message", "Logged out"));
    }

    private void handleGetHistory(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Map<String, Object> result = new HashMap<>();

        if (handler == null)
        {
            result.put("tracks", List.of());
            result.put("size", 0);
            ctx.json(result);
            return;
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (QueuedTrack qt : handler.getHistory())
        {
            Map<String, Object> t = new HashMap<>();
            AudioTrack at = qt.getTrack();
            t.put("title", at.getInfo().title);
            t.put("author", at.getInfo().author);
            t.put("uri", at.getInfo().uri);
            t.put("artworkUrl", resolveArtworkUrl(at));
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            putRequester(t, rm, "");
            tracks.add(t);
        }
        result.put("tracks", tracks);
        result.put("size", tracks.size());
        ctx.json(result);
    }

    private void handleClearHistory(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler != null)
        {
            handler.clearHistory();
        }
        broadcastUpdate(guild.getId(), "history");
        ctx.json(Map.of("message", "History cleared"));
    }

    private void handleGetFrequent(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;
        ctx.json(buildFrequentData(guild));
    }

    private void handleClearFrequent(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;
        bot.getPlayCountStore().clearGuild(guild.getIdLong());
        broadcastUpdate(guild.getId(), "frequent");
        ctx.json(Map.of("message", "Most-played cleared"));
    }

    private void handleClearQueue(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler != null)
        {
            handler.clearQueue();
        }
        ctx.json(Map.of("message", "Queue cleared"));
    }

    private void handleGetLyrics(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        String prefetchTitle = ctx.queryParam("title");
        if (prefetchTitle != null && !prefetchTitle.isBlank())
        {
            String prefetchAuthor = ctx.queryParam("author");
            if (prefetchAuthor == null)
                prefetchAuthor = "";
            long durationMs = 180_000L;
            String durParam = ctx.queryParam("duration");
            if (durParam != null && !durParam.isBlank())
            {
                try
                {
                    long v = Long.parseLong(durParam.trim());
                    if (v > 0 && v < 18_000_000L)
                        durationMs = v;
                }
                catch (NumberFormatException ignored) {}
            }
            final String pt = prefetchTitle;
            final String pa = prefetchAuthor;
            final long dms = durationMs;
            ctx.future(() -> LyricsFetch.fetchForTitleArtistDurationAsync(pt, pa, dms)
                    .thenAccept(result -> ctx.json(result))
                    .exceptionally(ex ->
                    {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        log.debug("Lyrics prefetch failed for guild {}: {}", guild.getId(), cause.toString());
                        ctx.status(500).json(Map.of(
                                "synced", false,
                                "lines", List.of(),
                                "error", "Could not load lyrics"));
                        return null;
                    }));
            return;
        }

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if (handler == null || !handler.isMusicPlaying(bot.getJDA()))
        {
            ctx.json(Map.of(
                    "synced", false,
                    "lines", List.of(),
                    "error", "Nothing playing"));
            return;
        }

        AudioTrack track = handler.getPlayer().getPlayingTrack();
        ctx.future(() -> LyricsFetch.fetchForTrackAsync(track)
                .thenAccept(result -> ctx.json(result))
                .exceptionally(ex ->
                {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("Lyrics fetch failed for guild {}", guild.getId(), cause);
                    ctx.status(500).json(Map.of(
                            "synced", false,
                            "lines", List.of(),
                            "error", "Could not load lyrics"));
                    return null;
                }));
    }
}
