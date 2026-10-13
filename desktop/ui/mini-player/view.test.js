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
