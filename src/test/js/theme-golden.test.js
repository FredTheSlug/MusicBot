'use strict';
/*
 * The built-in themes' decoration colours, kept as they were on 2026-10-17 (before the decorations were rewritten to
 * follow a theme's own colours). The golden files in src/test/resources/theme-golden/ were written by
 * scripts/theme-golden.js from the stylesheets of that day; every later rewrite has to keep evaluating to them.
 *
 * Excluded: inside url(). Colours drawn inside pictures (Ember's flame SVGs, Sumi's kanji tile, Revival's grain and
 * star) are not in the golden files; those pieces are checked by their own tests and by eye.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Colors = require('./css-colors.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const ThemeCreator = require('../../main/resources/web/theme-creator.js');

const ROOT = path.join(__dirname, '../../..');

function readJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, rel), 'utf8'));
}

const PIECES_FILE = 'src/main/resources/web/theme-pieces.json';
const goldenFiles = {};

/* The golden colours of one stylesheet ('panel' or 'mini'): { theme: { key: "rgba(r,g,b,a)" } }. */
function goldenOf(kind) {
  if (!goldenFiles[kind]) goldenFiles[kind] = readJson('src/test/resources/theme-golden/' + kind + '.json');
  return goldenFiles[kind];
}

/*
 * Golden keys gone on purpose and new decoration keys added on purpose are listed, each with the reason it was
 * reviewed, in src/test/resources/theme-golden/allowlist.json (read by this test and by scripts/theme-golden.js), and
 * so are theme variables whose colour turned plain on purpose (a decoration colour taken out of a variable a shared rule
 * paints with) and the cascade flips a piece guard causes on purpose (cascadeFlipsAreRestoredOrListed).
 */
const ALLOWLIST_FILE = 'src/test/resources/theme-golden/allowlist.json';

const DECORATED = ['ember', 'steel', 'grove', 'sumi', 'rasta', 'dawn-dark', 'cipher', 'signal', 'victory', 'neon',
  'revival'];

// ---- the evaluator

function near(actual, expected, label) {
  assert.equal(actual.length, 4, label);
  for (let i = 0; i < 3; i++) {
    assert.ok(Math.abs(actual[i] - expected[i]) <= 0.51, label + ': ' + actual + ' vs ' + expected);
  }
  assert.ok(Math.abs(actual[3] - expected[3]) <= 0.0005, label + ': ' + actual + ' vs ' + expected);
}

test('evaluatorHandlesEveryForm', () => {
  const vars = {
    '--accent': '#e2825c',
    '--accent-light': '#f2a882',
    '--accent-r': '226',
    '--accent-g': '130',
    '--accent-b': '92',
    '--glow': 'var(--accent)',
    '--cream': 'color-mix(in srgb, var(--accent-light) 40%, white)',
    '--card-alpha': '0.4',
    '--shadow': '0 4px 24px rgba(0, 0, 0, 0.2)'
  };
  const e = (expr) => Colors.evaluate(expr, vars);
  // hex, 3/4/6/8 digits
  near(e('#fff'), [255, 255, 255, 1], '#fff');
  near(e('#0008'), [0, 0, 0, 0x88 / 255], '#0008');
  near(e('#E2825C'), [226, 130, 92, 1], '#E2825C');
  near(e('#e2825c80'), [226, 130, 92, 128 / 255], '#e2825c80');
  // rgb() and rgba(), comma and space forms
  near(e('rgba(242, 179, 94, 0.2)'), [242, 179, 94, 0.2], 'rgba');
  near(e('rgb(1,2,3)'), [1, 2, 3, 1], 'rgb');
  near(e('rgb(1 2 3 / 50%)'), [1, 2, 3, 0.5], 'rgb space');
  near(e('rgba(255,255,255,.06)'), [255, 255, 255, 0.06], 'leading dot');
  // keywords
  near(e('transparent'), [0, 0, 0, 0], 'transparent');
  near(e('white'), [255, 255, 255, 1], 'white');
  near(e('BLACK'), [0, 0, 0, 1], 'black');
  // var(), chained, with a fallback, and the r/g/b triples
  near(e('var(--accent)'), [226, 130, 92, 1], 'var');
  near(e('var(--glow)'), [226, 130, 92, 1], 'chained var');
  near(e('var(--missing, #000)'), [0, 0, 0, 1], 'fallback');
  near(e('var(--missing, var(--accent-light))'), [242, 168, 130, 1], 'var fallback');
  near(e('rgba(var(--accent-r), var(--accent-g), var(--accent-b), 0.07)'), [226, 130, 92, 0.07], 'triple');
  // color-mix: a colour at an alpha, toward white and black, both weights, nested, see-through inputs
  near(e('color-mix(in srgb, var(--accent) 7%, transparent)'), [226, 130, 92, 0.07], 'mix with transparent');
  near(e('color-mix(in srgb, transparent, var(--accent) 25%)'), [226, 130, 92, 0.25], 'transparent first');
  near(e('color-mix(in srgb, var(--accent-light) 40%, white)'),
    [0.4 * 242 + 0.6 * 255, 0.4 * 168 + 0.6 * 255, 0.4 * 130 + 0.6 * 255, 1], 'toward white');
  near(e('color-mix(in srgb, #ffffff 30%, black)'), [76.5, 76.5, 76.5, 1], 'toward black');
  near(e('color-mix(in srgb, 30% #ffffff, black)'), [76.5, 76.5, 76.5, 1], 'weight first');
  near(e('color-mix(in srgb, #000, #fff)'), [127.5, 127.5, 127.5, 1], 'no weights: half and half');
  near(e('color-mix(in srgb, #ffffff 30%, #000000 50%)'), [95.625, 95.625, 95.625, 0.8], 'weights under 100%');
  near(e('color-mix(in srgb, #ffffff 60%, #000000 60%)'), [127.5, 127.5, 127.5, 1], 'weights over 100%');
  near(e('color-mix(in srgb, rgba(255, 0, 0, 1) 50%, rgba(0, 0, 255, 0.5))'), [170, 0, 85, 0.75], 'premultiplied');
  near(e('var(--cream)'), [0.4 * 242 + 0.6 * 255, 0.4 * 168 + 0.6 * 255, 0.4 * 130 + 0.6 * 255, 1], 'mix in a var');
  near(e('color-mix(in srgb, color-mix(in srgb, var(--accent) 50%, black) 20%, transparent)'),
    [113, 65, 46, 0.2], 'nested');
  near(e('color-mix(in srgb, rgba(163, 230, 53, 0.35) calc(var(--card-alpha) * 100%), transparent)'),
    [163, 230, 53, 0.14], 'calc weight');
  // what isn't one colour is an error, not a guess
  assert.throws(() => e('var(--nope)'), /--nope/);
  assert.throws(() => e('var(--shadow)'));
  assert.throws(() => e('linear-gradient(#000, #fff)'));
  assert.throws(() => e('currentColor'));
  assert.throws(() => e('color-mix(in oklab, #000, #fff)'), /srgb/);
  // the golden form and the match rule
  assert.equal(Colors.format([249, 115, 22, 0.32]), 'rgba(249,115,22,0.32)');
  assert.equal(Colors.format([127.5, 0.2, 254.6, 0.12345]), 'rgba(128,0,255,0.123)');
  assert.equal(Colors.format([0, 0, 0, 0]), 'rgba(0,0,0,0)');
  assert.equal(Colors.sameColour('rgba(249,115,22,0.32)', [250, 114, 23, 0.329]), true);
  assert.equal(Colors.sameColour('rgba(249,115,22,0.32)', [251, 115, 22, 0.32]), false);
  assert.equal(Colors.sameColour('rgba(249,115,22,0.32)', [249, 115, 22, 0.335]), false);
  assert.equal(Colors.sameColour('rgba(0,0,0,0)', [242, 179, 94, 0]), true, 'nothing shows at alpha 0');
});

// ---- reading a stylesheet: which colours count, and keys that survive a rewrite

