/*
 * The logic of the guided theme creator, apart from the page: the six steps, the state the creator keeps, the
 * choices that change it (colors, fine-tuning), what is checked (readability, a visible main color, pictures,
 * names), the values that are saved and the preview that is shown, and what to say when saving fails.
 * Pure functions on plain objects, no DOM: every function returns a new state and leaves the one it was given alone.
 * Loaded by the panel as a classic script (window.ThemeCreator, after theme-palette.js and theme-apply.js) and by
 * Node for tests.
 *
 * Colors are lower case "#rrggbb" ("#rrggbbaa" for the see-through ones). Nothing here builds CSS text.
 */
(function (root, factory) {
    var isNode = typeof module === 'object' && module && module.exports;
    if (!isNode && !(root.ThemePalette && root.ThemeApply)) return; // the panel then has no creator, and says so
    var api = factory(isNode ? require('./theme-palette.js') : root.ThemePalette,
        isNode ? require('./theme-apply.js') : root.ThemeApply);
    if (isNode) module.exports = api;
    else root.ThemeCreator = api;
}(typeof window !== 'undefined' ? window : this, function (palette, apply) {
    'use strict';

    var DASH = String.fromCharCode(0x2014);
    var CHECK = String.fromCharCode(0x2713);

    var MAX_NAME = 40;
    var MAX_PICTURE_BYTES = 8 * 1024 * 1024;
    var FITS = Object.freeze(['cover', 'contain', 'center']);
    var FONTS = Object.freeze(Object.keys(apply.FONT_STACKS));
    /* The darkening a picture starts with until its brightness is known (what suggestDim gives for "unknown"). */
    var DEFAULT_DIM = 40;
    /* The id the live preview wears: a well-formed theme id that no real theme has. */
    var PREVIEW_ID = 'themepreview';
    var PREVIEW_NAME = 'Your new theme';
    /* A main color has to stand out from the page at least this much to count as visible, and a clearer one a bit more. */
    var ACCENT_BAR = 3;
    var ACCENT_BAR_SUGGESTED = 3.2;

    /*
     * The 17 built-in themes as a starting point: the label as in the picker, the font, and the 15 color slots as the
     * stylesheet gives them (a test keeps this table equal to the stylesheet). Colors that are see-through carry their
     * alpha as the last two digits.
     */
    var BASE_PALETTES = deepFreeze({
        nebula: { label: 'Nebula', font: 'Inter', colors: {
            bgPrimary: '#0b0d17', bgSecondary: '#121427', bgCard: '#181b2e', bgElevated: '#1e2240', bgHover: '#252a4a',
            accent: '#7c5cfc', accentLight: '#9b82fc', accent2: '#e04cf0',
            textPrimary: '#eef0f6', textSecondary: '#7a7f9a', textTertiary: '#4e5270',
            border: '#ffffff0f', borderLight: '#ffffff1a', success: '#34d399', glass: '#181b2ed9'
        } },
        aurora: { label: 'Aurora', font: 'Inter', colors: {
            bgPrimary: '#0a1114', bgSecondary: '#0f181c', bgCard: '#152228', bgElevated: '#1a2e35', bgHover: '#23424d',
            accent: '#2dd4bf', accentLight: '#5eead4', accent2: '#22d3ee',
            textPrimary: '#ecf8f6', textSecondary: '#7a9d9a', textTertiary: '#4a6b67',
            border: '#ffffff12', borderLight: '#ffffff1f', success: '#34d399', glass: '#0f181ce0'
        } },
        ember: { label: 'Ember', font: 'Inter', colors: {
            bgPrimary: '#140c0a', bgSecondary: '#1a100c', bgCard: '#241815', bgElevated: '#2e1f18', bgHover: '#3d2a22',
            accent: '#f97316', accentLight: '#fb923c', accent2: '#ef4444',
            textPrimary: '#faf5f0', textSecondary: '#b8a090', textTertiary: '#7a6558',
            border: '#ffffff12', borderLight: '#ffffff1f', success: '#4ade80', glass: '#1a100ce0'
        } },
        crimson: { label: 'Crimson', font: 'Inter', colors: {
            bgPrimary: '#0f0506', bgSecondary: '#160809', bgCard: '#1f0c0e', bgElevated: '#2a1013', bgHover: '#3a161a',
            accent: '#f87171', accentLight: '#fca5a5', accent2: '#fb7185',
            textPrimary: '#fef2f2', textSecondary: '#c4a3a3', textTertiary: '#8f6668',
            border: '#f871711a', borderLight: '#fca5a529', success: '#4ade80', glass: '#16080ae6'
        } },
        dawn: { label: 'Dawn', font: 'DM Sans', colors: {
            bgPrimary: '#f4f2ef', bgSecondary: '#eae6e0', bgCard: '#ffffff', bgElevated: '#f0ece6', bgHover: '#e2dcd4',
            accent: '#6d28d9', accentLight: '#7c3aed', accent2: '#db2777',
            textPrimary: '#1c1917', textSecondary: '#57534e', textTertiary: '#78716c',
            border: '#00000014', borderLight: '#0000001f', success: '#059669', glass: '#ffffffc7'
        } },
        'dawn-dark': { label: 'Dawn Dark', font: 'DM Sans', colors: {
            bgPrimary: '#050403', bgSecondary: '#080706', bgCard: '#12100e', bgElevated: '#0c0b09', bgHover: '#1c1a17',
            accent: '#8b5cf6', accentLight: '#c4b5fd', accent2: '#db2777',
            textPrimary: '#fafaf9', textSecondary: '#b5aea8', textTertiary: '#78716c',
            border: '#ffffff0e', borderLight: '#ffffff1a', success: '#34d399', glass: '#050403e6'
        } },
        steel: { label: 'Steel', font: 'Inter', colors: {
            bgPrimary: '#0c0f14', bgSecondary: '#121620', bgCard: '#181e2a', bgElevated: '#1e2635', bgHover: '#283042',
            accent: '#38bdf8', accentLight: '#7dd3fc', accent2: '#818cf8',
            textPrimary: '#f1f5f9', textSecondary: '#94a3b8', textTertiary: '#64748b',
            border: '#ffffff0f', borderLight: '#ffffff1c', success: '#34d399', glass: '#121620db'
        } },
        verdant: { label: 'Verdant', font: 'Inter', colors: {
            bgPrimary: '#0a0f0c', bgSecondary: '#0f1812', bgCard: '#152019', bgElevated: '#1a2d22', bgHover: '#243a2d',
            accent: '#34d399', accentLight: '#6ee7b7', accent2: '#a3e635',
            textPrimary: '#ecfdf5', textSecondary: '#86a89a', textTertiary: '#527a6a',
            border: '#ffffff0f', borderLight: '#ffffff1c', success: '#4ade80', glass: '#0f1812e0'
        } },
        grove: { label: 'Grove', font: 'Inter', colors: {
            bgPrimary: '#171b1f', bgSecondary: '#1e2329', bgCard: '#252b33', bgElevated: '#2f3640', bgHover: '#3a4350',
            accent: '#34d399', accentLight: '#6ee7b7', accent2: '#94a3b8',
            textPrimary: '#f1f5f9', textSecondary: '#94a3b8', textTertiary: '#64748b',
            border: '#94a3b81a', borderLight: '#94a3b833', success: '#4ade80', glass: '#1e2329e6'
        } },
        grapevine: { label: 'Grapevine', font: 'Inter', colors: {
            bgPrimary: '#0c0a12', bgSecondary: '#110e1a', bgCard: '#181528', bgElevated: '#221d34', bgHover: '#2e2645',
            accent: '#a78bfa', accentLight: '#c4b5fd', accent2: '#22c55e',
            textPrimary: '#f3f0ff', textSecondary: '#a8a0c8', textTertiary: '#6b6390',
            border: '#a78bfa17', borderLight: '#4ade8024', success: '#4ade80', glass: '#120d1ce6'
        } },
        sumi: { label: 'Sumi', font: 'Noto Sans JP', colors: {
            bgPrimary: '#060708', bgSecondary: '#0a0c0f', bgCard: '#101318', bgElevated: '#0c0e12', bgHover: '#1a1f26',
            accent: '#c42b20', accentLight: '#e85d52', accent2: '#1a2f42',
            textPrimary: '#f2ebe3', textSecondary: '#a39e96', textTertiary: '#6f6a64',
            border: '#f2ebe30e', borderLight: '#c9a2271f', success: '#6d9f7b', glass: '#060708eb'
        } },
        rasta: { label: 'Rasta', font: 'Outfit', colors: {
            bgPrimary: '#000000', bgSecondary: '#000000', bgCard: '#121812', bgElevated: '#182018', bgHover: '#263026',
            accent: '#22c55e', accentLight: '#86efac', accent2: '#eab308',
            textPrimary: '#ecfdf4', textSecondary: '#9dc7a4', textTertiary: '#5e8a66',
            border: '#22c55e24', borderLight: '#eab30838', success: '#4ade80', glass: '#0a0e0ae0'
        } },
        cipher: { label: 'Cipher', font: 'IBM Plex Mono', colors: {
            bgPrimary: '#09070f', bgSecondary: '#100c1a', bgCard: '#171225', bgElevated: '#211a33', bgHover: '#2c2344',
            accent: '#8b5cf6', accentLight: '#c4b5fd', accent2: '#d946ef',
            textPrimary: '#f3eeff', textSecondary: '#a396c4', textTertiary: '#6b5f8a',
            border: '#8b5cf61f', borderLight: '#d946ef2e', success: '#34d399', glass: '#100c1ae6'
        } },
        signal: { label: 'Signal', font: 'Inter', colors: {
            bgPrimary: '#050a0c', bgSecondary: '#0a1215', bgCard: '#0f1a1e', bgElevated: '#152429', bgHover: '#1c333a',
            accent: '#22d3ee', accentLight: '#67e8f9', accent2: '#4ade80',
            textPrimary: '#e8fbff', textSecondary: '#7aadb8', textTertiary: '#4a7580',
            border: '#22d3ee1f', borderLight: '#67e8f933', success: '#4ade80', glass: '#0a1215eb'
        } },
        victory: { label: 'Victory', font: 'Syne', colors: {
            bgPrimary: '#0e0c08', bgSecondary: '#16130c', bgCard: '#1f1a12', bgElevated: '#2a2318', bgHover: '#3a3020',
            accent: '#eab308', accentLight: '#fde047', accent2: '#f59e0b',
            textPrimary: '#fffbeb', textSecondary: '#c4b48a', textTertiary: '#8a7a52',
            border: '#eab3081f', borderLight: '#fde04733', success: '#84cc16', glass: '#16130ce6'
        } },
        neon: { label: 'Neon', font: 'Outfit', colors: {
            bgPrimary: '#12081c', bgSecondary: '#1a0f28', bgCard: '#221436', bgElevated: '#2c1a45', bgHover: '#3a2458',
            accent: '#a3e635', accentLight: '#d9f99d', accent2: '#c084fc',
            textPrimary: '#ffffff', textSecondary: '#e8e0f2', textTertiary: '#b9a8d0',
            border: '#a3e63524', borderLight: '#c084fc38', success: '#a3e635', glass: '#1a0f28e6'
        } },
        revival: { label: 'Revival', font: 'Inter', colors: {
            bgPrimary: '#14100d', bgSecondary: '#1b1612', bgCard: '#211b17', bgElevated: '#2b231e', bgHover: '#372d26',
            accent: '#e2825c', accentLight: '#f2a882', accent2: '#f2b35e',
            textPrimary: '#f6efe5', textSecondary: '#cdbcaa', textTertiary: '#a08f7e',
            border: '#f2b35e1a', borderLight: '#f2b35e33', success: '#b4d48a', glass: '#181310d1'
        } }
    });

    /* The built-in themes that have decorative layers (flames, tiles, header strips...) that "plain" switches off. */
    var DECORATED_BASES = Object.freeze(['ember', 'steel', 'grove', 'sumi', 'rasta', 'dawn-dark', 'cipher', 'signal',
        'victory', 'neon', 'revival']);

    function hasDecorations(base) {
        return typeof base === 'string' && DECORATED_BASES.indexOf(base) >= 0;
    }

    function deepFreeze(value) {
        Object.keys(value).forEach(function (key) {
            if (value[key] && typeof value[key] === 'object') deepFreeze(value[key]);
        });
        return Object.freeze(value);
    }

    function isBase(id) {
        return typeof id === 'string' && Object.prototype.hasOwnProperty.call(BASE_PALETTES, id);
    }

    /* ---- the words ---- */

    var TEXT = Object.freeze({
        READABLE: 'Readable ' + CHECK,
        NOT_READABLE: 'Some text will be hard to read',
        TEXT_APART: "Text can't be readable on both your Background and Cards " + DASH + ' make them closer',
        ACCENT_HARD_TO_SEE: 'Your main color is hard to see on this background',
        NOT_A_PICTURE: "That file isn't a picture we can use (JPG, PNG, WebP or GIF)",
        PICTURE_TOO_BIG: 'Pictures can be up to ' + (MAX_PICTURE_BYTES >> 20) + ' MB',
        NAME_MISSING: 'Give your theme a name',
        NAME_TOO_LONG: 'Names can be up to ' + MAX_NAME + ' characters',
        NAME_CONTROL: "Names can't have control characters",
        CHANGED: 'This theme changed ' + DASH + ' reload it?',
        GONE: "This theme was deleted, so your changes can't be saved over it. You can save them as a new theme instead.",
        GONE_PICTURE: ' Its picture went with it, so the new theme will have no picture unless you choose one again.',
        SAVE_FAILED: "Couldn't save " + DASH + ' try again',
        DIDNT_WORK: "That didn't work " + DASH + ' check your choices and try again'
    });

    /* ---- the steps ---- */

    var STEPS = Object.freeze([
        Object.freeze({ id: 'start', title: 'Start from' }),
        Object.freeze({ id: 'colors', title: 'Colors' }),
        Object.freeze({ id: 'picture', title: 'Background picture' }),
        Object.freeze({ id: 'tweak', title: 'Fine-tune' }),
        Object.freeze({ id: 'mini', title: 'Mini-player' }),
        Object.freeze({ id: 'share', title: 'Name and share' })
    ]);
    var STEP = Object.freeze({ START: 0, COLORS: 1, PICTURE: 2, TWEAK: 3, MINI: 4, SHARE: 5 });

    /* A new theme begins by choosing what to start from; an edit has all that behind it and opens on the colors. */
    function firstStep(editing) {
        return editing ? STEP.COLORS : STEP.START;
    }

    function clampStep(i) {
        var n = Math.floor(Number(i));
        return isFinite(n) ? Math.max(0, Math.min(STEPS.length - 1, n)) : 0;
    }

    function nextStep(i) {
        return clampStep(clampStep(i) + 1);
    }

    function prevStep(i) {
        return clampStep(clampStep(i) - 1);
    }

    function isFirstStep(i) {
        return clampStep(i) === 0;
    }

    function isLastStep(i) {
        return clampStep(i) === STEPS.length - 1;
    }

    /* The forward button: "Next" on every step, "Save theme" on the last. */
    function nextLabel(i, saving) {
        if (!isLastStep(i)) return 'Next';
        return saving ? 'Saving...' : 'Save theme';
    }

    /* ---- numbers and colors ---- */

    function clampInt(value, min, max, fallback) {
        var n = typeof value === 'number' ? value : (typeof value === 'string' && value.trim() !== '' ? Number(value) : NaN);
        if (!isFinite(n)) return fallback;
        return Math.max(min, Math.min(max, Math.round(n)));
    }

    var HEX6 = /^#[0-9a-f]{6}$/i;
    var HEX_ANY = /^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/i;

    function isHex6(value) {
        return typeof value === 'string' && HEX6.test(value);
    }

    /* "#rrggbb" of a "#rrggbb" or "#rrggbbaa" color, lower case. */
    function rgbOf(hex) {
        return hex.slice(0, 7).toLowerCase();
    }

    /* The two alpha digits of a "#rrggbbaa" color, '' for an opaque one. */
    function alphaOf(hex) {
        return typeof hex === 'string' && hex.length === 9 ? hex.slice(7).toLowerCase() : '';
    }

    /* A lower-case copy with exactly these slots; a slot that isn't a color takes the value from `fallback`. */
    function normalizeColors(colors, slots, fallback) {
        var out = {};
        for (var i = 0; i < slots.length; i++) {
            var c = colors && typeof colors === 'object' ? colors[slots[i]] : null;
            out[slots[i]] = typeof c === 'string' && HEX_ANY.test(c) ? c.toLowerCase() : fallback[slots[i]];
        }
        return out;
    }

    function copyOf(object) {
        var out = {};
        Object.keys(object).forEach(function (key) { out[key] = object[key]; });
        return out;
    }

    function hexByte(n) {
        var s = Math.max(0, Math.min(255, Math.round(n))).toString(16);
        return s.length < 2 ? '0' + s : s;
    }

    function hslToHex(h, s, l) {
        h = ((h % 360) + 360) % 360;
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        var c = (1 - Math.abs(2 * l - 1)) * s;
        var x = c * (1 - Math.abs((h / 60) % 2 - 1));
        var m = l - c / 2;
        var rgb;
        if (h < 60) rgb = [c, x, 0];
        else if (h < 120) rgb = [x, c, 0];
        else if (h < 180) rgb = [0, c, x];
        else if (h < 240) rgb = [0, x, c];
        else if (h < 300) rgb = [x, 0, c];
        else rgb = [c, 0, x];
        return '#' + hexByte((rgb[0] + m) * 255) + hexByte((rgb[1] + m) * 255) + hexByte((rgb[2] + m) * 255);
    }

    function hexToHsl(hex) {
        var r = parseInt(hex.substr(1, 2), 16) / 255, g = parseInt(hex.substr(3, 2), 16) / 255, b = parseInt(hex.substr(5, 2), 16) / 255;
        var max = Math.max(r, g, b), min = Math.min(r, g, b);
        var l = (max + min) / 2, d = max - min, h = 0, s = 0;
        if (d > 0) {
            s = Math.min(1, d / (1 - Math.abs(2 * l - 1)));
            if (max === r) h = ((g - b) / d) % 6;
            else if (max === g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h = (h * 60 + 360) % 360;
        }
        return { h: h, s: s, l: l };
    }

    /* 'dark' or 'light': which of white and black the color is further from. */
    function modeOf(hex) {
        return palette.contrast(hex, '#ffffff') > palette.contrast(hex, '#000000') ? 'dark' : 'light';
    }

    /* ---- the state ---- */

    /*
     * What the creator keeps:
     *   id, version, hiddenByOwner   the theme being edited (null/null/false for a new one)
     *   name, visibility             step 6
     *   base, effects, colors{15}, radiusScale, font   the look; mainColor, pageColor, mode are step 2's picks
     *   picture   'none' | 'saved' (the theme's own picture on the server) | 'new' (a file chosen here, sent on save)
     *   hadPicture, savedPictureUrl  whether the edited theme has a picture on the server, and its link
     *   dim, blur, fit               the picture's settings
     *   mini                         null or {colors{14}, useImage, dim}
     */
    function blankState() {
        return {
            id: null, version: null, hiddenByOwner: false, name: '', visibility: 'public',
            base: 'nebula', effects: true, colors: null, radiusScale: 100, font: 'Inter',
            mainColor: '', pageColor: '', mode: 'dark',
            picture: 'none', hadPicture: false, savedPictureUrl: '', dim: DEFAULT_DIM, blur: 0, fit: 'cover',
            mini: null
        };
    }

    function clone(state) {
        var next = copyOf(state);
        next.colors = state.colors ? copyOf(state.colors) : null;
        return next;
    }

    /* The fields that make up a look, from a base, its colors, font, effects and corners. */
    function lookFields(base, colors, font, effects, radiusScale) {
        var fixed = normalizeColors(colors, palette.SLOTS, BASE_PALETTES.nebula.colors);
        return {
            base: isBase(base) ? base : 'nebula',
            colors: fixed,
            font: FONTS.indexOf(font) >= 0 ? font : 'Inter',
            effects: effects === true,
            radiusScale: clampInt(radiusScale, 0, 200, 100),
            mainColor: rgbOf(fixed.accent),
            pageColor: rgbOf(fixed.bgPrimary),
            mode: modeOf(fixed.bgPrimary)
        };
    }

    function baseLook(id) {
        var base = isBase(id) ? id : 'nebula';
        return lookFields(base, BASE_PALETTES[base].colors, BASE_PALETTES[base].font, true, 100);
    }

    function themeLook(theme) {
        // a picture switched the effects off for good; without the picture they are back
        var effects = theme.background ? true : theme.effects !== false;
        return lookFields(theme.base, theme.colors, theme.font, effects, theme.radiusScale);
    }

    function withFields(state, fields) {
        var next = clone(state);
        Object.keys(fields).forEach(function (key) { next[key] = fields[key]; });
        return next;
    }

    /* A new theme starting from a built-in theme. */
    function startState(baseId) {
        return withFields(blankState(), baseLook(baseId));
    }

    /* A new theme starting from somebody's community theme: its look, but not its picture, name or mini-player version. */
    function startFromTheme(theme) {
        return withFields(blankState(), themeLook(theme));
    }

    /* The state for editing a theme of the list. */
    function fromTheme(theme) {
        var state = withFields(blankState(), lookFields(theme.base, theme.colors, theme.font, theme.effects === true, theme.radiusScale));
        var version = Number(theme.version);
        state.id = typeof theme.id === 'string' ? theme.id : null;
        state.version = isFinite(version) ? Math.floor(version) : 0;
        state.hiddenByOwner = theme.hiddenByOwner === true;
        state.name = typeof theme.name === 'string' ? theme.name : '';
        state.visibility = state.hiddenByOwner || theme.visibility === 'private' ? 'private' : 'public';
        var back = theme.background && typeof theme.background === 'object' ? theme.background : null;
        var url = back ? apply.pictureUrl(back.url) : null;
        if (url) {
            state.picture = 'saved';
            state.hadPicture = true;
            state.savedPictureUrl = url;
            state.dim = clampInt(back.dim, 0, 90, DEFAULT_DIM);
            state.blur = clampInt(back.blur, 0, 20, 0);
            state.fit = FITS.indexOf(back.fit) >= 0 ? back.fit : 'cover';
        }
        if (theme.mini && typeof theme.mini === 'object') {
            state.mini = {
                colors: normalizeColors(theme.mini.colors, palette.MINI_SLOTS, state.colors),
                useImage: theme.mini.useImage === true,
                dim: clampInt(theme.mini.dim, 0, 90, DEFAULT_DIM)
            };
        }
        return state;
    }

    /* The same work as a new theme: the old theme is gone, and its picture went with it. */
    function asNewTheme(state) {
        var next = clone(state);
        next.id = null;
        next.version = null;
        next.hiddenByOwner = false;
        next.hadPicture = false;
        next.savedPictureUrl = '';
        if (next.picture === 'saved') next.picture = 'none';
        return next;
    }

    /* Picking another starting point replaces the look and keeps everything else (picture, name, mini-player version). */
    function chooseBase(state, baseId) {
        return withFields(state, baseLook(baseId));
    }

    function chooseTheme(state, theme) {
        return withFields(state, themeLook(theme));
    }

    /* ---- step 2: the colors ---- */

    function withPair(state, main, page, mode) {
        var colors = palette.generate(main, page, mode);
        return withFields(state, { colors: colors, mainColor: rgbOf(colors.accent), pageColor: rgbOf(colors.bgPrimary), mode: mode });
    }

    function setMain(state, hex) {
        return isHex6(hex) ? withPair(state, hex.toLowerCase(), state.pageColor, state.mode) : state;
    }

    function setPage(state, hex) {
        return isHex6(hex) ? withPair(state, state.mainColor, hex.toLowerCase(), modeOf(hex)) : state;
    }

    /* Dark or Light: the first background suggested for the main color in that mode. */
    function setMode(state, mode) {
        var m = mode === 'light' ? 'light' : 'dark';
        return withPair(state, state.mainColor, palette.suggestBackgrounds(state.mainColor, m)[0], m);
    }

    /* "Surprise me": a random good pair (randomFn gives numbers from 0 up to, not including, 1). */
    function surpriseMe(state, randomFn) {
        var pick = palette.surprise(randomFn);
        return withPair(state, pick.accent, pick.background, pick.mode);
    }

    /* The 16 suggested main colors: a hue each, set to a lightness that shows up on the background. */
    var ACCENT_HUES = Object.freeze([
        ['Red', 0], ['Orange', 24], ['Amber', 40], ['Yellow', 52], ['Lime', 82], ['Green', 130], ['Emerald', 158], ['Teal', 176],
        ['Cyan', 190], ['Sky', 204], ['Blue', 222], ['Indigo', 242], ['Violet', 264], ['Purple', 284], ['Pink', 322], ['Rose', 346]
    ]);
    var ACCENT_NAMES = Object.freeze(ACCENT_HUES.map(function (entry) { return entry[0]; }));

    /* Does going lighter or going darker give a color more to stand out against this background? */
    function goesLighter(pageHex) {
        return palette.contrast('#ffffff', pageHex) >= palette.contrast('#000000', pageHex);
    }

    /* The color moved in lightness (same hue and saturation) until it reaches the bar against the page. */
    function standOut(hue, saturation, startLightness, pageHex) {
        var lighter = goesLighter(pageHex);
        var l = startLightness;
        var color = hslToHex(hue, saturation, l);
        for (var i = 0; i < 100 && palette.contrast(color, pageHex) < ACCENT_BAR_SUGGESTED; i++) {
            l += lighter ? 0.01 : -0.01;
            color = hslToHex(hue, saturation, l);
        }
        return color;
    }

    function accentSuggestions(pageHex) {
        var page = rgbOf(pageHex);
        var start = goesLighter(page) ? 0.62 : 0.44;
        return ACCENT_HUES.map(function (entry) { return standOut(entry[1], 0.78, start, page); });
    }

    /* A main color that shows up: this one when it already does, else the same hue moved in lightness until it does. */
    function clearerAccent(accentHex, pageHex) {
        var page = rgbOf(pageHex);
        if (palette.contrast(accentHex, page) >= ACCENT_BAR) return accentHex;
        var hsl = hexToHsl(rgbOf(accentHex));
        return standOut(hsl.h, hsl.s, hsl.l, page);
    }

    function accentVisible(colors) {
        return palette.contrast(colors.accent, colors.bgPrimary) >= ACCENT_BAR;
    }

    /* Are the texts readable, and can the main color be seen? */
    function report(colors) {
        var readable = palette.readability(colors);
        return { ok: readable.ok, problems: readable.problems, accentVisible: accentVisible(colors) };
    }

    /*
     * "Fix it": moves the text colors just far enough. When Cards and Background are so far apart that no text works
     * on both, the best it can do is not enough: farApart says so, with the words to use and the two colors to change.
     */
    function fixIt(state) {
        var fixed = palette.fixReadability(state.colors);
        var ok = palette.readability(fixed).ok;
        return {
            state: withFields(state, { colors: fixed }),
            ok: ok,
            farApart: !ok,
            highlight: ok ? [] : ['bgPrimary', 'bgCard'],
            message: ok ? '' : TEXT.TEXT_APART
        };
    }

    /* ---- step 4: fine-tuning ---- */

    /* The colors a person may set one by one, with the plain names the server uses too. Light borders and glass follow. */
    var COLOR_CHOICES = Object.freeze([
        ['bgPrimary', 'Background', 'The page behind everything'],
        ['bgSecondary', 'Panels', 'The top bar and the side areas'],
        ['bgCard', 'Cards', 'The boxes that hold the songs and lists'],
        ['bgElevated', 'Raised panels', 'Buttons, menus and inputs'],
        ['bgHover', 'Hover', 'What lights up under the mouse'],
        ['accent', 'Main color', 'Buttons, highlights and the progress bar'],
        ['accentLight', 'Light main color', 'Links and soft highlights'],
        ['accent2', 'Second color', 'The other end of the colorful gradients'],
        ['textPrimary', 'Main text', 'Titles and most of the words'],
        ['textSecondary', 'Secondary text', 'Smaller words, like artists'],
        ['textTertiary', 'Faint text', 'Hints and labels'],
        ['border', 'Borders', 'The thin lines around things'],
        ['success', 'Success color', 'Ticks and "done" messages']
    ].map(function (c) { return Object.freeze({ slot: c[0], label: c[1], hint: c[2] }); }));

    function isChoice(slot) {
        return COLOR_CHOICES.some(function (c) { return c.slot === slot; });
    }

    /* One color from a picker (plain #rrggbb). It keeps the slot's transparency; the colors made from it follow. */
    function tweakColor(state, slot, hex) {
        if (!isChoice(slot) || !isHex6(hex)) return state;
        var rgb = rgbOf(hex);
        var next = clone(state);
        next.colors[slot] = rgb + alphaOf(state.colors[slot]);
        if (slot === 'bgSecondary') next.colors.glass = rgb + (alphaOf(state.colors.glass) || 'e0');
        if (slot === 'border') next.colors.borderLight = rgb + alphaOf(state.colors.borderLight);
        if (slot === 'accent') next.mainColor = rgb;
        if (slot === 'bgPrimary') {
            next.pageColor = rgb;
            next.mode = modeOf(rgb);
        }
        return next;
    }

    function setCorners(state, percent) {
        return withFields(state, { radiusScale: clampInt(percent, 0, 200, 100) });
    }

    function setFont(state, font) {
        return FONTS.indexOf(font) >= 0 ? withFields(state, { font: font }) : state;
    }

    function setEffects(state, on) {
        return withFields(state, { effects: !!on });
    }

    /* ---- step 3: the picture ---- */

    /* A file chosen here. Its darkening starts at `dim` (suggested from its brightness) when that is a number. */
    function withNewPicture(state, dim) {
        return withFields(state, { picture: 'new', dim: clampInt(dim, 0, 90, state.dim) });
    }

    /* No picture: for a theme that had one on the server this means removing it. */
    function withoutPicture(state) {
        return withFields(state, { picture: 'none' });
    }

    /* Any of dim, blur and fit; what is missing or out of range is left as it was. */
    function setPictureLook(state, look) {
        var fields = {};
        if (look && look.dim !== undefined) fields.dim = clampInt(look.dim, 0, 90, state.dim);
        if (look && look.blur !== undefined) fields.blur = clampInt(look.blur, 0, 20, state.blur);
        if (look && FITS.indexOf(look.fit) >= 0) fields.fit = look.fit;
        return withFields(state, fields);
    }

    /* The mean of the red, green and blue values as they are stored (sRGB, 0..1), over RGBA bytes; NaN when there are none. */
    function averageBrightness(data) {
        if (!data || typeof data.length !== 'number') return NaN;
        var sum = 0, count = 0;
        for (var i = 0; i + 3 < data.length; i += 4) {
            sum += data[i] + data[i + 1] + data[i + 2];
            count += 3;
        }
        return count ? sum / (count * 255) : NaN;
    }

    /* How much to darken a picture, from its RGBA bytes (drawn small, over the page color). */
    function suggestedDim(data) {
        return palette.suggestDim(averageBrightness(data));
    }

    function startsWith(bytes, signature, at) {
        for (var i = 0; i < signature.length; i++) {
            if (bytes[(at || 0) + i] !== signature[i]) return false;
        }
        return true;
    }

    function codes(text) {
        return text.split('').map(function (c) { return c.charCodeAt(0); });
    }

    /* 'jpg', 'png', 'gif' or 'webp' by the first bytes, as the server tells them apart; null for anything else. */
    function sniffPicture(bytes) {
        if (!bytes || typeof bytes.length !== 'number') return null;
        if (startsWith(bytes, [0xff, 0xd8, 0xff])) return 'jpg';
        if (startsWith(bytes, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return 'png';
        if (startsWith(bytes, codes('GIF87a')) || startsWith(bytes, codes('GIF89a'))) return 'gif';
        if (startsWith(bytes, codes('RIFF')) && startsWith(bytes, codes('WEBP'), 8)) return 'webp';
        return null;
    }

    /* Any picture type but JPEG can move. */
    function canMove(kind) {
        return kind === 'gif' || kind === 'webp' || kind === 'png';
    }

    /* Why this file can't be used ('' when it can): its kind from sniffPicture and its size in bytes. */
    function pictureProblem(kind, size) {
        if (!kind || !(size > 0)) return TEXT.NOT_A_PICTURE;
        return size > MAX_PICTURE_BYTES ? TEXT.PICTURE_TOO_BIG : '';
    }

    /* ---- step 6: the name and who can use the theme ---- */

    function setName(state, name) {
        return withFields(state, { name: typeof name === 'string' ? name : '' });
    }

    /* A theme the bot owner hid stays private until he shows it again. */
    function setVisibility(state, visibility) {
        return withFields(state, { visibility: visibility === 'private' || state.hiddenByOwner ? 'private' : 'public' });
    }

    function nameProblem(name) {
        var text = typeof name === 'string' ? name.trim() : '';
        if (!text) return TEXT.NAME_MISSING;
        if (Array.from(text).length > MAX_NAME) return TEXT.NAME_TOO_LONG;
        for (var i = 0; i < text.length; i++) {
            var code = text.charCodeAt(i);
            if (code < 32 || (code >= 127 && code <= 159)) return TEXT.NAME_CONTROL;
        }
        return '';
    }

    /* ---- what is saved, and what is shown while choosing ---- */

    function hasPicture(state) {
        return state.picture === 'new' || state.picture === 'saved';
    }

    /*
     * The theme's values as the server takes them (spec 2: no id, owner, version or picture name). Effects are sent as
     * the person set them only for a base that has some; any other base always sends true, because false would hide
     * even its plain accent glow. A picture switches effects off, and a mini-player picture needs the picture.
     */
    function buildValues(state) {
        var picture = hasPicture(state);
        var values = {
            name: String(state.name == null ? '' : state.name).trim(),
            visibility: state.hiddenByOwner || state.visibility === 'private' ? 'private' : 'public',
            base: isBase(state.base) ? state.base : 'nebula',
            effects: picture ? false : (hasDecorations(state.base) ? state.effects === true : true),
            colors: normalizeColors(state.colors, palette.SLOTS, BASE_PALETTES.nebula.colors),
            radiusScale: clampInt(state.radiusScale, 0, 200, 100),
            font: FONTS.indexOf(state.font) >= 0 ? state.font : 'Inter'
        };
        if (picture) {
            values.background = {
                dim: clampInt(state.dim, 0, 90, DEFAULT_DIM),
                blur: clampInt(state.blur, 0, 20, 0),
                fit: FITS.indexOf(state.fit) >= 0 ? state.fit : 'cover'
            };
        }
        if (state.mini) {
            values.mini = {
                colors: normalizeColors(state.mini.colors, palette.MINI_SLOTS, values.colors),
                useImage: state.mini.useImage === true && picture,
                dim: clampInt(state.mini.dim, 0, 90, DEFAULT_DIM)
            };
        }
        return values;
    }

    /* How to save: where to post, with which version, whether the old picture is to go, whether a file goes along. */
    function saveRequest(state) {
        var editing = typeof state.id === 'string' && state.id !== '';
        return {
            editing: editing,
            path: editing ? '/api/themes/' + encodeURIComponent(state.id) : '/api/themes',
            version: editing ? state.version : null,
            hadPicture: editing && state.hadPicture === true,
            removeBackground: editing && state.hadPicture === true && state.picture === 'none',
            sendsPicture: state.picture === 'new',
            values: buildValues(state)
        };
    }

    /*
     * The theme as the panel would get it from the server, for the live preview. `localUrl` is the blob: link of a
     * picture chosen here (ThemeApply takes that one link only from the creator, see ThemeApply.localPicture).
     */
    function previewTheme(state, localUrl) {
        var values = buildValues(state);
        var url = state.picture === 'new' ? localUrl : (state.picture === 'saved' ? state.savedPictureUrl : '');
        return {
            id: PREVIEW_ID,
            name: values.name || PREVIEW_NAME,
            owner: { id: '', name: '' },
            visibility: values.visibility,
            hiddenByOwner: false,
            mine: true,
            version: 0,
            base: values.base,
            effects: values.effects,
            colors: values.colors,
            radiusScale: values.radiusScale,
            font: values.font,
            background: values.background && url ? { url: url, dim: values.background.dim, blur: values.background.blur, fit: values.background.fit } : null,
            mini: null
        };
    }

    /* ---- when saving does not work ---- */

    /* The server's message when it is short, plain and on one line; else ''. */
    function cleanMessage(message) {
        if (typeof message !== 'string') return '';
        var text = message.trim();
        return !text || text.length > 200 || /[\r\n]/.test(text) ? '' : text;
    }

    /* The step a refusal belongs to, from the server's words. */
    function stepForMessage(message, status) {
        if (status === 413) return STEP.PICTURE;
        var text = String(message == null ? '' : message);
        if (/mini-player/i.test(text)) return STEP.MINI;
        if (/must be a color|is missing|unknown color/i.test(text)) return STEP.TWEAK; // before "Background", which is also a color
        if (/picture|background|darken|blur|\bfit\b/i.test(text)) return STEP.PICTURE;
        if (/starting theme/i.test(text)) return STEP.START;
        if (/corners|font|special effects/i.test(text)) return STEP.TWEAK;
        return STEP.SHARE;
    }

    /*
     * What to tell the person, and where, after the server refused a save. `request` is saveRequest()'s answer.
     *   limit    a new theme and the person has the most they may: explain, offer to open My themes
     *   changed  an edit, and the theme changed since it was opened (the bot owner hiding it counts): offer to reload
     *   gone     an edit, and the theme is not there any more: offer to save it as a new theme
     *   invalid  anything else: the server's own words, on the step they belong to
     */
    function classifyFailure(request, status, message, limitMax) {
        if (status === 409 && !request.editing) {
            var max = limitMax > 0 ? limitMax : 10;
            return {
                type: 'limit', step: STEP.SHARE,
                text: 'You already have ' + max + " themes, the most you can have. Delete one you don't use, then save this one."
            };
        }
        if (status === 409) return { type: 'changed', step: STEP.SHARE, text: TEXT.CHANGED };
        if (status === 404 && request.editing) {
            var lostPicture = request.hadPicture && !request.sendsPicture;
            return { type: 'gone', step: STEP.SHARE, text: TEXT.GONE + (lostPicture ? TEXT.GONE_PICTURE : '') };
        }
        var said = cleanMessage(message);
        var text = said || (status === 413 ? TEXT.PICTURE_TOO_BIG : (status >= 500 ? TEXT.SAVE_FAILED : TEXT.DIDNT_WORK));
        return { type: 'invalid', step: stepForMessage(text, status), text: text };
    }

    return {
        BASE_PALETTES: BASE_PALETTES,
        DECORATED_BASES: DECORATED_BASES,
        hasDecorations: hasDecorations,
        FONTS: FONTS,
        FITS: FITS,
        MAX_NAME: MAX_NAME,
        MAX_PICTURE_BYTES: MAX_PICTURE_BYTES,
        PREVIEW_ID: PREVIEW_ID,
        TEXT: TEXT,
        STEPS: STEPS,
        STEP: STEP,
        firstStep: firstStep,
        clampStep: clampStep,
        nextStep: nextStep,
        prevStep: prevStep,
        isFirstStep: isFirstStep,
        isLastStep: isLastStep,
        nextLabel: nextLabel,
        startState: startState,
        startFromTheme: startFromTheme,
        fromTheme: fromTheme,
        asNewTheme: asNewTheme,
        chooseBase: chooseBase,
        chooseTheme: chooseTheme,
        setMain: setMain,
        setPage: setPage,
        setMode: setMode,
        surpriseMe: surpriseMe,
        ACCENT_NAMES: ACCENT_NAMES,
        accentSuggestions: accentSuggestions,
        clearerAccent: clearerAccent,
        accentVisible: accentVisible,
        report: report,
        fixIt: fixIt,
        COLOR_CHOICES: COLOR_CHOICES,
        tweakColor: tweakColor,
        setCorners: setCorners,
        setFont: setFont,
        setEffects: setEffects,
        withNewPicture: withNewPicture,
        withoutPicture: withoutPicture,
        setPictureLook: setPictureLook,
        averageBrightness: averageBrightness,
        suggestedDim: suggestedDim,
        sniffPicture: sniffPicture,
        canMove: canMove,
        pictureProblem: pictureProblem,
        setName: setName,
        setVisibility: setVisibility,
        nameProblem: nameProblem,
        buildValues: buildValues,
        saveRequest: saveRequest,
        previewTheme: previewTheme,
        classifyFailure: classifyFailure,
        stepForMessage: stepForMessage
    };
}));
