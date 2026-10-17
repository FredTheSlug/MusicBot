'use strict';
/*
 * The built-in themes' shapes, kept as they were before their decorations were rewritten (the stylesheets of dd330df):
 * every other value a theme's own rules set (geometry, opacity, blur, sizes, positions, layers, animations and their
 * @keyframes, z-index, display, ...), with the colours taken out. The colours are theme-golden.test.js's; this is the
 * rest of the look. src/test/resources/theme-golden/panel-shapes.json and mini-shapes.json are written by
 * scripts/theme-golden.js from dd330df; the differences the rewrite made on purpose are listed, each with its reason and
 * the value it is pinned to, in allowlist.json (shapeGoneOnPurpose, shapeNewOnPurpose, shapeChangedOnPurpose).
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const Colors = require('./css-colors.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');

const ROOT = path.join(__dirname, '../../..');

// ---- the shape of one value

test('aShapeValueKeepsTheGeometryAndTakesTheColoursOut', () => {
  const vars = { '--accent': '#e2825c', '--glow': '0 0 24px rgba(226, 130, 92, 0.4)', '--edge': 'var(--accent)' };
  const v = (prop, value) => Colors.shapeValue(prop, value, vars);
  // every colour form is one <c>: hex, rgb()/rgba(), hsl(), color-mix(), a named colour, a var() holding one colour
  assert.equal(v('box-shadow', '0 0 0 1px #fff, 0 0 20px rgba(226, 130, 92, 0.4)'), '0 0 0 1px <c>, 0 0 20px <c>');
  assert.equal(v('background', 'linear-gradient(90deg,color-mix(in srgb, var(--accent) 20%, transparent) 0%,transparent 70%)'),
    'linear-gradient(90deg, <c> 0%, <c> 70%)');
  assert.equal(v('border', '1px solid var(--edge)'), '1px solid <c>');
  assert.equal(v('filter', 'drop-shadow( 0 0 8px hsl(20 50% 50% / 0.5) )'), 'drop-shadow(0 0 8px <c>)');
  // a variable that holds more than a colour is a shape of its own (pinned where it is set)
  assert.equal(v('box-shadow', 'var(--glow),  inset 0 1px 0 #ffffff14'), 'var(--glow), inset 0 1px 0 <c>');
  // a colour alone is all colour
  assert.equal(v('color', '#f6efe5'), '<c>');
  assert.equal(v('background', 'transparent'), '<c>');
  // spacing one way, numbers and units as written, pictures and names as written
  assert.equal(v('transform', 'translate( -50%,-50% )   rotate(-8deg)'), 'translate(-50%, -50%) rotate(-8deg)');
  assert.equal(v('opacity', '.6'), '.6');
  assert.equal(v('mask', 'url("data:image/svg+xml,%3Csvg fill=\'%23ff0000\'/%3E") 0 0 / 180px  180px'),
    'url("data:image/svg+xml,%3Csvg fill=\'%23ff0000\'/%3E") 0 0 / 180px 180px');
  assert.equal(v('animation', 'tan 3s  infinite'), 'tan 3s infinite', 'an animation called tan is no colour');
  assert.equal(v('font-family', "'Fraunces',Georgia, serif"), "'Fraunces', Georgia, serif");
  assert.equal(v('content', '"\\2726  red"'), '"\\2726  red"', 'a string as written');
  assert.equal(v('width', 'calc(var(--rv-disc) - 2px)'), 'calc(var(--rv-disc) - 2px)', 'an unknown variable as written');
});

// ---- a theme's shapes, and keys that survive a rewrite

const SAMPLE = [
  ':root { --accent: #7c5cfc; --accent-r: 124; --accent-g: 92; --accent-b: 252; --radius: 12px; }',
  'html[data-theme="x"] { --accent: #e2825c; --accent-r: 226; --accent-g: 130; --accent-b: 92;',
  '  --glow: 0 0 24px rgba(226, 130, 92, 0.4); --blur: 10px; }',
  'html[data-theme="x"] .card,',
  'html[data-theme="x"]   .header > .bar::before {',
  '  background: linear-gradient(90deg, rgba(242, 179, 94, 0.2) 0%, transparent 70%), url("data:image/svg+xml,%3Csvg/%3E");',
  '  box-shadow: var(--glow), 0 0 0 1px var(--accent) !important;',
  '  color: #f6efe5;',
  '  opacity: 0.6;',
  '  animation: xGlow 3s infinite;',
  '}',
  'html[data-theme="y"] .card { width: 10px; }',
  'html[data-custom-theme][data-theme="x"] .card { opacity: 1; }',
  '@media (min-width:1280px) { html[data-theme="x"] .badge { inset: 0 auto auto 0; transform: rotate(-8deg) scale(1.1); } }',
  '@keyframes xGlow { 0%,100% { box-shadow: 0 0 0 rgba(226, 130, 92, 0); } 50% { box-shadow: 0 0 24px rgba(226, 130, 92, 0.4); transform: scale(1.02); } }',
  '@keyframes yGlow { to { opacity: 0; } }'
].join('\n');

/* The same look after a rewrite: piece guards, other spacing and order, slot expressions instead of literal colours. */
const SAMPLE_REWRITTEN = [
  ':root { --accent: #7c5cfc; --accent-r: 124; --accent-g: 92; --accent-b: 252; --radius: 12px; }',
  '@keyframes xGlow { 0%, 100% { box-shadow: 0 0 0 transparent; } 50% { transform: scale(1.02); box-shadow: 0 0 24px color-mix(in srgb, var(--accent) 40%, transparent); } }',
  'html[data-theme="x"] { --accent: #e2825c; --accent-light: #f2a882; --accent-r: 226; --accent-g: 130; --accent-b: 92;',
  '  --glow: 0 0 24px color-mix(in srgb, var(--accent) 40%, transparent); --blur: 10px; --accent2-r: 1; --deco-extra: #fde047; }',
  'html[data-theme="x"]:not([data-deco-off~="glows"]) .header>.bar::before, html[data-theme="x"]:not([data-deco-off~="glows"]) .card {',
  '  opacity: 0.6; animation: xGlow 3s infinite; color: var(--accent-light);',
  '  background: linear-gradient(90deg, color-mix(in srgb, var(--accent-light) 20%, transparent) 0%, transparent 70%), url("data:image/svg+xml,%3Csvg/%3E");',
  '  box-shadow: var(--glow), 0 0 0 1px rgba(var(--accent-r), var(--accent-g), var(--accent-b), 1) !important; }',
  '@media (min-width: 1280px) { html[data-theme="x"]:not([data-deco-off~="badge"]) .badge { transform: rotate(-8deg)  scale(1.1); inset: 0 auto auto 0; } }'
].join('\n');

