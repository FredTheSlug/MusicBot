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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.settings.Settings;

/** Turns SponsorBlock on or off: skips non-music sections of YouTube music videos. */
public class SponsorblockCmd extends DJCommand
{
    public SponsorblockCmd(Bot bot)
    {
        super(bot);
        this.name = "sponsorblock";
        this.help = "skips non-music sections of YouTube music videos";
        this.arguments = "[on|off]";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
    }

    // Like repeat: a server setting, so it works from any channel.
    @Override
    protected void execute(CommandEvent event)
    {
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        String args = event.getArgs().trim();
        boolean value;
        if(args.isEmpty())
            value = !settings.getSponsorBlock();
        else if(args.equalsIgnoreCase("on") || args.equalsIgnoreCase("true"))
            value = true;
        else if(args.equalsIgnoreCase("off") || args.equalsIgnoreCase("false"))
            value = false;
        else
        {
            event.replyError("Valid options are `on` or `off` (or leave empty to toggle)");
            return;
        }
        settings.setSponsorBlock(value);
        event.replySuccess("SponsorBlock is now `" + (value ? "on" : "off") + "`" + (value ? " — non-music sections of YouTube videos are skipped" : ""));
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally Empty */ }
}
