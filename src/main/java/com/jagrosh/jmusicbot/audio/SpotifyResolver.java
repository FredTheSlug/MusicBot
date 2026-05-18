package com.jagrosh.jmusicbot.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jagrosh.jmusicbot.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpotifyResolver
{
    private static final Logger log = LoggerFactory.getLogger(SpotifyResolver.class);
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1/";
    private static final String UA = "JMusicBot/1.0 (https://github.com/jagrosh/MusicBot)";

    private static final Pattern SPOTIFY_HTTP = Pattern.compile(
            "(?i)https?://(?:open\\.|play\\.)?spotify\\.com/(track|album|playlist|artist)/([a-zA-Z0-9]+)");
    private static final Pattern SPOTIFY_URI = Pattern.compile(
            "(?i)spotify:(track|album|playlist|artist):([a-zA-Z0-9]+)");

    private final HttpClient http;
    private final String clientId;
    private final String clientSecret;
    private final String market;
    private final int maxPlaylistTracks;

    private volatile String accessToken;
    private volatile long tokenExpiresAtMs;

    private SpotifyResolver(String clientId, String clientSecret, String market, int maxPlaylistTracks)
    {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.market = market == null || market.isBlank() ? "US" : market.trim().toUpperCase(Locale.ROOT);
        this.maxPlaylistTracks = Math.max(1, maxPlaylistTracks);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static SpotifyResolver create(BotConfig config)
    {
        if (config == null || !config.isSpotifyEnabled())
            return null;
        String id = config.getSpotifyClientId();
        String secret = config.getSpotifyClientSecret();
        if (id == null || id.isBlank() || secret == null || secret.isBlank())
        {
            log.warn("Spotify is enabled but clientid/clientsecret are not set; Spotify links will not resolve.");
            return null;
        }
        return new SpotifyResolver(id, secret, config.getSpotifyMarket(), config.getSpotifyMaxPlaylistTracks());
    }

    public boolean isSpotifyUrl(String input)
    {
        return parseLink(input) != null;
    }

    public CompletableFuture<SpotifyResolveResult> resolve(String input)
    {
        SpotifyLink link = parseLink(input);
        if (link == null)
            return CompletableFuture.completedFuture(SpotifyResolveResult.failure("Not a Spotify URL."));
        return ensureToken()
                .thenCompose(token -> resolveLink(link))
                .exceptionally(ex ->
                {
                    log.warn("Spotify resolve failed for {}", input, ex);
                    String msg = ex.getMessage();
                    if (msg == null || msg.isBlank())
                        msg = "Could not resolve Spotify link.";
                    return SpotifyResolveResult.failure(msg);
                });
    }

    private CompletableFuture<String> ensureToken()
    {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAtMs - 60_000L)
            return CompletableFuture.completedFuture(accessToken);
        return requestToken();
    }

    private CompletableFuture<String> requestToken()
    {
        String credentials = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", UA)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp ->
                {
                    if (resp.statusCode() != 200)
                        throw new IllegalStateException("Spotify authentication failed (HTTP " + resp.statusCode() + "). Check client ID and secret.");
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    accessToken = json.get("access_token").getAsString();
                    int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
                    tokenExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L;
                    return accessToken;
                });
    }

    private CompletableFuture<SpotifyResolveResult> resolveLink(SpotifyLink link)
    {
        switch(link.type)
        {
            case "track":
                return apiGet("tracks/" + link.id).thenApply(this::trackToQuery).thenApply(q ->
                        SpotifyResolveResult.success(Collections.singletonList(q), null));
            case "album":
                return resolveAlbum(link.id);
            case "playlist":
                return resolvePlaylist(link.id);
            case "artist":
                return apiGet("artists/" + link.id + "/top-tracks?market=" + urlEncode(market))
                        .thenApply(this::topTracksToQueries)
                        .thenApply(list -> SpotifyResolveResult.success(list, "Top tracks"));
            default:
                return CompletableFuture.completedFuture(
                        SpotifyResolveResult.failure("Unsupported Spotify link type: " + link.type));
        }
    }

    private CompletableFuture<SpotifyResolveResult> resolveAlbum(String albumId)
    {
        return apiGet("albums/" + albumId).thenCompose(albumJson ->
        {
            String title = albumJson.has("name") ? albumJson.get("name").getAsString() : "Album";
            return fetchAllPaged("albums/" + albumId + "/tracks", "items")
                    .thenApply(tracks -> buildFromTrackItems(tracks, title));
        });
    }

    private CompletableFuture<SpotifyResolveResult> resolvePlaylist(String playlistId)
    {
        return apiGet("playlists/" + playlistId).thenCompose(plJson ->
        {
            String title = plJson.has("name") ? plJson.get("name").getAsString() : "Playlist";
            return fetchAllPaged("playlists/" + playlistId + "/tracks", "items")
                    .thenApply(items -> buildFromPlaylistItems(items, title));
        });
    }

    private CompletableFuture<JsonObject> apiGet(String path)
    {
        return ensureToken().thenCompose(token ->
        {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(resp ->
            {
                if (resp.statusCode() == 401)
                    throw new IllegalStateException("Spotify authentication failed. Check your API credentials.");
                if (resp.statusCode() == 404)
                    throw new IllegalStateException("Spotify resource not found.");
                if (resp.statusCode() == 429)
                    throw new IllegalStateException("Spotify rate limit exceeded. Try again in a moment.");
                if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                    throw new IllegalStateException("Spotify API error (HTTP " + resp.statusCode() + ").");
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            });
        });
    }

    private CompletableFuture<JsonArray> fetchAllPaged(String path, String arrayKey)
    {
        List<JsonElement> all = new ArrayList<>();
        return fetchPage(path, arrayKey, all, null);
    }

    private CompletableFuture<JsonArray> fetchPage(String path, String arrayKey, List<JsonElement> all, String nextUrl)
    {
        CompletableFuture<JsonObject> pageFuture = nextUrl == null
                ? apiGet(path + (path.contains("?") ? "&" : "?") + "limit=50")
                : apiGetAbsolute(nextUrl);
        return pageFuture.thenCompose(page ->
        {
            if (page.has(arrayKey) && page.get(arrayKey).isJsonArray())
            {
                for (JsonElement el : page.getAsJsonArray(arrayKey))
                    all.add(el);
            }
            if (all.size() >= maxPlaylistTracks)
            {
                JsonArray trimmed = new JsonArray();
                for (int i = 0; i < maxPlaylistTracks; i++)
                    trimmed.add(all.get(i));
                return CompletableFuture.completedFuture(trimmed);
            }
            if (page.has("next") && !page.get("next").isJsonNull())
            {
                String next = page.get("next").getAsString();
                if (next != null && !next.isBlank())
                    return fetchPage(path, arrayKey, all, next);
            }
            JsonArray out = new JsonArray();
            all.forEach(out::add);
            return CompletableFuture.completedFuture(out);
        });
    }

    private CompletableFuture<JsonObject> apiGetAbsolute(String absoluteUrl)
    {
        return ensureToken().thenCompose(token ->
        {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(absoluteUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(resp ->
            {
                if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                    throw new IllegalStateException("Spotify API error (HTTP " + resp.statusCode() + ").");
                return JsonParser.parseString(resp.body()).getAsJsonObject();
            });
        });
    }

    private SpotifyResolveResult buildFromTrackItems(JsonArray items, String collectionTitle)
    {
        List<String> queries = new ArrayList<>();
        for (JsonElement el : items)
        {
            if (!el.isJsonObject())
                continue;
            String q = trackToQuery(el.getAsJsonObject());
            if (q != null && !q.isBlank())
                queries.add(q);
            if (queries.size() >= maxPlaylistTracks)
                break;
        }
        if (queries.isEmpty())
            return SpotifyResolveResult.failure("No playable tracks found in that Spotify album.");
        return SpotifyResolveResult.success(queries, collectionTitle);
    }

    private SpotifyResolveResult buildFromPlaylistItems(JsonArray items, String collectionTitle)
    {
        List<String> queries = new ArrayList<>();
        for (JsonElement el : items)
        {
            if (!el.isJsonObject())
                continue;
            JsonObject row = el.getAsJsonObject();
            if (!row.has("track") || row.get("track").isJsonNull())
                continue;
            JsonObject track = row.getAsJsonObject("track");
            if (track.has("is_local") && track.get("is_local").getAsBoolean())
                continue;
            String q = trackToQuery(track);
            if (q != null && !q.isBlank())
                queries.add(q);
            if (queries.size() >= maxPlaylistTracks)
                break;
        }
        if (queries.isEmpty())
            return SpotifyResolveResult.failure("No playable tracks found in that Spotify playlist.");
        return SpotifyResolveResult.success(queries, collectionTitle);
    }

    private List<String> topTracksToQueries(JsonObject json)
    {
        List<String> queries = new ArrayList<>();
        if (!json.has("tracks") || !json.get("tracks").isJsonArray())
            return queries;
        for (JsonElement el : json.getAsJsonArray("tracks"))
        {
            if (el.isJsonObject())
            {
                String q = trackToQuery(el.getAsJsonObject());
                if (q != null && !q.isBlank())
                    queries.add(q);
            }
        }
        return queries;
    }

    private String trackToQuery(JsonObject track)
    {
        if (track == null)
            return null;
        String title = track.has("name") ? track.get("name").getAsString() : "";
        String artist = primaryArtist(track);
        if (title.isBlank())
            return null;
        if (artist.isBlank())
            return title;
        return artist + " - " + title;
    }

    private static String primaryArtist(JsonObject track)
    {
        if (!track.has("artists") || !track.get("artists").isJsonArray())
            return "";
        JsonArray artists = track.getAsJsonArray("artists");
        if (artists.isEmpty())
            return "";
        List<String> names = new ArrayList<>();
        for (JsonElement a : artists)
        {
            if (a.isJsonObject() && a.getAsJsonObject().has("name"))
                names.add(a.getAsJsonObject().get("name").getAsString());
        }
        return String.join(", ", names);
    }

    public static boolean looksLikeSpotifyUrl(String input)
    {
        return parseLink(input) != null;
    }

    public static SpotifyLink parseLink(String input)
    {
        if (input == null)
            return null;
        String trimmed = input.trim();
        Matcher http = SPOTIFY_HTTP.matcher(trimmed);
        if (http.find())
            return new SpotifyLink(http.group(1).toLowerCase(Locale.ROOT), http.group(2));
        Matcher uri = SPOTIFY_URI.matcher(trimmed);
        if (uri.find())
            return new SpotifyLink(uri.group(1).toLowerCase(Locale.ROOT), uri.group(2));
        return null;
    }

    private static String urlEncode(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static final class SpotifyLink
    {
        public final String type;
        public final String id;

        SpotifyLink(String type, String id)
        {
            this.type = type;
            this.id = id;
        }
    }
}
