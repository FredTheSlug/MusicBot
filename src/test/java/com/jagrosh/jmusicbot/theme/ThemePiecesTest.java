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
package com.jagrosh.jmusicbot.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThemePiecesTest
{
    /** The pieces list as it is in the source tree (the server reads its copy from the classpath). */
    private static JSONObject sourceList() throws IOException
    {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/resources/web/theme-pieces.json")), StandardCharsets.UTF_8));
    }

    @Test
    public void everyBaseHasThePiecesAndExtraColourOfTheList() throws IOException
    {
        JSONObject list = sourceList();
        assertEquals(new HashSet<>(ThemeFile.BASES), list.keySet());
        for(String base : ThemeFile.BASES)
        {
            JSONArray pieces = list.getJSONObject(base).getJSONArray("pieces");
            List<String> ids = new ArrayList<>();
            for(int i = 0; i < pieces.length(); i++)
                ids.add(pieces.getJSONObject(i).getString("id"));
            assertEquals(base, ids, ThemePieces.ids(base));
            assertEquals(base, list.getJSONObject(base).getString("decoExtra"), ThemePieces.decoExtra(base));
        }
        // the three that are not their theme's light main colour
        assertEquals("#dc2626", ThemePieces.decoExtra("rasta"));
        assertEquals("#22d3ee", ThemePieces.decoExtra("cipher"));
        assertEquals("#fde047", ThemePieces.decoExtra("ember"));
        assertEquals(Arrays.asList("flames", "edge", "fire"), ThemePieces.ids("ember"));
        assertEquals(Arrays.asList("glow"), ThemePieces.ids("nebula"));
    }

    @Test
    public void unknownBasesHaveNothing()
    {
        for(String base : new String[]{null, "", "Ember", "nope", "custom"})
            assertThrows(String.valueOf(base), IllegalArgumentException.class, () -> ThemePieces.ids(base));
        assertThrows(IllegalArgumentException.class, () -> ThemePieces.decoExtra("nope"));
    }

    @Test
    public void theListsCannotBeChanged()
    {
        assertThrows(UnsupportedOperationException.class, () -> ThemePieces.ids("revival").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> ThemePieces.ids("revival").remove(0));
    }

    @Test
    public void aBrokenListIsRefused()
    {
        JSONObject good = new JSONObject();
        for(String base : ThemeFile.BASES)
            good.put(base, new JSONObject().put("decoExtra", "#aabbcc")
                    .put("pieces", new JSONArray().put(new JSONObject().put("id", "glow").put("label", "Glow").put("background", false))));
        assertEquals(Arrays.asList("glow"), ThemePieces.parse(good.toString()).get("ember").ids);

        List<JSONObject> broken = new ArrayList<>();
        broken.add(new JSONObject(good.toString()).put("ember", JSONObject.NULL));
        JSONObject missing = new JSONObject(good.toString());
        missing.remove("ember");
        broken.add(missing);
        broken.add(new JSONObject(good.toString()).put("ember", new JSONObject().put("decoExtra", "red")
                .put("pieces", new JSONArray())));
        broken.add(new JSONObject(good.toString()).put("ember", new JSONObject().put("decoExtra", "#aabbccdd")
                .put("pieces", new JSONArray())));
        for(String id : new String[]{"", "Flames", "a b", "x\"y", "\u00fc"})
            broken.add(new JSONObject(good.toString()).put("ember", new JSONObject().put("decoExtra", "#aabbcc")
                    .put("pieces", new JSONArray().put(new JSONObject().put("id", id).put("background", false)))));
        broken.add(new JSONObject(good.toString()).put("ember", new JSONObject().put("decoExtra", "#aabbcc")
                .put("pieces", new JSONArray().put(new JSONObject().put("id", "a").put("background", false))
                        .put(new JSONObject().put("id", "a").put("background", true)))));
        broken.add(new JSONObject(good.toString()).put("ember", new JSONObject().put("decoExtra", "#aabbcc")
                .put("pieces", new JSONArray().put(new JSONObject().put("id", "a").put("background", "yes")))));
        for(JSONObject list : broken)
            assertThrows(list.optString("ember"), IllegalStateException.class, () -> ThemePieces.parse(list.toString()));
        assertThrows(IllegalStateException.class, () -> ThemePieces.parse("not json"));
        assertThrows(IllegalStateException.class, () -> ThemePieces.parse(null));
    }

    @Test
    public void aMissingOrBrokenListFailsWithIllegalStateExceptionEveryTime()
    {
        // not an error from class loading (ExceptionInInitializerError, then NoClassDefFoundError): the documented exception, each time
        for(int i = 0; i < 2; i++)
        {
            assertThrows(IllegalStateException.class, () -> ThemePieces.load("/web/no-such-pieces-list.json"));
            assertThrows(IllegalStateException.class, () -> ThemePieces.load("/web/index.html"));
        }
        assertEquals(ThemePieces.ids("ember"), ThemePieces.load("/web/theme-pieces.json").get("ember").ids);
    }
}
