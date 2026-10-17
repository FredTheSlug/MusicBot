'use strict';
/*
 * The theme creator's mini-player preview (step 5): the desktop app's own card in a frame of its own
 * (web/mini-preview.html), given the version by the app's own view.js as the 0.7.0 app gives it. The panel's own
 * functions run here under vm, against a fake page and a fake frame whose page has the real view.js.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { panelFunction } = require('./panel-source.js');
const TC = require('../../main/resources/web/theme-creator.js');
const ThemeApply = require('../../main/resources/web/theme-apply.js');
const MV = require('../../../desktop/ui/mini-player/view.js');

const WEB = path.join(__dirname, '../../main/resources/web');
const MINI_PLAYER = path.join(__dirname, '../../../desktop/ui/mini-player');
const read = (file) => fs.readFileSync(file, 'utf8').replace(/\r/g, '');
const PANEL = read(path.join(WEB, 'index.html'));
const PREVIEW = read(path.join(WEB, 'mini-preview.html'));
const APP = read(path.join(MINI_PLAYER, 'index.html'));
const MINI_CSS = read(path.join(MINI_PLAYER, 'mini.css'));
const PIECES = JSON.parse(read(path.join(WEB, 'theme-pieces.json')));
const PICTURE = '/theme-images/' + '3a4b'.repeat(8) + '.jpg';
const MOVING_PICTURE = '/theme-images/' + '9f2c'.repeat(8) + '.gif';
const FIRST_FRAME = 'data:image/png;base64,AAAA';

const tick = () => new Promise((resolve) => setImmediate(resolve));

// ---- a fake page, as far as the creator's step and the app's view.js use one

function fakeElement(tag) {
  const attrs = Object.create(null);
  const props = Object.create(null);
  const classes = new Set();
  const el = {
    tagName: tag.toUpperCase(), parentNode: null, children: [], listeners: Object.create(null), attrs: attrs, props: props,
    hidden: false, textContent: '', src: '',
    get className() { return [...classes].join(' '); },
    set className(value) {
      classes.clear();
      String(value).split(/\s+/).filter(Boolean).forEach((c) => classes.add(c));
    },
    classList: {
      add: (...names) => names.forEach((c) => classes.add(c)),
      remove: (...names) => names.forEach((c) => classes.delete(c)),
      contains: (c) => classes.has(c),
      toggle: (c, force) => {
        const on = force === undefined ? !classes.has(c) : !!force;
        if (on) classes.add(c);
        else classes.delete(c);
        return on;
      }
    },
    setAttribute: (n, v) => { attrs[n] = String(v); },
    getAttribute: (n) => (n in attrs ? attrs[n] : null),
    hasAttribute: (n) => n in attrs,
    removeAttribute: (n) => {
      delete attrs[n];
      if (n === 'src') el.src = '';
    },
    toggleAttribute: (n, force) => {
      const on = force === undefined ? !(n in attrs) : !!force;
      if (on) attrs[n] = '';
      else delete attrs[n];
      return on;
    },
    style: {
      setProperty: (n, v) => { props[n] = String(v); },
      removeProperty: (n) => { delete props[n]; },
      getPropertyValue: (n) => props[n] || ''
    },
    appendChild: (child) => {
      if (child.parentNode && child.parentNode.removeChild) child.parentNode.removeChild(child);
      child.parentNode = el;
      el.children.push(child);
      return child;
    },
    removeChild: (child) => {
      el.children.splice(el.children.indexOf(child), 1);
      child.parentNode = null;
      return child;
    },
    addEventListener: (type, fn) => { (el.listeners[type] = el.listeners[type] || []).push(fn); },
    fire: (type) => (el.listeners[type] || []).forEach((fn) => fn({ type: type }))
  };
  return el;
}

/* The page in the frame: web/mini-preview.html, with the elements the creator touches. */
function previewDocument() {
  const byId = { mini: fakeElement('main'), bgPic: fakeElement('img'), bgDim: fakeElement('div') };
  byId.bgPic.hidden = true;
  byId.bgDim.hidden = true;
  return { documentElement: fakeElement('html'), getElementById: (id) => byId[id] || null, byId: byId };
}

/*
 * The creator's step 5, run from the panel's own source: tcMiniStep with its preview and the helpers it draws with.
 * reduce.on is the panel's Reduce motion. load(withViewJs) loads the frame's page (the copied files there or not).
 */
