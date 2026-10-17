'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const ThemeCreator = require('../../main/resources/web/theme-creator.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const ThemePalette = require('../../main/resources/web/theme-palette.js');
const { panelFunction } = require('./panel-source.js');

const INDEX = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8').replace(/\r/g, '');
const THEME_FILE = fs.readFileSync(
  path.join(__dirname, '../../main/java/com/jagrosh/jmusicbot/theme/ThemeFile.java'), 'utf8');

const C = ThemeCreator;
const P = ThemePalette;
const EM_DASH = String.fromCharCode(0x2014);

function javaList(name) {
  const m = new RegExp('List<String> ' + name + ' = list\\(([^;]*)\\);').exec(THEME_FILE);
  assert.ok(m, 'ThemeFile.' + name + ' not found');
  return m[1].match(/"[^"]*"/g).map((s) => s.slice(1, -1));
}

/* The text between the braces of a rule whose selector is exactly this one. */
function cssBlock(selector) {
  const needle = '\n        ' + selector + ' {';
  const at = INDEX.indexOf(needle);
  assert.ok(at >= 0, selector + ' not found');
  let i = at + needle.length, depth = 1;
  const start = i;
  while (depth > 0 && i < INDEX.length) {
    if (INDEX[i] === '{') depth++;
    else if (INDEX[i] === '}') depth--;
    i++;
  }
  return INDEX.slice(start, i - 1);
}

