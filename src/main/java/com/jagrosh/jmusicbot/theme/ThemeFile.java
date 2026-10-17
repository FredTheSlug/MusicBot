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

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One custom theme as stored in {@code Themes/<id>.json} (format 1). A plain mutable data class that
 * only converts to and from JSON text; the store owns all file access and locking. Its values reach CSS
 * in the panel, so every value is checked against an allow-list when it is set, read or written: a
 * {@code ThemeFile} can never hold a color, font, base, number or name that was not allowed.
 */
public class ThemeFile
{
    /** The only file format this class reads and writes. */
    public static final int FORMAT = 1;
    /** Longest theme name, counted in code points. */
    public static final int MAX_NAME = 40;
    public static final String PUBLIC = "public";
    public static final String PRIVATE = "private";

    /** The built-in themes a custom theme can be based on. */
    public static final List<String> BASES = list("nebula", "aurora", "ember", "crimson", "dawn", "dawn-dark", "steel",
            "verdant", "grove", "grapevine", "sumi", "rasta", "cipher", "signal", "victory", "neon", "revival");
    /** The fonts the panel loads. */
    public static final List<String> FONTS = list("Inter", "DM Sans", "Outfit", "Syne", "Fraunces", "IBM Plex Mono",
            "Noto Sans JP", "Shippori Mincho");
    /**
     * The 16 color slots every theme has, in the order of the file. The last, {@code decoExtra} (the extra decoration
     * color, {@code #rrggbb} only), came later: a theme read without it gets its starting theme's own ({@link ThemePieces}).
     */
    public static final List<String> COLOR_SLOTS = list("bgPrimary", "bgSecondary", "bgCard", "bgElevated", "bgHover",
            "accent", "accentLight", "accent2", "textPrimary", "textSecondary", "textTertiary", "border", "borderLight",
            "success", "glass", "decoExtra");
    /** The 15 color slots of the mini-player version: all of them except {@code glass}. */
    public static final List<String> MINI_SLOTS = without(COLOR_SLOTS, "glass");
    /** How a background picture fills the screen. */
    public static final List<String> FITS = list("cover", "contain", "center");

    private static final List<String> VISIBILITIES = list(PUBLIC, PRIVATE);

