/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.owner;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.OwnerCommand;
import com.jagrosh.jmusicbot.commands.music.PlaylistsCmd;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlaylistCmd extends OwnerCommand 
{
    private final Bot bot;
    public PlaylistCmd(Bot bot)
    {
        this.bot = bot;
        this.guildOnly = false;
        this.name = "playlist";
        this.arguments = "<append|delete|make|setdefault>";
        this.help = "playlist management";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.children = new OwnerCommand[]{
            new ListCmd(),
            new AppendlistCmd(),
            new DeletelistCmd(),
            new MakelistCmd(),
            new DefaultlistCmd(bot)
        };
    }

    @Override
    public void execute(CommandEvent event) 
    {
        StringBuilder builder = new StringBuilder(event.getClient().getWarning()+" Playlist Management Commands:\n");
        for(Command cmd: this.children)
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" ").append(cmd.getName())
                    .append(" ").append(cmd.getArguments()==null ? "" : cmd.getArguments()).append("` - ").append(cmd.getHelp());
        event.reply(builder.toString());
    }

    /** Playlists are edited in the web panel: the commands that used to do it say so. */
    private void sendToPanel(CommandEvent event)
    {
        event.reply("Edit playlists in the panel: " + bot.getConfig().getPanelUrl());
    }
    
    public class MakelistCmd extends OwnerCommand 
    {
        public MakelistCmd()
        {
            this.name = "make";
            this.aliases = new String[]{"create"};
            this.help = "makes a new playlist (in the panel)";
            this.arguments = "<name>";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event) 
        {
            sendToPanel(event);
        }
    }
    
    public class DeletelistCmd extends OwnerCommand 
    {
        public DeletelistCmd()
        {
            this.name = "delete";
            this.aliases = new String[]{"remove"};
            this.help = "deletes an existing playlist (in the panel)";
            this.arguments = "<name>";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event) 
        {
            sendToPanel(event);
        }
    }
    
    public class AppendlistCmd extends OwnerCommand 
    {
        public AppendlistCmd()
        {
            this.name = "append";
            this.aliases = new String[]{"add"};
            this.help = "appends songs to an existing playlist (in the panel)";
            this.arguments = "<name> <URL> | <URL> | ...";
            this.guildOnly = false;
        }

        @Override
        protected void execute(CommandEvent event) 
        {
            sendToPanel(event);
        }
    }
    
    public class DefaultlistCmd extends AutoplaylistCmd 
    {
        public DefaultlistCmd(Bot bot)
        {
            super(bot);
            this.name = "setdefault";
            this.aliases = new String[]{"default"};
            this.arguments = "<playlistname|NONE>";
            this.guildOnly = true;
        }
    }
    
    public class ListCmd extends OwnerCommand 
    {
        public ListCmd()
        {
            this.name = "all";
            this.aliases = new String[]{"available","list"};
            this.help = "lists all available playlists";
            this.guildOnly = true;
        }

        @Override
        protected void execute(CommandEvent event) 
        {
            event.reply(PlaylistsCmd.availableMessage(bot, event, ""));
        }
    }
}
