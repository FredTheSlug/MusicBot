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
package com.jagrosh.jmusicbot;

import org.junit.Test;
import static org.junit.Assert.*;

/** Where Discord replies send people to edit playlists. */
public class BotConfigPanelUrlTest
{
    @Test
    public void theRedirectUriGivesItsOrigin()
    {
        assertEquals("https://bot.example.com/", BotConfig.panelUrl("https://bot.example.com/auth/discord/callback", "0.0.0.0", 8080));
        assertEquals("a port stays", "http://localhost:8080/", BotConfig.panelUrl("http://localhost:8080/api/auth/callback", "0.0.0.0", 9999));
        assertEquals("no path at all", "https://music.example.org/", BotConfig.panelUrl("https://music.example.org", "0.0.0.0", 8080));
        assertEquals("query and fragment are left out", "https://music.example.org:8443/",
                BotConfig.panelUrl("https://music.example.org:8443/cb?x=1#top", "0.0.0.0", 8080));
        assertEquals("spaces around it", "https://bot.example.com/", BotConfig.panelUrl("  https://bot.example.com/cb  ", "h", 1));
        assertEquals("the scheme in lower case", "https://bot.example.com/", BotConfig.panelUrl("HTTPS://bot.example.com/cb", "h", 1));
        assertEquals("a password in it is not repeated", "https://bot.example.com/",
                BotConfig.panelUrl("https://user:secret@bot.example.com/cb", "h", 1));
        assertEquals("an IPv6 address", "http://[::1]:8080/", BotConfig.panelUrl("http://[::1]:8080/cb", "h", 1));
    }

    @Test
    public void withoutAUsableRedirectUriItIsTheWebHostAndPort()
    {
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("   ", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl(null, "music.local", 8081));
        assertEquals("http://0.0.0.0:8080/", BotConfig.panelUrl("", "0.0.0.0", 8080));
        // something that is not a web address
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("not a url", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("/just/a/path", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("ftp://files.example.com/x", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("https://", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("http://bad host/cb", "music.local", 8081));
        assertEquals("http://music.local:8081/", BotConfig.panelUrl("discord://app/cb", "music.local", 8081));
    }
}
