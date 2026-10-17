'use strict';
/*
 * Checks of the themes' decoration pieces, shared by the panel's tests (theme-golden.test.js) and the mini-player's
 * (theme-golden-mini.test.js): which rules are a theme's, which sit under exactly one piece guard, what stays with every
 * piece off (the theme's shape and pinned card style), and which colours of a theme a custom palette can't reach. A
 * stylesheet is read with css-colors.js. Each check takes the page's own lists (card style, layout effects, heads, states).
 */
const fs = require('node:fs');
const path = require('node:path');
const Colors = require('./css-colors.js');
const ThemePalette = require('../../main/resources/web/theme-palette.js');

const ROOT = path.join(__dirname, '../../..');

const DECORATED = ['ember', 'steel', 'grove', 'sumi', 'rasta', 'dawn-dark', 'cipher', 'signal', 'victory', 'neon',
  'revival'];

/* theme-pieces.json: every built-in theme's decoExtra and pieces, the one list. */
const PIECES = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/web/theme-pieces.json'), 'utf8'));

function pieceIds(themeId) {
  return PIECES[themeId].pieces.map((p) => p.id);
}

/* A theme id in a test name: dawn-dark -> dawnDark (camel) / DawnDark (capitalized). */
function camel(id) {
  return id.replace(/-(\w)/g, (m, c) => c.toUpperCase());
}

function capitalized(id) {
  return camel(id)[0].toUpperCase() + camel(id).slice(1);
}

/* A theme's own rules: those starting at html[data-theme="x"], and its custom-theme rules html[data-custom-theme][...]. */
function ownedBy(d, themeId) {
  return d.theme === themeId || Colors.customThemeOf(d.selector) === themeId;
}

/*
 * The blocks a theme's variables may be set in: the theme block, and the custom-theme block over it (and Nebula's
 * :root, the panel's Nebula and the mini-player's base look under every skin).
 */
function bareBlocks(themeId) {
  return ['html[data-theme="' + themeId + '"]', 'html[data-custom-theme][data-theme="' + themeId + '"]',
    'html[data-theme="' + themeId + '"][data-custom-theme]'].concat(themeId === 'nebula' ? [':root'] : []);
}

/*
 * The <html> a rule of a theme is seen on, with some pieces off (custom-theme rules: with a custom theme on; the
 * mini-player's compact-bar rules, html.compact: in the compact bar).
 */
function htmlOf(d, themeId, off) {
  const attrs = { 'data-theme': themeId };
  if (off !== undefined) attrs['data-deco-off'] = off;
  if (Colors.customThemeOf(d.selector) === themeId) attrs['data-custom-theme'] = 'x';
  if (/^html\.compact(?![-\w])/.test(Colors.splitFirst(d.selector).head)) attrs.class = 'compact';
  return attrs;
}

/* Paints something: a colour, generated content, or an animation. */
function decorates(d, vars) {
  if (Colors.declarationColours(d.decl.prop, d.decl.value, vars).length > 0) return true;
  if (d.decl.prop === 'content') return true;
  return /^animation(-name)?$/.test(d.decl.prop) && !/^none$/i.test(d.decl.value.trim());
}

/*
 * Pieces the panel doesn't have: they are the mini-player's only (the panel's version of the theme never had them):
 * Cipher's "> " prompt and blinking caret, Neon's glowing title and progress bar, Ember's fire glow, Sumi's seal and
 * brush stroke, Steel's top sheen and brushed lines, Dawn Dark's gradient edge. (Grove's glows are its cards' soft
 * green halo in the panel.)
 */
const MINI_ONLY = { cipher: ['prompt'], neon: ['title'], ember: ['fire'], sumi: ['seal'], steel: ['sheen'],
  'dawn-dark': ['edge'] };

/* Is this declaration pinned as card style (an unguarded one, outside @media)? cardStyle: the theme's list, below. */
function isCardStyle(d, cardStyle) {
  const list = cardStyle || [];
  return d.rule.at.length === 0 && Colors.guardsOf(d.spaced).mentions === 0 &&
    list.some(([selector, props]) => d.selector === selector && props.includes(d.decl.prop));
}

