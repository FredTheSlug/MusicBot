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

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeFileTest
{
    private static final String IMAGE = "0123456789abcdef0123456789abcdef.webp";

    private static String repeat(String piece, int count)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < count; i++)
            sb.append(piece);
        return sb.toString();
    }

    /** All 16 slots (or the 15 mini ones) with a valid color each. */
    private static JSONObject colors(boolean withGlass)
    {
        return oldColors(withGlass).put("decoExtra", "#f0abfc");
    }

    /** The 15 slots (or 14 mini ones) a theme had before the extra decoration color, as older panels still send them. */
    private static JSONObject oldColors(boolean withGlass)
    {
        JSONObject colors = new JSONObject();
        colors.put("bgPrimary", "#0b0d14");
        colors.put("bgSecondary", "#10131c");
        colors.put("bgCard", "#161a26");
        colors.put("bgElevated", "#1c2131");
        colors.put("bgHover", "#262c42");
        colors.put("accent", "#7c5cff");
        colors.put("accentLight", "#a08bff");
        colors.put("accent2", "#22d3ee");
        colors.put("textPrimary", "#eef0f8");
        colors.put("textSecondary", "#9aa0b8");
        colors.put("textTertiary", "#646a82");
        colors.put("border", "#ffffff12");
        colors.put("borderLight", "#ffffff1f");
        colors.put("success", "#34d399");
        if(withGlass)
            colors.put("glass", "#10131ce0");
        return colors;
    }

    private static JSONObject background()
    {
        return new JSONObject().put("image", IMAGE).put("dim", 45).put("blur", 4).put("fit", "cover");
    }

    private static JSONObject mini()
    {
        return new JSONObject().put("colors", colors(false)).put("useImage", true).put("dim", 55);
    }

    /** The values a browser sends: no id, owner, version or picture name. */
    private static JSONObject clientJson()
    {
        return new JSONObject()
                .put("name", "Midnight Drive")
                .put("visibility", "public")
                .put("base", "ember")
                .put("decorationsOff", new JSONArray().put("edge"))
                .put("colors", colors(true))
                .put("radiusScale", 100)
                .put("font", "Outfit");
    }

    /** What a panel from before the pieces sends, and what a theme file from then holds: an effects flag and 15 colors. */
    private static JSONObject oldShape(JSONObject json, Boolean effects)
    {
        json.remove("decorationsOff");
        if(effects != null)
            json.put("effects", effects);
        json.put("colors", oldColors(true));
        if(json.has("mini"))
            json.getJSONObject("mini").put("colors", oldColors(false));
        return json;
    }

    private static List<String> ids(String... ids)
    {
        return Arrays.asList(ids);
    }

    /** A complete stored theme file without the optional blocks. */
    private static JSONObject fileJson()
    {
        return clientJson()
                .put("format", 1)
                .put("id", "k3j9x0q2m7ab")
                .put("owner", new JSONObject().put("id", "233362518045753345").put("name", "fred"))
                .put("hiddenByOwner", false)
                .put("version", 3)
                .put("createdAt", "2026-10-14T18:00:00Z")
                .put("updatedAt", "2026-10-14T18:05:00Z");
    }

    private static JSONObject without(JSONObject json, String key)
    {
        json.remove(key);
        return json;
    }

    /** Asserts that both the stored-file reader and the browser-values reader refuse what {@code breakIt} does. */
    private static void assertRejected(Consumer<JSONObject> breakIt)
    {
        JSONObject file = fileJson();
        breakIt.accept(file);
        assertThrows("fromJson " + file, IllegalArgumentException.class, () -> ThemeFile.fromJson(file.toString()));
        JSONObject client = clientJson();
        breakIt.accept(client);
        assertThrows("valuesFromClient " + client, IllegalArgumentException.class, () -> ThemeFile.valuesFromClient(client));
    }

    /** Asserts that both readers accept what {@code change} does, and hands each result to {@code check}. */
    private static void assertAccepted(Consumer<JSONObject> change, Consumer<ThemeFile> check)
    {
        JSONObject file = fileJson();
        change.accept(file);
        check.accept(ThemeFile.fromJson(file.toString()));
        JSONObject client = clientJson();
        change.accept(client);
        check.accept(ThemeFile.valuesFromClient(client));
    }

    private static ThemeFile completeTheme()
    {
        return ThemeFile.fromJson(fileJson().put("background", background()).put("mini", mini()).toString());
    }

    @Test
    public void roundTrip()
    {
        ThemeFile file = new ThemeFile();
        file.setId("k3j9x0q2m7ab");
        file.setName("Midnight Drive");
        file.setOwner(new ThemeFile.Person("233362518045753345", "fred"));
        file.setVisibility("private");
        file.setHiddenByOwner(true);
        file.setVersion(3);
        file.setCreatedAt(Instant.parse("2026-10-14T18:00:00Z"));
        file.setUpdatedAt(Instant.parse("2026-10-14T18:05:00Z"));
        file.setBase("ember");
        file.setDecorationsOff(ids("fire", "flames"));
        Map<String, String> palette = new LinkedHashMap<>();
        JSONObject colors = colors(true);
        for(String slot : ThemeFile.COLOR_SLOTS)
            palette.put(slot, colors.getString(slot));
        file.setColors(palette);
        file.setRadiusScale(150);
        file.setFont("Fraunces");
        file.setBackground(new ThemeFile.Background(IMAGE, 45, 4, "contain"));
        Map<String, String> miniPalette = new LinkedHashMap<>(palette);
        miniPalette.remove("glass");
        miniPalette.put("accent", "#ff0000");
        file.setMini(new ThemeFile.Mini(miniPalette, true, 55));

        String text = file.toJson();
        ThemeFile back = ThemeFile.fromJson(text);

        assertEquals("k3j9x0q2m7ab", back.getId());
        assertEquals("Midnight Drive", back.getName());
        assertEquals("233362518045753345", back.getOwner().getId());
        assertEquals("fred", back.getOwner().getName());
        assertEquals("private", back.getVisibility());
        assertTrue(back.isHiddenByOwner());
        assertEquals(3L, back.getVersion());
        assertEquals(Instant.parse("2026-10-14T18:00:00Z"), back.getCreatedAt());
        assertEquals(Instant.parse("2026-10-14T18:05:00Z"), back.getUpdatedAt());
        assertEquals("ember", back.getBase());
        assertEquals(ids("fire", "flames"), back.getDecorationsOff());
        assertEquals(palette, back.getColors());
        assertEquals("#f0abfc", back.getColors().get("decoExtra"));
        assertEquals(ThemeFile.COLOR_SLOTS, Arrays.asList(back.getColors().keySet().toArray()));
        assertEquals(150, back.getRadiusScale());
        assertEquals("Fraunces", back.getFont());
        assertEquals(IMAGE, back.getBackground().getImage());
        assertEquals(45, back.getBackground().getDim());
        assertEquals(4, back.getBackground().getBlur());
        assertEquals("contain", back.getBackground().getFit());
        assertEquals(miniPalette, back.getMini().getColors());
        assertEquals(ThemeFile.MINI_SLOTS, Arrays.asList(back.getMini().getColors().keySet().toArray()));
        assertTrue(back.getMini().isUseImage());
        assertEquals(55, back.getMini().getDim());
        assertTrue(new JSONObject(text).similar(new JSONObject(back.toJson())));
    }

    @Test
    public void roundTripWithoutOptionalBlocksAndWithEveryPieceOn()
    {
        ThemeFile file = ThemeFile.fromJson(fileJson().put("decorationsOff", new JSONArray()).toString());
        assertEquals(ids(), file.getDecorationsOff());
        assertNull(file.getBackground());
        assertNull(file.getMini());
        ThemeFile back = ThemeFile.fromJson(file.toJson());
        assertEquals(ids(), back.getDecorationsOff());
        assertNull(back.getBackground());
        assertNull(back.getMini());
    }

    @Test
    public void roundTripKeepsEverything()
    {
        // a theme in the new shape, with every optional block: written, read, written again, nothing lost or changed
        JSONObject full = fileJson().put("base", "revival").put("decorationsOff", new JSONArray().put("rays").put("record"))
                .put("background", background()).put("mini", mini());
        full.getJSONObject("colors").put("decoExtra", "#123456");
        full.getJSONObject("mini").getJSONObject("colors").put("decoExtra", "#abcdef");
        ThemeFile first = ThemeFile.fromJson(full.toString());
        String text = first.toJson();
        ThemeFile again = ThemeFile.fromJson(text);
        assertEquals(text, again.toJson());
        assertEquals(ids("rays", "record"), again.getDecorationsOff());
        assertEquals("#123456", again.getColors().get("decoExtra"));
        assertEquals("#abcdef", again.getMini().getColors().get("decoExtra"));
        JSONObject written = new JSONObject(text);
        for(String key : new String[]{"name", "visibility", "base", "radiusScale", "font", "decorationsOff", "colors"})
            assertTrue(key, new JSONObject().put(key, full.get(key)).similar(new JSONObject().put(key, written.get(key))));

        // an old file (effects off, 15 colors, no extra color in the mini-player version) reads, writes in the new
        // shape and reads back the same: its pieces stay off, its colors stay, the extra colors are its base's
        JSONObject old = oldShape(fileJson().put("base", "rasta").put("background", background()).put("mini", mini()), false);
        ThemeFile converted = ThemeFile.fromJson(old.toString());
        ThemeFile resaved = ThemeFile.fromJson(converted.toJson());
        assertEquals(converted.toJson(), resaved.toJson());
        assertEquals(ids("stripe", "titles", "bar", "lyric"), resaved.getDecorationsOff());
        for(String slot : ThemeFile.MINI_SLOTS)
        {
            if(!slot.equals("decoExtra"))
                assertEquals(slot, old.getJSONObject("colors").getString(slot), resaved.getColors().get(slot));
        }
        assertEquals(old.getJSONObject("colors").getString("glass"), resaved.getColors().get("glass"));
        assertEquals("#dc2626", resaved.getColors().get("decoExtra"));
        assertEquals("#dc2626", resaved.getMini().getColors().get("decoExtra"));
        assertEquals(IMAGE, resaved.getBackground().getImage());
        assertEquals(55, resaved.getMini().getDim());
        assertEquals("Midnight Drive", resaved.getName());
    }

    @Test
    public void effectsIsNoLongerWritten()
    {
        for(Boolean effects : new Boolean[]{true, false, null})
        {
            JSONObject written = new JSONObject(ThemeFile.fromJson(oldShape(fileJson(), effects).toString()).toJson());
            assertFalse(written.toString(), written.has("effects"));
            assertTrue(written.has("decorationsOff"));
            assertEquals(effects != null && !effects ? 3 : 0, written.getJSONArray("decorationsOff").length());
        }
        // and a theme whose pieces are all on still writes its (empty) list
        JSONObject allOn = new JSONObject(ThemeFile.fromJson(fileJson().put("decorationsOff", new JSONArray()).toString()).toJson());
        assertEquals(0, allOn.getJSONArray("decorationsOff").length());
    }

    @Test
    public void toJsonWritesTheSpecShape()
    {
        JSONObject minimal = new JSONObject(ThemeFile.fromJson(fileJson().toString()).toJson());
        assertEquals(new HashSet<>(Arrays.asList("format", "id", "name", "owner", "visibility", "hiddenByOwner", "version",
                "createdAt", "updatedAt", "base", "decorationsOff", "colors", "radiusScale", "font")), minimal.keySet());
        assertEquals(1, minimal.getJSONArray("decorationsOff").length());
        assertEquals("edge", minimal.getJSONArray("decorationsOff").getString(0));
        assertEquals("#f0abfc", minimal.getJSONObject("colors").getString("decoExtra"));
        assertEquals(1, minimal.getInt("format"));
        assertEquals("k3j9x0q2m7ab", minimal.getString("id"));
        assertEquals("2026-10-14T18:05:00Z", minimal.getString("updatedAt"));
        assertEquals(new HashSet<>(Arrays.asList("id", "name")), minimal.getJSONObject("owner").keySet());
        assertEquals(new HashSet<>(ThemeFile.COLOR_SLOTS), minimal.getJSONObject("colors").keySet());

        JSONObject full = new JSONObject(completeTheme().toJson());
        assertEquals(new HashSet<>(Arrays.asList("image", "dim", "blur", "fit")), full.getJSONObject("background").keySet());
        assertEquals(new HashSet<>(Arrays.asList("colors", "useImage", "dim")), full.getJSONObject("mini").keySet());
        assertEquals(new HashSet<>(ThemeFile.MINI_SLOTS), full.getJSONObject("mini").getJSONObject("colors").keySet());
        assertTrue(full.getJSONObject("mini").getBoolean("useImage"));
    }

    @Test
    public void constantsMatchTheSpec()
    {
        assertEquals(Arrays.asList("nebula", "aurora", "ember", "crimson", "dawn", "dawn-dark", "steel", "verdant", "grove",
                "grapevine", "sumi", "rasta", "cipher", "signal", "victory", "neon", "revival"), ThemeFile.BASES);
        assertEquals(Arrays.asList("Inter", "DM Sans", "Outfit", "Syne", "Fraunces", "IBM Plex Mono", "Noto Sans JP",
                "Shippori Mincho"), ThemeFile.FONTS);
        assertEquals(Arrays.asList("bgPrimary", "bgSecondary", "bgCard", "bgElevated", "bgHover", "accent", "accentLight",
                "accent2", "textPrimary", "textSecondary", "textTertiary", "border", "borderLight", "success", "glass",
                "decoExtra"), ThemeFile.COLOR_SLOTS);
        assertEquals(16, ThemeFile.COLOR_SLOTS.size());
        assertEquals(15, ThemeFile.MINI_SLOTS.size());
        assertEquals(ThemeFile.COLOR_SLOTS.subList(0, 14), ThemeFile.MINI_SLOTS.subList(0, 14));
        assertEquals("decoExtra", ThemeFile.MINI_SLOTS.get(14));
        assertFalse(ThemeFile.MINI_SLOTS.contains("glass"));
        assertEquals(40, ThemeFile.MAX_NAME);
    }

    @Test
    public void fromJsonRefusesNonThemeText()
    {
        for(String text : new String[]{null, "", "{oops", "[]", "\"theme\"", "{}"})
            assertThrows("text " + text, IllegalArgumentException.class, () -> ThemeFile.fromJson(text));
        assertRejectedFile(file -> file.put("format", 2));
        assertRejectedFile(file -> file.put("format", "1"));
        assertRejectedFile(file -> file.put("format", 1.5));
        assertRejectedFile(file -> file.remove("format"));
    }

    private static void assertRejectedFile(Consumer<JSONObject> breakIt)
    {
        JSONObject file = fileJson();
        breakIt.accept(file);
        assertThrows("fromJson " + file, IllegalArgumentException.class, () -> ThemeFile.fromJson(file.toString()));
    }

    @Test
    public void rejectsBadColors()
    {
        String[] bad = {"#fff", "red", "#12345g", "#1234567", "url(x)", "#000000; x", "rgb(0,0,0)", "#00000", "#000000000",
                " #000000", "#000000 ", "#000000\n", "000000", "##000000", "#00 000", "", "#ｆｆｆｆｆｆ", "#٠٠٠٠٠٠",
                "#000000;background:url(//evil)", "var(--x)"};
        for(String color : bad)
            assertRejected(json -> json.getJSONObject("colors").put("accent", color));
        assertRejected(json -> json.getJSONObject("colors").put("glass", "transparent"));
        assertRejected(json -> json.getJSONObject("colors").put("accent", 123456));
        assertRejected(json -> json.getJSONObject("colors").put("accent", true));
        assertRejected(json -> json.getJSONObject("colors").put("accent", JSONObject.NULL));
        assertRejected(json -> json.getJSONObject("colors").put("accent", new JSONObject()));
        assertRejected(json -> json.getJSONObject("colors").put("accent", new org.json.JSONArray().put("#000000")));
    }

    @Test
    public void colorsAreCheckedAndStoredLowerCased()
    {
        assertAccepted(json -> json.getJSONObject("colors").put("accent", "#AbCdEf").put("glass", "#10131CE0"),
                theme ->
                {
                    assertEquals("#abcdef", theme.getColors().get("accent"));
                    assertEquals("#10131ce0", theme.getColors().get("glass"));
                    assertEquals("#0b0d14", theme.getColors().get("bgPrimary"));
                });
        assertAccepted(json -> json.getJSONObject("colors").put("border", "#ffffff12").put("success", "#000000"),
                theme ->
                {
                    assertEquals("#ffffff12", theme.getColors().get("border"));
                    assertEquals("#000000", theme.getColors().get("success"));
                });
        ThemeFile theme = ThemeFile.fromJson(fileJson().toString());
        assertThrows(UnsupportedOperationException.class, () -> theme.getColors().put("accent", "red"));
    }

    @Test
    public void rejectsOutOfRange()
    {
        assertRejected(json -> json.put("radiusScale", -1));
        assertRejected(json -> json.put("radiusScale", 201));
        assertRejected(json -> json.put("radiusScale", Long.MAX_VALUE));
        assertRejected(json -> json.put("radiusScale", 100.5));
        assertRejected(json -> json.put("radiusScale", "100"));
        assertRejected(json -> json.put("radiusScale", true));
        assertRejected(json -> json.put("radiusScale", JSONObject.NULL));
        assertRejected(json -> json.remove("radiusScale"));
        assertRejected(json -> json.put("background", background().put("dim", 91)));
        assertRejected(json -> json.put("background", background().put("dim", -1)));
        assertRejected(json -> json.put("background", background().put("blur", 21)));
        assertRejected(json -> json.put("background", background().put("blur", -1)));
        assertRejected(json -> json.put("background", background().put("fit", "stretch")));
        assertRejected(json -> json.put("background", background().put("fit", "Cover")));
        assertRejected(json -> json.put("background", background().put("fit", "cover; x")));
        assertRejected(json -> json.put("background", background().put("dim", "45")));
        assertRejected(json -> json.put("background", background().put("blur", 4.5)));
        assertRejected(json -> json.put("background", background().put("dim", 91.0)));
        assertRejected(json -> json.put("background", "cover"));
        for(String key : new String[]{"dim", "blur", "fit"})
            assertRejected(json -> json.put("background", without(background(), key)));
        assertRejected(json -> json.put("mini", mini().put("dim", 91)));
        assertRejected(json -> json.put("mini", mini().put("dim", -1)));

        for(int radius : new int[]{0, 1, 100, 200})
            assertAccepted(json -> json.put("radiusScale", radius), theme -> assertEquals(radius, theme.getRadiusScale()));
        for(int dim : new int[]{0, 90})
            assertAccepted(json -> json.put("background", background().put("dim", dim)),
                    theme -> assertEquals(dim, theme.getBackground().getDim()));
        for(int blur : new int[]{0, 20})
            assertAccepted(json -> json.put("background", background().put("blur", blur)),
                    theme -> assertEquals(blur, theme.getBackground().getBlur()));
        for(String fit : new String[]{"cover", "contain", "center"})
            assertAccepted(json -> json.put("background", background().put("fit", fit)),
                    theme -> assertEquals(fit, theme.getBackground().getFit()));
        for(int dim : new int[]{0, 90})
            assertAccepted(json -> json.put("mini", mini().put("dim", dim)), theme -> assertEquals(dim, theme.getMini().getDim()));
    }

    @Test
    public void rejectsUnknownBaseAndFont()
    {
        for(String base : new String[]{"Ember", "nope", "", " ember", "dawn-dark ", "dawn_dark", "ember\"]{x", "../ember"})
            assertRejected(json -> json.put("base", base));
        assertRejected(json -> json.put("base", 3));
        assertRejected(json -> json.put("base", JSONObject.NULL));
        assertRejected(json -> json.remove("base"));
        for(String font : new String[]{"Comic Sans", "inter", "DM  Sans", "", "Inter;x", "Inter, sans-serif", "Outfit\n"})
            assertRejected(json -> json.put("font", font));
        assertRejected(json -> json.put("font", 1));
        assertRejected(json -> json.remove("font"));

        for(String base : ThemeFile.BASES)
            assertAccepted(json -> json.put("base", base), theme -> assertEquals(base, theme.getBase()));
        for(String font : ThemeFile.FONTS)
            assertAccepted(json -> json.put("font", font), theme -> assertEquals(font, theme.getFont()));
    }

    @Test
    public void visibilityIsExactlyPublicOrPrivate()
    {
        for(String visibility : new String[]{"Public", "PRIVATE", "unlisted", "", " public", "public ", "public\n"})
            assertRejected(json -> json.put("visibility", visibility));
        assertRejected(json -> json.put("visibility", true));
        assertRejected(json -> json.put("visibility", JSONObject.NULL));
        assertRejected(json -> json.remove("visibility"));
        assertAccepted(json -> json.put("visibility", "public"), theme -> assertEquals("public", theme.getVisibility()));
        assertAccepted(json -> json.put("visibility", "private"), theme -> assertEquals("private", theme.getVisibility()));
    }

    @Test
    public void nameRules()
    {
        for(String name : new String[]{"", "   ", repeat("a", 41), "a\u0007b", "\u0007Midnight", "Midnight\u0007", "a\tb",
                "a\nb", "a\u0000b", "a\u007fb", "a\u0085b", "\ud800", "a\udc00b", repeat("🎧", 41)})
            assertRejected(json -> json.put("name", name));
        assertRejected(json -> json.put("name", 42));
        assertRejected(json -> json.put("name", JSONObject.NULL));
        assertRejected(json -> json.remove("name"));

        assertAccepted(json -> json.put("name", "  Midnight  "), theme -> assertEquals("Midnight", theme.getName()));
        assertAccepted(json -> json.put("name", repeat("a", 40)), theme -> assertEquals(repeat("a", 40), theme.getName()));
        assertAccepted(json -> json.put("name", "a"), theme -> assertEquals("a", theme.getName()));
        assertAccepted(json -> json.put("name", "🎧 Late night 🌙"),
                theme -> assertEquals("🎧 Late night 🌙", theme.getName()));
        // 40 emoji are 80 chars but 40 code points
        String emojis = repeat("🎧", 40);
        assertAccepted(json -> json.put("name", emojis), theme -> assertEquals(emojis, theme.getName()));
        // length is counted after trimming
        assertAccepted(json -> json.put("name", "  " + repeat("a", 40) + "  "), theme -> assertEquals(40, theme.getName().length()));
        assertAccepted(json -> json.put("name", "<b>x</b> & \"quotes\""), theme -> assertEquals("<b>x</b> & \"quotes\"", theme.getName()));

        ThemeFile theme = new ThemeFile();
        theme.setName("  Midnight  ");
        assertEquals("Midnight", theme.getName());
        assertThrows(IllegalArgumentException.class, () -> theme.setName(""));
        assertThrows(IllegalArgumentException.class, () -> theme.setName(null));
        assertEquals("Midnight", theme.getName());
    }

    @Test
    public void missingOrExtraColorSlotsRejected()
    {
        for(String slot : ThemeFile.COLOR_SLOTS)
        {
            if(!slot.equals("decoExtra")) // the one a theme may leave out (decoExtraIsOptionalOnRead)
                assertRejected(json -> json.getJSONObject("colors").remove(slot));
        }
        assertRejected(json -> json.getJSONObject("colors").put("shadow", "#000000"));
        assertRejected(json -> json.getJSONObject("colors").put("BgPrimary", "#000000"));
        assertRejected(json -> without(json.getJSONObject("colors"), "bgPrimary").put("bgprimary", "#0b0d14"));
        assertRejected(json -> json.put("colors", new JSONObject()));
        assertRejected(json -> json.put("colors", "#000000"));
        assertRejected(json -> json.put("colors", new org.json.JSONArray()));
        assertRejected(json -> json.put("colors", JSONObject.NULL));
        assertRejected(json -> json.remove("colors"));

        ThemeFile theme = new ThemeFile();
        Map<String, String> missing = new LinkedHashMap<>();
        for(String slot : ThemeFile.MINI_SLOTS)
            missing.put(slot, "#000000");
        assertThrows(IllegalArgumentException.class, () -> theme.setColors(missing));
        assertThrows(IllegalArgumentException.class, () -> theme.setColors(null));
        missing.put("glass", "#000000");
        theme.setColors(missing);
        assertEquals(16, theme.getColors().size());
        assertEquals(ThemeFile.COLOR_SLOTS, Arrays.asList(theme.getColors().keySet().toArray()));
        // the setter takes complete colors only: filling in the extra color is for reading a theme, which knows its base
        Map<String, String> noExtra = new LinkedHashMap<>(missing);
        noExtra.remove("decoExtra");
        assertThrows(IllegalArgumentException.class, () -> theme.setColors(noExtra));
        missing.put("extra", "#000000");
        assertThrows(IllegalArgumentException.class, () -> theme.setColors(missing));
    }

    @Test
    public void miniOptionalAndValidated()
    {
        assertAccepted(json -> {}, theme -> assertNull(theme.getMini()));
        assertAccepted(json -> json.put("mini", JSONObject.NULL), theme -> assertNull(theme.getMini()));
        assertAccepted(json -> json.put("mini", mini()), theme ->
        {
            assertNotNull(theme.getMini());
            assertEquals(ThemeFile.MINI_SLOTS, Arrays.asList(theme.getMini().getColors().keySet().toArray()));
            assertEquals("#7c5cff", theme.getMini().getColors().get("accent"));
            assertTrue(theme.getMini().isUseImage());
            assertEquals(55, theme.getMini().getDim());
        });
        assertAccepted(json -> json.put("mini", mini().put("useImage", false).put("dim", 0)), theme ->
        {
            assertFalse(theme.getMini().isUseImage());
            assertEquals(0, theme.getMini().getDim());
        });
        assertAccepted(json -> json.put("mini", mini().put("colors", colors(false).put("accent", "#ABCDEF"))),
                theme -> assertEquals("#abcdef", theme.getMini().getColors().get("accent")));
        // a boolean flag that is absent is off
        assertAccepted(json -> json.put("mini", without(mini(), "useImage")), theme -> assertFalse(theme.getMini().isUseImage()));

        // glass is not a mini slot; every other slot is required
        assertRejected(json -> json.put("mini", mini().put("colors", colors(true))));
        for(String slot : ThemeFile.MINI_SLOTS)
            assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put(slot, "#000"))));
        for(String slot : ThemeFile.MINI_SLOTS)
        {
            if(!slot.equals("decoExtra")) // the one a theme may leave out
                assertRejected(json -> json.put("mini", mini().put("colors", without(colors(false), slot))));
        }
        assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("accent", "red"))));
        assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("accent", "url(x)"))));
        assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("extra", "#000000"))));
        assertRejected(json -> json.put("mini", mini().put("useImage", "yes")));
        assertRejected(json -> json.put("mini", mini().put("useImage", 1)));
        assertRejected(json -> json.put("mini", mini().put("dim", 91)));
        assertRejected(json -> json.put("mini", mini().put("dim", "50")));
        assertRejected(json -> json.put("mini", without(mini(), "dim")));
        assertRejected(json -> json.put("mini", without(mini(), "colors")));
        assertRejected(json -> json.put("mini", "mini"));
        assertRejected(json -> json.put("mini", new JSONObject()));
    }

    @Test
    public void backgroundIsOptionalAndTheStoredFileNeedsAValidPicture()
    {
        assertAccepted(json -> {}, theme -> assertNull(theme.getBackground()));
        assertAccepted(json -> json.put("background", JSONObject.NULL), theme -> assertNull(theme.getBackground()));
        for(String image : new String[]{"../0123456789abcdef0123456789abcdef.jpg", "0123456789ABCDEF0123456789ABCDEF.jpg",
                "0123456789abcdef0123456789abcde.jpg", "0123456789abcdef0123456789abcdef0.jpg",
                "0123456789abcdef0123456789abcdef.svg", "0123456789abcdef0123456789abcdef.JPG",
                "0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef.jpg ", "a/0123456789abcdef0123456789abcdef.jpg",
                "0123456789abcdef0123456789abcdef.jpg\n", "", "url(x)"})
        {
            JSONObject file = fileJson().put("background", background().put("image", image));
            assertThrows("image " + image, IllegalArgumentException.class, () -> ThemeFile.fromJson(file.toString()));
        }
        JSONObject noImage = fileJson().put("background", new JSONObject().put("dim", 1).put("blur", 1).put("fit", "cover"));
        assertThrows(IllegalArgumentException.class, () -> ThemeFile.fromJson(noImage.toString()));
        for(String ext : new String[]{"jpg", "png", "webp", "gif"})
        {
            ThemeFile file = ThemeFile.fromJson(fileJson()
                    .put("background", background().put("image", "0123456789abcdef0123456789abcdef." + ext)).toString());
            assertEquals("0123456789abcdef0123456789abcdef." + ext, file.getBackground().getImage());
        }
    }

    @Test
    public void aPictureLeavesTheSavedPiecesAlone()
    {
        // the panel switches a picture theme's background pieces off when it shows it; what is saved is the person's choice
        assertAccepted(json -> json.put("background", background()).put("decorationsOff", new JSONArray().put("edge")),
                theme -> assertEquals(ids("edge"), theme.getDecorationsOff()));
        assertAccepted(json -> json.put("background", background()).put("decorationsOff", new JSONArray()),
                theme -> assertEquals(ids(), theme.getDecorationsOff()));
        ThemeFile theme = ThemeFile.fromJson(fileJson().toString());
        theme.setBackground(new ThemeFile.Background(IMAGE, 0, 0, "cover"));
        assertEquals(ids("edge"), theme.getDecorationsOff());
        theme.setBackground(null);
        assertEquals(ids("edge"), theme.getDecorationsOff());
    }

    @Test
    public void unknownPieceIdsAreDropped()
    {
        assertAccepted(json -> json.put("decorationsOff", new JSONArray().put("flames").put("rays").put("nope").put("")
                .put("FLAMES").put(" fire").put("edge ").put("glow")),
                theme -> assertEquals(ids("flames"), theme.getDecorationsOff()));
        // ids are the starting theme's own: Revival's rays are no Ember piece, Ember's flames no Revival one
        assertAccepted(json -> json.put("base", "revival").put("decorationsOff", new JSONArray().put("flames").put("rays")),
                theme -> assertEquals(ids("rays"), theme.getDecorationsOff()));
        assertAccepted(json -> json.put("base", "nebula").put("decorationsOff", new JSONArray().put("glow").put("rays")),
                theme -> assertEquals(ids("glow"), theme.getDecorationsOff()));
        assertAccepted(json -> json.put("decorationsOff", new JSONArray().put("../x").put("a\"b").put(repeat("x", 5000))),
                theme -> assertEquals(ids(), theme.getDecorationsOff()));

        ThemeFile theme = new ThemeFile();
        theme.setBase("ember");
        theme.setDecorationsOff(ids("nope", "fire"));
        assertEquals(ids("fire"), theme.getDecorationsOff());
        theme.setDecorationsOff(null);
        assertEquals(ids(), theme.getDecorationsOff());
    }

    @Test
    public void duplicatesRemovedOrderKept()
    {
        assertAccepted(json -> json.put("decorationsOff", new JSONArray().put("fire").put("flames").put("fire").put("edge")
                .put("flames")), theme -> assertEquals(ids("fire", "flames", "edge"), theme.getDecorationsOff()));
        ThemeFile theme = new ThemeFile();
        theme.setBase("revival");
        theme.setDecorationsOff(ids("record", "rays", "record", "logo", "rays"));
        assertEquals(ids("record", "rays", "logo"), theme.getDecorationsOff());
        assertThrows(UnsupportedOperationException.class, () -> theme.getDecorationsOff().add("glows"));
    }

    @Test
    public void decorationsOffMustBeAListOfNames()
    {
        assertRejected(json -> json.put("decorationsOff", "flames"));
        assertRejected(json -> json.put("decorationsOff", new JSONObject().put("flames", true)));
        assertRejected(json -> json.put("decorationsOff", true));
        assertRejected(json -> json.put("decorationsOff", new JSONArray().put("flames").put(1)));
        assertRejected(json -> json.put("decorationsOff", new JSONArray().put(JSONObject.NULL)));
        assertRejected(json -> json.put("decorationsOff", new JSONArray().put(new JSONArray().put("flames"))));
        assertTrue(messageFor(json -> json.put("decorationsOff", "flames")).startsWith("Decorations"));
        // null is no list: the theme reads as one without (none off, unless it has the old effects flag)
        assertAccepted(json -> json.put("decorationsOff", JSONObject.NULL), theme -> assertEquals(ids(), theme.getDecorationsOff()));
    }

    @Test
    public void aNewStartingThemeKeepsOnlyItsOwnPieces()
    {
        ThemeFile theme = new ThemeFile();
        assertThrows("the pieces are the starting theme's, so it comes first", IllegalStateException.class,
                () -> theme.setDecorationsOff(ids("flames")));
        theme.setBase("cipher");
        theme.setDecorationsOff(ids("glows", "grid", "prompt"));
        theme.setBase("neon"); // Neon has glows too, but no grid or prompt
        assertEquals(ids("glows"), theme.getDecorationsOff());
        theme.setBase("ember");
        assertEquals(ids(), theme.getDecorationsOff());
    }

    @Test
    public void decoExtraMustBeAColour()
    {
        for(String bad : new String[]{"#fff", "red", "#12345g", "#dc2626ff", "#dc262680", "url(x)", "var(--accent)", "",
                " #dc2626", "#dc2626 ", "#dc2626;x", "dc2626"})
        {
            assertRejected(json -> json.getJSONObject("colors").put("decoExtra", bad));
            assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("decoExtra", bad))));
        }
        for(Object bad : new Object[]{123456, true, JSONObject.NULL, new JSONObject(), new JSONArray().put("#dc2626")})
        {
            assertRejected(json -> json.getJSONObject("colors").put("decoExtra", bad));
            assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("decoExtra", bad))));
        }
        assertEquals("Extra decoration color must be a color like #7c5cff",
                messageFor(json -> json.getJSONObject("colors").put("decoExtra", "red")));
        assertEquals("Mini-player extra decoration color must be a color like #7c5cff",
                messageFor(json -> json.put("mini", mini().put("colors", colors(false).put("decoExtra", "#dc2626ff")))));
        assertAccepted(json -> json.put("mini", mini().put("colors", colors(false).put("decoExtra", "#ABCDEF")))
                .getJSONObject("colors").put("decoExtra", "#DC2626"), theme ->
        {
            assertEquals("#dc2626", theme.getColors().get("decoExtra"));
            assertEquals("#abcdef", theme.getMini().getColors().get("decoExtra"));
        });
    }

    @Test
    public void decoExtraIsOptionalOnRead()
    {
        // left out, it is the starting theme's own (theme-pieces.json)
        for(String base : ThemeFile.BASES)
        {
            assertAccepted(json -> json.put("base", base).put("mini", mini()).put("decorationsOff", new JSONArray()).getJSONObject("colors")
                    .remove("decoExtra"), theme ->
            {
                assertEquals(base, ThemePieces.decoExtra(base), theme.getColors().get("decoExtra"));
                assertEquals(ThemeFile.COLOR_SLOTS, Arrays.asList(theme.getColors().keySet().toArray()));
                assertEquals("the mini-player version keeps its own", "#f0abfc", theme.getMini().getColors().get("decoExtra"));
            });
        }
        assertAccepted(json -> json.put("base", "rasta").getJSONObject("colors").remove("decoExtra"),
                theme -> assertEquals("#dc2626", theme.getColors().get("decoExtra")));
        assertAccepted(json -> json.put("base", "nebula").getJSONObject("colors").remove("decoExtra"),
                theme -> assertEquals("#9b82fc", theme.getColors().get("decoExtra")));
    }

    @Test
    public void miniColoursWithAndWithoutDecoExtra()
    {
        assertAccepted(json -> json.put("mini", mini()), theme ->
        {
            assertEquals(ThemeFile.MINI_SLOTS, Arrays.asList(theme.getMini().getColors().keySet().toArray()));
            assertEquals("#f0abfc", theme.getMini().getColors().get("decoExtra"));
        });
        assertAccepted(json -> json.put("base", "cipher").put("mini", mini().put("colors", oldColors(false))), theme ->
        {
            assertEquals(ThemeFile.MINI_SLOTS, Arrays.asList(theme.getMini().getColors().keySet().toArray()));
            assertEquals("the starting theme's", "#22d3ee", theme.getMini().getColors().get("decoExtra"));
            assertEquals("#f0abfc", theme.getColors().get("decoExtra"));
        });
        assertRejected(json -> json.put("mini", mini().put("colors", colors(false).put("glass", "#000000"))));

        // the constructor takes complete colors only
        Map<String, String> fourteen = new LinkedHashMap<>();
        for(String slot : ThemeFile.MINI_SLOTS.subList(0, 14))
            fourteen.put(slot, "#000000");
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Mini(fourteen, false, 0));
        fourteen.put("decoExtra", "#ffffff");
        assertEquals(15, new ThemeFile.Mini(fourteen, false, 0).getColors().size());
    }

    @Test
    public void oldThemesConvertOnRead()
    {
        // effects off: every piece of the starting theme off; on, or not there: none off
        for(String base : ThemeFile.BASES)
        {
            JSONObject off = oldShape(fileJson().put("base", base), false);
            assertEquals(base, ThemePieces.ids(base), ThemeFile.fromJson(off.toString()).getDecorationsOff());
            assertEquals(base, ThemePieces.ids(base), ThemeFile.valuesFromClient(oldShape(clientJson().put("base", base), false))
                    .getDecorationsOff());
            assertEquals(base, ids(), ThemeFile.fromJson(oldShape(fileJson().put("base", base), true).toString()).getDecorationsOff());
            assertEquals(base, ids(), ThemeFile.fromJson(oldShape(fileJson().put("base", base), null).toString()).getDecorationsOff());
            // the extra decoration color is the starting theme's own, so a Rasta theme's red stays red
            assertEquals(base, ThemePieces.decoExtra(base), ThemeFile.fromJson(off.toString()).getColors().get("decoExtra"));
        }
        assertEquals(ids("flames", "edge", "fire"), ThemeFile.fromJson(oldShape(fileJson(), false).toString()).getDecorationsOff());
        assertEquals("#dc2626", ThemeFile.fromJson(oldShape(fileJson().put("base", "rasta"), true).toString()).getColors().get("decoExtra"));
        assertEquals("#22d3ee", ThemeFile.fromJson(oldShape(fileJson().put("base", "cipher"), true).toString()).getColors().get("decoExtra"));

        // an old file with a picture was saved with effects off (a picture forced them off): all off, as it looked
        ThemeFile pictured = ThemeFile.fromJson(oldShape(fileJson().put("base", "grove").put("background", background()), false).toString());
        assertEquals(ids("photo", "hairlines", "glows"), pictured.getDecorationsOff());
        assertEquals(IMAGE, pictured.getBackground().getImage());

        // a theme that has both keeps its list; the old flag is only for themes without one
        assertEquals(ids("edge"), ThemeFile.fromJson(fileJson().put("effects", false).toString()).getDecorationsOff());
        assertEquals(ids(), ThemeFile.fromJson(fileJson().put("effects", false).put("decorationsOff", new JSONArray()).toString())
                .getDecorationsOff());
        // a null list counts as none
        assertEquals(ids("flames", "edge", "fire"), ThemeFile.fromJson(fileJson().put("effects", false)
                .put("decorationsOff", JSONObject.NULL).toString()).getDecorationsOff());
    }

    @Test
    public void fifteenColourClientsStillWork()
    {
        // a panel from before the pieces (or one that loaded before the bot was updated) sends 15 colors, 14 for the
        // mini-player, and the effects flag
        ThemeFile values = ThemeFile.valuesFromClient(oldShape(clientJson().put("base", "rasta").put("mini", mini()), false));
        assertEquals(16, values.getColors().size());
        assertEquals("#dc2626", values.getColors().get("decoExtra"));
        assertEquals("#7c5cff", values.getColors().get("accent"));
        assertEquals(15, values.getMini().getColors().size());
        assertEquals("#dc2626", values.getMini().getColors().get("decoExtra"));
        assertEquals(ids("stripe", "titles", "bar", "lyric"), values.getDecorationsOff());
        ThemeFile kept = ThemeFile.valuesFromClient(oldShape(clientJson(), true));
        assertEquals(ids(), kept.getDecorationsOff());
        assertEquals("#fde047", kept.getColors().get("decoExtra"));
    }

    @Test
    public void effectsMustBeABooleanWhenSent()
    {
        assertRejected(json -> json.put("effects", "true"));
        assertRejected(json -> json.put("effects", 1));
        assertRejected(json -> json.put("effects", JSONObject.NULL));
        assertAccepted(json -> json.remove("effects"), theme -> assertEquals(ids("edge"), theme.getDecorationsOff()));
    }

    @Test
    public void clientCannotSetIdOwnerVersionOrImage()
    {
        JSONObject json = clientJson()
                .put("id", "aaaaaaaaaaaa")
                .put("owner", new JSONObject().put("id", "999").put("name", "mallory"))
                .put("version", 99)
                .put("hiddenByOwner", true)
                .put("createdAt", "2020-01-01T00:00:00Z")
                .put("updatedAt", "2021-01-01T00:00:00Z")
                .put("format", 7)
                .put("background", background().put("image", "ffffffffffffffffffffffffffffffff.png"));

        ThemeFile values = ThemeFile.valuesFromClient(json);

        assertNull(values.getId());
        assertNull(values.getOwner());
        assertEquals(0L, values.getVersion());
        assertFalse(values.isHiddenByOwner());
        assertNull(values.getCreatedAt());
        assertNull(values.getUpdatedAt());
        assertNotNull(values.getBackground());
        assertNull("the picture name comes from an upload, never from the client", values.getBackground().getImage());
        assertEquals(45, values.getBackground().getDim());
        assertEquals(4, values.getBackground().getBlur());
        assertEquals("cover", values.getBackground().getFit());
        assertEquals("Midnight Drive", values.getName());

        // even garbage in those fields is just ignored
        JSONObject junk = clientJson().put("id", "../../etc").put("owner", "x").put("version", "lol")
                .put("hiddenByOwner", "maybe").put("createdAt", "yesterday").put("background", background().put("image", "../../x"));
        ThemeFile ignored = ThemeFile.valuesFromClient(junk);
        assertNull(ignored.getId());
        assertNull(ignored.getBackground().getImage());
    }

    @Test
    public void valuesFromClientReadsEveryEditableValue()
    {
        ThemeFile values = ThemeFile.valuesFromClient(clientJson()
                .put("visibility", "private").put("base", "dawn-dark").put("decorationsOff", new JSONArray().put("wash").put("edge"))
                .put("radiusScale", 0).put("font", "IBM Plex Mono").put("mini", mini()));
        assertEquals("private", values.getVisibility());
        assertEquals("dawn-dark", values.getBase());
        assertEquals(ids("wash", "edge"), values.getDecorationsOff());
        assertEquals(0, values.getRadiusScale());
        assertEquals("IBM Plex Mono", values.getFont());
        assertEquals(16, values.getColors().size());
        assertEquals("#10131ce0", values.getColors().get("glass"));
        assertEquals("#f0abfc", values.getColors().get("decoExtra"));
        assertNull(values.getBackground());
        assertEquals(15, values.getMini().getColors().size());
        assertThrows(IllegalArgumentException.class, () -> ThemeFile.valuesFromClient(null));
    }

    @Test
    public void clientMessagesNameTheField()
    {
        assertEquals("Main color must be a color like #7c5cff",
                messageFor(json -> json.getJSONObject("colors").put("accent", "red")));
        assertEquals("Cards must be a color like #7c5cff",
                messageFor(json -> json.getJSONObject("colors").put("bgCard", "#12")));
        assertEquals("Main text must be a color like #7c5cff",
                messageFor(json -> json.getJSONObject("colors").put("textPrimary", "url(x)")));
        assertEquals("Mini-player main color must be a color like #7c5cff",
                messageFor(json -> json.put("mini", mini().put("colors", colors(false).put("accent", "blue")))));
        assertTrue(messageFor(json -> json.put("name", "")).startsWith("Name"));
        assertTrue(messageFor(json -> json.put("name", "a\u0007b")).startsWith("Name"));
        assertTrue(messageFor(json -> json.put("radiusScale", 500)).startsWith("Corners"));
        assertTrue(messageFor(json -> json.put("font", "Wingdings")).startsWith("Font"));
        assertTrue(messageFor(json -> json.put("base", "nope")).toLowerCase().contains("starting theme"));
        assertTrue(messageFor(json -> json.put("visibility", "everyone")).startsWith("Visibility"));
        assertTrue(messageFor(json -> json.put("background", background().put("dim", 95))).contains("darken"));
        assertTrue(messageFor(json -> json.put("background", background().put("blur", 95))).contains("blur"));
        assertTrue(messageFor(json -> json.put("background", background().put("fit", "stretch"))).contains("fit"));
        assertTrue(messageFor(json -> json.put("mini", mini().put("dim", 95))).startsWith("Mini-player"));
        assertTrue(messageFor(json -> json.getJSONObject("colors").remove("accent")).contains("Main color"));
        assertTrue(messageFor(json -> json.getJSONObject("colors").put("shadow", "#000000")).contains("shadow"));
    }

    private static String messageFor(Consumer<JSONObject> breakIt)
    {
        JSONObject client = clientJson();
        breakIt.accept(client);
        try
        {
            ThemeFile.valuesFromClient(client);
        }
        catch(IllegalArgumentException e)
        {
            assertNotNull(e.getMessage());
            return e.getMessage();
        }
        throw new AssertionError("accepted " + client);
    }

    @Test
    public void storedFileFieldsAreChecked()
    {
        for(String id : new String[]{"ABCDEFGHIJKL", "k3j9x0q2m7a", "k3j9x0q2m7abc", "../../etc/pa", "k3j9x0q2m7a-", "", "k3j9x0q2m7ab\n"})
            assertRejectedFile(json -> json.put("id", id));
        assertRejectedFile(json -> json.put("id", 123456789012L));
        assertRejectedFile(json -> json.remove("id"));
        assertRejectedFile(json -> json.remove("owner"));
        assertRejectedFile(json -> json.put("owner", "fred"));
        assertRejectedFile(json -> json.put("owner", new JSONObject().put("name", "fred")));
        assertRejectedFile(json -> json.put("owner", new JSONObject().put("id", "").put("name", "fred")));
        assertRejectedFile(json -> json.put("version", -1));
        assertRejectedFile(json -> json.put("version", "3"));
        assertRejectedFile(json -> json.put("version", 3.5));
        assertRejectedFile(json -> json.put("hiddenByOwner", "no"));
        assertRejectedFile(json -> json.put("createdAt", "yesterday"));
        assertRejectedFile(json -> json.put("updatedAt", 12345));

        JSONObject lean = fileJson();
        for(String key : new String[]{"hiddenByOwner", "version", "createdAt", "updatedAt", "decorationsOff"})
            lean.remove(key);
        lean.getJSONObject("colors").remove("decoExtra");
        ThemeFile defaults = ThemeFile.fromJson(lean.toString());
        assertFalse(defaults.isHiddenByOwner());
        assertEquals(0L, defaults.getVersion());
        assertNull(defaults.getCreatedAt());
        assertNull(defaults.getUpdatedAt());
        assertEquals("no list and no effects flag: every piece on", ids(), defaults.getDecorationsOff());
        assertEquals("#fde047", defaults.getColors().get("decoExtra"));
        assertEquals("fred", defaults.getOwner().getName());
        assertEquals("233362518045753345", defaults.getOwner().getId());
        assertEquals("k3j9x0q2m7ab", defaults.getId());
    }

    @Test
    public void unknownKeysInAStoredFileAreIgnored()
    {
        ThemeFile file = ThemeFile.fromJson(fileJson().put("future", "x").put("background", JSONObject.NULL).toString());
        assertEquals("Midnight Drive", file.getName());
    }

    @Test
    public void settersEnforceTheRulesToo()
    {
        ThemeFile theme = new ThemeFile();
        assertThrows(IllegalArgumentException.class, () -> theme.setVisibility("Public"));
        assertThrows(IllegalArgumentException.class, () -> theme.setVisibility(null));
        assertThrows(IllegalArgumentException.class, () -> theme.setBase("nope"));
        assertThrows(IllegalArgumentException.class, () -> theme.setFont("nope"));
        assertThrows(IllegalArgumentException.class, () -> theme.setRadiusScale(-1));
        assertThrows(IllegalArgumentException.class, () -> theme.setRadiusScale(201));
        assertThrows(IllegalArgumentException.class, () -> theme.setId("short"));
        assertThrows(IllegalArgumentException.class, () -> theme.setId(null));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Background(IMAGE, 91, 0, "cover"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Background(IMAGE, 0, 21, "cover"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Background(IMAGE, 0, 0, "stretch"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Background("x.svg", 0, 0, "cover"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Mini(null, true, 10));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Person("", "fred"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeFile.Person(null, "fred"));

        theme.setVisibility("public");
        theme.setBase("grove");
        theme.setFont("Syne");
        theme.setRadiusScale(80);
        theme.setId("abcdefghij12");
        assertEquals("public", theme.getVisibility());
        assertEquals("grove", theme.getBase());
        assertEquals("Syne", theme.getFont());
        assertEquals(80, theme.getRadiusScale());
        assertEquals("abcdefghij12", theme.getId());
    }

    @Test
    public void aBackgroundCanGetItsPictureLater()
    {
        ThemeFile values = ThemeFile.valuesFromClient(clientJson().put("background", background()));
        ThemeFile.Background withoutPicture = values.getBackground();
        assertNull(withoutPicture.getImage());

        ThemeFile.Background withPicture = withoutPicture.withImage(IMAGE);
        assertEquals(IMAGE, withPicture.getImage());
        assertEquals(45, withPicture.getDim());
        assertEquals(4, withPicture.getBlur());
        assertEquals("cover", withPicture.getFit());
        assertNull("the original is unchanged", withoutPicture.getImage());
        assertThrows(IllegalArgumentException.class, () -> withoutPicture.withImage("../x.jpg"));
    }

    @Test
    public void toJsonRefusesAnIncompleteTheme()
    {
        assertThrows(IllegalStateException.class, () -> new ThemeFile().toJson());

        // values straight from a browser have no id, owner or picture name yet
        ThemeFile values = ThemeFile.valuesFromClient(clientJson().put("background", background()));
        assertThrows(IllegalStateException.class, values::toJson);

        values.setId("k3j9x0q2m7ab");
        values.setOwner(new ThemeFile.Person("1", "fred"));
        assertThrows("the background still has no picture", IllegalStateException.class, values::toJson);

        values.setBackground(values.getBackground().withImage(IMAGE));
        ThemeFile back = ThemeFile.fromJson(values.toJson());
        assertEquals(IMAGE, back.getBackground().getImage());
    }

    @Test
    public void newIdIsTwelveLowercaseAlphanumerics()
    {
        Random random = new Random(7);
        Set<String> seen = new HashSet<>();
        for(int i = 0; i < 200; i++)
        {
            String id = ThemeFile.newId(random);
            assertTrue(id, id.matches("[a-z0-9]{12}"));
            seen.add(id);
        }
        assertEquals(200, seen.size());
        assertEquals(ThemeFile.newId(new Random(5)), ThemeFile.newId(new Random(5)));
        new ThemeFile().setId(ThemeFile.newId(random));
    }

    @Test
    public void listsOfAllowedValuesCannotBeChanged()
    {
        for(List<String> list : Arrays.asList(ThemeFile.BASES, ThemeFile.FONTS, ThemeFile.COLOR_SLOTS, ThemeFile.MINI_SLOTS))
            assertThrows(UnsupportedOperationException.class, () -> list.add("x"));
    }
}