const SAMPLE = [
  ':root { --accent: #7c5cfc; --accent-r: 124; --accent-g: 92; --accent-b: 252; --card-shadow: 0 4px 24px rgba(0, 0, 0, 0.2); }',
  'html[data-theme="x"] { --accent: #e2825c; --accent-light: #f2a882; --accent-r: 226; --accent-g: 130; --accent-b: 92;',
  '  --font-family: \'Inter\', sans-serif; }',
  '/* a comment with html[data-theme="x"] .nope { color: red; } in it */',
  'html[data-theme="x"] .card,',
  'html[data-theme="x"]   .header > .bar::before {',
  '  background: linear-gradient(90deg, rgba(242, 179, 94, 0.2), transparent 70%), url("data:image/svg+xml,%3Csvg fill=\'%23ff0000\'/%3E");',
  '  box-shadow: var(--card-shadow), 0 0 0 1px var(--accent) !important;',
  '  font-family: Georgia, serif;',
  '  animation: xGlow 3s infinite;',
  '}',
  'html[data-theme="y"] .card { color: #123456; }',
  'html[data-custom-theme][data-theme="x"] .card { color: #abcdef; }',
  '@media (min-width:1280px) { html[data-theme="x"] .badge { color: #f6efe5; } }',
  '@keyframes xGlow { 0%,100% { box-shadow: 0 0 0 rgba(226, 130, 92, 0); } 50% { box-shadow: 0 0 24px rgba(226, 130, 92, 0.4); } }',
  '@keyframes yGlow { to { color: #000; } }'
].join('\n');

/* The same colours after a rewrite: slots and mixes instead of literals, piece guards, other spacing and order. */
const SAMPLE_REWRITTEN = [
  ':root { --accent: #7c5cfc; --accent-r: 124; --accent-g: 92; --accent-b: 252; --card-shadow: 0 4px 24px rgba(0, 0, 0, 0.2); }',
  'html[data-theme="x"] { --accent: #e2825c; --accent-light: #f2a882; --accent2: #f2b35e; --accent-r: 226; --accent-g: 130;',
  '  --accent-b: 92; --text-primary: #f6efe5; --font-family: \'Inter\', sans-serif; --deco-extra: var(--accent-light); }',
  '@keyframes xGlow { 0%, 100% { box-shadow: 0 0 0 transparent; } 50% { box-shadow: 0 0 24px color-mix(in srgb, var(--accent) 40%, transparent); } }',
  'html[data-theme="x"]:not([data-deco-off~="glows"]) .header>.bar::before { background: linear-gradient(90deg, color-mix(in srgb, var(--accent2) 20%, transparent), transparent 70%), url("data:image/svg+xml,%3Csvg/%3E");',
  '  box-shadow: var(--card-shadow), 0 0 0 1px rgba(var(--accent-r), var(--accent-g), var(--accent-b), 1) !important; animation: xGlow 3s infinite; }',
  'html[data-theme="x"]:not([data-deco-off~="glows"]) .card { background: linear-gradient(90deg, color-mix(in srgb, var(--accent2) 20%, transparent), transparent 70%);',
  '  box-shadow: var(--card-shadow), 0 0 0 1px var(--accent) !important; animation: xGlow 3s infinite; }',
  '@media (min-width: 1280px) { html[data-theme="x"]:not([data-deco-off~="badge"]) .badge { color: color-mix(in srgb, var(--text-primary) 100%, white); } }'
].join('\n');

test('keysAreSelectorPropertyAndPlaceAndSurviveARewrite', () => {
  const keys = Colors.decorationColours(SAMPLE, 'x').map((c) => c.key);
  assert.deepEqual(keys.sort(), [
    '@keyframes xGlow 0%, 100%|box-shadow|0',
    '@keyframes xGlow 50%|box-shadow|0',
    '@media (min-width:1280px) html[data-theme="x"] .badge|color|0',
    'html[data-theme="x"] .card|background|0',
    'html[data-theme="x"] .card|background|1',
    'html[data-theme="x"] .card|box-shadow|0',
    'html[data-theme="x"] .card|box-shadow|1',
    'html[data-theme="x"] .header > .bar::before|background|0',
    'html[data-theme="x"] .header > .bar::before|background|1',
    'html[data-theme="x"] .header > .bar::before|box-shadow|0',
    'html[data-theme="x"] .header > .bar::before|box-shadow|1',
    'html[data-theme="x"]|--accent-light|0',
    'html[data-theme="x"]|--accent-rgb|0',
    'html[data-theme="x"]|--accent|0'
  ].sort());
  const golden = Colors.snapshot(SAMPLE, 'x');
  assert.equal(golden['html[data-theme="x"] .card|box-shadow|0'], 'rgba(0,0,0,0.2)', 'a shadow variable is read through');
  assert.equal(golden['html[data-theme="x"] .card|box-shadow|1'], 'rgba(226,130,92,1)', 'the theme\'s own --accent');
  assert.equal(golden['@media (min-width:1280px) html[data-theme="x"] .badge|color|0'], 'rgba(246,239,229,1)');
  assert.deepEqual(Colors.snapshot(SAMPLE, 'y'), {
    'html[data-theme="y"]|--accent-rgb|0': 'rgba(124,92,252,1)',
    'html[data-theme="y"] .card|color|0': 'rgba(18,52,86,1)'
  }, 'yGlow is not used by y, the custom-theme rule belongs to no built-in theme, and :root gives y its triple');
  assert.deepEqual(Colors.compareToGolden(golden, SAMPLE_REWRITTEN, 'x'), [],
    'guards, spacing, order and slot expressions keep the keys and the colours');
});