function creatorStep(state, reduce) {
  const panelRoot = fakeElement('html');
  const frames = [];
  const probes = [];
  const srcSets = [];
  const document = {
    documentElement: panelRoot,
    createElement: (tag) => {
      const el = fakeElement(tag);
      if (tag === 'iframe') {
        el.contentWindow = null;
        el.contentDocument = null;
        Object.defineProperty(el, 'src', {
          get() { return this._src || ''; },
          set(value) { this._src = value; srcSets.push(value); }
        });
        frames.push(el);
      }
      return el;
    },
    createTextNode: (text) => ({ textContent: text })
  };
  const context = vm.createContext({
    document: document,
    window: { ThemeCreator: TC },
    themeKit: ThemeApply,
    themePieces: PIECES,
    themeLocalPicture: null,
    WeakMap: WeakMap,
    Image: class { constructor() { this.src = ''; probes.push(this); } },
    firstFrameOf: () => Promise.resolve(FIRST_FRAME),
    readReduceMotion: () => !!(reduce && reduce.on),
    tcCommit: () => {}
  });
  const constant = /var TC_MINI_PREVIEW_PAGE = '[^']*';/.exec(PANEL);
  assert.ok(constant, 'TC_MINI_PREVIEW_PAGE not found');
  vm.runInContext(constant[0], context);
  vm.runInContext('var tcPictureState = new WeakMap();', context);
  ['plEl', 'tcBtn', 'tcChoice', 'tcPress', 'tcGroup', 'tcColorInput', 'tcRange', 'tcPictureSrc', 'tcSetPicture',
    'tcMiniPreview', 'tcMiniStatus', 'tcMiniStep'].forEach((name) => vm.runInContext(panelFunction(name), context));
  context.s = { state: state, blobUrl: '', body: fakeElement('div') };
  const step = {
    context: context, panelRoot: panelRoot, frames: frames, probes: probes, srcSets: srcSets, doc: null,
    /* Draws the step (as tcShowStep does: the body emptied first) and returns its refresh. */
    show: () => {
      context.s.body = fakeElement('div');
      step.refresh = vm.runInContext('tcMiniStep(s, s.body)', context);
      step.refresh();
      return step;
    },
    commit: (next) => {
      context.s.state = next;
      step.refresh();
    },
    load: (withViewJs) => {
      const frame = frames[frames.length - 1];
      step.doc = previewDocument();
      frame.contentWindow = withViewJs === false ? {} : { __cksMiniView: MV };
      frame.contentDocument = step.doc;
      frame.fire('load');
      return step.doc;
    },
    box: () => frames[frames.length - 1].parentNode
  };
  return step.show();
}

/*
 * What the 0.7.0 app gives view.js for the theme the creator saves (theme.rs parse_mini, then into_ui with the bot's
 * address): its base's skin, the mini-player version's colors, and its pieces off, with the base's background pieces too
 * when the picture is shown. The comparison the preview must pass.
 */
function asTheAppGetsIt(values, picturePath) {
  const own = PIECES[values.base].pieces;
  const off = [];
  const add = (id) => { if (own.some((p) => p.id === id) && !off.includes(id)) off.push(id); };
  values.decorationsOff.forEach(add);
  const picture = values.mini.useImage && picturePath ? 'http://127.0.0.1:8888' + picturePath : null;
  if (picture) own.filter((p) => p.background).forEach((p) => add(p.id));
  return {
    id: values.base, vars: {},
    custom: {
      id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: 'fred', mine: true, private: false, base: values.base,
      font: values.font, colors: values.mini.colors, dim: values.mini.dim, picture: picture, decorationsOff: off
    }
  };
}

/* The app's <html> once its mini.js has put that theme on it. */
function appRoot(values, picturePath) {
  const root = fakeElement('html');
  MV.applyTheme(root, asTheAppGetsIt(values, picturePath), []);
  return root;
}

function assertLikeTheApp(previewRoot, values, picturePath) {
  const app = appRoot(values, picturePath);
  assert.deepEqual({ ...previewRoot.props }, { ...app.props }, 'the same inline variables as the app');
  for (const name of ['data-theme', 'data-deco-off']) assert.equal(previewRoot.getAttribute(name), app.getAttribute(name), name);
  // the id is the preview's own (the theme has none yet), but it is there: the skin derives its few tones from it
  assert.equal(previewRoot.hasAttribute('data-custom-theme'), true);
  assert.equal(app.hasAttribute('data-custom-theme'), true);
}