function cssVars(block) {
  const out = {};
  for (const m of block.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

function hex2(n) {
  return Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
}

function cssColor(text) {
  let m = /^#([0-9a-f]{6})$/i.exec(text);
  if (m) return '#' + m[1].toLowerCase();
  m = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(text);
  assert.ok(m, 'not a color: ' + text);
  const a = m[4] === undefined ? 1 : parseFloat(m[4]);
  return '#' + hex2(+m[1]) + hex2(+m[2]) + hex2(+m[3]) + (a >= 1 ? '' : hex2(a * 255));
}

const SLOT_VARS = {
  bgPrimary: '--bg-primary', bgSecondary: '--bg-secondary', bgCard: '--bg-card', bgElevated: '--bg-elevated',
  bgHover: '--bg-hover', accent: '--accent', accentLight: '--accent-light', textPrimary: '--text-primary',
  textSecondary: '--text-secondary', textTertiary: '--text-tertiary', border: '--border', borderLight: '--border-light',
  success: '--success', glass: '--glass', decoExtra: '--deco-extra'
};

const PICTURE = '/theme-images/' + '9f2c'.repeat(8) + '.webp';
const BLOB = 'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70';

/* theme-pieces.json as the panel has it (themePieces): every built-in theme's decoration pieces. */
const PIECES = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/web/theme-pieces.json'), 'utf8'));
const idsOf = (base) => PIECES[base].pieces.map((p) => p.id);

/* A theme's 16 colors as the server sends them (the extra decoration color last, here its accent-light). */
const COLORS = {
  bgPrimary: '#0b0d14', bgSecondary: '#10131c', bgCard: '#161a26', bgElevated: '#1c2131', bgHover: '#262c42',
  accent: '#7c5cff', accentLight: '#a08bff', accent2: '#22d3ee', textPrimary: '#eef0f8',
  textSecondary: '#9aa0b8', textTertiary: '#646a82', border: '#ffffff12', borderLight: '#ffffff1f',
  success: '#34d399', glass: '#10131ce0', decoExtra: '#a08bff'
};

/* The 15 colors of a theme saved before the extra decoration color. */
function oldColors() {
  const colors = Object.assign({}, COLORS);
  delete colors.decoExtra;
  return colors;
}

function savedTheme(extra) {
  return Object.assign({
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: { id: '1', name: 'fred' }, visibility: 'public',
    hiddenByOwner: false, mine: true, version: 3, base: 'ember', decorationsOff: ['edge'], colors: Object.assign({}, COLORS),
    radiusScale: 80, font: 'Outfit', background: { url: PICTURE, dim: 45, blur: 4, fit: 'contain' },
    mini: { colors: P.mini(COLORS), useImage: true, dim: 55 }
  }, extra);
}

/* A repeatable stream of numbers in [0, 1). */
function seeded(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

// ---- the lists must not drift from the stylesheet and the server

test('basesWithSpecialEffectsAreTheOnesWithPanelPieces', () => {
  // theme-pieces.json lists every theme's pieces; the six without panel decorations have only the mini-player's glow
  const pieces = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/web/theme-pieces.json'), 'utf8'));
  const decorated = Object.keys(pieces).filter((id) => pieces[id].pieces.some((p) => p.id !== 'glow'));
  assert.deepEqual([...C.DECORATED_BASES].sort(), decorated.sort());
  for (const base of ThemeApply.BUILT_IN) assert.equal(C.hasDecorations(base), decorated.includes(base), base);
  assert.ok(!INDEX.includes('data-custom-plain]'), 'the stylesheet has no "plain" rule any more: pieces switch off');
});

test('theElevenBasesWithEffectsAreTheOnesTheBriefNames', () => {
  assert.deepEqual([...C.DECORATED_BASES].sort(),
    ['cipher', 'dawn-dark', 'ember', 'grove', 'neon', 'rasta', 'revival', 'signal', 'steel', 'sumi', 'victory']);
  assert.equal(C.hasDecorations('nebula'), false);
  assert.equal(C.hasDecorations('dawn'), false);
  assert.equal(C.hasDecorations('nope'), false);
  assert.equal(C.hasDecorations(undefined), false);
});

test('startingPalettesStillMatchTheStylesheet', () => {
  assert.deepEqual(Object.keys(C.BASE_PALETTES), [...ThemeApply.BUILT_IN]);
  const root = cssVars(cssBlock(':root'));
  for (const id of ThemeApply.BUILT_IN) {
    const vars = Object.assign({}, root, id === 'nebula' ? {} : cssVars(cssBlock('html[data-theme="' + id + '"]')));
    const expected = {};
    for (const slot of P.SLOTS) {
      if (slot !== 'accent2') expected[slot] = cssColor(vars[SLOT_VARS[slot]]);
      else expected.accent2 = '#' + hex2(+vars['--accent2-r']) + hex2(+vars['--accent2-g']) + hex2(+vars['--accent2-b']);
    }
    // the extra decoration color too: the theme block's own --deco-extra
    assert.match(vars['--deco-extra'], /^#[0-9a-f]{6}$/i, id + ' --deco-extra');
    assert.deepEqual(C.BASE_PALETTES[id].colors, expected, id);
    assert.equal(C.BASE_PALETTES[id].font, /^'([^']+)'/.exec(vars['--font-family'])[1], id + ' font');
  }
});

test('baseDecoExtraTable', () => {
  // Rasta's red, Cipher's cyan and Ember's flame-core yellow have no slot of their own; every other theme uses its accent-light
  const own = { rasta: '#dc2626', cipher: '#22d3ee', ember: '#fde047' };
  for (const id of ThemeApply.BUILT_IN) {
    const colors = C.BASE_PALETTES[id].colors;
    assert.equal(colors.decoExtra, own[id] || colors.accentLight, id);
    assert.equal(colors.decoExtra, PIECES[id].decoExtra, id + ': the same as the pieces list the server fills it from');
    assert.match(colors.decoExtra, /^#[0-9a-f]{6}$/, id + ': never see-through');
  }
});

test('theStartingPalettesAreCompleteReadableAndNamedLikeThePicker', () => {
  const select = /<select id="themeSelect"[\s\S]*?<\/select>/.exec(INDEX)[0];
  const labels = {};
  for (const m of select.matchAll(/<option value="([a-z-]+)">([^<]*)<\/option>/g)) labels[m[1]] = m[2];
  for (const id of ThemeApply.BUILT_IN) {
    const base = C.BASE_PALETTES[id];
    assert.ok(labels[id].startsWith(base.label), id + ': ' + labels[id] + ' / ' + base.label);
    assert.ok(ThemeApply.usable({ id: 'aaaaaaaaaaaa', name: 'x', base: id, colors: base.colors }), id);
    assert.deepEqual(Object.keys(base.colors), [...P.SLOTS], id);
    assert.notEqual(ThemeApply.fontStack(base.font), null, id + ' font');
    assert.equal(P.readability(base.colors).ok, true, id + ' readable');
    assert.equal(C.accentVisible(base.colors), true, id + ' main color visible');
  }
});

test('theCreatorKnowsTheSameBasesFontsFitsAndSlotsAsTheServer', () => {
  assert.deepEqual([...ThemeApply.BUILT_IN], javaList('BASES'));
  assert.deepEqual(C.FONTS, javaList('FONTS'));
  assert.deepEqual(C.FITS, javaList('FITS'));
  // all 16, the extra decoration color last, and the mini-player version's 15 (no glass)
  assert.deepEqual([...P.SLOTS], javaList('COLOR_SLOTS'));
  assert.deepEqual([...P.MINI_SLOTS], javaList('COLOR_SLOTS').filter((slot) => slot !== 'glass'));
  assert.equal(P.SLOTS.length, 16);
  assert.equal(P.MINI_SLOTS.length, 15);
  assert.deepEqual(P.SLOTS.slice(-1), ['decoExtra']);
  assert.equal(C.MAX_NAME, 40);
  assert.equal(C.MAX_PICTURE_BYTES, 8 * 1024 * 1024);
  const nameLimit = /MAX_NAME = (\d+)/.exec(THEME_FILE);
  assert.equal(C.MAX_NAME, +nameLimit[1]);
});

// ---- steps

test('thereAreSixStepsInTheOrderOfTheSpec', () => {
  assert.deepEqual(C.STEPS.map((s) => s.id), ['start', 'colors', 'picture', 'tweak', 'mini', 'share']);
  assert.deepEqual(C.STEPS.map((s) => s.title),
    ['Start from', 'Colors', 'Background picture', 'Fine-tune', 'Mini-player', 'Name and share']);
  assert.equal(C.STEP.START, 0);
  assert.equal(C.STEP.COLORS, 1);
  assert.equal(C.STEP.PICTURE, 2);
  assert.equal(C.STEP.TWEAK, 3);
  assert.equal(C.STEP.MINI, 4);
  assert.equal(C.STEP.SHARE, 5);
});

test('aNewThemeOpensOnStartFromAndAnEditOnColors', () => {
  assert.equal(C.firstStep(false), C.STEP.START);
  assert.equal(C.firstStep(true), C.STEP.COLORS);
});

test('nextAndBackWalkTheStepsAndStopAtTheEnds', () => {
  assert.equal(C.nextStep(0), 1);
  assert.equal(C.nextStep(4), 5);
  assert.equal(C.nextStep(5), 5);
  assert.equal(C.prevStep(5), 4);
  assert.equal(C.prevStep(1), 0);
  assert.equal(C.prevStep(0), 0);
  assert.equal(C.nextStep(-3), 1, 'out of range counts as the first step');
  assert.equal(C.nextStep(NaN), 1);
  assert.equal(C.isFirstStep(0), true);
  assert.equal(C.isFirstStep(1), false);
  assert.equal(C.isLastStep(5), true);
  assert.equal(C.isLastStep(4), false);
  assert.equal(C.clampStep(99), 5);
  assert.equal(C.clampStep('2'), 2);
  assert.equal(C.clampStep(undefined), 0);
});

test('nextAlwaysWorksOnEveryStepButTheLastWhereItSaves', () => {
  for (let i = 0; i < 5; i++) assert.equal(C.nextLabel(i), 'Next', String(i));
  assert.equal(C.nextLabel(5), 'Save theme');
  assert.equal(C.nextLabel(5, true), 'Saving...');
});

// ---- starting a theme

test('startingFromABuiltInTakesItsColorsFontAndEveryPiece', () => {
  const s = C.startState('ember');
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, C.BASE_PALETTES.ember.colors);
  assert.equal(s.colors.decoExtra, '#fde047', "the starting theme's own extra decoration color");
  assert.equal(s.decoExtraSetByHand, false);
  assert.equal(s.font, C.BASE_PALETTES.ember.font);
  assert.deepEqual(s.decorationsOff, [], 'every piece on');
  assert.equal('effects' in s, false);
  assert.equal(s.radiusScale, 100);
  assert.equal(s.mainColor, C.BASE_PALETTES.ember.colors.accent);
  assert.equal(s.pageColor, C.BASE_PALETTES.ember.colors.bgPrimary);
  assert.equal(s.mode, 'dark');
  assert.equal(s.picture, 'none');
  assert.deepEqual(s.mini, { colors: P.mini(s.colors), useImage: false, dim: 40 }, 'a new theme starts with a mini-player version made from its colors');
  assert.equal(s.name, '');
  assert.equal(s.visibility, 'public');
  assert.equal(s.id, null);
  assert.equal(C.startState('dawn').mode, 'light');
  assert.equal(C.startState('nope').base, 'nebula');
  assert.notEqual(C.startState('ember').colors, C.BASE_PALETTES.ember.colors, 'a copy, so tweaks never change the table');
});

test('startingFromACommunityThemeTakesItsLookButNotItsPictureNameOrItsOwnMiniPlayerColors', () => {
  const theirs = Object.assign(P.mini(COLORS), { accent: '#ff7a00', accentLight: '#ff9a40' });
  const s = C.startFromTheme(savedTheme({ mine: false, owner: { id: '9', name: 'sam' }, mini: { colors: theirs, useImage: true, dim: 70 } }), PIECES);
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, COLORS);
  assert.equal(s.font, 'Outfit');
  assert.equal(s.radiusScale, 80);
  assert.deepEqual(s.decorationsOff, ['edge'], 'its pieces');
  assert.equal(s.picture, 'none');
  assert.deepEqual(s.mini, { colors: P.mini(COLORS), useImage: false, dim: 40 }, 'its look, made into a mini-player version of our own');
  assert.equal(s.id, null);
  assert.equal(s.name, '');
});

test('editingFillsTheStateFromTheSavedTheme', () => {
  const s = C.fromTheme(savedTheme(), PIECES);
  assert.equal(s.id, 'k3j9x0q2m7ab');
  assert.equal(s.version, 3);
  assert.equal(s.name, 'Midnight Drive');
  assert.equal(s.visibility, 'public');
  assert.equal(s.hiddenByOwner, false);
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.decorationsOff, ['edge'], 'the pieces it has off; its picture does not change them');
  assert.deepEqual(s.colors, COLORS);
  assert.equal(s.mainColor, '#7c5cff');
  assert.equal(s.pageColor, '#0b0d14');
  assert.equal(s.mode, 'dark');
  assert.equal(s.radiusScale, 80);
  assert.equal(s.font, 'Outfit');
  assert.equal(s.picture, 'saved');
  assert.equal(s.hadPicture, true);
  assert.equal(s.savedPictureUrl, PICTURE);
  assert.equal(s.dim, 45);
  assert.equal(s.blur, 4);
  assert.equal(s.fit, 'contain');
  assert.deepEqual(s.mini, { colors: P.mini(COLORS), useImage: true, dim: 55 });
});

test('piecesStartFromTheSavedTheme', () => {
  // the pieces the theme has off, as saved: ids its starting theme doesn't have dropped, each once, in their order
  const saved = savedTheme({ base: 'revival', decorationsOff: ['rays', 'flames', 'rays', 'record'], background: null });
  const s = C.fromTheme(saved, PIECES);
  assert.deepEqual(s.decorationsOff, ['rays', 'record']);
  assert.deepEqual(C.buildValues(s).decorationsOff, ['rays', 'record'], 'saved again as it was');
  const view = C.piecesView(s, PIECES);
  assert.deepEqual(view.pieces.map((p) => p.id), idsOf('revival'), "one switch per piece of the starting theme, in the list's order");
  assert.deepEqual(view.pieces.map((p) => p.label), PIECES.revival.pieces.map((p) => p.label));
  assert.deepEqual(view.pieces.filter((p) => !p.on).map((p) => p.id), ['rays', 'record']);
  assert.equal(view.pieces.some((p) => p.forced), false, 'no picture, nothing forced');
  // starting from it, or choosing it as the start, takes its pieces as well
  assert.deepEqual(C.startFromTheme(saved, PIECES).decorationsOff, ['rays', 'record']);
  assert.deepEqual(C.chooseTheme(C.startState('nebula'), saved, PIECES).decorationsOff, ['rays', 'record']);
  // a theme with every piece on
  assert.deepEqual(C.fromTheme(savedTheme({ decorationsOff: [] }), PIECES).decorationsOff, []);
  // the state it came from is a copy: switching a piece doesn't touch the theme it was opened from
  C.setPiece(s, 'logo', false, PIECES);
  assert.deepEqual(saved.decorationsOff, ['rays', 'flames', 'rays', 'record']);
  // without the pieces list: no switches, and the list is saved as it came (the server keeps the base's ids)
  const blind = C.fromTheme(saved, null);
  assert.equal(C.piecesView(blind, null), null);
  assert.deepEqual(C.buildValues(blind).decorationsOff, ['rays', 'flames', 'record']);
  assert.equal(C.setPiece(blind, 'logo', false, null), blind, 'nothing to switch without the list');
  assert.equal(C.allPieces(blind, true, null), blind);
});

test('setPieceSwitchesOnePieceAndKeepsTheOrder', () => {
  let s = C.startState('revival');
  s = C.setPiece(s, 'rays', false, PIECES);
  s = C.setPiece(s, 'glows', false, PIECES);
  assert.deepEqual(s.decorationsOff, ['rays', 'glows']);
  assert.deepEqual(C.setPiece(s, 'rays', true, PIECES).decorationsOff, ['glows']);
  assert.equal(C.setPiece(s, 'rays', false, PIECES).decorationsOff.length, 2, 'off twice is off once');
  for (const bad of ['flames', 'nope', '', null, undefined, '__proto__', 'a b']) {
    assert.equal(C.setPiece(s, bad, false, PIECES), s, String(bad) + ' is not a piece of Revival');
  }
  // the six themes with nothing on the page have the mini-player's glow
  assert.deepEqual(C.setPiece(C.startState('nebula'), 'glow', false, PIECES).decorationsOff, ['glow']);
});

test('allOnAllOff', () => {
  const s = C.startState('revival');
  const off = C.allPieces(s, false, PIECES);
  assert.deepEqual(off.decorationsOff, idsOf('revival'));
  let view = C.piecesView(off, PIECES);
  assert.equal(view.pieces.every((p) => !p.on), true);
  assert.equal(view.allOff, true);
  assert.equal(view.allOn, false);
  const on = C.allPieces(C.setPiece(off, 'record', true, PIECES), true, PIECES);
  assert.deepEqual(on.decorationsOff, []);
  view = C.piecesView(on, PIECES);
  assert.equal(view.pieces.every((p) => p.on), true);
  assert.equal(view.allOn, true);
  assert.equal(view.allOff, false);
  assert.deepEqual(s.decorationsOff, [], 'the state it was given is left alone');
  assert.deepEqual(C.allPieces(C.startState('dawn'), false, PIECES).decorationsOff, ['glow']);
  // with a picture the saved list still follows the buttons
  const picture = C.withNewPicture(s, 30);
  assert.deepEqual(C.allPieces(picture, false, PIECES).decorationsOff, idsOf('revival'));
  assert.deepEqual(C.allPieces(C.allPieces(picture, false, PIECES), true, PIECES).decorationsOff, []);
});

test('pictureForcesBackgroundPiecesOff', () => {
  // Fred's case: Revival with the record on and the rays off, then a picture
  const s = C.setPiece(C.startState('revival'), 'rays', false, PIECES);
  const picture = C.withNewPicture(s, 30);
  // the saved list does not change: a picture is not a choice about the pieces
  assert.deepEqual(picture.decorationsOff, ['rays']);
  assert.deepEqual(C.buildValues(picture).decorationsOff, ['rays']);
  // the switches: the background pieces show off and can't be switched while the picture is on
  const view = C.piecesView(picture, PIECES);
  const byId = Object.fromEntries(view.pieces.map((p) => [p.id, p]));
  for (const piece of PIECES.revival.pieces) {
    assert.equal(byId[piece.id].forced, piece.background === true, piece.id);
    if (piece.background) assert.equal(byId[piece.id].on, false, piece.id + ' shows off');
  }
  assert.equal(byId.record.on, true, 'the record stays');
  assert.equal(byId.logo.on, true);
  assert.equal(view.pictureNote, true);
  assert.equal(view.pieces.find((p) => p.forced).hint, C.TEXT.PIECE_PICTURE_OFF);
  // the preview: the page gets the saved list plus the background pieces
  const t = C.previewTheme(picture, BLOB);
  assert.deepEqual(t.decorationsOff, ['rays']);
  const local = ThemeApply.localPicture(BLOB, false);
  assert.equal(ThemeApply.decoOffFor(t, !!ThemeApply.backdrop(t.background, local), PIECES), 'rays sunrise');
  // the picture goes: the pieces are as they were saved
  const without = C.withoutPicture(picture);
  assert.deepEqual(without.decorationsOff, ['rays']);
  assert.equal(C.piecesView(without, PIECES).pieces.find((p) => p.id === 'sunrise').on, true);
  assert.equal(C.piecesView(without, PIECES).pictureNote, false);
  // a saved picture counts too, and a theme without background pieces has nothing to force
  assert.equal(C.piecesView(C.fromTheme(savedTheme({ base: 'cipher', decorationsOff: [] }), PIECES), PIECES)
    .pieces.find((p) => p.id === 'grid').forced, true);
  const ember = C.piecesView(C.withNewPicture(C.startState('ember'), 30), PIECES);
  assert.equal(ember.pieces.some((p) => p.forced), false);
  assert.equal(ember.pictureNote, false);
});

test('theSixThemesWithoutDecorationsShowTheirMiniPlayerGlow', () => {
  for (const base of ThemeApply.BUILT_IN) {
    const view = C.piecesView(C.startState(base), PIECES);
    assert.equal(view.miniOnly, !C.hasDecorations(base), base);
    if (view.miniOnly) {
      assert.deepEqual(view.pieces.map((p) => p.id), ['glow'], base);
      assert.equal(view.pieces[0].hint, C.TEXT.PIECE_MINI_ONLY, base);
    }
  }
  assert.match(C.TEXT.PIECE_MINI_ONLY, /mini-player/);
});

test('changingTheStartingThemeKeepsOnlyThePiecesOffThatItHas', () => {
  let s = C.setPiece(C.setPiece(C.startState('revival'), 'glows', false, PIECES), 'rays', false, PIECES);
  assert.deepEqual(s.decorationsOff, ['glows', 'rays']);
  assert.deepEqual(C.chooseBase(s, 'neon', PIECES).decorationsOff, ['glows'], 'Neon has glows too, not rays');
  assert.deepEqual(C.chooseBase(s, 'nebula', PIECES).decorationsOff, []);
  // a community theme's own list is what counts when starting from it
  assert.deepEqual(C.chooseTheme(s, savedTheme({ base: 'ember', decorationsOff: ['fire'] }), PIECES).decorationsOff, ['fire']);
  // without the list the ids stay as they are (the server keeps the ones the new base has)
  assert.deepEqual(C.chooseBase(s, 'neon', null).decorationsOff, ['glows', 'rays']);
});

test('anOldThemeReopensWithItsPiecesOffAndSavesInTheNewForm', () => {
  // saved before the pieces: effects false and 15 colors (no extra decoration color), a 14-color mini-player version
  const old = savedTheme({ base: 'ember', effects: false, colors: oldColors(), background: null,
    mini: { colors: P.mini(oldColors()), useImage: false, dim: 40 } });
  delete old.decorationsOff;
  const s = C.fromTheme(old, PIECES);
  assert.deepEqual(s.decorationsOff, idsOf('ember'), 'effects off is every piece of its starting theme off');
  assert.equal(s.colors.decoExtra, '#fde047', "the extra decoration color is the starting theme's own");
  assert.equal(s.decoExtraSetByHand, false);
  assert.equal(C.miniFollows(s), true, 'its mini-player version still follows the theme');
  const v = C.buildValues(s);
  assert.equal('effects' in v, false);
  assert.deepEqual(v.decorationsOff, idsOf('ember'));
  assert.deepEqual(Object.keys(v.colors), javaList('COLOR_SLOTS'));
  assert.equal(v.colors.decoExtra, '#fde047');
  assert.equal(Object.keys(v.mini.colors).length, 15);
  assert.equal(v.mini.colors.decoExtra, '#fde047');
  // effects on, or no flag at all: every piece on
  const on = Object.assign({}, old, { effects: true });
  assert.deepEqual(C.fromTheme(on, PIECES).decorationsOff, []);
  const none = Object.assign({}, old);
  delete none.effects;
  assert.deepEqual(C.fromTheme(none, PIECES).decorationsOff, []);
  // the server's conversion, whatever the picture: effects false is every piece off
  assert.deepEqual(C.fromTheme(Object.assign({}, old, { background: savedTheme().background }), PIECES).decorationsOff, idsOf('ember'));
});

test('editingAThemeWithoutAPictureOrMini', () => {
  const s = C.fromTheme(savedTheme({ background: null, mini: null }), PIECES);
  assert.equal(s.picture, 'none');
  assert.equal(s.hadPicture, false);
  assert.equal(s.savedPictureUrl, '');
  assert.equal(s.mini, null);
  assert.deepEqual(s.decorationsOff, ['edge']);
});

test('editingAHiddenThemeKeepsItPrivate', () => {
  const s = C.fromTheme(savedTheme({ hiddenByOwner: true, visibility: 'private' }));
  assert.equal(s.hiddenByOwner, true);
  assert.equal(s.visibility, 'private');
  // and whatever the state says, it is never sent as public
  assert.equal(C.buildValues(Object.assign({}, s, { visibility: 'public' })).visibility, 'private');
});

test('editingKeepsColorsAsStoredButLowerCased', () => {
  const upper = Object.assign({}, COLORS, { accent: '#7C5CFF', glass: '#10131CE0' });
  const s = C.fromTheme(savedTheme({ colors: upper }));
  assert.equal(s.colors.accent, '#7c5cff');
  assert.equal(s.colors.glass, '#10131ce0');
  assert.equal(s.mainColor, '#7c5cff');
});

test('editingDoesNotTrustOddNumbersFromTheServer', () => {
  const s = C.fromTheme(savedTheme({ radiusScale: 9000, background: { url: PICTURE, dim: 500, blur: -4, fit: 'weird' } }));
  assert.equal(s.radiusScale, 200);
  assert.equal(s.dim, 90);
  assert.equal(s.blur, 0);
  assert.equal(s.fit, 'cover');
});

test('aPictureLinkThatIsNotOursIsNotKeptWhenEditing', () => {
  const s = C.fromTheme(savedTheme({ background: { url: 'https://evil.example/x.png', dim: 45, blur: 4, fit: 'cover' } }));
  assert.equal(s.picture, 'none');
  assert.equal(s.savedPictureUrl, '');
});

// ---- name and sharing

test('theNameAndVisibilityAreSetThroughTheState', () => {
  const s = C.startState('nebula');
  assert.equal(C.setName(s, 'Hello').name, 'Hello');
  assert.equal(C.setName(s, 12).name, '', 'not text is no name');
  assert.equal(s.name, '', 'the state it was given is not changed');
  assert.equal(C.setVisibility(s, 'private').visibility, 'private');
  assert.equal(C.setVisibility(C.setVisibility(s, 'private'), 'public').visibility, 'public');
  assert.equal(C.setVisibility(s, 'friends').visibility, 'public');
});

test('aThemeTheBotOwnerHidCannotBeMadePublic', () => {
  const hidden = C.fromTheme(savedTheme({ hiddenByOwner: true, visibility: 'private' }));
  assert.equal(C.setVisibility(hidden, 'public').visibility, 'private');
  assert.equal(C.setVisibility(hidden, 'private').visibility, 'private');
});

// ---- choosing colors

test('theMainColorIsKeptAndTheRestIsGeneratedAndReadable', () => {
  const s = C.setMain(C.startState('nebula'), '#ff7a00');
  assert.equal(s.mainColor, '#ff7a00');
  assert.equal(s.colors.accent, '#ff7a00');
  assert.equal(s.pageColor, C.startState('nebula').pageColor);
  assert.equal(P.readability(s.colors).ok, true);
  assert.equal(s.base, 'nebula');
  assert.deepEqual(s.colors, P.generate('#ff7a00', s.pageColor, 'dark'));
});

test('theBackgroundChoiceSetsTheModeFromHowLightItIs', () => {
  const dark = C.setPage(C.startState('dawn'), '#101820');
  assert.equal(dark.mode, 'dark');
  assert.equal(dark.pageColor, '#101820');
  assert.equal(dark.colors.bgPrimary, '#101820');
  const light = C.setPage(C.startState('nebula'), '#f5f0e6');
  assert.equal(light.mode, 'light');
  assert.equal(P.readability(light.colors).ok, true);
});

test('switchingBetweenDarkAndLightPicksTheFirstSuggestedBackground', () => {
  const start = C.setMain(C.startState('nebula'), '#3b82f6');
  const light = C.setMode(start, 'light');
  assert.equal(light.mode, 'light');
  assert.equal(light.pageColor, P.suggestBackgrounds('#3b82f6', 'light')[0]);
  assert.equal(light.mainColor, '#3b82f6');
  const back = C.setMode(light, 'dark');
  assert.equal(back.pageColor, P.suggestBackgrounds('#3b82f6', 'dark')[0]);
  assert.equal(C.setMode(start, 'nonsense').mode, 'dark');
});

test('aColorThatIsNotAColorChangesNothing', () => {
  const s = C.startState('nebula');
  for (const bad of ['red', '#12', '#12345', 'url(x)', '', null, undefined, 5, '#gggggg']) {
    assert.equal(C.setMain(s, bad), s, String(bad));
    assert.equal(C.setPage(s, bad), s, String(bad));
  }
});

test('surpriseMeGivesAReadableThemeFromTheRandomPair', () => {
  const random = seeded(7);
  for (let i = 0; i < 40; i++) {
    const s = C.surpriseMe(C.startState('steel'), random);
    assert.equal(P.readability(s.colors).ok, true);
    assert.equal(C.accentVisible(s.colors), true);
    assert.equal(s.base, 'steel', 'only the colors change');
    assert.equal(s.colors.accent, s.mainColor);
    assert.equal(s.colors.bgPrimary, s.pageColor);
  }
});

test('changingColorsKeepsEverythingElseTheCreatorHasDone', () => {
  let s = C.fromTheme(savedTheme());
  s = C.setMain(s, '#22c55e');
  assert.equal(s.id, 'k3j9x0q2m7ab');
  assert.equal(s.name, 'Midnight Drive');
  assert.equal(s.picture, 'saved');
  assert.equal(s.radiusScale, 80);
  assert.equal(s.font, 'Outfit');
  assert.ok(s.mini);
});

test('chooseBaseStartsTheLookOverButKeepsThePictureNameAndMini', () => {
  let s = C.fromTheme(savedTheme(), PIECES);
  s = C.chooseBase(s, 'dawn', PIECES);
  assert.equal(s.base, 'dawn');
  assert.deepEqual(s.colors, C.BASE_PALETTES.dawn.colors);
  assert.equal(s.font, C.BASE_PALETTES.dawn.font);
  assert.equal(s.radiusScale, 100);
  assert.deepEqual(s.decorationsOff, [], "Dawn has no piece called edge");
  assert.equal(s.mode, 'light');
  assert.equal(s.id, 'k3j9x0q2m7ab');
  assert.equal(s.name, 'Midnight Drive');
  assert.equal(s.picture, 'saved');
  assert.equal(s.dim, 45);
  assert.ok(s.mini);
  assert.equal(C.chooseBase(s, 'nope', PIECES).base, 'nebula');
});

test('chooseThemeStartsFromACommunityThemesLook', () => {
  const s = C.chooseTheme(C.startState('nebula'), savedTheme({ mine: false, background: null }), PIECES);
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, COLORS);
  assert.equal(s.font, 'Outfit');
  assert.equal(s.radiusScale, 80);
  assert.deepEqual(s.decorationsOff, ['edge']);
});

