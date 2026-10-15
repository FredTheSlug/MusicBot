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
