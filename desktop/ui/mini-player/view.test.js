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

const { sleepLabel } = require('./view.js');

test('sleepLabel', () => {
  assert.equal(sleepLabel({ mode: 'timer', endsAtMs: 1000 + 14 * 60000 }, 1000), 'Stops in 14 min');
  assert.equal(sleepLabel({ mode: 'timer', endsAtMs: 1000 + 30000 }, 1000), 'Stops in 1 min');
  assert.equal(sleepLabel({ mode: 'end', endsAtMs: null }, 1000), 'Stops after this song');
  assert.equal(sleepLabel({ mode: 'off', endsAtMs: null }, 1000), '');
  assert.equal(sleepLabel(null, 1000), '');
});

const { accelFromEvent } = require('./view.js');

test('accelFromEvent', () => {
  assert.equal(accelFromEvent({ ctrlKey: true, altKey: true, code: 'KeyP' }), 'Ctrl+Alt+P');
  assert.equal(accelFromEvent({ shiftKey: true, code: 'ArrowRight' }), 'Shift+Right');
  assert.equal(accelFromEvent({ ctrlKey: true, code: 'Digit1' }), 'Ctrl+1');
  assert.equal(accelFromEvent({ altKey: true, code: 'Numpad5' }), 'Alt+Numpad5');
  assert.equal(accelFromEvent({ code: 'F9' }), 'F9');
  assert.equal(accelFromEvent({ code: 'KeyP' }), null, 'a plain letter would swallow typing');
  assert.equal(accelFromEvent({ ctrlKey: true, code: 'ControlLeft' }), null, 'modifier only');
  assert.equal(accelFromEvent({ ctrlKey: true, code: 'Escape' }), null, 'Escape cancels recording');
});

const { pickerRows, playlistKey, rememberRecent, playlistSong, pickerError, playlistCount } = require('./view.js');

const PLAYLISTS = [
  { name: 'Chill', folder: null, songCount: 3, legacy: false },
  { name: 'Zen', folder: null, songCount: 1, legacy: false },
  { name: 'Rap', folder: 'Fred', songCount: 12, legacy: false },
  { name: '2010', folder: 'Fred/Old', songCount: 40, legacy: true },
  { name: 'Rock', folder: 'Mom', songCount: 0, legacy: false },
  { name: 'Jazz', folder: 'Mom', songCount: 7, legacy: false },
  { name: 'Lofi', folder: 'Mom', songCount: 2, legacy: false }
];
const key = (name, folder) => playlistKey({ name, folder });
const names = (rows) => rows.map((r) => r.name);

test('playlistKey tells apart the same name in different folders', () => {
  assert.equal(key('Rap', 'Fred'), 'Fred\nRap');
  assert.equal(key('Rap', null), '\nRap');
  assert.equal(key('Rap', ''), key('Rap', null));
  assert.notEqual(key('Rap', 'Fred'), key('Rap', 'Mom'));
});

test('picker lists recently used playlists first, then the rest in the given order', () => {
  const rows = pickerRows(PLAYLISTS, [key('Jazz', 'Mom'), key('Zen', null)], '');
  assert.deepEqual(names(rows.recent), ['Jazz', 'Zen'], 'newest first');
  assert.deepEqual(names(rows.rest), ['Chill', 'Rap', '2010', 'Rock', 'Lofi'], 'what is left keeps the app\'s order');
  assert.equal(rows.recent.length + rows.rest.length, PLAYLISTS.length, 'nothing listed twice or lost');
});

test('picker shows at most 5 recent playlists and skips ones that are gone or repeated', () => {
  const recentKeys = ['Gone\nNope', key('Rap', 'Fred'), key('Rap', 'Fred'), key('Zen', null), key('Rock', 'Mom'), key('Chill', null),
    key('Jazz', 'Mom'), key('Lofi', 'Mom')];
  const rows = pickerRows(PLAYLISTS, recentKeys, '');
  assert.deepEqual(names(rows.recent), ['Rap', 'Zen', 'Rock', 'Chill', 'Jazz']);
  assert.deepEqual(names(rows.rest), ['2010', 'Lofi']);
});

