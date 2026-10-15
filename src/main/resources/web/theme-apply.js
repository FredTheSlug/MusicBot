/*
 * The checks and small calculations behind showing custom themes in the panel: which themes may be used at
 * all, how their names are shown, which picture links are accepted, how a radius is scaled, how a choice falls
 * back to its base theme, and the values of the background layer. Pure functions, no DOM.
 * Loaded by the panel as a classic script (window.ThemeApply, after theme-palette.js) and by Node for tests.
 *
 * Nothing here builds CSS text: callers get single values to give to element.style.setProperty.
 */
(function (root, factory) {
    var isNode = typeof module === 'object' && module && module.exports;
    var api = factory(isNode ? require('./theme-palette.js') : root.ThemePalette);
    if (isNode) module.exports = api;
    else root.ThemeApply = api;
}(typeof window !== 'undefined' ? window : this, function (palette) {
    'use strict';

    /* The 17 built-in themes. Nebula is the one with no data-theme attribute. */
    var BUILT_IN = Object.freeze(['nebula', 'aurora', 'ember', 'crimson', 'dawn', 'dawn-dark', 'steel', 'verdant',
        'grove', 'grapevine', 'sumi', 'rasta', 'cipher', 'signal', 'victory', 'neon', 'revival']);

    /* The fonts a theme may use, each with the fallbacks the built-in themes give the same font. */
    var FONT_STACKS = Object.freeze({
        'Inter': "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
        'DM Sans': "'DM Sans', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
        'Outfit': "'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
        'Syne': "'Syne', 'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
        'Fraunces': "'Fraunces', Georgia, 'Times New Roman', serif",
        'IBM Plex Mono': "'IBM Plex Mono', ui-monospace, 'Cascadia Code', 'Consolas', monospace",
        'Noto Sans JP': "'Noto Sans JP', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
        'Shippori Mincho': "'Shippori Mincho', 'Noto Serif JP', 'Yu Mincho', Georgia, serif"
    });

    /* Every custom property of the stylesheet that holds a corner radius (a test keeps this list complete). */
    var RADIUS_VARS = Object.freeze(['--card-radius', '--col-radius', '--col-resize-radius', '--logo-icon-radius',
        '--lyrics-line-radius', '--lyrics-scroll-radius', '--pane-drag-radius', '--radius-btn', '--radius-dropdown',
        '--radius-input', '--radius-login-box', '--radius-logout', '--radius-select', '--upload-zone-radius']);

    var UNTITLED = 'Untitled theme';
    var UNKNOWN_PERSON = 'someone';
    var CUSTOM_PREFIX = 'custom:';

    var THEME_ID = /^[a-z0-9]{12}$/;
    var COLOR = /^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/i;
    /* The secret link of a theme's picture, exactly as the server makes it. Anything else is not used. */
    var PICTURE_URL = /^\/theme-images\/[0-9a-f]{32}\.(?:jpg|png|webp|gif)$/;
    var ANIMATED_URL = /\.(?:gif|webp)$/;

    /* Direction controls: they could reorder the text around a name, so they are never shown. */
    var BIDI_CONTROLS = /[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g;
    /* What leaves nothing to see: spaces, controls, zero-width and filler characters, variation selectors, tags. */
    var INVISIBLE = /[\u0000-\u0020\u007F-\u00A0\u00AD\u034F\u115F\u1160\u17B4\u17B5\u180B-\u180F\u2000-\u200F\u2028-\u202F\u205F-\u206F\u2800\u3000\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF9-\uFFFB\u{1D173}-\u{1D17A}\u{E0000}-\u{E0FFF}]/gu;

    var FSI = '\u2068';
    var PDI = '\u2069';
    var DASH = '\u2014';

    function isBuiltIn(id) {
        return typeof id === 'string' && BUILT_IN.indexOf(id) >= 0;
    }

    /* ---- pictures ---- */

    /* The link when it is one of our secret picture links, else null. */
    function pictureUrl(url) {
        return typeof url === 'string' && PICTURE_URL.test(url) ? url : null;
    }

    /* Can this picture move (GIF, WebP)? Reduce motion shows those as their first frame. */
    function canAnimate(url) {
        return typeof url === 'string' && ANIMATED_URL.test(url);
    }

    function number(value) {
        var n = typeof value === 'number' ? value : Number(value);
        return isFinite(n) ? n : 0;
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    /*
     * The values for the background layer, all strings ready for setProperty, or null when the theme has no
     * usable picture. The picture layer reaches 2x the blur radius past the screen on every side, so the
     * blur's soft edge never shows; fit 'center' keeps the picture's own size.
     */
    function backdrop(background) {
        if (!background || typeof background !== 'object') return null;
        var url = pictureUrl(background.url);
        if (!url) return null;
        var dim = clamp(number(background.dim), 0, 90);
        var blur = clamp(number(background.blur), 0, 20);
        return {
            url: url,
            size: background.fit === 'contain' ? 'contain' : (background.fit === 'center' ? 'auto' : 'cover'),
            position: 'center',
            repeat: 'no-repeat',
            filter: blur > 0 ? 'blur(' + blur + 'px)' : 'none',
            inset: blur > 0 ? '-' + (2 * blur) + 'px' : '0px',
            overlay: 'rgba(0, 0, 0, ' + (dim / 100) + ')'
        };
    }

    /* ---- radius and font ---- */

    /* "12px" at 50% is "6px". Null for anything that is not a plain, positive pixel length. The scale is held to 0..200. */
    function scaleRadius(value, percent) {
        var m = typeof value === 'string' ? /^\s*(\d+(?:\.\d+)?)px\s*$/.exec(value) : null;
        var p = typeof percent === 'number' ? percent : NaN;
        if (!m || !isFinite(p) || parseFloat(m[1]) === 0) return null;
        return (Math.round(parseFloat(m[1]) * clamp(p, 0, 200)) / 100) + 'px';
    }

    /* The font-family value for one of the fonts, or null. */
    function fontStack(font) {
        return typeof font === 'string' && Object.prototype.hasOwnProperty.call(FONT_STACKS, font) ? FONT_STACKS[font] : null;
    }

    /* ---- names ---- */

    /* The name without direction controls and without spaces at the ends; '' when nothing of it can be seen. */
    function visibleName(name) {
        if (typeof name !== 'string') return '';
        var text = name.replace(BIDI_CONTROLS, '');
        return text.replace(INVISIBLE, '') === '' ? '' : text.trim();
    }

    /* A theme's name as shown: see visibleName; "Untitled theme" when there is nothing to see. */
    function displayName(name) {
        return visibleName(name) || UNTITLED;
    }

    /* Text that cannot reorder what is around it, whatever direction its characters run in. */
    function isolate(text) {
        return FSI + text + PDI;
    }

    /* A person's name as shown: without direction controls, "someone" when there is nothing to see. */
    function personName(name) {
        return visibleName(name) || UNKNOWN_PERSON;
    }

    /* How a community theme reads in the picker: "Name -- by creator" (an em dash), each part isolated. */
    function communityLabel(theme) {
        return isolate(displayName(theme.name)) + ' ' + DASH + ' by ' + isolate(personName(theme.owner ? theme.owner.name : null));
    }

    /* How one of your own themes reads in the picker; private ones, and ones the bot owner hid, are marked. */
    function mineLabel(theme) {
        var label = isolate(displayName(theme.name));
        if (theme.hiddenByOwner === true) return label + ' (hidden by the bot owner)';
        return theme.visibility === 'private' ? label + ' (private)' : label;
    }

    /* ---- which themes are used ---- */

    /* The id in a stored choice like "custom:k3j9x0q2m7ab", or null. */
    function customId(choice) {
        if (typeof choice !== 'string' || choice.indexOf(CUSTOM_PREFIX) !== 0) return null;
        var id = choice.slice(CUSTOM_PREFIX.length);
        return THEME_ID.test(id) ? id : null;
    }

    function isObject(value) {
        return !!value && typeof value === 'object' && !Array.isArray(value);
    }

    /*
     * May this theme from the server be shown and applied? It needs a proper id, a known base theme, a name and
     * all 15 colors as #rrggbb(aa). Values the page puts into CSS are checked here as well as on the server.
     */
    function usable(theme) {
        if (!isObject(theme) || typeof theme.id !== 'string' || !THEME_ID.test(theme.id) || typeof theme.name !== 'string') return false;
        if (!isBuiltIn(theme.base) || !isObject(theme.colors) || !palette || !palette.SLOTS) return false;
        for (var i = 0; i < palette.SLOTS.length; i++) {
            var color = theme.colors[palette.SLOTS[i]];
            if (typeof color !== 'string' || !COLOR.test(color)) return false;
        }
        return true;
    }

    function byName(a, b) {
        var x = displayName(a.name).toLowerCase(), y = displayName(b.name).toLowerCase();
        if (x !== y) return x < y ? -1 : 1;
        return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0);
    }

    /* The usable themes as {community, mine}, each sorted by name. Community = other people's, which are all public. */
    function groupThemes(themes) {
        var groups = { community: [], mine: [] };
        if (!Array.isArray(themes)) return groups;
        themes.filter(usable).forEach(function (theme) {
            if (theme.mine === true) groups.mine.push(theme);
            else if (theme.visibility === 'public') groups.community.push(theme);
        });
        groups.community.sort(byName);
        groups.mine.sort(byName);
        return groups;
    }

    /*
     * What a stored choice (a built-in id or "custom:<id>") means right now. themes is the loaded list, or null
     * before it has loaded; baseHint is the base theme remembered with a custom choice.
     *  - {kind: 'builtin', id}                         a built-in theme (anything unknown is Nebula)
     *  - {kind: 'custom', theme}                       a theme from the list
     *  - {kind: 'builtin', id, fellBack: true}         the custom theme is gone or unusable: its base (else Nebula)
     *  - {kind: 'builtin', id, pending: true}          the list isn't here yet: the base for now, the choice is kept
     */
    function resolveChoice(choice, themes, baseHint) {
        if (choice === 'nihon') choice = 'sumi';
        var id = customId(choice);
        if (id === null) return { kind: 'builtin', id: isBuiltIn(choice) ? choice : 'nebula' };
        var base = isBuiltIn(baseHint) ? baseHint : 'nebula';
        if (!Array.isArray(themes)) return { kind: 'builtin', id: base, pending: true };
        for (var i = 0; i < themes.length; i++) {
            if (themes[i] && themes[i].id === id && usable(themes[i])) return { kind: 'custom', theme: themes[i] };
        }
        return { kind: 'builtin', id: base, fellBack: true };
    }

    return {
        BUILT_IN: BUILT_IN,
        FONT_STACKS: FONT_STACKS,
        RADIUS_VARS: RADIUS_VARS,
        UNTITLED: UNTITLED,
        pictureUrl: pictureUrl,
        canAnimate: canAnimate,
        backdrop: backdrop,
        scaleRadius: scaleRadius,
        fontStack: fontStack,
        displayName: displayName,
        personName: personName,
        isolate: isolate,
        communityLabel: communityLabel,
        mineLabel: mineLabel,
        customId: customId,
        usable: usable,
        groupThemes: groupThemes,
        resolveChoice: resolveChoice
    };
}));