test('shapesAreEveryOtherValueOfTheThemesRulesAndSurviveARewrite', () => {
  assert.deepEqual(Colors.shapes(SAMPLE, 'x'), {
    'html[data-theme="x"]|--glow': '0 0 24px <c>',
    'html[data-theme="x"]|--blur': '10px',
    'html[data-theme="x"] .card|background': 'linear-gradient(90deg, <c> 0%, <c> 70%), url("data:image/svg+xml,%3Csvg/%3E")',
    'html[data-theme="x"] .card|box-shadow': 'var(--glow), 0 0 0 1px <c> !important',
    'html[data-theme="x"] .card|opacity': '0.6',
    'html[data-theme="x"] .card|animation': 'xGlow 3s infinite',
    'html[data-theme="x"] .header > .bar::before|background':
      'linear-gradient(90deg, <c> 0%, <c> 70%), url("data:image/svg+xml,%3Csvg/%3E")',
    'html[data-theme="x"] .header > .bar::before|box-shadow': 'var(--glow), 0 0 0 1px <c> !important',
    'html[data-theme="x"] .header > .bar::before|opacity': '0.6',
    'html[data-theme="x"] .header > .bar::before|animation': 'xGlow 3s infinite',
    '@media (min-width:1280px) html[data-theme="x"] .badge|inset': '0 auto auto 0',
    '@media (min-width:1280px) html[data-theme="x"] .badge|transform': 'rotate(-8deg) scale(1.1)',
    '@keyframes xGlow 0%, 100%|box-shadow': '0 0 0 <c>',
    '@keyframes xGlow 50%|box-shadow': '0 0 24px <c>',
    '@keyframes xGlow 50%|transform': 'scale(1.02)'
  }, 'colours alone, the accent triples, :root (Nebula\'s), y, the custom-theme rule and an unused @keyframes are not x\'s shapes');
  assert.deepEqual(Colors.shapes(SAMPLE, 'y'), { 'html[data-theme="y"] .card|width': '10px' });
  const golden = Colors.shapes(SAMPLE, 'x');
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE_REWRITTEN, 'x'), [],
    'guards, spacing, order, slot expressions, new colour variables and triples keep every shape');
  // the same declaration twice: the one the browser takes with every piece on (a guard weighs one attribute)
  const twice = 'html[data-theme="x"]:not([data-deco-off~="a"]) .card { opacity: 0.6; }\nhtml[data-theme="x"] .card { opacity: 1; }';
  assert.deepEqual(Colors.shapes(twice, 'x'), { 'html[data-theme="x"] .card|opacity': '0.6' });
});