test('aChangedOrMissingColourIsReportedUnlessListedAsGone', () => {
  const golden = Colors.snapshot(SAMPLE, 'x');
  const changed = SAMPLE.replace('rgba(242, 179, 94, 0.2)', 'rgba(242, 179, 96, 0.2)');
  const problems = Colors.compareToGolden(golden, changed, 'x');
  assert.equal(problems.length, 2, problems.join('\n'));
  assert.match(problems[0], /changed/);
  assert.match(problems[0], /rgba\(242,179,94,0\.2\)/);
  const gone = SAMPLE.replace('@media (min-width:1280px) { html[data-theme="x"] .badge { color: #f6efe5; } }', '');
  assert.deepEqual(Colors.compareToGolden(golden, gone, 'x'),
    ['gone: @media (min-width:1280px) html[data-theme="x"] .badge|color|0 (was rgba(246,239,229,1))']);
  assert.deepEqual(Colors.compareToGolden(golden, gone, 'x',
    { gone: ['@media (min-width:1280px) html[data-theme="x"] .badge|color|0'] }), []);
  assert.deepEqual(Colors.compareToGolden(golden, SAMPLE, 'x', { gone: ['html[data-theme="x"]|--accent|0'] }),
    ['listed as gone but still there: html[data-theme="x"]|--accent|0']);
  const broken = SAMPLE.replace('#f6efe5', 'color-mix(in oklab, #f6efe5, white)');
  assert.match(Colors.compareToGolden(golden, broken, 'x').join('\n'), /can't evaluate/);
});

test('aGuardedDeclarationWinsAsInTheBrowser', () => {
  // each :not([data-deco-off~=...]) weighs one attribute, so with every piece on a guarded rule beats an unguarded
  // one whatever their order; !important beats both
  const x = (rules) => 'html[data-theme="x"] { --accent: #222222; }\n' + rules;
  const golden = Colors.snapshot(x('html[data-theme="x"] .card { color: #111111; }'), 'x');
  const guardedFirst = x('html[data-theme="x"]:not([data-deco-off~="a"]) .card { color: #ff0000; }\n' +
    'html[data-theme="x"] .card { color: #111111; }');
  assert.deepEqual(Colors.compareToGolden(golden, guardedFirst, 'x'),
    ['changed: html[data-theme="x"] .card|color|0: was rgba(17,17,17,1), now rgba(255,0,0,1) (#ff0000)']);
  const twoGuards = x('html[data-theme="x"]:not([data-deco-off~="a"]):not([data-deco-off~="b"]) .card { color: #ff0000; }\n' +
    'html[data-theme="x"]:not([data-deco-off~="a"]) .card { color: #111111; }');
  assert.equal(Colors.compareToGolden(golden, twoGuards, 'x').length, 1, 'two guards beat one');
  const guardList = x('html[data-theme="x"]:not([data-deco-off~="a"], [data-deco-off~="b"]) .card { color: #ff0000; }\n' +
    'html[data-theme="x"]:not([data-deco-off~="c"]) .card { color: #111111; }');
  assert.deepEqual(Colors.compareToGolden(golden, guardList, 'x'), [], 'a guard list weighs one guard: the later wins');
  const important = x('html[data-theme="x"] .card { color: #111111 !important; }\n' +
    'html[data-theme="x"]:not([data-deco-off~="a"]) .card { color: #ff0000; }');
  assert.deepEqual(Colors.compareToGolden(golden, important, 'x'), []);
  // the same for variables: a guarded theme block over a later unguarded one, the theme block over :root
  const varGolden = Colors.snapshot(x('html[data-theme="x"] .card { color: var(--accent); }'), 'x');
  const guardedVars = 'html[data-theme="x"]:not([data-deco-off~="a"]) { --accent: #ff0000; }\n' +
    'html[data-theme="x"] { --accent: #222222; }\nhtml[data-theme="x"] .card { color: var(--accent); }';
  assert.equal(Colors.themeVars(guardedVars, 'x')['--accent'], '#ff0000');
  assert.deepEqual(Colors.compareToGolden(varGolden, guardedVars, 'x'), [
    'changed: html[data-theme="x"]|--accent|0: was rgba(34,34,34,1), now rgba(255,0,0,1) (#ff0000)',
    'changed: html[data-theme="x"] .card|color|0: was rgba(34,34,34,1), now rgba(255,0,0,1) (var(--accent))'
  ]);
  assert.equal(Colors.themeVars('html[data-theme="x"] { --accent: #222222; }\n:root { --accent: #ff0000; }', 'x')
    ['--accent'], '#222222', 'the theme block beats a later :root');
  assert.equal(Colors.themeVars('html[data-theme="x"] { --accent: #222222; }\n' +
    ':root:not([data-deco-off~="a"]):not([data-deco-off~="b"]) { --accent: #ff0000; }', 'x')['--accent'], '#ff0000',
  'a :root with two guards (0,3,0) beats the theme block (0,1,1)');
});

test('variablesSetBelowTheThemeBlockAreRefused', () => {
  // the check resolves every variable at html; one set on an element would be seen differently by that element's
  // variants (:hover, ::after, descendants), so it is refused rather than guessed
  assert.throws(() => Colors.decorationColours('html[data-theme="x"] .card { --c: #ff0000; }\n' +
    'html[data-theme="x"] .card:hover { color: var(--c); }', 'x'), /--c is set by html\[data-theme="x"\] \.card\b/);
  // an html-level variable built from another one is computed at html: an element can't change it from below
  assert.throws(() => Colors.decorationColours('html[data-theme="x"] { --accent: #222222; --glow: var(--accent); }\n' +
    'html[data-theme="x"] .card { --accent: #ff0000; color: var(--glow); }', 'x'),
  /--accent is set by html\[data-theme="x"\] \.card\b/);
  // a shared rule setting a variable a theme colour uses
  assert.throws(() => Colors.decorationColours('html[data-theme="x"] { --accent: #222222; }\n.card { --accent: #ff0000; }\n' +
    'html[data-theme="x"] .card { color: var(--accent); }', 'x'),
  /--accent is set by \.card and used by html\[data-theme="x"\] \.card\|color\|0/);
  // a shared rule's variable no theme colour uses is fine (the panel's layout values), and so is the theme block,
  // guarded or in an @media copy, which a rule in the same @media sees
  assert.deepEqual(Colors.snapshot(':root { --card-padding: 16px; }\n.main { --card-padding: 8px; }\n' +
    'html[data-theme="x"] { --accent: #222222; }\n' +
    '@media (max-width:640px) { html[data-theme="x"]:not([data-deco-off~="a"]) { --accent: #333333; }\n' +
    '  html[data-theme="x"] .card { color: var(--accent); padding: var(--card-padding); } }', 'x'), {
    'html[data-theme="x"]|--accent|0': 'rgba(34,34,34,1)',
    '@media (max-width:640px) html[data-theme="x"]|--accent|0': 'rgba(51,51,51,1)',
    '@media (max-width:640px) html[data-theme="x"] .card|color|0': 'rgba(51,51,51,1)'
  });
});

test('aNewDecorationIsReportedUnlessListedAsNew', () => {
  const golden = Colors.snapshot(SAMPLE, 'x');
  const added = SAMPLE + '\nhtml[data-theme="x"]:not([data-deco-off~="glows"]) .card::after { box-shadow: 0 0 40px #ff00ff; }';
  assert.deepEqual(Colors.compareToGolden(golden, added, 'x'),
    ['new: html[data-theme="x"] .card::after|box-shadow|0 (rgba(255,0,255,1))']);
  assert.deepEqual(Colors.compareToGolden(golden, added, 'x',
    { added: ['html[data-theme="x"] .card::after|box-shadow|0'] }), []);
  // a new variable is not a decoration until a rule paints with it
  assert.deepEqual(Colors.compareToGolden(golden,
    SAMPLE.replace('--accent-light: #f2a882;', '--accent-light: #f2a882; --deco-extra: #fde047;'), 'x'), []);
  assert.deepEqual(Colors.compareToGolden(golden, SAMPLE, 'x', { added: ['html[data-theme="x"] .nope|color|0'] }),
    ['listed as new but not there: html[data-theme="x"] .nope|color|0']);
  assert.deepEqual(Colors.compareToGolden(golden, SAMPLE, 'x', { added: ['html[data-theme="x"] .card|background|0'] }),
    ['listed as new but golden: html[data-theme="x"] .card|background|0']);
  const unreadable = SAMPLE + '\nhtml[data-theme="x"] .card::after { color: color-mix(in oklab, #000, #fff); }';
  assert.match(Colors.compareToGolden(golden, unreadable, 'x', { added: ['html[data-theme="x"] .card::after|color|0'] })
    .join('\n'), /can't evaluate: html\[data-theme="x"\] \.card::after\|color\|0/);
});

test('aThemeVariableMayTurnPlainOnPurpose', () => {
  // a decoration colour taken out of a theme variable a shared rule paints with (an accent ring out of --card-shadow,
  // into the piece's own rule): the variable's colour changes, but only where listed, and only to plain black or white
  const css = (shadow) => 'html[data-theme="x"] { --accent: #222222; --card-shadow: ' + shadow + '; }';
  const golden = Colors.snapshot(css('0 0 0 1px rgba(34, 34, 34, 0.14), 0 12px 40px rgba(0, 0, 0, 0.35)'), 'x');
  const key = 'html[data-theme="x"]|--card-shadow|';
  const plain = css('0 12px 40px rgba(0, 0, 0, 0.35)');
  assert.deepEqual(Colors.compareToGolden(golden, plain, 'x'), [
    'changed: ' + key + '0: was rgba(34,34,34,0.14), now rgba(0,0,0,0.35) (rgba(0, 0, 0, 0.35))',
    'gone: ' + key + '1 (was rgba(0,0,0,0.35))'
  ]);
  const listed = { changed: [key + '0'], gone: [key + '1'] };
  assert.deepEqual(Colors.compareToGolden(golden, plain, 'x', listed), []);
  assert.deepEqual(Colors.compareToGolden(golden, css('0 12px 40px rgba(255, 0, 0, 0.35)'), 'x', listed),
    ['changed to a colour: ' + key + '0: now rgba(255,0,0,0.35); only plain black, white or nothing may be listed']);
  assert.deepEqual(Colors.compareToGolden(golden, css('0 0 0 1px rgba(34, 34, 34, 0.14), 0 12px 40px rgba(0, 0, 0, 0.35)'),
    'x', { changed: [key + '0'] }), ['listed as changed but the same: ' + key + '0']);
  const card = 'html[data-theme="x"] .card|color|0';
  const ruleGolden = Colors.snapshot('html[data-theme="x"] .card { color: #111111; }', 'x');
  assert.deepEqual(Colors.compareToGolden(ruleGolden, 'html[data-theme="x"] .card { color: #000000; }', 'x',
    { changed: [card] }), ['listed as changed but not a variable of the theme block: ' + card]);
  assert.deepEqual(Colors.compareToGolden(golden, plain, 'x', { changed: [key + '0', key + '7'], gone: [key + '1'] }),
    ['listed as changed but not golden: ' + key + '7']);
});

test('theAccentTriplesArePinned', () => {
  // shared rules paint with rgba(var(--accent-r), var(--accent-g), var(--accent-b), a): the numbers are a colour too
  const golden = Colors.snapshot(SAMPLE, 'x');
  assert.equal(golden['html[data-theme="x"]|--accent-rgb|0'], 'rgba(226,130,92,1)');
  assert.equal(golden['html[data-theme="x"]|--accent2-rgb|0'], undefined, 'no --accent2-r/g/b here');
  assert.deepEqual(Colors.compareToGolden(golden, SAMPLE.replace('--accent-g: 130;', '--accent-g: 140;'), 'x'),
    ['changed: html[data-theme="x"]|--accent-rgb|0: was rgba(226,130,92,1), now rgba(226,140,92,1) ' +
      '(rgb(var(--accent-r), var(--accent-g), var(--accent-b)))']);
  for (const id of ThemeApply.BUILT_IN) {
    const block = id === 'nebula' ? ':root' : 'html[data-theme="' + id + '"]';
    const vars = Colors.themeVars(Colors.panelCss(), id);
    for (const t of ['accent', 'accent2']) {
      const rgb = ['r', 'g', 'b'].map((c) => vars['--' + t + '-' + c]).join(',');
      assert.equal(goldenOf('panel')[id][block + '|--' + t + '-rgb|0'], 'rgba(' + rgb + ',1)', id + ' ' + t);
    }
  }
});

test('theAllowlistsAreOneReviewedFileTheScriptReadsToo', () => {
  const allow = readJson(ALLOWLIST_FILE);
  assert.deepEqual(Object.keys(allow), ['goneOnPurpose', 'newOnPurpose', 'changedOnPurpose', 'flipsOnPurpose']);
  for (const list of Object.values(allow)) {
    assert.deepEqual(Object.keys(list), ['panel', 'mini']);
    for (const kind of ['panel', 'mini']) {
      for (const theme of Object.keys(list[kind])) {
        assert.ok(ThemeApply.BUILT_IN.includes(theme), kind + ': ' + theme);
        for (const [key, why] of Object.entries(list[kind][theme])) {
          assert.ok(typeof why === 'string' && why.trim().length >= 10, kind + ' ' + theme + ' ' + key + ': say why');
        }
      }
    }
  }
  assert.deepEqual(Colors.allowlist(), allow);
  const script = fs.readFileSync(path.join(ROOT, 'scripts/theme-golden.js'), 'utf8');
  assert.match(script, /Colors\.checkSheet\(/, 'the script checks with the shared allowlist');
  assert.ok(!/ON_PURPOSE\s*=/.test(fs.readFileSync(__filename, 'utf8')), 'no second list in the test');
});

test('theStylesheetsAreReadAsThePanelAndTheMiniPlayerLoadThem', () => {
  const panel = Colors.panelCss();
  assert.ok(panel.includes('html[data-theme="revival"]') && !panel.includes('<style'), 'the panel\'s <style>');
  const mini = Colors.miniCss();
  assert.ok(mini.indexOf('--card-alpha: 1') < mini.indexOf('html[data-theme="nebula"]'), 'mini.css, then themes.css');
  assert.equal(Colors.themeVars(panel, 'nebula')['--accent'], '#7c5cfc', 'Nebula is :root');
  assert.equal(Colors.themeVars(panel, 'revival')['--accent'], '#e2825c', 'the theme block over :root');
  assert.equal(Colors.themeVars(panel, 'revival')['--main-max-width'], '1920px', ':root fills the rest');
  assert.equal(Colors.themeVars(mini, 'neon')['--card-alpha'], '1', 'the mini-player\'s :root');
});

// ---- the pieces

const SPEC_PIECES = {
  revival: [['sunrise', 'Sunrise glow', true], ['rays', 'Sunburst rays', true], ['logo', 'Turning logo'],
    ['badge', '"revived" badge'], ['sparkles', '\u2726 titles'], ['record', 'Spinning record'], ['glows', 'Warm glows']],
  ember: [['flames', 'Corner flames'], ['edge', 'Header flame edge'], ['fire', 'Fire glow']],
  neon: [['photo', 'Backdrop photo', true], ['rails', 'Lime rails'], ['glows', 'Glows and outlines'],
    ['title', 'Glowing title and progress bar']],
  cipher: [['grid', 'Terminal grid', true], ['underline', 'Neon underline'], ['glows', 'Glows and outlines'],
    ['prompt', 'Prompt and blinking caret']],
  signal: [['scanlines', 'CRT scanlines', true], ['caption', '"LINK ESTABLISHED" caption'],
    ['line', 'Header glow line'], ['hud', 'HUD frames and outlines']],
  victory: [['glows', 'Gold glows', true], ['rule', 'Header gold rule'], ['gilded', 'Gilded cards and glows']],
  sumi: [['mist', 'Ink mist', true], ['kanji', 'Kanji watermark', true], ['lines', 'Vermilion header lines'],
    ['hanko', 'Hanko card edge'], ['seal', 'Seal and brush stroke']],
  rasta: [['stripe', 'Flag stripe'], ['titles', 'Flag-coloured titles'], ['bar', 'Flag drag bar'],
    ['lyric', 'Lyric glow']],
  grove: [['photo', 'Forest photo', true], ['hairlines', 'Slate hairlines'], ['glows', 'Corner glows']],
  steel: [['wash', 'Accent wash', true], ['sheen', 'Top sheen and brushed lines']],
  'dawn-dark': [['wash', 'Ambient wash', true], ['edge', 'Gradient edge']]
};

test('piecesListMatchesTheSpec', () => {
  const raw = fs.readFileSync(path.join(ROOT, PIECES_FILE), 'utf8');
  assert.notEqual(raw.charCodeAt(0), 0xFEFF, 'no byte order mark');
  for (const ch of raw) {
    const cp = ch.codePointAt(0);
    assert.ok(cp >= 0x20 || cp === 0x0A || cp === 0x0D, 'control character U+' + cp.toString(16));
    assert.ok(!/\p{Cf}/u.test(ch), 'invisible or direction character U+' + cp.toString(16).toUpperCase());
  }
  const pieces = JSON.parse(raw);
  assert.deepEqual(Object.keys(pieces), [...ThemeApply.BUILT_IN], 'every built-in theme, in the picker\'s order');
  assert.deepEqual(Object.keys(SPEC_PIECES).sort(), [...ThemeCreator.DECORATED_BASES].sort());
  assert.deepEqual(DECORATED.slice().sort(), [...ThemeCreator.DECORATED_BASES].sort());
  for (const id of ThemeApply.BUILT_IN) {
    assert.deepEqual(Object.keys(pieces[id]), ['decoExtra', 'pieces'], id);
    const expected = (SPEC_PIECES[id] || [['glow', 'Glow']])
      .map(([pid, label, background]) => ({ id: pid, label: label, background: background === true }));
    assert.deepEqual(pieces[id].pieces, expected, id);
    const ids = pieces[id].pieces.map((p) => p.id);
    assert.equal(new Set(ids).size, ids.length, id + ': ids are unique');
    ids.forEach((pid) => assert.match(pid, /^[a-z]+$/, id + ': ' + pid + ' fits in data-deco-off'));
  }
  const special = { rasta: '#dc2626', cipher: '#22d3ee', ember: '#fde047' };
  for (const id of ThemeApply.BUILT_IN) {
    assert.equal(pieces[id].decoExtra, special[id] || ThemeCreator.BASE_PALETTES[id].colors.accentLight, id);
    assert.match(pieces[id].decoExtra, /^#[0-9a-f]{6}$/, id);
  }
});

// ---- the golden colours

test('goldenIsTodaysStylesheet', () => {
  assert.deepEqual(Object.keys(goldenOf('panel')), [...ThemeApply.BUILT_IN]);
  assert.deepEqual(Object.keys(goldenOf('mini')), [...ThemeApply.BUILT_IN]);
  for (const kind of ['panel', 'mini']) {
    const problems = Colors.checkSheet(kind);
    assert.deepEqual(problems, [], kind + ': ' + problems.length + ' decoration colour(s) differ from the golden file');
  }
});

test('goldenCoversEveryDecoratedTheme', () => {
  const pieces = readJson(PIECES_FILE);
  for (const kind of ['panel', 'mini']) {
    for (const id of ThemeApply.BUILT_IN) {
      const keys = Object.keys(goldenOf(kind)[id] || {});
      const block = kind === 'panel' && id === 'nebula' ? ':root|' : 'html[data-theme="' + id + '"]|';
      assert.ok(keys.some((k) => k.startsWith(block + '--accent|')), kind + ' ' + id + ': its colour slots');
      const decorations = keys.filter((k) => !k.startsWith(block));
      // every theme with pieces in a stylesheet has decorations there: the panel's 11, and all 17 skins
      const decorated = kind === 'mini' || DECORATED.includes(id);
      if (decorated) assert.ok(decorations.length > 0, kind + ' ' + id + ': no decoration colours');
      for (const k of keys) assert.match(goldenOf(kind)[id][k], /^rgba\(\d+,\d+,\d+,(0|1|0\.\d{1,3})\)$/, k);
    }
  }
  // the big ones are all there
  const count = (kind, id) => Object.keys(goldenOf(kind)[id]).filter((k) => !k.startsWith('html[data-theme="' + id + '"]|'))
    .length;
  assert.ok(count('panel', 'revival') >= 83, 'revival: ' + count('panel', 'revival'));
  assert.ok(count('panel', 'neon') >= 48, 'neon: ' + count('panel', 'neon'));
  assert.ok(Object.keys(pieces).every((id) => goldenOf('panel')[id] && goldenOf('mini')[id]));
});

// ---- the rewritten themes: colours from the slots, every rule of a piece under its guard, shape left unguarded,
// no cascade turned by a guard, motion that can stop (each theme joins these as its decorations are rewritten)

const ThemePalette = require('../../main/resources/web/theme-palette.js');

const PIECES = readJson(PIECES_FILE);

const REWRITTEN = ['revival', 'neon', 'signal', 'victory', 'cipher'];

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

/* Every declaration of the panel's stylesheet (Colors.declarations: selector normalized, spaced with its guards). */
function panelDeclarations() {
  return Colors.declarations(Colors.panelCss());
}

/* A theme's own rules: those starting at html[data-theme="x"], and its custom-theme rules html[data-custom-theme][...]. */
function ownedBy(d, themeId) {
  return d.theme === themeId || Colors.customThemeOf(d.selector) === themeId;
}

/* The blocks a theme's variables may be set in: the theme block, and the custom-theme block over it. */
function bareBlocks(themeId) {
  return ['html[data-theme="' + themeId + '"]', 'html[data-custom-theme][data-theme="' + themeId + '"]',
    'html[data-theme="' + themeId + '"][data-custom-theme]'];
}

/* The <html> a rule of a theme is seen on, with some pieces off (custom-theme rules: with a custom theme on). */
function htmlOf(d, themeId, off) {
  const attrs = { 'data-theme': themeId };
  if (off !== undefined) attrs['data-deco-off'] = off;
  if (Colors.customThemeOf(d.selector) === themeId) attrs['data-custom-theme'] = 'x';
  return attrs;
}

/* Paints something: a colour, generated content, or an animation. */
function decorates(d, vars) {
  if (Colors.declarationColours(d.decl.prop, d.decl.value, vars).length > 0) return true;
  if (d.decl.prop === 'content') return true;
  return /^animation(-name)?$/.test(d.decl.prop) && !/^none$/i.test(d.decl.value.trim());
}

/* The allowlists of one theme, as compareToGolden takes them. */
function allowedFor(themeId) {
  const allow = Colors.allowlist();
  return {
    gone: Object.keys(allow.goneOnPurpose.panel[themeId] || {}),
    added: Object.keys(allow.newOnPurpose.panel[themeId] || {}),
    changed: Object.keys(allow.changedOnPurpose.panel[themeId] || {})
  };
}

for (const themeId of REWRITTEN) {
  test(camel(themeId) + 'MatchesItsGolden', () => {
    assert.deepEqual(Colors.compareToGolden(goldenOf('panel')[themeId], Colors.panelCss(), themeId, allowedFor(themeId)),
      []);
  });
}

for (const themeId of REWRITTEN) {
  test(camel(themeId) + 'DecorationsFollowTheThemesColours', () => {
    // with another palette (a custom theme: its colours inline on <html>, and the custom-theme rules), every decoration
    // colour changes, but for plain black and white (shadows, masks, selected text) and see-through ones
    const css = Colors.panelCss();
    const customRules = {};
    panelDeclarations().filter((d) => d.rule.at.length === 0 &&
      d.selector === 'html[data-custom-theme][data-theme="' + themeId + '"]').forEach((d) => {
      customRules[d.decl.prop] = d.decl.value;
    });
    const inline = ThemePalette.cssVars(ThemePalette.generate('#3b82f6', '#0b1020', 'dark'));
    const block = 'html[data-theme="' + themeId + '"]|';
    const stuck = [];
    let checked = 0;
    for (const e of Colors.decorationColours(css, themeId)) {
      if (e.key.startsWith(block)) continue;
      const before = Colors.evaluate(e.expr, e.vars);
      if (before[3] === 0) continue;
      if (before.slice(0, 3).every((v) => v === 0) || before.slice(0, 3).every((v) => v === 255)) continue;
      checked++;
      const after = Colors.evaluate(e.expr, Object.assign({}, e.vars, customRules, inline));
      const readThroughASlot = e.via.some((name) => Object.prototype.hasOwnProperty.call(inline, name));
      if (Colors.sameColour(Colors.format(before), after) && !readThroughASlot) stuck.push(e.key + ': ' + e.expr);
    }
    assert.ok(checked >= (themeId === 'revival' ? 80 : 20), 'checked ' + checked);
    assert.deepEqual(stuck, [], 'colours a custom theme can\'t reach');
  });
}

/*
 * Pieces the panel doesn't have: they are the mini-player's only (the panel's version of the theme never had them):
 * Cipher's "> " prompt and blinking caret, Neon's glowing title and progress bar.
 */
const MINI_ONLY = { cipher: ['prompt'], neon: ['title'] };

/*
 * Where a theme's decorations (its custom-theme rules' too) aren't each under exactly one of its pieces, right after its
 * html part; and variables set anywhere but its bare blocks.
 */
function pieceGuardProblems(css, themeId, ids) {
  const vars = Colors.themeVars(css, themeId);
  const blocks = bareBlocks(themeId);
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
    if (guards.mentions === 0 && !decorates(d, vars)) continue;
    const head = Colors.splitFirst(d.selector).head.replace(/::?[-\w]+$/, '');
    if (guards.mentions !== 1 || guards.ids.length !== 1) {
      problems.push(at + d.spaced + ' { ' + d.decl.prop + ' }: ' + guards.ids.length + ' guards');
    } else if (!blocks.includes(head) || !d.spaced.startsWith(head + ':not([data-deco-off~="' + guards.ids[0] + '"])')) {
      problems.push(at + d.spaced + ': the guard belongs right after ' + head);
    }
  }
  return { problems: [...new Set(problems)], used: used };
}

for (const themeId of REWRITTEN) {
  test('every' + capitalized(themeId) + 'DecorationRuleIsUnderOnePiece', () => {
    const found = pieceGuardProblems(Colors.panelCss(), themeId, pieceIds(themeId));
    assert.deepEqual(found.problems, []);
    const miniOnly = MINI_ONLY[themeId] || [];
    assert.deepEqual([...found.used].sort(), pieceIds(themeId).filter((id) => !miniOnly.includes(id)).sort(),
      'every piece of the panel has rules');
  });
}

/*
 * What an unguarded rule of a decorated theme may set: the theme's shape, which is not a piece (spec §3) and stays with
 * every piece off. Type (font, font-*, letter-spacing, word-spacing, line-height, text-transform, text-align,
 * text-overflow, white-space, overflow), corners (border-*radius), sizes and spacing (width, height, min-/max-,
 * margin*, padding*, gap, row-gap, column-gap), flex layout (flex-wrap, align-content, justify-content),
 * scrollbar-width, and a border taken away (border, border-top/right/bottom/left: none or 0). Nothing on a
 * pseudo-element: in a decorated theme that is a decoration's own layer, all of it the piece's. Besides the shape: the
 * variables of its bare blocks (the theme block, the custom-theme block), and animation: none where motion is reduced.
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
  if (Colors.subjectOf(d.selector).pseudo) return false;
  return SHAPE.test(prop) || (BORDER_TAKEN_AWAY.test(prop) && /^(none|0)$/i.test(value));
}

/*
 * Layout effects of a piece that the shape rule alone would let pass (they are shape properties), pinned to their
 * piece: [piece, normalized selector, properties]. Signal's caption sits in a gutter under the header's content: with
 * the caption off the header is the panel's own, no empty gap; its HUD rule under a pane's header has room around it
 * that goes with it. Victory's gold rule takes the header border's place: with the rule off the border is back.
 * Neon's drag bar is a 4px lime rail: with the rails off it is the panel's own drag bar.
 */
const LAYOUT_EFFECTS = {
  signal: [
    ['caption', 'html[data-theme="signal"] .header', ['min-height', 'padding-bottom', 'align-items', 'padding-top']],
    ['hud', 'html[data-theme="signal"] .pane-header-row', ['padding-bottom', 'margin-bottom']],
    ['hud', 'html[data-theme="signal"] .pane-header-with-actions', ['padding-bottom', 'margin-bottom']]
  ],
  victory: [['rule', 'html[data-theme="victory"] .header', ['border-bottom']]],
  neon: [['rails', 'html[data-theme="neon"] .layout-pane.card > .pane-drag-bar',
    ['border-bottom', 'min-height', 'padding-top', 'padding-bottom']]]
};

/*
 * What keeps a theme's pieces from switching off cleanly (its custom-theme rules' too): a declaration under a guard
 * that isn't one piece's, doesn't apply with every piece on, applies with every piece off, or doesn't follow its own
 * piece alone; an unguarded one that isn't the theme's shape; a pinned layout effect that isn't under its piece. Every
 * declaration of a guarded rule counts (its layout effects too).
 */
function piecesOffProblems(decls, themeId, ids, effects) {
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
    } else if (!isShape(d, themeId)) {
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

test('allPiecesOffLeavesNoDecoration', () => {
  for (const themeId of REWRITTEN) {
    const found = piecesOffProblems(panelDeclarations(), themeId, pieceIds(themeId), LAYOUT_EFFECTS[themeId]);
    assert.ok(found.guarded >= 20, themeId + ': ' + found.guarded + ' declarations under a piece');
    assert.deepEqual(found.problems, [], themeId);
  }
});

test('thePiecesOffCheckSeesALeftover', () => {
  const css = [
    'html[data-theme="x"] { --accent: #222222; --pad: 4px; }',
    'html[data-theme="x"]:not([data-deco-off~="a"]) .header::before { content: "HI"; position: absolute; color: var(--accent); }',
    'html[data-theme="x"] .header { padding-bottom: 18px; font-size: 1rem; border-bottom: none; border-radius: 2px; }',
    'html[data-theme="x"] .header { position: relative; border-top: 1px solid; }',
    'html[data-theme="x"] .card::after { width: 2px; }',
    'html[data-theme="x"] .card { --pad: 8px; }',
    'html[data-reduce-motion][data-theme="x"] .card { animation: none !important; }',
    'html[data-theme="x"] .badge { animation: none; }',
    'html[data-theme="x"]:not([data-deco-off~="a"]):not([data-deco-off~="b"]) .logo { color: red; }',
    'html[data-custom-theme][data-theme="x"] { --glow: var(--accent); }',
    'html[data-custom-theme][data-theme="x"] .track-title { text-shadow: 0 0 12px var(--accent); }',
    'html[data-custom-theme][data-theme="x"] .card { --pad: 2px; }',
    'html[data-custom-theme][data-theme="x"]:not([data-deco-off~="b"]) .logo { color: blue; }',
    'html[data-custom-theme][data-theme="y"] .logo { color: green; }'
  ].join('\n');
  const found = piecesOffProblems(Colors.declarations(css), 'x', ['a', 'b'],
    [['a', 'html[data-theme="x"] .header', ['padding-bottom', 'border-bottom']]]);
  assert.deepEqual(found.problems, [
    'html[data-theme="x"] .header { position: relative }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"] .header { border-top: 1px solid }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"] .card::after { width: 2px }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"] .card { --pad: 8px }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"] .badge { animation: none }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"]:not([data-deco-off~="a"]):not([data-deco-off~="b"]) .logo { color: red }: under 2 guards',
    'html[data-custom-theme][data-theme="x"] .track-title { text-shadow: 0 0 12px var(--accent) }: not under a piece, ' +
      'and not the theme\'s shape',
    'html[data-custom-theme][data-theme="x"] .card { --pad: 2px }: not under a piece, and not the theme\'s shape',
    'html[data-theme="x"] .header { padding-bottom }: belongs to a',
    'html[data-theme="x"] .header { border-bottom }: belongs to a'
  ]);
  assert.equal(found.guarded, 5);
});

test('aCustomThemeRuleIsCheckedForPiecesToo', () => {
  // a rule that only shows on a custom theme over Neon is still Neon's: it can't paint without a piece
  const probe = '\nhtml[data-custom-theme][data-theme="neon"] .track-title { text-shadow: 0 0 12px var(--accent); }\n';
  const css = Colors.panelCss() + probe;
  const where = 'html[data-custom-theme][data-theme="neon"] .track-title';
  assert.deepEqual(pieceGuardProblems(css, 'neon', pieceIds('neon')).problems, [where + ' { text-shadow }: 0 guards']);
  assert.deepEqual(piecesOffProblems(Colors.declarations(css), 'neon', pieceIds('neon'), LAYOUT_EFFECTS.neon).problems,
    [where + ' { text-shadow: 0 0 12px var(--accent) }: not under a piece, and not the theme\'s shape']);
  const guarded = probe.replace('[data-theme="neon"]', '[data-theme="neon"]:not([data-deco-off~="glows"])');
  assert.deepEqual(pieceGuardProblems(Colors.panelCss() + guarded, 'neon', pieceIds('neon')).problems, []);
  assert.deepEqual(pieceGuardProblems(Colors.panelCss() + '\nhtml[data-custom-theme][data-theme="neon"] .logo { --x: red; }\n',
    'neon', pieceIds('neon')).problems, ['html[data-custom-theme][data-theme="neon"] .logo { --x }: a variable below html']);
});

/* Plain black, plain white, or nothing: a colour no theme gives a piece. */
function neutral(c) {
  const rgb = Colors.format(c).match(/\d+/g).slice(0, 3).map(Number);
  return c[3] === 0 || rgb.every((v) => v === 0) || rgb.every((v) => v === 255);
}

/*
 * The theme block's own colour variables (not a slot a custom theme sets, and not neutral) that a rule reads without a
 * piece guard (a shared rule, or the theme's own): a colour that stays whatever the pieces, and in a custom theme in
 * the built-in theme's colours.
 */
function ownColourReads(css, themeId) {
  const slots = new Set(Object.keys(ThemePalette.cssVars(ThemePalette.generate('#3b82f6', '#0b1020', 'dark'))));
  const own = new Set();
  const decls = Colors.declarations(css);
  for (const d of decls) {
    if (d.selector !== 'html[data-theme="' + themeId + '"]' || !d.decl.prop.startsWith('--') || slots.has(d.decl.prop)) {
      continue;
    }
    const vars = Colors.themeVars(css, themeId, d.rule.at);
    const colours = Colors.declarationColours('background', d.decl.value, vars);
    if (colours.some((e) => !neutral(Colors.evaluate(e, vars)))) own.add(d.decl.prop);
  }
  const vars = Colors.themeVars(css, themeId);
  const states = [{ 'data-theme': themeId }, { 'data-theme': themeId, 'data-custom-theme': 'x' },
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

test('onlyAPieceReadsTheThemesOwnColourVariables', () => {
  // an accent ring kept in --card-shadow is painted by the panel's own .card rule, whatever the pieces
  for (const themeId of REWRITTEN) assert.deepEqual(ownColourReads(Colors.panelCss(), themeId), [], themeId);
  const sheet = [
    'html[data-theme="x"] { --accent: #222222; --ring: 0 0 0 1px var(--accent); --shade: 0 4px 8px rgba(0, 0, 0, 0.3);',
    '  --pale: rgba(255, 255, 255, 0.1); --glint: color-mix(in srgb, var(--accent) 10%, white); }',
    '.card { box-shadow: var(--ring), var(--shade); }',
    '.tile { box-shadow: var(--shade), inset 0 1px 0 var(--pale); border-color: var(--accent); }',
    'html[data-theme="x"]:not([data-deco-off~="a"]) .card { box-shadow: var(--ring), 0 0 2px var(--glint); }',
    'html[data-theme="x"] .badge { color: var(--glint); }',
    'html[data-theme="y"] .card { color: var(--glint); }'
  ].join('\n');
  assert.deepEqual(ownColourReads(sheet, 'x'), ['.card { box-shadow } reads --ring',
    'html[data-theme="x"] .badge { color } reads --glint']);
});

test('cascadeFlipsAreRestoredOrListed', () => {
  // a guard weighs one attribute: a guarded rule can now beat a panel rule that beat it before (the hover colour of a
  // switched-on repeat button); each such rule is given back by the theme under the same piece, or listed with a reason
  const listed = Colors.allowlist().flipsOnPurpose.panel;
  const problems = [];
  const flips = Colors.cascadeFlips(Colors.panelCss(), REWRITTEN);
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

test('theFlipCheckCatchesTheRepeatButtonHover', () => {
  const css = Colors.panelCss();
  const restore = /html\[data-theme="revival"\]:not\(\[data-deco-off~="glows"\]\) \.ctrl-btn\.repeat-active:hover,\s*html\[data-theme="revival"\]:not\(\[data-deco-off~="glows"\]\) \.ctrl-btn\.shuffle-active:hover\s*\{[^}]*\}/;
  assert.match(css, restore, 'Revival gives the hover colour back');
  const key = (state) => 'html[data-theme="revival"] .ctrl-btn|color <- .ctrl-btn.' + state + '-active:hover|color';
  const flips = (sheet) => Colors.cascadeFlips(sheet, ['revival']).filter((f) => /\.ctrl-btn\.\w+-active:hover/.test(f.key));
  assert.deepEqual(flips(css).map((f) => [f.key, f.restored]), [[key('repeat'), true], [key('shuffle'), true]]);
  assert.deepEqual(flips(css.replace(restore, '')).map((f) => [f.key, f.restored]),
    [[key('repeat'), false], [key('shuffle'), false]]);
  // a restoring rule has to beat the guarded one and carry the panel's own value
  const wrong = css.replace(restore, (rule) => rule.replace(/color:\s*var\(--accent-light\)/, 'color: var(--accent)'));
  assert.deepEqual(flips(wrong).map((f) => f.restored), [false, false]);
});

test('theFlipCheckReadsSubjectsAndFamilies', () => {
  // T, guarded under a: (0,2,1) without its guard, (0,3,1) with it. Every panel rule below lies in that band (or ties
  // (0,2,1) and comes later), so it beat T before and loses to it now, unless it isn't T's element or property.
  const t = 'html[data-theme="x"]:not([data-deco-off~="a"]) .ctrl-btn { color: #111111; border: 1px solid #222222; }';
  const sheet = [
    t,
    '.ctrl-btn.repeat-active:hover { color: var(--accent-light); }', // (0,3,0): a flip
    'nav button[aria-pressed="true"]:hover { color: red; }', // (0,2,2), a button named by its tag alone: not .ctrl-btn
    'div.card .ctrl-btn::after { color: green; }', // (0,2,2), the ::after layer: another element
    '.card .ctrl-btn:hover { border-color: blue; }', // (0,3,0), border family: a flip
    '.ctrl-btn.is-on:hover { color: var(--accent); }', // (0,3,0): given back under b, not a, so not restored
    'html[data-theme="x"]:not([data-deco-off~="b"]) .ctrl-btn.is-on:hover { color: var(--accent); }',
    '.ctrl-btn.is-lit:hover { color: var(--accent); }', // (0,3,0): given back under a, restored
    'html[data-theme="x"]:not([data-deco-off~="a"]) .ctrl-btn.is-lit:hover { color: var(--accent); }',
    '@media (max-width: 640px) { .ctrl-btn.small:hover { color: blue; } }', // given back outside its @media: not restored
    'html[data-theme="x"]:not([data-deco-off~="a"]) .ctrl-btn.small:hover { color: blue; }',
    '@media (max-width: 640px) { .ctrl-btn.tiny:hover { color: blue; } }', // given back in its @media: restored
    '@media (max-width: 640px) { html[data-theme="x"]:not([data-deco-off~="a"]) .ctrl-btn.tiny:hover { color: blue; } }',
    'html[data-custom-theme] .ctrl-btn { color: red; }', // (0,2,1), later: on a custom theme, a flip; given back
    'html[data-theme="x"][data-custom-theme]:not([data-deco-off~="a"]) .ctrl-btn { color: red; }',
    'html:not([data-theme]) .ctrl-btn { color: pink; }', // (0,2,1), later, but never with theme x on
    '.lyrics-line.tappable:hover:focus { background: blue; }', // (0,4,0) against the line's (0,3,1)..(0,4,1): a flip
    'html[data-theme="x"]:not([data-deco-off~="a"]) .lyrics-line.active { background: #444444; }',
    'html[data-theme="x"] #deck .ctrl-btn { color: #333333; }' // (1,2,1): above every guard, it still wins
  ].join('\n');
  const keys = Colors.cascadeFlips(sheet, ['x']).map((f) => f.key + (f.restored ? ' (restored)' : ''));
  const T = 'html[data-theme="x"] .ctrl-btn|';
  assert.deepEqual(keys, [
    T + 'color <- .ctrl-btn.repeat-active:hover|color',
    T + 'color <- .ctrl-btn.is-on:hover|color',
    T + 'color <- .ctrl-btn.is-lit:hover|color (restored)',
    T + 'color <- @media (max-width:640px) .ctrl-btn.small:hover|color',
    T + 'color <- @media (max-width:640px) .ctrl-btn.tiny:hover|color (restored)',
    T + 'color <- html[data-custom-theme] .ctrl-btn|color (restored)',
    T + 'border <- .card .ctrl-btn:hover|border-color',
    'html[data-theme="x"] .lyrics-line.active|background <- .lyrics-line.tappable:hover:focus|background'
  ]);
  assert.equal(Colors.propertyFamily('border-bottom-color'), 'border');
  assert.equal(Colors.propertyFamily('border-top-left-radius'), 'border-radius');
  assert.equal(Colors.propertyFamily('background-image'), 'background');
  assert.equal(Colors.propertyFamily('-webkit-mask-image'), 'mask');
  assert.equal(Colors.propertyFamily('color'), 'color');
});

/* The animations of these themes and the shared bgShift, and what doesn't stop them under each reduced-motion hook. */
function motionProblems(themeIds) {
  const all = panelDeclarations();
  const isAnimation = (d) => d.decl.prop === 'animation' || d.decl.prop === 'animation-name';
  const stops = (d) => isAnimation(d) && /^none$/i.test(d.decl.value.trim());
  // where a declaration lands: the theme its html part names (null: any) and the element after it
  const target = (d) => {
    const parts = Colors.splitFirst(d.selector);
    const m = /^(html|:root)(?![-\w])(.*?)(::[-\w]+)?$/.exec(parts.head);
    if (!m) return { html: '', theme: null, subject: ' ' + d.selector };
    const theme = /\[data-theme="([a-z-]+)"\]/.exec(m[2]);
    return { html: m[2], theme: theme ? theme[1] : null, subject: (m[3] || '') + parts.rest };
  };
  const wins = (o, a) => (o.decl.important !== a.decl.important ? o.decl.important
    : (o.spec[0] - a.spec[0] || o.spec[1] - a.spec[1] || o.spec[2] - a.spec[2] || o.order - a.order) > 0);
  const animated = all.filter((d) => isAnimation(d) && !stops(d) &&
    (themeIds.includes(d.theme) || (d.theme === null && /\bbgShift\b/.test(d.decl.value))));
  const problems = [];
  for (const a of animated) {
    const t = target(a);
    if (a.rule.at.length) problems.push(a.spaced + ': an animation inside ' + a.rule.at.join(' '));
    const covers = (o, hook) => {
      const ot = target(o);
      if (ot.subject !== t.subject || (ot.theme !== null && ot.theme !== t.theme)) return false;
      const rest = ot.html.replace(/\[data-theme="[a-z-]+"\]/, '');
      if (hook === 'attribute') return o.rule.at.length === 0 && rest === '[data-reduce-motion]';
      return o.rule.at.length === 1 && o.rule.at[0] === '@media (prefers-reduced-motion:reduce)' && rest === '';
    };
    for (const hook of ['attribute', 'media']) {
      const stoppers = all.filter((o) => stops(o) && covers(o, hook));
      if (!stoppers.some((o) => wins(o, a))) {
        problems.push((hook === 'attribute' ? 'html[data-reduce-motion]' : '@media (prefers-reduced-motion: reduce)') +
          ' doesn\'t stop ' + a.spaced + ' { ' + a.decl.prop + ': ' + a.decl.value + ' }');
      }
    }
  }
  return { animated: animated, problems: problems };
}

test('neonTextUsesTheTextSlot', () => {
  // Neon forced its text to #ffffff in 8 rules, over a custom theme's own text colour (white text on a light page);
  // it is the text slot now, and the only other colours of text are the panel's own hover colours given back
  const text = ['.card-title', '.pane-header-row .card-title', '.pane-header-with-actions .card-title',
    '.playlist-header .card-title', '.logo', '.lyrics-line', '.lyrics-line.active', '.track-title', '.now-playing',
    '.queue-item', '.history-item', '.search-result-item', '.ctrl-btn', '.guild-selector label', '.login-box h2'];
  const given = ['.ctrl-btn.repeat-active:hover', '.ctrl-btn.shuffle-active:hover'];
  const neon = panelDeclarations().filter((d) => d.theme === 'neon');
  const colours = neon.filter((d) => d.decl.prop === 'color');
  const subject = (d) => d.selector.replace(/^html\[data-theme="neon"\] /, '');
  assert.deepEqual(colours.map(subject).sort(), text.concat(given).sort());
  const light = ThemePalette.generate('#3b82f6', '#f8fafc', 'light');
  const vars = Object.assign({}, Colors.themeVars(Colors.panelCss(), 'neon'), ThemePalette.cssVars(light));
  for (const d of colours) {
    if (given.includes(subject(d))) {
      assert.equal(d.decl.value, 'var(--accent-light)', subject(d) + ': the panel\'s own hover colour');
      continue;
    }
    assert.equal(d.decl.value, 'var(--text-primary)', subject(d));
    assert.equal(Colors.format(Colors.evaluate(d.decl.value, vars)), Colors.format(Colors.evaluate(light.textPrimary)),
      subject(d) + ': a custom theme\'s own text colour');
  }
  assert.equal(new Set(colours.filter((d) => text.includes(subject(d))).map((d) => d.rule)).size, 8, 'the 8 rules');
  const white = /#fff(fff)?\b|(^|[^-\w])white\b|rgba?\(\s*255\s*,\s*255\s*,\s*255\b/i;
  assert.deepEqual(neon.filter((d) => !d.decl.prop.startsWith('--') && white.test(d.decl.value))
    .map((d) => d.spaced + ' { ' + d.decl.prop + ' }'), [], 'no white of its own but its text slot');
});

test('reducedMotionStopsEveryAnimatedPiece', () => {
  const found = motionProblems(REWRITTEN);
  const names = new Set(found.animated.flatMap((d) => d.decl.value.match(/[A-Za-z][\w-]*/g)
    .filter((w) => /[A-Z]/.test(w) && /^[a-z]/.test(w))));
  assert.deepEqual([...names].sort(),
    ['bgShift', 'revivalFloat', 'revivalGlowPulse', 'revivalRays', 'revivalRaysIn', 'revivalSpin', 'revivalSunrise']);
  assert.deepEqual(found.problems, []);
});

test('revivalImagesCarryNoColour', () => {
  // Revival's film grain and its logo's star are masks: what shows through them is painted with the theme's colours
  const images = [];
  for (const d of panelDeclarations()) {
    if (d.theme !== 'revival') continue;
    for (const m of d.decl.value.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^"'\s)]*))\s*\)/g)) {
      const link = [m[1], m[2], m[3]].find((g) => g !== undefined);
      assert.match(link, /^data:image\/svg\+xml,/, d.spaced + ': a picture drawn here');
      images.push({ selector: d.spaced, prop: d.decl.prop, svg: decodeURIComponent(link) });
    }
  }
  assert.ok(images.some((i) => /feTurbulence/.test(i.svg) && /::after$/.test(i.selector)), 'the grain');
  assert.ok(images.some((i) => /<polygon/.test(i.svg) && /\.logo-icon::before$/.test(i.selector)), 'the star');
  for (const image of images) {
    const where = image.selector + ' { ' + image.prop + ' }';
    assert.match(image.prop, /^(-webkit-)?mask(-image)?$/, where + ': a mask, not a picture');
    assert.ok(!/<(feColorMatrix|feFlood|feComponentTransfer|feDiffuseLighting|feSpecularLighting|linearGradient|radialGradient)\b/i
      .test(image.svg), where + ': nothing in it makes a colour');
    assert.ok(!/\bstyle\s*=/.test(image.svg), where + ': no style attribute');
    for (const m of image.svg.matchAll(/([-\w:]+)\s*=\s*(["'])([^"']*)\2/g)) {
      assert.ok(!/^(fill|stroke|stop-color|flood-color|lighting-color|color)$/i.test(m[1]), where + ': ' + m[1]);
      if (m[1] === 'xmlns') continue;
      assert.throws(() => Colors.evaluate(m[3]), Error, where + ': ' + m[1] + '="' + m[3] + '" is a colour');
    }
  }
});

test('everyThemeBlockDefinesItsDecoExtra', () => {
  const css = Colors.panelCss();
  const own = new Set();
  panelDeclarations().forEach((d) => {
    if (d.rule.at.length === 0 && d.decl.prop === '--deco-extra' &&
      (d.spaced === ':root' || /^html\[data-theme="[a-z-]+"\]$/.test(d.spaced))) own.add(d.spaced === ':root' ? 'nebula' : d.theme);
  });
  for (const id of ThemeApply.BUILT_IN) {
    assert.ok(own.has(id), id + ' sets --deco-extra in its own block');
    assert.equal(Colors.themeVars(css, id)['--deco-extra'], PIECES[id].decoExtra, id);
  }
});
