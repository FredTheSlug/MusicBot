#!/usr/bin/env node
'use strict';
/*
 * Writes the built-in themes' golden files, read by src/test/js/theme-golden.test.js, theme-golden-mini.test.js and
 * theme-shape-golden.test.js, for the panel (index.html) and the mini-player (mini.css + themes.css):
 * - panel.json and mini.json, the decoration colours: { theme: { key: "rgba(r,g,b,a)" } };
 * - panel-shapes.json and mini-shapes.json, every other value of the themes' rules (geometry, opacity, blur, sizes,
 *   positions, layers, animations and their @keyframes, ...), colours taken out: { theme: { key: value } }.
 * See src/test/js/css-colors.js for what a key is and what counts.
 *
 * The golden files are the record of how the themes looked before their decorations were rewritten: they are written
 * from the stylesheets of that commit (Colors.ORIGINAL, dd330df, read with git show), never from today's, which would
 * make the tests agree with whatever the rewrite did. The script won't overwrite a file that is there unless asked to
 * (--force); written again, the files come out the same.
 *
 * Excluded from the colours: inside url(). Colours drawn inside pictures (Ember's flame SVGs, Sumi's kanji tile,
 * Revival's grain and star) are not stylesheet colours; the pictures are listed below each theme's count. The shapes
 * keep url() as written.
 *
 * Keys gone, new or changed on purpose are listed, each with its reason (and a new or changed shape with its value), in
 * src/test/resources/theme-golden/allowlist.json; --check and the tests both read it.
 *
 *   node scripts/theme-golden.js           write the golden files (only those that aren't there yet)
 *   node scripts/theme-golden.js --check   compare today's stylesheets with the golden files (and the allowlist)
 *   node scripts/theme-golden.js --force   write them over the existing ones
 */
const fs = require('node:fs');
const path = require('node:path');
const Colors = require('../src/test/js/css-colors.js');
const ThemeApply = require('../src/main/resources/web/theme-apply.js');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'src/test/resources/theme-golden');
const KINDS = ['panel', 'mini'];

const args = process.argv.slice(2);
const check = args.includes('--check');
const force = args.includes('--force');

function write(file, out) {
  if (fs.existsSync(file) && !force) {
    console.log(path.relative(ROOT, file) + ' is there already; not written (--force writes it over)');
    return;
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out, null, 2) + '\n', 'utf8');
  console.log('wrote ' + path.relative(ROOT, file));
}

let problems = 0;
for (const kind of KINDS) {
  if (check) {
    Colors.checkSheet(kind).concat(Colors.checkShapes(kind)).forEach((p) => {
      problems++;
      console.log(kind + ' ' + p);
    });
    continue;
  }
  const css = Colors.originalCss(kind);
  const colours = {};
  const shapes = {};
  for (const theme of ThemeApply.BUILT_IN) {
    colours[theme] = Colors.snapshot(css, theme);
    shapes[theme] = Colors.shapes(css, theme);
    const seen = Colors.inspect(css, theme);
    console.log(kind + ' ' + theme + ': ' + Object.keys(colours[theme]).length + ' colours, ' +
      Object.keys(shapes[theme]).length + ' shapes' +
      (seen.urls.length ? '; excluded from the colours, inside url(): ' + seen.urls.join(', ') : ''));
    if (seen.unresolved.length) throw new Error(theme + ': undefined variables ' + seen.unresolved.join(', '));
  }
  write(path.join(OUT, kind + '.json'), colours);
  write(path.join(OUT, kind + '-shapes.json'), shapes);
}
if (check) {
  console.log(problems === 0 ? 'the stylesheets match the golden colours and shapes' : problems + ' difference(s)');
  process.exitCode = problems === 0 ? 0 : 1;
}