test('picker rows carry what the list shows and what adding needs', () => {
  const rows = pickerRows(PLAYLISTS, [], '');
  assert.deepEqual(rows.recent, []);
  assert.deepEqual(rows.rest[0], { key: '\nChill', name: 'Chill', folder: null, path: '', count: '3 songs', legacy: false });
  assert.deepEqual(rows.rest[1].count, '1 song');
  assert.equal(rows.rest.find((r) => r.name === 'Rock').count, '0 songs');
  assert.deepEqual(rows.rest.find((r) => r.name === 'Rap'), { key: 'Fred\nRap', name: 'Rap', folder: 'Fred', path: 'Fred', count: '12 songs', legacy: false });
  const old = rows.rest.find((r) => r.name === '2010');
  assert.equal(old.legacy, true);
  assert.equal(old.count, 'upgrading…', 'a playlist the bot is still converting cannot be picked');
});

test('picker filter keeps playlists whose folder path and name hold every word', () => {
  const recentKeys = [key('Jazz', 'Mom'), key('Rap', 'Fred')];
  assert.deepEqual(names(pickerRows(PLAYLISTS, recentKeys, 'mom').recent), ['Jazz']);
  assert.deepEqual(names(pickerRows(PLAYLISTS, recentKeys, 'mom').rest), ['Rock', 'Lofi'], 'the folder name matches too');
  assert.deepEqual(names(pickerRows(PLAYLISTS, [], '  FRED  rap ').rest), ['Rap'], 'any case, any order, extra spaces');
  assert.deepEqual(names(pickerRows(PLAYLISTS, [], 'fred/old').rest), ['2010'], 'the path as shown');
  assert.deepEqual(pickerRows(PLAYLISTS, recentKeys, 'nothing like it'), { recent: [], rest: [] });
  assert.deepEqual(names(pickerRows(PLAYLISTS, [], 'o').rest), ['2010', 'Rock', 'Jazz', 'Lofi'], 'a letter matches inside folder paths too');
});

test('picker survives missing or odd input', () => {
  assert.deepEqual(pickerRows(null, null, undefined), { recent: [], rest: [] });
  assert.deepEqual(pickerRows([], [key('A', null)], ''), { recent: [], rest: [] });
  assert.equal(pickerRows(PLAYLISTS, 'not a list', null).rest.length, PLAYLISTS.length);
  assert.deepEqual(names(pickerRows(PLAYLISTS, [5, null, {}, key('Zen', null)], '').recent), ['Zen']);
});

test('rememberRecent puts the newest first, drops repeats and keeps 8', () => {
  assert.deepEqual(rememberRecent(['a', 'b', 'c'], 'c'), ['c', 'a', 'b']);
  assert.deepEqual(rememberRecent([], 'a'), ['a']);
  const long = rememberRecent(['1', '2', '3', '4', '5', '6', '7', '8'], '9');
  assert.deepEqual(long, ['9', '1', '2', '3', '4', '5', '6', '7']);
  assert.deepEqual(rememberRecent(null, 'a'), ['a']);
  assert.deepEqual(rememberRecent(['a', 7, null, 'b'], 'c'), ['c', 'a', 'b'], 'only text survives');
});

test('playlistCount counts songs, or says the bot is still converting the playlist', () => {
  assert.equal(playlistCount({ songCount: 12, legacy: false }), '12 songs');
  assert.equal(playlistCount({ songCount: 1 }), '1 song');
  assert.equal(playlistCount({ songCount: 0 }), '0 songs');
  assert.equal(playlistCount({ songCount: 40, legacy: true }), 'upgrading…');
  assert.equal(playlistCount({}), '0 songs');
});