test('aChangedGoneOrNewShapeIsReportedUnlessListed', () => {
  const golden = Colors.shapes(SAMPLE, 'x');
  const card = 'html[data-theme="x"] .card|';
  // a gradient stop, an opacity, a blur, a keyframe: each is reported with what it was and is
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE.replace('transparent 70%', 'transparent 60%'), 'x'), [
    'changed: ' + card + 'background: was linear-gradient(90deg, <c> 0%, <c> 70%), url("data:image/svg+xml,%3Csvg/%3E"), ' +
      'now linear-gradient(90deg, <c> 0%, <c> 60%), url("data:image/svg+xml,%3Csvg/%3E")',
    'changed: html[data-theme="x"] .header > .bar::before|background: was linear-gradient(90deg, <c> 0%, <c> 70%), ' +
      'url("data:image/svg+xml,%3Csvg/%3E"), now linear-gradient(90deg, <c> 0%, <c> 60%), url("data:image/svg+xml,%3Csvg/%3E")'
  ]);
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE.replace('--blur: 10px', '--blur: 12px'), 'x'),
    ['changed: html[data-theme="x"]|--blur: was 10px, now 12px']);
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE.replace('transform: scale(1.02)', 'transform: scale(1.2)'), 'x'),
    ['changed: @keyframes xGlow 50%|transform: was scale(1.02), now scale(1.2)']);
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE.replace('box-shadow: var(--glow), 0 0 0 1px var(--accent) !important;',
    'box-shadow: var(--glow), 0 0 0 1px var(--accent);'), 'x').length, 2, 'importance is part of the shape');
  // a declaration gone, and one new (a new variable too: it is a shape until a rule reads it)
  const gone = SAMPLE.replace('@media (min-width:1280px) { html[data-theme="x"] .badge { inset: 0 auto auto 0; ', '@media (min-width:1280px) { html[data-theme="x"] .badge { ');
  assert.deepEqual(Colors.compareShapes(golden, gone, 'x'),
    ['gone: @media (min-width:1280px) html[data-theme="x"] .badge|inset (was 0 auto auto 0)']);
  const added = SAMPLE.replace('--blur: 10px;', '--blur: 10px; --spin: 4s;') + '\nhtml[data-theme="x"] .card::after { filter: blur(2px); }';
  assert.deepEqual(Colors.compareShapes(golden, added, 'x'),
    ['new: html[data-theme="x"]|--spin (4s)', 'new: html[data-theme="x"] .card::after|filter (blur(2px))']);
  // listed on purpose: gone, new and changed ones are pinned to what they are now
  assert.deepEqual(Colors.compareShapes(golden, gone, 'x', { gone: ['@media (min-width:1280px) html[data-theme="x"] .badge|inset'] }), []);
  assert.deepEqual(Colors.compareShapes(golden, added, 'x',
    { added: { 'html[data-theme="x"]|--spin': '4s', 'html[data-theme="x"] .card::after|filter': 'blur(2px)' } }), []);
  assert.deepEqual(Colors.compareShapes(golden, added, 'x',
    { added: { 'html[data-theme="x"]|--spin': '5s', 'html[data-theme="x"] .card::after|filter': 'blur(2px)' } }),
  ['changed: html[data-theme="x"]|--spin: listed as new with 5s, now 4s']);
  const blur12 = SAMPLE.replace('--blur: 10px', '--blur: 12px');
  assert.deepEqual(Colors.compareShapes(golden, blur12, 'x', { changed: { 'html[data-theme="x"]|--blur': '12px' } }), []);
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE.replace('--blur: 10px', '--blur: 14px'), 'x',
    { changed: { 'html[data-theme="x"]|--blur': '12px' } }),
  ['changed: html[data-theme="x"]|--blur: listed as changed to 12px, now 14px']);
  // a list that doesn't fit what is there
  assert.deepEqual(Colors.compareShapes(golden, SAMPLE, 'x', {
    gone: [card + 'opacity', card + 'nope'],
    added: { [card + 'width']: '1px', [card + 'animation']: 'xGlow 3s infinite' },
    changed: { [card + 'opacity']: '1', 'html[data-theme="x"]|--blur': '10px', [card + 'height']: '1px' }
  }), [
    'listed as changed but the same: html[data-theme="x"]|--blur',
    'listed as gone but still there: ' + card + 'opacity',
    'listed as gone but not golden: ' + card + 'nope',
    'listed as changed and as gone: ' + card + 'opacity',
    'listed as changed but not golden: ' + card + 'height',
    'listed as new but not there: ' + card + 'width',
    'listed as new but golden: ' + card + 'animation'
  ]);
  assert.deepEqual(Colors.compareShapes(golden, gone, 'x',
    { changed: { '@media (min-width:1280px) html[data-theme="x"] .badge|inset': '0' } }),
  ['listed as changed but gone: @media (min-width:1280px) html[data-theme="x"] .badge|inset']);
});

