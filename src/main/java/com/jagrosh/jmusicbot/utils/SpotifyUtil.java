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
package com.jagrosh.jmusicbot.utils;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.SpotifyPlayback;
import com.jagrosh.jmusicbot.audio.SpotifyResolveResult;
import com.jagrosh.jmusicbot.audio.SpotifyResolver;
import net.dv8tion.jda.api.entities.Message;

public final class SpotifyUtil
{
    @FunctionalInterface
    public interface SingleTrackLoad
    {
        void load(Message message, CommandEvent event, String ytSearchQuery);
    }

    private SpotifyUtil() {}

    public static boolean playIfSpotifyUrl(Bot bot, CommandEvent event, String loadQuery, String loadingEmoji,
            boolean playNext, SingleTrackLoad singleTrackLoad)
    {
        if(!SpotifyResolver.looksLikeSpotifyUrl(loadQuery))
            return false;
        if(bot.getSpotifyResolver() == null)
        {
            event.replyWarning("Spotify isn't set up in config.txt.");
            return true;
        }
        SpotifyResolver spotify = bot.getSpotifyResolver();
        event.reply(loadingEmoji+" Loading... `[Spotify]`", m -> spotify.resolve(loadQuery).whenComplete((result, ex) -> {
            if(ex != null)
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" "+ex.getMessage())).queue();
                return;
            }
            if(!result.isSuccess())
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" "+result.getError())).queue();
                return;
            }
            if(result.searchQueries().size() == 1)
                singleTrackLoad.load(m, event, "ytsearch:"+result.searchQueries().get(0));
            else
            {
                String label = result.getCollectionTitle() != null ? result.getCollectionTitle() : "Spotify";
                m.editMessage(loadingEmoji+" Loading **"+label+"** ("+result.searchQueries().size()+" tracks)...").queue(edited ->
                        SpotifyPlayback.loadDiscordMultiple(bot, event.getGuild(), edited, event, result, playNext));
            }
        }));
        return true;
    }
}