test('playlistSong keeps the five fields a playlist stores', () => {
  const playing = { title: 'T', author: 'A', uri: 'https://y/1', durationMs: 200000, positionMs: 5, isStream: false, requestedBy: 'fred', artworkUrl: 'https://i.ytimg.com/x.jpg' };
  assert.deepEqual(playlistSong(playing), { uri: 'https://y/1', title: 'T', author: 'A', durationMs: 200000, artworkUrl: 'https://i.ytimg.com/x.jpg' });
  const found = { title: '', author: 'A', uri: 'https://y/2', durationMs: 61000.4, isStream: false, source: 'youtube' };
  assert.deepEqual(playlistSong(found), { uri: 'https://y/2', title: '', author: 'A', durationMs: 61000, artworkUrl: null }, 'search results carry no artwork');
  assert.equal(playlistSong({ uri: 'https://live', title: 'Radio', isStream: true, durationMs: 9e15 }).durationMs, 0, 'a stream has no length');
  assert.equal(playlistSong({ uri: 'u', artworkUrl: 'javascript:alert(1)' }).artworkUrl, null);
  assert.equal(playlistSong({ uri: 'u', artworkUrl: 'data:text/html,x' }).artworkUrl, null);
  assert.equal(playlistSong({ uri: 'u', durationMs: -5 }).durationMs, 0);
  assert.equal(playlistSong({ uri: '  ', title: 'T' }), null);
  assert.equal(playlistSong({ title: 'T' }), null);
  assert.equal(playlistSong(null), null);
});

test('pickerError shows the bot\'s words, short and on one line', () => {
  assert.equal(pickerError('That song can\'t be added to a playlist'), 'That song can\'t be added to a playlist');
  assert.equal(pickerError(new Error('Playlist is full')), 'Playlist is full');
  assert.equal(pickerError('  Something\n  went   wrong '), 'Something went wrong');
  assert.equal(pickerError(''), 'Couldn\'t add the song');
  assert.equal(pickerError(null), 'Couldn\'t add the song');
  assert.equal(pickerError('', 'Couldn\'t load your playlists'), 'Couldn\'t load your playlists');
  assert.equal(pickerError('Could not reach the bot', 'Couldn\'t load your playlists'), 'Could not reach the bot');
  const html = pickerError('<html>' + 'x'.repeat(500));
  assert.equal(html.length, 160);
  assert.ok(html.endsWith('…'));
});

// ---- Custom themes: the mini-player versions people made in the panel ----

const {
  customVars, onAccent, fontStack, themeMenu, themeLabel, pictureLayer, themePicture, CUSTOM_SLOTS, FONT_STACKS
} = require('./view.js');
const ThemePalette = require('../../../src/main/resources/web/theme-palette.js');
const ThemeApply = require('../../../src/main/resources/web/theme-apply.js');

const MINI_COLORS = {
  bgPrimary: '#0b0d14', bgSecondary: '#10131c', bgCard: '#161a26', bgElevated: '#1c2131', bgHover: '#262c42',
  accent: '#7c5cff', accentLight: '#a08bff', accent2: '#22d3ee', textPrimary: '#eef0f8', textSecondary: '#9aa0b8',
  textTertiary: '#646a82', border: '#ffffff12', borderLight: '#ffffff1f', success: '#34d399'
};
const PIC = 'https://cloutkings.duckdns.org/theme-images/' + '9f2c4d6e8a0b1c3d5e7f9a1b3c5d7e9f' + '.webp';

function custom(extra) {
  return Object.assign({
    id: 'k3j9x0q2m7ab', name: 'Midnight Drive', owner: 'fred', mine: false, private: false, base: 'ember', font: 'Outfit',
    colors: Object.assign({}, MINI_COLORS), dim: 55, picture: null
  }, extra);
}

test('CUSTOM_SLOTS are the 14 colors of a mini-player version (the theme\'s 15 without glass)', () => {
  assert.equal(CUSTOM_SLOTS.length, 14);
  assert.deepEqual(CUSTOM_SLOTS, ThemePalette.MINI_SLOTS);
});

