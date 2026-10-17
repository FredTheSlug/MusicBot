'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { panelFunction } = require('./panel-source.js');

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

/* <html> with attributes and an inline style, as far as the theme code uses them. */
function fakeHtml() {
  const root = fakeRoot();
  const attrs = {};
  const props = {};
  root.setAttribute = (n, v) => { attrs[n] = String(v); };
  root.removeAttribute = (n) => { delete attrs[n]; };
  root.hasAttribute = (n) => Object.prototype.hasOwnProperty.call(attrs, n);
  root.getAttribute = (n) => (root.hasAttribute(n) ? attrs[n] : null);
  root.style = {
    setProperty: (n, v) => { props[n] = String(v); },
    removeProperty: (n) => { delete props[n]; },
    getPropertyValue: (n) => props[n] || ''
  };
  root.attrs = attrs;
  root.props = props;
  return root;
}

const PIECES = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/web/theme-pieces.json'), 'utf8'));
const PICTURE = '/theme-images/' + '9f2c'.repeat(8) + '.webp';

/* The panel's own theme functions, run against a fake page with the real ThemeApply and ThemePalette. */
function themePage(pieces) {
  const root = fakeHtml();
  const backdrops = [];
  const context = vm.createContext({
    document: { documentElement: root },
    window: { ThemePalette: require('../../main/resources/web/theme-palette.js') },
    themeKit: require('../../main/resources/web/theme-apply.js'),
    themePieces: pieces,
    themeLocalPicture: null,
    customApplied: [],
    customThemes: { list: null, activeId: '', activeName: '', activeJson: '' },
    themeShown: 'nebula',
    themeBase: '',
    getComputedStyle: () => ({ getPropertyValue: () => '12px' }),
    setThemeBackdrop: (back) => backdrops.push(back)
  });
  ['setBaseThemeAttribute', 'setCustomProperty', 'clearCustomTheme', 'applyCustomTheme'].forEach((name) => {
    vm.runInContext(panelFunction(name), context);
  });
  return {
    root: root,
    backdrops: backdrops,
    apply: (theme) => { context.theme = theme; vm.runInContext('applyCustomTheme(theme)', context); },
    clear: () => vm.runInContext('clearCustomTheme()', context)
  };
}

function customTheme(extra) {
  return Object.assign({
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: { id: '1', name: 'fred' }, visibility: 'public', hiddenByOwner: false,
    mine: true, version: 2, base: 'revival', decorationsOff: ['rays', 'record'],
    colors: {
      bgPrimary: '#0b0d14', bgSecondary: '#10131c', bgCard: '#161a26', bgElevated: '#1c2131', bgHover: '#262c42',
      accent: '#1e3a8a', accentLight: '#a08bff', accent2: '#22d3ee', textPrimary: '#eef0f8', textSecondary: '#9aa0b8',
      textTertiary: '#646a82', border: '#ffffff12', borderLight: '#ffffff1f', success: '#34d399', glass: '#10131ce0',
      decoExtra: '#dc2626'
    },
    radiusScale: 100, font: 'Outfit', background: null, mini: null
  }, extra);
}

test('applyCustomThemeSetsDecoOff', () => {
  const page = themePage(PIECES);
  page.apply(customTheme());
  assert.equal(page.root.getAttribute('data-theme'), 'revival');
  assert.equal(page.root.getAttribute('data-custom-theme'), 'k3j9x0q2m7ab');
  assert.equal(page.root.getAttribute('data-deco-off'), 'rays record');
  assert.equal(page.root.hasAttribute('data-custom-picture'), false);
  assert.equal(page.root.props['--deco-extra'], '#dc2626');
  assert.equal(page.root.props['--on-accent'], '#ffffff', 'white on the dark blue accent');

  // a picture: the background pieces go too, and the page knows there is a picture (the ambient glow goes)
  page.apply(customTheme({ background: { url: PICTURE, dim: 40, blur: 0, fit: 'cover' } }));
  assert.equal(page.root.getAttribute('data-deco-off'), 'rays record sunrise');
  assert.equal(page.root.hasAttribute('data-custom-picture'), true);
  assert.equal(page.backdrops[page.backdrops.length - 1].url, PICTURE);

  // every piece on and no picture: no attribute at all, nothing left from the theme before
  page.apply(customTheme({ base: 'ember', decorationsOff: [] }));
  assert.equal(page.root.hasAttribute('data-deco-off'), false);
  assert.equal(page.root.hasAttribute('data-custom-picture'), false);
  assert.equal(page.root.getAttribute('data-theme'), 'ember');

  // back to a built-in look: everything a custom theme set is taken back
  page.apply(customTheme({ background: { url: PICTURE, dim: 40, blur: 0, fit: 'cover' } }));
  page.clear();
  for (const name of ['data-deco-off', 'data-custom-picture', 'data-custom-theme']) {
    assert.equal(page.root.hasAttribute(name), false, name);
  }
  assert.deepEqual(Object.keys(page.root.props), []);

  // the old plain switch is gone for good
  assert.equal(PANEL.includes('data-custom-plain'), false);
  assert.equal(PANEL.includes('theme.effects'), false);
});