    private static final int MAX_PERSON_ID = 64;
    private static final int MAX_PERSON_NAME = 100;
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 12;
    /** What a theme id looks like; the store only builds a file path from an id that matches it. */
    static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]{12}");
    private static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    /** The extra decoration color is never see-through. */
    private static final String DECO_EXTRA = "decoExtra";
    private static final Pattern OPAQUE_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}");
    /** What a stored picture's name looks like: 32 lowercase hex characters, a dot and the extension. {@link ThemeImages} uses it too. */
    static final Pattern IMAGE_PATTERN = Pattern.compile("[0-9a-f]{32}\\.(jpg|png|webp|gif)");
    private static final Map<String, String> SLOT_LABELS = slotLabels();

    private static final String NAME_LENGTH = "Name must be 1 to " + MAX_NAME + " characters";
    private static final String NAME_CONTROL = "Name can't contain control characters";
    private static final String VISIBILITY = "Visibility must be public or private";
    private static final String BASE = "Starting theme must be one of the built-in themes";
    private static final String FONT = "Font must be one of the fonts offered";
    private static final String DARKEN = "Background darken";
    private static final String PIECES = "Decorations must be a list of piece names";

    private String id;
    private String name;
    private Person owner;
    private String visibility;
    private boolean hiddenByOwner;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
    private String base;
    private List<String> decorationsOff = Collections.emptyList();
    private Map<String, String> colors;
    private int radiusScale = 100;
    private String font;
    private Background background;
    private Mini mini;

    public String getId()
    {
        return id;
    }

    /** The theme id: 12 characters from {@code [a-z0-9]}. */
    public void setId(String id)
    {
        if(id == null || !ID_PATTERN.matcher(id).matches())
            throw new IllegalArgumentException("Not a theme id");
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    /** The name is trimmed and must be 1 to {@link #MAX_NAME} code points without control characters. */
    public void setName(String name)
    {
        this.name = checkName(name);
    }

    public Person getOwner()
    {
        return owner;
    }

    public void setOwner(Person owner)
    {
        this.owner = owner;
    }

    public String getVisibility()
    {
        return visibility;
    }

    /** Exactly {@link #PUBLIC} or {@link #PRIVATE}. */
    public void setVisibility(String visibility)
    {
        this.visibility = choice(visibility, VISIBILITIES, VISIBILITY);
    }

    /** True when the bot owner hid this theme: it is private to its creator, who can't make it public again. */
    public boolean isHiddenByOwner()
    {
        return hiddenByOwner;
    }

    public void setHiddenByOwner(boolean hiddenByOwner)
    {
        this.hiddenByOwner = hiddenByOwner;
    }

    public long getVersion()
    {
        return version;
    }

    public void setVersion(long version)
    {
        this.version = version;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt)
    {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt)
    {
        this.updatedAt = updatedAt;
    }

    public String getBase()
    {
        return base;
    }

    /** One of {@link #BASES}. Pieces switched off that the new base doesn't have are dropped. */
    public void setBase(String base)
    {
        this.base = choice(base, BASES, BASE);
        decorationsOff = ownPieces(decorationsOff, this.base);
    }

    /**
     * The ids of the starting theme's decoration pieces that are switched off ({@link ThemePieces}): each one once, in
     * the order they were given; read-only. Empty when every piece is on.
     */
    public List<String> getDecorationsOff()
    {
        return decorationsOff;
    }

    /**
     * Keeps only ids the starting theme has (others are dropped), each once, in the order given; null is none. The
     * starting theme must be set first ({@link IllegalStateException}). A background picture doesn't change the list:
     * the panel switches a picture theme's background pieces off when it shows it.
     */
    public void setDecorationsOff(List<String> ids)
    {
        if(base == null)
            throw new IllegalStateException("The starting theme comes before its pieces");
        decorationsOff = ownPieces(ids, base);
    }

    /** The 16 colors by slot, in {@link #COLOR_SLOTS} order, lower-cased; a read-only view. */
    public Map<String, String> getColors()
    {
        return colors;
    }

    /**
     * Needs exactly the {@link #COLOR_SLOTS}, each {@code #rrggbb} or {@code #rrggbbaa} ({@code decoExtra}
     * {@code #rrggbb} only); stored lower-cased.
     */
    public void setColors(Map<String, String> colors)
    {
        this.colors = checkColors(colors, COLOR_SLOTS, "");
    }

    public int getRadiusScale()
    {
        return radiusScale;
    }

    /** Percent, 0 to 200. */
    public void setRadiusScale(int radiusScale)
    {
        this.radiusScale = checkRange(radiusScale, 0, 200, "Corners");
    }

    public String getFont()
    {
        return font;
    }

    /** One of {@link #FONTS}. */
    public void setFont(String font)
    {
        this.font = choice(font, FONTS, FONT);
    }

    /** The background picture settings, or null for none. */
    public Background getBackground()
    {
        return background;
    }

    public void setBackground(Background background)
    {
        this.background = background;
    }

    /** The mini-player version, or null when the theme isn't offered in the mini-player. */
    public Mini getMini()
    {
        return mini;
    }

    public void setMini(Mini mini)
    {
        this.mini = mini;
    }

    /**
     * Parses file text; throws {@link IllegalArgumentException} when it is not a format 1 theme or any
     * value is not allowed. Optional: {@code hiddenByOwner}, {@code decorationsOff}, {@code effects},
     * {@code colors.decoExtra}, {@code version}, {@code createdAt}, {@code updatedAt}, {@code background},
     * {@code mini} and its {@code colors.decoExtra} (flags default to false). A theme saved before the pieces is
     * converted as it is read (see {@link #valuesFromClient}); {@link #toJson} writes the new form.
     */
    public static ThemeFile fromJson(String text)
    {
        if(text == null)
            throw new IllegalArgumentException("No theme text");
        JSONObject root;
        try
        {
            root = new JSONObject(text);
        }
        catch(JSONException e)
        {
            throw new IllegalArgumentException("Not a theme file: " + e.getMessage(), e);
        }
        Long format = integral(root.opt("format"));
        if(format == null || format != FORMAT)
            throw new IllegalArgumentException("Unsupported theme format");

        ThemeFile file = new ThemeFile();
        file.setId(textOf(root.opt("id")));
        file.readEditableValues(root, true);
        file.setOwner(readPerson(root.opt("owner")));
        file.setHiddenByOwner(flag(root.opt("hiddenByOwner"), "Hidden by owner"));
        Object version = root.opt("version");
        if(version != null)
        {
            Long number = integral(version);
            if(number == null || number < 0)
                throw new IllegalArgumentException("Version must be a whole number");
            file.setVersion(number);
        }
        file.setCreatedAt(instant(root.opt("createdAt"), "Created"));
        file.setUpdatedAt(instant(root.opt("updatedAt"), "Updated"));
        return file;
    }

    /**
     * Reads only the values a browser may set: name, visibility, base, decorationsOff, colors, radiusScale, font,
     * background (dim, blur, fit, but never the picture) and mini. Id, owner, version, timestamps,
     * hiddenByOwner and any picture name in the input are ignored. Throws {@link IllegalArgumentException}
     * with a message for the user that names the field.
     * <p>
     * Older panels and files still read: without {@code decorationsOff} the {@code effects} flag says which pieces
     * are off ({@code false}: all of the starting theme's; {@code true} or absent: none), and colors (main or
     * mini-player) without {@code decoExtra} get the starting theme's own.
     */
    public static ThemeFile valuesFromClient(JSONObject json)
    {
        if(json == null)
            throw new IllegalArgumentException("No theme values");
        ThemeFile values = new ThemeFile();
        values.readEditableValues(json, false);
        return values;
    }

    /** Sets everything an editor may change from {@code json}; {@code withImage} says whether the background has a picture name. */
    private void readEditableValues(JSONObject json, boolean withImage)
    {
        setName(textOf(json.opt("name")));
        setVisibility(textOf(json.opt("visibility")));
        setBase(textOf(json.opt("base")));
        Object effects = json.opt("effects");
        boolean effectsOn = effects == null || flag(effects, "Special effects");
        List<String> off = readPieceIds(json.opt("decorationsOff"));
        setDecorationsOff(off != null ? off : (effectsOn ? null : ThemePieces.ids(base)));
        setColors(colorsFrom(json.opt("colors"), COLOR_SLOTS, "", base));
        setRadiusScale(checkRange(json.opt("radiusScale"), 0, 200, "Corners"));
        setFont(textOf(json.opt("font")));
        setBackground(readBackground(json.opt("background"), withImage));
        setMini(readMini(json.opt("mini"), base));
    }

    /** The file text: pretty printed with 2-space indent. Throws {@link IllegalStateException} for an unfinished theme. */
    public String toJson()
    {
        if(id == null || name == null || owner == null || visibility == null || base == null || colors == null || font == null)
            throw new IllegalStateException("The theme is not complete");
        if(background != null && background.getImage() == null)
            throw new IllegalStateException("The background has no picture");
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("id", id);
        root.put("name", name);
        root.put("owner", new JSONObject().put("id", owner.getId()).put("name", owner.getName()));
        root.put("visibility", visibility);
        root.put("hiddenByOwner", hiddenByOwner);
        root.put("version", version);
        if(createdAt != null)
            root.put("createdAt", createdAt.toString());
        if(updatedAt != null)
            root.put("updatedAt", updatedAt.toString());
        root.put("base", base);
        root.put("decorationsOff", new JSONArray(decorationsOff));
        root.put("colors", new JSONObject(colors));
        root.put("radiusScale", radiusScale);
        root.put("font", font);
        if(background != null)
        {
            root.put("background", new JSONObject().put("image", background.getImage()).put("dim", background.getDim())
                    .put("blur", background.getBlur()).put("fit", background.getFit()));
        }
        if(mini != null)
        {
            root.put("mini", new JSONObject().put("colors", new JSONObject(mini.getColors()))
                    .put("useImage", mini.isUseImage()).put("dim", mini.getDim()));
        }
        return root.toString(2);
    }

    /** A fresh random theme id: 12 characters from {@code [a-z0-9]}. */
    public static String newId(Random random)
    {
        char[] id = new char[ID_LENGTH];
        for(int i = 0; i < ID_LENGTH; i++)
            id[i] = ID_CHARS.charAt(random.nextInt(ID_CHARS.length()));
        return new String(id);
    }

    private static Background readBackground(Object raw, boolean withImage)
    {
        if(raw == null || JSONObject.NULL.equals(raw))
            return null;
        if(!(raw instanceof JSONObject))
            throw new IllegalArgumentException("Background settings are not valid");
        JSONObject json = (JSONObject) raw;
        String image = null;
        if(withImage)
        {
            Object stored = json.opt("image");
            if(!(stored instanceof String))
                throw new IllegalArgumentException("Background picture is missing");
            image = (String) stored;
        }
        return new Background(image, checkRange(json.opt("dim"), 0, 90, DARKEN), checkRange(json.opt("blur"), 0, 20, "Background blur"),
                textOf(json.opt("fit")));
    }

    private static Mini readMini(Object raw, String base)
    {
        if(raw == null || JSONObject.NULL.equals(raw))
            return null;
        if(!(raw instanceof JSONObject))
            throw new IllegalArgumentException("Mini-player settings are not valid");
        JSONObject json = (JSONObject) raw;
        return new Mini(colorsFrom(json.opt("colors"), MINI_SLOTS, "Mini-player ", base),
                flag(json.opt("useImage"), "Mini-player picture setting"), checkRange(json.opt("dim"), 0, 90, "Mini-player darken"));
    }

    /** The ids in a {@code decorationsOff} list (as given: {@link #ownPieces} sorts them out), or null when there is no list. */
    private static List<String> readPieceIds(Object raw)
    {
        if(raw == null || JSONObject.NULL.equals(raw))
            return null;
        if(!(raw instanceof JSONArray))
            throw new IllegalArgumentException(PIECES);
        List<String> ids = new ArrayList<>();
        for(Object id : (JSONArray) raw)
        {
            if(!(id instanceof String))
                throw new IllegalArgumentException(PIECES);
            ids.add((String) id);
        }
        return ids;
    }

    /** The ids that are pieces of {@code base}, each once, in the order given; read-only. */
    private static List<String> ownPieces(Collection<String> ids, String base)
    {
        if(ids == null || ids.isEmpty())
            return Collections.emptyList();
        List<String> own = ThemePieces.ids(base);
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for(String id : ids)
        {
            if(own.contains(id))
                kept.add(id);
        }
        return Collections.unmodifiableList(new ArrayList<>(kept));
    }

    private static Person readPerson(Object raw)
    {
        if(!(raw instanceof JSONObject))
            throw new IllegalArgumentException("Owner is missing");
        JSONObject json = (JSONObject) raw;
        Object name = json.opt("name");
        if(name != null && !(name instanceof String))
            throw new IllegalArgumentException("Owner name must be text");
        return new Person(textOf(json.opt("id")), (String) name);
    }

    /** {@code raw} as a name: trimmed, 1 to {@link #MAX_NAME} code points, no control characters, no lone surrogates. */
    private static String checkName(Object raw)
    {
        if(!(raw instanceof String))
            throw new IllegalArgumentException(NAME_LENGTH);
        String name = ((String) raw).strip();
        int length = name.codePointCount(0, name.length());
        if(length < 1 || length > MAX_NAME)
            throw new IllegalArgumentException(NAME_LENGTH);
        for(int i = 0; i < name.length(); i += Character.charCount(name.codePointAt(i)))
        {
            int type = Character.getType(name.codePointAt(i));
            if(type == Character.CONTROL || type == Character.SURROGATE)
                throw new IllegalArgumentException(NAME_CONTROL);
        }
        return name;
    }

    /** {@code raw} when it is text, otherwise null. */
    private static String textOf(Object raw)
    {
        return raw instanceof String ? (String) raw : null;
    }

    private static String choice(Object raw, List<String> allowed, String message)
    {
        if(raw instanceof String && allowed.contains(raw))
            return (String) raw;
        throw new IllegalArgumentException(message);
    }

    /** A boolean flag; an absent one is false. */
    private static boolean flag(Object raw, String label)
    {
        if(raw == null)
            return false;
        if(raw instanceof Boolean)
            return (Boolean) raw;
        throw new IllegalArgumentException(label + " must be true or false");
    }

    private static int checkRange(Object raw, int min, int max, String label)
    {
        Long number = integral(raw);
        if(number == null || number < min || number > max)
            throw new IllegalArgumentException(label + " must be a whole number from " + min + " to " + max);
        return (int) (long) number;
    }

    /** {@code raw} as a long when it is a JSON integer (not a decimal, text or boolean); otherwise null. */
    private static Long integral(Object raw)
    {
        if(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)
            return ((Number) raw).longValue();
        if(raw instanceof BigInteger && ((BigInteger) raw).bitLength() < 64)
            return ((BigInteger) raw).longValue();
        return null;
    }

    private static Instant instant(Object raw, String label)
    {
        if(raw == null)
            return null;
        try
        {
            if(raw instanceof String)
                return Instant.parse((String) raw);
        }
        catch(DateTimeParseException e)
        {
            // falls through to the message below
        }
        throw new IllegalArgumentException(label + " must be a date and time");
    }

    /** Colors as read: without {@code decoExtra} they get the starting theme's own. */
    private static Map<String, String> colorsFrom(Object raw, List<String> slots, String prefix, String base)
    {
        if(!(raw instanceof JSONObject))
            return checkColors(null, slots, prefix);
        JSONObject json = (JSONObject) raw;
        Map<String, Object> given = new LinkedHashMap<>();
        for(String key : json.keySet())
            given.put(key, json.get(key));
        if(!given.containsKey(DECO_EXTRA))
            given.put(DECO_EXTRA, ThemePieces.decoExtra(base));
        return checkColors(given, slots, prefix);
    }

    /**
     * A read-only, ordered, lower-cased copy of {@code given}, which must have exactly {@code slots} with valid colors
     * ({@code decoExtra} without alpha).
     */
    private static Map<String, String> checkColors(Map<String, ?> given, List<String> slots, String prefix)
    {
        if(given == null)
            throw new IllegalArgumentException(prefix.isEmpty() ? "Colors are missing" : "Mini-player colors are missing");
        for(String key : given.keySet())
        {
            if(!slots.contains(key))
                throw new IllegalArgumentException((prefix.isEmpty() ? "Unknown color: " : "Unknown mini-player color: ") + shorten(key));
        }
        Map<String, String> checked = new LinkedHashMap<>();
        for(String slot : slots)
        {
            String label = slotLabel(slot, prefix);
            if(!given.containsKey(slot))
                throw new IllegalArgumentException(label + " is missing");
            Object color = given.get(slot);
            Pattern allowed = DECO_EXTRA.equals(slot) ? OPAQUE_COLOR_PATTERN : COLOR_PATTERN;
            if(!(color instanceof String) || !allowed.matcher((String) color).matches())
                throw new IllegalArgumentException(label + " must be a color like #7c5cff");
            checked.put(slot, ((String) color).toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableMap(checked);
    }

    /** The plain name of a color slot; with a prefix ("Mini-player ") it continues in lower case. */
    private static String slotLabel(String slot, String prefix)
    {
        String label = SLOT_LABELS.get(slot);
        return prefix.isEmpty() ? label : prefix + label.toLowerCase(Locale.ROOT);
    }

    private static String shorten(Object text)
    {
        String shown = String.valueOf(text);
        return shown.length() <= 30 ? shown : shown.substring(0, 30) + "...";
    }

    private static List<String> list(String... values)
    {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static List<String> without(List<String> values, String left)
    {
        List<String> kept = new ArrayList<>(values);
        kept.remove(left);
        return Collections.unmodifiableList(kept);
    }

    private static Map<String, String> slotLabels()
    {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("bgPrimary", "Background");
        labels.put("bgSecondary", "Panels");
        labels.put("bgCard", "Cards");
        labels.put("bgElevated", "Raised panels");
        labels.put("bgHover", "Hover");
        labels.put("accent", "Main color");
        labels.put("accentLight", "Light main color");
        labels.put("accent2", "Second color");
        labels.put("textPrimary", "Main text");
        labels.put("textSecondary", "Secondary text");
        labels.put("textTertiary", "Faint text");
        labels.put("border", "Borders");
        labels.put("borderLight", "Light borders");
        labels.put("success", "Success color");
        labels.put("glass", "Glass");
        labels.put(DECO_EXTRA, "Extra decoration color");
        return Collections.unmodifiableMap(labels);
    }

    /** A Discord user: id and display name. */
    public static final class Person
    {
        private final String id;
        private final String name;

        /** The id must be 1 to 64 characters; a missing name becomes empty and a long one is cut. */
        public Person(String id, String name)
        {
            if(id == null || id.isEmpty() || id.length() > MAX_PERSON_ID)
                throw new IllegalArgumentException("Owner id is not valid");
            this.id = id;
            this.name = cut(name);
        }

        public String getId()
        {
            return id;
        }

        public String getName()
        {
            return name;
        }

        private static String cut(String text)
        {
            if(text == null)
                return "";
            if(text.length() <= MAX_PERSON_NAME)
                return text;
            // never leave half of a surrogate pair at the end
            int end = Character.isHighSurrogate(text.charAt(MAX_PERSON_NAME - 1)) ? MAX_PERSON_NAME - 1 : MAX_PERSON_NAME;
            return text.substring(0, end);
        }
    }

    /** The optional background picture settings. Immutable; {@link #withImage} attaches the picture name. */
    public static final class Background
    {
        private final String image;
        private final int dim;
        private final int blur;
        private final String fit;

        /** {@code image} is a stored picture name, or null while the picture has not been saved yet; dim 0 to 90, blur 0 to 20. */
        public Background(String image, int dim, int blur, String fit)
        {
            if(image != null && !IMAGE_PATTERN.matcher(image).matches())
                throw new IllegalArgumentException("Background picture name is not valid");
            this.image = image;
            this.dim = checkRange(dim, 0, 90, DARKEN);
            this.blur = checkRange(blur, 0, 20, "Background blur");
            this.fit = choice(fit, FITS, "Background fit must be cover, contain or center");
        }

        /** The stored picture name ({@code <32 hex>.<jpg|png|webp|gif>}), or null before the picture is saved. */
        public String getImage()
        {
            return image;
        }

        /** How much the picture is darkened, in percent. */
        public int getDim()
        {
            return dim;
        }

        public int getBlur()
        {
            return blur;
        }

        /** {@code cover}, {@code contain} or {@code center}. */
        public String getFit()
        {
            return fit;
        }

        /** A copy with another picture name. */
        public Background withImage(String image)
        {
            return new Background(image, dim, blur, fit);
        }
    }

    /** The optional mini-player version: the 15 {@link #MINI_SLOTS} colors, whether it shows the picture, and how dark. Immutable. */
    public static final class Mini
    {
        private final Map<String, String> colors;
        private final boolean useImage;
        private final int dim;

        public Mini(Map<String, String> colors, boolean useImage, int dim)
        {
            this.colors = checkColors(colors, MINI_SLOTS, "Mini-player ");
            this.useImage = useImage;
            this.dim = checkRange(dim, 0, 90, "Mini-player darken");
        }

        /** The 15 colors by slot, in {@link #MINI_SLOTS} order, lower-cased; a read-only view. */
        public Map<String, String> getColors()
        {
            return colors;
        }

        public boolean isUseImage()
        {
            return useImage;
        }

        /** How much the picture is darkened, in percent. */
        public int getDim()
        {
            return dim;
        }
    }
}