test('customVars turns a mini palette into the 16 variables plus the color on the accent', () => {
  const vars = customVars(custom());
  const SIXTEEN = ['--bg-primary', '--bg-secondary', '--bg-card', '--bg-elevated', '--bg-hover', '--accent', '--accent-light',
    '--accent-glow', '--accent-gradient', '--text-primary', '--text-secondary', '--text-tertiary', '--border', '--border-light',
    '--success', '--font-family'];
  assert.deepEqual(Object.keys(vars).sort(), SIXTEEN.concat(['--on-accent']).sort());
  assert.equal(vars['--bg-card'], '#161a26');
  assert.equal(vars['--accent'], '#7c5cff');
  assert.equal(vars['--accent-light'], '#a08bff');
  assert.equal(vars['--border'], '#ffffff12', 'colors with alpha stay as they are');
  assert.equal(vars['--accent-glow'], '#7c5cff52', 'the accent at 32%');
  assert.equal(vars['--accent-gradient'], 'linear-gradient(135deg, #7c5cff, #22d3ee)');
  assert.equal(vars['--font-family'], FONT_STACKS.Outfit);
  assert.equal(vars['--on-accent'], '#000000');
});

test('customVars gives the mini-player exactly what the panel\'s creator previews', () => {
  // Same colors, same derived values as ThemePalette.cssVars (what the creator's mini-player preview wears).
  for (const accent of ['#7c5cff', '#f1c40f', '#10b981cc']) {
    const colors = Object.assign({}, MINI_COLORS, { accent });
    const vars = customVars(custom({ colors }));
    const panel = ThemePalette.cssVars(Object.assign({ glass: '#10131ce0' }, colors));
    for (const name of Object.keys(vars)) {
      if (name === '--font-family' || name === '--on-accent') continue;
      assert.equal(vars[name], panel[name], name + ' for ' + accent);
    }
    assert.equal(vars['--on-accent'], ThemePalette.onAccent(accent));
  }
  for (const font of Object.keys(ThemeApply.FONT_STACKS)) {
    assert.equal(fontStack(font), ThemeApply.FONT_STACKS[font], font);
  }
  assert.deepEqual(Object.keys(FONT_STACKS), Object.keys(ThemeApply.FONT_STACKS), 'the same eight fonts');
});

test('an unknown font is Inter', () => {
  for (const font of [undefined, null, '', 'Comic Sans', 'constructor', '__proto__', 'Inter; color: red']) {
    assert.equal(fontStack(font), FONT_STACKS.Inter, String(font));
  }
  assert.equal(customVars(custom({ font: 'Papyrus' }))['--font-family'], FONT_STACKS.Inter);
});

test('customVars refuses a palette with any color that is not #rrggbb or #rrggbbaa', () => {
  for (const bad of ['red', '#fff', '#12345', '#1234567', '#12345678a', 'rgb(1,2,3)', '#ggg000', '', null, 5, '#7c5cff; background: url(x)',
    'url(https://evil/x)', '#7c5cff\n', ' #7c5cff']) {
    for (const slot of ['accent', 'bgCard', 'textPrimary', 'border']) {
      const colors = Object.assign({}, MINI_COLORS, { [slot]: bad });
      assert.equal(customVars(custom({ colors })), null, slot + ' = ' + bad);
    }
  }
  const missing = Object.assign({}, MINI_COLORS);
  delete missing.success;
  assert.equal(customVars(custom({ colors: missing })), null);
  assert.equal(customVars(custom({ colors: null })), null);
  assert.equal(customVars(null), null);
  assert.equal(customVars(undefined), null);
  assert.ok(customVars(custom({ colors: Object.assign({}, MINI_COLORS, { accent: '#ABCDEF' }) })), 'upper case is fine');
});

test('onAccent is the same black-or-white rule as the panel\'s', () => {
  assert.equal(onAccent('#ffffff'), '#000000');
  assert.equal(onAccent('#000000'), '#ffffff');
  assert.equal(onAccent('#7c5cff'), '#000000');
  assert.equal(onAccent('#1e3a8a'), '#ffffff');
  assert.equal(onAccent('#ffffff00'), '#ffffff', 'an accent with alpha counts over black, as contrast() counts it');
  let seed = 12345;
  const rnd = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
  for (let i = 0; i < 500; i++) {
    const hex = '#' + Math.floor(rnd() * 0x1000000).toString(16).padStart(6, '0');
    assert.equal(onAccent(hex), ThemePalette.onAccent(hex), hex);
    const withAlpha = hex + Math.floor(rnd() * 256).toString(16).padStart(2, '0');
    assert.equal(onAccent(withAlpha), ThemePalette.onAccent(withAlpha), withAlpha);
  }
});

