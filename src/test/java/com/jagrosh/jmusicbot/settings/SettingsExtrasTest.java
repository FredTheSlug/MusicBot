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
package com.jagrosh.jmusicbot.settings;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsExtrasTest
{
    private static Settings fresh()
    {
        return new Settings(null, 0L, 0L, 0L, 100, null, RepeatMode.OFF, null, -1, QueueType.LINEAR);
    }

    @Test
    public void extrasDefaultAndRoundTrip()
    {
        Settings defaults = fresh();
        SettingsManager.readExtras(new JSONObject(), defaults);
        assertFalse(defaults.getAutoplay());
        assertTrue(defaults.getSponsorBlock());

        JSONObject written = new JSONObject();
        SettingsManager.writeExtras(defaults, written);
        assertEquals("defaults are not written", 0, written.length());

        Settings changed = fresh();
        SettingsManager.readExtras(new JSONObject("{\"autoplay\":true,\"sponsorblock\":false}"), changed);
        assertTrue(changed.getAutoplay());
        assertFalse(changed.getSponsorBlock());
        JSONObject back = new JSONObject();
        SettingsManager.writeExtras(changed, back);
        assertTrue(back.getBoolean("autoplay"));
        assertFalse(back.getBoolean("sponsorblock"));
    }
}
