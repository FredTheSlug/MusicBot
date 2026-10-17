'use strict';
/*
 * The mini-player's 17 skins (desktop/ui/mini-player: mini.css, then themes.css) under the panel's rules
 * (theme-golden.test.js): their golden colours, every decoration under one piece (every skin has pieces there),
 * nothing left with every piece off, and every colour reached by a custom version, which wears its starting theme's
 * skin with its own colours inline on <html> (view.js customVars), data-custom-theme and data-deco-off.
 */
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const Colors = require('./css-colors.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const ThemeCreator = require('../../main/resources/web/theme-creator.js');
const ThemePalette = require('../../main/resources/web/theme-palette.js');
const MiniView = require('../../../desktop/ui/mini-player/view.js');
const {
  DECORATED, PIECES, MINI_ONLY, pieceIds, camel, bareBlocks, pieceGuardProblems, piecesOffProblems, cardStyleProblems,
  neutral, ownColourReads
} = require('./theme-checks.js');

/* The mini-player's golden colours (src/test/resources/theme-golden/mini.json), from before the rewrite. */
const MINI_GOLDEN = Colors.goldenFile('mini');

/*
 * The pieces each skin draws in the mini-player (its others are the panel's only: Ember's flames and flame edge, Grove's
 * photo and hairlines, Neon's photo and rails, ...). The mini-player's decorations sit on one layer behind the card (its
 * ::before and ::after): a skin's corner glows there belong to the piece the panel gives its page's glow (Cipher's grid,
 * Signal's scanlines, Victory's glows, Grove's corner glows), else to the skin's signature piece (Rasta's flag stripe).
 * Revival's rays and sunrise glow are two layers (::before and ::after), so each switches off alone.
 */
const MINI_PIECES = {
  nebula: ['glow'], aurora: ['glow'], ember: ['fire'], crimson: ['glow'], dawn: ['glow'], 'dawn-dark': ['wash', 'edge'],
  steel: ['wash', 'sheen'], verdant: ['glow'], grove: ['glows'], grapevine: ['glow'], sumi: ['mist', 'seal'],
  rasta: ['stripe'], cipher: ['grid', 'prompt'], signal: ['scanlines'], victory: ['glows', 'gilded'],
  neon: ['glows', 'title'], revival: ['rays', 'sunrise', 'record']
};

/* A skin's card style with a colour (its shape, never a piece): Dawn's flat buttons, Rasta's play icon kept legible. */
const MINI_CARD_STYLE = {
  dawn: [['html[data-theme="dawn"] .ctrl', ['box-shadow']]],
  rasta: [['html[data-theme="rasta"] .ctrl.primary', ['text-shadow']]]
};

/*
 * Layout effects of a mini-player piece: Sumi's brush stroke takes 3px under the title; Revival's record is a bigger,
 * round artwork with a spindle (in the compact bar too). With the piece off they are the mini-player's own again.
 */
const MINI_LAYOUT_EFFECTS = {
  sumi: [['seal', 'html[data-theme="sumi"] .title', ['padding-bottom']]],
  revival: [
    ['record', 'html[data-theme="revival"] .disc', ['flex-basis', 'width', 'height', 'border-radius', 'overflow']],
    ['record', 'html[data-theme="revival"] .art', ['inset', 'width', 'height', 'border-radius']],
    ['record', 'html[data-theme="revival"] .spindle', ['display', 'width', 'height', 'margin']],
    ['record', 'html.compact[data-theme="revival"] .disc', ['flex-basis', 'width', 'height']],
    ['record', 'html.compact[data-theme="revival"] .art', ['inset', 'width', 'height']],
    ['record', 'html.compact[data-theme="revival"] .spindle', ['width', 'height', 'margin']]
  ]
};

/*
 * Tones of a built-in skin no mix of its slots gives (out of reach of its slots, black and white: no colour-mix of them
 * comes within the golden tolerance): the skin block keeps its own value and a custom version derives it from its
 * colours in html[data-custom-theme][data-theme="x"], the panel's way.
 */
const MINI_OUT_OF_REACH = {
  aurora: ['--aurora-violet'],
  crimson: ['--crimson-red', '--crimson-rose'],
  rasta: ['--rasta-gold'],
  revival: ['--rv-ember', '--rv-glint']
};

/* At least this many colours a custom palette is checked against, and declarations under a piece, per skin. */
const MINI_FLOORS = {
  nebula: { checked: 1, guarded: 2 }, aurora: { checked: 5, guarded: 7 }, ember: { checked: 2, guarded: 4 },
  crimson: { checked: 2, guarded: 3 }, dawn: { checked: 2, guarded: 3 }, 'dawn-dark': { checked: 3, guarded: 5 },
  steel: { checked: 1, guarded: 3 }, verdant: { checked: 2, guarded: 3 }, grove: { checked: 2, guarded: 3 },
  grapevine: { checked: 2, guarded: 3 }, sumi: { checked: 4, guarded: 11 }, rasta: { checked: 4, guarded: 5 },
  cipher: { checked: 4, guarded: 9 }, signal: { checked: 3, guarded: 4 }, victory: { checked: 3, guarded: 4 },
  neon: { checked: 6, guarded: 6 }, revival: { checked: 11, guarded: 45 }
};

/* What a guard may follow in the mini-player: the bare blocks, and the compact bar's html.compact[data-theme="x"]. */
function miniHeads(themeId) {
  return bareBlocks(themeId).concat(['html.compact[data-theme="' + themeId + '"]']);
}

/* The mini-player's states of <html>: a skin, a custom version over it, each in the compact bar too. */
function miniStates(themeId) {
  return [{ 'data-theme': themeId }, { 'data-theme': themeId, 'data-custom-theme': 'x' },
    { 'data-theme': themeId, class: 'compact' }, { 'data-theme': themeId, 'data-custom-theme': 'x', class: 'compact' }];
}

/* A custom mini-player version made from a generated palette (15 colours, its own extra decoration colour). */
function miniVersion(accent, background, mode, decoExtra) {
  const colors = ThemePalette.mini(ThemePalette.generate(accent, background, mode));
  if (decoExtra) colors.decoExtra = decoExtra;
  return { id: 'k3j9x0q2m7ab', base: 'nebula', font: 'Inter', colors: colors, decorationsOff: [] };
}

/*
 * What every custom version sets inline on <html>, light or dark, with or without its extra decoration color: the
 * mini-player's colour slots. A variable only some versions set is the skin's own for the others (--deco-extra is, on
 * purpose: a version without one wears the skin's, the bot's rule).
 */
const MINI_ALWAYS_SET = [['#3b82f6', '#0b1020', 'dark', '#f43f5e'], ['#3b82f6', '#f8fafc', 'light', '#f43f5e'],
  ['#3b82f6', '#0b1020', 'dark'], ['#3b82f6', '#f8fafc', 'light']]
  .map((args) => {
    const version = miniVersion(...args);
    if (args.length === 3) delete version.colors.decoExtra;
    return Object.keys(MiniView.customVars(version));
  })
  .reduce((all, names) => all.filter((name) => names.includes(name)));

/* The rule that wins a property of one element of the card for <html> with these attributes (outside @media). */
function miniWinner(decls, subject, prop, attrs) {
  const live = decls.filter((d) => d.rule.at.length === 0 && d.decl.prop === prop && (d.selector === subject ||
    (Colors.splitFirst(d.selector).rest.trim() === subject && Colors.htmlMatches(d.spaced, attrs) === true)));
  live.sort((a, b) => {
    const x = [a.decl.important ? 1 : 0].concat(a.spec, [a.order]);
    const y = [b.decl.important ? 1 : 0].concat(b.spec, [b.order]);
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] - y[i];
    return 0;
  });
  return live.length ? live[live.length - 1] : null;
}

