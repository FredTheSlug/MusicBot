'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const PANEL = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8');

/** The panel's inline scripts (the ones written in the page, not loaded from /web/…). */
function inlineScripts(html) {
  const found = [];
  const pattern = /<script>([\s\S]*?)<\/script>/g;
  let match;
  while ((match = pattern.exec(html)) !== null) found.push(match[1]);
  return found;
}

test('everyInlineScriptOfThePanelParses', () => {
  // a stray quote in one string breaks the whole panel, and no other test would notice
  const scripts = inlineScripts(PANEL);
  assert.ok(scripts.length >= 2, 'found the panel scripts');
  scripts.forEach((code, i) => {
    assert.doesNotThrow(() => new vm.Script(code, { filename: 'index.html inline script ' + i }));
  });
});

test('theCheckCatchesABrokenString', () => {
  // the kind of slip it is there for: an apostrophe inside a single-quoted string
  assert.throws(() => new vm.Script("var x = '<div>Couldn't upload</div>';"), SyntaxError);
});

/** The panel's own sources: its scripts, its page and the Node tests that read them. */
function panelSourceFiles() {
  const web = path.join(__dirname, '../../main/resources/web');
  const pick = (dir, keep) => fs.readdirSync(dir).filter(keep).map((name) => path.join(dir, name));
  return pick(web, (name) => name.endsWith('.js') || name === 'index.html')
    .concat(pick(__dirname, (name) => name.endsWith('.js')));
}

/** [{line, codePoint}] for every literal mark, embedding, override or isolate that changes text direction. */
function literalBidiControls(text) {
  const found = [];
  text.split(String.fromCharCode(10)).forEach((line, i) => {
    for (const ch of line) {
      const c = ch.codePointAt(0);
      if (c === 0x200E || c === 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
        found.push({ line: i + 1, codePoint: 'U+' + c.toString(16).toUpperCase().padStart(4, '0') });
      }
    }
  });
  return found;
}

test('noLiteralBidiControlsInPanelSources', () => {
  // invisible characters in code can make it read differently from what it does (and from what a reviewer sees)
  const root = path.join(__dirname, '../..');
  const problems = [];
  panelSourceFiles().forEach((file) => {
    literalBidiControls(fs.readFileSync(file, 'utf8')).forEach((hit) => {
      problems.push(path.relative(root, file).split(path.sep).join('/') + ':' + hit.line + ': ' + hit.codePoint);
    });
  });
  assert.deepEqual(problems, [], 'write these as unicode escapes in the source, not as the characters themselves');
});

test('theBidiCheckSeesACharacterItIsThereFor', () => {
  const rlo = String.fromCharCode(0x202E);
  const isolate = String.fromCharCode(0x2068);
  assert.deepEqual(literalBidiControls('fine' + String.fromCharCode(10) + 'a' + rlo + 'b' + isolate),
    [{ line: 2, codePoint: 'U+202E' }, { line: 2, codePoint: 'U+2068' }]);
  assert.deepEqual(literalBidiControls('plain text, and a middle dot: ' + String.fromCharCode(0xB7)), []);
});