test('applyTheme wears a custom mini-player version on the neutral skin and takes all of it off again', () => {
  const root = fakeRoot();
  let applied = applyTheme(root, { id: 'ember', vars: {}, custom: custom() }, []);
  assert.equal(root.attrs['data-theme'], 'custom', 'no built-in skin\'s decorations under someone else\'s colors');
  assert.equal(applied.length, 17);
  assert.equal(root.props['--accent'], '#7c5cff');
  assert.equal(root.props['--on-accent'], '#000000');
  assert.equal(root.props['--font-family'], FONT_STACKS.Outfit);
  // back to a built-in skin: not one variable of the custom theme stays, the skin's own on-accent returns
  applied = applyTheme(root, { id: 'aurora', vars: {} }, applied);
  assert.deepEqual(root.props, {});
  assert.equal(root.attrs['data-theme'], 'aurora');
  // the panel's live values and a custom version never mix
  applied = applyTheme(root, { id: 'neon', vars: { '--accent': '#0ff' } }, applied);
  assert.deepEqual(root.props, { '--accent': '#0ff' });
  applyTheme(root, { id: 'neon', vars: { '--accent': '#0ff' }, custom: custom() }, applied);
  assert.equal(root.props['--accent'], '#7c5cff');
});

test('applyTheme falls back to the base skin when a custom palette is unusable', () => {
  const root = fakeRoot();
  const colors = Object.assign({}, MINI_COLORS, { accent: 'url(https://evil/x)' });
  const applied = applyTheme(root, { id: 'ember', vars: {}, custom: custom({ colors }) }, []);
  assert.equal(root.attrs['data-theme'], 'ember');
  assert.deepEqual(applied, []);
  assert.deepEqual(root.props, {});
});

test('the theme menu groups Built-in, Community and My themes', () => {
  const themes = [
    custom({ id: 'aaaaaaaaaaaa', name: 'Zed', owner: 'sam', mine: false }),
    custom({ id: 'bbbbbbbbbbbb', name: 'Midnight Drive', owner: 'fred', mine: true, private: true }),
    custom({ id: 'cccccccccccc', name: 'Harbor', mine: true })
  ];
  const groups = themeMenu(themes);
  assert.deepEqual(groups.map((g) => g.label), ['Built-in', 'Community', 'My themes']);
  assert.deepEqual(groups[0].options.map((o) => o.value), THEME_IDS);
  assert.equal(groups[0].options[5].label, 'Dawn Dark');
  assert.deepEqual(groups[1].options.map((o) => o.value), ['custom:aaaaaaaaaaaa']);
  assert.deepEqual(groups[2].options.map((o) => o.value), ['custom:bbbbbbbbbbbb', 'custom:cccccccccccc'], 'the order the app sent');
  assert.equal(groups[2].options[0].label, themeLabel(themes[1]));
});

test('the theme menu leaves out a group with nothing in it, and a theme with no usable id', () => {
  assert.deepEqual(themeMenu([]).map((g) => g.label), ['Built-in']);
  assert.deepEqual(themeMenu(null).map((g) => g.label), ['Built-in']);
  assert.deepEqual(themeMenu(undefined).map((g) => g.label), ['Built-in']);
  const only = themeMenu([custom({ id: 'dddddddddddd', mine: true })]);
  assert.deepEqual(only.map((g) => g.label), ['Built-in', 'My themes']);
  const odd = themeMenu([custom({ id: 'not an id' }), custom({ id: 'EEEEEEEEEEEE' }), custom({ id: 5 }), null, 'x', custom({ id: '../../x/y/z/w' })]);
  assert.deepEqual(odd.map((g) => g.label), ['Built-in'], 'ids the panel would never make are not offered');
});

