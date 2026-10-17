'use strict';
/*
 * Colours in the themes' stylesheets, for tests: reads a stylesheet, finds every colour a built-in theme's own rules
 * paint with, and evaluates colour expressions with that theme's variables the way a browser would.
 *
 * - evaluate(expr, vars) -> [r, g, b, a]: #hex (3/4/6/8 digits), rgb()/rgba() (numbers, percentages, var() triples),
 *   hsl()/hsla(), named colours and transparent, var(--x[, fallback]) resolved from vars, and
 *   color-mix(in srgb, A [p%], B [q%]) as CSS Color 5 mixes (premultiplied by alpha), nested, calc() weights allowed.
 *   r, g, b are 0..255 and a is 0..1, not rounded. Anything else throws.
 * - themeVars(css, themeId[, at]): the custom properties the theme's <html> has: :root / html and the theme block
 *   (guarded or not), each property from the declaration that wins in the browser with every piece on; with at (an
 *   @media list) the copies in that @media count too.
 * - decorationColours(css, themeId) -> [{ key, expr, vars, via }]: every colour in the theme's rules (selectors starting
 *   with html[data-theme="id"], or :root / html:not([data-theme]) for Nebula; custom-theme rules belong to no theme)
 *   and in the @keyframes those rules animate with. key = selector + "|" + property + "|" + n, where the selector is
 *   normalized (whitespace and combinators spaced one way, piece guards :not([data-deco-off~="..."]) removed, an
 *   @media / @supports condition in front) and n counts the colours in the declaration's value in order. A var() that
 *   holds one colour counts as one; a var() that holds more (a shadow, a gradient) is read through (via: the
 *   variables a colour was read through, [] for one written in the rule).
 *   - Declarations that end up with the same key are ranked as the browser ranks them with every piece on:
 *     !important, then specificity (each guard weighs one attribute), then document order.
 *   - Every variable is resolved at html. So a theme rule that sets a variable anywhere but the theme block (or its
 *     @media copies) is refused, and so is a colour that uses a variable some other rule sets below html.
 *   - The theme's accent triples are pinned as synthetic keys <block>|--accent-rgb|0 and <block>|--accent2-rgb|0
 *     (shared rules paint with rgba(var(--accent-r), ...)).
 *   Excluded: inside url(). Colours drawn inside pictures (data URIs, SVG files) are not colours of the stylesheet.
 * - declarationColours(prop, value[, vars]): the colour expressions of one declaration, read the same way.
 * - snapshot(css, themeId) -> { key: "rgba(r,g,b,a)" }.
 * - compareToGolden(golden, css, themeId, { gone, added, changed }): changed, gone and new colours (a new key counts
 *   unless it is a custom property or listed in added, { key: "rgba(r,g,b,a)" }, with the colour it is pinned to as a
 *   golden one is; a key listed in changed may differ only as a variable of the theme block, not a colour slot
 *   (isSlot), whose colour turned plain black, white or see-through: a decoration colour taken out of a variable a
 *   shared rule paints with, the colours of the theme's own rules still pinned by their keys).
 * - isSlot(name): a variable a custom theme sets (the names ThemePalette.cssVars gives), or the synthetic accent
 *   triples --accent-rgb / --accent2-rgb.
 * - checkSheet(kind[, css]): compareToGolden for every theme of the golden file of 'panel' or 'mini', with the reviewed
 *   allowlists of src/test/resources/theme-golden/allowlist.json: { "goneOnPurpose" | "changedOnPurpose" |
 *   "flipsOnPurpose": { "panel" | "mini": { "<theme>": { "<key>": "<why>" } } }, "newOnPurpose": { ... { "<key>":
 *   { "why": "<why>", "is": "rgba(r,g,b,a)" } } } }; allowedFor(kind, themeId) gives one theme's, as compareToGolden
 *   takes them.
 *
 * Shapes (everything else a theme's rules set: geometry, opacity, blur, sizes, positions, layers, animations, z-index,
 * display, ...):
 * - shapeValue(prop, value[, vars]): a value with its colours taken out. Spaced one way (one space between words, ", "
 *   between items, none inside brackets); every colour expression (hex, rgb()/hsl()/color-mix()/..., a named colour or
 *   transparent, a var() that holds one colour with vars) written <c>; strings and url() as written; a property whose
 *   words are names (font, animation, content, ...) only spaced.
 * - shapes(css, themeId) -> { key: value }: every declaration of the theme's rules and of the @keyframes they animate
 *   with, as decorationColours reads them (the same keys without |n, ranked the same way), value = shapeValue, then
 *   " !important" when it is. Left to the colours: a value that is one colour alone (<c>), and the accent triples
 *   (--accent-r/g/b, --accent2-r/g/b).
 * - compareShapes(golden, css, themeId, { gone, added, changed }): a shape gone (unless listed in gone), changed (unless
 *   listed in changed, { key: value }: then it must be that value) or new (unless listed in added, { key: value }, the
 *   same way; a new variable counts too: it is a shape until a rule reads it), and a listed key that doesn't fit.
 * - checkShapes(kind[, css]): compareShapes for every theme of the shape golden of 'panel' or 'mini' (shapeGoldenFile:
 *   theme-golden/panel-shapes.json, mini-shapes.json), with allowlist.json's "shapeGoneOnPurpose": { kind: { theme:
 *   { key: "<why>" } } }, "shapeNewOnPurpose" and "shapeChangedOnPurpose": { kind: { theme: { key: { "why", "is" } } } };
 *   allowedShapesFor(kind, themeId) gives one theme's.
 * - ORIGINAL: the commit the golden files are written from (the last before the rewrite); originalCss(kind) is that
 *   commit's stylesheet, read with git (the script's; a shallow clone hasn't got it).
 *
 * Pieces (selectors as written, guards and all):
 * - guardsOf(selector) -> { ids, mentions }: the ids of its :not([data-deco-off~="id"]) guards, and how often
 *   data-deco-off appears in it.
 * - splitFirst(selector) -> { head, rest }: its first compound and the rest. htmlMatches(selector, attrs): does its
 *   <html> part match <html> with these attributes (null for a selector that doesn't start at html / :root)?
 *   attributesMatch(text, attrs) does the same for a string of [attribute] selectors, .classes (attrs.class) and :not()s.
 * - customThemeOf(selector): the theme a custom-theme rule (html[data-custom-theme][data-theme="id"] ...) builds on.
 * - cascadeFlips(css, themeIds[, statesOf]) -> [{ theme, piece, key, restored }]: a guard weighs one attribute, so a
 *   guarded declaration T of the theme (or of its custom-theme rules) can beat a rule S that beat it before. S is any
 *   unguarded rule that applies while the theme is on (shared, the theme's own, or only with a custom theme or reduced
 *   motion on: the panel's states of <html>; statesOf(themeId) gives another page's, the mini-player's compact bar),
 *   styling what can be the same element (their last compounds share a class or id name, or one is named by a tag
 *   alone that the other has; same pseudo-element) in the same property family (propertyFamily). It flips when S beats
 *   T without its guard and T with it beats S. The theme restores S with a rule under T's piece whose html part has
 *   T's attributes and S's own (if S starts at html), followed by the rest of S's selector, in S's @media, with S's
 *   property and value, beating T. key: "<T> <- <S>", each where|property (where = [@media ] normalized selector).
 *   Not caught: a rule that names the same element only by other classes (".controls .ctrl-btn-primary:hover" against
 *   a guarded ".ctrl-btn", when the primary button carries both): elements are matched by the names the selectors
 *   share, not by what the page's elements carry.
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const ThemePalette = require('../../main/resources/web/theme-palette.js');

const ROOT = path.join(__dirname, '../../..');

const NAMED = (function () {
  const out = { transparent: [0, 0, 0, 0] };
  ('aliceblue:f0f8ff antiquewhite:faebd7 aqua:00ffff aquamarine:7fffd4 azure:f0ffff beige:f5f5dc bisque:ffe4c4 ' +
    'black:000000 blanchedalmond:ffebcd blue:0000ff blueviolet:8a2be2 brown:a52a2a burlywood:deb887 ' +
    'cadetblue:5f9ea0 chartreuse:7fff00 chocolate:d2691e coral:ff7f50 cornflowerblue:6495ed cornsilk:fff8dc ' +
    'crimson:dc143c cyan:00ffff darkblue:00008b darkcyan:008b8b darkgoldenrod:b8860b darkgray:a9a9a9 ' +
    'darkgreen:006400 darkgrey:a9a9a9 darkkhaki:bdb76b darkmagenta:8b008b darkolivegreen:556b2f ' +
    'darkorange:ff8c00 darkorchid:9932cc darkred:8b0000 darksalmon:e9967a darkseagreen:8fbc8f ' +
    'darkslateblue:483d8b darkslategray:2f4f4f darkslategrey:2f4f4f darkturquoise:00ced1 darkviolet:9400d3 ' +
    'deeppink:ff1493 deepskyblue:00bfff dimgray:696969 dimgrey:696969 dodgerblue:1e90ff firebrick:b22222 ' +
    'floralwhite:fffaf0 forestgreen:228b22 fuchsia:ff00ff gainsboro:dcdcdc ghostwhite:f8f8ff gold:ffd700 ' +
    'goldenrod:daa520 gray:808080 green:008000 greenyellow:adff2f grey:808080 honeydew:f0fff0 hotpink:ff69b4 ' +
    'indianred:cd5c5c indigo:4b0082 ivory:fffff0 khaki:f0e68c lavender:e6e6fa lavenderblush:fff0f5 ' +
    'lawngreen:7cfc00 lemonchiffon:fffacd lightblue:add8e6 lightcoral:f08080 lightcyan:e0ffff ' +
    'lightgoldenrodyellow:fafad2 lightgray:d3d3d3 lightgreen:90ee90 lightgrey:d3d3d3 lightpink:ffb6c1 ' +
    'lightsalmon:ffa07a lightseagreen:20b2aa lightskyblue:87cefa lightslategray:778899 lightslategrey:778899 ' +
    'lightsteelblue:b0c4de lightyellow:ffffe0 lime:00ff00 limegreen:32cd32 linen:faf0e6 magenta:ff00ff ' +
    'maroon:800000 mediumaquamarine:66cdaa mediumblue:0000cd mediumorchid:ba55d3 mediumpurple:9370db ' +
    'mediumseagreen:3cb371 mediumslateblue:7b68ee mediumspringgreen:00fa9a mediumturquoise:48d1cc ' +
    'mediumvioletred:c71585 midnightblue:191970 mintcream:f5fffa mistyrose:ffe4e1 moccasin:ffe4b5 ' +
    'navajowhite:ffdead navy:000080 oldlace:fdf5e6 olive:808000 olivedrab:6b8e23 orange:ffa500 ' +
    'orangered:ff4500 orchid:da70d6 palegoldenrod:eee8aa palegreen:98fb98 paleturquoise:afeeee ' +
    'palevioletred:db7093 papayawhip:ffefd5 peachpuff:ffdab9 peru:cd853f pink:ffc0cb plum:dda0dd ' +
    'powderblue:b0e0e6 purple:800080 rebeccapurple:663399 red:ff0000 rosybrown:bc8f8f royalblue:4169e1 ' +
    'saddlebrown:8b4513 salmon:fa8072 sandybrown:f4a460 seagreen:2e8b57 seashell:fff5ee sienna:a0522d ' +
    'silver:c0c0c0 skyblue:87ceeb slateblue:6a5acd slategray:708090 slategrey:708090 snow:fffafa ' +
    'springgreen:00ff7f steelblue:4682b4 tan:d2b48c teal:008080 thistle:d8bfd8 tomato:ff6347 ' +
    'turquoise:40e0d0 violet:ee82ee wheat:f5deb3 white:ffffff whitesmoke:f5f5f5 yellow:ffff00 ' +
    'yellowgreen:9acd32').split(' ').forEach(function (pair) {
    const parts = pair.split(':');
    out[parts[0]] = parseHex('#' + parts[1]);
  });
  return out;
})();

/* Functions whose whole call is one colour. */
const COLOUR_FUNCTIONS = new Set(['rgb', 'rgba', 'hsl', 'hsla', 'hwb', 'lab', 'lch', 'oklab', 'oklch', 'color',
  'color-mix', 'light-dark']);