// ---- the golden shapes: the stylesheets of dd330df

/* A copy of a stylesheet with `from` replaced by `to` at its first place after `after` (which must be there). */
function edit(css, after, from, to) {
  const at = css.indexOf(after);
  assert.ok(at >= 0, 'not in the stylesheet: ' + after);
  const i = css.indexOf(from, at);
  assert.ok(i >= 0, 'not after ' + after + ': ' + from);
  return css.slice(0, i) + to + css.slice(i + from.length);
}

test('shapeGoldenIsTodaysStylesheet', () => {
  for (const kind of ['panel', 'mini']) {
    const problems = Colors.checkShapes(kind);
    assert.deepEqual(problems, [], kind + ': ' + problems.length + ' shape(s) differ from the golden file');
  }
});

test('shapeGoldenCoversEveryThemeAndItsKeyframes', () => {
  let total = 0;
  for (const kind of ['panel', 'mini']) {
    const golden = Colors.shapeGoldenFile(kind);
    assert.deepEqual(Object.keys(golden), [...ThemeApply.BUILT_IN], kind);
    for (const id of ThemeApply.BUILT_IN) {
      assert.ok(Object.keys(golden[id]).length > 0, kind + ' ' + id);
      for (const [key, value] of Object.entries(golden[id])) {
        assert.equal(typeof value, 'string', key);
        assert.notEqual(value, '<c>', key + ': a colour alone is the colour golden\'s');
        assert.ok(!/^--accent2?-[rgb]$/.test(key.split('|').pop()), key + ': the accent triples are colours');
      }
      total += Object.keys(golden[id]).length;
    }
  }
  const panel = Colors.shapeGoldenFile('panel');
  const keyframes = (id, name) => Object.keys(panel[id]).filter((k) => k.startsWith('@keyframes ' + name + ' '));
  assert.ok(Object.keys(panel.revival).length >= 200, 'revival: ' + Object.keys(panel.revival).length);
  for (const name of ['revivalSunrise', 'revivalRays', 'revivalRaysIn', 'revivalSpin']) {
    assert.ok(keyframes('revival', name).length > 0, 'revival @keyframes ' + name);
  }
  assert.ok(keyframes('ember', 'emberFlameDecalFlicker').length > 0, 'ember @keyframes emberFlameDecalFlicker');
  assert.ok(Object.keys(Colors.shapeGoldenFile('mini').revival).some((k) => k.startsWith('@keyframes spin ')), 'mini record');
  assert.ok(total >= 1500, 'declarations snapshotted: ' + total);
});