// ---- colors from any theme, and the extra decoration color

test('useColoursFromFillsEverySlot', () => {
  // Fred's case: Revival's shape and decorations, Nebula's colors
  const s = C.setPiece(C.withNewPicture(C.setCorners(C.startState('revival'), 60), 30), 'rays', false, PIECES);
  const u = C.useColoursFrom(s, 'nebula');
  assert.deepEqual(u.colors, C.BASE_PALETTES.nebula.colors, 'all 16, the extra decoration color too');
  assert.deepEqual(Object.keys(u.colors), [...P.SLOTS]);
  assert.notEqual(u.colors, C.BASE_PALETTES.nebula.colors, 'a copy, so tweaks never change the table');
  assert.equal(u.mainColor, C.BASE_PALETTES.nebula.colors.accent);
  assert.equal(u.pageColor, C.BASE_PALETTES.nebula.colors.bgPrimary);
  assert.equal(u.mode, 'dark');
  // everything else stays: the starting theme, its font and corners, the pieces, the picture
  assert.equal(u.base, 'revival');
  assert.equal(u.font, s.font);
  assert.equal(u.radiusScale, 60);
  assert.deepEqual(u.decorationsOff, ['rays']);
  assert.equal(u.picture, 'new');
  assert.deepEqual(u.mini.colors, P.mini(u.colors), 'a following mini-player version follows');
  for (const id of ThemeApply.BUILT_IN) {
    const from = C.useColoursFrom(s, id);
    assert.deepEqual(from.colors, C.BASE_PALETTES[id].colors, id);
    assert.equal(from.mode, id === 'dawn' ? 'light' : 'dark', id);
  }
  assert.equal(C.useColoursFrom(C.startState('ember'), 'rasta').colors.decoExtra, '#dc2626', "Rasta's red comes along");
  for (const bad of ['nope', '', null, undefined, '__proto__', 'constructor']) assert.equal(C.useColoursFrom(s, bad), s, String(bad));
  assert.deepEqual(s.colors, C.BASE_PALETTES.revival.colors, 'the state it was given is left alone');
  // the chip of the theme whose colors are on shows as chosen
  assert.equal(C.colorsAreFrom(u, 'nebula'), true);
  assert.equal(C.colorsAreFrom(u, 'revival'), false);
  assert.equal(C.colorsAreFrom(C.setMain(u, '#ff7a00'), 'nebula'), false);
  assert.equal(C.colorsAreFrom(u, 'nope'), false);
});

test('decoExtraFollowsColoursUntilSetByHand', () => {
  // a starting theme gives its own; any new main color, background or mode makes it the new accent-light
  const rasta = C.startState('rasta');
  assert.equal(rasta.colors.decoExtra, '#dc2626');
  assert.equal(rasta.decoExtraSetByHand, false);
  for (const step of [
    (x) => C.setMain(x, '#3b82f6'),
    (x) => C.setMain(x, C.accentSuggestions(x.pageColor)[3]),
    (x) => C.setPage(x, '#201020'),
    (x) => C.setMode(x, 'light'),
    (x) => C.surpriseMe(x, seeded(3))
  ]) {
    const next = step(rasta);
    assert.equal(next.colors.decoExtra, next.colors.accentLight, String(step));
    assert.equal(next.decoExtraSetByHand, false);
  }
  // set in Fine-tune: it stays through every color change of step 2
  const own = C.setDecoExtra(rasta, '#FF0000');
  assert.equal(own.colors.decoExtra, '#ff0000');
  assert.equal(own.decoExtraSetByHand, true);
  assert.equal(rasta.decoExtraSetByHand, false, 'the state it was given is left alone');
  let kept = C.setMain(own, '#3b82f6');
  kept = C.setPage(kept, '#102030');
  kept = C.setMode(kept, 'light');
  kept = C.surpriseMe(kept, seeded(5));
  kept = C.fixIt(kept).state;
  assert.equal(kept.colors.decoExtra, '#ff0000');
  assert.equal(kept.decoExtraSetByHand, true);
  // the Fine-tune color list sets it the same way
  const tweaked = C.tweakColor(rasta, 'decoExtra', '#00ff00');
  assert.equal(tweaked.colors.decoExtra, '#00ff00');
  assert.equal(tweaked.decoExtraSetByHand, true);
  // a new light main color in Fine-tune takes along one that follows the colors, and leaves a theme's own or a chosen one
  const following = C.tweakColor(C.setMain(rasta, '#3b82f6'), 'accentLight', '#123456');
  assert.equal(following.colors.decoExtra, '#123456');
  assert.equal(following.decoExtraSetByHand, false);
  assert.equal(C.tweakColor(rasta, 'accentLight', '#123456').colors.decoExtra, '#dc2626', "Rasta's own red stays");
  assert.equal(C.tweakColor(tweaked, 'accentLight', '#123456').colors.decoExtra, '#00ff00');
  assert.equal(C.tweakColor(rasta, 'bgCard', '#123456').colors.decoExtra, '#dc2626', 'other colors leave it alone');
  for (const bad of ['red', '#ff000080', '#12', '', null, undefined]) assert.equal(C.setDecoExtra(rasta, bad), rasta, String(bad));
  // "Use colors from" takes that theme's and lets it follow again
  const cipher = C.useColoursFrom(kept, 'cipher');
  assert.equal(cipher.colors.decoExtra, '#22d3ee');
  assert.equal(cipher.decoExtraSetByHand, false);
  assert.equal(C.setMain(cipher, '#3b82f6').colors.decoExtra, C.setMain(cipher, '#3b82f6').colors.accentLight);
  // a new starting theme starts it over
  const ember = C.chooseBase(kept, 'ember', PIECES);
  assert.equal(ember.colors.decoExtra, '#fde047');
  assert.equal(ember.decoExtraSetByHand, false);
  // reopening: set by hand when it is neither the theme's accent-light nor its starting theme's own
  const reopen = (decoExtra, base) => C.fromTheme(savedTheme({ base: base || 'ember', colors: Object.assign({}, COLORS, { decoExtra }) }), PIECES);
  assert.equal(reopen(COLORS.accentLight).decoExtraSetByHand, false);
  assert.equal(reopen('#fde047').decoExtraSetByHand, false, "Ember's own");
  assert.equal(reopen('#dc2626', 'rasta').decoExtraSetByHand, false, "Rasta's own");
  assert.equal(reopen('#dc2626').decoExtraSetByHand, true, "Rasta's red on Ember was chosen");
  assert.equal(C.setMain(reopen('#dc2626'), '#3b82f6').colors.decoExtra, '#dc2626', 'and stays');
  assert.equal(C.setMain(reopen('#fde047'), '#3b82f6').colors.decoExtra, C.setMain(reopen('#fde047'), '#3b82f6').colors.accentLight);
  // a theme saved with "Use colors from" a theme that has its own (Rasta, Cipher, Ember) on another base: not by hand
  for (const [id, own] of [['rasta', '#dc2626'], ['cipher', '#22d3ee'], ['ember', '#fde047']]) {
    const saved = C.buildValues(C.useColoursFrom(C.startState('revival'), id));
    const back = C.fromTheme(savedTheme({ base: 'revival', colors: saved.colors }), PIECES);
    assert.equal(back.colors.decoExtra, own, id);
    assert.equal(back.decoExtraSetByHand, false, id + "'s colors, all 16: borrowed, not set by hand");
    assert.equal(C.setMain(back, '#3b82f6').colors.decoExtra, C.setMain(back, '#3b82f6').colors.accentLight, id + ': it follows again');
    // its colors changed afterwards: that red, cyan or yellow on another base was kept on purpose
    const tweaked = C.buildValues(C.tweakColor(C.useColoursFrom(C.startState('revival'), id), 'bgCard', '#123456'));
    assert.equal(C.fromTheme(savedTheme({ base: 'revival', colors: tweaked.colors }), PIECES).decoExtraSetByHand, true, id + ' tweaked');
  }
  // the mini-player version takes it along while it follows the theme
  assert.equal(own.mini.colors.decoExtra, '#ff0000');
});

// ---- fine-tuning

test('everyFineTuneColorHasAPlainNameAndTheDerivedOnesDoNot', () => {
  const slots = C.COLOR_CHOICES.map((c) => c.slot);
  assert.equal(new Set(slots).size, slots.length);
  assert.deepEqual(slots.filter((s) => !P.SLOTS.includes(s)), []);
  assert.deepEqual([...P.SLOTS].filter((s) => !slots.includes(s)), ['borderLight', 'glass']);
  const labels = C.COLOR_CHOICES.map((c) => c.label);
  for (const wanted of ['Background', 'Panels', 'Cards', 'Hover', 'Main color', 'Main text', 'Secondary text', 'Borders', 'Extra decoration color']) {
    assert.ok(labels.includes(wanted), wanted);
  }
  for (const c of C.COLOR_CHOICES) {
    assert.match(c.label, /^[A-Z][a-z]+( [a-z]+)*$/, 'a plain name, not a variable name');
    assert.equal(typeof c.hint, 'string');
  }
});

test('theExtraDecorationColorIsOfferedOnlyForTheElevenDecoratedThemes', () => {
  for (const base of ThemeApply.BUILT_IN) {
    const slots = C.fineTuneColors(base).map((c) => c.slot);
    assert.equal(slots.includes('decoExtra'), C.hasDecorations(base), base);
    assert.deepEqual(slots.filter((s) => s !== 'decoExtra'), C.COLOR_CHOICES.map((c) => c.slot).filter((s) => s !== 'decoExtra'), base);
  }
});

test('tweakingAColorKeepsItsTransparency', () => {
  const s = C.tweakColor(C.fromTheme(savedTheme()), 'border', '#ff0000');
  assert.equal(s.colors.border, '#ff000012');
  const opaque = C.tweakColor(C.fromTheme(savedTheme()), 'bgCard', '#112233');
  assert.equal(opaque.colors.bgCard, '#112233');
});

test('theDerivedColorsFollowTheOnesTheyAreMadeFrom', () => {
  const s = C.fromTheme(savedTheme());
  const panels = C.tweakColor(s, 'bgSecondary', '#223344');
  assert.equal(panels.colors.glass, '#223344e0', 'the header glass follows Panels');
  const borders = C.tweakColor(s, 'border', '#00ff00');
  assert.equal(borders.colors.borderLight, '#00ff001f', 'light borders follow Borders');
});

test('tweakingTheMainColorOrBackgroundUpdatesTheStepTwoPicks', () => {
  const s = C.fromTheme(savedTheme());
  const main = C.tweakColor(s, 'accent', '#00ffaa');
  assert.equal(main.mainColor, '#00ffaa');
  assert.equal(main.colors.accent, '#00ffaa');
  assert.equal(main.colors.bgCard, COLORS.bgCard, 'nothing else is regenerated');
  const page = C.tweakColor(s, 'bgPrimary', '#f0f0f0');
  assert.equal(page.pageColor, '#f0f0f0');
  assert.equal(page.mode, 'light');
});

test('tweakingIgnoresWhatIsNotAColorSlotOrColor', () => {
  const s = C.startState('nebula');
  assert.equal(C.tweakColor(s, 'glass', '#000000'), s, 'glass is derived');
  assert.equal(C.tweakColor(s, 'borderLight', '#000000'), s, 'light borders are derived');
  assert.equal(C.tweakColor(s, 'nope', '#000000'), s);
  assert.equal(C.tweakColor(s, '__proto__', '#000000'), s);
  assert.equal(C.tweakColor(s, 'accent', 'red'), s);
  assert.equal(C.tweakColor(s, 'accent', '#ff000080'), s, 'the picker gives plain #rrggbb');
  assert.equal(s.colors.accent, C.BASE_PALETTES.nebula.colors.accent, 'the original is not touched');
});

test('thereIsASetterForCornersAndFontThatKeepsThemInRange', () => {
  const s = C.startState('ember');
  assert.equal(C.setCorners(s, 40).radiusScale, 40);
  assert.equal(C.setCorners(s, 400).radiusScale, 200);
  assert.equal(C.setCorners(s, -4).radiusScale, 0);
  assert.equal(C.setCorners(s, '75').radiusScale, 75);
  assert.equal(C.setCorners(s, 'wide').radiusScale, 100);
  assert.equal(C.setFont(s, 'Syne').font, 'Syne');
  assert.equal(C.setFont(s, 'Comic Sans').font, s.font);
  // the one effects switch is gone: pieces are switched one by one
  assert.equal('setEffects' in C, false);
});

// ---- readability

test('theColorsOfAGoodPaletteAreReadableAndVisible', () => {
  const report = C.report(P.generate('#7c5cff', '#0b0d14', 'dark'));
  assert.deepEqual(report, { ok: true, problems: [], accentVisible: true });
});

test('hardToReadTextIsReportedPerSlot', () => {
  const bad = Object.assign({}, COLORS, { textPrimary: '#20243a', textSecondary: '#1c2131' });
  const report = C.report(bad);
  assert.equal(report.ok, false);
  assert.deepEqual(report.problems, ['textPrimary', 'textSecondary']);
});

test('aMainColorThatBlendsIntoTheBackgroundIsReported', () => {
  const dim = Object.assign({}, COLORS, { accent: '#1a1f30' });
  assert.equal(C.accentVisible(dim), false);
  assert.equal(C.report(dim).accentVisible, false);
  assert.equal(C.accentVisible(Object.assign({}, COLORS, { accent: '#ffffff' })), true);
  assert.equal(C.accentVisible(COLORS), true);
});

test('aMainColorIsVisibleFromThreeToOneAndNotBelow', () => {
  const page = Object.assign({}, COLORS, { bgPrimary: '#000000' });
  const faint = Object.assign({}, page, { accent: '#555555' });
  const clear = Object.assign({}, page, { accent: '#606060' });
  assert.ok(P.contrast('#555555', '#000000') > 2.5 && P.contrast('#555555', '#000000') < 3);
  assert.ok(P.contrast('#606060', '#000000') > 3 && P.contrast('#606060', '#000000') < 3.5);
  assert.equal(C.accentVisible(faint), false);
  assert.equal(C.accentVisible(clear), true);
});