// ---- the version on the app's own card

test('creatorMiniPreviewUsesBaseSkin', () => {
  // Fred's case: Revival with other colors, the record on and the rays off, and a main color of the mini-player's own
  let state = TC.setPiece(TC.useColoursFrom(TC.startState('revival'), 'nebula'), 'rays', false, PIECES);
  state = TC.setFont(TC.setMiniMain(state, '#ff7a00'), 'Fraunces');
  const step = creatorStep(state);
  assert.equal(step.frames.length, 1);
  assert.equal(step.box().classList.contains('loading'), true, 'hidden until its page is in');
  const doc = step.load();
  assert.equal(step.box().classList.contains('loading'), false);
  const root = doc.documentElement;
  assert.equal(root.getAttribute('data-theme'), 'revival', "the starting theme's skin");
  assert.equal(root.getAttribute('data-deco-off'), 'rays');
  assert.equal(root.props['--accent'], '#ff7a00', "the mini-player's own colors, inline");
  assert.equal(root.props['--font-family'], MV.fontStack('Fraunces'));
  assert.equal(root.props['--deco-extra'], state.mini.colors.decoExtra);
  assert.equal(root.props['--on-accent'], MV.onAccent('#ff7a00'));
  assert.equal(typeof root.props['--legibility-shadow'], 'string');
  assert.equal(root.props['--accent2-r'], String(parseInt(state.mini.colors.accent2.slice(1, 3), 16)));
  // exactly what the app puts on its own <html> for the saved theme
  assertLikeTheApp(root, TC.buildValues(state), null);
  // no picture: none behind the card
  assert.equal(doc.byId.bgPic.hidden, true);
  assert.equal(doc.byId.bgDim.hidden, true);
});

test('creatorMiniPreviewFollowsEveryChangeWithoutLoadingAgain', () => {
  let state = TC.startState('ember');
  const step = creatorStep(state);
  const doc = step.load();
  assert.equal(doc.documentElement.getAttribute('data-theme'), 'ember');
  assert.equal(doc.documentElement.hasAttribute('data-deco-off'), false);
  // pieces, colors, another starting theme: put on the same page, no new frame, no new load
  state = TC.setPiece(state, 'flames', false, PIECES);
  step.commit(state);
  assert.equal(doc.documentElement.getAttribute('data-deco-off'), 'flames');
  state = TC.setMiniPage(state, '#f4f2ef');
  step.commit(state);
  assert.equal(doc.documentElement.props['--bg-primary'], state.mini.colors.bgPrimary);
  state = TC.chooseBase(state, 'cipher', PIECES);
  step.commit(state);
  assert.equal(doc.documentElement.getAttribute('data-theme'), 'cipher');
  assertLikeTheApp(doc.documentElement, TC.buildValues(state), null);
  // all pieces on again: the attribute goes, as in the app
  step.commit(TC.allPieces(state, true, PIECES));
  assert.equal(doc.documentElement.hasAttribute('data-deco-off'), false);
  assert.equal(step.frames.length, 1, 'one frame');
  assert.deepEqual(step.srcSets, ['/web/mini-preview.html'], 'its page asked for once');
  assert.equal(step.box().classList.contains('loading'), false, 'and never hidden in between');
});

test('theFrameIsMadeOncePerCreatorAndTakesTheVersionAgainWhenTheStepIsShownAgain', () => {
  const state = TC.setPiece(TC.startState('neon'), 'rails', false, PIECES);
  const step = creatorStep(state);
  step.load();
  const box = step.box();
  // to another step and back: the step is drawn anew, the frame is the same one, put back in the page
  step.show();
  assert.equal(step.frames.length, 1, 'no second frame');
  assert.equal(step.box(), box);
  assert.equal(step.context.s.miniPreview.el, box, 'kept with the creator');
  // a frame put back in a page loads its page again: hidden until then, and the version goes on the new page
  assert.equal(box.classList.contains('loading'), true);
  const again = step.load();
  assert.equal(box.classList.contains('loading'), false);
  assert.equal(again.documentElement.getAttribute('data-theme'), 'neon');
  assert.equal(again.documentElement.getAttribute('data-deco-off'), 'rails');
  assertLikeTheApp(again.documentElement, TC.buildValues(state), null);
});

