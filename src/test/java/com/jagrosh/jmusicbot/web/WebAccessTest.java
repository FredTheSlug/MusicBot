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
package com.jagrosh.jmusicbot.web;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebAccessTest
{
    private static final long OWNER = 1L;
    private static final long FRIEND = 2L;
    private static final long STRANGER = 3L;
    private static final Set<Long> BOT_GUILDS = Set.of(100L, 200L);

    @Test
    public void memberOfABotGuildMayLogIn()
    {
        assertTrue(WebServer.mayUseWebPanel(FRIEND, OWNER, Set.of(200L, 999L), BOT_GUILDS));
    }

    @Test
    public void discordUserSharingNoGuildWithTheBotMayNotLogIn()
    {
        assertFalse(WebServer.mayUseWebPanel(STRANGER, OWNER, Set.of(999L), BOT_GUILDS));
        assertFalse(WebServer.mayUseWebPanel(STRANGER, OWNER, Set.of(), BOT_GUILDS));
    }

    @Test
    public void ownerMayAlwaysLogIn()
    {
        assertTrue(WebServer.mayUseWebPanel(OWNER, OWNER, Set.of(), BOT_GUILDS));
    }

    @Test
    public void anyoneMayWatchWhenWebAuthIsDisabled()
    {
        assertTrue(WebServer.mayWatchGuild(false, null, "100"));
    }

    @Test
    public void unauthenticatedSocketMayNotWatchAGuild()
    {
        assertFalse(WebServer.mayWatchGuild(true, null, "100"));
    }

    @Test
    public void loggedInUserMayOnlyWatchTheirOwnGuilds()
    {
        assertTrue(WebServer.mayWatchGuild(true, Set.of(100L), "100"));
        assertFalse(WebServer.mayWatchGuild(true, Set.of(100L), "200"));
        assertFalse(WebServer.mayWatchGuild(true, Set.of(100L), "not-a-guild"));
    }
}
