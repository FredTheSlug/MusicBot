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
 * reviewed, in src/test/resources/theme-golden/allowlist.json (read by this test and by scripts/theme-golden.js).
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
  assert.deepEqual(Object.keys(allow), ['goneOnPurpose', 'newOnPurpose']);
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
