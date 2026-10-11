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

import com.jagrosh.jmusicbot.audio.Autoplay;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoplayTest
{
    @Test
    public void youtubeIds()
    {
        assertTrue(Autoplay.isYoutubeId("dQw4w9WgXcQ"));
        assertTrue(Autoplay.isYoutubeId("a-b_c-d_e-f"));
        assertFalse(Autoplay.isYoutubeId("dQw4w9WgXc"));
        assertFalse(Autoplay.isYoutubeId("dQw4w9WgXcQ!"));
        assertFalse(Autoplay.isYoutubeId("dQw4 9WgXcQ"));
        assertFalse(Autoplay.isYoutubeId(null));
    }

    @Test
    public void mixUrl()
    {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ", Autoplay.mixUrl("dQw4w9WgXcQ"));
    }

    @Test
    public void pickSkipsCurrentAndRecent()
    {
        assertEquals(2, Autoplay.pick(Arrays.asList("cur", "a", "b"), new HashSet<>(Arrays.asList("a")), "cur"));
        assertEquals(-1, Autoplay.pick(Arrays.asList("cur", "a"), new HashSet<>(Arrays.asList("a")), "cur"));
        assertEquals(-1, Autoplay.pick(Collections.emptyList(), Collections.emptySet(), "x"));
        assertEquals(1, Autoplay.pick(Arrays.asList(null, "b"), Collections.emptySet(), "x"));
    }
}