test('shapeGoldenIsTheOriginalStylesheets', (t) => {
  // written from dd330df, the last commit before the rewrite (a shallow clone hasn't got it: then this is skipped)
  try {
    execFileSync('git', ['cat-file', '-e', Colors.ORIGINAL + '^{commit}'], { cwd: ROOT, stdio: 'ignore' });
  } catch (e) {
    t.skip('git has no ' + Colors.ORIGINAL + ' here');
    return;
  }
  for (const kind of ['panel', 'mini']) {
    const css = Colors.originalCss(kind);
    const golden = Colors.shapeGoldenFile(kind);
    for (const id of ThemeApply.BUILT_IN) assert.deepEqual(golden[id], Colors.shapes(css, id), kind + ' ' + id);
    // and the colour golden is the same commit's
    const colours = Colors.goldenFile(kind);
    for (const id of ThemeApply.BUILT_IN) assert.deepEqual(colours[id], Colors.snapshot(css, id), kind + ' ' + id + ' colours');
  }
});

test('theShapeCheckSeesTheEditsTheColourCheckCant', () => {
  // the final review's list: Revival's rays moved, the record shrunk, a gradient stop, a blur radius, an opacity, a
  // keyframe, a declaration deleted; and in the mini-player the rays' angle and the sunrise layer's depth
  const panel = Colors.panelCss();
  const rays = 'html[data-theme="revival"]:not([data-deco-off~="rays"]) body::after {';
  const raysKey = 'revival: changed: html[data-theme="revival"] body::after|';
  const cases = [
    [edit(panel, rays, 'left: 50%;', 'left: 30%;'), [raysKey + 'left: was 50%, now 30%']],
    [edit(panel, rays, 'revivalRays 300s', 'revivalRays 200s'), [raysKey + 'animation: ']],
    [edit(panel, 'html[data-theme="revival"] {', '--rv-disc: 212px', '--rv-disc: 180px'),
      ['revival: changed: html[data-theme="revival"]|--rv-disc: was 212px, now 180px']],
    [edit(panel, rays, 'mask-image: radial-gradient(circle closest-side, rgba(0, 0, 0, 0.95) 0%, rgba(0, 0, 0, 0.4) 30%',
      'mask-image: radial-gradient(circle closest-side, rgba(0, 0, 0, 0.95) 0%, rgba(0, 0, 0, 0.4) 40%'),
    [raysKey + '-webkit-mask-image: ']],
    [edit(panel, '.np-glow {', 'filter: blur(70px)', 'filter: blur(40px)'),
      ['revival: changed: html[data-theme="revival"] .np-glow|filter: was blur(70px), now blur(40px)']],
    [edit(panel, 'html[data-theme="revival"]:not([data-deco-off~="glows"]) .layout-pane.card > .pane-drag-bar {', 'opacity: 0.7;',
      'opacity: 0.5;'),
    ['revival: changed: html[data-theme="revival"] .layout-pane.card > .pane-drag-bar|opacity: was 0.7, now 0.5']],
    [edit(panel, '@keyframes revivalRays {', 'rotate(360deg)', 'rotate(180deg)'),
      ['revival: changed: @keyframes revivalRays to|transform: was translate(-50%, -50%) rotate(360deg), ' +
        'now translate(-50%, -50%) rotate(180deg)']],
    [edit(panel, rays, 'width: 240vmax;', ''), ['revival: gone: html[data-theme="revival"] body::after|width (was 240vmax)']]
  ];
  for (const [css, expected] of cases) {
    const problems = Colors.checkShapes('panel', css);
    assert.equal(problems.length, expected.length, problems.join('\n'));
    expected.forEach((start, i) => assert.ok(problems[i].startsWith(start), problems[i] + '\n  expected ' + start));
  }
  const mini = Colors.miniCss();
  const miniRays = Colors.checkShapes('mini', mini.replace('repeating-conic-gradient(from 0deg at 0% 100%', 'repeating-conic-gradient(from 10deg at 0% 100%'));
  assert.equal(miniRays.length, 1, miniRays.join('\n'));
  assert.ok(miniRays[0].startsWith('revival: changed: html[data-theme="revival"] .mini::before|background: '), miniRays[0]);
  const sunrise = Colors.checkShapes('mini', edit(mini, 'html[data-theme="revival"]:not([data-deco-off~="sunrise"]) .mini::after {',
    'z-index: -2;', 'z-index: 0;'));
  assert.equal(sunrise.length, 1, sunrise.join('\n'));
  assert.ok(sunrise[0].startsWith('revival: changed: html[data-theme="revival"] .mini::after|z-index: listed as new with -2, now 0'),
    sunrise[0]);
});

