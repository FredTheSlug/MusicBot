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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The decoration pieces of every built-in theme and its extra decoration color, from {@code /web/theme-pieces.json}
 * (the one list the panel, the creator and the mini-player read too): {@code { "<theme>": { "decoExtra": "#rrggbb",
 * "pieces": [ { "id", "label", "background" } ] } }}. Read from the classpath on first use and kept; a list that is
 * missing or broken makes every use throw an {@link IllegalStateException} (it is read again each time, not kept).
 */
public final class ThemePieces
{
    private static final String RESOURCE = "/web/theme-pieces.json";
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-f]{6}");

    /** The list once it has been read; null before. */
    private static volatile Map<String, Base> all;

    private ThemePieces()
    {
    }

    /** The ids of a built-in theme's pieces, in the list's order; read-only. Throws {@link IllegalArgumentException} for anything else. */
    public static List<String> ids(String base)
    {
        return of(base).ids;
    }

    /** A built-in theme's own extra decoration color, {@code #rrggbb}. Throws {@link IllegalArgumentException} for anything else. */
    public static String decoExtra(String base)
    {
        return of(base).decoExtra;
    }

    private static Base of(String base)
    {
        Map<String, Base> list = all;
        if(list == null)
        {
            synchronized(ThemePieces.class)
            {
                if(all == null)
                    all = load(RESOURCE);
                list = all;
            }
        }
        Base found = base == null ? null : list.get(base);
        if(found == null)
            throw new IllegalArgumentException("Not a built-in theme");
        return found;
    }

    /** The list in a classpath resource, read and checked; {@link IllegalStateException} when it is missing, unreadable or broken. */
    static Map<String, Base> load(String resource)
    {
        try(InputStream in = ThemePieces.class.getResourceAsStream(resource))
        {
            if(in == null)
                throw new IllegalStateException(resource + " is not on the classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch(IOException e)
        {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    /** The list, parsed and checked: every one of {@link ThemeFile#BASES}, nothing else. */
    static Map<String, Base> parse(String text)
    {
        if(text == null)
            throw new IllegalStateException("No pieces list");
        JSONObject root;
        try
        {
            root = new JSONObject(text);
        }
        catch(JSONException e)
        {
            throw new IllegalStateException("The pieces list is not JSON: " + e.getMessage(), e);
        }
        if(!root.keySet().equals(new HashSet<>(ThemeFile.BASES)))
            throw new IllegalStateException("The pieces list must name exactly the built-in themes");
        Map<String, Base> all = new LinkedHashMap<>();
        for(String base : ThemeFile.BASES)
        {
            JSONObject entry = root.optJSONObject(base);
            if(entry == null)
                throw new IllegalStateException(base + ": not an entry");
            Object color = entry.opt("decoExtra");
            if(!(color instanceof String) || !COLOR.matcher((String) color).matches())
                throw new IllegalStateException(base + ": decoExtra must be #rrggbb");
            JSONArray pieces = entry.optJSONArray("pieces");
            if(pieces == null)
                throw new IllegalStateException(base + ": no pieces");
            List<String> ids = new ArrayList<>();
            for(int i = 0; i < pieces.length(); i++)
            {
                JSONObject piece = pieces.optJSONObject(i);
                Object id = piece == null ? null : piece.opt("id");
                if(!(id instanceof String) || !ID.matcher((String) id).matches() || ids.contains(id))
                    throw new IllegalStateException(base + ": piece " + i + " needs its own id of a-z, 0-9 and dashes");
                if(!(piece.opt("background") instanceof Boolean))
                    throw new IllegalStateException(base + ": piece " + id + " needs background true or false");
                ids.add((String) id);
            }
            all.put(base, new Base(Collections.unmodifiableList(ids), (String) color));
        }
        return Collections.unmodifiableMap(all);
    }

    /** One built-in theme's entry. */
    static final class Base
    {
        final List<String> ids;
        final String decoExtra;

        Base(List<String> ids, String decoExtra)
        {
            this.ids = ids;
            this.decoExtra = decoExtra;
        }
    }
}
