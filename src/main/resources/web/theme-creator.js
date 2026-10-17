/*
 * The logic of the guided theme creator, apart from the page: the six steps, the state the creator keeps, the
 * choices that change it (colors, fine-tuning, the mini-player version), what is checked (readability, a visible main
 * color, pictures, names), the values that are saved and the previews that are shown, and what to say when saving
 * fails.
 * Pure functions on plain objects, no DOM: every function returns a new state and leaves the one it was given alone.
 * What needs the starting themes' decoration pieces is given the pieces list (theme-pieces.json as the panel loaded
 * it, its themePieces; null while it hasn't): without it there are no piece switches and the pieces that are off stay
 * as they are. Loaded by the panel as a classic script (window.ThemeCreator, after theme-palette.js and
 * theme-apply.js) and by Node for tests.
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
     * The 17 built-in themes as a starting point: the label as in the picker, the font, and the 16 color slots as the
     * stylesheet gives them (a test keeps this table equal to the stylesheet). Colors that are see-through carry their
     * alpha as the last two digits. The extra decoration color (decoExtra, the block's --deco-extra) is Rasta's red,
     * Cipher's cyan and Ember's flame-core yellow, and every other theme's accent-light.
     */
    var BASE_PALETTES = deepFreeze({
        nebula: { label: 'Nebula', font: 'Inter', colors: {
            bgPrimary: '#0b0d17', bgSecondary: '#121427', bgCard: '#181b2e', bgElevated: '#1e2240', bgHover: '#252a4a',
            accent: '#7c5cfc', accentLight: '#9b82fc', accent2: '#e04cf0',
            textPrimary: '#eef0f6', textSecondary: '#7a7f9a', textTertiary: '#4e5270',
            border: '#ffffff0f', borderLight: '#ffffff1a', success: '#34d399', glass: '#181b2ed9', decoExtra: '#9b82fc'
        } },
        aurora: { label: 'Aurora', font: 'Inter', colors: {
            bgPrimary: '#0a1114', bgSecondary: '#0f181c', bgCard: '#152228', bgElevated: '#1a2e35', bgHover: '#23424d',
            accent: '#2dd4bf', accentLight: '#5eead4', accent2: '#22d3ee',
            textPrimary: '#ecf8f6', textSecondary: '#7a9d9a', textTertiary: '#4a6b67',
            border: '#ffffff12', borderLight: '#ffffff1f', success: '#34d399', glass: '#0f181ce0', decoExtra: '#5eead4'
        } },
        ember: { label: 'Ember', font: 'Inter', colors: {
            bgPrimary: '#140c0a', bgSecondary: '#1a100c', bgCard: '#241815', bgElevated: '#2e1f18', bgHover: '#3d2a22',
            accent: '#f97316', accentLight: '#fb923c', accent2: '#ef4444',
            textPrimary: '#faf5f0', textSecondary: '#b8a090', textTertiary: '#7a6558',
            border: '#ffffff12', borderLight: '#ffffff1f', success: '#4ade80', glass: '#1a100ce0', decoExtra: '#fde047'
        } },
        crimson: { label: 'Crimson', font: 'Inter', colors: {
            bgPrimary: '#0f0506', bgSecondary: '#160809', bgCard: '#1f0c0e', bgElevated: '#2a1013', bgHover: '#3a161a',
            accent: '#f87171', accentLight: '#fca5a5', accent2: '#fb7185',
            textPrimary: '#fef2f2', textSecondary: '#c4a3a3', textTertiary: '#8f6668',
            border: '#f871711a', borderLight: '#fca5a529', success: '#4ade80', glass: '#16080ae6', decoExtra: '#fca5a5'
        } },
        dawn: { label: 'Dawn', font: 'DM Sans', colors: {
            bgPrimary: '#f4f2ef', bgSecondary: '#eae6e0', bgCard: '#ffffff', bgElevated: '#f0ece6', bgHover: '#e2dcd4',
            accent: '#6d28d9', accentLight: '#7c3aed', accent2: '#db2777',
            textPrimary: '#1c1917', textSecondary: '#57534e', textTertiary: '#78716c',
            border: '#00000014', borderLight: '#0000001f', success: '#059669', glass: '#ffffffc7', decoExtra: '#7c3aed'
        } },
        'dawn-dark': { label: 'Dawn Dark', font: 'DM Sans', colors: {
            bgPrimary: '#050403', bgSecondary: '#080706', bgCard: '#12100e', bgElevated: '#0c0b09', bgHover: '#1c1a17',
            accent: '#8b5cf6', accentLight: '#c4b5fd', accent2: '#db2777',
            textPrimary: '#fafaf9', textSecondary: '#b5aea8', textTertiary: '#78716c',
            border: '#ffffff0e', borderLight: '#ffffff1a', success: '#34d399', glass: '#050403e6', decoExtra: '#c4b5fd'
        } },
        steel: { label: 'Steel', font: 'Inter', colors: {
            bgPrimary: '#0c0f14', bgSecondary: '#121620', bgCard: '#181e2a', bgElevated: '#1e2635', bgHover: '#283042',
            accent: '#38bdf8', accentLight: '#7dd3fc', accent2: '#818cf8',
            textPrimary: '#f1f5f9', textSecondary: '#94a3b8', textTertiary: '#64748b',
            border: '#ffffff0f', borderLight: '#ffffff1c', success: '#34d399', glass: '#121620db', decoExtra: '#7dd3fc'
        } },
        verdant: { label: 'Verdant', font: 'Inter', colors: {
            bgPrimary: '#0a0f0c', bgSecondary: '#0f1812', bgCard: '#152019', bgElevated: '#1a2d22', bgHover: '#243a2d',
            accent: '#34d399', accentLight: '#6ee7b7', accent2: '#a3e635',
            textPrimary: '#ecfdf5', textSecondary: '#86a89a', textTertiary: '#527a6a',
            border: '#ffffff0f', borderLight: '#ffffff1c', success: '#4ade80', glass: '#0f1812e0', decoExtra: '#6ee7b7'
        } },
        grove: { label: 'Grove', font: 'Inter', colors: {
            bgPrimary: '#171b1f', bgSecondary: '#1e2329', bgCard: '#252b33', bgElevated: '#2f3640', bgHover: '#3a4350',
            accent: '#34d399', accentLight: '#6ee7b7', accent2: '#94a3b8',
            textPrimary: '#f1f5f9', textSecondary: '#94a3b8', textTertiary: '#64748b',
            border: '#94a3b81a', borderLight: '#94a3b833', success: '#4ade80', glass: '#1e2329e6', decoExtra: '#6ee7b7'
        } },
        grapevine: { label: 'Grapevine', font: 'Inter', colors: {
            bgPrimary: '#0c0a12', bgSecondary: '#110e1a', bgCard: '#181528', bgElevated: '#221d34', bgHover: '#2e2645',
            accent: '#a78bfa', accentLight: '#c4b5fd', accent2: '#22c55e',
            textPrimary: '#f3f0ff', textSecondary: '#a8a0c8', textTertiary: '#6b6390',
            border: '#a78bfa17', borderLight: '#4ade8024', success: '#4ade80', glass: '#120d1ce6', decoExtra: '#c4b5fd'
        } },
        sumi: { label: 'Sumi', font: 'Noto Sans JP', colors: {
            bgPrimary: '#060708', bgSecondary: '#0a0c0f', bgCard: '#101318', bgElevated: '#0c0e12', bgHover: '#1a1f26',
            accent: '#c42b20', accentLight: '#e85d52', accent2: '#1a2f42',
            textPrimary: '#f2ebe3', textSecondary: '#a39e96', textTertiary: '#6f6a64',
            border: '#f2ebe30e', borderLight: '#c9a2271f', success: '#6d9f7b', glass: '#060708eb', decoExtra: '#e85d52'
        } },
        rasta: { label: 'Rasta', font: 'Outfit', colors: {
            bgPrimary: '#000000', bgSecondary: '#000000', bgCard: '#121812', bgElevated: '#182018', bgHover: '#263026',
            accent: '#22c55e', accentLight: '#86efac', accent2: '#eab308',
            textPrimary: '#ecfdf4', textSecondary: '#9dc7a4', textTertiary: '#5e8a66',
            border: '#22c55e24', borderLight: '#eab30838', success: '#4ade80', glass: '#0a0e0ae0', decoExtra: '#dc2626'
        } },
        cipher: { label: 'Cipher', font: 'IBM Plex Mono', colors: {
            bgPrimary: '#09070f', bgSecondary: '#100c1a', bgCard: '#171225', bgElevated: '#211a33', bgHover: '#2c2344',
            accent: '#8b5cf6', accentLight: '#c4b5fd', accent2: '#d946ef',
            textPrimary: '#f3eeff', textSecondary: '#a396c4', textTertiary: '#6b5f8a',
            border: '#8b5cf61f', borderLight: '#d946ef2e', success: '#34d399', glass: '#100c1ae6', decoExtra: '#22d3ee'
        } },
        signal: { label: 'Signal', font: 'Inter', colors: {
            bgPrimary: '#050a0c', bgSecondary: '#0a1215', bgCard: '#0f1a1e', bgElevated: '#152429', bgHover: '#1c333a',
            accent: '#22d3ee', accentLight: '#67e8f9', accent2: '#4ade80',
            textPrimary: '#e8fbff', textSecondary: '#7aadb8', textTertiary: '#4a7580',
            border: '#22d3ee1f', borderLight: '#67e8f933', success: '#4ade80', glass: '#0a1215eb', decoExtra: '#67e8f9'
        } },
        victory: { label: 'Victory', font: 'Syne', colors: {
            bgPrimary: '#0e0c08', bgSecondary: '#16130c', bgCard: '#1f1a12', bgElevated: '#2a2318', bgHover: '#3a3020',
            accent: '#eab308', accentLight: '#fde047', accent2: '#f59e0b',
            textPrimary: '#fffbeb', textSecondary: '#c4b48a', textTertiary: '#8a7a52',
            border: '#eab3081f', borderLight: '#fde04733', success: '#84cc16', glass: '#16130ce6', decoExtra: '#fde047'
        } },
        neon: { label: 'Neon', font: 'Outfit', colors: {
            bgPrimary: '#12081c', bgSecondary: '#1a0f28', bgCard: '#221436', bgElevated: '#2c1a45', bgHover: '#3a2458',
            accent: '#a3e635', accentLight: '#d9f99d', accent2: '#c084fc',
            textPrimary: '#ffffff', textSecondary: '#e8e0f2', textTertiary: '#b9a8d0',
            border: '#a3e63524', borderLight: '#c084fc38', success: '#a3e635', glass: '#1a0f28e6', decoExtra: '#d9f99d'
        } },
        revival: { label: 'Revival', font: 'Inter', colors: {
            bgPrimary: '#14100d', bgSecondary: '#1b1612', bgCard: '#211b17', bgElevated: '#2b231e', bgHover: '#372d26',
            accent: '#e2825c', accentLight: '#f2a882', accent2: '#f2b35e',
            textPrimary: '#f6efe5', textSecondary: '#cdbcaa', textTertiary: '#a08f7e',
            border: '#f2b35e1a', borderLight: '#f2b35e33', success: '#b4d48a', glass: '#181310d1', decoExtra: '#f2a882'
        } }
    });

    /*
     * The built-in themes with decorations on the page (flames, tiles, header strips...): in theme-pieces.json they have
     * pieces of their own. The other six have only the mini-player's glow.
     */
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
        DIDNT_WORK: "That didn't work " + DASH + ' check your choices and try again',
        MINI_SKIP: 'Skip ' + DASH + ' no mini-player version',
        MINI_MAKE: 'Make a mini-player version',
        MINI_NOT_READABLE: 'Some text will be hard to read in the mini-player',
        MINI_MATCH: 'Update to match the theme',
        PIECES_HINT: "The extras that come with the theme you started from. Switch off any you don't want.",
        PIECE_PICTURE_OFF: 'Off while you use a background picture.',
        PIECE_MINI_ONLY: 'Only in the mini-player. This theme has no decorations on the page.'
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

    /*
     * A lower-case copy with exactly these slots; a slot that isn't a color takes the value from `fallback`. The extra
     * decoration color has to be opaque (#rrggbb), as the server wants it.
     */
    function normalizeColors(colors, slots, fallback) {
        var out = {};
        for (var i = 0; i < slots.length; i++) {
            var c = colors && typeof colors === 'object' ? colors[slots[i]] : null;
            var ok = typeof c === 'string' && (slots[i] === 'decoExtra' ? HEX6 : HEX_ANY).test(c);
            out[slots[i]] = ok ? c.toLowerCase() : fallback[slots[i]];
        }
        return out;
    }

    /* What a color that is missing becomes: Nebula's, but the extra decoration color is the starting theme's own (as on the server). */
    function fallbackColors(base) {
        var out = copyOf(BASE_PALETTES.nebula.colors);
        out.decoExtra = BASE_PALETTES[isBase(base) ? base : 'nebula'].colors.decoExtra;
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
     *   base, decorationsOff, colors{16}, radiusScale, font   the look; mainColor, pageColor, mode are step 2's picks
     *   decorationsOff               the ids of the starting theme's pieces that are switched off (theme-pieces.json). A
     *                                picture never changes it: the page switches the background pieces off while it shows
     *                                one (ThemeApply.decoOffFor)
     *   decoExtraSetByHand           the extra decoration color was set in Fine-tune, so new colors on step 2 leave it be;
     *                                otherwise it is the starting theme's own, then the accent-light of any new colors
     *   picture   'none' | 'saved' (the theme's own picture on the server) | 'new' (a file chosen here, sent on save)
     *   hadPicture, savedPictureUrl  whether the edited theme has a picture on the server, and its link
     *   dim, blur, fit               the picture's settings
     *   mini                         null (skipped) or {colors{15}, useImage, dim}; a new theme starts with one made from
     *                                its colors (startState), an edit keeps whatever the theme has. Whether its colors follow
     *                                the theme is not kept apart: they do exactly when they are the ones the theme's colors
     *                                make (miniFollows)
     */
    function blankState() {
        return {
            id: null, version: null, hiddenByOwner: false, name: '', visibility: 'public',
            base: 'nebula', decorationsOff: [], colors: null, decoExtraSetByHand: false, radiusScale: 100, font: 'Inter',
            mainColor: '', pageColor: '', mode: 'dark',
            picture: 'none', hadPicture: false, savedPictureUrl: '', dim: DEFAULT_DIM, blur: 0, fit: 'cover',
            mini: null
        };
    }

    function copyMini(mini) {
        return mini ? { colors: mini.colors ? copyOf(mini.colors) : {}, useImage: mini.useImage === true, dim: mini.dim } : null;
    }

    function clone(state) {
        var next = copyOf(state);
        next.decorationsOff = Array.isArray(state.decorationsOff) ? state.decorationsOff.slice() : [];
        next.colors = state.colors ? copyOf(state.colors) : null;
        next.mini = copyMini(state.mini);
        return next;
    }

    /* The fields that make up a look, from a base, its colors, font, the pieces that are off and corners. */
    function lookFields(base, colors, font, decorationsOff, radiusScale) {
        var id = isBase(base) ? base : 'nebula';
        var fixed = normalizeColors(colors, palette.SLOTS, fallbackColors(id));
        return {
            base: id,
            decorationsOff: decorationsOff.slice(),
            colors: fixed,
            decoExtraSetByHand: false,
            font: FONTS.indexOf(font) >= 0 ? font : 'Inter',
            radiusScale: clampInt(radiusScale, 0, 200, 100),
            mainColor: rgbOf(fixed.accent),
            pageColor: rgbOf(fixed.bgPrimary),
            mode: modeOf(fixed.bgPrimary)
        };
    }

    /* A built-in theme's look: its colors (its own extra decoration color with them) and every piece on. */
    function baseLook(id) {
        var base = isBase(id) ? id : 'nebula';
        return lookFields(base, BASE_PALETTES[base].colors, BASE_PALETTES[base].font, [], 100);
    }

    /*
     * The pieces a theme from the server has off, as the panel reads them (ThemeApply.decoOffFor without a picture: a
     * picture is no choice about the pieces): its list with only its starting theme's ids, each once, in their order.
     * A theme in the old shape (no list) converts the way the server converts it: effects false is every piece off.
     * Without the pieces list only ids that look like ids go through, and the old shape can't be converted.
     */
    function savedPiecesOff(theme, pieces) {
        var off = apply.decoOffFor(theme, false, pieces);
        return off ? off.split(' ') : [];
    }

    /*
     * A theme's look. Its extra decoration color counts as set by hand when it is none of: its accent-light, its
     * starting theme's own, or the own of a built-in theme whose 16 colors it has exactly ("Use colors from" Rasta on
     * Revival keeps Rasta's red without anybody choosing it).
     */
    function themeLook(theme, pieces) {
        var look = lookFields(theme.base, theme.colors, theme.font, savedPiecesOff(theme, pieces), theme.radiusScale);
        var extra = look.colors.decoExtra;
        look.decoExtraSetByHand = extra !== rgbOf(look.colors.accentLight) && extra !== BASE_PALETTES[look.base].colors.decoExtra &&
            !Object.keys(BASE_PALETTES).some(function (id) { return colorsAreFrom(look, id); });
        return look;
    }

    function withFields(state, fields) {
        var next = clone(state);
        Object.keys(fields).forEach(function (key) { next[key] = fields[key]; });
        return fields.colors ? keepMiniFollowing(state, next) : next;
    }

    /* A new theme starting from a built-in theme, with a mini-player version made from its colors (Skip on step 5 drops it). */
    function startState(baseId) {
        return makeMini(withFields(blankState(), baseLook(baseId)));
    }

    /*
     * A new theme starting from somebody's community theme: its look (its pieces too), but not its picture, name or the
     * mini-player version it has (ours is made from the look it starts with, as for any new theme).
     */
    function startFromTheme(theme, pieces) {
        return makeMini(withFields(blankState(), themeLook(theme, pieces)));
    }

    /* The state for editing a theme of the list. */
    function fromTheme(theme, pieces) {
        var state = withFields(blankState(), themeLook(theme, pieces));
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
                useImage: theme.mini.useImage === true && !!url,
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

    /*
     * Picking another starting point replaces the look and keeps everything else (picture, name, mini-player version).
     * Of the pieces that were off, the ones the new starting theme has stay off (as the server does); without the pieces
     * list they all stay, and the server keeps the ones it has.
     */
    function chooseBase(state, baseId, pieces) {
        var look = baseLook(baseId);
        var own = apply.piecesOf(pieces, look.base);
        look.decorationsOff = (state.decorationsOff || []).filter(function (id) {
            return !own || own.some(function (piece) { return piece.id === id; });
        });
        return withFields(state, look);
    }

    function chooseTheme(state, theme, pieces) {
        return withFields(state, themeLook(theme, pieces));
    }

    /* ---- step 2: the colors ---- */

    /* A palette made from a main color and a background. Its extra decoration color is its accent-light, unless the person set one. */
    function withPair(state, main, page, mode) {
        var colors = palette.generate(main, page, mode);
        if (state.decoExtraSetByHand === true && state.colors && isHex6(state.colors.decoExtra)) colors.decoExtra = state.colors.decoExtra;
        return withFields(state, { colors: colors, mainColor: rgbOf(colors.accent), pageColor: rgbOf(colors.bgPrimary), mode: mode });
    }

    /*
     * "Use colors from": all 16 colors of a built-in theme, its extra decoration color too, which follows the colors
     * again from here. The starting theme, its shape and its pieces stay.
     */
    function useColoursFrom(state, themeId) {
        if (!isBase(themeId)) return state;
        var colors = copyOf(BASE_PALETTES[themeId].colors);
        return withFields(state, {
            colors: colors, decoExtraSetByHand: false,
            mainColor: rgbOf(colors.accent), pageColor: rgbOf(colors.bgPrimary), mode: modeOf(colors.bgPrimary)
        });
    }

    /* Are the colors exactly those of this built-in theme (its chip shows as chosen)? */
    function colorsAreFrom(state, themeId) {
        return isBase(themeId) && !!state.colors && palette.SLOTS.every(function (slot) {
            return state.colors[slot] === BASE_PALETTES[themeId].colors[slot];
        });
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

    /*
     * The colors a person may set one by one, with the plain names the server uses too. Light borders and glass follow.
     * The extra decoration color is only offered for a starting theme with decorations on the page (fineTuneColors).
     */
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
        ['success', 'Success color', 'Ticks and "done" messages'],
        ['decoExtra', 'Extra decoration color', 'A color some of the decorations use, like flame cores or a flag stripe']
    ].map(function (c) { return Object.freeze({ slot: c[0], label: c[1], hint: c[2] }); }));

    /* The colors Fine-tune lists for a starting theme: all of them, but the extra decoration color only where there are decorations. */
    function fineTuneColors(base) {
        return COLOR_CHOICES.filter(function (c) { return c.slot !== 'decoExtra' || hasDecorations(base); });
    }

    function isChoice(slot) {
        return COLOR_CHOICES.some(function (c) { return c.slot === slot; });
    }

    /* One color from a picker (plain #rrggbb). It keeps the slot's transparency; the colors made from it follow. */
    function tweakColor(state, slot, hex) {
        if (slot === 'decoExtra') return setDecoExtra(state, hex);
        if (!isChoice(slot) || !isHex6(hex)) return state;
        var rgb = rgbOf(hex);
        var next = clone(state);
        next.colors[slot] = rgb + alphaOf(state.colors[slot]);
        if (slot === 'bgSecondary') next.colors.glass = rgb + (alphaOf(state.colors.glass) || 'e0');
        if (slot === 'border') next.colors.borderLight = rgb + alphaOf(state.colors.borderLight);
        // an extra decoration color that is following the colors (it is the accent-light) goes along
        if (slot === 'accentLight' && state.decoExtraSetByHand !== true && state.colors.decoExtra === rgbOf(state.colors.accentLight)) {
            next.colors.decoExtra = rgb;
        }
        if (slot === 'accent') next.mainColor = rgb;
        if (slot === 'bgPrimary') {
            next.pageColor = rgb;
            next.mode = modeOf(rgb);
        }
        return keepMiniFollowing(state, next);
    }

    /* The extra decoration color, set by hand: it stays when the colors change on step 2 (until "Use colors from"). */
    function setDecoExtra(state, hex) {
        if (!isHex6(hex)) return state;
        var next = clone(state);
        next.colors.decoExtra = hex.toLowerCase();
        next.decoExtraSetByHand = true;
        return keepMiniFollowing(state, next);
    }

    function setCorners(state, percent) {
        return withFields(state, { radiusScale: clampInt(percent, 0, 200, 100) });
    }

    function setFont(state, font) {
        return FONTS.indexOf(font) >= 0 ? withFields(state, { font: font }) : state;
    }

    /* ---- step 4: the decoration pieces ---- */

    function isOff(state, id) {
        return (state.decorationsOff || []).indexOf(id) >= 0;
    }

    /*
     * One piece of the starting theme on or off. Nothing happens for an id the starting theme doesn't have, or without
     * the pieces list. A picture doesn't stop it: the list is what is saved, and the picture only hides the background
     * pieces while it is there.
     */
    function setPiece(state, id, on, pieces) {
        var own = apply.piecesOf(pieces, state.base);
        if (!own || !own.some(function (piece) { return piece.id === id; }) || isOff(state, id) === !on) return state;
        var off = (state.decorationsOff || []).filter(function (other) { return other !== id; });
        if (!on) off.push(id);
        return withFields(state, { decorationsOff: off });
    }

    /* "All on" / "All off" for the starting theme's pieces (nothing without the pieces list). */
    function allPieces(state, on, pieces) {
        var own = apply.piecesOf(pieces, state.base);
        if (!own) return state;
        return withFields(state, { decorationsOff: on ? [] : own.map(function (piece) { return piece.id; }) });
    }

    /*
     * Everything the Fine-tune step shows about the pieces, or null without the pieces list (no switches then):
     *   pieces       [{id, label, background, on, forced, hint}] in the list's order: `on` as the page shows it (what the
     *                preview gets from ThemeApply.decoOffFor); `forced` for a background piece while there is a picture
     *                (shown off, can't be switched); `hint` the words under it ('' for none)
     *   allOn, allOff   whether "All on" / "All off" would change nothing (the saved list)
     *   miniOnly     the starting theme has nothing on the page: its one piece is the mini-player's
     *   pictureNote  some pieces are off because of the picture
     */
    function piecesView(state, pieces) {
        var own = apply.piecesOf(pieces, state.base);
        if (!own) return null;
        var picture = hasPicture(state);
        var shownOff = apply.decoOffFor({ base: state.base, decorationsOff: state.decorationsOff || [] }, picture, pieces).split(' ');
        var miniOnly = !hasDecorations(state.base);
        var list = own.map(function (piece) {
            var forced = picture && piece.background === true;
            return {
                id: piece.id,
                label: typeof piece.label === 'string' && piece.label ? piece.label : piece.id,
                background: piece.background === true,
                on: shownOff.indexOf(piece.id) < 0,
                forced: forced,
                hint: forced ? TEXT.PIECE_PICTURE_OFF : (miniOnly ? TEXT.PIECE_MINI_ONLY : '')
            };
        });
        return {
            pieces: list,
            allOn: own.every(function (piece) { return !isOff(state, piece.id); }),
            allOff: own.every(function (piece) { return isOff(state, piece.id); }),
            miniOnly: miniOnly,
            pictureNote: list.some(function (piece) { return piece.forced; })
        };
    }

    /* ---- step 3: the picture ---- */

    /*
     * A file chosen here. Its darkening starts at `dim` (suggested from its brightness) when that is a number. A theme that
     * had no picture and has a mini-player version gets the picture there too, at the same darkening, just as a mini-player
     * version made after the picture would (step 5 comes after step 3, but a new theme has its version from the start).
     */
    function withNewPicture(state, dim) {
        var next = withFields(state, { picture: 'new', dim: clampInt(dim, 0, 90, state.dim) });
        if (next.mini && !hasPicture(state)) {
            next.mini.useImage = true;
            next.mini.dim = next.dim;
        }
        return next;
    }

    /* No picture: for a theme that had one on the server this means removing it. The mini-player has none to use then. */
    function withoutPicture(state) {
        var next = withFields(state, { picture: 'none' });
        if (next.mini) next.mini.useImage = false;
        return next;
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

    /* ---- step 5: the mini-player version ---- */

    /* The 16 variables the desktop mini-player takes from a theme (desktop/bridge/core.js THEME_VARS; a test keeps them equal). */
    var MINI_VARS = Object.freeze(['--bg-primary', '--bg-secondary', '--bg-card', '--bg-elevated', '--bg-hover', '--accent',
        '--accent-light', '--accent-glow', '--accent-gradient', '--text-primary', '--text-secondary', '--text-tertiary',
        '--border', '--border-light', '--success', '--font-family']);

    function sameColors(a, b) {
        return palette.MINI_SLOTS.every(function (slot) { return !!a && !!b && a[slot] === b[slot]; });
    }

    /* Do the mini-player's colors follow the theme, that is, are they the ones the theme's colors make? */
    function miniFollows(state) {
        return !!state.mini && !!state.colors && sameColors(state.mini.colors, palette.mini(state.colors));
    }

    /*
     * `next` is `prev` after a change; if the theme's colors changed and the mini-player's colors were following them,
     * they follow to the new ones. Colors the person set for the mini-player themselves stay. (Every change of the
     * theme's colors ends here, so a mini-player version made from the theme never goes out of step.)
     */
    function keepMiniFollowing(prev, next) {
        if (miniFollows(prev) && next.mini && next.colors) next.mini.colors = palette.mini(next.colors);
        return next;
    }

    function withMini(state, fields) {
        var next = clone(state);
        Object.keys(fields).forEach(function (key) { next.mini[key] = fields[key]; });
        return next;
    }

    /* The mini-player's 15 colors made from a main color and a background of its own (readable, like step 2's). */
    function miniFrom(main, page) {
        return palette.mini(palette.generate(main, page, modeOf(page)));
    }

    /* "Make a mini-player version": its colors from the theme's, on its picture (and darkening) when the theme has one. */
    function makeMini(state) {
        if (state.mini) return state;
        var picture = hasPicture(state);
        var next = clone(state);
        next.mini = {
            colors: palette.mini(state.colors),
            useImage: picture,
            dim: picture ? clampInt(state.dim, 0, 90, DEFAULT_DIM) : DEFAULT_DIM
        };
        return next;
    }

    /* "Skip": no mini-player version, so the theme isn't offered in the mini-player. */
    function skipMini(state) {
        if (!state.mini) return state;
        var next = clone(state);
        next.mini = null;
        return next;
    }

    /* Its own main color: the mini-player's palette is made again from it and the mini-player's background. */
    function setMiniMain(state, hex) {
        if (!state.mini || !isHex6(hex)) return state;
        return withMini(state, { colors: miniFrom(hex.toLowerCase(), rgbOf(state.mini.colors.bgPrimary)) });
    }

    function setMiniPage(state, hex) {
        if (!state.mini || !isHex6(hex)) return state;
        return withMini(state, { colors: miniFrom(rgbOf(state.mini.colors.accent), hex.toLowerCase()) });
    }

    /* "Update to match the theme": the colors the theme's make, and following it again. */
    function matchMini(state) {
        return state.mini ? withMini(state, { colors: palette.mini(state.colors) }) : state;
    }

    /*
     * "Fix it": the words move just far enough (as on step 2). Where the page and cards are so far apart that no words
     * work on both, the palette starts over from the mini-player's main color and background, which is always readable.
     * Either way the colors are the mini-player's own from then on.
     */
    function fixMini(state) {
        if (!state.mini || palette.readability(state.mini.colors).ok) return state;
        var fixed = palette.fixReadability(state.mini.colors);
        if (!palette.readability(fixed).ok) fixed = miniFrom(rgbOf(state.mini.colors.accent), rgbOf(state.mini.colors.bgPrimary));
        return withMini(state, { colors: fixed });
    }

    /* "Use my background picture": only a theme with a picture has one to use. */
    function setMiniPicture(state, on) {
        return state.mini ? withMini(state, { useImage: on === true && hasPicture(state) }) : state;
    }

    function setMiniDim(state, dim) {
        return state.mini ? withMini(state, { dim: clampInt(dim, 0, 90, state.mini.dim) }) : state;
    }

    /* The 16 variables for the preview, from the mini-player's colors and the theme's font. */
    function miniVars(state) {
        var all = palette.cssVars(state.mini.colors);
        var vars = {};
        MINI_VARS.forEach(function (name) { vars[name] = all[name]; });
        vars['--font-family'] = apply.fontStack(state.font) || apply.fontStack('Inter');
        return vars;
    }

    /* Everything the step shows about the mini-player version, or {made: false} when there is none. */
    function miniView(state) {
        if (!state.mini) return { made: false };
        var colors = state.mini.colors;
        var picture = hasPicture(state);
        return {
            made: true,
            follows: miniFollows(state),
            main: rgbOf(colors.accent),
            page: rgbOf(colors.bgPrimary),
            report: report(colors),
            hasPicture: picture,
            useImage: state.mini.useImage === true && picture,
            dim: clampInt(state.mini.dim, 0, 90, DEFAULT_DIM),
            vars: miniVars(state),
            /* The mini-player's own --on-accent: the play icon's color on the main color (dark on light, white on dark). */
            onAccent: palette.onAccent(colors.accent)
        };
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

    /* The ids in the pieces-off list, each once, in their order (a copy). */
    function piecesOffOf(state) {
        var out = [];
        (Array.isArray(state.decorationsOff) ? state.decorationsOff : []).forEach(function (id) {
            if (typeof id === 'string' && out.indexOf(id) < 0) out.push(id);
        });
        return out;
    }

    /*
     * The theme's values as the server takes them (spec 2: no id, owner, version or picture name): the pieces that are
     * off as the person set them (a picture doesn't change them: the page hides the background pieces while it shows
     * one) and all 16 colors. A mini-player picture needs the picture.
     */
    function buildValues(state) {
        var picture = hasPicture(state);
        var values = {
            name: String(state.name == null ? '' : state.name).trim(),
            visibility: state.hiddenByOwner || state.visibility === 'private' ? 'private' : 'public',
            base: isBase(state.base) ? state.base : 'nebula',
            decorationsOff: piecesOffOf(state),
            colors: normalizeColors(state.colors, palette.SLOTS, fallbackColors(state.base)),
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
     * picture chosen here (ThemeApply takes that one link only from the creator, see ThemeApply.localPicture). The
     * page applies it like a saved theme: its pieces off, and the background pieces too while it shows the picture
     * (ThemeApply.decoOffFor).
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
            decorationsOff: values.decorationsOff,
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
        if (/corners|font|decorations/i.test(text)) return STEP.TWEAK;
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
        useColoursFrom: useColoursFrom,
        colorsAreFrom: colorsAreFrom,
        ACCENT_NAMES: ACCENT_NAMES,
        accentSuggestions: accentSuggestions,
        clearerAccent: clearerAccent,
        accentVisible: accentVisible,
        report: report,
        fixIt: fixIt,
        COLOR_CHOICES: COLOR_CHOICES,
        fineTuneColors: fineTuneColors,
        tweakColor: tweakColor,
        setDecoExtra: setDecoExtra,
        setCorners: setCorners,
        setFont: setFont,
        setPiece: setPiece,
        allPieces: allPieces,
        piecesView: piecesView,
        withNewPicture: withNewPicture,
        withoutPicture: withoutPicture,
        setPictureLook: setPictureLook,
        MINI_VARS: MINI_VARS,
        makeMini: makeMini,
        skipMini: skipMini,
        miniFollows: miniFollows,
        setMiniMain: setMiniMain,
        setMiniPage: setMiniPage,
        matchMini: matchMini,
        fixMini: fixMini,
        setMiniPicture: setMiniPicture,
        setMiniDim: setMiniDim,
        miniView: miniView,
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
