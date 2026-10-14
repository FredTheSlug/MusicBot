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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Guards hand edits to the "What's new" list that ships in the jar (and is served at
 * /web/patch-notes.json).
 */
public class PatchNotesFileTest
{
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern APP_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final List<String> AREAS = Arrays.asList("Panel", "Mini-player", "App", "Discord");

    private static JSONArray notes() throws IOException
    {
        try (InputStream in = PatchNotesFileTest.class.getResourceAsStream("/web/patch-notes.json"))
        {
            assertNotNull("/web/patch-notes.json is missing from the classpath", in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new JSONObject(json).getJSONArray("notes");
        }
    }

    @Test
    public void parsesAndHasNotes() throws IOException
    {
        assertTrue("expected at least 3 entries", notes().length() >= 3);
    }

    @Test
    public void idsUniqueAndNewestFirst() throws IOException
    {
        JSONArray notes = notes();
        Set<String> ids = new HashSet<>();
        LocalDate previous = null;
        for (int i = 0; i < notes.length(); i++)
        {
            JSONObject note = notes.getJSONObject(i);
            String id = note.getString("id");
            String date = note.getString("date");
            assertTrue("date must look like YYYY-MM-DD: " + date, DATE.matcher(date).matches());
            LocalDate parsed = LocalDate.parse(date); // throws if it is not a real day
            assertTrue("ids must be the date or date-N: " + id, id.equals(date) || id.matches(Pattern.quote(date) + "-\\d+"));
            assertTrue("duplicate id " + id, ids.add(id));
            if (previous != null)
                assertFalse("entries must be newest first, but " + date + " follows " + previous, parsed.isAfter(previous));
            previous = parsed;
        }
    }

    @Test
    public void fieldsWithinLimits() throws IOException
    {
        JSONArray notes = notes();
        for (int i = 0; i < notes.length(); i++)
        {
            JSONObject note = notes.getJSONObject(i);
            String id = note.getString("id");
            int titleLength = note.getString("title").length();
            assertTrue("title of " + id + " must be 1-60 characters, was " + titleLength, titleLength >= 1 && titleLength <= 60);
            if (note.has("app"))
                assertTrue("app of " + id + " must look like 1.2.3", APP_VERSION.matcher(note.getString("app")).matches());
            JSONArray items = note.getJSONArray("items");
            assertTrue(id + " needs at least one item", items.length() > 0);
            for (int j = 0; j < items.length(); j++)
            {
                JSONObject item = items.getJSONObject(j);
                String area = item.getString("area");
                assertTrue("unknown area '" + area + "' in " + id, AREAS.contains(area));
                int textLength = item.getString("text").length();
                assertTrue("item text in " + id + " must be 1-300 characters, was " + textLength, textLength >= 1 && textLength <= 300);
            }
        }
    }
}