test('themeLabel reads like the panel\'s picker: community themes name their creator, private ones are marked', () => {
  const FSI = '\u2068', PDI = '\u2069';
  assert.equal(themeLabel(custom({ name: 'Midnight Drive', owner: 'fred', mine: false })),
    FSI + 'Midnight Drive' + PDI + ' \u2014 by ' + FSI + 'fred' + PDI);
  assert.equal(themeLabel(custom({ name: 'Mine', mine: true })), FSI + 'Mine' + PDI);
  assert.equal(themeLabel(custom({ name: 'Mine', mine: true, private: true })), FSI + 'Mine' + PDI + ' (private)');
  assert.equal(themeLabel(custom({ name: '', owner: '', mine: false })), FSI + 'Untitled theme' + PDI + ' \u2014 by ' + FSI + 'someone' + PDI);
  assert.equal(themeLabel(custom({ name: 5, owner: null, mine: false })), FSI + 'Untitled theme' + PDI + ' \u2014 by ' + FSI + 'someone' + PDI);
  // the panel's labels, for the same theme
  const t = { name: 'Midnight Drive', owner: { name: 'fred' } };
  assert.equal(themeLabel(custom({ name: t.name, owner: 'fred', mine: false })), ThemeApply.communityLabel(t));
  assert.equal(themeLabel(custom({ name: t.name, mine: true, private: true })), ThemeApply.mineLabel({ name: t.name, visibility: 'private' }));
  assert.equal(themeLabel(custom({ name: t.name, mine: true })), ThemeApply.mineLabel({ name: t.name, visibility: 'public' }));
});

test('the picture is a theme-images link of the bot and nothing else', () => {
  assert.deepEqual(pictureLayer(custom({ picture: PIC }), false), { url: PIC, overlay: 'rgba(0, 0, 0, 0.55)', freeze: false });
  for (const ext of ['jpg', 'png', 'webp', 'gif']) {
    const url = 'https://bot.example/theme-images/' + 'a'.repeat(32) + '.' + ext;
    assert.equal(pictureLayer(custom({ picture: url }), false).url, url, ext);
  }
  assert.equal(pictureLayer(custom({ picture: 'http://10.0.0.43:8888/theme-images/' + 'a'.repeat(32) + '.jpg' }), false).url,
    'http://10.0.0.43:8888/theme-images/' + 'a'.repeat(32) + '.jpg', 'a bot on a plain address still has its link');
  const bad = [
    null, undefined, '', 5, 'javascript:alert(1)', 'data:image/gif;base64,AAAA', 'file:///C:/x.png', '//evil.example/theme-images/' + 'a'.repeat(32) + '.jpg',
    '/theme-images/' + 'a'.repeat(32) + '.jpg', 'https://bot.example/theme-images/' + 'a'.repeat(31) + '.jpg',
    'https://bot.example/theme-images/' + 'a'.repeat(33) + '.jpg', 'https://bot.example/theme-images/' + 'A'.repeat(32) + '.jpg',
    'https://bot.example/theme-images/' + 'a'.repeat(32) + '.svg', 'https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg?x=1',
    'https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg#x', 'https://bot.example/theme-images/../x/' + 'a'.repeat(32) + '.jpg',
    'https://bot.example/other/' + 'a'.repeat(32) + '.jpg', 'https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg\n',
    'https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg" onerror="x', ' https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg',
    'https://bot.example/theme-images/' + 'a'.repeat(32) + '.jpg ', 'https://user:pw@bot.example/theme-images/' + 'a'.repeat(32) + '.jpg'
  ];
  for (const picture of bad) assert.equal(pictureLayer(custom({ picture }), false), null, String(picture));
  assert.equal(pictureLayer(null, false), null);
  assert.equal(pictureLayer(undefined, true), null);
});

test('a version that does not use the picture has no picture layer', () => {
  // the app sends no picture for useImage false, or for useImage true when the theme has no background picture
  assert.equal(pictureLayer(custom({ picture: null }), false), null);
  assert.equal(pictureLayer(custom({}), true), null);
});

