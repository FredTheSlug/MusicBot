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
package com.jagrosh.jmusicbot.settings;

import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-guild most-played track counters, persisted to {@code playcounts.json}.
 */
public class PlayCountStore
{
    private static final Logger LOG = LoggerFactory.getLogger("PlayCounts");
    private static final String FILE_NAME = "playcounts.json";
    public static final int TOP_N = 20;

    private final Map<Long, Map<String, Entry>> byGuild = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public PlayCountStore()
    {
        load();
    }

    public void recordPlay(long guildId, AudioTrack track)
    {
        if (track == null)
            return;
        AudioTrackInfo info = track.getInfo();
        String key = trackKey(info);
        if (key == null || key.isBlank())
            return;

        Map<String, Entry> guildMap = byGuild.computeIfAbsent(guildId, id -> new ConcurrentHashMap<>());
        Entry entry = guildMap.compute(key, (k, prev) -> {
            if (prev == null)
                return new Entry(key, info.title, info.author, info.uri, info.length, 1);
            prev.count++;
            if (info.title != null && !info.title.isBlank())
                prev.title = info.title;
            if (info.author != null && !info.author.isBlank())
                prev.author = info.author;
            if (info.uri != null && !info.uri.isBlank())
                prev.uri = info.uri;
            if (info.length > 0)
                prev.duration = info.length;
            return prev;
        });
        writeAsync();
    }

    public List<Entry> topForGuild(long guildId, int limit)
    {
        Map<String, Entry> guildMap = byGuild.get(guildId);
        if (guildMap == null || guildMap.isEmpty())
            return List.of();
        List<Entry> list = new ArrayList<>(guildMap.values());
        list.sort(Comparator
                .comparingInt((Entry e) -> e.count).reversed()
                .thenComparing(e -> e.title == null ? "" : e.title, String.CASE_INSENSITIVE_ORDER));
        if (list.size() > limit)
            return list.subList(0, limit);
        return list;
    }

    public void clearGuild(long guildId)
    {
        byGuild.remove(guildId);
        writeAsync();
    }

    private static String trackKey(AudioTrackInfo info)
    {
        if (info == null)
            return null;
        if (info.identifier != null && !info.identifier.isBlank())
            return info.identifier;
        if (info.uri != null && !info.uri.isBlank())
            return info.uri;
        return null;
    }

    private void load()
    {
        Path path = OtherUtil.getPath(FILE_NAME);
        try
        {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(path)));
            for (String guildKey : root.keySet())
            {
                long guildId;
                try
                {
                    guildId = Long.parseLong(guildKey);
                }
                catch (NumberFormatException e)
                {
                    continue;
                }
                JSONObject tracks = root.optJSONObject(guildKey);
                if (tracks == null)
                    continue;
                Map<String, Entry> map = new ConcurrentHashMap<>();
                for (String trackKey : tracks.keySet())
                {
                    JSONObject o = tracks.optJSONObject(trackKey);
                    if (o == null)
                        continue;
                    int count = o.optInt("count", 0);
                    if (count <= 0)
                        continue;
                    map.put(trackKey, new Entry(
                            trackKey,
                            o.optString("title", "Unknown"),
                            o.optString("author", ""),
                            o.optString("uri", ""),
                            o.optLong("duration", 0L),
                            count));
                }
                if (!map.isEmpty())
                    byGuild.put(guildId, map);
            }
            LOG.info("{} loaded from {}", FILE_NAME, path.toAbsolutePath());
        }
        catch (NoSuchFileException e)
        {
            LOG.info("{} will be created when tracks are played", FILE_NAME);
        }
        catch (IOException | JSONException e)
        {
            LOG.warn("Failed to load {}: {}", FILE_NAME, e.toString());
        }
    }

    private void writeAsync()
    {
        // Persist on the calling thread is fine; plays are infrequent. Keep writes serialized.
        synchronized (writeLock)
        {
            JSONObject root = new JSONObject();
            for (Map.Entry<Long, Map<String, Entry>> ge : byGuild.entrySet())
            {
                JSONObject tracks = new JSONObject();
                for (Entry e : ge.getValue().values())
                {
                    JSONObject o = new JSONObject();
                    o.put("count", e.count);
                    o.put("title", e.title == null ? "" : e.title);
                    o.put("author", e.author == null ? "" : e.author);
                    o.put("uri", e.uri == null ? "" : e.uri);
                    o.put("duration", e.duration);
                    tracks.put(e.key, o);
                }
                root.put(Long.toString(ge.getKey()), tracks);
            }
            try
            {
                Files.write(OtherUtil.getPath(FILE_NAME), root.toString(4).getBytes());
            }
            catch (IOException ex)
            {
                LOG.warn("Failed to write {}: {}", FILE_NAME, ex.toString());
            }
        }
    }

    public static final class Entry
    {
        final String key;
        String title;
        String author;
        String uri;
        long duration;
        int count;

        Entry(String key, String title, String author, String uri, long duration, int count)
        {
            this.key = key;
            this.title = title;
            this.author = author;
            this.uri = uri;
            this.duration = duration;
            this.count = count;
        }

        public int getCount()
        {
            return count;
        }

        public long getDuration()
        {
            return duration;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new HashMap<>();
            m.put("title", title == null ? "Unknown" : title);
            m.put("author", author == null ? "" : author);
            m.put("uri", uri == null ? "" : uri);
            m.put("identifier", key);
            m.put("duration", duration);
            m.put("playCount", count);
            return m;
        }
    }
}
