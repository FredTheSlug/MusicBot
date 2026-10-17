#!/usr/bin/env node
'use strict';
/*
 * Writes the built-in themes' golden decoration colours: src/test/resources/theme-golden/panel.json (the panel's
 * index.html) and mini.json (the mini-player's mini.css + themes.css), { theme: { key: "rgba(r,g,b,a)" } }, read by
 * src/test/js/theme-golden.test.js. See src/test/js/css-colors.js for what a key is and which colours count.
 *
 * The golden files are the record of how the themes looked before their decorations were rewritten (2026-10-17);
 * writing them again from a rewritten stylesheet would make the test agree with whatever the rewrite did. So the
 * script won't overwrite them unless asked to (--force).
 *
 * Excluded: inside url(). Colours drawn inside pictures (Ember's flame SVGs, Sumi's kanji tile, Revival's grain and
 * star) are not stylesheet colours; the pictures are listed below each theme's count.
 *
 * Keys gone on purpose and new decoration keys added on purpose are listed, each with its reason, in
 * src/test/resources/theme-golden/allowlist.json; --check and the test both read it.
 *
 *   node scripts/theme-golden.js           write the golden files (only if they aren't there yet)
 *   node scripts/theme-golden.js --check   compare today's stylesheets with the golden files (and the allowlist)
 *   node scripts/theme-golden.js --force   write them over the existing ones
 */
const fs = require('node:fs');
const path = require('node:path');
const Colors = require('../src/test/js/css-colors.js');
const ThemeApply = require('../src/main/resources/web/theme-apply.js');

const ROOT = path.join(__dirname, '..');
const OUT = path.join(ROOT, 'src/test/resources/theme-golden');
const SHEETS = { panel: Colors.panelCss(), mini: Colors.miniCss() };

const args = process.argv.slice(2);
const check = args.includes('--check');
const force = args.includes('--force');

let problems = 0;
for (const kind of Object.keys(SHEETS)) {
  const file = path.join(OUT, kind + '.json');
  const css = SHEETS[kind];
  if (check) {
    Colors.checkSheet(kind, css).forEach((p) => {
      problems++;
      console.log(kind + ' ' + p);
    });
    continue;
  }
  const out = {};
  for (const theme of ThemeApply.BUILT_IN) {
    out[theme] = Colors.snapshot(css, theme);
    const seen = Colors.inspect(css, theme);
    console.log(kind + ' ' + theme + ': ' + Object.keys(out[theme]).length + ' colours' +
      (seen.urls.length ? '; excluded, inside url(): ' + seen.urls.join(', ') : ''));
    if (seen.unresolved.length) throw new Error(theme + ': undefined variables ' + seen.unresolved.join(', '));
  }
  if (fs.existsSync(file) && !force) {
    console.log(path.relative(ROOT, file) + ' is there already; not written (--force writes it over)');
    continue;
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out, null, 2) + '\n', 'utf8');
  console.log('wrote ' + path.relative(ROOT, file));
}
if (check) {
  console.log(problems === 0 ? 'the stylesheets match the golden colours' : problems + ' difference(s)');
  process.exitCode = problems === 0 ? 0 : 1;
}
