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
import com.jagrosh.jmusicbot.audio.soundcloud.YouTubeFallbackTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
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
    public void broadcastMessageCarriesTheClientsGuildAndRealJson()
    {
        JSONObject data = new JSONObject().put("name", "Mix").put("folder", "Rock").put("version", 3);
        JSONObject message = new JSONObject(WebServer.broadcastMessage("playlists", "123", data));
        assertEquals("playlists", message.getString("type"));
        assertEquals("123", message.getString("guildId"));
        assertEquals("an object, not a string of one", 3, message.getJSONObject("data").getInt("version"));
        assertEquals("Mix", message.getJSONObject("data").getString("name"));
        assertEquals("456", new JSONObject(WebServer.broadcastMessage("playlists", "456", data)).getString("guildId"));

        Map<String, Object> map = new HashMap<>();
        map.put("tree", true);
        assertTrue(new JSONObject(WebServer.broadcastMessage("playlists", "123", map)).getJSONObject("data").getBoolean("tree"));
        assertTrue("no data is still valid", new JSONObject(WebServer.broadcastMessage("playlists", "123", null)).has("data"));
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

    @Test
    public void viaIsYoutubeForAFallbackTrack() throws Exception
    {
        AudioTrackInfo info = new AudioTrackInfo("444222a", "Lil Uzi Vert", 180_000, "X:soundcloud", false,
                "https://soundcloud.com/liluzivert/444222a");
        assertNull("a normal track plays as itself", WebServer.viaOf(new Song(info)));
        assertNull("nothing playing", WebServer.viaOf(null));

        // the now-playing message is built before the track's process() runs the YouTube search, so the note
        // cannot wait for the swap: a fallback track plays from YouTube or fails and is skipped
        YouTubeFallbackTrack fallback = new YouTubeFallbackTrack(info,
                query -> Collections.<com.sedmelluq.discord.lavaplayer.track.AudioTrack>singletonList(
                        new Song(new AudioTrackInfo("Song", "Channel", 181_000, "abcdefghijk", false,
                                "https://youtu.be/abcdefghijk"))), null);
        assertEquals("youtube", WebServer.viaOf(fallback));

        fallback.process(null);
        assertEquals("youtube", WebServer.viaOf(fallback));
    }

    /** A track that is not a fallback: it only has an info. */
    private static class Song extends BaseAudioTrack
    {
        Song(AudioTrackInfo info)
        {
            super(info);
        }

        @Override
        public void process(LocalAudioTrackExecutor executor)
        {
        }
    }
}