test('fixItMovesTheTextJustEnoughAndLeavesTheRest', () => {
  const bad = Object.assign({}, COLORS, { textPrimary: '#3a4060', textSecondary: '#2a3050' });
  const fix = C.fixIt(Object.assign(C.startState('nebula'), { colors: bad }));
  assert.equal(fix.ok, true);
  assert.equal(fix.farApart, false);
  assert.deepEqual(fix.highlight, []);
  assert.equal(P.readability(fix.state.colors).ok, true);
  for (const slot of P.SLOTS) {
    if (slot !== 'textPrimary' && slot !== 'textSecondary') assert.equal(fix.state.colors[slot], bad[slot], slot);
  }
});

test('whenCardsAndBackgroundAreTooFarApartFixItSaysSoAndNamesTheTwo', () => {
  // a page below the middle and a card above it: no text color reaches 4.5:1 on both
  const apart = Object.assign({}, COLORS, { bgPrimary: '#6a6a6a', bgCard: '#8a8a8a' });
  const fix = C.fixIt(Object.assign(C.startState('nebula'), { colors: apart }));
  assert.equal(fix.ok, false);
  assert.equal(fix.farApart, true);
  assert.deepEqual(fix.highlight, ['bgPrimary', 'bgCard']);
  assert.equal(fix.message, "Text can't be readable on both your Background and Cards " + EM_DASH + ' make them closer');
});

test('fixItOnAnAlreadyReadableThemeChangesNothing', () => {
  const s = C.startState('aurora');
  const fix = C.fixIt(s);
  assert.equal(fix.ok, true);
  assert.deepEqual(fix.state.colors, s.colors);
});

test('theWarningsAreInPlainWords', () => {
  assert.equal(C.TEXT.ACCENT_HARD_TO_SEE, 'Your main color is hard to see on this background');
  assert.equal(C.TEXT.READABLE, 'Readable ' + String.fromCharCode(0x2713));
  assert.match(C.TEXT.NOT_READABLE, /hard to read/);
});

// ---- suggested main colors

test('thereAreSixteenSuggestedMainColorsAndEachCanBeSeenOnTheBackground', () => {
  const pages = ['#0b0d14', '#000000', '#12100e', '#14100d', '#1a0f28', '#f4f2ef', '#ffffff', '#fff6e6', '#e8f0ff', '#2b2b2b', '#cfcfcf', '#808080'];
  for (const page of pages) {
    const swatches = C.accentSuggestions(page);
    assert.equal(swatches.length, 16, page);
    assert.equal(new Set(swatches).size, 16, page + ' all different');
    for (const color of swatches) {
      assert.match(color, /^#[0-9a-f]{6}$/, page);
      assert.ok(P.contrast(color, page) >= 3, page + ' ' + color + ' ' + P.contrast(color, page));
    }
  }
  assert.equal(C.ACCENT_NAMES.length, 16);
  assert.equal(new Set(C.ACCENT_NAMES).size, 16);
});

test('suggestedMainColorsKeepTheirHuesOnDarkAndLightPages', () => {
  const dark = C.accentSuggestions('#0b0d14');
  const light = C.accentSuggestions('#f4f2ef');
  assert.notDeepEqual(dark, light, 'they are adjusted to the page');
  // the first is red and the last is rose on either page: red channel leads
  const parse = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  for (const set of [dark, light]) {
    const red = parse(set[0]);
    assert.ok(red[0] > red[1] && red[0] > red[2], set[0]);
    const blue = parse(set[10]);
    assert.ok(blue[2] > blue[0], set[10]);
  }
});

test('anAccentThatFailsGetsAClearerSuggestionNearby', () => {
  const page = '#0b0d14';
  const hard = '#1a1f30';
  const better = C.clearerAccent(hard, page);
  assert.ok(P.contrast(better, page) >= 3, better);
  assert.equal(C.clearerAccent('#7c5cff', page), '#7c5cff', 'a fine color is left alone');
  // same hue family: the green channel dominates
  const green = C.clearerAccent('#0a2a14', page);
  const rgb = [1, 3, 5].map((i) => parseInt(green.slice(i, i + 2), 16));
  assert.ok(rgb[1] > rgb[0] && rgb[1] > rgb[2], green);
});

// ---- the values that are saved

const SHARED_KEYS = ['base', 'colors', 'decorationsOff', 'font', 'name', 'radiusScale', 'visibility'];

test('theValuesAreExactlyWhatTheServerTakes', () => {
  const s = Object.assign(C.startState('nebula'), { name: '  Neon Nights  ' });
  const v = C.buildValues(s);
  assert.deepEqual(Object.keys(v).sort(), SHARED_KEYS.concat('mini').sort(), 'a new theme carries its mini-player version');
  assert.deepEqual(Object.keys(C.buildValues(C.skipMini(s))).sort(), SHARED_KEYS, 'unless it was skipped');
  assert.equal(v.name, 'Neon Nights');
  assert.equal(v.visibility, 'public');
  assert.equal(v.base, 'nebula');
  assert.deepEqual(Object.keys(v.colors), javaList('COLOR_SLOTS'));
  assert.deepEqual(v.decorationsOff, []);
  assert.equal(v.radiusScale, 100);
  assert.equal(v.font, 'Inter');
  assert.equal(JSON.stringify(v).includes('"id"'), false);
  assert.equal(JSON.stringify(v).includes('"owner"'), false);
  assert.equal(JSON.stringify(v).includes('"version"'), false);
  assert.equal(JSON.stringify(v).includes('"image"'), false);
  assert.equal(JSON.stringify(v).includes('"url"'), false);
});

test('everyColorIsLowerCaseAndInTheServersFormat', () => {
  const s = C.fromTheme(savedTheme({ colors: Object.assign({}, COLORS, { accent: '#7C5CFF', glass: '#10131CE0' }) }));
  const v = C.buildValues(s);
  for (const slot of P.SLOTS) assert.match(v.colors[slot], /^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/, slot);
  assert.equal(v.colors.accent, '#7c5cff');
});

test('theColorsOfTheValuesAreACopyNotTheStatesOwn', () => {
  const s = C.startState('nebula');
  const v = C.buildValues(s);
  v.colors.accent = '#000000';
  assert.equal(s.colors.accent, C.BASE_PALETTES.nebula.colors.accent);
});

test('theValuesCarryThePiecesOffAndSixteenColorsAndNoEffects', () => {
  const s = C.setDecoExtra(C.setPiece(C.useColoursFrom(C.startState('revival'), 'nebula'), 'rays', false, PIECES), '#123abc');
  const v = C.buildValues(s);
  assert.equal('effects' in v, false, 'the old switch is never sent');
  assert.deepEqual(v.decorationsOff, ['rays']);
  assert.notEqual(v.decorationsOff, s.decorationsOff, 'a copy, not the state\'s own');
  assert.deepEqual(Object.keys(v.colors), javaList('COLOR_SLOTS'));
  assert.equal(v.colors.decoExtra, '#123abc');
  assert.deepEqual(Object.keys(v.mini.colors), javaList('COLOR_SLOTS').filter((slot) => slot !== 'glass'));
  assert.equal(v.mini.colors.decoExtra, '#123abc');
  // a picture never changes the list that is saved, on any starting theme
  for (const base of ThemeApply.BUILT_IN) {
    const off = C.allPieces(C.startState(base), false, PIECES);
    assert.deepEqual(C.buildValues(C.withNewPicture(off, 30)).decorationsOff, idsOf(base), base);
    assert.deepEqual(C.buildValues(C.withNewPicture(C.startState(base), 30)).decorationsOff, [], base);
  }
  // nothing in the creator speaks of effects any more
  assert.equal(JSON.stringify(C.previewTheme(s, '')).includes('effects'), false);
  assert.equal(JSON.stringify(s).includes('effects'), false);
});

test('theBackgroundIsSentOnlyWhenThereIsAPicture', () => {
  const none = C.buildValues(C.startState('nebula'));
  assert.equal('background' in none, false);
  const withNew = C.buildValues(Object.assign(C.startState('nebula'), { picture: 'new', dim: 33, blur: 7, fit: 'center' }));
  assert.deepEqual(withNew.background, { dim: 33, blur: 7, fit: 'center' });
  const saved = C.buildValues(C.fromTheme(savedTheme()));
  assert.deepEqual(saved.background, { dim: 45, blur: 4, fit: 'contain' });
  assert.equal(JSON.stringify(saved).includes('theme-images'), false);
});

test('backgroundNumbersAreWholeAndInRange', () => {
  const v = C.buildValues(Object.assign(C.startState('nebula'), { picture: 'new', dim: 120.6, blur: -3, fit: 'stretch', radiusScale: 100.4 }));
  assert.deepEqual(v.background, { dim: 90, blur: 0, fit: 'cover' });
  assert.equal(v.radiusScale, 100);
});

test('theMiniPlayerVersionIsSentOnlyWhenOneIsSet', () => {
  assert.equal('mini' in C.buildValues(C.skipMini(C.startState('nebula'))), false);
  const mini = { colors: P.mini(COLORS), useImage: true, dim: 55 };
  const withPicture = C.buildValues(Object.assign(C.fromTheme(savedTheme()), { mini }));
  assert.deepEqual(withPicture.mini, mini);
  assert.deepEqual(Object.keys(withPicture.mini.colors), javaList('COLOR_SLOTS').filter((slot) => slot !== 'glass'));
  // no picture, no picture in the mini-player
  const without = C.buildValues(Object.assign(C.startState('nebula'), { mini }));
  assert.equal(without.mini.useImage, false);
});

test('removingThePictureDropsTheBackgroundAndTheMiniPicture', () => {
  const s = Object.assign(C.fromTheme(savedTheme()), { picture: 'none' });
  const v = C.buildValues(s);
  assert.equal('background' in v, false);
  assert.equal(v.mini.useImage, false);
});

test('aNameWithPaddingIsTrimmedAndAnOddVisibilityIsPublic', () => {
  assert.equal(C.buildValues(Object.assign(C.startState('nebula'), { name: '\t Hi \n' })).name, 'Hi');
  assert.equal(C.buildValues(Object.assign(C.startState('nebula'), { visibility: 'private' })).visibility, 'private');
  assert.equal(C.buildValues(Object.assign(C.startState('nebula'), { visibility: 'whatever' })).visibility, 'public');
});

// ---- saving

test('aNewThemeIsPostedToTheCollectionWithoutAVersion', () => {
  const req = C.saveRequest(Object.assign(C.startState('ember'), { name: 'Mine', picture: 'new' }));
  assert.equal(req.editing, false);
  assert.equal(req.path, '/api/themes');
  assert.equal(req.version, null);
  assert.equal(req.removeBackground, false);
  assert.equal(req.sendsPicture, true);
  assert.equal(req.values.name, 'Mine');
});

test('anEditIsPostedToTheThemeWithItsVersion', () => {
  const req = C.saveRequest(C.fromTheme(savedTheme()));
  assert.equal(req.editing, true);
  assert.equal(req.path, '/api/themes/k3j9x0q2m7ab');
  assert.equal(req.version, 3);
  assert.equal(req.removeBackground, false);
  assert.equal(req.sendsPicture, false, 'the picture already on the server stays');
  assert.deepEqual(req.values.background, { dim: 45, blur: 4, fit: 'contain' });
});

test('removingTheOldPictureAsksTheServerToRemoveIt', () => {
  const req = C.saveRequest(Object.assign(C.fromTheme(savedTheme()), { picture: 'none' }));
  assert.equal(req.removeBackground, true);
  assert.equal(req.sendsPicture, false);
  assert.equal('background' in req.values, false);
});

test('replacingThePictureSendsTheNewOneAndDoesNotAskToRemove', () => {
  const req = C.saveRequest(Object.assign(C.fromTheme(savedTheme()), { picture: 'new' }));
  assert.equal(req.removeBackground, false);
  assert.equal(req.sendsPicture, true);
});

test('anEditWithNoPictureBeforeOrAfterNeedsNothingRemoved', () => {
  const req = C.saveRequest(C.fromTheme(savedTheme({ background: null })));
  assert.equal(req.removeBackground, false);
});

test('theThemeIdInThePathIsEncoded', () => {
  const req = C.saveRequest(Object.assign(C.fromTheme(savedTheme()), { id: 'a/../b' }));
  assert.equal(req.path, '/api/themes/a%2F..%2Fb');
});

test('savingAsANewThemeDropsTheIdentityAndAPictureThatWentWithTheOldOne', () => {
  const gone = C.asNewTheme(C.fromTheme(savedTheme({ hiddenByOwner: true, visibility: 'private' })));
  assert.equal(gone.id, null);
  assert.equal(gone.version, null);
  assert.equal(gone.hiddenByOwner, false);
  assert.equal(gone.picture, 'none');
  assert.equal(gone.hadPicture, false);
  assert.equal(gone.savedPictureUrl, '');
  assert.equal(gone.name, 'Midnight Drive');
  assert.deepEqual(gone.colors, COLORS);
  const req = C.saveRequest(gone);
  assert.equal(req.editing, false);
  assert.equal(req.removeBackground, false);
  assert.equal('background' in req.values, false);
  // a picture the person chose again is kept
  const again = C.asNewTheme(Object.assign(C.fromTheme(savedTheme()), { picture: 'new' }));
  assert.equal(again.picture, 'new');
  assert.equal(C.saveRequest(again).sendsPicture, true);
});

// ---- what the live page is told

test('thePreviewIsATheThemeThePanelCanApply', () => {
  const s = Object.assign(C.startState('ember'), { name: '' });
  const t = C.previewTheme(s, '');
  assert.equal(ThemeApply.usable(t), true);
  assert.equal(t.id, C.PREVIEW_ID);
  assert.match(t.id, /^[a-z0-9]{12}$/);
  assert.equal(t.name, 'Your new theme');
  assert.equal(t.base, 'ember');
  assert.deepEqual(t.colors, C.BASE_PALETTES.ember.colors);
  assert.deepEqual(t.decorationsOff, []);
  assert.equal('effects' in t, false);
  assert.equal(t.background, null);
  assert.equal(C.previewTheme(Object.assign(s, { name: 'Hi' }), '').name, 'Hi');
  // the pieces that are off reach the page the way a saved theme's do
  const off = C.setPiece(C.startState('revival'), 'rays', false, PIECES);
  assert.equal(ThemeApply.decoOffFor(C.previewTheme(off, ''), false, PIECES), 'rays');
});

test('aPictureChosenHereIsPreviewedFromItsLocalLinkAndASavedOneFromItsOwn', () => {
  const local = ThemeApply.localPicture(BLOB, false);
  const fresh = C.previewTheme(Object.assign(C.startState('nebula'), { picture: 'new', dim: 20, blur: 3, fit: 'center' }), BLOB);
  assert.deepEqual(fresh.background, { url: BLOB, dim: 20, blur: 3, fit: 'center' });
  assert.deepEqual(fresh.decorationsOff, [], 'the picture is not in the saved list; the page adds the background pieces');
  assert.ok(ThemeApply.backdrop(fresh.background, local));
  assert.equal(ThemeApply.backdrop(fresh.background), null, 'the page only takes that link from the creator');
  const old = C.previewTheme(C.fromTheme(savedTheme()), BLOB);
  assert.equal(old.background.url, PICTURE, 'a saved picture is shown from the server');
  const replaced = C.previewTheme(Object.assign(C.fromTheme(savedTheme()), { picture: 'new' }), BLOB);
  assert.equal(replaced.background.url, BLOB);
  const removed = C.previewTheme(Object.assign(C.fromTheme(savedTheme()), { picture: 'none' }), BLOB);
  assert.equal(removed.background, null);
});

test('aNewPictureWithNoLinkYetShowsNoBackground', () => {
  const t = C.previewTheme(Object.assign(C.startState('nebula'), { picture: 'new' }), '');
  assert.equal(t.background, null);
});

// ---- the picture

test('aNewPictureStartsAtTheSuggestedDarkeningAndKeepsBlurAndFit', () => {
  const s = C.setPictureLook(C.startState('nebula'), { blur: 6, fit: 'contain' });
  const picked = C.withNewPicture(s, 62);
  assert.equal(picked.picture, 'new');
  assert.equal(picked.dim, 62);
  assert.equal(picked.blur, 6);
  assert.equal(picked.fit, 'contain');
  assert.equal(C.withNewPicture(s, NaN).dim, s.dim, 'an unmeasured picture keeps the darkening it had');
  assert.equal(C.withNewPicture(s, 500).dim, 90);
  assert.equal(s.picture, 'none', 'the state it was given is not changed');
});

test('removingThePictureKeepsItsSettingsForAnotherOne', () => {
  const s = C.withoutPicture(C.setPictureLook(C.fromTheme(savedTheme()), { dim: 20 }));
  assert.equal(s.picture, 'none');
  assert.equal(s.dim, 20);
  assert.equal(s.hadPicture, true, 'the server still has one until a save removes it');
});

test('theDarkeningBlurAndFitStayInTheirRanges', () => {
  const s = C.startState('nebula');
  assert.equal(C.setPictureLook(s, { dim: 70 }).dim, 70);
  assert.equal(C.setPictureLook(s, { dim: 120 }).dim, 90);
  assert.equal(C.setPictureLook(s, { dim: -1 }).dim, 0);
  assert.equal(C.setPictureLook(s, { blur: 30 }).blur, 20);
  assert.equal(C.setPictureLook(s, { blur: '8' }).blur, 8);
  assert.equal(C.setPictureLook(s, { dim: 'lots' }).dim, s.dim);
  assert.equal(C.setPictureLook(s, { fit: 'center' }).fit, 'center');
  assert.equal(C.setPictureLook(s, { fit: 'stretch' }).fit, s.fit);
  assert.equal(C.setPictureLook(s, { dim: 33 }).blur, s.blur, 'only what is given changes');
  assert.equal(C.setPictureLook(s, null).dim, s.dim);
});

test('averageBrightnessIsTheMeanOfTheEncodedChannelValues', () => {
  const px = (r, g, b, a) => [r, g, b, a === undefined ? 255 : a];
  assert.equal(C.averageBrightness(Uint8ClampedArray.from([].concat(px(0, 0, 0), px(0, 0, 0)))), 0);
  assert.equal(C.averageBrightness(Uint8ClampedArray.from([].concat(px(255, 255, 255), px(255, 255, 255)))), 1);
  // mid grey is 0.5 as encoded; as linear light it would be about 0.21
  assert.ok(Math.abs(C.averageBrightness(Uint8ClampedArray.from(px(128, 128, 128))) - 128 / 255) < 1e-12);
  // one black pixel and one white pixel
  assert.equal(C.averageBrightness(Uint8ClampedArray.from([].concat(px(0, 0, 0), px(255, 255, 255)))), 0.5);
  // each channel counts the same
  assert.ok(Math.abs(C.averageBrightness(Uint8ClampedArray.from(px(255, 0, 0))) - 1 / 3) < 1e-12);
  assert.ok(Math.abs(C.averageBrightness(Uint8ClampedArray.from(px(0, 255, 0))) - 1 / 3) < 1e-12);
  // alpha is not part of it (the picture is drawn over the page color first)
  assert.equal(C.averageBrightness(Uint8ClampedArray.from(px(255, 255, 255, 0))), 1);
});

test('averageBrightnessOfNothingIsNotANumber', () => {
  assert.ok(Number.isNaN(C.averageBrightness(new Uint8ClampedArray(0))));
  assert.ok(Number.isNaN(C.averageBrightness(null)));
  assert.ok(Number.isNaN(C.averageBrightness(undefined)));
  assert.ok(Number.isNaN(C.averageBrightness(Uint8ClampedArray.from([1, 2, 3]))), 'less than one whole pixel');
});

test('theDarkenSuggestionComesFromTheMeasuredBrightness', () => {
  const white = Uint8ClampedArray.from([255, 255, 255, 255]);
  const black = Uint8ClampedArray.from([0, 0, 0, 255]);
  assert.equal(C.suggestedDim(white), P.suggestDim(1));
  assert.equal(C.suggestedDim(black), P.suggestDim(0));
  assert.equal(C.suggestedDim(new Uint8ClampedArray(0)), P.suggestDim(NaN), 'unmeasurable gives the middling default');
  assert.ok(C.suggestedDim(white) > C.suggestedDim(Uint8ClampedArray.from([128, 128, 128, 255])));
});

test('thePictureTypeIsRecognizedByItsFirstBytes', () => {
  const ascii = (s) => Array.from(s).map((c) => c.charCodeAt(0));
  const bytes = (...b) => Uint8Array.from(b);
  assert.equal(C.sniffPicture(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0x10)), 'jpg');
  assert.equal(C.sniffPicture(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)), 'png');
  assert.equal(C.sniffPicture(Uint8Array.from(ascii('GIF87a....'))), 'gif');
  assert.equal(C.sniffPicture(Uint8Array.from(ascii('GIF89a....'))), 'gif');
  assert.equal(C.sniffPicture(Uint8Array.from([...ascii('RIFF'), 1, 2, 3, 4, ...ascii('WEBPVP8 ')])), 'webp');
  assert.equal(C.sniffPicture(Uint8Array.from(ascii('<svg xmlns="http://www.w3.org/2000/svg"/>'))), null);
  assert.equal(C.sniffPicture(Uint8Array.from(ascii('<!doctype html>'))), null);
  assert.equal(C.sniffPicture(bytes(0x4d, 0x5a, 0x90, 0x00, 0x03, 0x00)), null);
  assert.equal(C.sniffPicture(Uint8Array.from([...ascii('RIFF'), 1, 2, 3, 4, ...ascii('WAVEfmt ')])), null);
  assert.equal(C.sniffPicture(Uint8Array.from(ascii('GIF90a'))), null);
  assert.equal(C.sniffPicture(bytes(0xff, 0xd8)), null, 'too short');
  assert.equal(C.sniffPicture(new Uint8Array(0)), null);
  assert.equal(C.sniffPicture(null), null);
  assert.equal(C.sniffPicture(undefined), null);
});

