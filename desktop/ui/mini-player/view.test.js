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

const { spotifyView, isLink, formatDuration, latestOnly } = require('./view.js');

test('spotifyView states', () => {
  assert.deepEqual(spotifyView({ available: true, now: { title: 'Toxic', artist: 'slayr', playing: true } }),
    { playable: true, tooltip: 'Queue “Toxic – slayr” on the bot' });
  assert.equal(spotifyView({ available: true, now: { title: 'Toxic', artist: '', playing: true } }).tooltip, 'Queue “Toxic” on the bot');
  assert.deepEqual(spotifyView({ available: true, now: { title: 'Toxic', artist: 'slayr', playing: false } }),
    { playable: false, tooltip: "Spotify isn't playing" });
  assert.deepEqual(spotifyView({ available: true, now: null }), { playable: false, tooltip: "Spotify isn't open" });
  assert.deepEqual(spotifyView(null), { playable: false, tooltip: "Spotify isn't open" });
});

test('isLink', () => {
  assert.equal(isLink('https://youtu.be/x'), true);
  assert.equal(isLink('  spotify:album:1 '), true);
  assert.equal(isLink('watch https://x.y now'), true);
  assert.equal(isLink('httpx'), false);
  assert.equal(isLink('daft punk'), false);
  assert.equal(isLink(''), false);
});

test('formatDuration', () => {
  assert.equal(formatDuration(61000, false), '1:01');
  assert.equal(formatDuration(3723000, false), '1:02:03');
  assert.equal(formatDuration(0, false), '0:00');
  assert.equal(formatDuration(5000, true), 'LIVE');
});

test('latestOnly', () => {
  const seq = latestOnly();
  const a = seq.next();
  const b = seq.next();
  assert.equal(seq.isLatest(a), false);
  assert.equal(seq.isLatest(b), true);
});

const { currentLyricIndex, trackKey } = require('./view.js');

test('currentLyricIndex', () => {
  const lines = [{ ms: 1000, text: 'a' }, { ms: 5000, text: 'b' }, { ms: 9000, text: 'c' }];
  assert.equal(currentLyricIndex(lines, 0), -1);
  assert.equal(currentLyricIndex(lines, 1000), 0);
  assert.equal(currentLyricIndex(lines, 4999), 0);
  assert.equal(currentLyricIndex(lines, 5000), 1);
  assert.equal(currentLyricIndex(lines, 99999), 2);
  assert.equal(currentLyricIndex([], 5000), -1);
});

test('trackKey', () => {
  const a = { track: { title: 'T', uri: 'u1' } };
  assert.equal(trackKey(a), trackKey({ track: { title: 'T', uri: 'u1', positionMs: 5 } }));
  assert.notEqual(trackKey(a), trackKey({ track: { title: 'T', uri: 'u2' } }));
  assert.equal(trackKey({ track: null }), null);
  assert.equal(trackKey(null), null);
});

const { lyricsButtonEnabled } = require('./view.js');

test('lyrics button stays usable to close the lyrics after the song ends', () => {
  assert.equal(lyricsButtonEnabled(true, 'none'), true);
  assert.equal(lyricsButtonEnabled(false, 'none'), false);
  assert.equal(lyricsButtonEnabled(false, 'menu'), false);
  assert.equal(lyricsButtonEnabled(false, 'lyrics'), true);
});

const { repeatView, seekPosition, queueRows } = require('./view.js');

test('repeatView', () => {
  assert.deepEqual(repeatView('OFF'), { label: 'Repeat: off', badge: '', active: false });
  assert.deepEqual(repeatView('ALL'), { label: 'Repeat: whole queue', badge: '', active: true });
  assert.deepEqual(repeatView('SINGLE'), { label: 'Repeat: this song', badge: '1', active: true });
  assert.deepEqual(repeatView(undefined), repeatView('OFF'));
});

test('seekPosition', () => {
  assert.equal(seekPosition(0.5, 200000), 100000);
  assert.equal(seekPosition(-0.2, 200000), 0);
  assert.equal(seekPosition(1.7, 200000), 200000);
  assert.equal(seekPosition(0.5, 0), 0);
});

test('queueRows', () => {
  const state = { upNext: [
    { title: 'Toxic', author: 'slayr', uri: 'u0', durationMs: 161000, requestedBy: 'fred' },
    { title: 'Nights', author: '', uri: 'u1', durationMs: 307000, requestedBy: null }
  ] };
  assert.deepEqual(queueRows(state), [
    { index: 0, uri: 'u0', title: 'Toxic', sub: 'slayr · fred', time: '2:41' },
    { index: 1, uri: 'u1', title: 'Nights', sub: '', time: '5:07' }
  ]);
  assert.deepEqual(queueRows(null), []);
});
