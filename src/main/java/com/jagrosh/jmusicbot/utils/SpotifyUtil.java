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
