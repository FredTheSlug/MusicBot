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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jagrosh.jlyrics.LyricsClient;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricsFetch
{
    private static final Logger log = LoggerFactory.getLogger(LyricsFetch.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.,](\\d{1,3}))?]\\s*(.*)");
    private static final Pattern LRC_OFFSET = Pattern.compile("(?i)\\[offset:\\s*([+-]?\\d+)]");
    private static final String UA = "JMusicBot-Web/1.0 (https://github.com/jagrosh/MusicBot)";
    private static final LyricsClient JLYRICS = new LyricsClient();

    private static final int[] DURATION_TRIES = {0, -1, 1, -2, 2, -3, 3, -5, 5, -8, 8, -12, 12, -15, 15};
    private static final String[] ALBUM_GET_TRIES = {"-", "", "Unknown", "null", "Single"};

    private LyricsFetch() {}

    public static Map<String, Object> fetchForTrack(AudioTrack track)
    {
        String rawTitle = track.getInfo().title == null ? "" : track.getInfo().title;
        String rawAuthor = track.getInfo().author == null ? "" : track.getInfo().author;
        return fetchForTitleArtistDuration(rawTitle, rawAuthor, track.getDuration());
    }

    public static Map<String, Object> fetchForTitleArtistDuration(String rawTitle, String rawAuthor, long rawDurationMs)
    {
        String t = rawTitle == null ? "" : rawTitle;
        String a = rawAuthor == null ? "" : rawAuthor;
        long durationMs = rawDurationMs > 0 && rawDurationMs < 18_000_000L ? rawDurationMs : 180_000L;
        int durationSec = (int) Math.max(1L, Math.min(durationMs / 1000, 36_000L));

        if (t.isEmpty())
        {
            return Map.of("synced", false, "lines", List.of(), "title", "", "artist", a, "error", "No track title");
        }

        Meta meta = normalizeTrackMeta(t, a);

        Map<String, Object> fromSearch = tryLrclibSearch(meta, durationSec, durationMs);
        if (fromSearch != null)
        {
            return fromSearch;
        }

        for (String album : ALBUM_GET_TRIES)
        {
            for (int delta : DURATION_TRIES)
            {
                int trySec = (int) Math.max(1L, durationSec + delta);
                trySec = Math.min(trySec, 36_000);
                try
                {
                    String body = httpGet(buildGetUri(meta.cleanTitle, meta.cleanArtist, trySec, album));
                    if (body != null)
                    {
                        Map<String, Object> fromApi = parseLrclibBody(body, meta.cleanTitle, meta.cleanArtist, durationMs);
                        if (fromApi != null && hasLyricLines(fromApi))
                        {
                            return fromApi;
                        }
                        if (fromApi != null && Boolean.TRUE.equals(fromApi.get("instrumental")))
                        {
                            return fromApi;
                        }
                    }
                }
                catch (Exception e)
                {
                    log.debug("LRCLIB /api/get (album {}, delta {}): {}", album, delta, e.toString());
                }
            }
        }

        return fallbackJLyrics(meta, t, a, durationMs);
    }

    public static CompletableFuture<Map<String, Object>> fetchForTitleArtistDurationAsync(String title, String author, long durationMs)
    {
        return CompletableFuture.supplyAsync(() -> fetchForTitleArtistDuration(title, author, durationMs));
    }

    private static boolean hasLyricLines(Map<String, Object> fromApi)
    {
        Object lines = fromApi.get("lines");
        return lines instanceof List && !((List<?>) lines).isEmpty();
    }

    public static CompletableFuture<Map<String, Object>> fetchForTrackAsync(AudioTrack track)
    {
        return CompletableFuture.supplyAsync(() -> fetchForTrack(track));
    }

    private static final class Meta
    {
        final String cleanTitle;
        final String cleanArtist;

        Meta(String cleanTitle, String cleanArtist)
        {
            this.cleanTitle = cleanTitle;
            this.cleanArtist = cleanArtist;
        }
    }

    static Meta normalizeTrackMeta(String title, String author)
    {
        String t = normalizeUnicodeHyphens(scrubVideoTitle(title));
        String a = author == null ? "" : normalizeUnicodeHyphens(scrubVideoTitle(author)).trim();
        if (a.toLowerCase(java.util.Locale.ROOT).endsWith(" - topic"))
        {
            a = a.substring(0, a.length() - " - topic".length()).trim();
        }

        boolean authorWeak = a.isEmpty()
                || a.equalsIgnoreCase("Unknown Artist")
                || (a.matches("(?i).*(lyrics|vevo|records).*") && a.length() > 50);

        int sep = t.indexOf(" - ");
        if (sep > 0 && sep < t.length() - 3)
        {
            String left = t.substring(0, sep).trim();
            String right = t.substring(sep + 3).trim();
            if (!right.isEmpty() && left.length() <= 64)
            {
                if (authorWeak || left.length() < 48)
                {
                    a = scrubVideoTitle(left);
                    t = scrubVideoTitle(right);
                }
            }
        }

        if (a.isEmpty())
        {
            a = "Unknown Artist";
        }
        return new Meta(t, a);
    }

    static String normalizeUnicodeHyphens(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s == null ? "" : s;
        }
        String t = s;
        t = t.replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-');
        t = t.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"');
        return t;
    }

    static String scrubVideoTitle(String s)
    {
        if (s == null || s.isEmpty())
        {
            return "";
        }
        String t = s;
        t = t.replaceAll("(?i)\\s*\\(official[^)]*\\)", "");
        t = t.replaceAll("(?i)\\s*\\[official[^]]*]", "");
        t = t.replaceAll("(?i)\\s*\\(lyric[^)]*\\)", "");
        t = t.replaceAll("(?i)\\s*\\[lyric[^]]*]", "");
        t = t.replaceAll("(?i)\\s*\\(audio[^)]*\\)", "");
        t = t.replaceAll("(?i)\\s*\\[audio[^]]*]", "");
        t = t.replaceAll("(?i)\\s*\\(visualizer[^)]*\\)", "");
        t = t.replaceAll("\\s*\\|[^|]*$", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static Map<String, Object> tryLrclibSearch(Meta meta, int durationSec, long durationMs)
    {
        List<String> searchUris = new ArrayList<>();
        String encTitle = URLEncoder.encode(meta.cleanTitle, StandardCharsets.UTF_8);
        String encArtist = URLEncoder.encode(meta.cleanArtist, StandardCharsets.UTF_8);
        String combined = URLEncoder.encode((meta.cleanArtist + " " + meta.cleanTitle).trim(), StandardCharsets.UTF_8);

        searchUris.add("https://lrclib.net/api/search?track_name=" + encTitle + "&artist_name=" + encArtist);
        searchUris.add("https://lrclib.net/api/search?q=" + combined);
        searchUris.add("https://lrclib.net/api/search?q=" + encTitle);
        searchUris.add("https://lrclib.net/api/search?track_name=" + encTitle);

        for (String searchUrl : searchUris)
        {
            try
            {
                String body = httpGet(URI.create(searchUrl));
                if (body == null || !body.trim().startsWith("["))
                {
                    continue;
                }
                JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                Map<String, Object> parsed = pickSearchResults(arr, durationSec, durationMs);
                if (parsed != null && (hasLyricLines(parsed) || Boolean.TRUE.equals(parsed.get("instrumental"))))
                {
                    return parsed;
                }
            }
            catch (Exception e)
            {
                log.debug("LRCLIB search failed {}: {}", searchUrl, e.toString());
            }
        }
        return null;
    }

    private static Map<String, Object> pickSearchResults(JsonArray arr, int durationSec, long durationMs)
    {
        if (arr == null || arr.isEmpty())
        {
            return null;
        }
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement el : arr)
        {
            if (el.isJsonObject())
            {
                rows.add(el.getAsJsonObject());
            }
        }
        rows.sort(Comparator.comparingInt(o -> Math.abs(durationSecondsFromJson(o) - durationSec)));

        for (JsonObject o : rows)
        {
            long rowDurMs = durationMsFromJson(o, durationMs);
            Map<String, Object> parsed = parseLrclibRecord(o, "", "", rowDurMs);
            if (parsed != null && (hasLyricLines(parsed) || Boolean.TRUE.equals(parsed.get("instrumental"))))
            {
                return parsed;
            }
        }
        return null;
    }

    private static int durationSecondsFromJson(JsonObject o)
    {
        if (!o.has("duration") || o.get("duration").isJsonNull())
        {
            return 0;
        }
        try
        {
            return (int) Math.round(o.get("duration").getAsDouble());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static long durationMsFromJson(JsonObject o, long fallbackMs)
    {
        if (!o.has("duration") || o.get("duration").isJsonNull())
        {
            return fallbackMs;
        }
        try
        {
            return Math.round(o.get("duration").getAsDouble() * 1000.0);
        }
        catch (Exception e)
        {
            return fallbackMs;
        }
    }

    private static URI buildGetUri(String title, String artist, int durationSec, String albumName)
    {
        String tn = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String an = URLEncoder.encode(artist.isEmpty() ? "Unknown Artist" : artist, StandardCharsets.UTF_8);
        String al = URLEncoder.encode(albumName == null ? "" : albumName, StandardCharsets.UTF_8);
        return URI.create("https://lrclib.net/api/get?track_name=" + tn + "&artist_name=" + an + "&album_name=" + al + "&duration=" + durationSec);
    }

    private static String httpGet(URI uri) throws Exception
    {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        if (code == 200)
        {
            return resp.body();
        }
        if (code != 404)
        {
            log.debug("LRCLIB HTTP {} for {}", code, uri);
        }
        return null;
    }

    private static Map<String, Object> parseLrclibBody(String json, String fallbackTitle, String fallbackArtist, long durationMs)
    {
        JsonObject root;
        try
        {
            root = JsonParser.parseString(json).getAsJsonObject();
        }
        catch (Exception e)
        {
            return null;
        }
        return parseLrclibRecord(root, fallbackTitle, fallbackArtist, durationMs);
    }

    private static Map<String, Object> parseLrclibRecord(JsonObject root, String fallbackTitle, String fallbackArtist, long durationMs)
    {
        if (root.has("code") && !root.get("code").isJsonNull() && root.get("code").getAsInt() == 404)
        {
            return null;
        }
        if (root.has("statusCode") && !root.get("statusCode").isJsonNull() && root.get("statusCode").getAsInt() == 404)
        {
            return null;
        }
        String trTitle = root.has("trackName") ? root.get("trackName").getAsString() : fallbackTitle;
        String trArtist = root.has("artistName") ? root.get("artistName").getAsString() : fallbackArtist;
        boolean instrumental = root.has("instrumental") && root.get("instrumental").getAsBoolean();
        if (instrumental)
        {
            return Map.of("synced", true, "lines", List.of(), "title", trTitle, "artist", trArtist, "instrumental", true);
        }

        String synced = root.has("syncedLyrics") && !root.get("syncedLyrics").isJsonNull()
                ? root.get("syncedLyrics").getAsString() : null;
        if (synced != null && !synced.isBlank())
        {
            List<Map<String, Object>> lines = parseLrc(synced);
            if (!lines.isEmpty())
            {
                return Map.of("synced", true, "lines", lines, "title", trTitle, "artist", trArtist);
            }
        }

        String plain = root.has("plainLyrics") && !root.get("plainLyrics").isJsonNull()
                ? root.get("plainLyrics").getAsString() : null;
        if (plain != null && !plain.isBlank())
        {
            List<Map<String, Object>> lines = spreadPlainLines(plain, durationMs);
            if (!lines.isEmpty())
            {
                return Map.of("synced", false, "lines", lines, "title", trTitle, "artist", trArtist);
            }
        }
        return null;
    }

    static List<Map<String, Object>> parseLrc(String lrc)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        long offsetAccumMs = 0L;
        for (String raw : lrc.split("\n"))
        {
            String line = raw.trim().replace(',', '.');
            if (line.isEmpty())
            {
                continue;
            }
            Matcher off = LRC_OFFSET.matcher(line);
            if (off.matches())
            {
                offsetAccumMs += Long.parseLong(off.group(1).trim());
                continue;
            }
            Matcher m = LRC_LINE.matcher(line);
            if (m.matches())
            {
                int min = Integer.parseInt(m.group(1));
                int secInt = Integer.parseInt(m.group(2));
                String frac = m.group(3);
                double seconds = frac == null || frac.isEmpty()
                        ? secInt
                        : Double.parseDouble(secInt + "." + frac);
                long ms = min * 60_000L + (long) Math.round(seconds * 1000.0) + offsetAccumMs;
                String text = m.group(4).trim();
                if (!text.isEmpty())
                {
                    out.add(Map.of("ms", ms, "text", text));
                }
            }
        }
        out.sort((a, b) -> Long.compare((Long) a.get("ms"), (Long) b.get("ms")));
        return out;
    }

    private static List<Map<String, Object>> spreadPlainLines(String plain, long durationMs)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] parts = plain.replace("\r\n", "\n").split("\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts)
        {
            String t = p.trim();
            if (!t.isEmpty())
            {
                nonEmpty.add(t);
            }
        }
        if (nonEmpty.isEmpty())
        {
            return out;
        }
        long usable = durationMs > 5000 ? durationMs : 180_000L;
        long step = usable / nonEmpty.size();
        long at = 0;
        for (String t : nonEmpty)
        {
            out.add(Map.of("ms", at, "text", t));
            at += step;
        }
        return out;
    }

    private static Map<String, Object> fallbackJLyrics(Meta meta, String rawTitle, String rawAuthor, long durationMs)
    {
        String[] queries = {
                meta.cleanArtist + " " + meta.cleanTitle,
                meta.cleanTitle,
                rawTitle,
                rawAuthor.isEmpty() ? rawTitle : rawAuthor + " " + rawTitle
        };
        for (String q : queries)
        {
            if (q == null || q.trim().isEmpty())
            {
                continue;
            }
            try
            {
                var lyrics = JLYRICS.getLyrics(q.trim()).get(25, java.util.concurrent.TimeUnit.SECONDS);
                if (lyrics != null && lyrics.getContent() != null && !lyrics.getContent().isBlank())
                {
                    List<Map<String, Object>> lines = spreadPlainLines(lyrics.getContent(), durationMs);
                    if (!lines.isEmpty())
                    {
                        return Map.of(
                                "synced", false,
                                "lines", lines,
                                "title", lyrics.getTitle() != null ? lyrics.getTitle() : meta.cleanTitle,
                                "artist", lyrics.getAuthor() != null ? lyrics.getAuthor() : meta.cleanArtist);
                    }
                }
            }
            catch (Exception e)
            {
                log.debug("JLyrics query '{}' failed: {}", q, e.toString());
            }
        }
        return Map.of("synced", false, "lines", List.of(), "title", meta.cleanTitle, "artist", meta.cleanArtist, "error", "No lyrics found");
    }
}