/* Properties whose words are names, not colours (an animation called "tan" is not a colour). */
const NOT_COLOURS = /^(font|font-family|animation|animation-name|transition|transition-property|will-change|content|quotes|counter-.*|grid-.*|list-style.*|view-transition-name|container|container-name|anchor-name|position-anchor)$/;

const MAX_DEPTH = 32;

// ---- small text helpers (strings and parentheses aware)

function endOfString(s, i) {
  const quote = s[i];
  let j = i + 1;
  while (j < s.length && s[j] !== quote) j += s[j] === '\\' ? 2 : 1;
  return Math.min(j + 1, s.length);
}

/* The index just past the bracket that closes the one at s[open] ("(" or "{"). */
function closing(s, open) {
  const pairs = { '(': ')', '{': '}', '[': ']' };
  const stack = [];
  for (let i = open; i < s.length; i++) {
    const c = s[i];
    if (c === '"' || c === "'") {
      i = endOfString(s, i) - 1;
      continue;
    }
    if (pairs[c]) stack.push(pairs[c]);
    else if (c === ')' || c === '}' || c === ']') {
      if (stack.pop() !== c) throw new Error('unbalanced ' + c + ' near: ' + s.slice(Math.max(0, i - 60), i + 1));
      if (stack.length === 0) return i + 1;
    }
  }
  throw new Error('unclosed ' + s[open] + ' near: ' + s.slice(open, open + 80));
}

/* The first index of ch outside strings, parentheses and brackets, or -1. */
function indexOfTop(s, ch) {
  let depth = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '"' || c === "'") {
      i = endOfString(s, i) - 1;
      continue;
    }
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth--;
    else if (depth === 0 && c === ch) return i;
  }
  return -1;
}