test('onlyJpegCannotMove', () => {
  assert.equal(C.canMove('jpg'), false);
  for (const kind of ['gif', 'webp', 'png']) assert.equal(C.canMove(kind), true, kind);
  assert.equal(C.canMove(null), false);
});

test('aPictureThatIsTooBigOrNotAPictureIsRefusedInPlainWords', () => {
  const MB = 1024 * 1024;
  assert.equal(C.pictureProblem('jpg', 8 * MB), '');
  assert.equal(C.pictureProblem('gif', 1), '');
  assert.equal(C.pictureProblem('png', 8 * MB + 1), 'Pictures can be up to 8 MB');
  assert.equal(C.pictureProblem(null, 1000), "That file isn't a picture we can use (JPG, PNG, WebP or GIF)");
  assert.equal(C.pictureProblem(null, 9 * MB), "That file isn't a picture we can use (JPG, PNG, WebP or GIF)");
  assert.equal(C.pictureProblem('jpg', 0), "That file isn't a picture we can use (JPG, PNG, WebP or GIF)");
  assert.equal(C.pictureProblem('jpg', NaN), "That file isn't a picture we can use (JPG, PNG, WebP or GIF)");
});

// ---- the name

test('aNameNeedsOneToFortyVisibleCharacters', () => {
  assert.equal(C.nameProblem('Midnight Drive'), '');
  assert.equal(C.nameProblem('x'), '');
  assert.equal(C.nameProblem('a'.repeat(40)), '');
  assert.equal(C.nameProblem('a'.repeat(41)), 'Names can be up to 40 characters');
  assert.equal(C.nameProblem(''), 'Give your theme a name');
  assert.equal(C.nameProblem('    '), 'Give your theme a name');
  assert.equal(C.nameProblem(null), 'Give your theme a name');
  assert.equal(C.nameProblem(undefined), 'Give your theme a name');
});

test('namesAreCountedInCharactersNotCodeUnits', () => {
  const smile = String.fromCodePoint(0x1f600);
  assert.equal(C.nameProblem(smile.repeat(40)), '');
  assert.equal(C.nameProblem(smile.repeat(41)), 'Names can be up to 40 characters');
});

test('aNameWithControlCharactersIsRefused', () => {
  assert.equal(C.nameProblem('a' + String.fromCharCode(7) + 'b'), "Names can't have control characters");
  assert.equal(C.nameProblem('a' + String.fromCharCode(0x85) + 'b'), "Names can't have control characters");
  assert.equal(C.nameProblem('line\nbreak'), "Names can't have control characters");
  assert.equal(C.nameProblem('tabs\tinside'), "Names can't have control characters");
});

// ---- when saving does not work

test('theLimitOnACreateExplainsAndPointsToMyThemes', () => {
  const f = C.classifyFailure({ editing: false }, 409, 'You can have up to 10 themes', 10);
  assert.equal(f.type, 'limit');
  assert.equal(f.text, "You already have 10 themes, the most you can have. Delete one you don't use, then save this one.");
  assert.equal(f.step, C.STEP.SHARE);
  assert.equal(C.classifyFailure({ editing: false }, 409, '', undefined).type, 'limit');
  assert.match(C.classifyFailure({ editing: false }, 409, '', undefined).text, /^You already have 10 themes/);
});

test('aChangedThemeOffersToReloadItIncludingWhenTheBotOwnerHidItMeanwhile', () => {
  const f = C.classifyFailure({ editing: true }, 409, 'This theme changed ' + EM_DASH + ' reload it');
  assert.equal(f.type, 'changed');
  assert.equal(f.text, 'This theme changed ' + EM_DASH + ' reload it?');
  assert.equal(f.step, C.STEP.SHARE);
});

test('aThemeThatIsGoneOffersToSaveAsANewOne', () => {
  const plain = C.classifyFailure({ editing: true, hadPicture: false }, 404, 'Theme not found');
  assert.equal(plain.type, 'gone');
  assert.match(plain.text, /deleted/);
  assert.match(plain.text, /new theme/);
  assert.doesNotMatch(plain.text, /picture/);
  const withPicture = C.classifyFailure({ editing: true, hadPicture: true, sendsPicture: false }, 404, 'Theme not found');
  assert.match(withPicture.text, /picture went with it/);
  const chosenAgain = C.classifyFailure({ editing: true, hadPicture: true, sendsPicture: true }, 404, 'Theme not found');
  assert.doesNotMatch(chosenAgain.text, /went with it/, 'the picture on this computer is still there');
});

test('refusedValuesShowTheServersMessageOnTheStepItBelongsTo', () => {
  const rows = [
    [400, 'Name must be 1 to 40 characters', C.STEP.SHARE],
    [400, "Name can't contain control characters", C.STEP.SHARE],
    [400, 'Visibility must be public or private', C.STEP.SHARE],
    [403, 'Hidden by the bot owner', C.STEP.SHARE],
    [400, 'Background darken must be a whole number from 0 to 90', C.STEP.PICTURE],
    [400, 'Background blur must be a whole number from 0 to 20', C.STEP.PICTURE],
    [400, 'Background fit must be cover, contain or center', C.STEP.PICTURE],
    [400, 'Choose a picture or remove the background', C.STEP.PICTURE],
    [400, "That file isn't a picture we can use (JPG, PNG, WebP or GIF)", C.STEP.PICTURE],
    [413, 'Pictures can be up to 8 MB', C.STEP.PICTURE],
    [400, 'Mini-player darken must be a whole number from 0 to 90', C.STEP.MINI],
    [400, 'Mini-player main color must be a color like #7c5cff', C.STEP.MINI],
    [400, 'Corners must be a whole number from 0 to 200', C.STEP.TWEAK],
    [400, 'Font must be one of the fonts offered', C.STEP.TWEAK],
    [400, 'Decorations must be a list of piece names', C.STEP.TWEAK],
    [400, 'Extra decoration color must be a color like #7c5cff', C.STEP.TWEAK],
    [400, 'Mini-player extra decoration color must be a color like #7c5cff', C.STEP.MINI],
    [400, 'Cards must be a color like #7c5cff', C.STEP.TWEAK],
    [400, 'Starting theme must be one of the built-in themes', C.STEP.START]
  ];
  for (const [status, message, step] of rows) {
    const f = C.classifyFailure({ editing: false }, status, message);
    assert.equal(f.type, 'invalid', message);
    assert.equal(f.step, step, message);
    assert.equal(f.text, message, message);
  }
});

test('aMessageOfAnUnknownKindLandsOnTheLastStepAndTheStatusSuppliesWordsWhenThereAreNone', () => {
  assert.equal(C.classifyFailure({ editing: false }, 500, "Couldn't save " + EM_DASH + ' try again').step, C.STEP.SHARE);
  assert.equal(C.classifyFailure({ editing: false }, 500, '').text, "Couldn't save " + EM_DASH + ' try again');
  assert.equal(C.classifyFailure({ editing: false }, 413, '').text, 'Pictures can be up to 8 MB');
  assert.equal(C.classifyFailure({ editing: false }, 413, '').step, C.STEP.PICTURE);
  assert.equal(C.classifyFailure({ editing: false }, 400, undefined).text, "That didn't work " + EM_DASH + ' check your choices and try again');
  assert.equal(C.classifyFailure({ editing: false }, 403, null).type, 'invalid');
});

test('aHugeOrMultilineErrorBodyIsNotShownAsItIs', () => {
  const html = '<html><body>' + 'x'.repeat(5000) + '</body></html>';
  const f = C.classifyFailure({ editing: false }, 413, html);
  assert.equal(f.text, 'Pictures can be up to 8 MB');
  const lines = C.classifyFailure({ editing: false }, 400, 'first\nsecond');
  assert.equal(lines.text, "That didn't work " + EM_DASH + ' check your choices and try again');
  const trimmed = C.classifyFailure({ editing: false }, 400, '   Font must be one of the fonts offered  ');
  assert.equal(trimmed.text, 'Font must be one of the fonts offered');
  assert.equal(C.classifyFailure({ editing: false }, 400, 12).text, "That didn't work " + EM_DASH + ' check your choices and try again');
});

// ---- step 5: the mini-player version

const DESKTOP_CORE = require('../../../desktop/bridge/core.js');

/* A theme with a picture chosen here (the file itself is the page's business). */
function withPicture(state, dim) {
  return C.withNewPicture(state, dim === undefined ? 35 : dim);
}

test('aThemeWithoutAMiniPlayerVersionSendsNone', () => {
  const s = C.skipMini(C.startState('nebula'));
  assert.equal(s.mini, null);
  assert.equal('mini' in C.buildValues(s), false);
  assert.deepEqual(C.miniView(s), { made: false });
});