test('theCreatorPreviewSetsDecoOff', () => {
  // the creator's live preview goes through applyCustomTheme like a saved theme: its pieces reach <html data-deco-off>
  const TC = require('../../main/resources/web/theme-creator.js');
  const root = fakeHtml();
  const backdrops = [];
  const context = vm.createContext({
    document: { documentElement: root },
    window: { ThemePalette: require('../../main/resources/web/theme-palette.js'), ThemeCreator: TC },
    themeKit: require('../../main/resources/web/theme-apply.js'),
    themePieces: PIECES,
    themeLocalPicture: null,
    customApplied: [],
    customThemes: { list: null, activeId: '', activeName: '', activeJson: '' },
    themeShown: 'nebula',
    themeBase: '',
    themeCreator: null,
    clearTimeout: clearTimeout,
    getComputedStyle: () => ({ getPropertyValue: () => '12px' }),
    setThemeBackdrop: (back) => backdrops.push(back)
  });
  ['setBaseThemeAttribute', 'setCustomProperty', 'clearCustomTheme', 'applyCustomTheme', 'tcPreviewNow'].forEach((name) => {
    vm.runInContext(panelFunction(name), context);
  });
  const preview = (state) => {
    context.s = { state: state, blobUrl: '', aside: '', previewTimer: 0 };
    vm.runInContext('themeCreator = s; tcPreviewNow(s)', context);
  };
  // Fred's case: Revival with Nebula's colors, the record on and the rays off
  const fred = TC.useColoursFrom(TC.setPiece(TC.startState('revival'), 'rays', false, PIECES), 'nebula');
  preview(fred);
  assert.equal(root.getAttribute('data-theme'), 'revival');
  assert.equal(root.getAttribute('data-deco-off'), 'rays');
  assert.equal(root.props['--accent'], TC.BASE_PALETTES.nebula.colors.accent);
  assert.equal(root.props['--deco-extra'], TC.BASE_PALETTES.nebula.colors.decoExtra);
  assert.equal(context.themeShown, 'nebula', 'the rest of the page keeps believing in the real theme');
  // a picture: the background pieces go on the page, the saved list stays as it is
  const withPicture = Object.assign({}, fred, { picture: 'saved', savedPictureUrl: PICTURE });
  preview(withPicture);
  assert.equal(root.getAttribute('data-deco-off'), 'rays sunrise');
  assert.equal(root.hasAttribute('data-custom-picture'), true);
  assert.equal(backdrops[backdrops.length - 1].url, PICTURE);
  assert.deepEqual(withPicture.decorationsOff, ['rays']);
  // every piece on, no picture: no attribute
  preview(TC.allPieces(fred, true, PIECES));
  assert.equal(root.hasAttribute('data-deco-off'), false);
  preview(TC.allPieces(fred, false, PIECES));
  assert.equal(root.getAttribute('data-deco-off'), PIECES.revival.pieces.map((p) => p.id).join(' '));
});

test('withoutThePiecesListACustomThemeKeepsItsSavedPiecesOff', () => {
  const page = themePage(null);
  page.apply(customTheme({ background: { url: PICTURE, dim: 40, blur: 0, fit: 'cover' } }));
  assert.equal(page.root.getAttribute('data-deco-off'), 'rays record');
  assert.equal(page.root.hasAttribute('data-custom-picture'), true, 'the ambient glow still goes under the picture');
});

