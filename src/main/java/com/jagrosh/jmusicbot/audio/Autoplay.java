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

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Autoplay ("radio"): when the queue runs out, queue one related song from YouTube's mix for the
 * song that just ended.
 */
public final class Autoplay
{
    private static final Logger LOG = LoggerFactory.getLogger(Autoplay.class);
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    /** Long mixes and streams don't make good "next song" picks. */
    private static final long MAX_PICK_MS = 15 * 60 * 1000L;

    /** Shown as the requester of songs autoplay adds. */
    public static final RequestMetadata METADATA = RequestMetadata.fromDiscordWebUser(0L, "Autoplay", "0", null, null);

    private Autoplay() {}

    public static boolean isYoutubeId(String id)
    {
        return id != null && YOUTUBE_ID.matcher(id).matches();
    }

    /**
     * Autoplay continues after a song that finished or was skipped. Never after a failed load:
     * when YouTube playback is broken, each pick would fail and chain into the next one forever.
     */
    public static boolean continuesAfter(AudioTrackEndReason reason)
    {
        return reason == AudioTrackEndReason.FINISHED || reason == AudioTrackEndReason.STOPPED;
    }

    /** True until {@code stops} changes: a stop while the related song loads cancels the pick. */
    public static BooleanSupplier stillWanted(AtomicInteger stops)
    {
        int seen = stops.get();
        return () -> stops.get() == seen;
    }

    /** YouTube's endless "mix" playlist that starts from this video. */
    public static String mixUrl(String videoId)
    {
        return "https://www.youtube.com/watch?v=" + videoId + "&list=RD" + videoId;
    }

    /** Index of the first candidate that isn't the song that just ended or a recent one; -1 if none. */
    public static int pick(List<String> candidates, Set<String> recent, String current)
    {
        for (int i = 0; i < candidates.size(); i++)
        {
            String id = candidates.get(i);
            if (id != null && !id.equals(current) && !recent.contains(id))
                return i;
        }
        return -1;
    }

    /**
     * Loads a related song and hands it to {@code play}; calls {@code nothing} when there is none.
     * Exactly one of the two runs, on lavaplayer's loader thread.
     */
    public static void queueRelated(PlayerManager manager, Guild guild, AudioTrack ended, Set<String> recent,
                                    Consumer<AudioTrack> play, Runnable nothing)
    {
        String id = ended.getIdentifier();
        String uri = ended.getInfo().uri;
        if (isYoutubeId(id) && uri != null && uri.contains("youtu"))
        {
            loadMix(manager, guild, id, recent, play, nothing);
            return;
        }
        // Not a YouTube song (SoundCloud, a file…): find it on YouTube first, then use its mix.
        String query = "ytsearch:" + ended.getInfo().author + " " + ended.getInfo().title;
        manager.loadItemOrdered(guild, query, new Loader(nothing)
        {
            @Override
            void found(List<AudioTrack> tracks)
            {
                String match = tracks.isEmpty() ? null : tracks.get(0).getIdentifier();
                if (isYoutubeId(match))
                    loadMix(manager, guild, match, recent, play, nothing);
                else
                    nothing.run();
            }
        });
    }

    private static void loadMix(PlayerManager manager, Guild guild, String videoId, Set<String> recent,
                                Consumer<AudioTrack> play, Runnable nothing)
    {
        manager.loadItemOrdered(guild, mixUrl(videoId), new Loader(nothing)
        {
            @Override
            void found(List<AudioTrack> tracks)
            {
                List<String> ids = new ArrayList<>();
                for (AudioTrack t : tracks)
                    ids.add(t.getInfo().isStream || t.getDuration() > MAX_PICK_MS ? null : t.getIdentifier());
                int index = pick(ids, recent, videoId);
                if (index < 0)
                {
                    nothing.run();
                    return;
                }
                AudioTrack next = tracks.get(index);
                LOG.info("Autoplay: queued '{}' for guild {}", next.getInfo().title, guild.getId());
                play.accept(next);
            }
        });
    }

    /** One result path for lavaplayer's four callbacks; anything but tracks means "nothing". */
    private abstract static class Loader implements AudioLoadResultHandler
    {
        private final Runnable nothing;

        Loader(Runnable nothing)
        {
            this.nothing = nothing;
        }

        abstract void found(List<AudioTrack> tracks);

        private void safely(List<AudioTrack> tracks)
        {
            try
            {
                found(tracks);
            }
            catch (Exception e)
            {
                LOG.warn("Autoplay failed: {}", e.toString());
                nothing.run();
            }
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            // A single track back for a mix URL means mixes aren't available: nothing new to play.
            safely(List.of(track));
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            safely(playlist.getTracks());
        }

        @Override
        public void noMatches()
        {
            nothing.run();
        }

        @Override
        public void loadFailed(FriendlyException exception)
        {
            LOG.warn("Autoplay could not load related songs: {}", exception.getMessage());
            nothing.run();
        }
    }
}
