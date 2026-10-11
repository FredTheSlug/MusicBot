'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { view } = require('./view.js');

const track = { title: 'Small Worlds', author: 'Mac Miller', requestedBy: 'fred', durationMs: 1000, positionMs: 0, isStream: false };
function ui(state, stale) {
  return { state: Object.assign({ connected: true, signedIn: true, guild: { id: '9', name: 'CK' }, track: null, paused: false, volume: 100 }, state), stale: !!stale };
}

test('playing track shows title, artist and requester', () => {
  const v = view(ui({ track }));
  assert.equal(v.title, 'Small Worlds');
  assert.equal(v.meta, 'Mac Miller · requested by fred');
  assert.equal(v.track, track);
});

test('idle shows the server name', () => {
  assert.deepEqual(view(ui({})), { title: 'Nothing playing', meta: 'CK', track: null, live: true, signedIn: true });
});

test('unreachable or stale shows reconnecting with no track', () => {
  for (const v of [view(ui({ connected: false, track })), view(ui({ track }, true)), view(null)]) {
    assert.equal(v.title, 'Reconnecting…');
    assert.equal(v.track, null);
    assert.equal(v.live, false);
  }
});

test('signed out shows the sign-in prompt', () => {
  const v = view(ui({ signedIn: false }));
  assert.equal(v.title, 'Not signed in');
  assert.equal(v.live && !v.signedIn, true, 'Open panel button is shown');
});

test('bot restart (socket rejected with 4401) shows sign in, not reconnecting', () => {
  const v = view(ui({ connected: false, signedIn: false }));
  assert.equal(v.title, 'Not signed in');
  assert.equal(v.live && !v.signedIn, true, 'Open panel button is shown');
  assert.equal(view(ui({ connected: false, signedIn: false }, true)).title, 'Reconnecting…', 'a stale report still means unreachable');
});

const fs = require('node:fs');
const path = require('node:path');
const { THEMES, applyTheme, applyOpacity } = require('./view.js');
const THEME_IDS = ['nebula', 'aurora', 'ember', 'crimson', 'dawn', 'dawn-dark', 'steel', 'verdant', 'grove', 'grapevine',
  'sumi', 'rasta', 'cipher', 'signal', 'victory', 'neon', 'revival'];

function fakeRoot() {
  const attrs = {}, props = {};
  return {
    attrs, props,
    setAttribute(name, value) { attrs[name] = String(value); },
    style: { setProperty(name, value) { props[name] = value; }, removeProperty(name) { delete props[name]; } }
  };
}

test('THEMES lists the 17 panel themes in order', () => {
  assert.deepEqual(THEMES.map((t) => t.id), THEME_IDS);
  assert.equal(THEMES.find((t) => t.id === 'dawn-dark').label, 'Dawn Dark');
});

test('themes.css has a block per theme', () => {
  const css = fs.readFileSync(path.join(__dirname, 'themes.css'), 'utf8');
  for (const id of THEME_IDS) assert.ok(css.includes(`[data-theme="${id}"]`), id);
});

test('applyTheme swaps inline vars', () => {
  const root = fakeRoot();
  let applied = applyTheme(root, { id: 'neon', vars: { '--accent': '#0ff', '--bg-card': '#000' } }, []);
  assert.deepEqual(applied.sort(), ['--accent', '--bg-card']);
  applied = applyTheme(root, { id: 'ember', vars: { '--accent': '#f80' } }, applied);
  assert.deepEqual(applied, ['--accent']);
  assert.deepEqual(root.props, { '--accent': '#f80' });
  assert.equal(root.attrs['data-theme'], 'ember');
});

test('applyOpacity sets the card alpha and flags low values', () => {
  const root = fakeRoot();
  root.classList = { values: new Set(), toggle(name, on) { if (on) this.values.add(name); else this.values.delete(name); } };
  applyOpacity(root, 0.4);
  assert.equal(root.props['--card-alpha'], '0.4');
  assert.ok(root.classList.values.has('low-alpha'));
  applyOpacity(root, 0.9);
  assert.ok(!root.classList.values.has('low-alpha'));
});