/*
 * Where a theme's decorations (its custom-theme rules' too) aren't each under exactly one of its pieces, right after its
 * html part (its pinned card style aside); and variables set anywhere but its bare blocks. heads: the html parts a guard
 * may follow (the bare blocks; the mini-player's compact bar adds html.compact[data-theme="x"]).
 */
function pieceGuardProblems(css, themeId, ids, heads, cardStyle) {
  const vars = Colors.themeVars(css, themeId);
  const blocks = bareBlocks(themeId);
  const after = heads || blocks;
  const problems = [];
  const used = new Set();
  for (const d of Colors.declarations(css)) {
    if (!ownedBy(d, themeId)) continue;
    const at = d.rule.at.length ? d.rule.at.join(' ') + ' ' : '';
    const guards = Colors.guardsOf(d.spaced);
    guards.ids.forEach((id) => {
      used.add(id);
      if (!ids.includes(id)) problems.push(d.spaced + ': ' + id + ' is not a piece of ' + themeId);
    });
    if (d.decl.prop.startsWith('--')) {
      if (!blocks.includes(d.selector)) problems.push(at + d.spaced + ' { ' + d.decl.prop + ' }: a variable below html');
      continue;
    }
    if (guards.mentions === 0 && (!decorates(d, vars) || isCardStyle(d, cardStyle))) continue;
    const head = Colors.splitFirst(d.selector).head.replace(/::?[-\w]+$/, '');
    if (guards.mentions !== 1 || guards.ids.length !== 1) {
      problems.push(at + d.spaced + ' { ' + d.decl.prop + ' }: ' + guards.ids.length + ' guards');
    } else if (!after.includes(head) || !d.spaced.startsWith(head + ':not([data-deco-off~="' + guards.ids[0] + '"])')) {
      problems.push(at + d.spaced + ': the guard belongs right after ' + head);
    }
  }
  return { problems: [...new Set(problems)], used: used };
}

/*
 * What an unguarded rule of a decorated theme may set: the theme's shape, which is not a piece (spec §3) and stays with
 * every piece off. Type (font, font-*, letter-spacing, word-spacing, line-height, text-transform, text-align,
 * text-overflow, white-space, overflow), corners (border-*radius), sizes and spacing (width, height, min-/max-,
 * margin*, padding*, gap, row-gap, column-gap), flex layout (flex-wrap, align-content, justify-content),
 * scrollbar-width, and a border taken away (border, border-top/right/bottom/left: none or 0). Nothing on a
 * pseudo-element: in a decorated theme that is a decoration's own layer, all of it the piece's. Besides the shape: the
 * variables of its bare blocks (the theme block, the custom-theme block), animation: none where motion is reduced, and a
 * layer taken away (display: none, on a pseudo-element too: it can't paint; Rasta's and Grove's plain pages hide the
 * panel's drifting glow, and Steel, Sumi and Dawn Dark hide it unless their wash or mist piece shows it).
 */
const SHAPE = new RegExp('^(font|font-[a-z-]+|letter-spacing|word-spacing|line-height|text-transform|text-align|' +
  'text-overflow|white-space|overflow(-[xy])?|border(-(top|bottom)-(left|right))?-radius|(min-|max-)?(width|height)|' +
  'margin(-[a-z]+)?|padding(-[a-z]+)?|(row-|column-)?gap|flex-wrap|align-content|justify-content|scrollbar-width)$');
const BORDER_TAKEN_AWAY = /^border(-(top|right|bottom|left))?$/;

function isShape(d, themeId) {
  const prop = d.decl.prop;
  const value = d.decl.value.trim();
  if (prop.startsWith('--')) return bareBlocks(themeId).includes(d.selector);
  if (/^animation(-name)?$/.test(prop) && /^none$/i.test(value)) {
    return /\[data-reduce-motion\]/.test(Colors.splitFirst(d.selector).head) ||
      d.rule.at.join(' ') === '@media (prefers-reduced-motion:reduce)';
  }
  if (prop === 'display' && /^none$/i.test(value)) return true;
  if (Colors.subjectOf(d.selector).pseudo) return false;
  return SHAPE.test(prop) || (BORDER_TAKEN_AWAY.test(prop) && /^(none|0)$/i.test(value));
}

