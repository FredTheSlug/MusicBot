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

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebPayloadsTest
{
    @Test
    public void requesterFieldsIncludeNameAndId()
    {
        Map<String, Object> t = new HashMap<>();
        WebServer.putRequester(t, RequestMetadata.fromDiscordWebUser(233362518045753345L, "fred", "0", "", null), "Web");
        assertEquals("fred", t.get("requestedBy"));
        assertEquals("233362518045753345", t.get("requestedById"));
    }

    @Test
    public void requesterFieldsFallBackWhenNoUser()
    {
        Map<String, Object> t = new HashMap<>();
        WebServer.putRequester(t, null, "Web");
        assertEquals("Web", t.get("requestedBy"));
        assertTrue(t.containsKey("requestedById"));
        assertNull(t.get("requestedById"));
        WebServer.putRequester(t, RequestMetadata.EMPTY, "");
        assertEquals("", t.get("requestedBy"));
        assertNull(t.get("requestedById"));
    }

    @Test
    public void mePayloadHasIdNameAndAvatar()
    {
        Map<String, Object> me = WebServer.mePayload(233362518045753345L, "fred", "https://cdn.discordapp.com/avatars/1/a.png");
        assertEquals("233362518045753345", me.get("id"));
        assertEquals("fred", me.get("name"));
        assertEquals("https://cdn.discordapp.com/avatars/1/a.png", me.get("avatarUrl"));
    }

    @Test
    public void defaultGuildMatchesOnlyTheConfiguredId()
    {
        assertTrue(WebServer.isDefaultGuild("123", "123"));
        assertFalse(WebServer.isDefaultGuild("456", "123"));
        assertFalse(WebServer.isDefaultGuild("123", ""));
        assertFalse(WebServer.isDefaultGuild("123", null));
        assertTrue(WebServer.isDefaultGuild("123", " 123 "));
    }
}