test('theStepSaysWhatToDoInPlainWords', () => {
  assert.equal(C.TEXT.MINI_SKIP, 'Skip ' + EM_DASH + ' no mini-player version');
  assert.equal(C.TEXT.MINI_MAKE, 'Make a mini-player version');
  assert.equal(C.TEXT.MINI_NOT_READABLE, 'Some text will be hard to read in the mini-player');
  assert.equal(C.TEXT.MINI_MATCH, 'Update to match the theme');
});

test('makingAMiniPlayerVersionGeneratesItFromTheThemesColorsWithoutGlass', () => {
  const before = C.skipMini(C.startState('ember'));
  const s = C.makeMini(before);
  assert.deepEqual(s.mini.colors, P.mini(before.colors));
  assert.deepEqual(Object.keys(s.mini.colors), P.MINI_SLOTS.slice());
  assert.equal(Object.keys(s.mini.colors).length, 15, 'the 16 without glass');
  assert.equal('glass' in s.mini.colors, false);
  assert.equal(before.mini, null, 'the state it was given is left alone');
  assert.equal(C.miniFollows(s), true);
  // making one when there is one changes nothing
  assert.equal(C.makeMini(s), s);
});

test('aMiniPlayerVersionOfAThemeWithoutAPictureNeverUsesOne', () => {
  const s = C.makeMini(C.startState('nebula'));
  assert.equal(s.mini.useImage, false);
  assert.equal(C.setMiniPicture(s, true).mini.useImage, false);
  assert.equal(C.buildValues(C.setMiniPicture(s, true)).mini.useImage, false);
  const view = C.miniView(s);
  assert.equal(view.hasPicture, false);
  assert.equal(view.useImage, false);
});

test('aThemeWithAPictureStartsItsMiniPlayerVersionOnThePictureAtTheSameDarkening', () => {
  const s = C.makeMini(withPicture(C.startState('nebula'), 62));
  assert.equal(s.mini.useImage, true);
  assert.equal(s.mini.dim, 62);
  assert.equal(C.makeMini(C.skipMini(C.startState('nebula'))).mini.dim, 40, 'without a picture, the usual start');
  const late = C.makeMini(withPicture(C.skipMini(C.startState('nebula')), 62));
  assert.equal(late.mini.useImage, true, 'made after the picture: on it from the start');
  assert.equal(late.mini.dim, 62);
  const view = C.miniView(s);
  assert.equal(view.hasPicture, true);
  assert.equal(view.useImage, true);
  assert.equal(view.dim, 62);
  // the switch can be turned off and on again
  const off = C.setMiniPicture(s, false);
  assert.equal(off.mini.useImage, false);
  assert.equal(C.setMiniPicture(off, true).mini.useImage, true);
  assert.equal(s.mini.useImage, true, 'the state it was given is left alone');
});

test('aSavedPictureCountsAsAPictureForTheMiniPlayer', () => {
  const s = C.setMiniPicture(C.fromTheme(savedTheme({ mini: { colors: P.mini(COLORS), useImage: false, dim: 20 } })), true);
  assert.equal(s.mini.useImage, true);
  assert.equal(C.miniView(s).hasPicture, true);
  assert.equal(C.buildValues(s).mini.useImage, true);
});

test('takingThePictureAwayTakesItAwayFromTheMiniPlayerToo', () => {
  const s = C.withoutPicture(C.makeMini(withPicture(C.startState('nebula'))));
  assert.equal(s.mini.useImage, false);
  assert.equal(C.miniView(s).hasPicture, false);
  assert.equal(C.buildValues(s).mini.useImage, false);
  assert.equal('background' in C.buildValues(s), false);
  // a picture that comes after takes the place of the one that went (see aMiniPlayerVersionMadeBeforeThePictureTakesItUp)
  assert.equal(withPicture(s).mini.useImage, true);
});

test('theMiniPlayerPictureIsDarkenedFromZeroToNinetyInWholeNumbers', () => {
  const s = C.makeMini(withPicture(C.startState('nebula')));
  assert.equal(C.setMiniDim(s, 0).mini.dim, 0);
  assert.equal(C.setMiniDim(s, 90).mini.dim, 90);
  assert.equal(C.setMiniDim(s, 120).mini.dim, 90);
  assert.equal(C.setMiniDim(s, -5).mini.dim, 0);
  assert.equal(C.setMiniDim(s, 33.6).mini.dim, 34);
  assert.equal(C.setMiniDim(s, '45').mini.dim, 45);
  assert.equal(C.setMiniDim(s, 'x').mini.dim, s.mini.dim, 'not a number: as it was');
  assert.equal(C.setMiniDim(C.skipMini(C.startState('nebula')), 10).mini, null, 'no mini-player version, nothing to set');
  const odd = C.buildValues(Object.assign(withPicture(C.startState('nebula')), {
    mini: { colors: P.mini(C.startState('nebula').colors), useImage: true, dim: 140.4 }
  }));
  assert.equal(odd.mini.dim, 90);
});

test('theMiniPlayerVersionIsSavedAsFifteenColorsAFlagAndADarkening', () => {
  const s = C.makeMini(withPicture(C.startState('aurora'), 55));
  const v = C.buildValues(s);
  assert.deepEqual(Object.keys(v.mini).sort(), ['colors', 'dim', 'useImage']);
  assert.deepEqual(Object.keys(v.mini.colors), javaList('COLOR_SLOTS').filter((slot) => slot !== 'glass'));
  assert.equal(v.mini.useImage, true);
  assert.equal(v.mini.dim, 55);
  assert.deepEqual(v.mini.colors, P.mini(s.colors));
  assert.notEqual(v.mini.colors, s.mini.colors, 'a copy');
  assert.equal(C.saveRequest(s).values.mini.dim, 55);
});

test('skippingTheMiniPlayerVersionSendsNoneAndTheThemeIsNotOfferedThere', () => {
  const made = C.makeMini(C.startState('nebula'));
  const skipped = C.skipMini(made);
  assert.equal(skipped.mini, null);
  assert.equal('mini' in C.buildValues(skipped), false);
  assert.deepEqual(C.miniView(skipped), { made: false });
  assert.ok(made.mini, 'the state it was given is left alone');
  assert.equal(C.skipMini(skipped), skipped);
  // the saved theme that had one loses it
  assert.equal('mini' in C.buildValues(C.skipMini(C.fromTheme(savedTheme()))), false);
});

test('aGeneratedMiniPlayerVersionFollowsTheThemeWhenItsColorsChange', () => {
  let s = C.makeMini(C.startState('nebula'));
  for (const step of [
    (x) => C.setMain(x, '#22c55e'),
    (x) => C.setPage(x, '#201020'),
    (x) => C.setMode(x, 'light'),
    (x) => C.surpriseMe(x, seeded(7)),
    (x) => C.tweakColor(x, 'textSecondary', '#8899aa'),
    (x) => C.tweakColor(x, 'bgCard', '#223344'),
    (x) => C.fixIt(x).state,
    (x) => C.chooseBase(x, 'dawn'),
    (x) => C.chooseTheme(x, savedTheme({ mine: false })),
    (x) => C.useColoursFrom(x, 'rasta'),
    (x) => C.setDecoExtra(x, '#00ff88'),
    (x) => C.setPiece(x, 'flames', false, PIECES),
    (x) => C.setFont(C.setCorners(x, 50), 'Syne')
  ]) {
    s = step(s);
    assert.deepEqual(s.mini.colors, P.mini(s.colors));
    assert.equal(C.miniFollows(s), true);
    assert.equal('glass' in s.mini.colors, false);
  }
});

test('theEarlierStateOfAFollowingMiniPlayerVersionIsNotTouchedWhenTheThemeMoves', () => {
  const made = C.makeMini(C.startState('nebula'));
  const moved = C.setMain(made, '#22c55e');
  assert.notDeepEqual(moved.mini.colors, made.mini.colors);
  assert.deepEqual(made.mini.colors, P.mini(C.startState('nebula').colors));
});

test('aMiniPlayerVersionWithItsOwnColorsKeepsThemWhenTheThemeChanges', () => {
  let s = C.setMiniMain(C.makeMini(C.startState('nebula')), '#ff7a00');
  assert.equal(C.miniFollows(s), false);
  const own = s.mini.colors;
  s = C.setMain(s, '#22c55e');
  s = C.setPage(s, '#102030');
  s = C.chooseBase(s, 'ember');
  s = C.tweakColor(s, 'accent', '#123456');
  assert.deepEqual(s.mini.colors, own);
  assert.equal(C.miniFollows(s), false);
  assert.equal(C.miniView(s).follows, false);
});

test('updateToMatchTheThemeBringsTheOwnColorsBackToTheThemesAndFollowingAgain', () => {
  let s = C.setMiniPage(C.makeMini(C.startState('nebula')), '#101828');
  s = C.setMain(s, '#22c55e');
  assert.equal(C.miniView(s).follows, false);
  const matched = C.matchMini(s);
  assert.deepEqual(matched.mini.colors, P.mini(s.colors));
  assert.equal(C.miniFollows(matched), true);
  assert.equal(C.miniView(matched).follows, true);
  assert.equal(matched.mini.useImage, s.mini.useImage);
  assert.equal(matched.mini.dim, s.mini.dim);
  assert.equal(C.setMain(matched, '#ec4899').mini.colors.accent, '#ec4899', 'and it follows from then on');
  assert.equal(C.matchMini(C.skipMini(C.startState('nebula'))).mini, null);
});

test('editingAThemeWhoseMiniPlayerVersionWasGeneratedFromItFollowsTheNewColors', () => {
  const generated = C.fromTheme(savedTheme());
  assert.equal(C.miniFollows(generated), true);
  const s = C.setMain(generated, '#22c55e');
  assert.deepEqual(s.mini.colors, P.mini(s.colors));
  assert.equal(s.mini.colors.accent, '#22c55e');
  assert.equal(s.mini.useImage, true);
  assert.equal(s.mini.dim, 55);
  assert.equal(C.buildValues(s).mini.colors.accent, '#22c55e');
});

test('editingAThemeWhoseMiniPlayerVersionHasItsOwnColorsKeepsThemAndOffersToUpdate', () => {
  const own = Object.assign(P.mini(COLORS), { accent: '#ff7a00', accentLight: '#ff9a40' });
  const generated = C.fromTheme(savedTheme({ mini: { colors: own, useImage: false, dim: 20 } }));
  assert.equal(C.miniFollows(generated), false);
  const s = C.setMain(generated, '#22c55e');
  assert.deepEqual(s.mini.colors, own);
  assert.equal(C.miniView(s).follows, false);
  assert.deepEqual(C.matchMini(s).mini.colors, P.mini(s.colors));
});

test('aStoredMiniPlayerPictureWithNoPictureOnTheThemeIsNoPicture', () => {
  const s = C.fromTheme(savedTheme({ background: null, mini: { colors: P.mini(COLORS), useImage: true, dim: 20 } }));
  assert.equal(s.mini.useImage, false);
  assert.equal(C.buildValues(s).mini.useImage, false);
});

test('savingAsANewThemeKeepsTheMiniPlayerVersion', () => {
  const own = Object.assign(P.mini(COLORS), { accent: '#ff7a00' });
  const s = C.asNewTheme(C.fromTheme(savedTheme({ mini: { colors: own, useImage: true, dim: 55 } })));
  assert.equal(s.id, null);
  assert.deepEqual(s.mini.colors, own);
  assert.equal(C.miniFollows(s), false);
  // its picture went with the old theme, so the mini-player cannot use one
  assert.equal(C.buildValues(s).mini.useImage, false);
  assert.deepEqual(C.buildValues(s).mini.colors, own);
  assert.equal(C.asNewTheme(C.fromTheme(savedTheme())).mini.dim, 55);
});

test('theMiniPlayersOwnMainColorAndBackgroundRegenerateItsPaletteWithoutTouchingTheTheme', () => {
  const made = C.makeMini(C.startState('nebula'));
  const orange = C.setMiniMain(made, '#ff7a00');
  assert.equal(orange.mini.colors.accent, '#ff7a00');
  assert.equal(orange.mini.colors.bgPrimary, made.mini.colors.bgPrimary, 'the background stays');
  assert.deepEqual(orange.mini.colors, P.mini(P.generate('#ff7a00', made.mini.colors.bgPrimary, 'dark')));
  const light = C.setMiniPage(orange, '#f4efe6');
  assert.equal(light.mini.colors.bgPrimary, '#f4efe6');
  assert.equal(light.mini.colors.accent, '#ff7a00', 'the main color stays');
  assert.deepEqual(light.mini.colors, P.mini(P.generate('#ff7a00', '#f4efe6', 'light')));
  // nothing happens to the theme's own colors
  assert.deepEqual(light.colors, made.colors);
  assert.equal(light.mainColor, made.mainColor);
  assert.equal(light.pageColor, made.pageColor);
  const view = C.miniView(light);
  assert.equal(view.main, '#ff7a00');
  assert.equal(view.page, '#f4efe6');
  assert.equal(C.miniView(made).main, made.mainColor);
  assert.equal(C.miniView(made).page, made.pageColor);
});

test('theMiniPlayersOwnColorsAreFifteenReadableColorsForAnyPair', () => {
  for (let i = 0; i < 150; i++) {
    const pick = P.surprise(seeded(i + 1));
    let s = C.makeMini(C.startState('nebula'));
    s = C.setMiniPage(C.setMiniMain(s, pick.accent), pick.background);
    assert.equal(Object.keys(s.mini.colors).length, 15, String(i));
    assert.equal('glass' in s.mini.colors, false, String(i));
    assert.equal(C.miniView(s).report.ok, true, String(i));
    assert.equal(P.readability(s.mini.colors).ok, true, String(i));
    assert.equal(C.miniFollows(s), false);
  }
});

test('aColorThatIsNotAColorChangesNothingInTheMiniPlayer', () => {
  const s = C.makeMini(C.startState('nebula'));
  for (const bad of ['red', '#12', '#12345678', '', null, undefined, 5, '#gggggg']) {
    assert.equal(C.setMiniMain(s, bad), s, String(bad));
    assert.equal(C.setMiniPage(s, bad), s, String(bad));
  }
  const none = C.skipMini(C.startState('nebula'));
  assert.equal(C.setMiniMain(none, '#ff0000'), none);
  assert.equal(C.setMiniPage(none, '#ff0000'), none);
  assert.equal(C.fixMini(none), none);
  assert.equal(C.setMiniPicture(none, true), none);
  // upper case is fine and comes out lower case
  assert.equal(C.setMiniMain(s, '#FF7A00').mini.colors.accent, '#ff7a00');
});

test('theMiniPlayerHasTheSameReadableBadgeAsStepTwoAndTheMainColorHint', () => {
  const s = C.makeMini(C.startState('nebula'));
  assert.deepEqual(C.miniView(s).report, C.report(s.mini.colors));
  assert.equal(C.miniView(s).report.ok, true);
  assert.equal(C.miniView(s).report.accentVisible, true);
  // a main color that blends into the background
  const hidden = C.setMiniMain(s, s.mini.colors.bgPrimary);
  assert.equal(C.miniView(hidden).report.accentVisible, false);
  const clearer = C.setMiniMain(hidden, C.clearerAccent(hidden.mini.colors.accent, hidden.mini.colors.bgPrimary));
  assert.equal(C.miniView(clearer).report.accentVisible, true);
});