test('aPiecesListThatFailedIsAskedForAgainByThePollAndThenApplied', async () => {
  // /api/themes answers but theme-pieces.json does not: the picture theme shows with only its saved pieces off. The
  // next poll asks for the list again and applies the theme anew with it, so the background pieces go.
  const root = fakeHtml();
  const piecesAnswers = [() => Promise.reject(new Error('offline')),
    () => Promise.resolve({ ok: true, json: () => Promise.resolve(PIECES) })];
  const asked = [];
  const theme = customTheme({ background: { url: PICTURE, dim: 40, blur: 0, fit: 'cover' } });
  const context = vm.createContext({
    Promise: Promise,
    JSON: JSON,
    document: { documentElement: root },
    window: { ThemePalette: require('../../main/resources/web/theme-palette.js') },
    themeKit: require('../../main/resources/web/theme-apply.js'),
    themePieces: null,
    themePiecesLoading: null,
    themeLocalPicture: null,
    themeLoadSeq: 0,
    themeChoice: 'custom:' + theme.id,
    themeActedOn: '',
    customApplied: [],
    customThemes: { list: null, me: null, limit: null, hidden: [], activeId: '', activeName: '', activeJson: '' },
    themeShown: 'revival',
    themeBase: 'revival',
    fetch: (url) => { asked.push(url); return piecesAnswers.shift()(); },
    authFetch: () => Promise.resolve({ ok: true }),
    readApiJson: () => Promise.resolve({ themes: [theme], me: { id: '1', isOwner: false }, limit: { max: 10, used: 1 } }),
    getComputedStyle: () => ({ getPropertyValue: () => '12px' }),
    setThemeBackdrop: () => {},
    rebuildThemePicker: () => {},
    syncThemePicker: () => {},
    storeThemeChoice: () => {},
    readStoredThemeBase: () => 'revival',
    themeCreatorHoldsPage: () => false,
    themeCreator: null,
    plToast: () => {}
  });
  ['setBaseThemeAttribute', 'setCustomProperty', 'clearCustomTheme', 'applyCustomTheme', 'applyThemeChoice',
    'reconcileThemeChoice', 'loadThemePieces', 'retryThemePieces'].forEach((name) => {
    vm.runInContext(panelFunction(name), context);
  });
  assert.match(PANEL, /async function loadCustomThemes\(/);
  vm.runInContext('async ' + panelFunction('loadCustomThemes'), context);
  await vm.runInContext('loadCustomThemes()', context);
  assert.equal(root.getAttribute('data-custom-theme'), theme.id);
  assert.equal(root.getAttribute('data-deco-off'), 'rays record', 'the list failed: the saved pieces only');
  assert.equal(context.themePieces, null);

  await vm.runInContext('retryThemePieces()', context);
  assert.deepEqual(asked, ['/web/theme-pieces.json', '/web/theme-pieces.json']);
  assert.equal(root.getAttribute('data-deco-off'), 'rays record sunrise', 'the picture now has the background pieces off');

  // once the list is here, the poll asks for nothing more
  await vm.runInContext('retryThemePieces()', context);
  assert.equal(asked.length, 2);
  // and the poll is what calls it, while the list of themes is in but the pieces list is not
  assert.match(panelFunction('loadGuilds'), /retryThemePieces\(\)/);
});

test('thePanelLoadsThePiecesListOnceAndAgainAfterAFailure', async () => {
  const answers = [() => Promise.reject(new Error('offline')), () => Promise.resolve({ ok: false }),
    () => Promise.resolve({ ok: true, json: () => Promise.resolve(PIECES) })];
  const asked = [];
  const context = vm.createContext({
    themePieces: null,
    themePiecesLoading: null,
    Promise: Promise,
    fetch: (url) => { asked.push(url); return answers.shift()(); }
  });
  vm.runInContext(panelFunction('loadThemePieces'), context);
  assert.equal(await vm.runInContext('loadThemePieces()', context), null);
  assert.equal(await vm.runInContext('loadThemePieces()', context), null);
  const loaded = await vm.runInContext('loadThemePieces()', context);
  assert.deepEqual(loaded, PIECES);
  assert.deepEqual(context.themePieces, PIECES);
  await vm.runInContext('loadThemePieces()', context);
  assert.deepEqual(asked, ['/web/theme-pieces.json', '/web/theme-pieces.json', '/web/theme-pieces.json'], 'once it is here, no more asking');
  // two at once share one request
  const shared = vm.createContext({ themePieces: null, themePiecesLoading: null, Promise: Promise,
    fetch: () => { asked.push('again'); return Promise.resolve({ ok: true, json: () => Promise.resolve(PIECES) }); } });
  vm.runInContext(panelFunction('loadThemePieces'), shared);
  await vm.runInContext('Promise.all([loadThemePieces(), loadThemePieces()])', shared);
  assert.equal(asked.filter((u) => u === 'again').length, 1);
  // the list of custom themes waits for it, so a theme is first applied with its pieces
  const load = panelFunction('loadCustomThemes');
  assert.match(load, /loadThemePieces\(\)/);
  assert.ok(load.indexOf('await pieces') < load.indexOf('customThemes.list ='), 'pieces before the list is used');
});
