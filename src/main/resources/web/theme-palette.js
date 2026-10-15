/*
 * Theme palette math for the guided theme creator: build a full palette from two colors, check and fix
 * readability, suggest backgrounds and a "Surprise me" pair, derive the mini-player palette and the panel's
 * CSS variables, and suggest how much to darken a background picture. Pure functions, no DOM.
 * Loaded by the panel as a classic script (window.ThemePalette) and by Node for tests (module.exports).
 *
 * Colors in and out are strings: "#rrggbb" or "#rrggbbaa" (the same rule the server enforces for a theme's
 * colors); functions that build colors return lower case. Anything else throws a TypeError.
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module && module.exports) module.exports = api;
    else root.ThemePalette = api;
}(typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /* The 15 color slots of a theme, and the 14 of its mini-player version (no glass). */
    var SLOTS = Object.freeze(['bgPrimary', 'bgSecondary', 'bgCard', 'bgElevated', 'bgHover',
        'accent', 'accentLight', 'accent2', 'textPrimary', 'textSecondary', 'textTertiary',
        'border', 'borderLight', 'success', 'glass']);
    var MINI_SLOTS = Object.freeze(SLOTS.filter(function (slot) { return slot !== 'glass'; }));

    /* WCAG contrast bars: main text on the page and on cards, secondary text on cards. */
    var MAIN_BAR = 4.5;
    var SECONDARY_BAR = 3;

    /* How finely the text-fixing search walks the lightness range (1 / this per step). */
    var SEARCH_STEPS = 1000;

    var BLACK = { r: 0, g: 0, b: 0, a: 1 };

    /* ---- Colors as {r, g, b, a}: bytes 0..255 and alpha 0..1 ---- */

    function parseHex(hex) {
        var m = typeof hex === 'string' ? /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.exec(hex) : null;
        if (!m) throw new TypeError('Not a color: ' + hex);
        var h = m[1];
        if (h.length <= 4) h = h.replace(/./g, '$&$&');
        return {
            r: parseInt(h.substr(0, 2), 16),
            g: parseInt(h.substr(2, 2), 16),
            b: parseInt(h.substr(4, 2), 16),
            a: h.length === 8 ? parseInt(h.substr(6, 2), 16) / 255 : 1
        };
    }

    function byte(v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    function hex2(v) {
        var s = byte(v).toString(16);
        return s.length < 2 ? '0' + s : s;
    }

    /* "#rrggbb", or "#rrggbbaa" when the alpha (given, else the color's own) is below 1. */
    function toHex(c, alpha) {
        var a = alpha === undefined ? (c.a === undefined ? 1 : c.a) : alpha;
        var ab = byte(a * 255);
        return '#' + hex2(c.r) + hex2(c.g) + hex2(c.b) + (ab === 255 ? '' : hex2(ab));
    }

    /* What a color looks like drawn over an opaque background (the way a browser rounds it). */
    function over(fg, bg) {
        return {
            r: byte(fg.r * fg.a + bg.r * (1 - fg.a)),
            g: byte(fg.g * fg.a + bg.g * (1 - fg.a)),
            b: byte(fg.b * fg.a + bg.b * (1 - fg.a)),
            a: 1
        };
    }

    function mix(a, b, shareOfA) {
        return {
            r: byte(a.r * shareOfA + b.r * (1 - shareOfA)),
            g: byte(a.g * shareOfA + b.g * (1 - shareOfA)),
            b: byte(a.b * shareOfA + b.b * (1 - shareOfA)),
            a: 1
        };
    }

    function clamp01(v) {
        return Math.max(0, Math.min(1, v));
    }

    function toHsl(c) {
        var r = c.r / 255, g = c.g / 255, b = c.b / 255;
        var max = Math.max(r, g, b), min = Math.min(r, g, b);
        var l = (max + min) / 2, d = max - min, h = 0, s = 0;
        if (d > 0) {
            s = clamp01(d / (1 - Math.abs(2 * l - 1)));
            if (max === r) h = ((g - b) / d) % 6;
            else if (max === g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h = (h * 60 + 360) % 360;
        }
        return { h: h, s: s, l: l };
    }

    function fromHsl(h, s, l) {
        h = ((h % 360) + 360) % 360;
        s = clamp01(s);
        l = clamp01(l);
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
        return { r: byte((rgb[0] + m) * 255), g: byte((rgb[1] + m) * 255), b: byte((rgb[2] + m) * 255), a: 1 };
    }

    /* ---- Contrast (WCAG 2.x) ---- */

    function linear(channel) {
        var c = channel / 255;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    function luminance(c) {
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b);
    }

    /* Contrast ratio of two opaque colors, 1..21. */
    function ratio(a, b) {
        var la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /* WCAG contrast ratio (1..21) of two colors; a color with alpha is composited on black first. */
    function contrast(hexA, hexB) {
        return ratio(over(parseHex(hexA), BLACK), over(parseHex(hexB), BLACK));
    }

    var WHITE = { r: 255, g: 255, b: 255, a: 1 };

    /*
     * The color for what sits on top of the main color (the play icon on the accent): '#000000' or '#ffffff',
     * whichever contrasts more with it (white when they tie). An accent with alpha counts as contrast() counts it,
     * over black. The desktop mini-player derives the same color from the same rule (desktop/ui/mini-player/view.js).
     */
    function onAccent(accentHex) {
        var accent = over(parseHex(accentHex), BLACK);
        return ratio(BLACK, accent) > ratio(WHITE, accent) ? '#000000' : '#ffffff';
    }

    /* ---- Readability ---- */

    /* The page color over black, and the card over the page: what the text really sits on. */
    function surfaces(colors) {
        var page = over(parseHex(colors.bgPrimary), BLACK);
        return { page: page, card: over(parseHex(colors.bgCard), page) };
    }

    /* Does the text (alpha composited over each background) reach the ratio on every background? */
    function reaches(text, backgrounds, min) {
        for (var i = 0; i < backgrounds.length; i++) {
            if (ratio(over(text, backgrounds[i]), backgrounds[i]) < min) return false;
        }
        return true;
    }

    function worstRatio(text, backgrounds) {
        var worst = Infinity;
        for (var i = 0; i < backgrounds.length; i++) {
            worst = Math.min(worst, ratio(over(text, backgrounds[i]), backgrounds[i]));
        }
        return worst;
    }

    /*
     * Text color that reaches the ratio on every background, found by moving only the lightness (hue and
     * saturation stay). Already fine: returned as given. Otherwise the nearest lightness that works, either
     * way. When no lightness works (two backgrounds that neither light nor dark text can serve), whichever of
     * pure black or pure white does best.
     */
    function fixText(hex, backgrounds, min) {
        var text = parseHex(hex);
        if (reaches(text, backgrounds, min)) return hex;
        var hsl = toHsl(text);
        for (var step = 1; step <= SEARCH_STEPS; step++) {
            var best = null, bestWorst = 0;
            var tries = [Math.max(0, hsl.l - step / SEARCH_STEPS), Math.min(1, hsl.l + step / SEARCH_STEPS)];
            for (var i = 0; i < tries.length; i++) {
                var candidate = fromHsl(hsl.h, hsl.s, tries[i]);
                if (!reaches(candidate, backgrounds, min)) continue;
                var worst = worstRatio(candidate, backgrounds);
                if (!best || worst > bestWorst) { best = candidate; bestWorst = worst; }
            }
            if (best) return toHex(best);
        }
        var white = { r: 255, g: 255, b: 255, a: 1 };
        return worstRatio(BLACK, backgrounds) > worstRatio(white, backgrounds) ? '#000000' : '#ffffff';
    }

    /*
     * Do the main text and secondary text meet the bars (main text 4.5:1 on the page and on cards, secondary
     * text 3:1 on cards)? problems lists the text slots that don't.
     */
    function readability(colors) {
        var s = surfaces(colors);
        var problems = [];
        if (!reaches(parseHex(colors.textPrimary), [s.page, s.card], MAIN_BAR)) problems.push('textPrimary');
        if (!reaches(parseHex(colors.textSecondary), [s.card], SECONDARY_BAR)) problems.push('textSecondary');
        return { ok: problems.length === 0, problems: problems };
    }

    function copyColors(colors) {
        var out = {};
        for (var key in colors) {
            if (Object.prototype.hasOwnProperty.call(colors, key)) out[key] = colors[key];
        }
        return out;
    }

    /*
     * A copy of the colors with the main and secondary text moved just far enough (lightness only, same hue)
     * to meet the bars; nothing else changes. If the page and card colors are so far apart that no text can
     * reach 4.5:1 on both, the main text becomes whichever of pure black or white does best and readability()
     * still reports it.
     */
    function fixReadability(colors) {
        var s = surfaces(colors);
        var out = copyColors(colors);
        out.textPrimary = fixText(colors.textPrimary, [s.page, s.card], MAIN_BAR);
        out.textSecondary = fixText(colors.textSecondary, [s.card], SECONDARY_BAR);
        return out;
    }

    /* ---- Generating a palette ---- */

    /* Lightness steps from the background for the other surfaces (lighter in dark mode, darker in light mode). */
    var SURFACE_STEPS = [['bgSecondary', 0.035], ['bgCard', 0.065], ['bgElevated', 0.105], ['bgHover', 0.165]];
    /* What share of those steps is used, tried in turn, until the page and card allow readable text. */
    var STEP_SCALES = [1, 0.8, 0.6, 0.4, 0.2, 0];
    var ACCENT2_HUE_SHIFT = 35;

    /*
     * The 15 color slots from a main (accent) color and a background color. The accent is kept as given;
     * the background becomes bgPrimary; the other surfaces step away from it (lighter in 'dark' mode, darker
     * in 'light', or the other way when the background has no room); text is near-white / near-black and then
     * fixed, so the result always meets the readability bars.
     */
    function generate(accentHex, backgroundHex, mode) {
        var accent = parseHex(accentHex);
        var background = over(parseHex(backgroundHex), BLACK);
        var bg = toHsl(background);

        var direction = mode === 'light' ? -1 : 1;
        var reach = SURFACE_STEPS[SURFACE_STEPS.length - 1][1];
        if (bg.l + direction * reach > 0.98 || bg.l + direction * reach < 0.02) direction = -direction;
        var lightTheme = direction < 0;

        // Text leans a little toward the background's hue, never more than a tint.
        var tintS = Math.min(bg.s, 0.3);
        var mainText = toHex(fromHsl(bg.h, tintS, lightTheme ? 0.09 : 0.95));

        // Where the card is too far from the page for any text to work, ease the steps off until it isn't.
        var surfacesOut, text, s;
        for (var i = 0; i < STEP_SCALES.length; i++) {
            surfacesOut = {};
            for (var k = 0; k < SURFACE_STEPS.length; k++) {
                surfacesOut[SURFACE_STEPS[k][0]] = fromHsl(bg.h, bg.s, bg.l + direction * SURFACE_STEPS[k][1] * STEP_SCALES[i]);
            }
            s = { page: background, card: surfacesOut.bgCard };
            text = fixText(mainText, [s.page, s.card], MAIN_BAR);
            if (reaches(parseHex(text), [s.page, s.card], MAIN_BAR)) break;
        }

        // Quieter text is the main text faded into the card, so it is always quieter than the main text.
        var textRgb = parseHex(text);
        var secondary = fixText(toHex(mix(textRgb, s.card, lightTheme ? 0.72 : 0.62)), [s.card], SECONDARY_BAR);
        var tertiary = toHex(mix(textRgb, s.card, lightTheme ? 0.52 : 0.4));

        var a = toHsl(accent);
        var accentLight = fromHsl(a.h, a.s, a.l <= 0.7 ? a.l + 0.12 : a.l - 0.12);
        var accent2 = fromHsl(a.h + ACCENT2_HUE_SHIFT, a.s, a.l);

        var overlay = lightTheme ? { r: 0, g: 0, b: 0 } : { r: 255, g: 255, b: 255 };
        var success = fixText(lightTheme ? '#059669' : '#34d399', [s.card], SECONDARY_BAR);

        return {
            bgPrimary: toHex(background),
            bgSecondary: toHex(surfacesOut.bgSecondary),
            bgCard: toHex(surfacesOut.bgCard),
            bgElevated: toHex(surfacesOut.bgElevated),
            bgHover: toHex(surfacesOut.bgHover),
            accent: toHex(accent),
            accentLight: toHex(accentLight),
            accent2: toHex(accent2),
            textPrimary: text,
            textSecondary: secondary,
            textTertiary: tertiary,
            border: toHex(overlay, lightTheme ? 0.08 : 0.07),
            borderLight: toHex(overlay, 0.12),
            success: success,
            glass: toHex(surfacesOut.bgSecondary, 0.88)
        };
    }

    /*
     * Four backgrounds that suit the main color, for the given mode ('dark' or 'light'): tinted toward its
     * hue (a grey main color gets plain greys), varied in depth and in hue.
     */
    function suggestBackgrounds(accentHex, mode) {
        var a = toHsl(parseHex(accentHex));
        var tint = Math.min(1, a.s / 0.5);
        // [hue shift, saturation, lightness]
        var specs = mode === 'light'
            ? [[0, 0.45, 0.95], [0, 0.12, 0.98], [-40, 0.35, 0.93], [0, 0.05, 0.9]]
            : [[0, 0.35, 0.07], [0, 0.2, 0.13], [40, 0.3, 0.08], [0, 0.04, 0.1]];
        return specs.map(function (spec) {
            return toHex(fromHsl(a.h + spec[0], spec[1] * tint, spec[2]));
        });
    }

    /*
     * A random good-looking pair: {accent, background, mode}. The only source of randomness is randomFn
     * (returns 0 up to, not including, 1) so tests can seed it. The accent is nudged until it stands out
     * from the background (3:1).
     */
    function surprise(randomFn) {
        if (typeof randomFn !== 'function') throw new TypeError('surprise needs a random function');
        function next() {
            var v = Number(randomFn());
            return v >= 0 ? Math.min(v, 0.999999) : 0;
        }
        function between(lo, hi) {
            return lo + (hi - lo) * next();
        }
        var mode = next() < 0.7 ? 'dark' : 'light';
        var hue = between(0, 360);
        var accentS = between(0.55, 0.9);
        var accentL = mode === 'dark' ? between(0.5, 0.68) : between(0.34, 0.5);
        var bgHue = hue + between(-40, 40);
        var bgS = mode === 'dark' ? between(0.15, 0.45) : between(0.2, 0.6);
        var bgL = mode === 'dark' ? between(0.05, 0.12) : between(0.93, 0.97);
        var background = fromHsl(bgHue, bgS, bgL);
        var accent = fixText(toHex(fromHsl(hue, accentS, accentL)), [background], 3);
        return { accent: accent, background: toHex(background), mode: mode };
    }

    /* ---- Deriving other forms ---- */

    /* The mini-player version's 14 slots: the theme's colors without glass (a copy). */
    function mini(colors) {
        var out = {};
        for (var i = 0; i < MINI_SLOTS.length; i++) {
            if (Object.prototype.hasOwnProperty.call(colors, MINI_SLOTS[i])) out[MINI_SLOTS[i]] = colors[MINI_SLOTS[i]];
        }
        return out;
    }

    /*
     * The panel's CSS variables for a theme's colors: the slots as they are, plus the derived accent glow
     * (accent at 32% alpha), accent gradient (accent to accent2) and the accent colors as decimal r/g/b.
     * Values are meant for element.style.setProperty, one variable at a time.
     */
    function cssVars(colors) {
        var accent = parseHex(colors.accent);
        var accent2 = parseHex(colors.accent2);
        return {
            '--bg-primary': colors.bgPrimary,
            '--bg-secondary': colors.bgSecondary,
            '--bg-card': colors.bgCard,
            '--bg-elevated': colors.bgElevated,
            '--bg-hover': colors.bgHover,
            '--accent': colors.accent,
            '--accent-light': colors.accentLight,
            '--accent-glow': toHex(accent, 0.32),
            '--accent-gradient': 'linear-gradient(135deg, ' + colors.accent + ', ' + colors.accent2 + ')',
            '--text-primary': colors.textPrimary,
            '--text-secondary': colors.textSecondary,
            '--text-tertiary': colors.textTertiary,
            '--border': colors.border,
            '--border-light': colors.borderLight,
            '--success': colors.success,
            '--glass': colors.glass,
            '--accent-r': String(accent.r),
            '--accent-g': String(accent.g),
            '--accent-b': String(accent.b),
            '--accent2-r': String(accent2.r),
            '--accent2-g': String(accent2.g),
            '--accent2-b': String(accent2.b)
        };
    }

    /*
     * How much to darken a background picture (0..90, whole numbers) given its average brightness
     * (0 = black .. 1 = white, as the eye sees it): none for black, rising smoothly to 80 for white.
     * Brightness that couldn't be measured gives a middling 40.
     */
    function suggestDim(averageLuminance) {
        if (typeof averageLuminance !== 'number' || !isFinite(averageLuminance)) return 40;
        return Math.round(80 * Math.pow(clamp01(averageLuminance), 0.85));
    }

    return {
        SLOTS: SLOTS,
        MINI_SLOTS: MINI_SLOTS,
        contrast: contrast,
        onAccent: onAccent,
        generate: generate,
        readability: readability,
        fixReadability: fixReadability,
        suggestBackgrounds: suggestBackgrounds,
        surprise: surprise,
        mini: mini,
        cssVars: cssVars,
        suggestDim: suggestDim
    };
}));
