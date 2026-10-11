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
package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.TrackMarkerHandler.MarkerState;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SponsorBlock for music: skips the sections of YouTube music videos that the SponsorBlock
 * community marked as "non-music" (talking intros, outros, skits).
 */
public final class SponsorBlock
{
    private static final Logger LOG = LoggerFactory.getLogger(SponsorBlock.class);
    private static final String CATEGORY = "music_offtopic";
    private static final String API = "https://sponsor.ajay.app/api/skipSegments?videoID=";
    private static final long MIN_SEGMENT_MS = 1000;
    private static final int MAX_SEGMENTS = 20;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private SponsorBlock() {}

    /** A section to skip, in milliseconds from the start of the song. */
    public static final class Segment
    {
        public final long startMs;
        public final long endMs;

        public Segment(long startMs, long endMs)
        {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Segment && ((Segment) o).startMs == startMs && ((Segment) o).endMs == endMs;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(startMs, endMs);
        }

        @Override
        public String toString()
        {
            return startMs + "-" + endMs;
        }
    }

    /** What to do at a position: seek now (inside a section), or mark where the next one starts. */
    public static final class Step
    {
        public final Long seekTo;
        public final Long markAt;

        public Step(Long seekTo, Long markAt)
        {
            this.seekTo = seekTo;
            this.markAt = markAt;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Step && Objects.equals(((Step) o).seekTo, seekTo) && Objects.equals(((Step) o).markAt, markAt);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(seekTo, markAt);
        }

        @Override
        public String toString()
        {
            return "seekTo=" + seekTo + " markAt=" + markAt;
        }
    }

    /**
     * The API's answer → sorted, merged "non-music" skip sections (shorter than a second dropped, at
     * most 20). Anything unexpected gives an empty list.
     */
    public static List<Segment> parseSegments(String json)
    {
        List<Segment> raw = new ArrayList<>();
        try
        {
            JSONArray items = new JSONArray(json);
            for (int i = 0; i < items.length(); i++)
            {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !CATEGORY.equals(item.optString("category")))
                    continue;
                if (!"skip".equals(item.optString("actionType", "skip")))
                    continue;
                JSONArray span = item.optJSONArray("segment");
                if (span == null || span.length() != 2)
                    continue;
                long start = Math.max(0, Math.round(span.optDouble(0, -1) * 1000));
                long end = Math.round(span.optDouble(1, -1) * 1000);
                if (end > start)
                    raw.add(new Segment(start, end));
            }
        }
        catch (Exception e)
        {
            return Collections.emptyList();
        }
        raw.sort(Comparator.comparingLong(s -> s.startMs));
        List<Segment> merged = new ArrayList<>();
        for (Segment s : raw)
        {
            Segment last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && s.startMs <= last.endMs)
                merged.set(merged.size() - 1, new Segment(last.startMs, Math.max(last.endMs, s.endMs)));
            else
                merged.add(s);
        }
        List<Segment> kept = new ArrayList<>();
        for (Segment s : merged)
            if (s.endMs - s.startMs >= MIN_SEGMENT_MS && kept.size() < MAX_SEGMENTS)
                kept.add(s);
        return kept;
    }

    public static Step nextStep(List<Segment> segments, long positionMs)
    {
        for (Segment s : segments)
        {
            if (positionMs >= s.startMs && positionMs < s.endMs)
                return new Step(s.endMs, null);
            if (positionMs < s.startMs)
                return new Step(null, s.startMs);
        }
        return new Step(null, null);
    }

    /**
     * Fetches the song's sections in the background and installs the skips on this track only.
     * {@code onSkip} runs after each skip (to tell the panel the position jumped).
     */
    public static void apply(AudioTrack track, Runnable onSkip)
    {
        String id = track.getIdentifier();
        String uri = track.getInfo().uri;
        if (!Autoplay.isYoutubeId(id) || uri == null || !uri.contains("youtu"))
            return;
        String categories = URLEncoder.encode("[\"" + CATEGORY + "\"]", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + id + "&categories=" + categories))
                .timeout(TIMEOUT)
                .header("User-Agent", "JMusicBot")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response ->
                {
                    // 404 means "no segments for this video".
                    if (response.statusCode() != 200)
                        return;
                    List<Segment> segments = parseSegments(response.body());
                    if (!segments.isEmpty())
                        install(track, segments, onSkip);
                })
                .exceptionally(e ->
                {
                    LOG.debug("SponsorBlock lookup failed for {}: {}", id, e.toString());
                    return null;
                });
    }

    private static boolean stillPlaying(AudioTrack track)
    {
        AudioTrackState state = track.getState();
        return state == AudioTrackState.PLAYING || state == AudioTrackState.SEEKING || state == AudioTrackState.LOADING;
    }

    /** Skips the section the song is in (if any) and marks the start of the next one. */
    static void install(AudioTrack track, List<Segment> segments, Runnable onSkip)
    {
        try
        {
            if (!stillPlaying(track))
                return;
            long position = track.getPosition();
            Step step = nextStep(segments, position);
            if (step.seekTo != null)
            {
                LOG.info("SponsorBlock: skipping {}-{} ms of '{}'", position, step.seekTo, track.getInfo().title);
                track.setPosition(step.seekTo);
                onSkip.run();
                step = nextStep(segments, step.seekTo);
            }
            if (step.markAt != null)
                track.setMarker(new TrackMarker(step.markAt, state ->
                {
                    // Reached (or set after its time): skip now. Bypassed: the user seeked; re-plan.
                    if (state == MarkerState.REACHED || state == MarkerState.LATE || state == MarkerState.BYPASSED)
                        install(track, segments, onSkip);
                }));
        }
        catch (Exception e)
        {
            LOG.warn("SponsorBlock skip failed: {}", e.toString());
        }
    }
}