/*
 * What keeps a theme's pieces from switching off cleanly (its custom-theme rules' too): a declaration under a guard
 * that isn't one piece's, doesn't apply with every piece on, applies with every piece off, or doesn't follow its own
 * piece alone; an unguarded one that isn't the theme's shape or its pinned card style; a pinned layout effect that isn't
 * under its piece. Every declaration of a guarded rule counts (its layout effects too).
 */
function piecesOffProblems(decls, themeId, ids, effects, cardStyle) {
  const problems = [];
  let guarded = 0;
  for (const d of decls) {
    if (!ownedBy(d, themeId)) continue;
    const at = d.rule.at.length ? d.rule.at.join(' ') + ' ' : '';
    const where = at + d.spaced + ' { ' + d.decl.prop + ': ' + d.decl.value + ' }';
    const guards = Colors.guardsOf(d.spaced);
    if (guards.mentions > 0) {
      guarded++;
      if (guards.mentions !== 1 || guards.ids.length !== 1) problems.push(where + ': under ' + guards.mentions + ' guards');
      if (Colors.htmlMatches(d.spaced, htmlOf(d, themeId)) !== true) problems.push(where + ': off with every piece on');
      if (Colors.htmlMatches(d.spaced, htmlOf(d, themeId, ids.join(' '))) !== false) {
        problems.push(where + ': still on with every piece off');
      }
      for (const id of ids) {
        const alone = Colors.htmlMatches(d.spaced, htmlOf(d, themeId, id));
        if (alone === guards.ids.includes(id)) {
          problems.push(where + ': ' + id + ' alone ' + (alone ? 'leaves it on' : 'switches it off'));
        }
      }
    } else if (!isShape(d, themeId) && !isCardStyle(d, cardStyle)) {
      problems.push(where + ': not under a piece, and not the theme\'s shape');
    }
  }
  for (const [piece, selector, props] of effects || []) {
    for (const prop of props) {
      const found = decls.filter((d) => ownedBy(d, themeId) && d.selector === selector && d.decl.prop === prop);
      if (found.length === 0) problems.push(selector + ' { ' + prop + ' }: pinned to ' + piece + ' but not there');
      found.forEach((d) => {
        const ids2 = Colors.guardsOf(d.spaced).ids;
        if (ids2.length !== 1 || ids2[0] !== piece) problems.push(d.spaced + ' { ' + prop + ' }: belongs to ' + piece);
      });
    }
  }
  return { problems: problems, guarded: guarded };
}

