'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ThemeCreator = require('../../main/resources/web/theme-creator.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const ThemePalette = require('../../main/resources/web/theme-palette.js');

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
  success: '--success', glass: '--glass'
};

const PICTURE = '/theme-images/' + '9f2c'.repeat(8) + '.webp';
const BLOB = 'blob:https://music.example.org/3f2b8c1e-7a4d-4e59-9b0c-2d6f1a8e4c70';

const COLORS = {
  bgPrimary: '#0b0d14', bgSecondary: '#10131c', bgCard: '#161a26', bgElevated: '#1c2131', bgHover: '#262c42',
  accent: '#7c5cff', accentLight: '#a08bff', accent2: '#22d3ee', textPrimary: '#eef0f8',
  textSecondary: '#9aa0b8', textTertiary: '#646a82', border: '#ffffff12', borderLight: '#ffffff1f',
  success: '#34d399', glass: '#10131ce0'
};

function savedTheme(extra) {
  return Object.assign({
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: { id: '1', name: 'fred' }, visibility: 'public',
    hiddenByOwner: false, mine: true, version: 3, base: 'ember', effects: false, colors: Object.assign({}, COLORS),
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

test('basesWithSpecialEffectsAreTheOnesWithLayersTheStylesheetCanSwitchOff', () => {
  // the targets the "plain" rule hides are the ones that count as special effects
  const plain = /((?:html\[data-custom-theme\]\[data-custom-plain\] [^,{]+,\s*)+)html\[data-custom-theme\]\[data-custom-plain\] ([^,{]+) \{\s*display: none;/.exec(INDEX);
  assert.ok(plain, 'plain rule not found');
  const targets = [...(plain[1] + 'html[data-custom-theme][data-custom-plain] ' + plain[2])
    .matchAll(/data-custom-plain\] ([^,{\s]+)/g)].map((m) => m[1]);
  assert.ok(targets.length >= 5, targets.join(' '));
  const layered = new Set();
  for (const m of INDEX.matchAll(/html\[data-theme="([a-z-]+)"\] ([^,{\s]+)/g)) {
    if (targets.includes(m[2])) layered.add(m[1]);
  }
  assert.deepEqual([...C.DECORATED_BASES].sort(), [...layered].sort());
  for (const base of ThemeApply.BUILT_IN) assert.equal(C.hasDecorations(base), layered.has(base), base);
});

test('theElevenBasesWithEffectsAreTheOnesTheBriefNames', () => {
  assert.deepEqual([...C.DECORATED_BASES].sort(),
    ['cipher', 'dawn-dark', 'ember', 'grove', 'neon', 'rasta', 'revival', 'signal', 'steel', 'sumi', 'victory']);
  assert.equal(C.hasDecorations('nebula'), false);
  assert.equal(C.hasDecorations('dawn'), false);
  assert.equal(C.hasDecorations('nope'), false);
  assert.equal(C.hasDecorations(undefined), false);
});

test('theStartingPalettesAreTheBuiltInThemesOfTheStylesheet', () => {
  assert.deepEqual(Object.keys(C.BASE_PALETTES), [...ThemeApply.BUILT_IN]);
  const root = cssVars(cssBlock(':root'));
  for (const id of ThemeApply.BUILT_IN) {
    const vars = Object.assign({}, root, id === 'nebula' ? {} : cssVars(cssBlock('html[data-theme="' + id + '"]')));
    const expected = {};
    for (const slot of Object.keys(SLOT_VARS)) expected[slot] = cssColor(vars[SLOT_VARS[slot]]);
    expected.accent2 = '#' + hex2(+vars['--accent2-r']) + hex2(+vars['--accent2-g']) + hex2(+vars['--accent2-b']);
    assert.deepEqual(C.BASE_PALETTES[id].colors, expected, id);
    assert.equal(C.BASE_PALETTES[id].font, /^'([^']+)'/.exec(vars['--font-family'])[1], id + ' font');
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
  assert.deepEqual([...P.SLOTS], javaList('COLOR_SLOTS'));
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

test('startingFromABuiltInTakesItsColorsFontAndEffects', () => {
  const s = C.startState('ember');
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, C.BASE_PALETTES.ember.colors);
  assert.equal(s.font, C.BASE_PALETTES.ember.font);
  assert.equal(s.effects, true);
  assert.equal(s.radiusScale, 100);
  assert.equal(s.mainColor, C.BASE_PALETTES.ember.colors.accent);
  assert.equal(s.pageColor, C.BASE_PALETTES.ember.colors.bgPrimary);
  assert.equal(s.mode, 'dark');
  assert.equal(s.picture, 'none');
  assert.equal(s.mini, null);
  assert.equal(s.name, '');
  assert.equal(s.visibility, 'public');
  assert.equal(s.id, null);
  assert.equal(C.startState('dawn').mode, 'light');
  assert.equal(C.startState('nope').base, 'nebula');
  assert.notEqual(C.startState('ember').colors, C.BASE_PALETTES.ember.colors, 'a copy, so tweaks never change the table');
});

test('startingFromACommunityThemeTakesItsLookButNotItsPictureNameOrMini', () => {
  const s = C.startFromTheme(savedTheme({ mine: false, owner: { id: '9', name: 'sam' } }));
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, COLORS);
  assert.equal(s.font, 'Outfit');
  assert.equal(s.radiusScale, 80);
  assert.equal(s.effects, true, 'its picture forced effects off; without the picture they are back');
  assert.equal(s.picture, 'none');
  assert.equal(s.mini, null);
  assert.equal(s.id, null);
  assert.equal(s.name, '');
  assert.equal(C.startFromTheme(savedTheme({ background: null, effects: false })).effects, false);
  assert.equal(C.startFromTheme(savedTheme({ background: null, effects: true })).effects, true);
});

test('editingFillsTheStateFromTheSavedTheme', () => {
  const s = C.fromTheme(savedTheme());
  assert.equal(s.id, 'k3j9x0q2m7ab');
  assert.equal(s.version, 3);
  assert.equal(s.name, 'Midnight Drive');
  assert.equal(s.visibility, 'public');
  assert.equal(s.hiddenByOwner, false);
  assert.equal(s.base, 'ember');
  assert.equal(s.effects, false);
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

test('editingAThemeWithoutAPictureOrMini', () => {
  const s = C.fromTheme(savedTheme({ background: null, mini: null, effects: true }));
  assert.equal(s.picture, 'none');
  assert.equal(s.hadPicture, false);
  assert.equal(s.savedPictureUrl, '');
  assert.equal(s.mini, null);
  assert.equal(s.effects, true);
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
  let s = C.fromTheme(savedTheme());
  s = C.chooseBase(s, 'dawn');
  assert.equal(s.base, 'dawn');
  assert.deepEqual(s.colors, C.BASE_PALETTES.dawn.colors);
  assert.equal(s.font, C.BASE_PALETTES.dawn.font);
  assert.equal(s.radiusScale, 100);
  assert.equal(s.effects, true);
  assert.equal(s.mode, 'light');
  assert.equal(s.id, 'k3j9x0q2m7ab');
  assert.equal(s.name, 'Midnight Drive');
  assert.equal(s.picture, 'saved');
  assert.equal(s.dim, 45);
  assert.ok(s.mini);
  assert.equal(C.chooseBase(s, 'nope').base, 'nebula');
});

test('chooseThemeStartsFromACommunityThemesLook', () => {
  const s = C.chooseTheme(C.startState('nebula'), savedTheme({ mine: false, background: null, effects: true }));
  assert.equal(s.base, 'ember');
  assert.deepEqual(s.colors, COLORS);
  assert.equal(s.font, 'Outfit');
  assert.equal(s.radiusScale, 80);
});

// ---- fine-tuning

test('everyFineTuneColorHasAPlainNameAndTheDerivedOnesDoNot', () => {
  const slots = C.COLOR_CHOICES.map((c) => c.slot);
  assert.equal(new Set(slots).size, slots.length);
  assert.deepEqual(slots.filter((s) => !P.SLOTS.includes(s)), []);
  assert.deepEqual([...P.SLOTS].filter((s) => !slots.includes(s)), ['borderLight', 'glass']);
  const labels = C.COLOR_CHOICES.map((c) => c.label);
  for (const wanted of ['Background', 'Panels', 'Cards', 'Hover', 'Main color', 'Main text', 'Secondary text', 'Borders']) {
    assert.ok(labels.includes(wanted), wanted);
  }
  for (const c of C.COLOR_CHOICES) {
    assert.match(c.label, /^[A-Z][a-z]+( [a-z]+)*$/, 'a plain name, not a variable name');
    assert.equal(typeof c.hint, 'string');
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

test('thereIsASetterForCornersFontAndEffectsThatKeepsThemInRange', () => {
  const s = C.startState('ember');
  assert.equal(C.setCorners(s, 40).radiusScale, 40);
  assert.equal(C.setCorners(s, 400).radiusScale, 200);
  assert.equal(C.setCorners(s, -4).radiusScale, 0);
  assert.equal(C.setCorners(s, '75').radiusScale, 75);
  assert.equal(C.setCorners(s, 'wide').radiusScale, 100);
  assert.equal(C.setFont(s, 'Syne').font, 'Syne');
  assert.equal(C.setFont(s, 'Comic Sans').font, s.font);
  assert.equal(C.setEffects(s, false).effects, false);
  assert.equal(C.setEffects(C.setEffects(s, false), true).effects, true);
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

const SHARED_KEYS = ['base', 'colors', 'effects', 'font', 'name', 'radiusScale', 'visibility'];

test('theValuesAreExactlyWhatTheServerTakes', () => {
  const s = Object.assign(C.startState('nebula'), { name: '  Neon Nights  ' });
  const v = C.buildValues(s);
  assert.deepEqual(Object.keys(v).sort(), SHARED_KEYS);
  assert.equal(v.name, 'Neon Nights');
  assert.equal(v.visibility, 'public');
  assert.equal(v.base, 'nebula');
  assert.deepEqual(Object.keys(v.colors), javaList('COLOR_SLOTS'));
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

test('effectsAreSentOnlyWhereTheBaseHasThemAndNoPictureIsSet', () => {
  const rows = [
    // base, state.effects, picture, expected
    ['ember', true, 'none', true],
    ['ember', false, 'none', false],
    ['ember', true, 'new', false],
    ['ember', true, 'saved', false],
    ['nebula', true, 'none', true],
    // a base without decorations never sends false: that would hide its default glow
    ['nebula', false, 'none', true],
    ['dawn', false, 'none', true],
    ['aurora', false, 'none', true],
    // ... except that a picture always switches effects off, as the server does
    ['nebula', true, 'new', false],
    ['dawn', true, 'saved', false]
  ];
  for (const [base, effects, picture, expected] of rows) {
    const s = Object.assign(C.startState(base), { effects, picture, savedPictureUrl: picture === 'saved' ? PICTURE : '' });
    assert.equal(C.buildValues(s).effects, expected, [base, effects, picture].join(' '));
  }
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
  assert.equal('mini' in C.buildValues(C.startState('nebula')), false);
  const mini = { colors: P.mini(COLORS), useImage: true, dim: 55 };
  const withPicture = C.buildValues(Object.assign(C.fromTheme(savedTheme()), { mini }));
  assert.deepEqual(withPicture.mini, mini);
  assert.deepEqual(Object.keys(withPicture.mini.colors), javaList('COLOR_SLOTS').slice(0, 14));
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
  assert.equal(t.effects, true);
  assert.equal(t.background, null);
  assert.equal(C.previewTheme(Object.assign(s, { name: 'Hi' }), '').name, 'Hi');
});

test('aPictureChosenHereIsPreviewedFromItsLocalLinkAndASavedOneFromItsOwn', () => {
  const local = ThemeApply.localPicture(BLOB, false);
  const fresh = C.previewTheme(Object.assign(C.startState('nebula'), { picture: 'new', dim: 20, blur: 3, fit: 'center' }), BLOB);
  assert.deepEqual(fresh.background, { url: BLOB, dim: 20, blur: 3, fit: 'center' });
  assert.equal(fresh.effects, false);
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
    [400, 'Special effects must be true or false', C.STEP.TWEAK],
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

// ---- the placeholder for the mini-player step

test('aThemeWithoutAMiniPlayerVersionSendsNone', () => {
  const s = C.startState('nebula');
  assert.equal(s.mini, null);
  assert.equal('mini' in C.buildValues(s), false);
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

test('theMiniPlayerStepIsAClearlyNamedHookForTheNextTask', () => {
  assert.match(CREATOR_CODE, /TASK 8 REPLACES THE BODY OF THIS FUNCTION/);
  assert.match(CREATOR_CODE, /function tcMiniStep\(s, host\)/);
});

test('theCreatorPreviewsThroughTheSameFunctionThatAppliesSavedThemes', () => {
  assert.match(CREATOR_CODE, /applyCustomTheme\(theme\)/);
  assert.match(CREATOR_CODE, /ThemeCreator\.previewTheme\(/);
  // the one second applier would be a second place that sets properties on the root
  assert.doesNotMatch(CREATOR_CODE, /documentElement\.style\.setProperty|setCustomProperty\(/);
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
