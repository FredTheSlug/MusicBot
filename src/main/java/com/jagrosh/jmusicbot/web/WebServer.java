package com.jagrosh.jmusicbot.web;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.javalin.websocket.WsContext;

public class WebServer
{
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final long SESSION_TTL_MS = TimeUnit.SECONDS.toMillis(86400);
    private static final long OAUTH_STATE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final Bot bot;
    private final Gson gson = new Gson();
    private Javalin app;
    private final Map<WsContext, String> wsClients = new ConcurrentHashMap<>();
    private final Map<String, AuthSession> authSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> oauthPendingStates = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> sessionCleanupTask;

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

        app.ws("/ws", ws ->
        {
            ws.onConnect(ctx ->
            {
                String guildId = ctx.queryParam("guild");
                if (guildId != null)
                {
                    wsClients.put(ctx, guildId);
                }
            });
            ws.onClose(ctx ->
            {
                wsClients.remove(ctx);
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
                    String gid = msg.substring("guild:".length());
                    wsClients.put(ctx, gid);
                }
            });
        });

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
        app.post("/api/guild/{guildId}/seek", this::handleSeek);
        app.post("/api/guild/{guildId}/upload", this::handleUpload);
        app.get("/api/playlists", this::handleListPlaylists);
        app.get("/api/playlist/{name}", this::handleGetPlaylist);
        app.post("/api/playlist/{name}", this::handleSavePlaylist);
        app.delete("/api/playlist/{name}", this::handleDeletePlaylist);
        app.post("/api/guild/{guildId}/playplaylist/{name}", this::handlePlayPlaylist);
        app.get("/api/playlist-folders", this::handleListFolders);
        app.post("/api/playlist-folder", this::handleCreateFolder);
        app.delete("/api/playlist-folder", this::handleDeleteFolder);
        app.put("/api/playlist-move", this::handleMovePlaylist);
        app.get("/api/playlists-tree", this::handlePlaylistsTree);
        app.get("/api/playlist-detail/{name}", this::handleGetPlaylistDetail);
        app.put("/api/playlist-rename/{name}", this::handleRenamePlaylist);
        app.get("/api/guild/{guildId}/search", this::handleSearch);
        app.post("/api/guild/{guildId}/resolve-playlist-url", this::handleResolvePlaylistUrl);
        app.post("/api/guild/{guildId}/reorder", this::handleReorder);
        app.post("/api/guild/{guildId}/remove/{index}", this::handleRemove);
        app.get("/api/guild/{guildId}/history", this::handleGetHistory);
        app.post("/api/guild/{guildId}/clear-history", this::handleClearHistory);
        app.post("/api/guild/{guildId}/clear-queue", this::handleClearQueue);
        app.get("/api/guild/{guildId}/lyrics", this::handleGetLyrics);
        app.get("/api/auth/discord", this::handleDiscordAuthStart);
        app.get("/api/auth/discord/callback", this::handleDiscordAuthCallback);
        app.post("/api/logout", this::handleLogout);

        if (bot.getConfig().isWebAuthEnabled())
        {
            app.before("/api/*", this::authMiddleware);
        }

        app.start(port);
        log.info("Web interface started on port {}", port);

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
                result.put("position", track.getPosition());
                result.put("duration", track.getDuration());
                result.put("positionFormatted", TimeUtil.formatTime(track.getPosition()));
                result.put("durationFormatted", track.getInfo().isStream ? "LIVE" : TimeUtil.formatTime(track.getDuration()));
                result.put("isStream", track.getInfo().isStream);

                RequestMetadata rm = handler.getRequestMetadata();
                if (rm != null && rm.user != null)
                {
                    result.put("requestedBy", rm.user.username);
                }
            }
        }
        return result;
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
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            t.put("requestedBy", rm != null && rm.user != null ? rm.user.username : "Web");
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
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            t.put("requestedBy", rm != null && rm.user != null ? rm.user.username : "");
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

        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        Settings settings = bot.getSettingsManager().getSettings(guild.getIdLong());

        Map<String, Object> result = new HashMap<>();
        result.put("playing", false);
        result.put("paused", false);
        result.put("volume", settings != null ? settings.getVolume() : 100);
        result.put("repeatMode", settings != null ? settings.getRepeatMode().name() : RepeatMode.OFF.name());
        result.put("shuffleMode", settings != null && settings.getShuffleMode());

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
                result.put("position", track.getPosition());
                result.put("duration", track.getDuration());
                result.put("positionFormatted", TimeUtil.formatTime(track.getPosition()));
                result.put("durationFormatted", TimeUtil.formatTime(track.getDuration()));
                result.put("isStream", track.getInfo().isStream);
                if (track.getInfo().isStream)
                {
                    result.put("durationFormatted", "LIVE");
                }

                RequestMetadata rm = handler.getRequestMetadata();
                if (rm != null && rm.user != null)
                {
                    result.put("requestedBy", rm.user.username);
                }
            }
        }

        ctx.json(result);
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
            t.put("identifier", at.getIdentifier());
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            t.put("requestedBy", rm != null && rm.user != null ? rm.user.username : "Web");
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
        long now = System.currentTimeMillis();
        String session = ctx.cookie("JMBSESSION");
        if (session != null)
        {
            AuthSession s = authSessions.get(session);
            if (s != null && now - s.lastUsedMs <= SESSION_TTL_MS)
            {
                s.lastUsedMs = now;
                return s;
            }
            authSessions.remove(session);
        }
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer "))
        {
            String token = authHeader.substring(7);
            AuthSession s = authSessions.get(token);
            if (s != null && now - s.lastUsedMs <= SESSION_TTL_MS)
            {
                s.lastUsedMs = now;
                return s;
            }
            authSessions.remove(token);
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
        boolean secureCookie = "https".equalsIgnoreCase(ctx.scheme());
        ctx.cookie(new Cookie(
                "JMBSESSION",
                sessionId,
                "/",
                86400,
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

    private void handleUpload(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        String guildId = guild.getId();
        UploadedFile upload = ctx.uploadedFile("file");
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

    private PlaylistLoader playlistLoader()
    {
        return bot.getPlaylistLoader();
    }

    private void handleListPlaylists(Context ctx)
    {
        String folder = ctx.queryParam("folder");
        if (folder == null) folder = "";
        List<String> names = playlistLoader().getPlaylistNamesInFolder(folder);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : names)
        {
            PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(name, folder);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("folder", folder);
            p.put("trackCount", pl != null ? pl.getItems().size() : 0);
            p.put("items", pl != null ? pl.getItems() : List.of());
            result.add(p);
        }
        ctx.json(result);
    }

    private void handleGetPlaylist(Context ctx)
    {
        String name = ctx.pathParam("name");
        String folder = ctx.queryParam("folder");
        if (folder == null) folder = "";
        PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(name, folder);
        if (pl == null)
        {
            ctx.status(404).result("Playlist not found");
            return;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("name", pl.getName());
        result.put("folder", folder);
        result.put("items", pl.getItems());
        result.put("shuffle", pl.getItems().contains("#shuffle"));
        ctx.json(result);
    }

    private void handleSavePlaylist(Context ctx)
    {
        String name = ctx.pathParam("name");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String folder = (String) body.get("folder");
        if (folder == null) folder = "";
        List<String> items = (List<String>) body.get("items");
        if (items == null)
        {
            ctx.status(400).result("Items are required");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items)
        {
            if (item != null && !item.trim().isEmpty())
                sb.append(item.trim()).append("\n");
        }
        try
        {
            if (!playlistLoader().getPlaylistNamesInFolder(folder).contains(name))
                playlistLoader().createPlaylistInFolder(name, folder);
            playlistLoader().writePlaylistInFolder(name, folder, sb.toString());
            log.info("Web playlist: saved '{}' in folder '{}'", name, folder);
            ctx.json(Map.of("message", "Playlist saved", "name", name, "folder", folder));
        }
        catch (IOException e)
        {
            log.error("Web playlist: failed to save '{}' in folder '{}'", name, folder, e);
            ctx.status(500).result("Failed to save playlist");
        }
    }

    private void handleDeletePlaylist(Context ctx)
    {
        String name = ctx.pathParam("name");
        String folder = ctx.queryParam("folder");
        if (folder == null) folder = "";
        try
        {
            playlistLoader().deletePlaylistInFolder(name, folder);
            log.info("Web playlist: deleted '{}' from folder '{}'", name, folder);
            ctx.json(Map.of("message", "Playlist deleted", "name", name));
        }
        catch (IOException e)
        {
            ctx.status(404).result("Playlist not found");
        }
    }

    private void handlePlayPlaylist(Context ctx)
    {
        Guild guild = resolveGuild(ctx);
        if (guild == null) return;

        String guildId = guild.getId();
        String name = ctx.pathParam("name");
        String folder = ctx.queryParam("folder");
        if (folder == null) folder = "";
        PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(name, folder);
        if (pl == null)
        {
            ctx.status(404).result("Playlist not found");
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
                    ctx.json(Map.of("message", "No voice channel available"));
                    return;
                }
                guild.getAudioManager().openAudioConnection(connectTo);
            }
        }
        catch (Exception e)
        {
            log.error("Web playlist: voice/setup failed for guild {}", guildId, e);
            ctx.status(500).json(Map.of("error", "Could not join a voice channel. Check bot permissions and try again."));
            return;
        }

        ctx.json(Map.of("message", "Loading playlist " + name + "..."));
        log.info("Web playlist: loading '{}' from folder '{}' for guild {}", name, folder, guildId);

        final RequestMetadata playlistRequester = requestMetadataForWeb(ctx);

        pl.loadTracks(manager, track ->
        {
            if (!bot.getConfig().isTooLong(track))
            {
                handler.addTrack(new QueuedTrack(track, playlistRequester));
            }
        }, () ->
        {
            broadcastUpdate(guildId, "queue");
            broadcastUpdate(guildId, "player");
            log.info("Web playlist: finished loading '{}'", name);
        });
    }

    private void handleListFolders(Context ctx)
    {
        String parent = ctx.queryParam("parent");
        if (parent == null) parent = "";
        List<String> folders = playlistLoader().getFolderNamesInFolder(parent);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String folder : folders)
        {
            String path = parent.isEmpty() ? folder : parent + "/" + folder;
            List<String> plNames = playlistLoader().getPlaylistNamesInFolder(path);
            List<String> subFolders = playlistLoader().getFolderNamesInFolder(path);
            Map<String, Object> f = new HashMap<>();
            f.put("name", folder);
            f.put("path", path);
            f.put("playlistCount", plNames.size());
            f.put("subFolderCount", subFolders.size());
            result.add(f);
        }
        ctx.json(result);
    }

    private void handleCreateFolder(Context ctx)
    {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String name = (String) body.get("name");
        String parent = (String) body.get("parent");
        if (name == null || name.trim().isEmpty())
        {
            ctx.status(400).result("Folder name is required");
            return;
        }
        if (parent == null) parent = "";
        String path = parent.isEmpty() ? name.trim() : parent + "/" + name.trim();
        try
        {
            playlistLoader().createSubfolder(path);
            log.info("Web playlist: created folder '{}'", path);
            ctx.json(Map.of("message", "Folder created", "path", path));
        }
        catch (IOException e)
        {
            log.error("Web playlist: failed to create folder '{}'", path, e);
            ctx.status(500).result("Failed to create folder");
        }
    }

    private void handleDeleteFolder(Context ctx)
    {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String path = (String) body.get("path");
        if (path == null || path.trim().isEmpty())
        {
            ctx.status(400).result("Folder path is required");
            return;
        }
        try
        {
            playlistLoader().deleteSubfolder(path.trim());
            log.info("Web playlist: deleted folder '{}'", path);
            ctx.json(Map.of("message", "Folder deleted", "path", path));
        }
        catch (IOException e)
        {
            ctx.status(404).result("Folder not found");
        }
    }

    private void handleMovePlaylist(Context ctx)
    {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String name = (String) body.get("name");
        String fromFolder = (String) body.get("fromFolder");
        String toFolder = (String) body.get("toFolder");
        if (name == null || name.trim().isEmpty())
        {
            ctx.status(400).result("Playlist name is required");
            return;
        }
        if (fromFolder == null) fromFolder = "";
        if (toFolder == null) toFolder = "";
        try
        {
            playlistLoader().movePlaylist(name.trim(), fromFolder, toFolder);
            log.info("Web playlist: moved '{}' from '{}' to '{}'", name, fromFolder, toFolder);
            ctx.json(Map.of("message", "Playlist moved", "name", name, "folder", toFolder));
        }
        catch (IOException e)
        {
            ctx.status(500).result("Failed to move playlist");
        }
    }

    private void handlePlaylistsTree(Context ctx)
    {
        Map<String, Object> tree = buildFolderTree("");
        ctx.json(tree);
    }

    private Map<String, Object> buildFolderTree(String folder)
    {
        Map<String, Object> node = new HashMap<>();
        node.put("folder", folder);

        List<String> plNames = playlistLoader().getPlaylistNamesInFolder(folder);
        List<Map<String, Object>> playlists = new ArrayList<>();
        for (String name : plNames)
        {
            PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(name, folder);
            Map<String, Object> p = new HashMap<>();
            p.put("name", name);
            p.put("folder", folder);
            p.put("trackCount", pl != null ? pl.getItems().size() : 0);
            p.put("items", pl != null ? pl.getItems() : List.of());
            playlists.add(p);
        }
        node.put("playlists", playlists);
        node.put("playlistCount", playlists.size());

        List<String> subFolders = playlistLoader().getFolderNamesInFolder(folder);
        List<Map<String, Object>> children = new ArrayList<>();
        for (String sub : subFolders)
        {
            String subPath = folder.isEmpty() ? sub : folder + "/" + sub;
            Map<String, Object> child = buildFolderTree(subPath);
            child.put("name", sub);
            children.add(child);
        }
        node.put("folders", children);
        node.put("subFolderCount", children.size());

        return node;
    }

    private void handleGetPlaylistDetail(Context ctx)
    {
        String name = ctx.pathParam("name");
        String folder = ctx.queryParam("folder");
        if (folder == null) folder = "";
        PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(name, folder);
        if (pl == null)
        {
            ctx.status(404).result("Playlist not found");
            return;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("name", pl.getName());
        result.put("folder", folder);
        result.put("items", pl.getItems());
        result.put("shuffle", pl.getItems().contains("#shuffle"));
        result.put("trackCount", pl.getItems().size());
        ctx.json(result);
    }

    private void handleRenamePlaylist(Context ctx)
    {
        String oldName = ctx.pathParam("name");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String newName = (String) body.get("newName");
        String folder = (String) body.get("folder");
        if (newName == null || newName.trim().isEmpty())
        {
            ctx.status(400).result("New name is required");
            return;
        }
        if (folder == null) folder = "";
        try
        {
            String content = "";
            PlaylistLoader.Playlist pl = playlistLoader().getPlaylistInFolder(oldName, folder);
            if (pl == null)
            {
                ctx.status(404).result("Playlist not found");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String item : pl.getItems())
            {
                sb.append(item).append("\n");
            }
            content = sb.toString();
            playlistLoader().createPlaylistInFolder(newName.trim(), folder);
            playlistLoader().writePlaylistInFolder(newName.trim(), folder, content);
            playlistLoader().deletePlaylistInFolder(oldName, folder);
            log.info("Web playlist: renamed '{}' to '{}' in folder '{}'", oldName, newName, folder);
            ctx.json(Map.of("message", "Playlist renamed", "name", newName.trim(), "folder", folder));
        }
        catch (IOException e)
        {
            log.error("Web playlist: failed to rename '{}' in folder '{}'", oldName, folder, e);
            ctx.status(500).result("Failed to rename playlist");
        }
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

    private void authMiddleware(Context ctx)
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

        ctx.status(401).json(Map.of("error", "Authentication required"));
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
            t.put("duration", at.getDuration());
            t.put("durationFormatted", TimeUtil.formatTime(at.getDuration()));
            RequestMetadata rm = qt.getRequestMetadata();
            t.put("requestedBy", rm != null && rm.user != null ? rm.user.username : "");
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
