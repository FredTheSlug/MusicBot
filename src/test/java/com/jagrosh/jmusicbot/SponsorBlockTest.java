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

import com.jagrosh.jmusicbot.audio.SponsorBlock;
import com.jagrosh.jmusicbot.audio.SponsorBlock.Segment;
import com.jagrosh.jmusicbot.audio.SponsorBlock.Step;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SponsorBlockTest
{
    private static String seg(double start, double end, String category)
    {
        return "{\"segment\":[" + start + "," + end + "],\"category\":\"" + category + "\",\"actionType\":\"skip\"}";
    }

    @Test
    public void parsesMusicOfftopicOnly()
    {
        String json = "[" + seg(10.5, 20, "music_offtopic") + "," + seg(30, 40, "sponsor") + "]";
        assertEquals(Collections.singletonList(new Segment(10500, 20000)), SponsorBlock.parseSegments(json));
        String mute = "[{\"segment\":[1,9],\"category\":\"music_offtopic\",\"actionType\":\"mute\"}]";
        assertTrue("only skip actions", SponsorBlock.parseSegments(mute).isEmpty());
    }

    @Test
    public void mergesOverlapsAndDropsTiny()
    {
        String json = "[" + seg(60, 70, "music_offtopic") + "," + seg(10, 20, "music_offtopic") + ","
                + seg(15, 25, "music_offtopic") + "," + seg(50, 50.5, "music_offtopic") + "]";
        assertEquals(Arrays.asList(new Segment(10000, 25000), new Segment(60000, 70000)), SponsorBlock.parseSegments(json));
    }

    @Test
    public void ignoresJunk()
    {
        assertTrue(SponsorBlock.parseSegments("not json").isEmpty());
        assertTrue(SponsorBlock.parseSegments("{\"x\":1}").isEmpty());
        assertTrue(SponsorBlock.parseSegments("[" + seg(20, 10, "music_offtopic") + "]").isEmpty());
        assertEquals(Collections.singletonList(new Segment(0, 10000)),
                SponsorBlock.parseSegments("[" + seg(-5, 10, "music_offtopic") + "]"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 30; i++)
            many.append(i == 0 ? "" : ",").append(seg(i * 10, i * 10 + 2, "music_offtopic"));
        assertEquals(20, SponsorBlock.parseSegments(many.append("]").toString()).size());
    }

    @Test
    public void nextStepBeforeInsideAfter()
    {
        List<Segment> segments = Arrays.asList(new Segment(10000, 20000), new Segment(60000, 70000));
        assertEquals(new Step(null, 10000L), SponsorBlock.nextStep(segments, 0));
        assertEquals(new Step(20000L, null), SponsorBlock.nextStep(segments, 15000));
        assertEquals(new Step(20000L, null), SponsorBlock.nextStep(segments, 10000));
        assertEquals(new Step(null, 60000L), SponsorBlock.nextStep(segments, 30000));
        assertEquals(new Step(null, null), SponsorBlock.nextStep(segments, 80000));
        assertEquals(new Step(null, null), SponsorBlock.nextStep(Collections.emptyList(), 0));
    }
}
