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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class SpotifyPlayback
{
    private SpotifyPlayback() {}

    public static void loadDiscordMultiple(Bot bot, Guild guild, Message message, CommandEvent event,
            SpotifyResolveResult result, boolean playNext)
    {
        List<String> queries = result.searchQueries();
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger added = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        Runnable loadNext = new Runnable()
        {
            @Override
            public void run()
            {
                int i = index.getAndIncrement();
                if (i >= queries.size())
                {
                    finishMulti(message, event, result, added.get(), skipped.get());
                    return;
                }
                boolean first = i == 0;
                bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + queries.get(i),
                        new MultiHandler(bot, guild, message, event, playNext && first, added, skipped, this));
            }
        };
        loadNext.run();
    }

    private static void finishMulti(Message message, CommandEvent event, SpotifyResolveResult result, int added, int skipped)
    {
        String name = result.getCollectionTitle() != null ? "**" + result.getCollectionTitle() + "**" : "Spotify collection";
        String msg;
        if (added == 0)
            msg = event.getClient().getWarning() + " Could not load any tracks from " + name + ".";
        else
        {
            msg = event.getClient().getSuccess() + " Added **" + added + "** track" + (added == 1 ? "" : "s")
                    + " from " + name + ".";
            if (skipped > 0)
                msg += "\n" + event.getClient().getWarning() + " Skipped **" + skipped + "** (too long or not found).";
        }
        message.editMessage(FormatUtil.filter(msg)).queue();
    }

    private static final class MultiHandler implements AudioLoadResultHandler
    {
        private final Bot bot;
        private final Guild guild;
        private final Message message;
        private final CommandEvent event;
        private final boolean playNext;
        private final AtomicInteger added;
        private final AtomicInteger skipped;
        private final Runnable loadNext;

        MultiHandler(Bot bot, Guild guild, Message message, CommandEvent event, boolean playNext,
                AtomicInteger added, AtomicInteger skipped, Runnable loadNext)
        {
            this.bot = bot;
            this.guild = guild;
            this.message = message;
            this.event = event;
            this.playNext = playNext;
            this.added = added;
            this.skipped = skipped;
            this.loadNext = loadNext;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            addTrack(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            AudioTrack track = playlist.getSelectedTrack() != null
                    ? playlist.getSelectedTrack()
                    : playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0);
            if (track != null)
                addTrack(track);
            else
            {
                skipped.incrementAndGet();
                loadNext.run();
            }
        }

        @Override
        public void noMatches()
        {
            skipped.incrementAndGet();
            loadNext.run();
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            skipped.incrementAndGet();
            loadNext.run();
        }

        private void addTrack(AudioTrack track)
        {
            if (bot.getConfig().isTooLong(track))
            {
                skipped.incrementAndGet();
                loadNext.run();
                return;
            }
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            QueuedTrack qt = new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event));
            if (playNext)
                handler.addTrackToFront(qt);
            else
                handler.addTrack(qt);
            added.incrementAndGet();
            loadNext.run();
        }
    }
}