/* The colours written out in an expression (hex, rgb()/hsl() of numbers, named), inside a color-mix() too. */
function literalColours(expr) {
  const found = [];
  const text = expr.replace(/--[-\w]+/g, ' '); // a variable's name is no colour (--rasta-gold)
  for (const m of text.matchAll(/#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?)\([^()]*\)|\b[a-z]+\b/gi)) {
    try {
      found.push(Colors.evaluate(m[0]));
    } catch (e) {
      // not a colour (in, srgb, var, ...)
    }
  }
  return found;
}

/*
 * What keeps a theme's pinned card style from being its shape: an entry that isn't there, is under a piece or in an
 * @media, is on a pseudo-element, or paints with anything but the palette's slots and plain black, white and
 * see-through (a colour of the theme's own, or a variable of its own, would stay whatever a custom theme's colours).
 */
function cardStyleProblems(css, themeId, cardStyle) {
  const vars = Colors.themeVars(css, themeId);
  const decls = Colors.declarations(css);
  const problems = [];
  for (const [selector, props] of cardStyle) {
    if (Colors.subjectOf(selector).pseudo) problems.push(selector + ': a pseudo-element is a decoration\'s layer');
    for (const prop of props) {
      const found = decls.filter((d) => d.selector === selector && d.decl.prop === prop);
      if (!found.some((d) => isCardStyle(d, cardStyle))) {
        problems.push(selector + ' { ' + prop + ' }: pinned as card style but not there, unguarded and outside @media');
      }
      for (const d of found) {
        if (!isCardStyle(d, cardStyle)) continue;
        for (const expr of Colors.declarationColours(d.decl.prop, d.decl.value, vars)) {
          if (neutral(Colors.evaluate(expr, vars))) continue;
          const own = [...Colors.variablesRead(expr, vars)].filter((name) => !Colors.isSlot(name));
          const written = literalColours(expr).filter((c) => !neutral(c));
          if (Colors.variablesRead(expr, vars).size === 0 || own.length > 0 || written.length > 0) {
            problems.push(selector + ' { ' + prop + ' }: ' + expr + ' is not the slots\' (' +
              (own.length ? own.join(', ') : 'a colour of its own') + ')');
          }
        }
      }
    }
  }
  return problems;
}

/* Plain black, plain white, or nothing: a colour no theme gives a piece. */
function neutral(c) {
  const rgb = Colors.format(c).match(/\d+/g).slice(0, 3).map(Number);
  return c[3] === 0 || rgb.every((v) => v === 0) || rgb.every((v) => v === 255);
}

/*
 * The theme block's own colour variables (not a slot a custom theme sets, and not neutral) that a rule reads without a
 * piece guard (a shared rule, or the theme's own): a colour that stays whatever the pieces, and in a custom theme in
 * the built-in theme's colours. options (another page's; the panel's by default):
 * - slots: the variables a custom theme always sets inline (one it sets only sometimes is the theme's own then);
 * - states: <html>'s states;
 * - plainToo: plain black and white count too (the mini-player, where a custom version wears the skin itself: a
 *   built-in skin's white glow behind its dark text would sit behind a custom version's light text).
 */
function ownColourReads(css, themeId, options) {
  const opts = options || {};
  const slots = new Set(opts.slots || Object.keys(ThemePalette.cssVars(ThemePalette.generate('#3b82f6', '#0b1020', 'dark'))));
  const own = new Set();
  const decls = Colors.declarations(css);
  for (const d of decls) {
    if (d.selector !== 'html[data-theme="' + themeId + '"]' || !d.decl.prop.startsWith('--') || slots.has(d.decl.prop)) {
      continue;
    }
    const vars = Colors.themeVars(css, themeId, d.rule.at);
    const colours = Colors.declarationColours('background', d.decl.value, vars);
    if (colours.some((e) => opts.plainToo || !neutral(Colors.evaluate(e, vars)))) own.add(d.decl.prop);
  }
  const vars = Colors.themeVars(css, themeId);
  const states = opts.states || [{ 'data-theme': themeId }, { 'data-theme': themeId, 'data-custom-theme': 'x' },
    { 'data-theme': themeId, 'data-reduce-motion': '' }];
  const problems = [];
  for (const d of decls) {
    if (d.decl.prop.startsWith('--') || Colors.guardsOf(d.spaced).mentions > 0) continue;
    if (!ownedBy(d, themeId)) {
      if (d.theme !== null || Colors.customThemeOf(d.selector) !== null) continue;
      const m = Colors.htmlMatches(d.selector, states[0]);
      if (m !== null && !states.some((s) => Colors.htmlMatches(d.selector, s))) continue;
    }
    const read = [...Colors.variablesRead(d.decl.value, vars)].filter((name) => own.has(name));
    if (read.length) {
      problems.push((d.rule.at.length ? d.rule.at.join(' ') + ' ' : '') + d.selector + ' { ' + d.decl.prop + ' } reads ' +
        read.join(', '));
    }
  }
  return problems;
}

module.exports = {
  DECORATED: DECORATED,
  PIECES: PIECES,
  MINI_ONLY: MINI_ONLY,
  pieceIds: pieceIds,
  camel: camel,
  capitalized: capitalized,
  ownedBy: ownedBy,
  bareBlocks: bareBlocks,
  htmlOf: htmlOf,
  decorates: decorates,
  isCardStyle: isCardStyle,
  pieceGuardProblems: pieceGuardProblems,
  isShape: isShape,
  piecesOffProblems: piecesOffProblems,
  literalColours: literalColours,
  cardStyleProblems: cardStyleProblems,
  neutral: neutral,
  ownColourReads: ownColourReads
};