test('theShapeAllowlistsSayWhyAndPinWhatTheyAllow', () => {
  const allow = Colors.allowlist();
  for (const name of ['shapeGoneOnPurpose', 'shapeNewOnPurpose', 'shapeChangedOnPurpose']) {
    assert.deepEqual(Object.keys(allow[name]), ['panel', 'mini'], name);
    for (const kind of ['panel', 'mini']) {
      const golden = Colors.shapeGoldenFile(kind);
      for (const [theme, entries] of Object.entries(allow[name][kind])) {
        assert.ok(ThemeApply.BUILT_IN.includes(theme), name + ' ' + kind + ': ' + theme);
        for (const [key, entry] of Object.entries(entries)) {
          const where = name + ' ' + kind + ' ' + theme + ' ' + key;
          let why = entry;
          if (name !== 'shapeGoneOnPurpose') {
            // a new or changed shape is listed with the value it has now, so it stays pinned
            assert.deepEqual(Object.keys(entry), ['why', 'is'], where);
            assert.equal(typeof entry.is, 'string', where);
            why = entry.why;
          }
          assert.ok(typeof why === 'string' && why.trim().length >= 10, where + ': say why');
          assert.equal(Object.prototype.hasOwnProperty.call(golden[theme], key), name !== 'shapeNewOnPurpose', where);
        }
      }
    }
  }
  // the script checks and writes the shapes too, from the original stylesheets
  const script = fs.readFileSync(path.join(ROOT, 'scripts/theme-golden.js'), 'utf8');
  assert.match(script, /Colors\.checkShapes\(/);
  assert.match(script, /Colors\.originalCss\(/);
  assert.match(script, /Colors\.shapes\(/);
  assert.equal(Colors.ORIGINAL, 'dd330df');
});