test('aMiniPlayerVersionThatFollowsAnUnreadableThemeIsUnreadableAndFixItMakesItReadable', () => {
  let s = C.makeMini(C.startState('nebula'));
  s = C.tweakColor(s, 'textPrimary', '#2a2d44');
  assert.equal(C.miniView(s).report.ok, false);
  assert.ok(C.miniView(s).report.problems.includes('textPrimary'));
  const fixed = C.fixMini(s);
  assert.equal(C.miniView(fixed).report.ok, true);
  assert.equal(fixed.mini.colors.accent, s.mini.colors.accent, 'only the words move');
  assert.equal(fixed.mini.colors.bgCard, s.mini.colors.bgCard);
  assert.deepEqual(fixed.colors, s.colors, 'the theme itself is not touched');
  assert.equal(C.miniFollows(fixed), false, 'the mini-player now has colors of its own');
  assert.equal(C.miniView(fixed).follows, false);
});

test('fixItInTheMiniPlayerWorksEvenWhenNoWordsCouldBeReadOnBothSurfaces', () => {
  let s = C.makeMini(C.startState('nebula'));
  s = C.tweakColor(C.tweakColor(s, 'bgPrimary', '#6a6a6a'), 'bgCard', '#8a8a8a'); // no words reach 4.5:1 on both
  assert.equal(C.miniView(s).report.ok, false);
  assert.equal(C.fixIt(s).farApart, true, 'on the panel the person must change the cards');
  const fixed = C.fixMini(s);
  assert.equal(C.miniView(fixed).report.ok, true, 'in the mini-player it starts over from the main color and background');
  assert.equal(fixed.mini.colors.accent, s.mini.colors.accent);
  assert.equal(fixed.mini.colors.bgPrimary, s.mini.colors.bgPrimary);
});

test('fixItInTheMiniPlayerAlwaysEndsReadableAndLeavesAReadableOneAlone', () => {
  const rand = seeded(99);
  const hex = () => '#' + [0, 1, 2].map(() => Math.floor(rand() * 256).toString(16).padStart(2, '0')).join('');
  for (let i = 0; i < 60; i++) {
    let s = C.makeMini(C.startState('nebula'));
    s = C.tweakColor(C.tweakColor(C.tweakColor(s, 'bgPrimary', hex()), 'bgCard', hex()), 'textPrimary', hex());
    assert.equal(C.miniView(C.fixMini(s)).report.ok, true, String(i));
  }
  const fine = C.makeMini(C.startState('nebula'));
  assert.equal(C.fixMini(fine), fine);
});

test('theMiniPlayerPreviewIsStyledByExactlyTheSixteenVariablesTheMiniPlayerTakes', () => {
  assert.deepEqual(C.MINI_VARS, DESKTOP_CORE.THEME_VARS, 'the real mini-player copies these 16 and no others');
  assert.equal(C.MINI_VARS.length, 16);
  const s = C.makeMini(C.startState('ember'));
  const vars = C.miniView(s).vars;
  assert.deepEqual(Object.keys(vars).sort(), C.MINI_VARS.slice().sort());
  const c = s.mini.colors;
  assert.equal(vars['--bg-primary'], c.bgPrimary);
  assert.equal(vars['--bg-card'], c.bgCard);
  assert.equal(vars['--accent'], c.accent);
  assert.equal(vars['--text-secondary'], c.textSecondary);
  assert.equal(vars['--border'], c.border);
  assert.equal(vars['--success'], c.success);
  assert.equal(vars['--accent-glow'], P.cssVars(c)['--accent-glow']);
  assert.equal(vars['--accent-gradient'], 'linear-gradient(135deg, ' + c.accent + ', ' + c.accent2 + ')');
  assert.equal(vars['--font-family'], ThemeApply.fontStack(s.font));
  for (const name of C.MINI_VARS) assert.equal(typeof vars[name], 'string', name);
  // not the panel's extras: no glass, no r/g/b numbers
  assert.equal('--glass' in vars, false);
  assert.equal('--accent-r' in vars, false);
});

test('theMiniPlayerPreviewWearsTheThemesFontAndItsOwnColors', () => {
  let s = C.makeMini(C.startState('nebula'));
  s = C.setFont(s, 'Fraunces');
  assert.equal(C.miniView(s).vars['--font-family'], ThemeApply.fontStack('Fraunces'));
  s = C.setMiniMain(s, '#ff7a00');
  assert.equal(C.miniView(s).vars['--accent'], '#ff7a00');
  assert.notEqual(C.miniView(s).vars['--accent'], s.colors.accent, "not the theme's main color");
});

test('everyStateTransitionLeavesTheOneItWasGivenAlone', () => {
  const base = C.makeMini(withPicture(C.startState('nebula')));
  const frozen = JSON.stringify(base);
  C.setMiniMain(base, '#ff0000');
  C.setMiniPage(base, '#00ff00');
  C.setMiniPicture(base, false);
  C.setMiniDim(base, 3);
  C.matchMini(C.setMiniMain(base, '#ff0000'));
  C.fixMini(base);
  C.skipMini(base);
  C.setMain(base, '#00ff00');
  C.tweakColor(base, 'accent', '#123456');
  C.withoutPicture(base);
  C.asNewTheme(base);
  assert.equal(JSON.stringify(base), frozen);
  // the pieces and the colors from any theme too, on a starting theme with pieces off and its extra color set by hand
  const revival = C.setDecoExtra(C.setPiece(C.setPiece(C.makeMini(withPicture(C.startState('revival'))), 'rays', false, PIECES),
    'glows', false, PIECES), '#ff00aa');
  const before = JSON.stringify(revival);
  const after = [
    C.setPiece(revival, 'record', false, PIECES),
    C.setPiece(revival, 'rays', true, PIECES),
    C.allPieces(revival, false, PIECES),
    C.allPieces(revival, true, PIECES),
    C.useColoursFrom(revival, 'rasta'),
    C.setDecoExtra(revival, '#00ff00'),
    C.tweakColor(revival, 'accentLight', '#123456'),
    C.chooseBase(revival, 'neon', PIECES),
    C.chooseBase(revival, 'nebula', PIECES),
    C.chooseTheme(revival, savedTheme(), PIECES)
  ];
  assert.equal(JSON.stringify(revival), before);
  // and what they give back shares nothing that could change it later
  for (const next of after) {
    next.decorationsOff.push('logo');
    next.colors.accent = '#000000';
    if (next.mini) next.mini.colors.accent = '#000000';
  }
  assert.equal(JSON.stringify(revival), before);
});

// ---- a new theme is made with a mini-player version unless the person skips it

test('aNewThemeCarriesAGeneratedReadableMiniPlayerVersionFromTheStart', () => {
  for (const base of ThemeApply.BUILT_IN) {
    const s = C.startState(base);
    assert.deepEqual(s.mini.colors, P.mini(s.colors), base);
    assert.equal(Object.keys(s.mini.colors).length, 15, base);
    assert.equal('glass' in s.mini.colors, false, base);
    assert.equal(s.mini.useImage, false, base);
    assert.equal(C.miniFollows(s), true, base);
    assert.equal(C.miniView(s).made, true, base);
    assert.equal(C.miniView(s).report.ok, true, base);
  }
});

test('aNewThemeSavedWithoutEverVisitingTheMiniPlayerStepSendsAGeneratedMini', () => {
  let s = C.setName(C.startState('aurora'), 'Quick one');
  s = C.setMain(s, '#22c55e');
  const v = C.saveRequest(s).values;
  assert.deepEqual(Object.keys(v.mini).sort(), ['colors', 'dim', 'useImage']);
  assert.deepEqual(v.mini.colors, P.mini(s.colors), 'made from the colors it was saved with');
  assert.equal(v.mini.colors.accent, '#22c55e');
  assert.equal(v.mini.useImage, false);
  assert.equal(v.mini.dim, 40);
  assert.equal('glass' in v.mini.colors, false);
  // starting from a community theme works the same
  const community = C.startFromTheme(savedTheme({ mine: false, mini: null, background: null }));
  assert.deepEqual(C.buildValues(community).mini.colors, P.mini(COLORS));
});

test('skippingStaysSkippedWhateverIsChangedAfterwards', () => {
  let s = C.skipMini(C.startState('nebula'));
  s = C.chooseBase(C.setMain(C.setPage(s, '#102030'), '#ff7a00'), 'dawn');
  s = C.withNewPicture(s, 50);
  assert.equal(s.mini, null);
  assert.equal('mini' in C.buildValues(s), false);
  // and it can be made again
  assert.deepEqual(C.makeMini(s).mini, { colors: P.mini(s.colors), useImage: true, dim: 50 });
});

test('editingKeepsWhateverTheThemeHasAndSavingAsANewThemeKeepsItToo', () => {
  const without = C.fromTheme(savedTheme({ mini: null }));
  assert.equal(without.mini, null, 'a theme saved without one is not given one');
  assert.equal('mini' in C.buildValues(without), false);
  assert.equal(C.asNewTheme(without).mini, null);
  const own = Object.assign(P.mini(COLORS), { accent: '#ff7a00' });
  const withOwn = C.fromTheme(savedTheme({ mini: { colors: own, useImage: false, dim: 20 } }));
  assert.deepEqual(withOwn.mini.colors, own);
  assert.deepEqual(C.asNewTheme(withOwn).mini.colors, own);
  assert.equal('mini' in C.buildValues(C.asNewTheme(without)), false);
});

test('aMiniPlayerVersionMadeBeforeThePictureTakesItUpAtThePicturesDarkening', () => {
  const start = C.startState('nebula');
  assert.equal(start.mini.useImage, false);
  const s = withPicture(start, 62);
  assert.equal(s.mini.useImage, true, 'the same start as making one after the picture');
  assert.equal(s.mini.dim, 62);
  assert.deepEqual(s.mini, C.makeMini(withPicture(C.skipMini(start), 62)).mini);
  assert.equal(C.buildValues(s).mini.useImage, true);
  // a different picture while one is on changes nothing the person set
  const off = C.setMiniDim(C.setMiniPicture(s, false), 77);
  const another = C.withNewPicture(off, 30);
  assert.equal(another.mini.useImage, false);
  assert.equal(another.mini.dim, 77);
  // a state without a mini-player version has nothing to take it up
  assert.equal(withPicture(C.skipMini(start)).mini, null);
});

// ---- the page side

const CREATOR_CODE = (() => {
  const start = INDEX.indexOf('// ---- The guided theme creator');
  const end = INDEX.indexOf('function initThemeTools()');
  assert.ok(start > 0 && end > start, 'creator code not found');
  return INDEX.slice(start, end);
})();

test('thePageLoadsTheCreatorAfterThePaletteAndTheApplyScripts', () => {
  const scripts = [...INDEX.matchAll(/<script src="\/web\/([a-z-]+\.js)"><\/script>/g)].map((m) => m[1]);
  const at = (name) => scripts.indexOf(name);
  assert.ok(at('theme-palette.js') >= 0 && at('theme-apply.js') > at('theme-palette.js'), scripts.join(' '));
  assert.ok(at('theme-creator.js') > at('theme-apply.js'), scripts.join(' '));
});