/* Splits at top-level commas, or (sep " ") at top-level whitespace, dropping empty parts of the latter. */
function splitTop(s, sep) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '"' || c === "'") {
      i = endOfString(s, i) - 1;
      continue;
    }
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth--;
    else if (depth === 0 && (sep === ',' ? c === ',' : /\s/.test(c))) {
      parts.push(s.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(s.slice(start));
  return sep === ',' ? parts.map((p) => p.trim()) : parts.map((p) => p.trim()).filter((p) => p !== '');
}

// ---- evaluating one colour

function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

function parseHex(text) {
  const m = /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.exec(text);
  if (!m) throw new Error('not a hex colour: ' + text);
  let h = m[1];
  if (h.length <= 4) h = h.split('').map((c) => c + c).join('');
  const n = (i) => parseInt(h.slice(i, i + 2), 16);
  return [n(0), n(2), n(4), h.length === 8 ? n(6) / 255 : 1];
}

/* calc() arithmetic on numbers and percentages: { value, percent }. */
function evalCalc(expr) {
  const tokens = [];
  let i = 0;
  while (i < expr.length) {
    const rest = expr.slice(i);
    let m;
    if (/^\s/.test(rest)) {
      i++;
    } else if ((m = /^(\d+\.?\d*|\.\d+)(e[-+]?\d+)?(%?)/i.exec(rest))) {
      tokens.push({ num: parseFloat(m[1] + (m[2] || '')), percent: m[3] === '%' });
      i += m[0].length;
    } else if ((m = /^calc\(/i.exec(rest))) {
      tokens.push('(');
      i += m[0].length;
    } else if ('+-*/()'.includes(rest[0])) {
      tokens.push(rest[0]);
      i++;
    } else {
      throw new Error('calc() with more than numbers and percentages: ' + expr);
    }
  }
  let at = 0;
  function factor() {
    const t = tokens[at++];
    if (t === '(') {
      const v = sum();
      if (tokens[at++] !== ')') throw new Error('unbalanced calc(): ' + expr);
      return v;
    }
    if (t === '-') {
      const v = factor();
      return { value: -v.value, percent: v.percent };
    }
    if (t && typeof t === 'object') return { value: t.num, percent: t.percent };
    throw new Error('bad calc(): ' + expr);
  }
  function product() {
    let v = factor();
    while (tokens[at] === '*' || tokens[at] === '/') {
      const op = tokens[at++];
      const w = factor();
      v = { value: op === '*' ? v.value * w.value : v.value / w.value, percent: v.percent || w.percent };
    }
    return v;
  }
  function sum() {
    let v = product();
    while (tokens[at] === '+' || tokens[at] === '-') {
      const op = tokens[at++];
      const w = product();
      v = { value: op === '+' ? v.value + w.value : v.value - w.value, percent: v.percent || w.percent };
    }
    return v;
  }
  const v = sum();
  if (at !== tokens.length) throw new Error('bad calc(): ' + expr);
  return v;
}

function numberOf(text) {
  const t = text.trim();
  if (/^calc\(/i.test(t) && closing(t, 4) === t.length) return evalCalc(t.slice(5, -1));
  const m = /^([-+]?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?)(%?)$/i.exec(t);
  if (!m) throw new Error('not a number: ' + t);
  return { value: parseFloat(m[1]), percent: m[2] === '%' };
}

function alphaOf(text) {
  const v = numberOf(text);
  return clamp(v.percent ? v.value / 100 : v.value, 0, 1);
}

/* "r, g, b[, a]" or "r g b[ / a]" -> [channels..., alpha text or undefined] */
function channelsAndAlpha(inner) {
  const commas = splitTop(inner, ',');
  if (commas.length > 1) return { channels: commas.slice(0, 3), alpha: commas[3], count: commas.length };
  const slash = indexOfTop(inner, '/');
  const channels = splitTop(slash < 0 ? inner : inner.slice(0, slash), ' ');
  return { channels: channels, alpha: slash < 0 ? undefined : inner.slice(slash + 1), count: channels.length + 1 };
}

function parseRgb(inner, text) {
  const parts = channelsAndAlpha(inner);
  if (parts.channels.length !== 3 || parts.count > 4) throw new Error('not an rgb() colour: ' + text);
  const ch = parts.channels.map((p) => {
    const v = numberOf(p);
    return clamp(v.percent ? v.value * 2.55 : v.value, 0, 255);
  });
  return ch.concat([parts.alpha === undefined ? 1 : alphaOf(parts.alpha)]);
}

function parseHsl(inner, text) {
  const parts = channelsAndAlpha(inner);
  if (parts.channels.length !== 3 || parts.count > 4) throw new Error('not an hsl() colour: ' + text);
  const h = ((parseFloat(parts.channels[0].replace(/deg$/i, '')) % 360) + 360) % 360;
  const s = clamp(numberOf(parts.channels[1]).value / 100, 0, 1);
  const l = clamp(numberOf(parts.channels[2]).value / 100, 0, 1);
  const f = (n) => {
    const k = (n + h / 30) % 12;
    return 255 * (l - s * Math.min(l, 1 - l) * Math.max(-1, Math.min(k - 3, 9 - k, 1)));
  };
  return [f(0), f(8), f(4), parts.alpha === undefined ? 1 : alphaOf(parts.alpha)];
}

function isWeight(token) {
  if (/%$/.test(token)) return true;
  if (!/^calc\(/i.test(token)) return false;
  try {
    return numberOf(token).percent;
  } catch (e) {
    return false;
  }
}

/* One colour of a color-mix(): [colour, weight or null]. */
function mixPart(arg, text) {
  const tokens = splitTop(arg, ' ');
  let weight = null;
  if (tokens.length > 1 && isWeight(tokens[0])) weight = numberOf(tokens.shift()).value;
  else if (tokens.length > 1 && isWeight(tokens[tokens.length - 1])) weight = numberOf(tokens.pop()).value;
  if (weight !== null && (weight < 0 || weight > 100)) throw new Error('color-mix() weight out of range: ' + text);
  return [parseColour(tokens.join(' ')), weight];
}

/* CSS Color 5 color-mix() in srgb: weights normalized, colours premultiplied by alpha, a sum under 100% thins it. */
function parseMix(inner, text) {
  const args = splitTop(inner, ',');
  if (args.length !== 3) throw new Error('color-mix() takes a space and two colours: ' + text);
  if (!/^in\s+srgb$/i.test(args[0])) throw new Error('only color-mix(in srgb, ...) is supported: ' + text);
  const [c1, p1] = mixPart(args[1], text);
  const [c2, p2] = mixPart(args[2], text);
  let w1 = p1;
  let w2 = p2;
  if (w1 === null && w2 === null) {
    w1 = 50;
    w2 = 50;
  } else if (w2 === null) {
    w2 = 100 - w1;
  } else if (w1 === null) {
    w1 = 100 - w2;
  }
  const total = w1 + w2;
  if (!(total > 0)) throw new Error('color-mix() weights add up to nothing: ' + text);
  const f1 = w1 / total;
  const f2 = w2 / total;
  const alpha = c1[3] * f1 + c2[3] * f2;
  const rgb = [0, 1, 2].map((i) => alpha === 0
    ? c1[i] * f1 + c2[i] * f2
    : (c1[i] * c1[3] * f1 + c2[i] * c2[3] * f2) / alpha);
  return rgb.concat([alpha * (total < 100 ? total / 100 : 1)]);
}

function parseColour(text) {
  const t = text.trim();
  const lower = t.toLowerCase();
  if (Object.prototype.hasOwnProperty.call(NAMED, lower)) return NAMED[lower].slice();
  if (t[0] === '#') return parseHex(t);
  const m = /^([a-z-]+)\(/i.exec(t);
  if (m && closing(t, m[1].length) === t.length) {
    const fn = m[1].toLowerCase();
    const inner = t.slice(m[1].length + 1, -1);
    if (fn === 'rgb' || fn === 'rgba') return parseRgb(inner, t);
    if (fn === 'hsl' || fn === 'hsla') return parseHsl(inner, t);
    if (fn === 'color-mix') return parseMix(inner, t);
  }
  throw new Error('not a colour: ' + t);
}

/*
 * Replaces every var() with its value from vars (or its fallback); throws on an undefined one or a loop. The names
 * it looks up go into used, when given.
 */
function substitute(text, vars, depth, used) {
  depth = depth || 0;
  if (depth > MAX_DEPTH) throw new Error('variables refer to each other in a loop: ' + text);
  let out = '';
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === '"' || c === "'") {
      const j = endOfString(text, i);
      out += text.slice(i, j);
      i = j;
      continue;
    }
    if (/^var\(/i.test(text.slice(i, i + 4)) && !/[-\w]/.test(text[i - 1] || '')) {
      const end = closing(text, i + 3);
      const inner = text.slice(i + 4, end - 1);
      const comma = indexOfTop(inner, ',');
      const name = (comma < 0 ? inner : inner.slice(0, comma)).trim();
      if (used) used.add(name);
      let value = Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : undefined;
      if (value === undefined) {
        if (comma < 0) throw new Error('undefined variable ' + name);
        value = inner.slice(comma + 1);
      }
      out += substitute(value.trim(), vars, depth + 1, used);
      i = end;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

function evaluate(expr, vars) {
  return parseColour(substitute(String(expr), vars || {}));
}

function isColour(text) {
  try {
    parseColour(text);
    return true;
  } catch (e) {
    return false;
  }
}

// ---- the golden form

function round3(a) {
  const v = Math.round(a * 1000) / 1000;
  return v === 0 ? 0 : v;
}

function format(c) {
  const ch = (v) => {
    const r = Math.round(clamp(v, 0, 255));
    return r === 0 ? 0 : r;
  };
  return 'rgba(' + ch(c[0]) + ',' + ch(c[1]) + ',' + ch(c[2]) + ',' + round3(clamp(c[3], 0, 1)) + ')';
}

function parseFormatted(text) {
  const m = /^rgba\((\d+),(\d+),(\d+),([\d.]+)\)$/.exec(text);
  if (!m) throw new Error('not a golden colour: ' + text);
  return [+m[1], +m[2], +m[3], +m[4]];
}

/*
 * Whether a colour still looks like its golden one: both as the browser keeps them (whole channels, alpha to 3
 * places), within 1 per channel and 0.01 alpha. Nothing shows at alpha 0, so there the channels don't matter.
 */
function sameColour(golden, colour) {
  const was = parseFormatted(golden);
  const now = parseFormatted(format(colour));
  if (was[3] === 0 && now[3] === 0) return true;
  for (let i = 0; i < 3; i++) if (Math.abs(was[i] - now[i]) > 1) return false;
  return Math.abs(was[3] - now[3]) <= 0.01 + 1e-9;
}

// ---- reading a stylesheet

function stripComments(css) {
  let out = '';
  let i = 0;
  while (i < css.length) {
    const c = css[i];
    if (c === '"' || c === "'") {
      const j = endOfString(css, i);
      out += css.slice(i, j);
      i = j;
    } else if (c === '/' && css[i + 1] === '*') {
      const j = css.indexOf('*/', i + 2);
      i = j < 0 ? css.length : j + 2;
      out += ' ';
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

function parseDecls(body, where) {
  if (indexOfTop(body, '{') >= 0) throw new Error('nested rules are not supported: ' + where);
  const decls = [];
  let rest = body;
  while (rest.trim() !== '') {
    const semi = indexOfTop(rest, ';');
    const text = (semi < 0 ? rest : rest.slice(0, semi)).trim();
    rest = semi < 0 ? '' : rest.slice(semi + 1);
    if (text === '') continue;
    const colon = text.indexOf(':');
    if (colon < 0) throw new Error('not a declaration: ' + text + ' in ' + where);
    let prop = text.slice(0, colon).trim();
    if (!prop.startsWith('--')) prop = prop.toLowerCase();
    let value = text.slice(colon + 1).trim();
    const important = /!\s*important\s*$/i.test(value);
    if (important) value = value.replace(/\s*!\s*important\s*$/i, '');
    decls.push({ prop: prop, value: value, important: important });
  }
  return decls;
}

function normalizeAt(prelude) {
  return prelude.replace(/\s+/g, ' ').replace(/\s*:\s*/g, ':').replace(/\(\s+/g, '(').replace(/\s+\)/g, ')').trim();
}

function normalizeStep(step) {
  return step.split(',').map((s) => s.trim().toLowerCase()).join(', ');
}

/*
 * Style rules ({ type: 'style', at, selectors, decls }) and @keyframes ({ type: 'keyframes', at, name,
 * steps: [{ step, decls }] }) in document order; at holds the normalized @media / @supports conditions around a rule.
 */
function parseCss(css) {
  const text = stripComments(String(css).replace(/\r/g, ''));
  const rules = [];
  parseBlock(text, 0, text.length, [], rules, null);
  return rules;
}

function parseBlock(text, start, end, at, out, keyframes) {
  let i = start;
  while (i < end) {
    let j = i;
    let depth = 0;
    while (j < end) {
      const c = text[j];
      if (c === '"' || c === "'") {
        j = endOfString(text, j);
        continue;
      }
      if (c === '(') depth++;
      else if (c === ')') depth--;
      else if (depth === 0 && (c === '{' || c === ';' || c === '}')) break;
      j++;
    }
    const prelude = text.slice(i, j).trim();
    if (j >= end) {
      if (prelude !== '') throw new Error('unfinished rule: ' + prelude.slice(0, 80));
      break;
    }
    if (text[j] === ';') {
      i = j + 1;
      continue;
    }
    if (text[j] === '}') throw new Error('stray } after: ' + prelude.slice(0, 80));
    const close = closing(text, j) - 1;
    const body = text.slice(j + 1, close);
    if (keyframes) {
      keyframes.steps.push({ step: normalizeStep(prelude), decls: parseDecls(body, '@keyframes ' + keyframes.name) });
    } else if (/^@(media|supports|container|layer|document)\b/i.test(prelude)) {
      parseBlock(text, j + 1, close, at.concat([normalizeAt(prelude)]), out, null);
    } else if (/^@(-webkit-)?keyframes\s/i.test(prelude)) {
      const rule = { type: 'keyframes', at: at, name: prelude.split(/\s+/)[1], steps: [] };
      parseBlock(text, j + 1, close, at, out, rule);
      out.push(rule);
    } else if (prelude[0] !== '@') {
      out.push({ type: 'style', at: at, selectors: splitTop(prelude, ','), decls: parseDecls(body, prelude) });
    }
    // other at-rules (@font-face, @page, @property) hold no theme colours
    i = close + 1;
  }
}

const GUARD = /:not\(\[data-deco-off~="[^"]*"\](?:, \[data-deco-off~="[^"]*"\])*\)/g;

/* One selector, spaced one way, with attribute values in double quotes (piece guards kept). */
function spaceSelector(selector) {
  const s = selector.replace(
    /\[\s*([-\w]+)\s*(?:([~|^$*]?=)\s*(?:"([^"]*)"|'([^']*)'|([-\w]+))\s*)?\]/g,
    (all, name, op, dq, sq, bare) => '[' + name + (op ? op + '"' + (dq !== undefined ? dq : sq !== undefined ? sq : bare) + '"' : '') + ']');
  let out = '';
  let bracket = 0;
  let i = 0;
  const skipSpace = () => {
    while (i < s.length && /\s/.test(s[i])) i++;
  };
  while (i < s.length) {
    const c = s[i];
    if (c === '"' || c === "'") {
      const j = endOfString(s, i);
      out += s.slice(i, j);
      i = j;
      continue;
    }
    if (c === '[') bracket++;
    else if (c === ']') bracket--;
    if (bracket === 0 && /\s/.test(c)) {
      out += ' ';
      skipSpace();
    } else if (bracket === 0 && (c === '>' || c === '+' || c === '~')) {
      out = out.trimEnd() + ' ' + c + ' ';
      i++;
      skipSpace();
    } else if (bracket === 0 && c === ',') {
      out = out.trimEnd() + ', ';
      i++;
      skipSpace();
    } else if (bracket === 0 && c === '(') {
      out += '(';
      i++;
      skipSpace();
    } else if (bracket === 0 && c === ')') {
      out = out.trimEnd() + ')';
      i++;
    } else {
      out += c;
      i++;
    }
  }
  return out.replace(/\s+/g, ' ').trim();
}

/* One selector, spaced one way, with attribute values in double quotes and the piece guards removed. */
function normalizeSelector(selector) {
  return spaceSelector(selector).replace(GUARD, '').replace(/\s+/g, ' ').trim();
}

function compareRank(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] || 0) - (b[i] || 0);
    if (d !== 0) return d;
  }
  return 0;
}

function skipName(s, i) {
  while (i < s.length && /[-\w\\]/.test(s[i])) i += s[i] === '\\' ? 2 : 1;
  return i;
}

/* [ids, classes + attributes + pseudo-classes, types + pseudo-elements], as Selectors 4 counts them. */
function specificity(selector) {
  const s = spaceSelector(selector);
  const sum = [0, 0, 0];
  const add = (v) => {
    sum[0] += v[0];
    sum[1] += v[1];
    sum[2] += v[2];
  };
  const most = (list) => splitTop(list, ',').map(specificity)
    .reduce((a, b) => (compareRank(a, b) >= 0 ? a : b), [0, 0, 0]);
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === '#') {
      sum[0]++;
      i = skipName(s, i + 1);
    } else if (c === '.') {
      sum[1]++;
      i = skipName(s, i + 1);
    } else if (c === '[') {
      sum[1]++;
      i = closing(s, i);
    } else if (c === ':') {
      const element = s[i + 1] === ':';
      const end = skipName(s, i + (element ? 2 : 1));
      const name = s.slice(i + (element ? 2 : 1), end).toLowerCase();
      let args = '';
      i = end;
      if (s[end] === '(') {
        i = closing(s, end);
        args = s.slice(end + 1, i - 1);
      }
      if (element || /^(before|after|first-line|first-letter)$/.test(name)) sum[2]++;
      else if (/^(not|is|has|matches|-webkit-any|-moz-any)$/.test(name)) add(most(args));
      else if (/^nth-(last-)?child$/.test(name) && /\sof\s/i.test(args)) {
        sum[1]++;
        add(most(args.split(/\sof\s/i)[1]));
      } else if (name !== 'where') sum[1]++;
    } else if (/[-\w]/.test(c)) {
      sum[2]++;
      i = skipName(s, i);
    } else {
      i++;
    }
  }
  return sum;
}

/* A normalized selector's parts: compounds and the combinators between them. */
function compounds(selector) {
  return splitTop(selector, ' ');
}

/* The built-in theme a normalized selector belongs to, or null (custom-theme rules belong to none). */
function themeOfSelector(selector) {
  if (selector.includes('data-custom-theme')) return null;
  const first = compounds(selector)[0] || '';
  if (!/^(html|:root)(?![-\w])/.test(first)) return null;
  const m = /\[data-theme="([a-z0-9-]+)"\]/.exec(first);
  if (m) return m[1];
  if (first === ':root' || /^html:not\(\[data-theme\]\)$/.test(first)) return 'nebula';
  return null;
}

/* The built-in theme a custom-theme rule (html[data-custom-theme][data-theme="id"] ...) builds on, or null. */
function customThemeOf(selector) {
  const first = compounds(selector)[0] || '';
  if (!/^html(?![-\w])/.test(first) || !first.includes('[data-custom-theme]')) return null;
  const m = /\[data-theme="([a-z0-9-]+)"\]/.exec(first);
  return m ? m[1] : null;
}

function themeBlock(themeId) {
  return 'html[data-theme="' + themeId + '"]';
}

/* Where a theme's own variables live: Nebula's are :root's (in the panel it has no attribute). */
function rootBlock(themeId) {
  return themeId === 'nebula' ? ':root' : themeBlock(themeId);
}

/* A normalized selector that matches <html> itself, whatever the theme. */
function isHtmlLevel(selector) {
  return /^(:root|html|html\[data-theme="[a-z0-9-]+"\]|html:not\(\[data-theme\]\))$/.test(selector);
}

/* A normalized selector whose variables a theme's <html> has. */
function isRootFor(selector, themeId) {
  return selector === ':root' || selector === 'html' || selector === themeBlock(themeId) ||
    (themeId === 'nebula' && selector === 'html:not([data-theme])');
}

const parsed = new Map();

/* A stylesheet's rules (parsed once per text: the tests read the same stylesheet for 17 themes). */
function rulesOf(css) {
  if (typeof css !== 'string') return css;
  if (!parsed.has(css)) {
    if (parsed.size >= 8) parsed.clear();
    parsed.set(css, parseCss(css));
  }
  return parsed.get(css);
}

const flattened = new WeakMap();

/*
 * Every declaration of every style rule of a stylesheet (its text, or its parsed rules), once per selector, in document
 * order: { rule, selector, spaced, theme, spec, decl, order } (selector: normalized, guards removed; spaced: normalized
 * with its guards; theme: themeOfSelector(selector); spec: with its guards).
 */
function declarations(css) {
  const rules = rulesOf(css);
  if (!flattened.has(rules)) {
    const out = [];
    let order = 0;
    rules.forEach((rule) => {
      if (rule.type !== 'style') return;
      rule.selectors.forEach((raw) => {
        const spaced = spaceSelector(raw);
        const selector = spaced.replace(GUARD, '').replace(/\s+/g, ' ').trim();
        const spec = specificity(spaced);
        const theme = themeOfSelector(selector);
        rule.decls.forEach((decl) => out.push({
          rule: rule, selector: selector, spaced: spaced, theme: theme, spec: spec, decl: decl, order: ++order
        }));
      });
    });
    flattened.set(rules, out);
  }
  return flattened.get(rules);
}

/* How the browser ranks declarations of one property on one element: !important, specificity, document order. */
function rankOf(d) {
  return [d.decl.important ? 1 : 0].concat(d.spec, [d.order]);
}

function atKey(at) {
  return at.join(' ');
}

function where(d) {
  return (d.rule.at.length ? atKey(d.rule.at) + ' ' : '') + d.selector;
}

/* The custom properties the theme's <html> has (see the top of this file). */
function themeVars(css, themeId, at) {
  const context = atKey(at || []);
  const best = {};
  declarations(rulesOf(css)).forEach((d) => {
    if (!d.decl.prop.startsWith('--') || !isRootFor(d.selector, themeId)) return;
    if (d.rule.at.length > 0 && atKey(d.rule.at) !== context) return;
    const rank = rankOf(d);
    const before = best[d.decl.prop];
    if (!before || compareRank(rank, before.rank) > 0) best[d.decl.prop] = { value: d.decl.value, rank: rank };
  });
  const vars = {};
  Object.keys(best).forEach((name) => {
    vars[name] = best[name].value;
  });
  return vars;
}

/* The colour expressions in one value, in order (see the top of this file); via: the variables read through. */
function colourTokens(value, vars, depth, notes) {
  const out = [];
  let i = 0;
  while (i < value.length) {
    const c = value[i];
    const rest = value.slice(i);
    let m;
    if (c === '"' || c === "'") {
      i = endOfString(value, i);
    } else if (c === '#') {
      m = /^#[0-9a-z]+/i.exec(rest);
      if (m && /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(m[0])) out.push({ expr: m[0] });
      i += m ? m[0].length : 1;
    } else if ((m = /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?[a-z%]*/i.exec(rest)) && /^[-+.\d]/.test(c)) {
      i += m[0].length;
    } else if ((m = /^-*[a-z_][-\w]*/i.exec(rest))) {
      const name = m[0];
      const after = i + name.length;
      if (value[after] === '(') {
        const end = closing(value, after);
        const fn = name.toLowerCase();
        const whole = value.slice(i, end);
        if (fn === 'url') {
          notes.urls.push(whole.length > 60 ? whole.slice(0, 57) + '...' : whole);
        } else if (COLOUR_FUNCTIONS.has(fn)) {
          out.push({ expr: whole });
        } else if (fn === 'var') {
          const used = new Set();
          let resolved = null;
          try {
            resolved = substitute(whole, vars, 0, used);
          } catch (e) {
            notes.unresolved.push(whole);
            out.push({ expr: whole });
          }
          if (resolved !== null) {
            if (isColour(resolved)) {
              out.push({ expr: whole });
            } else if (depth < MAX_DEPTH) {
              colourTokens(resolved, vars, depth + 1, notes)
                .forEach((t) => out.push({ expr: t.expr, via: Array.from(used).concat(t.via || []) }));
            }
          }
        } else {
          colourTokens(value.slice(after + 1, end - 1), vars, depth, notes).forEach((t) => out.push(t));
        }
        i = end;
      } else {
        if (Object.prototype.hasOwnProperty.call(NAMED, name.toLowerCase())) out.push({ expr: name });
        i = after;
      }
    } else {
      i++;
    }
  }
  return out;
}

/* Everything decorationColours finds, plus what it looked past: { entries, urls, unresolved }. */
function inspect(css, themeId) {
  const rules = rulesOf(css);
  const all = declarations(rules);
  const notes = { urls: [], unresolved: [] };
  // variables set below <html> by rules that apply while this theme is on
  const below = new Map();
  all.forEach((d) => {
    if (!d.decl.prop.startsWith('--') || isHtmlLevel(d.selector) || d.selector.includes('data-custom-theme')) return;
    const theme = themeOfSelector(d.selector);
    if (theme === themeId) {
      throw new Error(themeId + ': variable ' + d.decl.prop + ' is set by ' + where(d) +
        '; only the theme block may set variables (the check resolves them at html)');
    }
    if (theme === null && !below.has(d.decl.prop)) below.set(d.decl.prop, where(d));
  });
  const scopes = new Map();
  const scopeOf = (at) => {
    if (!scopes.has(atKey(at))) scopes.set(atKey(at), themeVars(rules, themeId, at));
    return scopes.get(atKey(at));
  };
  const refuseBelow = (key, expr, via, vars) => {
    const used = new Set(via || []);
    try {
      substitute(expr, vars, 0, used);
    } catch (e) {
      // an expression that can't be evaluated is reported where it is evaluated
    }
    used.forEach((name) => {
      if (below.has(name)) {
        throw new Error(themeId + ': variable ' + name + ' is set by ' + below.get(name) + ' and used by ' + key +
          '; the check resolves variables at html only');
      }
    });
  };
  const decls = new Map();
  const put = (key, decl, vars, rank) => {
    if (NOT_COLOURS.test(decl.prop)) return;
    const before = decls.get(key);
    if (before && compareRank(rank, before.rank) < 0) return;
    const tokens = colourTokens(decl.value, vars, 0, notes);
    tokens.forEach((t, n) => refuseBelow(key + '|' + n, t.expr, t.via, vars));
    decls.set(key, { rank: rank, tokens: tokens, vars: vars });
  };
  const base = scopeOf([]);
  const entries = [];
  ['--accent', '--accent2'].forEach((name) => {
    if (!['-r', '-g', '-b'].every((c) => base[name + c] !== undefined)) return;
    const key = rootBlock(themeId) + '|' + name + '-rgb|0';
    const expr = 'rgb(var(' + name + '-r), var(' + name + '-g), var(' + name + '-b))';
    refuseBelow(key, expr, [], base);
    entries.push({ key: key, expr: expr, vars: base, via: [] });
  });
  const animated = new Set();
  all.forEach((d) => {
    if (themeOfSelector(d.selector) !== themeId) return;
    put(where(d) + '|' + d.decl.prop, d.decl, scopeOf(d.rule.at), rankOf(d));
    if (d.decl.prop === 'animation' || d.decl.prop === 'animation-name') {
      (d.decl.value.match(/[-\w]+/g) || []).forEach((w) => animated.add(w));
    }
  });
  // the last @keyframes of a name is the one that counts
  const keyframes = new Map();
  rules.forEach((r) => {
    if (r.type === 'keyframes' && animated.has(r.name)) keyframes.set(r.name, r);
  });
  let step = 0;
  keyframes.forEach((r) => {
    const prefix = (r.at.length ? atKey(r.at) + ' ' : '') + '@keyframes ' + r.name + ' ';
    r.steps.forEach((s) => s.decls.forEach((d) => {
      if (d.prop.startsWith('--')) {
        throw new Error(themeId + ': variable ' + d.prop + ' is set by @keyframes ' + r.name +
          '; only the theme block may set variables (the check resolves them at html)');
      }
      put(prefix + s.step + '|' + d.prop, d, scopeOf(r.at), [0, 0, 0, 0, ++step]);
    }));
  });
  decls.forEach((d, key) => {
    d.tokens.forEach((t, n) => entries.push({ key: key + '|' + n, expr: t.expr, vars: d.vars, via: t.via || [] }));
  });
  return { entries: entries, urls: notes.urls, unresolved: notes.unresolved };
}

function decorationColours(css, themeId) {
  return inspect(css, themeId).entries;
}

/* The variables a value reads, through the ones they read in turn (an undefined one without a fallback included). */
function variablesRead(value, vars) {
  const used = new Set();
  try {
    substitute(value, vars, 0, used);
  } catch (e) {
    // what it read up to there is enough to know
  }
  return used;
}

/*
 * The colour expressions one declaration paints with, in order, as decorationColours reads them (none for a custom
 * property or a property whose words are names; nothing inside url()). vars: the variables to read var()s through.
 */
function declarationColours(prop, value, vars) {
  if (prop.startsWith('--') || NOT_COLOURS.test(prop)) return [];
  return colourTokens(value, vars || {}, 0, { urls: [], unresolved: [] }).map((t) => t.expr);
}

/* { key: "rgba(r,g,b,a)" } for every decoration colour of a theme; throws on one it can't evaluate. */
function snapshot(css, themeId) {
  const out = {};
  decorationColours(css, themeId).forEach((e) => {
    try {
      out[e.key] = format(evaluate(e.expr, e.vars));
    } catch (err) {
      throw new Error(themeId + ': ' + e.key + ': ' + e.expr + ': ' + err.message);
    }
  });
  return out;
}

/* The property of a key (selector|property|n). */
function propertyOf(key) {
  const parts = key.split('|');
  return parts[parts.length - 2];
}

/* The variables a custom theme sets, and the synthetic keys of the accent triples: the theme's colours themselves. */
const SLOT_VARS = new Set(Object.keys(ThemePalette.cssVars(ThemePalette.generate('#3b82f6', '#0b1020', 'dark')))
  .concat(['--accent-rgb', '--accent2-rgb']));

function isSlot(name) {
  return SLOT_VARS.has(name);
}

/* Plain black, plain white, or nothing to see: no theme's colour. */
function isPlain(colour) {
  const c = parseFormatted(format(colour));
  return c[3] === 0 || (c[0] === 0 && c[1] === 0 && c[2] === 0) || (c[0] === 255 && c[1] === 255 && c[2] === 255);
}

/*
 * What differs between a theme's golden colours and a stylesheet, one line each: a key that is gone (unless listed in
 * allow.gone), a colour that changed (unless listed in allow.changed, see the top of this file) or can't be evaluated,
 * a new key that isn't a custom property (unless listed in allow.added), and a listed key that doesn't fit its list.
 */
function compareToGolden(golden, css, themeId, allow) {
  const goneOk = new Set((allow && allow.gone) || []);
  const added = (allow && allow.added) || {};
  if (Array.isArray(added)) throw new Error('a key listed as new is listed with its colour: { key: "rgba(r,g,b,a)" }');
  Object.keys(added).forEach((key) => parseFormatted(added[key]));
  const newOk = new Set(Object.keys(added));
  const changedOk = new Set((allow && allow.changed) || []);
  const current = new Map(decorationColours(css, themeId).map((e) => [e.key, e]));
  const isGolden = (key) => Object.prototype.hasOwnProperty.call(golden, key);
  const evaluated = (e) => {
    try {
      return { colour: evaluate(e.expr, e.vars) };
    } catch (err) {
      return { error: 'can\'t evaluate: ' + e.key + ': ' + e.expr + ' (' + err.message + ')' };
    }
  };
  const problems = [];
  Object.keys(golden).forEach((key) => {
    const was = golden[key];
    const now = current.get(key);
    if (goneOk.has(key)) {
      if (now) problems.push('listed as gone but still there: ' + key);
      return;
    }
    if (!now) {
      problems.push('gone: ' + key + ' (was ' + was + ')');
      return;
    }
    const result = evaluated(now);
    if (result.error) {
      problems.push(result.error);
    } else if (changedOk.has(key)) {
      if (!key.startsWith(rootBlock(themeId) + '|--')) {
        problems.push('listed as changed but not a variable of the theme block: ' + key);
      } else if (isSlot(propertyOf(key))) {
        problems.push('listed as changed but a colour slot (a custom theme sets it): ' + key);
      } else if (sameColour(was, result.colour)) {
        problems.push('listed as changed but the same: ' + key);
      } else if (!isPlain(result.colour)) {
        problems.push('changed to a colour: ' + key + ': now ' + format(result.colour) +
          '; only plain black, white or nothing may be listed');
      }
    } else if (!sameColour(was, result.colour)) {
      problems.push('changed: ' + key + ': was ' + was + ', now ' + format(result.colour) + ' (' + now.expr + ')');
    }
  });
  current.forEach((e, key) => {
    if (isGolden(key)) return;
    const result = evaluated(e);
    if (newOk.has(key)) {
      if (result.error) problems.push(result.error);
      else if (!sameColour(added[key], result.colour)) {
        problems.push('changed: ' + key + ': listed as new with ' + added[key] + ', now ' + format(result.colour) +
          ' (' + e.expr + ')');
      }
    } else if (!propertyOf(key).startsWith('--')) {
      problems.push('new: ' + key + ' (' + (result.error || format(result.colour)) + ')');
    }
  });
  goneOk.forEach((key) => {
    if (!isGolden(key)) problems.push('listed as gone but not golden: ' + key);
  });
  changedOk.forEach((key) => {
    if (!isGolden(key)) problems.push('listed as changed but not golden: ' + key);
    else if (goneOk.has(key)) problems.push('listed as changed and as gone: ' + key);
    else if (!current.has(key)) problems.push('listed as changed but gone: ' + key);
  });
  newOk.forEach((key) => {
    if (isGolden(key)) problems.push('listed as new but golden: ' + key);
    else if (!current.has(key)) problems.push('listed as new but not there: ' + key);
  });
  return problems;
}

// ---- shapes: every other value of a theme's rules

const COLOUR = '<c>';

/* The accent triples: numbers of a colour, pinned by the colour golden (the synthetic --accent-rgb keys). */
const TRIPLE = /^--accent2?-[rgb]$/;

/* Does a var() hold one colour with these variables? */
function holdsOneColour(expr, vars) {
  try {
    evaluate(expr, vars);
    return true;
  } catch (e) {
    return false;
  }
}

/* A value's words spaced one way, its colours written <c> unless colours is false (see the top of this file). */
function shapeText(value, vars, colours) {
  let out = '';
  let space = false;
  const put = (text) => {
    if (space && out !== '') out += ' ';
    space = false;
    out += text;
  };
  let i = 0;
  while (i < value.length) {
    const c = value[i];
    const rest = value.slice(i);
    let m;
    if (/\s/.test(c)) {
      space = true;
      i++;
    } else if (c === ',') {
      out += ',';
      space = true;
      i++;
    } else if (c === '"' || c === "'") {
      const j = endOfString(value, i);
      put(value.slice(i, j));
      i = j;
    } else if (c === '#') {
      const word = (/^#[0-9a-z]+/i.exec(rest) || ['#'])[0];
      put(colours && /^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(word) ? COLOUR : word);
      i += word.length;
    } else if (/[-+.\d]/.test(c) && (m = /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?[a-z%]*/i.exec(rest))) {
      put(m[0]);
      i += m[0].length;
    } else if ((m = /^-*[a-z_][-\w]*/i.exec(rest))) {
      const name = m[0];
      const after = i + name.length;
      if (value[after] === '(') {
        const end = closing(value, after);
        const fn = name.toLowerCase();
        const whole = value.slice(i, end);
        if (fn === 'url') put(whole);
        else if (colours && (COLOUR_FUNCTIONS.has(fn) || (fn === 'var' && holdsOneColour(whole, vars)))) put(COLOUR);
        else put(name + '(' + shapeText(value.slice(after + 1, end - 1), vars, colours) + ')');
        i = end;
      } else {
        put(colours && Object.prototype.hasOwnProperty.call(NAMED, name.toLowerCase()) ? COLOUR : name);
        i = after;
      }
    } else {
      put(c);
      i++;
    }
  }
  return out;
}

/* A declaration's value with its colours taken out (see the top of this file). */
function shapeValue(prop, value, vars) {
  return shapeText(String(value), vars || {}, !NOT_COLOURS.test(prop));
}

/* { key: value } for every shape of a theme (see the top of this file). */
function shapes(css, themeId) {
  const rules = rulesOf(css);
  const scopes = new Map();
  const scopeOf = (at) => {
    if (!scopes.has(atKey(at))) scopes.set(atKey(at), themeVars(rules, themeId, at));
    return scopes.get(atKey(at));
  };
  const best = new Map();
  const put = (key, decl, vars, rank) => {
    if (TRIPLE.test(decl.prop)) return;
    const before = best.get(key);
    if (before && compareRank(rank, before.rank) < 0) return;
    best.set(key, { rank: rank, value: shapeValue(decl.prop, decl.value, vars) + (decl.important ? ' !important' : '') });
  };
  const animated = new Set();
  declarations(rules).forEach((d) => {
    if (d.theme !== themeId) return;
    put(where(d) + '|' + d.decl.prop, d.decl, scopeOf(d.rule.at), rankOf(d));
    if (d.decl.prop === 'animation' || d.decl.prop === 'animation-name') {
      (d.decl.value.match(/[-\w]+/g) || []).forEach((w) => animated.add(w));
    }
  });
  // the last @keyframes of a name is the one that counts
  const keyframes = new Map();
  rules.forEach((r) => {
    if (r.type === 'keyframes' && animated.has(r.name)) keyframes.set(r.name, r);
  });
  let step = 0;
  keyframes.forEach((r) => {
    const prefix = (r.at.length ? atKey(r.at) + ' ' : '') + '@keyframes ' + r.name + ' ';
    r.steps.forEach((s) => s.decls.forEach((d) => put(prefix + s.step + '|' + d.prop, d, scopeOf(r.at), [0, 0, 0, 0, ++step])));
  });
  const out = {};
  best.forEach((e, key) => {
    if (e.value !== COLOUR) out[key] = e.value;
  });
  return out;
}

/*
 * What differs between a theme's golden shapes and a stylesheet, one line each: a shape gone (unless listed in
 * allow.gone), changed (unless listed in allow.changed with what it is now) or new (unless listed in allow.added with
 * what it is), and a listed key that doesn't fit its list.
 */
function compareShapes(golden, css, themeId, allow) {
  const goneOk = new Set((allow && allow.gone) || []);
  const added = (allow && allow.added) || {};
  const changed = (allow && allow.changed) || {};
  [added, changed].forEach((list) => {
    if (Array.isArray(list)) throw new Error('a shape listed as new or changed is listed with its value: { key: "value" }');
  });
  const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const current = shapes(css, themeId);
  const problems = [];
  Object.keys(golden).forEach((key) => {
    const was = golden[key];
    const now = has(current, key) ? current[key] : undefined;
    if (goneOk.has(key)) {
      if (now !== undefined) problems.push('listed as gone but still there: ' + key);
    } else if (has(changed, key)) {
      if (now === was) problems.push('listed as changed but the same: ' + key);
      else if (now !== undefined && now !== changed[key]) {
        problems.push('changed: ' + key + ': listed as changed to ' + changed[key] + ', now ' + now);
      }
    } else if (now === undefined) {
      problems.push('gone: ' + key + ' (was ' + was + ')');
    } else if (now !== was) {
      problems.push('changed: ' + key + ': was ' + was + ', now ' + now);
    }
  });
  Object.keys(current).forEach((key) => {
    if (has(golden, key)) return;
    if (!has(added, key)) problems.push('new: ' + key + ' (' + current[key] + ')');
    else if (current[key] !== added[key]) {
      problems.push('changed: ' + key + ': listed as new with ' + added[key] + ', now ' + current[key]);
    }
  });
  goneOk.forEach((key) => {
    if (!has(golden, key)) problems.push('listed as gone but not golden: ' + key);
  });
  Object.keys(changed).forEach((key) => {
    if (!has(golden, key)) problems.push('listed as changed but not golden: ' + key);
    else if (goneOk.has(key)) problems.push('listed as changed and as gone: ' + key);
    else if (!has(current, key)) problems.push('listed as changed but gone: ' + key);
  });
  Object.keys(added).forEach((key) => {
    if (has(golden, key)) problems.push('listed as new but golden: ' + key);
    else if (!has(current, key)) problems.push('listed as new but not there: ' + key);
  });
  return problems;
}

// ---- pieces: guards, what applies with some pieces off, and cascade flips

/* The piece guards in a selector: the ids of :not([data-deco-off~="id"]), and how often data-deco-off appears. */
function guardsOf(selector) {
  return {
    ids: [...selector.matchAll(/:not\(\s*\[\s*data-deco-off\s*~=\s*["']?([^"'\]\s]+)["']?\s*\]\s*\)/g)].map((m) => m[1]),
    mentions: (selector.match(/data-deco-off/g) || []).length
  };
}

/* A selector's first compound and the rest (from the first space or combinator outside brackets and strings). */
function splitFirst(selector) {
  let depth = 0;
  for (let i = 0; i < selector.length; i++) {
    const c = selector[i];
    if (c === '"' || c === "'") {
      i = endOfString(selector, i) - 1;
      continue;
    }
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth--;
    else if (depth === 0 && /[\s>+~]/.test(c)) return { head: selector.slice(0, i), rest: selector.slice(i) };
  }
  return { head: selector, rest: '' };
}

/*
 * Does a string of attribute selectors ([a], [a="v"], [a~="v"]), classes (.c: one of the words of attrs.class) and
 * :not()s match an element with these attributes?
 */
function attributesMatch(text, attrs) {
  let i = 0;
  while (i < text.length) {
    if (text[i] === '.') {
      const end = skipName(text, i + 1);
      const classes = Object.prototype.hasOwnProperty.call(attrs, 'class') ? attrs.class.split(/\s+/) : [];
      if (end === i + 1 || !classes.includes(text.slice(i + 1, end))) return false;
      i = end;
    } else if (text[i] === '[') {
      const end = closing(text, i);
      const m = /^\s*([-\w]+)\s*(?:(~?=)\s*(?:"([^"]*)"|'([^']*)'|([-\w]+)))?\s*$/.exec(text.slice(i + 1, end - 1));
      if (!m) throw new Error('can\'t read ' + text);
      const has = Object.prototype.hasOwnProperty.call(attrs, m[1]);
      const want = m[3] !== undefined ? m[3] : m[4] !== undefined ? m[4] : m[5];
      const ok = !m[2] ? has : m[2] === '=' ? has && attrs[m[1]] === want : has && attrs[m[1]].split(/\s+/).includes(want);
      if (!ok) return false;
      i = end;
    } else if (text.startsWith(':not(', i)) {
      const end = closing(text, i + 4);
      if (splitTop(text.slice(i + 5, end - 1), ',').some((s) => attributesMatch(s, attrs))) return false;
      i = end;
    } else {
      throw new Error('can\'t read ' + text.slice(i));
    }
  }
  return true;
}

/* Does a selector's <html> part match <html> with these attributes? (null for a selector that doesn't start there) */
function htmlMatches(selector, attrs) {
  const head = splitFirst(selector).head.replace(/::?[-\w]+$/, '');
  const m = /^(html|:root)(?![-\w])/.exec(head);
  if (!m) return null;
  return attributesMatch(head.slice(m[0].length), attrs);
}

/* The element a normalized selector styles: its last compound's tag, class and id names, and pseudo-element. */
function subjectOf(selector) {
  const parts = compounds(selector);
  const last = parts[parts.length - 1] || '';
  const names = new Set();
  let tag = '';
  let pseudo = '';
  let i = 0;
  const t = /^(\*|[a-z][-\w]*|:root(?![-\w]))/i.exec(last);
  if (t) {
    tag = t[1] === ':root' ? 'html' : t[1].toLowerCase();
    i = t[1].length;
  }
  while (i < last.length) {
    const c = last[i];
    if (c === '.' || c === '#') {
      const end = skipName(last, i + 1);
      names.add(last.slice(i, end));
      i = end;
    } else if (c === '[') {
      i = closing(last, i);
    } else if (c === ':') {
      const element = last[i + 1] === ':';
      const end = skipName(last, i + (element ? 2 : 1));
      const name = last.slice(i + (element ? 2 : 1), end).toLowerCase();
      if (element || /^(before|after|first-line|first-letter)$/.test(name)) pseudo = '::' + name;
      i = last[end] === '(' ? closing(last, end) : end;
    } else {
      i++;
    }
  }
  return { tag: tag, names: names, pseudo: pseudo };
}

/*
 * Could two subjects be the same element? Same pseudo-element, no two different tags, and they share a class or id
 * name; one named by its tag alone is only that tag (a "button" is not taken for a ".card"; "*" is anything).
 */
function sameSubject(a, b) {
  if (a.pseudo !== b.pseudo) return false;
  const any = (x) => x.tag === '' || x.tag === '*';
  if (!any(a) && !any(b) && a.tag !== b.tag) return false;
  if (a.names.size === 0 || b.names.size === 0) {
    const bare = a.names.size === 0 ? a : b;
    return any(bare) || bare.tag === (bare === a ? b : a).tag;
  }
  return [...a.names].some((n) => b.names.has(n));
}

/* Properties that set each other (a shorthand and its longhands) share a family: border, background, margin, ... */
function propertyFamily(prop) {
  const p = prop.replace(/^-webkit-/, '');
  if (/-radius$/.test(p)) return 'border-radius';
  if (/^(top|right|bottom|left|inset)$/.test(p)) return 'inset';
  if (/^(row-|column-)?gap$/.test(p)) return 'gap';
  const m = /^(border|background|margin|padding|outline|font|animation|transition|mask|flex|grid|overflow|text-decoration|list-style|scroll-margin|scroll-padding|place)(-|$)/
    .exec(p);
  return m ? m[1] : p;
}

/* The html states a theme's rules are seen in: plain, as a custom theme's base, and with motion reduced. */
function themeStates(themeId) {
  return [{ 'data-theme': themeId }, { 'data-theme': themeId, 'data-custom-theme': 'x' },
    { 'data-theme': themeId, 'data-reduce-motion': '' }];
}

function sameValue(a, b) {
  return a.replace(/\s+/g, ' ').trim() === b.replace(/\s+/g, ' ').trim();
}

/*
 * A normalized selector's html part as its attributes and pseudo-classes, sorted, and what follows them (a
 * pseudo-element of html itself included): { html: [...], rest }, or null for a selector that doesn't start at html.
 */
function htmlAndRest(selector) {
  const parts = splitFirst(selector);
  const m = /^(html|:root)(?![-\w])/.exec(parts.head);
  if (!m) return null;
  let head = parts.head.slice(m[0].length);
  const element = /::[-\w]+$/.exec(head);
  if (element) head = head.slice(0, element.index);
  const simple = [];
  let i = 0;
  while (i < head.length) {
    let end = i + 1;
    if (head[i] === '[') {
      end = closing(head, i);
    } else if (head[i] === '.') {
      end = skipName(head, i + 1);
    } else if (head[i] === ':') {
      end = skipName(head, i + 1);
      if (head[end] === '(') end = closing(head, end);
    }
    simple.push(head.slice(i, end));
    i = end;
  }
  return { html: simple.sort(), rest: (element ? element[0] : '') + parts.rest };
}

/* Where a rule giving S back to a theme rule T sits: T's html part with S's own, then the rest of S. */
function restoredAt(t, s) {
  const own = htmlAndRest(t.selector);
  const other = htmlAndRest(s.selector);
  const html = new Set(own.html.concat(other ? other.html : []));
  return [...html].sort().join('') + '|' + (other ? other.rest : ' ' + s.selector);
}

/* See the top of this file. statesOf(themeId), when given, replaces themeStates (another page's states of <html>). */
function cascadeFlips(css, themeIds, statesOf) {
  const all = declarations(rulesOf(css));
  const subjects = new Map();
  const subject = (d) => {
    if (!subjects.has(d.selector)) subjects.set(d.selector, subjectOf(d.selector));
    return subjects.get(d.selector);
  };
  const out = [];
  themeIds.forEach((themeId) => {
    const states = (statesOf || themeStates)(themeId);
    const applies = (d) => {
      const m = htmlMatches(d.selector, states[0]);
      return m === null || states.some((s) => htmlMatches(d.selector, s));
    };
    const guarded = [];
    const unguarded = [];
    all.forEach((d) => {
      if (d.decl.prop.startsWith('--')) return;
      const theme = themeOfSelector(d.selector);
      const ids = guardsOf(d.spaced).ids;
      if (ids.length > 0) {
        if (theme === themeId || customThemeOf(d.selector) === themeId) guarded.push(d);
      } else if ((theme === null || theme === themeId) && applies(d)) {
        unguarded.push(d);
      }
    });
    const places = new Map();
    const placeOf = (d) => {
      const own = htmlAndRest(d.selector);
      if (!places.has(d)) places.set(d, own ? own.html.join('') + '|' + own.rest : null);
      return places.get(d);
    };
    guarded.forEach((t) => {
      const family = propertyFamily(t.decl.prop);
      const tSubject = subject(t);
      const before = [t.decl.important ? 1 : 0].concat(specificity(t.selector), [t.order]);
      const now = rankOf(t);
      const piece = guardsOf(t.spaced).ids;
      unguarded.forEach((s) => {
        if (propertyFamily(s.decl.prop) !== family || !sameSubject(tSubject, subject(s))) return;
        const rank = rankOf(s);
        if (compareRank(rank, before) <= 0 || compareRank(now, rank) <= 0) return;
        const wanted = restoredAt(t, s);
        const restored = guarded.some((r) => placeOf(r) === wanted && atKey(r.rule.at) === atKey(s.rule.at) &&
          r.decl.prop === s.decl.prop && sameValue(r.decl.value, s.decl.value) &&
          guardsOf(r.spaced).ids.join(' ') === piece.join(' ') && compareRank(rankOf(r), now) > 0);
        out.push({
          theme: themeId,
          piece: piece.join(' '),
          key: where(t) + '|' + t.decl.prop + ' <- ' + where(s) + '|' + s.decl.prop,
          restored: restored
        });
      });
    });
  });
  return out;
}

// ---- today's stylesheets, as the panel and the mini-player load them, and the golden files

function read(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8').replace(/\r/g, '');
}

/* The panel's <style> (index.html), its files read with readFile. */
function panelCssOf(readFile) {
  const html = readFile('src/main/resources/web/index.html');
  return Array.from(html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g), (m) => m[1]).join('\n');
}

/* The mini-player's mini.css, then its themes.css (the order its page links them), read with readFile. */
function miniCssOf(readFile) {
  return readFile('desktop/ui/mini-player/mini.css') + '\n' + readFile('desktop/ui/mini-player/themes.css');
}

function panelCss() {
  return panelCssOf(read);
}

function miniCss() {
  return miniCssOf(read);
}

/* The last commit before the decorations were rewritten: the golden files are its stylesheets. */
const ORIGINAL = 'dd330df';

/* The stylesheet of 'panel' or 'mini' as it was at ORIGINAL (read with git; throws when git hasn't got the commit). */
function originalCss(kind) {
  const show = (rel) => execFileSync('git', ['show', ORIGINAL + ':' + rel],
    { cwd: ROOT, maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] }).toString('utf8').replace(/\r/g, '');
  return kind === 'mini' ? miniCssOf(show) : panelCssOf(show);
}

const GOLDEN_DIR = 'src/test/resources/theme-golden/';

/* The golden colours of 'panel' or 'mini': { theme: { key: "rgba(r,g,b,a)" } }. */
function goldenFile(kind) {
  return JSON.parse(read(GOLDEN_DIR + kind + '.json'));
}

/* The reviewed allowlists (see the top of this file). */
function allowlist() {
  return JSON.parse(read(GOLDEN_DIR + 'allowlist.json'));
}

/* One theme's reviewed allowlists of 'panel' or 'mini', as compareToGolden takes them. */
function allowedFor(kind, themeId) {
  const allow = allowlist();
  const added = {};
  Object.entries(allow.newOnPurpose[kind][themeId] || {}).forEach(([key, entry]) => {
    added[key] = entry.is;
  });
  return {
    gone: Object.keys(allow.goneOnPurpose[kind][themeId] || {}),
    added: added,
    changed: Object.keys(allow.changedOnPurpose[kind][themeId] || {})
  };
}

/* Every difference between a golden file and a stylesheet (today's by default), each line led by its theme. */
function checkSheet(kind, css) {
  const golden = goldenFile(kind);
  const allow = allowlist();
  const gone = allow.goneOnPurpose[kind];
  const added = allow.newOnPurpose[kind];
  const changed = allow.changedOnPurpose[kind];
  const sheet = css === undefined ? (kind === 'mini' ? miniCss() : panelCss()) : css;
  const problems = [];
  Object.keys(golden).forEach((theme) => {
    compareToGolden(golden[theme], sheet, theme, allowedFor(kind, theme)).forEach((p) => problems.push(theme + ': ' + p));
  });
  Object.keys(gone).concat(Object.keys(added), Object.keys(changed)).forEach((theme) => {
    if (!golden[theme]) problems.push(theme + ': in the allowlist but has no golden colours');
  });
  return problems;
}

/* The golden shapes of 'panel' or 'mini': { theme: { key: value } }. */
function shapeGoldenFile(kind) {
  return JSON.parse(read(GOLDEN_DIR + kind + '-shapes.json'));
}

const SHAPE_LISTS = ['shapeGoneOnPurpose', 'shapeNewOnPurpose', 'shapeChangedOnPurpose'];

/* One theme's reviewed shape allowlists of 'panel' or 'mini', as compareShapes takes them. */
function allowedShapesFor(kind, themeId) {
  const allow = allowlist();
  const pinned = (name) => {
    const out = {};
    Object.entries(allow[name][kind][themeId] || {}).forEach(([key, entry]) => {
      out[key] = entry.is;
    });
    return out;
  };
  return {
    gone: Object.keys(allow.shapeGoneOnPurpose[kind][themeId] || {}),
    added: pinned('shapeNewOnPurpose'),
    changed: pinned('shapeChangedOnPurpose')
  };
}

/* Every difference between a shape golden file and a stylesheet (today's by default), each line led by its theme. */
function checkShapes(kind, css) {
  const golden = shapeGoldenFile(kind);
  const allow = allowlist();
  const sheet = css === undefined ? (kind === 'mini' ? miniCss() : panelCss()) : css;
  const problems = [];
  Object.keys(golden).forEach((theme) => {
    compareShapes(golden[theme], sheet, theme, allowedShapesFor(kind, theme)).forEach((p) => problems.push(theme + ': ' + p));
  });
  SHAPE_LISTS.forEach((name) => Object.keys(allow[name][kind]).forEach((theme) => {
    if (!golden[theme]) problems.push(theme + ': in ' + name + ' but has no golden shapes');
  }));
  return problems;
}

module.exports = {
  evaluate: evaluate,
  format: format,
  sameColour: sameColour,
  parseCss: parseCss,
  normalizeSelector: normalizeSelector,
  specificity: specificity,
  themeOfSelector: themeOfSelector,
  customThemeOf: customThemeOf,
  declarations: declarations,
  variablesRead: variablesRead,
  themeVars: themeVars,
  decorationColours: decorationColours,
  declarationColours: declarationColours,
  inspect: inspect,
  snapshot: snapshot,
  compareToGolden: compareToGolden,
  isSlot: isSlot,
  guardsOf: guardsOf,
  splitFirst: splitFirst,
  attributesMatch: attributesMatch,
  htmlMatches: htmlMatches,
  subjectOf: subjectOf,
  propertyFamily: propertyFamily,
  cascadeFlips: cascadeFlips,
  panelCss: panelCss,
  miniCss: miniCss,
  goldenFile: goldenFile,
  allowlist: allowlist,
  allowedFor: allowedFor,
  checkSheet: checkSheet,
  shapeValue: shapeValue,
  shapes: shapes,
  compareShapes: compareShapes,
  shapeGoldenFile: shapeGoldenFile,
  allowedShapesFor: allowedShapesFor,
  checkShapes: checkShapes,
  ORIGINAL: ORIGINAL,
  originalCss: originalCss
};