test('aVersionUsingThePictureHasTheBasesBackgroundPiecesOffInThePreviewButNotInWhatIsSaved', () => {
  const theme = {
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: { id: '1', name: 'fred' }, visibility: 'public', hiddenByOwner: false,
    mine: true, version: 3, base: 'revival', decorationsOff: ['record'],
    colors: Object.assign({}, TC.BASE_PALETTES.revival.colors), radiusScale: 100, font: 'Fraunces',
    background: { url: PICTURE, dim: 30, blur: 0, fit: 'cover' },
    mini: { colors: require('../../main/resources/web/theme-palette.js').mini(TC.BASE_PALETTES.revival.colors), useImage: true, dim: 55 }
  };
  const state = TC.fromTheme(theme, PIECES);
  const saved = JSON.stringify(TC.buildValues(state));
  const step = creatorStep(state);
  const doc = step.load();
  const root = doc.documentElement;
  assert.equal(root.getAttribute('data-deco-off'), 'record sunrise rays', 'the picture takes the place of the background pieces');
  assertLikeTheApp(root, TC.buildValues(state), PICTURE);
  // what is saved keeps the person's own list
  assert.deepEqual(step.context.s.state.decorationsOff, ['record']);
  assert.equal(JSON.stringify(TC.buildValues(step.context.s.state)), saved);
  // the picture behind the card, under the version's darkening, once it has loaded (as the app shows it)
  const pic = doc.byId.bgPic;
  assert.equal(pic.src, PICTURE);
  assert.equal(pic.hidden, true, 'not before it has loaded');
  pic.onload();
  assert.equal(pic.hidden, false);
  assert.equal(doc.byId.bgDim.hidden, false);
  assert.equal(doc.byId.bgDim.props['background-color'], 'rgba(0, 0, 0, 0.55)');
  assert.equal(doc.byId.bgDim.props['background-color'], MV.pictureLayer({ picture: 'http://127.0.0.1:8888' + PICTURE, dim: 55 }).overlay);
  // the version stops using it: the pieces come back, the picture goes
  step.commit(TC.setMiniPicture(state, false));
  assert.equal(root.getAttribute('data-deco-off'), 'record');
  assert.equal(pic.hidden, true);
  assert.equal(doc.byId.bgDim.hidden, true);
  assertLikeTheApp(root, TC.buildValues(step.context.s.state), PICTURE);
  // and again: the picture that loaded shows at once
  step.commit(state);
  assert.equal(pic.hidden, false);
  assert.equal(root.getAttribute('data-deco-off'), 'record sunrise rays');
});

