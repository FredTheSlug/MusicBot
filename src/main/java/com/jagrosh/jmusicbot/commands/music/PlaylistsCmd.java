/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
 *
 * Modified 2026 by Fred (https://github.com/FredTheSlug/MusicBot).
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
package com.jagrosh.jmusicbot.commands.music;

import java.util.ArrayList;
import java.util.List;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistStore;
import com.jagrosh.jmusicbot.playlist.PlaylistStore.Entry;
import com.jagrosh.jmusicbot.utils.FormatUtil;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlaylistsCmd extends MusicCommand 
{
    /** The most characters Discord takes in one message. */
    public static final int MESSAGE_LIMIT = 2000;

    public PlaylistsCmd(Bot bot)
    {
        super(bot);
        this.name = "playlists";
        this.help = "shows the available playlists";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
        this.beListening = false;
        this.beListening = false;
    }
    
    @Override
    public void doCommand(CommandEvent event) 
    {
        event.reply(availableMessage(bot, event,
                "\nType `" + event.getClient().getTextualPrefix() + "play playlist <name>` to play a playlist"));
    }

    /** The reply listing every playlist as {@code Folder/Name} (or why there is none to list), ending with {@code footer}. */
    public static String availableMessage(Bot bot, CommandEvent event, String footer)
    {
        List<Entry> list;
        try
        {
            list = bot.getPlaylistStore().tree();
        }
        catch(RuntimeException e)
        {
            return event.getClient().getError() + " Failed to load available playlists!";
        }
        if(list.isEmpty())
            return event.getClient().getWarning() + " There are no playlists in the Playlists folder!";
        List<String> paths = new ArrayList<>();
        for(Entry entry : list)
            paths.add(entry.getPath());
        return FormatUtil.filter(listMessage(event.getClient().getSuccess() + " Available playlists:\n", paths, footer, MESSAGE_LIMIT));
    }

    /**
     * {@code header}, the playlists in backticks, {@code footer}: one message of at most {@code limit}
     * characters. When they don't all fit, the names that do are shown whole, in order, followed by how many
     * are left out.
     */
    public static String listMessage(String header, List<String> paths, String footer, int limit)
    {
        StringBuilder all = new StringBuilder();
        for(String path : paths)
            all.append(piece(path));
        if(header.length() + all.length() + footer.length() <= limit)
            return header + all + footer;
        int reserved = more(paths.size()).length(); // room for "(+N more)", with N as large as it can be
        StringBuilder shown = new StringBuilder();
        int count = 0;
        for(String path : paths)
        {
            String piece = piece(path);
            if(header.length() + shown.length() + piece.length() + reserved + footer.length() > limit)
                break;
            shown.append(piece);
            count++;
        }
        return header + shown + more(paths.size() - count) + footer;
    }

    private static String piece(String path)
    {
        return "`" + path + "` ";
    }

    private static String more(int count)
    {
        return "(+" + count + " more)";
    }

    /**
     * Why typed text found no single playlist, for a reply (no emoji): a bare name that several playlists
     * have must be said with its folder; anything else is just not there.
     */
    public static String missingMessage(PlaylistStore store, String typed)
    {
        if(store.findAll(typed).size() > 1)
            return "More than one playlist is called `" + typed + "` — use `Folder/Name`";
        return "I could not find playlist `" + typed + "`";
    }
}
