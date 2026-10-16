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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.soundcloud.YouTubeFallbackTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** The footer of the Discord now-playing message. */
public class AudioHandlerFooterTest
{
    private static final AudioTrackInfo SOUNDCLOUD = new AudioTrackInfo("444222a", "Lil Uzi Vert", 180_000,
            "X:soundcloud", false, "https://soundcloud.com/liluzivert/444222a");

    @Test
    public void footerSaysPlayingFromYoutube()
    {
        YouTubeFallbackTrack fallback = new YouTubeFallbackTrack(SOUNDCLOUD, query -> Collections.<AudioTrack>emptyList(),
                null);
        // U+00B7 (middle dot) is the separator
        assertEquals("Source: Lil Uzi Vert \u00B7 playing from YouTube (SoundCloud protects the original)",
                AudioHandler.footerFor(fallback));
    }

    @Test
    public void footerOfANormalTrackIsUnchanged()
    {
        assertEquals("Source: Lil Uzi Vert", AudioHandler.footerFor(new Song(SOUNDCLOUD)));
        assertEquals("Source: Rick Astley", AudioHandler.footerFor(new Song(new AudioTrackInfo("Never Gonna Give You Up",
                "Rick Astley", 212_000, "dQw4w9WgXcQ", false, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))));
    }

    @Test
    public void noAuthorMeansNoFooter()
    {
        assertNull(AudioHandler.footerFor(new Song(new AudioTrackInfo("Song", "", 1000, "id", false, "https://x"))));
        assertNull(AudioHandler.footerFor(new Song(new AudioTrackInfo("Song", null, 1000, "id", false, "https://x"))));
        assertNull(AudioHandler.footerFor(new YouTubeFallbackTrack(
                new AudioTrackInfo("Song", "", 1000, "X:id", false, "https://x"), query -> Collections.<AudioTrack>emptyList(),
                null)));
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