test('creatorMiniPreviewRespectsReduceMotion', async () => {
  const reduce = { on: true };
  const state = TC.chooseBase(TC.startState('nebula'), 'aurora', PIECES);
  const step = creatorStep(state, reduce);
  const doc = step.load();
  // the panel's switch reaches the frame's <html>, as it reaches the panel's own
  assert.equal(doc.documentElement.hasAttribute('data-reduce-motion'), true);
  reduce.on = false;
  step.refresh();
  assert.equal(doc.documentElement.hasAttribute('data-reduce-motion'), false);
  reduce.on = true;
  step.refresh();
  assert.equal(doc.documentElement.hasAttribute('data-reduce-motion'), true);
  // and the preview page stops the skins' animations on it, with the rule the app has for the system setting
  const app = /@media \(prefers-reduced-motion: reduce\) \{\s*([^{]+)\{([^}]*)\}/.exec(MINI_CSS);
  assert.ok(app, "mini.css's reduced-motion rule not found");
  const own = /\n\s*(html\[data-reduce-motion\][^{]*)\{([^}]*)\}/.exec(PREVIEW);
  assert.ok(own, "the preview's reduce-motion rule not found");
  assert.deepEqual(own[1].split(',').map((s) => s.trim()), app[1].split(',').map((s) => 'html[data-reduce-motion] ' + s.trim()));
  assert.equal(own[2].trim(), app[2].trim());
  assert.match(own[2], /animation: none !important;/);
  // a picture that can move shows its first frame only
  const moving = TC.fromTheme({
    id: 'k3j9x0q2m7ab', name: 'x', mine: true, version: 1, base: 'nebula', decorationsOff: [],
    colors: Object.assign({}, TC.BASE_PALETTES.nebula.colors), radiusScale: 100, font: 'Inter',
    background: { url: MOVING_PICTURE, dim: 20, blur: 0, fit: 'cover' },
    mini: { colors: TC.startState('nebula').mini.colors, useImage: true, dim: 20 }
  }, PIECES);
  step.commit(moving);
  assert.equal(step.probes.length, 1, 'loaded first, to draw its first frame');
  assert.equal(step.probes[0].src, MOVING_PICTURE);
  step.probes[0].onload();
  await tick();
  assert.equal(doc.byId.bgPic.src, FIRST_FRAME, 'never the moving picture itself');
});

// ---- its own page

test('previewDocumentIsIsolated', () => {
  const state = TC.setPiece(TC.startState('revival'), 'rays', false, PIECES);
  const step = creatorStep(state);
  step.load();
  step.commit(TC.setMiniMain(state, '#22c55e'));
  // nothing of the mini-player's reaches the panel's own <html>
  assert.deepEqual({ ...step.panelRoot.attrs }, {});
  assert.deepEqual({ ...step.panelRoot.props }, {});
  // a frame with a page of its own (never srcdoc, never written into)
  const frame = step.frames[0];
  assert.equal(frame.src, '/web/mini-preview.html');
  assert.equal(frame.inert, true, 'a picture of the player: no clicks, no focus');
  assert.equal(frame.tabIndex, -1);
  assert.equal(step.box().getAttribute('role'), 'img');
  assert.equal(step.box().getAttribute('aria-label'), 'A preview of the mini-player');
  const preview = panelFunction('tcMiniPreview');
  assert.doesNotMatch(preview, /srcdoc|document\.write|innerHTML/);
  // the panel never loads the mini-player's stylesheets or scripts itself: only the preview page does
  assert.doesNotMatch(PANEL, /<link[^>]*(?:mini-player|themes\.css|mini\.css)|<script[^>]*(?:mini-player|view\.js|mini\.js)|@import[^;]*mini/);
  const links = [...PREVIEW.matchAll(/<link rel="stylesheet" href="([^"]+)">/g)].map((m) => m[1]);
  assert.ok(links.includes('mini-player/mini.css') && links.includes('mini-player/themes.css'), links.join(' '));
  const scripts = [...PREVIEW.matchAll(/<script src="([^"]+)"><\/script>/g)].map((m) => m[1]);
  assert.deepEqual(scripts, ['mini-player/view.js'], "view.js only: never the app's Tauri glue (mini.js)");
  assert.doesNotMatch(PREVIEW, /<script[^>]*mini\.js|import[^;]*mini\.js/);
  // the step uses the tested version and the one frame kept with the creator
  const stepCode = panelFunction('tcMiniStep');
  assert.match(stepCode, /var preview = s\.miniPreview \|\| \(s\.miniPreview = tcMiniPreview\(\)\);/);
  assert.match(stepCode, /preview\.update\(TC\.miniPreviewTheme\(s\.state, themePieces\), view, tcPictureSrc\(s\)\);/);
});

/*
 * The app's card (<main class="mini">...</main>) as a list of its start tags with their attributes, leaving out what
 * only says what is on show (the text, hidden, disabled, pressed, the words of titles and labels, the class "on").
 */
function cardShape(html) {
  const start = html.indexOf('<main class="mini"');
  const end = html.indexOf('</main>', start);
  assert.ok(start >= 0 && end > start, 'the card was not found');
  const card = html.slice(start, end).replace(/<!--[\s\S]*?-->/g, '');
  const SHOWN = new Set(['hidden', 'disabled', 'aria-pressed', 'title', 'aria-label']);
  return [...card.matchAll(/<([a-z]+)((?:\s+[A-Za-z-]+(?:="[^"]*")?)*)\s*\/?>/g)].map((m) => {
    const attrs = [...m[2].matchAll(/([A-Za-z-]+)(?:="([^"]*)")?/g)]
      .filter((a) => !SHOWN.has(a[1]))
      .map((a) => (a[1] === 'class' ? 'class=' + a[2].split(/\s+/).filter((c) => c !== 'on').join(' ') : a[1] + '=' + (a[2] || '')));
    return m[1] + ' ' + attrs.join(' ');
  });
}

test('thePreviewIsTheAppsOwnCard', () => {
  const app = cardShape(APP);
  assert.ok(app.length > 40, 'the whole card: ' + app.length);
  assert.deepEqual(cardShape(PREVIEW), app, "the preview's card must be the app's (desktop/ui/mini-player/index.html): copy it over");
  // in the app's frame, with the app's fonts
  assert.match(PREVIEW, /<div class="shell" id="shell">\s*<main class="mini"/);
  const fonts = (html) => [...html.matchAll(/<link rel="(?:preconnect|stylesheet)" href="(https:[^"]+)">/g)].map((m) => m[0]);
  assert.equal(fonts(APP).length, 2);
  assert.deepEqual(fonts(PREVIEW), fonts(APP));
  // a sample song: the same words as the creator always showed, part way through, shuffle on
  assert.match(PREVIEW, /<div class="title" id="title"[^>]*>Song title<\/div>/);
  assert.match(PREVIEW, /<div class="meta" id="meta"[^>]*>Artist name<\/div>/);
  assert.match(PREVIEW, /#progressFill \{ width: 38%; \}/);
  assert.match(PREVIEW, /id="shuffleBtn" class="ctrl mode on"/);
  // its stylesheets are the app's, in the app's order, and its own small rules come after them
  const sheets = [...PREVIEW.matchAll(/<link rel="stylesheet" href="(mini-player\/[^"]+)">/g)].map((m) => m[1]);
  assert.deepEqual(sheets, ['mini-player/mini.css', 'mini-player/themes.css']);
  assert.ok(PREVIEW.indexOf('<style>') > PREVIEW.indexOf('mini-player/themes.css'));
});

test('withoutTheCopiedFilesThePreviewSaysItNeedsTheBuiltBot', () => {
  // the static dev preview serves the sources, where the build's copies are not: the page has no view.js
  const inline = [...PREVIEW.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.equal(inline.length, 1);
  assert.ok(PREVIEW.indexOf('<script src="mini-player/view.js">') < PREVIEW.indexOf('<script>'), 'it runs after view.js');
  const missing = fakeElement('html');
  vm.runInNewContext(inline[0], { window: {}, document: { documentElement: missing } });
  assert.equal(missing.classList.contains('preview-missing'), true);
  const there = fakeElement('html');
  vm.runInNewContext(inline[0], { window: { __cksMiniView: MV }, document: { documentElement: there } });
  assert.equal(there.classList.contains('preview-missing'), false);
  // the note in place of the card, readable without the app's stylesheets
  assert.match(PREVIEW, /<p class="preview-note">Preview needs the built bot<\/p>/);
  assert.match(PREVIEW, /html\.preview-missing \.shell \{ display: none; \}/);
  assert.match(PREVIEW, /html\.preview-missing \.preview-note \{[^}]*display: flex;/);
  assert.match(PREVIEW, /\n\s*\.preview-note \{ display: none; \}/);
  // and the creator does nothing to such a page, but shows it
  const step = creatorStep(TC.startState('revival'));
  const doc = step.load(false);
  assert.deepEqual({ ...doc.documentElement.attrs }, {});
  assert.equal(step.box().classList.contains('loading'), false);
  step.commit(TC.setMiniMain(step.context.s.state, '#ff0000'));
  assert.deepEqual({ ...doc.documentElement.attrs }, {});
});

test('theCreatorsStylesheetHasOnlyTheFrameOfThePreview', () => {
  const rules = [...PANEL.matchAll(/\n        (\.tc-minip[^{]*)\{([^}]*)\}/g)].map((m) => m[1].trim());
  assert.deepEqual(rules, ['.tc-minip', '.tc-minip-frame', '.tc-minip.loading .tc-minip-frame']);
  // at the app's size: the window is 360 px wide, the card 170 px tall (mini.css .shell > .mini)
  assert.match(MINI_CSS, /\.shell > \.mini \{ flex: 0 0 auto; height: 170px; \}/);
  const box = /\n        \.tc-minip \{([^}]*)\}/.exec(PANEL)[1];
  assert.match(box, /max-width: 360px;/);
  assert.match(box, /height: 170px;/);
});