test('theCreatorNeverBuildsHtmlOrCssTextFromAnything', () => {
  assert.doesNotMatch(CREATOR_CODE, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function/);
  assert.doesNotMatch(CREATOR_CODE, /cssText|setAttribute\(\s*['"]style['"]/);
  // every style value goes in one property at a time
  assert.doesNotMatch(CREATOR_CODE, /\.style\.[A-Za-z]+\s*=[^=]/);
  assert.match(CREATOR_CODE, /\.style\.setProperty\(/);
});

test('theMiniPlayerStepIsBuiltByTcMiniStepAndIsNoLongerAPlaceholder', () => {
  assert.match(CREATOR_CODE, /function tcMiniStep\(s, host\)/);
  assert.doesNotMatch(CREATOR_CODE, /TASK 8 REPLACES THE BODY OF THIS FUNCTION/);
  assert.doesNotMatch(CREATOR_CODE, /isn't ready yet/);
  // the words and the choices come from the tested module; the step only draws them
  for (const name of ['TC.TEXT.MINI_SKIP', 'TC.TEXT.MINI_MAKE', 'TC.TEXT.MINI_MATCH', 'TC.makeMini(', 'TC.skipMini(',
    'TC.miniView(', 'TC.setMiniMain(', 'TC.setMiniPage(', 'TC.matchMini(', 'TC.fixMini(', 'TC.setMiniPicture(', 'TC.setMiniDim(']) {
    assert.ok(CREATOR_CODE.includes(name), name);
  }
});

test('theMiniPlayerPreviewGetsItsColorsOnItsOwnElementAndNeverOnThePage', () => {
  const fn = /function tcMiniPreview\(\) \{([\s\S]*?)\n        \}\n/.exec(CREATOR_CODE);
  assert.ok(fn, 'tcMiniPreview not found');
  assert.match(fn[1], /el\.style\.setProperty\(name, vars\[name\]\)/);
  assert.doesNotMatch(CREATOR_CODE, /documentElement\.style\.setProperty/);
  // the picture is the creator's validated one, and goes in as an <img>, never as CSS text
  assert.match(CREATOR_CODE, /themeKit\.backdrop\(\{ url: src, dim: view\.dim/);
  assert.match(CREATOR_CODE, /tcSetPicture\(pic, back\.url,/);
});

test('everyPictureTheCreatorShowsGoesThroughTheOneHelperThatHonoursReduceMotion', () => {
  // the two previews use it, and nothing sets an <img> source by itself
  assert.match(CREATOR_CODE, /tcSetPicture\(pic, back\.url,/);
  assert.match(CREATOR_CODE, /tcSetPicture\(thumb, src,/);
  assert.equal([...CREATOR_CODE.matchAll(/\bthumb\.src\b|\bpic\.src\b/g)].length, 0);
  const fn = /function tcSetPicture\(img, url, done\) \{([\s\S]*?)\n        \}\n/.exec(CREATOR_CODE);
  assert.ok(fn, 'tcSetPicture not found');
  assert.match(fn[1], /readReduceMotion\(\) && themeKit\.canAnimate\(url, themeLocalPicture\)/);
  assert.match(fn[1], /firstFrameOf\(url, probe\)/);
  assert.match(fn[1], /img\.src = src/);
  // the panel's own freeze and the creator's are one function
  assert.match(INDEX, /async function firstFrameOf\(url, img\)/);
  assert.equal([...INDEX.matchAll(/function firstFrameOf\(/g)].length, 1);
});

test('switchingReduceMotionWhileTheCreatorIsOpenShowsItsPicturesAgain', () => {
  const fn = /function setReduceMotion\(on\) \{([\s\S]*?)\n        \}\n/.exec(INDEX);
  assert.ok(fn, 'setReduceMotion not found');
  assert.match(fn[1], /if \(themeCreator\) themeCreator\.refresh\(\);/);
});

/* The helper itself, run against stand-ins for the page: an <img>, `new Image()`, the first-frame drawing, Reduce motion. */
function pictureHarness(reduce, local) {
  const probes = [];
  const frames = [];
  class FakeImage {
    constructor() { this.src = ''; probes.push(this); }
  }
  const ctx = {
    WeakMap, Image: FakeImage, themeKit: ThemeApply, themeLocalPicture: local || null,
    readReduceMotion: () => reduce.on,
    firstFrameOf: (url, img) => new Promise((resolve) => frames.push({ url, img, resolve }))
  };
  const code = /var tcPictureState = [^\n]*\n\s*function tcSetPicture\(img, url, done\) \{[\s\S]*?\n        \}\n/.exec(CREATOR_CODE);
  assert.ok(code, 'tcSetPicture not found');
  vm.runInNewContext(code[0], ctx);
  const img = () => ({
    _src: '', sets: [], onload: null, onerror: null,
    get src() { return this._src; },
    set src(value) { this._src = value; this.sets.push(value); },
    removeAttribute(name) { if (name === 'src') { this._src = ''; this.sets.push(''); } },
    getAttribute(name) { return name === 'src' ? this._src : null; }
  });
  return { set: ctx.tcSetPicture, probes, frames, img };
}

const GIF = '/theme-images/' + '9f2c'.repeat(8) + '.gif';
const JPG = '/theme-images/' + '3a4b'.repeat(8) + '.jpg';
const FIRST_FRAME = 'data:image/jpeg;base64,AAAA';
const tick = () => new Promise((resolve) => setImmediate(resolve));

test('withReduceMotionOffEveryPictureIsShownAsItIs', () => {
  const h = pictureHarness({ on: false });
  const img = h.img();
  h.set(img, GIF, null);
  assert.equal(img.src, GIF);
  assert.equal(h.probes.length, 0, 'nothing to load first');
  h.set(img, JPG, null);
  assert.equal(img.src, JPG);
});

test('withReduceMotionOnAPictureThatCanMoveIsShownAsItsFirstFrameAndNeverAsItself', async () => {
  const h = pictureHarness({ on: true });
  const img = h.img();
  const seen = [];
  h.set(img, GIF, (ok) => seen.push(ok));
  assert.equal(img.src, '', 'not the moving picture while the first frame is made');
  assert.equal(h.probes.length, 1);
  assert.equal(h.probes[0].src, GIF);
  h.probes[0].onload();
  await tick();
  assert.equal(h.frames.length, 1);
  assert.equal(h.frames[0].url, GIF);
  assert.equal(h.frames[0].img, h.probes[0], 'the loaded copy is the fallback, as in the panel');
  h.frames[0].resolve(FIRST_FRAME);
  await tick();
  assert.equal(img.src, FIRST_FRAME);
  assert.ok(!img.sets.includes(GIF), 'the moving picture was never the source');
  img.onload();
  assert.deepEqual(seen, [true]);
});

test('withReduceMotionOnAStillPictureIsShownAsItIs', () => {
  const h = pictureHarness({ on: true });
  const img = h.img();
  h.set(img, JPG, null);
  assert.equal(img.src, JPG);
  assert.equal(h.probes.length, 0);
});

test('aPictureChosenOnThisComputerIsFrozenOnlyWhenItCanMove', async () => {
  const blob = 'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70';
  const moving = pictureHarness({ on: true }, ThemeApply.localPicture(blob, true));
  const a = moving.img();
  moving.set(a, blob, null);
  assert.equal(a.src, '');
  assert.equal(moving.probes.length, 1);
  const still = pictureHarness({ on: true }, ThemeApply.localPicture(blob, false));
  const b = still.img();
  still.set(b, blob, null);
  assert.equal(b.src, blob);
  assert.equal(still.probes.length, 0);
});

test('anAnswerThatArrivesAfterANewerPictureWasChosenIsIgnored', async () => {
  // the first frame is being drawn when another picture is chosen
  const h = pictureHarness({ on: true });
  const img = h.img();
  h.set(img, GIF, null);
  h.probes[0].onload();
  await tick();
  assert.equal(h.frames.length, 1);
  h.set(img, JPG, null);
  assert.equal(img.src, JPG);
  h.frames[0].resolve(FIRST_FRAME);
  await tick();
  assert.equal(img.src, JPG, 'the old first frame does not overwrite the new picture');
  // the old picture only loads after the new one was chosen: no first frame is made for it
  const other = pictureHarness({ on: true });
  const img2 = other.img();
  other.set(img2, GIF, null);
  other.set(img2, JPG, null);
  other.probes[0].onload();
  await tick();
  assert.equal(other.frames.length, 0, 'nobody wants it any more');
  assert.equal(img2.src, JPG);
  // the old picture failing, with a caller waiting for the new one
  const third = pictureHarness({ on: true });
  const img3 = third.img();
  let called = 0;
  third.set(img3, GIF, () => called++);
  third.set(img3, JPG, () => called++);
  third.probes[0].onerror();
  assert.equal(img3.src, JPG);
  assert.equal(called, 0, 'only the new picture reports, and it has not loaded yet');
  // the old picture finishing its load while the new one's first frame is still being made
  const reduce = { on: false };
  const fourth = pictureHarness(reduce);
  const img4 = fourth.img();
  const heard = [];
  fourth.set(img4, JPG, (ok) => heard.push('jpg:' + ok));
  reduce.on = true;
  fourth.set(img4, GIF, (ok) => heard.push('gif:' + ok));
  img4.onload();
  assert.deepEqual(heard, [], 'that was not the picture asked for last');
});

test('theSamePictureAgainDoesNotLoadItAgainButAChangeOfReduceMotionDoes', async () => {
  const reduce = { on: true };
  const h = pictureHarness(reduce);
  const img = h.img();
  h.set(img, GIF, null);
  h.probes[0].onload();
  await tick();
  h.frames[0].resolve(FIRST_FRAME);
  await tick();
  assert.equal(img.src, FIRST_FRAME);
  h.set(img, GIF, null);
  h.set(img, GIF, null);
  assert.equal(h.probes.length, 1, 'a slider tick or a redraw is no new work');
  reduce.on = false;
  h.set(img, GIF, null);
  assert.equal(img.src, GIF, 'Reduce motion switched off: the picture moves again');
  assert.equal(h.probes.length, 1);
  reduce.on = true;
  h.set(img, GIF, null);
  assert.equal(h.probes.length, 2, 'and switched on again: a new first frame');
});

test('aPictureThatCannotBeDrawnOrLoadedIsNotShownAndTheCallerIsTold', async () => {
  const failing = pictureHarness({ on: true });
  const a = failing.img();
  const told = [];
  failing.set(a, GIF, (ok) => told.push(ok));
  failing.probes[0].onerror();
  assert.equal(a.src, '');
  assert.deepEqual(told, [false]);
  const blank = pictureHarness({ on: true });
  const b = blank.img();
  const told2 = [];
  blank.set(b, GIF, (ok) => told2.push(ok));
  blank.probes[0].onload();
  await tick();
  blank.frames[0].resolve('');
  await tick();
  assert.equal(b.src, '');
  assert.deepEqual(told2, [false]);
  // no link at all
  const none = pictureHarness({ on: false });
  const c = none.img();
  const told3 = [];
  none.set(c, '', (ok) => told3.push(ok));
  assert.deepEqual(told3, [false]);
});

test('theCallerHearsWhenTheShownPictureHasLoadedOrFailed', () => {
  const h = pictureHarness({ on: false });
  const img = h.img();
  const told = [];
  h.set(img, JPG, (ok) => told.push(ok));
  img.onload();
  assert.deepEqual(told, [true]);
  const other = h.img();
  const told2 = [];
  h.set(other, GIF, (ok) => told2.push(ok));
  other.onerror();
  assert.deepEqual(told2, [false]);
});

test('theMiniPlayerPreviewStylesUseOnlyTheSixteenMiniVariables', () => {
  const rules = [...INDEX.matchAll(/\n        (\.tc-minip[^{]*)\{([^}]*)\}/g)];
  assert.ok(rules.length >= 8, 'preview rules not found: ' + rules.length);
  const used = new Set();
  for (const rule of rules) {
    for (const m of rule[2].matchAll(/var\((--[a-z0-9-]+)/g)) used.add(m[1]);
  }
  assert.ok(used.size >= 8, [...used].join(' '));
  // --on-accent is the mini-player's own extra (the play icon on the accent), not one of the 16 it copies from a theme
  for (const name of used) {
    assert.ok(C.MINI_VARS.includes(name) || name === '--on-accent', name + ' is not one of the mini-player variables');
  }
  // and it does not paint with the creator's or the page's other colors
  for (const rule of rules) assert.doesNotMatch(rule[2], /--tc-|--glass|--accent2|--card-/, rule[1]);
});

test('theMiniPlayerPreviewPaintsItsIconsWithTheOnAccentColorNotWhite', () => {
  function rule(selector) {
    const at = INDEX.indexOf('\n        ' + selector + ' {');
    assert.ok(at >= 0, selector + ' not found');
    return INDEX.slice(at, INDEX.indexOf('}', at));
  }
  const art = rule('.tc-minip-art');
  const play = rule('.tc-minip-ctrl.primary');
  assert.match(art, /color:\s*var\(--on-accent\)/);
  assert.match(play, /color:\s*var\(--on-accent\)/);
  assert.doesNotMatch(art + play, /#fff\b|white/i, 'no hard-coded white on the accent');
  // and the preview gives that variable to its own element, from the tested view
  const fn = /function tcMiniPreview\(\) \{([\s\S]*?)\n        \}\n/.exec(CREATOR_CODE);
  assert.ok(fn, 'tcMiniPreview not found');
  assert.match(fn[1], /el\.style\.setProperty\('--on-accent', view\.onAccent\)/);
});

test('theMiniPlayerPreviewDerivesTheColorOnTheAccentFromTheMiniPlayersMainColor', () => {
  let s = C.makeMini(C.startState('nebula'));
  assert.equal(C.miniView(s).onAccent, P.onAccent(s.mini.colors.accent));
  s = C.setMiniMain(s, '#fff200');
  assert.equal(C.miniView(s).onAccent, '#000000', 'dark icons on a light main color');
  s = C.setMiniMain(s, '#101a5c');
  assert.equal(C.miniView(s).onAccent, '#ffffff', 'white icons on a dark main color');
  assert.equal(C.miniView(s).onAccent, P.onAccent(s.mini.colors.accent));
  // it is not one of the 16 variables a theme gives the mini-player
  assert.equal('--on-accent' in C.miniView(s).vars, false);
});

test('theCreatorPreviewsThroughTheSameFunctionThatAppliesSavedThemes', () => {
  assert.match(CREATOR_CODE, /applyCustomTheme\(theme\)/);
  assert.match(CREATOR_CODE, /ThemeCreator\.previewTheme\(/);
  // the one second applier would be a second place that sets properties on the root
  assert.doesNotMatch(CREATOR_CODE, /documentElement\.style\.setProperty|setCustomProperty\(/);
});

test('thePageDrawsThePiecesAndTheColorChipsFromTheTestedModule', () => {
  // the one effects switch is gone; a switch per piece, All on / All off, and the chips, all through ThemeCreator
  assert.doesNotMatch(INDEX, /Keep special effects|special effects/i);
  assert.doesNotMatch(CREATOR_CODE, /\.effects\b|setEffects/);
  for (const name of ['TC.piecesView(s.state, themePieces)', 'TC.setPiece(s.state, ', 'TC.allPieces(s.state, true, themePieces)',
    'TC.allPieces(s.state, false, themePieces)', 'TC.useColoursFrom(s.state, ', 'TC.colorsAreFrom(s.state, ', 'TC.fineTuneColors(',
    'TC.fromTheme(themeOrNull, themePieces)', 'TC.chooseBase(s.state, entry.base, themePieces)',
    'TC.chooseTheme(s.state, entry.theme, themePieces)']) {
    assert.ok(CREATOR_CODE.includes(name), name);
  }
  // the piece labels come from theme-pieces.json and go in as text only
  assert.match(CREATOR_CODE, /plEl\('span', 'tc-opt-name', piece\.label\)/);
  // a list that arrives while the creator is open shows its switches and reaches the preview
  assert.match(panelFunction('retryThemePieces'), /if \(themeCreator\) tcPiecesArrived\(themeCreator\);/);
  assert.match(panelFunction('openThemeCreator'), /loadThemePieces\(\)\.then\(function \(\) \{ tcPiecesArrived\(s\); \}\)/);
});

test('theThemeListRefreshingDoesNotPutTheRealThemeBackWhileTheCreatorShowsItsPreview', () => {
  const fn = /function reconcileThemeChoice\(\) \{([\s\S]*?)\n        \}/.exec(INDEX);
  assert.ok(fn, 'reconcileThemeChoice not found');
  assert.match(fn[1], /^\s*if \(themeCreatorHoldsPage\(\)\) return;/);
});

test('aLocalPictureLinkReachesTheBackdropOnlyThroughTheCreatorsOwnVariable', () => {
  assert.match(INDEX, /themeKit\.backdrop\(theme\.background, themeLocalPicture\)/);
  assert.match(INDEX, /themeKit\.canAnimate\(back\.url, themeLocalPicture\)/);
  assert.match(INDEX, /themeKit\.canAnimate\(themeBackdropData\.url, themeLocalPicture\)/);
  // only the creator ever sets it, and only to what ThemeApply.localPicture gave back (or null)
  const sets = [...INDEX.matchAll(/themeLocalPicture = ([^;]+);/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(sets)].sort(), ['local', 'null'].sort());
});

test('whileTheCreatorStandsAsideItTakesNoKeysSoThePagesOwnDialogsKeepTheirEscape', () => {
  const fn = /function tcOnKey\(e\) \{([\s\S]*?)\n        \}\n/.exec(INDEX);
  assert.ok(fn, 'tcOnKey not found');
  assert.match(fn[1], /^\s*var s = themeCreator;\s*if \(!s \|\| s\.aside\) return;/);
  // and nothing in it brings the creator back from aside: that is the pill's job
  assert.doesNotMatch(fn[1], /tcSetAside\(/);
});

test('enterThatEndsAnImeCompositionInTheNameFieldDoesNotSaveTheTheme', () => {
  // Enter that picks a candidate (Japanese, Chinese, Korean input) is the input method's, not the person's "save".
  assert.match(CREATOR_CODE,
    /input\.addEventListener\('keydown', function \(e\) \{\s*if \(e\.isComposing \|\| e\.keyCode === 229\) return;[^\n]*\s*if \(e\.key === 'Enter'\) \{\s*e\.preventDefault\(\);\s*tcSave\(s\);/);
});

test('whenTheThemeScriptsFailToLoadACustomPickIsNotOverwrittenWithItsBase', () => {
  const fn = /function applyThemeChoice\(choice\) \{([\s\S]*?)\n        \}\n/.exec(INDEX);
  assert.ok(fn, 'applyThemeChoice not found');
  const missing = /if \(!themeKit\) \{([\s\S]*?)\n            \}\n/.exec(fn[1]);
  assert.ok(missing, 'the themeKit-missing branch not found');
  const custom = /indexOf\('custom:'\) === 0\) \{([\s\S]*?)\n                    return;\n/.exec(missing[1]);
  assert.ok(custom, 'the custom-pick part of that branch not found');
  // the base theme goes on the page directly...
  assert.match(custom[1], /setBaseThemeAttribute\(/);
  assert.match(custom[1], /readStoredThemeBase\(\)/);
  // ...and nothing is written down: applyTheme and storeThemeChoice both store the choice
  assert.doesNotMatch(custom[1], /applyTheme\(|storeThemeChoice\(|localStorage/);
  // a built-in pick still goes the usual way
  assert.match(missing[1], /applyTheme\(choice\)/);
});

test('theOwnersHideConfirmSaysThePictureLinkOfAHiddenThemeKeepsWorking', () => {
  const fn = /function askHideTheme\(entry, returnTo\) \{([\s\S]*?)\n        \}\n/.exec(INDEX);
  assert.ok(fn, 'askHideTheme not found');
  assert.match(fn[1], /Only its creator will see it until you un-hide it\./);
  assert.match(fn[1], /Its picture link keeps working\. Use Delete to remove the picture\./);
});