describe('mini-player', () => {
  test('htmlMatchesReadsTheCompactBarsClass', () => {
    // the compact bar is a class on <html>: rules of it apply only there
    const compact = 'html.compact[data-theme="x"]:not([data-deco-off~="a"]) .disc';
    assert.equal(Colors.htmlMatches(compact, { 'data-theme': 'x' }), false);
    assert.equal(Colors.htmlMatches(compact, { 'data-theme': 'x', class: 'low-alpha compact' }), true);
    assert.equal(Colors.htmlMatches(compact, { 'data-theme': 'x', class: 'compact', 'data-deco-off': 'a' }), false);
    assert.equal(Colors.htmlMatches('html.compactish .disc', { class: 'compact' }), false);
    assert.equal(Colors.htmlMatches('html:not(.compact) .info', { class: 'compact' }), false);
    assert.equal(Colors.htmlMatches('html:not(.compact) .info', {}), true);
    assert.deepEqual(miniStates('x').filter((s) => Colors.htmlMatches(compact, s)).length, 2, 'the compact bar, with and without a custom version');
  });

  for (const themeId of ThemeApply.BUILT_IN) {
    test(camel(themeId) + 'MatchesItsGolden', () => {
      assert.deepEqual(Colors.compareToGolden(MINI_GOLDEN[themeId], Colors.miniCss(), themeId,
        Colors.allowedFor('mini', themeId)), []);
    });
  }

  test('everyMiniDecorationRuleIsUnderOnePiece', () => {
    const css = Colors.miniCss();
    const problems = {};
    for (const themeId of ThemeApply.BUILT_IN) {
      const found = pieceGuardProblems(css, themeId, pieceIds(themeId), miniHeads(themeId), MINI_CARD_STYLE[themeId] || []);
      if (found.problems.length) problems[themeId] = found.problems;
      assert.deepEqual([...found.used].sort(), MINI_PIECES[themeId].slice().sort(), themeId + ': the pieces it draws');
    }
    assert.deepEqual(problems, {});
    // the pieces only the mini-player has are all drawn there, and so is the plain themes' glow
    for (const [themeId, only] of Object.entries(MINI_ONLY)) {
      only.forEach((id) => assert.ok(MINI_PIECES[themeId].includes(id), themeId + ' ' + id));
    }
    ThemeApply.BUILT_IN.filter((id) => !DECORATED.includes(id))
      .forEach((id) => assert.deepEqual(MINI_PIECES[id], ['glow'], id));
  });

  test('allMiniPiecesOffLeavesNoDecoration', () => {
    const decls = Colors.declarations(Colors.miniCss());
    const problems = {};
    for (const themeId of ThemeApply.BUILT_IN) {
      const found = piecesOffProblems(decls, themeId, pieceIds(themeId), MINI_LAYOUT_EFFECTS[themeId],
        MINI_CARD_STYLE[themeId] || []);
      assert.ok(found.guarded >= MINI_FLOORS[themeId].guarded, themeId + ': ' + found.guarded + ' declarations under a piece');
      if (found.problems.length) problems[themeId] = found.problems;
      // the base glow behind every card (mini.css) is no skin's: with every piece off it is gone too
      const off = { 'data-theme': themeId, 'data-deco-off': pieceIds(themeId).join(' ') };
      for (const attrs of [off, Object.assign({ class: 'compact' }, off), Object.assign({ 'data-custom-theme': 'x' }, off)]) {
        const shown = miniWinner(decls, '.mini::before', 'display', attrs);
        assert.ok(shown && shown.decl.value === 'none', themeId + ': the glow layer with every piece off: ' +
          (shown ? shown.spaced + ' { display: ' + shown.decl.value + ' }' : 'shown'));
      }
    }
    assert.deepEqual(problems, {});
  });

  test('miniCardStyleIsPinnedAndInTheSlots', () => {
    const css = Colors.miniCss();
    for (const [themeId, cardStyle] of Object.entries(MINI_CARD_STYLE)) {
      assert.deepEqual(cardStyleProblems(css, themeId, cardStyle), [], themeId);
    }
  });

  test('miniDecorationsFollowACustomPalette', () => {
    // a custom version over any skin: every decoration colour changes with its colours, but for plain black and white
    // and see-through ones (the twin of the panel's "colours a custom theme can't reach")
    const css = Colors.miniCss();
    const inline = MiniView.customVars(miniVersion('#3b82f6', '#0b1020', 'dark', '#f43f5e'));
    const problems = {};
    for (const themeId of ThemeApply.BUILT_IN) {
      const customRules = {};
      Colors.declarations(css).filter((d) => d.rule.at.length === 0 &&
        d.selector === 'html[data-custom-theme][data-theme="' + themeId + '"]').forEach((d) => {
        customRules[d.decl.prop] = d.decl.value;
      });
      const blocks = ['html[data-theme="' + themeId + '"]|'].concat(themeId === 'nebula' ? [':root|'] : []);
      const stuck = [];
      let checked = 0;
      for (const e of Colors.decorationColours(css, themeId)) {
        if (blocks.some((b) => e.key.startsWith(b))) continue;
        const before = Colors.evaluate(e.expr, e.vars);
        if (neutral(before)) continue;
        checked++;
        const after = Colors.evaluate(e.expr, Object.assign({}, e.vars, customRules, inline));
        const readThroughASlot = e.via.some((name) => Object.prototype.hasOwnProperty.call(inline, name));
        if (Colors.sameColour(Colors.format(before), after) && !readThroughASlot) stuck.push(e.key + ': ' + e.expr);
      }
      assert.ok(checked >= MINI_FLOORS[themeId].checked, themeId + ': checked ' + checked);
      if (stuck.length) problems[themeId] = stuck;
    }
    assert.deepEqual(problems, {}, 'colours a custom version can\'t reach');
  });

  test('everyMiniSkinSetsItsDecoExtra', () => {
    const css = Colors.miniCss();
    const own = new Set();
    Colors.declarations(css).forEach((d) => {
      if (d.rule.at.length === 0 && d.decl.prop === '--deco-extra' && /^html\[data-theme="[a-z-]+"\]$/.test(d.spaced)) {
        own.add(d.theme);
      }
    });
    for (const themeId of ThemeApply.BUILT_IN) {
      assert.ok(own.has(themeId), themeId + ' sets --deco-extra in its own block');
      assert.equal(Colors.themeVars(css, themeId)['--deco-extra'], PIECES[themeId].decoExtra, themeId);
    }
  });

  test('miniSkinsSecondAccentIsTheirStartingPalettes', () => {
    // a skin whose rules (or custom block) paint with the second accent's numbers has them, and they are the theme's
    // own second accent (what "Use colours from…" gives a custom theme)
    const css = Colors.miniCss();
    const decls = Colors.declarations(css);
    const reading = new Set(decls.filter((d) => !d.decl.prop.startsWith('--accent2-') && /var\(--accent2-[rgb]\)/.test(d.decl.value))
      .map((d) => d.theme || Colors.customThemeOf(d.selector)));
    assert.ok(!reading.has(null), 'only skins paint with them');
    for (const themeId of ThemeApply.BUILT_IN) {
      const own = decls.filter((d) => d.selector === 'html[data-theme="' + themeId + '"]' && /^--accent2-[rgb]$/.test(d.decl.prop));
      assert.equal(own.length, reading.has(themeId) ? 3 : 0, themeId + ': the three numbers where they are read');
      if (!own.length) continue;
      const vars = Colors.themeVars(css, themeId);
      const rgb = Colors.format(Colors.evaluate('rgb(var(--accent2-r), var(--accent2-g), var(--accent2-b))', vars));
      assert.equal(rgb, Colors.format(Colors.evaluate(ThemeCreator.BASE_PALETTES[themeId].colors.accent2)), themeId);
    }
    assert.equal(reading.size, 9);
  });

  test('miniCustomBlocksOnlyGiveTheTonesNoMixOfTheSlotsGives', () => {
    const css = Colors.miniCss();
    const found = {};
    for (const d of Colors.declarations(css)) {
      const themeId = Colors.customThemeOf(d.selector);
      if (themeId === null) continue;
      const where = d.spaced + ' { ' + d.decl.prop + ' }';
      assert.equal(d.rule.at.length, 0, where);
      assert.equal(d.spaced, 'html[data-custom-theme][data-theme="' + themeId + '"]', where + ': the bare custom block');
      assert.ok(d.decl.prop.startsWith('--'), where + ': only the tones, no rule of its own');
      (found[themeId] = found[themeId] || []).push(d.decl.prop);
      // the skin's own value is written out; the custom version's comes from its colours only
      const builtIn = Colors.themeVars(css, themeId)[d.decl.prop];
      assert.ok(builtIn !== undefined && Colors.variablesRead(builtIn, {}).size === 0, where + ': the skin\'s own ' + builtIn);
      Colors.evaluate(builtIn);
      const read = [...Colors.variablesRead(d.decl.value, Colors.themeVars(css, themeId))];
      assert.ok(read.length > 0 && read.every((name) => MINI_ALWAYS_SET.includes(name)), where + ': ' + d.decl.value);
    }
    for (const list of Object.values(found)) list.sort();
    assert.deepEqual(found, MINI_OUT_OF_REACH);
  });

  test('miniCascadeFlipsAreRestoredOrListed', () => {
    const listed = Colors.allowlist().flipsOnPurpose.mini;
    const problems = [];
    const flips = Colors.cascadeFlips(Colors.miniCss(), ThemeApply.BUILT_IN, miniStates);
    for (const f of flips) {
      const why = (listed[f.theme] || {})[f.key];
      if (!f.restored && why === undefined) problems.push(f.theme + ': ' + f.key);
      if (f.restored && why !== undefined) problems.push(f.theme + ': listed but restored: ' + f.key);
    }
    for (const [themeId, entries] of Object.entries(listed)) {
      for (const key of Object.keys(entries)) {
        if (!flips.some((f) => f.theme === themeId && f.key === key)) problems.push(themeId + ': listed but no flip: ' + key);
      }
    }
    assert.deepEqual(problems, []);
  });

  test('onlyAMiniPieceReadsTheSkinsOwnColourVariables', () => {
    const css = Colors.miniCss();
    for (const themeId of ThemeApply.BUILT_IN) {
      assert.deepEqual(ownColourReads(css, themeId, { slots: MINI_ALWAYS_SET, states: miniStates(themeId), plainToo: true }),
        [], themeId);
    }
  });

  test('theOwnColourCheckSeesAGlowACustomVersionLeavesToTheSkin', () => {
    // Dawn's white glow behind its dark text, read by a shared rule: a custom version that doesn't set its own glow wears
    // Dawn's behind its light text. Plain white is no theme's colour in the panel, but in the mini-player it is the skin's.
    const sheet = 'html[data-theme="x"] { --accent: #222222; --legibility-shadow: 0 1px 3px rgba(255, 255, 255, 0.95); }\n' +
      '.low-alpha .title { text-shadow: var(--legibility-shadow); }';
    const states = miniStates('x');
    assert.deepEqual(ownColourReads(sheet, 'x', { slots: ['--accent'], states: states, plainToo: true }),
      ['.low-alpha .title { text-shadow } reads --legibility-shadow']);
    assert.deepEqual(ownColourReads(sheet, 'x', { slots: ['--accent', '--legibility-shadow'], states: states, plainToo: true }),
      [], 'a custom version that always sets its own');
    assert.deepEqual(ownColourReads(sheet, 'x', { slots: ['--accent'], states: states }), [], 'the panel\'s rule: plain white passes');
    assert.equal(MINI_ALWAYS_SET.includes('--deco-extra'), false, 'a version without one wears the skin\'s');
    assert.ok(MINI_ALWAYS_SET.includes('--accent') && MINI_ALWAYS_SET.includes('--accent2-r'));
  });

  test('miniReducedMotionStopsEveryAnimation', () => {
    // the mini-player has no Reduce motion switch of its own (the system's setting is the one, as for its pictures):
    // one rule stops every animation there, and every animation is one it reaches
    const decls = Colors.declarations(Colors.miniCss());
    const stops = decls.filter((d) => d.rule.at.join(' ') === '@media (prefers-reduced-motion:reduce)' &&
      d.decl.prop === 'animation' && /^none$/i.test(d.decl.value.trim()) && d.decl.important);
    assert.deepEqual(stops.map((d) => d.selector).sort(), ['*', '*::after', '*::before']);
    const animated = decls.filter((d) => /^animation(-name)?$/.test(d.decl.prop) && !/^none$/i.test(d.decl.value.trim()));
    const keyframes = new Set(Colors.parseCss(Colors.miniCss()).filter((r) => r.type === 'keyframes').map((r) => r.name));
    const names = new Set(animated.flatMap((d) => (d.decl.value.match(/[-\w]+/g) || []).filter((w) => keyframes.has(w))));
    assert.deepEqual([...names].sort(), ['auroraShift', 'cipherCaret', 'emberFlicker', 'signalSweep', 'spin']);
    for (const d of animated) {
      const where = d.spaced + ' { ' + d.decl.prop + ': ' + d.decl.value + ' }';
      assert.equal(d.decl.important, false, where + ': !important would outlast the stop');
      assert.equal(d.rule.at.length, 0, where);
      assert.ok(['', '::before', '::after'].includes(Colors.subjectOf(d.selector).pseudo), where + ': an element the stop reaches');
      if (d.theme !== null) assert.equal(Colors.guardsOf(d.spaced).ids.length, 1, where + ': a piece\'s');
    }
  });
});
