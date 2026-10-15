'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../../main/resources/web/theme-palette.js');
const { contrast, generate, readability, fixReadability, suggestBackgrounds, surprise, mini, cssVars, suggestDim, onAccent } = P;

const SLOTS = ['bgPrimary', 'bgSecondary', 'bgCard', 'bgElevated', 'bgHover', 'accent', 'accentLight', 'accent2',
  'textPrimary', 'textSecondary', 'textTertiary', 'border', 'borderLight', 'success', 'glass'];
const HEX = /^#[0-9a-f]{6}([0-9a-f]{2})?$/;

// Small seeded generator (mulberry32) so the "random" tests are the same on every run.
function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function randomHex(rnd) {
  const n = Math.floor(rnd() * 0x1000000);
  return '#' + n.toString(16).padStart(6, '0');
}

function parts(hex) {
  return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16));
}

function hueOf(hex) {
  const [r, g, b] = parts(hex).map(v => v / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (d === 0) return 0;
  let h;
  if (max === r) h = ((g - b) / d) % 6;
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return (h * 60 + 360) % 360;
}

// The Aurora built-in theme from index.html, as theme colors.
const AURORA = {
  bgPrimary: '#0a1114', bgSecondary: '#0f181c', bgCard: '#152228', bgElevated: '#1a2e35', bgHover: '#23424d',
  accent: '#2dd4bf', accentLight: '#5eead4', accent2: '#22d3ee',
  textPrimary: '#ecf8f6', textSecondary: '#7a9d9a', textTertiary: '#4a6b67',
  border: '#ffffff12', borderLight: '#ffffff1f', success: '#34d399', glass: '#0f181ce0'
};

test('contrastKnownValues', () => {
  assert.ok(Math.abs(contrast('#000000', '#ffffff') - 21) < 1e-9);
  assert.ok(Math.abs(contrast('#ffffff', '#000000') - 21) < 1e-9);
  assert.equal(contrast('#7c5cff', '#7c5cff'), 1);
  assert.equal(contrast('#ffffff', '#ffffff'), 1);
  // The well-known mid-grey: #777777 on white is just under 4.5, #767676 just over.
  assert.ok(contrast('#777777', '#ffffff') < 4.5);
  assert.ok(contrast('#767676', '#ffffff') > 4.5);
  assert.ok(Math.abs(contrast('#767676', '#ffffff') - 4.54) < 0.01);
});

test('contrastCompositesAlphaOnBlack', () => {
  // White at alpha 0 is black; white at full alpha is white.
  assert.equal(contrast('#ffffff00', '#000000'), 1);
  assert.ok(Math.abs(contrast('#ffffffff', '#000000') - 21) < 1e-9);
  // Half-transparent white over black is a mid-grey, so a middling ratio against black.
  const half = contrast('#ffffff80', '#000000');
  assert.ok(half > 5 && half < 6, String(half));
  // Short forms and upper case are accepted.
  assert.equal(contrast('#FFF', '#ffffff'), 1);
});

test('contrastRejectsGarbage', () => {
  assert.throws(() => contrast('red', '#ffffff'));
  assert.throws(() => contrast('#12345', '#ffffff'));
  assert.throws(() => contrast(null, '#ffffff'));
  assert.throws(() => generate('nope', '#101010', 'dark'));
  assert.throws(() => contrast('#ffffff', undefined));
});

test('readabilityOnTheBuiltInAurora', () => {
  assert.deepEqual(readability(AURORA), { ok: true, problems: [] });
});

test('readabilityNamesTheTextThatFails', () => {
  const lowMain = Object.assign({}, AURORA, { textPrimary: '#15252b' });
  assert.deepEqual(readability(lowMain), { ok: false, problems: ['textPrimary'] });
  const lowSecondary = Object.assign({}, AURORA, { textSecondary: '#1b2b31' });
  assert.deepEqual(readability(lowSecondary), { ok: false, problems: ['textSecondary'] });
  const both = Object.assign({}, AURORA, { textPrimary: '#15252b', textSecondary: '#1b2b31' });
  assert.deepEqual(readability(both), { ok: false, problems: ['textPrimary', 'textSecondary'] });
});

test('readabilityChecksMainTextOnBothBackgrounds', () => {
  // Fine on the card, too faint on the page background: still a problem.
  const c = Object.assign({}, AURORA, { bgPrimary: '#cfd8dc', bgCard: '#152228', textPrimary: '#ecf8f6' });
  assert.equal(contrast(c.textPrimary, c.bgCard) >= 4.5, true);
  assert.equal(contrast(c.textPrimary, c.bgPrimary) < 4.5, true);
  assert.deepEqual(readability(c).problems, ['textPrimary']);
});

test('readabilityCompositesTranslucentTextOnTheBackground', () => {
  // Translucent text is judged as it looks over the background: a faint white veil is not readable.
  const base = Object.assign({}, AURORA, { textPrimary: '#ffffff', textSecondary: '#ffffff' });
  assert.equal(readability(base).ok, true);
  const faint = Object.assign({}, base, { textPrimary: '#ffffff30' });
  assert.deepEqual(readability(faint).problems, ['textPrimary']);
});

test('fixReadabilityRepairs', () => {
  const cases = [
    // white text on white
    { bgPrimary: '#ffffff', bgCard: '#ffffff', textPrimary: '#ffffff', textSecondary: '#ffffff' },
    { bgPrimary: '#ffffff', bgCard: '#f4f4f4', textPrimary: '#fefefe', textSecondary: '#f0f0f0' },
    // black text on black
    { bgPrimary: '#000000', bgCard: '#101010', textPrimary: '#000000', textSecondary: '#050505' },
    // mid-greys where black text is the only way
    { bgPrimary: '#808080', bgCard: '#858585', textPrimary: '#8a8a8a', textSecondary: '#8f8f8f' },
    { bgPrimary: '#888888', bgCard: '#909090', textPrimary: '#ffffff', textSecondary: '#ffffff' },
    // mid-greys where white text is the only way
    { bgPrimary: '#6a6a6a', bgCard: '#707070', textPrimary: '#6e6e6e', textSecondary: '#727272' },
    { bgPrimary: '#5e5e5e', bgCard: '#6a6a6a', textPrimary: '#000000', textSecondary: '#101010' },
    // coloured: light text on a light page, dark text on a dark page
    { bgPrimary: '#f3e8ff', bgCard: '#ffffff', textPrimary: '#e9d5ff', textSecondary: '#d8b4fe' },
    { bgPrimary: '#0b0d17', bgCard: '#181b2e', textPrimary: '#232850', textSecondary: '#2a3060' }
  ];
  for (const c of cases) {
    const before = Object.assign({}, AURORA, c);
    assert.equal(readability(before).ok, false, 'should start unreadable: ' + JSON.stringify(c));
    const after = fixReadability(before);
    assert.deepEqual(readability(after), { ok: true, problems: [] }, JSON.stringify(c));
  }
});

test('fixReadabilityLeavesReadableColorsAlone', () => {
  const out = fixReadability(AURORA);
  assert.deepEqual(out, AURORA);
  assert.notEqual(out, AURORA);
});

test('fixReadabilityOnlyMovesText', () => {
  const bad = Object.assign({}, AURORA, { textPrimary: '#15252b', textSecondary: '#1b2b31' });
  const before = JSON.parse(JSON.stringify(bad));
  const out = fixReadability(bad);
  assert.deepEqual(bad, before, 'the input is not modified');
  assert.notEqual(out.textPrimary, bad.textPrimary);
  assert.notEqual(out.textSecondary, bad.textSecondary);
  for (const slot of SLOTS) {
    if (slot !== 'textPrimary' && slot !== 'textSecondary') assert.equal(out[slot], bad[slot], slot);
  }
  assert.equal(readability(out).ok, true);
});

test('fixReadabilityKeepsTheHue', () => {
  const bad = Object.assign({}, AURORA, { textPrimary: '#2a4aa8', textSecondary: '#2a4aa8' });
  const out = fixReadability(bad);
  assert.equal(readability(out).ok, true);
  assert.ok(out.textPrimary !== bad.textPrimary);
  for (const slot of ['textPrimary', 'textSecondary']) {
    const drift = Math.abs(hueOf(out[slot]) - hueOf(bad[slot]));
    assert.ok(Math.min(drift, 360 - drift) < 4, slot + ' ' + out[slot]);
  }
});

test('fixReadabilityMovesAsLittleAsPossible', () => {
  // Slightly short of the bar: the fix is a small step, not a jump to white.
  const bad = Object.assign({}, AURORA, { textPrimary: '#688582' }); // 4.08:1 on the card
  assert.deepEqual(readability(bad).problems, ['textPrimary']);
  const out = fixReadability(bad);
  assert.equal(readability(out).ok, true);
  assert.notEqual(out.textPrimary, '#ffffff');
  assert.ok(contrast(out.textPrimary, AURORA.bgCard) < 4.7, String(contrast(out.textPrimary, AURORA.bgCard)));
});

test('fixReadabilityFallsBackToBlackOrWhiteWhenNothingReachesTheBar', () => {
  // A dark page with a light card: no single text color reaches 4.5:1 on both. Moving text can't
  // fix that, so the best of pure black / pure white is used and the problem stays reported.
  const c = Object.assign({}, AURORA, { bgPrimary: '#6a6a6a', bgCard: '#8a8a8a', textPrimary: '#777777' });
  const out = fixReadability(c);
  assert.ok(out.textPrimary === '#000000' || out.textPrimary === '#ffffff', out.textPrimary);
  const worst = t => Math.min(contrast(t, c.bgPrimary), contrast(t, c.bgCard));
  const other = out.textPrimary === '#000000' ? '#ffffff' : '#000000';
  assert.ok(worst(out.textPrimary) >= worst(other));
  assert.deepEqual(readability(out).problems, ['textPrimary']);
});

test('generateKeepsTheAccent', () => {
  for (const mode of ['dark', 'light']) {
    const c = generate('#7c5cff', mode === 'dark' ? '#0b0d14' : '#f4f2ef', mode);
    assert.equal(c.accent, '#7c5cff');
  }
  // Upper case is normalised, never altered otherwise.
  assert.equal(generate('#FF8800', '#101010', 'dark').accent, '#ff8800');
});

test('generateGivesAllSlotsInTheAllowedFormat', () => {
  for (const mode of ['dark', 'light']) {
    const c = generate('#2dd4bf', mode === 'dark' ? '#0a1114' : '#f4f2ef', mode);
    assert.deepEqual(Object.keys(c).sort(), SLOTS.slice().sort());
    for (const slot of SLOTS) assert.match(c[slot], HEX, slot + ' ' + c[slot]);
  }
});

test('generateStepsLighterInDarkModeAndDarkerInLight', () => {
  const lum = hex => contrast(hex, '#000000');
  const dark = generate('#7c5cff', '#0b0d14', 'dark');
  assert.equal(dark.bgPrimary, '#0b0d14');
  assert.ok(lum(dark.bgSecondary) > lum(dark.bgPrimary));
  assert.ok(lum(dark.bgCard) > lum(dark.bgSecondary));
  assert.ok(lum(dark.bgElevated) > lum(dark.bgCard));
  assert.ok(lum(dark.bgHover) > lum(dark.bgElevated));
  const light = generate('#6d28d9', '#f4f2ef', 'light');
  assert.equal(light.bgPrimary, '#f4f2ef');
  assert.ok(lum(light.bgSecondary) < lum(light.bgPrimary));
  assert.ok(lum(light.bgCard) < lum(light.bgSecondary));
  assert.ok(lum(light.bgElevated) < lum(light.bgCard));
  assert.ok(lum(light.bgHover) < lum(light.bgElevated));
});

test('generateTextFollowsTheMode', () => {
  const dark = generate('#7c5cff', '#0b0d14', 'dark');
  assert.ok(contrast(dark.textPrimary, '#ffffff') < 1.5, dark.textPrimary);
  const light = generate('#6d28d9', '#f4f2ef', 'light');
  assert.ok(contrast(light.textPrimary, '#000000') < 2, light.textPrimary);
  // Secondary and tertiary text are quieter than the main text, in both modes.
  for (const c of [dark, light]) {
    assert.ok(contrast(c.textPrimary, c.bgCard) > contrast(c.textSecondary, c.bgCard));
    assert.ok(contrast(c.textSecondary, c.bgCard) > contrast(c.textTertiary, c.bgCard));
  }
});

test('generateDerivesBordersGlassAndSuccess', () => {
  const dark = generate('#7c5cff', '#0b0d14', 'dark');
  assert.equal(dark.border, '#ffffff12');
  assert.equal(dark.borderLight, '#ffffff1f');
  assert.equal(dark.glass, dark.bgSecondary + 'e0');
  assert.ok(contrast(dark.success, dark.bgCard) >= 3);
  assert.ok(hueOf(dark.success) > 100 && hueOf(dark.success) < 190, dark.success);
  const light = generate('#6d28d9', '#f4f2ef', 'light');
  assert.equal(light.border, '#00000014');
  assert.equal(light.borderLight, '#0000001f');
  assert.equal(light.glass, light.bgSecondary + 'e0');
  assert.ok(contrast(light.success, light.bgCard) >= 3);
  assert.ok(hueOf(light.success) > 100 && hueOf(light.success) < 190, light.success);
});

test('generateAccentVariantsAreNearbyShades', () => {
  const c = generate('#7c5cff', '#0b0d14', 'dark');
  assert.notEqual(c.accentLight, c.accent);
  assert.ok(contrast(c.accentLight, '#000000') > contrast(c.accent, '#000000'), 'accentLight is lighter');
  assert.notEqual(c.accent2, c.accent);
  const drift = Math.abs(hueOf(c.accent2) - hueOf(c.accent));
  assert.ok(Math.min(drift, 360 - drift) <= 60, 'accent2 stays near the accent hue');
});

test('manyRandomPairsMeetTheBars', () => {
  const rnd = seeded(20261014);
  for (let i = 0; i < 500; i++) {
    const accent = randomHex(rnd);
    const background = randomHex(rnd);
    for (const mode of ['dark', 'light']) {
      const c = generate(accent, background, mode);
      const r = readability(c);
      assert.equal(r.ok, true, `${accent} on ${background} (${mode}) -> ${JSON.stringify(c)} ${JSON.stringify(r)}`);
      assert.equal(c.accent, accent);
      for (const slot of SLOTS) assert.match(c[slot], HEX, slot);
    }
  }
});

test('midToneBackgroundsStillMeetTheBars', () => {
  // The hard case: a page color whose lighter/darker card steps would straddle the greys that
  // neither white nor black text can serve.
  for (let v = 0x50; v <= 0xa0; v += 2) {
    const bg = '#' + v.toString(16).padStart(2, '0').repeat(3);
    for (const mode of ['dark', 'light']) {
      const r = readability(generate('#e11d48', bg, mode));
      assert.equal(r.ok, true, `${bg} ${mode} ${JSON.stringify(r)}`);
    }
  }
});

test('generateCopesWithAModeThatDoesNotMatchTheBackground', () => {
  const lightBgDarkMode = generate('#7c5cff', '#ffffff', 'dark');
  assert.equal(readability(lightBgDarkMode).ok, true);
  assert.equal(lightBgDarkMode.bgPrimary, '#ffffff');
  const darkBgLightMode = generate('#7c5cff', '#000000', 'light');
  assert.equal(readability(darkBgLightMode).ok, true);
  // Borders stay visible against the page they sit on.
  assert.equal(lightBgDarkMode.border.slice(0, 7), '#000000');
  assert.equal(darkBgLightMode.border.slice(0, 7), '#ffffff');
});

test('generateIsDeterministic', () => {
  assert.deepEqual(generate('#e11d48', '#120a0c', 'dark'), generate('#e11d48', '#120a0c', 'dark'));
});

test('suggestBackgroundsGivesFourDistinctOnesForTheMode', () => {
  const rnd = seeded(7);
  for (let i = 0; i < 60; i++) {
    const accent = randomHex(rnd);
    for (const mode of ['dark', 'light']) {
      const list = suggestBackgrounds(accent, mode);
      assert.equal(list.length, 4);
      assert.equal(new Set(list).size, 4, `${accent} ${mode} ${list}`);
      for (const bg of list) {
        assert.match(bg, /^#[0-9a-f]{6}$/);
        const l = contrast(bg, '#000000');
        if (mode === 'dark') assert.ok(l < 2, `${bg} should be dark`);
        else assert.ok(l > 15, `${bg} should be light`);
        assert.equal(readability(generate(accent, bg, mode)).ok, true);
      }
    }
  }
});

test('suggestBackgroundsFollowTheAccentHue', () => {
  const list = suggestBackgrounds('#2dd4bf', 'dark');
  // At least the first suggestion is tinted toward the accent (teal), not grey.
  const h = hueOf(list[0]);
  assert.ok(Math.abs(h - hueOf('#2dd4bf')) < 12, list[0]);
  assert.notDeepEqual(suggestBackgrounds('#2dd4bf', 'dark'), suggestBackgrounds('#e11d48', 'dark'));
  // A grey accent gets neutral backgrounds, not an arbitrary tint.
  for (const bg of suggestBackgrounds('#888888', 'dark')) {
    const [r, g, b] = parts(bg);
    assert.ok(Math.max(r, g, b) - Math.min(r, g, b) <= 1, bg);
  }
});

test('surpriseIsReadable', () => {
  const rnd = seeded(99);
  const modes = new Set();
  for (let i = 0; i < 200; i++) {
    const s = surprise(rnd);
    assert.deepEqual(Object.keys(s).sort(), ['accent', 'background', 'mode']);
    assert.match(s.accent, /^#[0-9a-f]{6}$/);
    assert.match(s.background, /^#[0-9a-f]{6}$/);
    assert.ok(s.mode === 'dark' || s.mode === 'light');
    modes.add(s.mode);
    assert.equal(readability(generate(s.accent, s.background, s.mode)).ok, true);
    // A good pair: the main color stands out from the background.
    assert.ok(contrast(s.accent, s.background) >= 3, `${s.accent} on ${s.background}`);
  }
  assert.equal(modes.size, 2, 'both modes come up');
});

test('surpriseOnlyUsesTheGivenRandomFunction', () => {
  const realRandom = Math.random;
  Math.random = () => { throw new Error('Math.random must not be used'); };
  try {
    const a = surprise(seeded(5));
    const b = surprise(seeded(5));
    assert.deepEqual(a, b);
    assert.notDeepEqual(surprise(seeded(5)), surprise(seeded(6)));
    // Extreme values from the function are tolerated.
    assert.ok(surprise(() => 0));
    assert.ok(surprise(() => 0.9999999999));
    assert.ok(surprise(() => 1));
  } finally {
    Math.random = realRandom;
  }
  assert.throws(() => surprise());
});

test('miniDropsGlass', () => {
  const m = mini(AURORA);
  assert.equal('glass' in m, false);
  assert.deepEqual(Object.keys(m).sort(), SLOTS.filter(s => s !== 'glass').sort());
  assert.equal(Object.keys(m).length, 14);
  for (const slot of Object.keys(m)) assert.equal(m[slot], AURORA[slot], slot);
  // A copy: changing it leaves the theme alone.
  m.accent = '#000000';
  assert.equal(AURORA.accent, '#2dd4bf');
});

test('miniIgnoresUnknownSlots', () => {
  const m = mini(Object.assign({}, AURORA, { sneaky: '#123456' }));
  assert.equal('sneaky' in m, false);
  assert.equal(Object.keys(m).length, 14);
});

test('cssVarsDerivesGlowGradientAndRgb', () => {
  const v = cssVars(AURORA);
  assert.equal(v['--bg-primary'], '#0a1114');
  assert.equal(v['--bg-secondary'], '#0f181c');
  assert.equal(v['--bg-card'], '#152228');
  assert.equal(v['--bg-elevated'], '#1a2e35');
  assert.equal(v['--bg-hover'], '#23424d');
  assert.equal(v['--accent'], '#2dd4bf');
  assert.equal(v['--accent-light'], '#5eead4');
  assert.equal(v['--text-primary'], '#ecf8f6');
  assert.equal(v['--text-secondary'], '#7a9d9a');
  assert.equal(v['--text-tertiary'], '#4a6b67');
  assert.equal(v['--border'], '#ffffff12');
  assert.equal(v['--border-light'], '#ffffff1f');
  assert.equal(v['--success'], '#34d399');
  assert.equal(v['--glass'], '#0f181ce0');
  // Derived: glow is the accent at 32% alpha (0x52), gradient runs accent -> accent2.
  assert.equal(v['--accent-glow'], '#2dd4bf52');
  assert.equal(v['--accent-gradient'], 'linear-gradient(135deg, #2dd4bf, #22d3ee)');
  assert.equal(v['--accent-r'], '45');
  assert.equal(v['--accent-g'], '212');
  assert.equal(v['--accent-b'], '191');
  assert.equal(v['--accent2-r'], '34');
  assert.equal(v['--accent2-g'], '211');
  assert.equal(v['--accent2-b'], '238');
});

test('cssVarsHasExactlyThePanelVariables', () => {
  const names = Object.keys(cssVars(AURORA)).sort();
  assert.deepEqual(names, [
    '--accent', '--accent-b', '--accent-g', '--accent-glow', '--accent-gradient', '--accent-light', '--accent-r',
    '--accent2-b', '--accent2-g', '--accent2-r',
    '--bg-card', '--bg-elevated', '--bg-hover', '--bg-primary', '--bg-secondary',
    '--border', '--border-light', '--glass', '--success',
    '--text-primary', '--text-secondary', '--text-tertiary'
  ]);
  for (const value of Object.values(cssVars(AURORA))) assert.equal(typeof value, 'string');
});

test('cssVarsGlowIgnoresAnAlphaOnTheAccent', () => {
  const v = cssVars(Object.assign({}, AURORA, { accent: '#2dd4bf80' }));
  assert.equal(v['--accent-glow'], '#2dd4bf52');
  assert.equal(v['--accent-r'], '45');
});

test('suggestDimDarkensBrightPictures', () => {
  assert.ok(suggestDim(1.0) >= 50, String(suggestDim(1.0)));
  assert.ok(suggestDim(0.1) <= 20, String(suggestDim(0.1)));
});

test('suggestDimIsMonotonicAndClamped', () => {
  let last = -1;
  for (let i = 0; i <= 100; i++) {
    const d = suggestDim(i / 100);
    assert.ok(Number.isInteger(d), String(d));
    assert.ok(d >= 0 && d <= 90, String(d));
    assert.ok(d >= last, `not monotonic at ${i / 100}`);
    last = d;
  }
  assert.equal(suggestDim(0), 0);
  assert.equal(suggestDim(-5), 0);
  assert.ok(suggestDim(50) <= 90);
  // A picture whose brightness couldn't be measured gets a middling default instead of NaN.
  for (const odd of [NaN, undefined, null, 'bright', Infinity]) {
    const d = suggestDim(odd);
    assert.ok(Number.isInteger(d) && d >= 0 && d <= 90, String(odd));
  }
});

test('onAccentIsDarkOnLightAccentsAndWhiteOnDarkOnes', () => {
  assert.equal(onAccent('#ffffff'), '#000000');
  assert.equal(onAccent('#f1c40f'), '#000000');
  assert.equal(onAccent('#2dd4bf'), '#000000');
  assert.equal(onAccent('#000000'), '#ffffff');
  assert.equal(onAccent('#1e3a8a'), '#ffffff');
  assert.equal(onAccent('#6d28d9'), '#ffffff');
  // Black reaches 4.83:1 on this violet and white only 4.35:1, so the rule picks black.
  assert.equal(onAccent('#7c5cff'), '#000000');
  assert.equal(onAccent('#7C5CFF'), '#000000', 'upper case is the same color');
});

test('onAccentTreatsAnAccentWithAlphaAsContrastDoesOverBlack', () => {
  assert.equal(onAccent('#ffffff00'), '#ffffff', 'nothing of it shows over black');
  assert.equal(onAccent('#ffffffff'), '#000000');
  assert.equal(onAccent('#ffffff40'), onAccent('#404040'), 'a quarter of white over black is that dark grey');
});

test('onAccentAlwaysPicksTheBetterOfBlackAndWhiteAndIsReadable', () => {
  const rnd = seeded(7);
  for (let i = 0; i < 500; i++) {
    const c = randomHex(rnd);
    const chosen = onAccent(c);
    const other = chosen === '#000000' ? '#ffffff' : '#000000';
    assert.ok(chosen === '#000000' || chosen === '#ffffff', c);
    assert.ok(contrast(chosen, c) >= contrast(other, c), c);
    assert.ok(contrast(chosen, c) >= 4.5, c + ' ' + contrast(chosen, c));
  }
});

test('onAccentRefusesWhatIsNotAColor', () => {
  for (const bad of ['red', '', null, undefined, 42, '#12']) assert.throws(() => onAccent(bad), TypeError, String(bad));
});