test('the picture is darkened by the version\'s own amount, held to 0-90', () => {
  const dim = (value) => pictureLayer(custom({ picture: PIC, dim: value }), false).overlay;
  assert.equal(dim(0), 'rgba(0, 0, 0, 0)');
  assert.equal(dim(45), 'rgba(0, 0, 0, 0.45)');
  assert.equal(dim(90), 'rgba(0, 0, 0, 0.9)');
  assert.equal(dim(200), 'rgba(0, 0, 0, 0.9)');
  assert.equal(dim(-5), 'rgba(0, 0, 0, 0)');
  assert.equal(dim('30'), 'rgba(0, 0, 0, 0.3)');
  for (const odd of [undefined, null, NaN, 'dark', Infinity]) assert.equal(dim(odd), 'rgba(0, 0, 0, 0)', String(odd));
  // the same overlay the panel's own layer is drawn with
  assert.equal(dim(45), ThemeApply.backdrop({ url: '/theme-images/' + 'a'.repeat(32) + '.jpg', dim: 45, blur: 0, fit: 'cover' }).overlay);
});

test('Reduce motion freezes the pictures that can move: GIF, WebP and PNG (an animated PNG is a .png)', () => {
  const at = (ext, reduce) => pictureLayer(custom({ picture: 'https://bot.example/theme-images/' + 'b'.repeat(32) + '.' + ext }), reduce).freeze;
  for (const ext of ['gif', 'webp', 'png']) {
    assert.equal(at(ext, true), true, ext + ' with Reduce motion');
    assert.equal(at(ext, false), false, ext + ' without it');
  }
  assert.equal(at('jpg', true), false, 'a JPEG never moves');
  assert.equal(at('jpg', false), false);
  // the same call the panel makes
  for (const ext of ['gif', 'webp', 'png', 'jpg']) {
    assert.equal(at(ext, true), ThemeApply.canAnimate('/theme-images/' + 'b'.repeat(32) + '.' + ext), ext);
  }
  assert.equal(pictureLayer(custom({ picture: PIC }), 'yes').freeze, true, 'any truthy answer from the system counts');
  assert.equal(pictureLayer(custom({ picture: PIC }), undefined).freeze, false);
});

test('a theme brings its picture only while its own colors are the ones in use', () => {
  const theme = { id: 'ember', vars: {}, custom: custom({ picture: PIC }) };
  assert.deepEqual(themePicture(theme, false), pictureLayer(theme.custom, false));
  assert.equal(themePicture(theme, true).freeze, true);
  // a palette that can't be used shows the base skin, and a picture over that would be a stranger's
  const broken = { id: 'ember', vars: {}, custom: custom({ picture: PIC, colors: Object.assign({}, MINI_COLORS, { accent: 'nope' }) }) };
  assert.equal(themePicture(broken, false), null);
  assert.equal(themePicture({ id: 'neon', vars: { '--accent': '#0ff' } }, false), null, 'a built-in theme has none');
  assert.equal(themePicture({ id: 'neon', vars: {}, custom: null }, true), null);
  assert.equal(themePicture(null, true), null);
  assert.equal(themePicture(undefined, false), null);
});

// ---- the picture sits behind the content, under the dim, faded by the opacity slider ----

const MINI_CSS = fs.readFileSync(path.join(__dirname, 'mini.css'), 'utf8').replace(/\r/g, '');
const MINI_HTML = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8').replace(/\r/g, '');

function cssRule(css, selector) {
  const at = css.indexOf('\n' + selector + ' {');
  assert.ok(at >= 0, selector + ' not found');
  return css.slice(at, css.indexOf('}', at));
}

// The picture and the dark layer over it share one rule for what they have in common.
const LAYERS = '.bg-pic,\n.bg-dim';

