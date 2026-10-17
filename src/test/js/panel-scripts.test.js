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
  assert.ok(scripts.some((code) => code.includes('function applyCustomTheme(')), 'the theme code is among them');
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

/**
 * [{line, codePoint}] for every literal mark, embedding, override or isolate that changes text direction: U+061C,
 * U+200E/F, U+202A-202E, U+2066-2069 (as the desktop guard in desktop/ui/mini-player/view.test.js).
 */
function literalBidiControls(text) {
  const found = [];
  text.split(String.fromCharCode(10)).forEach((line, i) => {
    for (const ch of line) {
      const c = ch.codePointAt(0);
      if (c === 0x061C || c === 0x200E || c === 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
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
  // the Arabic letter mark changes direction too, as the desktop guard knows
  assert.deepEqual(literalBidiControls('x' + String.fromCharCode(0x061C)), [{ line: 1, codePoint: 'U+061C' }]);
});

/* The source of a function of the panel (from "function name(" to its closing brace). */
function panelFunction(name) {
  const html = PANEL.replace(/\r/g, '');
  const at = html.indexOf('function ' + name + '(');
  assert.ok(at >= 0, name + ' not found');
  let depth = 0;
  for (let i = html.indexOf('{', at); i < html.length; i++) {
    if (html[i] === '{') depth++;
    else if (html[i] === '}' && --depth === 0) return html.slice(at, i + 1);
  }
  throw new Error(name + ' never closes');
}

function fakeRoot() {
  const attrs = {};
  const has = (n) => Object.prototype.hasOwnProperty.call(attrs, n);
  return {
    setAttribute: (n, v) => { attrs[n] = String(v); },
    removeAttribute: (n) => { delete attrs[n]; },
    hasAttribute: has,
    toggleAttribute: (n, force) => {
      const on = force === undefined ? !has(n) : !!force;
      if (on) attrs[n] = '';
      else delete attrs[n];
      return on;
    }
  };
}

test('theReduceMotionSwitchSetsTheHookOnThePage', () => {
  const head = inlineScripts(PANEL.replace(/\r/g, ''))[0];
  // the tiny script in the head runs before the first paint, so nothing moves even for a moment
  for (const [saved, system, expected] of [['1', false, true], ['0', true, false], [null, true, true], [null, false, false]]) {
    const root = fakeRoot();
    vm.runInNewContext(head, {
      localStorage: { getItem: (k) => (k === 'jmusicbot_reduce_motion_v1' ? saved : null), setItem: () => {} },
      document: { documentElement: root },
      window: { matchMedia: () => ({ matches: system }) }
    });
    assert.equal(root.hasAttribute('data-reduce-motion'), expected, 'head script: saved ' + saved + ', system ' + system);
  }
  // the switch in the Panels menu, and the page as it starts
  const root = fakeRoot();
  const store = {};
  const context = vm.createContext({
    reduceMotion: null,
    REDUCE_MOTION_STORAGE_KEY: 'jmusicbot_reduce_motion_v1',
    localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } },
    window: { matchMedia: () => ({ matches: false }) },
    document: { documentElement: root },
    themeBackdropData: null,
    themeKit: null,
    themeCreator: null
  });
  ['readReduceMotion', 'setReduceMotion', 'applyReduceMotionAttribute'].forEach((name) => {
    vm.runInContext(panelFunction(name), context);
  });
  vm.runInContext('setReduceMotion(true)', context);
  assert.equal(root.hasAttribute('data-reduce-motion'), true, 'on');
  vm.runInContext('setReduceMotion(false)', context);
  assert.equal(root.hasAttribute('data-reduce-motion'), false, 'off');
  store.jmusicbot_reduce_motion_v1 = '1';
  vm.runInContext('reduceMotion = null; applyReduceMotionAttribute()', context);
  assert.equal(root.hasAttribute('data-reduce-motion'), true, 'from what was saved');
  assert.match(panelFunction('initTheme'), /applyReduceMotionAttribute\(\)/, 'the page applies it as it starts');
});