test('the picture layers fade with the opacity slider, like the card', () => {
  assert.match(cssRule(MINI_CSS, LAYERS), /opacity:\s*var\(--card-alpha\)/);
  assert.match(cssRule(MINI_CSS, '.mini'), /background:\s*color-mix\(in srgb, var\(--bg-card\) calc\(var\(--card-alpha\) \* 100%\)/);
  assert.match(cssRule(MINI_CSS, '.mini::before,\n.mini::after'), /opacity:\s*var\(--card-alpha\)/, 'as the glow does');
  // and the slider reaches them through the one variable it sets
  const { applyOpacity } = require('./view.js');
  const root = fakeRoot();
  root.classList = { toggle() {} };
  applyOpacity(root, 0.4);
  assert.equal(root.props['--card-alpha'], '0.4');
});

test('the picture is under the dim, which is under the glow and the content (the creator\'s own stacking)', () => {
  // the rule whose selector is exactly this one and sets a z-index (.bg-dim also ends the shared rule above it)
  const z = (selector) => {
    const found = new RegExp('\\n' + selector.replace(/[.\[\]]/g, '\\$&') + ' \\{[^}]*z-index:\\s*(-?\\d+)').exec(MINI_CSS);
    assert.ok(found, selector + ' sets no z-index');
    return Number(found[1]);
  };
  const pic = z('.bg-pic');
  const dim = z('.bg-dim');
  const glow = z('.mini::before,\n.mini::after');
  assert.ok(pic < dim && dim < glow && glow < 0, `${pic} < ${dim} < ${glow} < 0`);
  assert.match(cssRule(MINI_CSS, '.mini'), /isolation:\s*isolate/, 'the layers stay inside the card');
  assert.match(cssRule(MINI_CSS, '.mini'), /overflow:\s*hidden/, 'clipped by its rounded corners');
  const shared = cssRule(MINI_CSS, LAYERS);
  assert.match(shared, /position:\s*absolute/);
  assert.match(shared, /inset:\s*0/);
  assert.match(shared, /pointer-events:\s*none/, 'the card can still be dragged');
  assert.match(cssRule(MINI_CSS, '.bg-pic'), /object-fit:\s*cover/, 'as the creator\'s preview fits it');
});

test('the picture layers live inside the player card, first, so the content is on top of them', () => {
  const main = MINI_HTML.indexOf('<main class="mini"');
  const disc = MINI_HTML.indexOf('<div class="disc"');
  for (const id of ['bgPic', 'bgFrame', 'bgDim']) {
    const at = MINI_HTML.indexOf('id="' + id + '"');
    assert.ok(at > main && at < disc, id + ' is in the card before the artwork');
    assert.match(MINI_HTML.slice(at, MINI_HTML.indexOf('>', at)), /\bhidden\b/, id + ' starts hidden');
  }
  assert.match(MINI_HTML.slice(MINI_HTML.indexOf('id="bgFrame"') - 40, MINI_HTML.indexOf('id="bgFrame"')), /<canvas/);
  assert.match(MINI_HTML.slice(MINI_HTML.indexOf('id="bgPic"') - 40, MINI_HTML.indexOf('id="bgPic"')), /<img/);
});

test('What\'s new is a row of the menu\'s main view, not in the settings', () => {
  const menu = MINI_HTML.indexOf('id="menuPanel"');
  const search = MINI_HTML.indexOf('id="searchForm"');
  const settings = MINI_HTML.indexOf('id="settings"');
  const button = MINI_HTML.indexOf('id="whatsNewBtn"');
  assert.ok(button > menu && button < settings, 'in the menu, before the settings pane starts');
  assert.ok(button > search, 'with the search row\'s buttons');
  assert.equal(MINI_HTML.split('id="whatsNewBtn"').length, 2, 'once');
  const settingsBlock = MINI_HTML.slice(settings, MINI_HTML.indexOf('id="lyricsPanel"'));
  assert.doesNotMatch(settingsBlock, /What's new|whatsNew/);
});

test('a custom skin has no rule of its own in themes.css, so it gets mini.css\'s base look (glow, 16px corners)', () => {
  const themes = fs.readFileSync(path.join(__dirname, 'themes.css'), 'utf8');
  assert.doesNotMatch(themes, /data-theme="custom"/);
  assert.doesNotMatch(themes, /data-theme\^=|data-theme\*=|:not\(\[data-theme/);
});
