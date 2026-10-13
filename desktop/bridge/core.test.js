'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { toBotState, parseWsMessage, commandToRequest, isOwnLoadError, readTheme, THEME_VARS } = require('./core.js');

test('maps now playing, queue and me into BotState', () => {
  const s = toBotState({ connected: true, signedIn: true, me: { id: '1', name: 'fred', avatarUrl: '' }, guild: { id: '9', name: 'CK' },
    nowPlaying: { playing: true, paused: false, title: 'T', author: 'A', artworkUrl: 'u', uri: 'yt', duration: 214000, position: 85000, isStream: false, requestedBy: 'fred', requestedById: '1', volume: 80 },
    queue: { tracks: [{ title: 'Next', requestedById: '2' }, { title: 'Later', requestedById: '1' }] } });
  assert.deepEqual(s.track, { title: 'T', author: 'A', artworkUrl: 'u', uri: 'yt', durationMs: 214000, positionMs: 85000, isStream: false, requestedBy: 'fred', requestedById: '1' });
  assert.equal(s.paused, false); assert.equal(s.volume, 80);
  assert.deepEqual(s.queueHead, { title: 'Next', requestedById: '2' });
  assert.deepEqual(s.me, { id: '1', name: 'fred', avatarUrl: '' });
  assert.deepEqual(s.guild, { id: '9', name: 'CK' });
  assert.equal(s.connected, true); assert.equal(s.signedIn, true);
});

test('nothing playing gives null track and empty queue gives null head', () => {
  const s = toBotState({ connected: true, signedIn: true, me: null, guild: { id: '9', name: 'CK' },
    nowPlaying: { playing: false, paused: false, volume: 100 }, queue: { tracks: [] } });
  assert.equal(s.track, null);
  assert.equal(s.queueHead, null);
  assert.equal(s.volume, 100);
});

test('missing requestedById and empty requestedBy become null', () => {
  const s = toBotState({ connected: true, signedIn: true, me: null, guild: { id: '9', name: 'CK' },
    nowPlaying: { playing: true, paused: false, title: 'T', author: 'A', uri: 'yt', duration: 1000, position: 0, isStream: false, requestedBy: '', volume: 100 },
    queue: { tracks: [] } });
  assert.equal(s.track.requestedById, null);
  assert.equal(s.track.requestedBy, null);
});

test('signed_out_state_carries_no_track', () => {
  const s = toBotState({ connected: true, signedIn: false, me: null, guild: null, nowPlaying: { playing: true, title: 'T' }, queue: { tracks: [{ title: 'x' }] } });
  assert.equal(s.signedIn, false); assert.equal(s.track, null); assert.equal(s.queueHead, null); assert.equal(s.me, null);
  assert.equal(s.paused, false); assert.equal(s.volume, 100);
});

test('optional fields get the defaults BotState needs', () => {
  const s = toBotState({ connected: false, signedIn: true, me: null, guild: null,
    nowPlaying: { playing: true, title: 'T' }, queue: null });
  assert.equal(s.paused, false);
  assert.equal(s.volume, 100);
  assert.equal(s.track.uri, '');
  assert.equal(s.track.author, '');
  assert.equal(s.track.artworkUrl, null);
  assert.equal(s.track.durationMs, 0);
  assert.equal(s.track.positionMs, 0);
  assert.equal(s.queueHead, null);
});

test('parse_ws_message_keeps_only_watched_guild', () => {
  assert.deepEqual(parseWsMessage('{"type":"player","guildId":"9","data":{"playing":false}}', '9'), { type: 'player', data: { playing: false } });
  assert.equal(parseWsMessage('{"type":"player","guildId":"8","data":{}}', '9'), null);
  assert.deepEqual(parseWsMessage('{"type":"error","guildId":"9","code":"NO_MATCHES","message":"No results"}', '9'), { type: 'error', code: 'NO_MATCHES', message: 'No results' });
  assert.equal(parseWsMessage('pong', '9'), null);
  assert.equal(parseWsMessage('{bad', '9'), null);
});

test('commands map to panel endpoints', () => {
  const g = '9', st = { paused: false };
  assert.deepEqual(commandToRequest(g, { type: 'TogglePause' }, st), { method: 'POST', path: '/api/guild/9/pause', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Skip' }, st), { method: 'POST', path: '/api/guild/9/skip', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'RestartTrack' }, st), { method: 'POST', path: '/api/guild/9/seek', body: { position: 0 } });
  assert.deepEqual(commandToRequest(g, { type: 'SetVolume', value: 200 }, st).body, { volume: 150 });
  assert.deepEqual(commandToRequest(g, { type: 'SetVolume', value: -5 }, st).body, { volume: 0 });
  assert.deepEqual(commandToRequest(g, { type: 'PlayQuery', value: 'A - T' }, st), { method: 'POST', path: '/api/guild/9/play', body: { query: 'A - T' } });
  assert.deepEqual(commandToRequest(g, { type: 'PlaySpotifyLink', value: 'https://open.spotify.com/playlist/x' }, st).body, { query: 'https://open.spotify.com/playlist/x' });
  assert.equal(commandToRequest(g, { type: 'Pause' }, { paused: true }), null);
  assert.equal(commandToRequest(g, { type: 'Pause' }, st).path, '/api/guild/9/pause');
  assert.equal(commandToRequest('', { type: 'Skip' }, st), null);
  assert.equal(commandToRequest(g, { type: 'Bogus' }, st), null);
});

test('command_paths_encode_guild_id', () => {
  assert.equal(commandToRequest('9/../x', { type: 'Skip' }, { paused: false }).path, '/api/guild/9%2F..%2Fx/skip');
});

test('position advances from when the page received it', () => {
  // The bot only sends `player` on events, so heartbeats and queue updates re-report an old position.
  const np = { playing: true, paused: false, title: 'T', duration: 200000, position: 5000, isStream: false };
  const snap = { connected: true, signedIn: true, guild: { id: '9', name: 'CK' }, nowPlaying: np, nowPlayingAt: 1000, queue: null };
  assert.equal(toBotState(snap, 31000).track.positionMs, 35000);
  assert.equal(toBotState(snap, 900000).track.positionMs, 200000, 'capped at the track length');
  assert.equal(toBotState(Object.assign({}, snap, { nowPlaying: Object.assign({}, np, { paused: true }) }), 31000).track.positionMs, 5000, 'paused stays put');
  assert.equal(toBotState(Object.assign({}, snap, { nowPlaying: Object.assign({}, np, { isStream: true }) }), 31000).track.positionMs, 5000, 'streams have no position to advance');
  assert.equal(toBotState(snap).track.positionMs, 5000, 'no clock given: as reported');
});

test('load errors count only shortly after this app asked to play', () => {
  // The bot broadcasts load errors to everyone watching the server, including friends' failed searches.
  assert.equal(isOwnLoadError(10000, 0), false, 'this app never asked to play');
  assert.equal(isOwnLoadError(10000, 5000), true);
  assert.equal(isOwnLoadError(5000 + 30000, 5000), true, 'Spotify playlists can take a while to resolve');
  assert.equal(isOwnLoadError(5000 + 30001, 5000), false, 'too long after our request');
});

test('fetch commands map to GET endpoints', () => {
  assert.deepEqual(commandToRequest('9', { type: 'Search', value: '  Ke$ha & "Tik" Tok ' }, {}),
    { method: 'GET', path: '/api/guild/9/search?q=Ke%24ha%20%26%20%22Tik%22%20Tok', body: null });
  assert.equal(commandToRequest('9', { type: 'Search', value: '   ' }, {}), null);
  assert.deepEqual(commandToRequest('9', { type: 'Lyrics' }, {}), { method: 'GET', path: '/api/guild/9/lyrics', body: null });
});

test('readTheme builds id and vars', () => {
  assert.equal(THEME_VARS.length, 16);
  const getVar = (name) => (name === '--accent' ? ' #fff ' : '');
  assert.deepEqual(readTheme('neon', getVar), { id: 'neon', vars: { '--accent': '#fff' } });
  assert.deepEqual(readTheme(null, () => ''), { id: 'nebula', vars: {} });
});

test('queue and modes map into BotState', () => {
  const tracks = Array.from({ length: 22 }, (_, i) => ({ title: 'T' + i, author: 'A', uri: 'u' + i, duration: 1000 * i, requestedBy: i % 2 ? 'fred' : '' }));
  const s = toBotState({ connected: true, signedIn: true, guild: { id: '9', name: 'CK' },
    nowPlaying: { playing: true, title: 'Now', duration: 1000, position: 0, shuffleMode: true, repeatMode: 'ALL' },
    queue: { tracks: tracks, size: 22 } });
  assert.equal(s.upNext.length, 20);
  assert.deepEqual(s.upNext[1], { title: 'T1', author: 'A', uri: 'u1', durationMs: 1000, requestedBy: 'fred' });
  assert.equal(s.upNext[0].requestedBy, null);
  assert.equal(s.queueLength, 22);
  assert.equal(s.shuffle, true);
  assert.equal(s.repeat, 'ALL');
  assert.equal(toBotState({ connected: true, signedIn: true, nowPlaying: { repeatMode: 'WEIRD' } }).repeat, 'OFF');
  const out = toBotState({ connected: true, signedIn: false, nowPlaying: { shuffleMode: true, repeatMode: 'ALL' }, queue: { tracks: tracks } });
  assert.deepEqual(out.upNext, []);
  assert.equal(out.queueLength, 0);
  assert.equal(out.repeat, 'OFF');
  assert.equal(out.shuffle, false);
});

const { queueEditError } = require('./core.js');

test('new commands map to endpoints', () => {
  const g = '9';
  assert.deepEqual(commandToRequest(g, { type: 'ToggleShuffle' }, {}), { method: 'POST', path: '/api/guild/9/shuffle', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'CycleRepeat' }, {}), { method: 'POST', path: '/api/guild/9/repeat', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Seek', value: 61000.7 }, {}), { method: 'POST', path: '/api/guild/9/seek', body: { position: 61000 } });
  assert.equal(commandToRequest(g, { type: 'Seek', value: -5 }, {}), null);
  assert.deepEqual(commandToRequest(g, { type: 'RemoveFromQueue', value: { index: 3, uri: 'u3' } }, {}), { method: 'POST', path: '/api/guild/9/remove/3', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'MoveToTop', value: { index: 3, uri: 'u3' } }, {}), { method: 'POST', path: '/api/guild/9/reorder', body: { from: 3, to: 0 } });
  assert.equal(commandToRequest(g, { type: 'RemoveFromQueue', value: { index: -1, uri: 'x' } }, {}), null);
  assert.deepEqual(commandToRequest(g, { type: 'PlayPlaylist', value: { name: 'Chill / Vibes', folder: "Fred's" } }, {}),
    { method: 'POST', path: '/api/guild/9/playlists/play', body: { name: 'Chill / Vibes', folder: "Fred's", mode: 'all' } });
  assert.deepEqual(commandToRequest(g, { type: 'PlayPlaylist', value: { name: 'Rap', folder: null } }, {}).body, { name: 'Rap', folder: '', mode: 'all' });
  assert.equal(commandToRequest(g, { type: 'PlayPlaylist', value: { name: '', folder: null } }, {}), null);
  assert.deepEqual(commandToRequest(g, { type: 'History' }, {}), { method: 'GET', path: '/api/guild/9/history', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Frequent' }, {}), { method: 'GET', path: '/api/guild/9/frequent', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Playlists' }, {}), { method: 'GET', path: '/api/playlists-tree', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Lyrics', value: { title: 'Ke$ha & "Tik"', author: 'Kesha', durationMs: 200000 } }, {}),
    { method: 'GET', path: '/api/guild/9/lyrics?title=Ke%24ha%20%26%20%22Tik%22&author=Kesha&duration=200000', body: null });
});

test('queue edits check the song', () => {
  const queue = { tracks: [{ uri: 'a' }, { uri: 'b' }] };
  assert.equal(queueEditError(queue, 1, 'b'), null);
  assert.equal(queueEditError(queue, 1, 'a'), 'The queue changed — try again');
  assert.equal(queueEditError(queue, 5, 'b'), 'The queue changed — try again');
  assert.equal(queueEditError(null, 0, 'a'), 'The queue changed — try again');
});

test('autoplay flag maps and toggles', () => {
  const base = { connected: true, signedIn: true, guild: { id: '9', name: 'CK' }, queue: null };
  assert.equal(toBotState(Object.assign({}, base, { nowPlaying: { autoplay: true } })).autoplay, true);
  assert.equal(toBotState(Object.assign({}, base, { nowPlaying: { autoplay: false } })).autoplay, false);
  assert.equal(toBotState(Object.assign({}, base, { nowPlaying: {} })).autoplay, null, 'an older bot without the setting');
  assert.equal(toBotState(Object.assign({}, base, { signedIn: false, nowPlaying: { autoplay: true } })).autoplay, null);
  assert.deepEqual(commandToRequest('9', { type: 'ToggleAutoplay' }, {}), { method: 'POST', path: '/api/guild/9/autoplay', body: null });
});

const { fallbackRequest } = require('./core.js');

const SONG = { uri: 'https://youtu.be/abc', title: 'Ke$ha "Tik" Tok', author: 'Kesha', durationMs: 200000, artworkUrl: 'https://i.ytimg.com/vi/abc/hqdefault.jpg' };

test('the v2 playlist tree is a fetch that is not under a server', () => {
  assert.deepEqual(commandToRequest('9', { type: 'PlaylistsTreeV2' }, {}), { method: 'GET', path: '/api/playlists/tree', body: null });
  // The old tree stays available for the app's own fallback.
  assert.deepEqual(commandToRequest('9', { type: 'Playlists' }, {}), { method: 'GET', path: '/api/playlists-tree', body: null });
});

test('AddToPlaylist posts one song to the named playlist', () => {
  assert.deepEqual(commandToRequest('9', { type: 'AddToPlaylist', value: { name: 'Chill', folder: 'Fred/Old', song: SONG } }, {}),
    { method: 'POST', path: '/api/playlists/songs/add', body: { name: 'Chill', folder: 'Fred/Old', songs: [SONG] } });
  // A top-level playlist has no folder; the bot wants it as "".
  const top = commandToRequest('9', { type: 'AddToPlaylist', value: { name: 'Chill', folder: null, song: Object.assign({}, SONG, { artworkUrl: null }) } }, {});
  assert.equal(top.body.folder, '');
  assert.equal(top.body.songs[0].artworkUrl, null);
});

test('AddToPlaylist sends only the five song fields, cleaned', () => {
  const dirty = { uri: 'u', title: 5, author: null, durationMs: -3, artworkUrl: '', extra: 'x', addedBy: { id: '1', name: 'evil' } };
  const req = commandToRequest('9', { type: 'AddToPlaylist', value: { name: 'P', folder: '', song: dirty } }, {});
  assert.deepEqual(req.body.songs, [{ uri: 'u', title: '', author: '', durationMs: 0, artworkUrl: null }]);
  assert.equal(commandToRequest('9', { type: 'AddToPlaylist', value: { name: 'P', folder: '', song: { uri: '' } } }, {}), null);
  assert.equal(commandToRequest('9', { type: 'AddToPlaylist', value: { name: '', folder: '', song: SONG } }, {}), null);
  assert.equal(commandToRequest('9', { type: 'AddToPlaylist', value: { name: 'P', folder: '' } }, {}), null);
  assert.equal(commandToRequest('9', { type: 'AddToPlaylist' }, {}), null);
});

test('CreatePlaylist posts the name and folder', () => {
  assert.deepEqual(commandToRequest('9', { type: 'CreatePlaylist', value: { name: 'Road trip', folder: null } }, {}),
    { method: 'POST', path: '/api/playlists', body: { name: 'Road trip', folder: '' } });
  assert.deepEqual(commandToRequest('9', { type: 'CreatePlaylist', value: { name: 'Road trip', folder: 'Fred' } }, {}).body, { name: 'Road trip', folder: 'Fred' });
  assert.equal(commandToRequest('9', { type: 'CreatePlaylist', value: { name: '   ', folder: null } }, {}), null);
  assert.equal(commandToRequest('9', { type: 'CreatePlaylist' }, {}), null);
});

test('play falls back to the old endpoint when the bot has no such route', () => {
  const play = { type: 'PlayPlaylist', value: { name: 'Chill / Vibes', folder: "Fred's" } };
  // An older bot answers an unknown route with a plain 404 (no JSON error).
  assert.deepEqual(fallbackRequest('9', play, 404, null),
    { method: 'POST', path: "/api/guild/9/playplaylist/Chill%20%2F%20Vibes?folder=Fred's", body: null });
  assert.deepEqual(fallbackRequest('9', play, 404, { message: 'x' }).method, 'POST', 'a JSON answer without an error is still not our route');
  assert.equal(fallbackRequest('9', { type: 'PlayPlaylist', value: { name: 'Rap', folder: null } }, 404, null).path, '/api/guild/9/playplaylist/Rap');
});

test('play does not fall back when the new route itself answered', () => {
  const play = { type: 'PlayPlaylist', value: { name: 'Rap', folder: '' } };
  assert.equal(fallbackRequest('9', play, 404, { error: 'Playlist not found' }), null, 'the playlist is gone: the old route would say the same');
  assert.equal(fallbackRequest('9', play, 400, null), null);
  assert.equal(fallbackRequest('9', play, 500, null), null);
  assert.equal(fallbackRequest('9', play, 200, { message: 'Loading playlist Rap...' }), null);
});

test('only play has a fallback', () => {
  assert.equal(fallbackRequest('9', { type: 'Skip' }, 404, null), null);
  assert.equal(fallbackRequest('9', { type: 'PlaylistsTreeV2' }, 404, null), null);
  assert.equal(fallbackRequest('9', { type: 'AddToPlaylist', value: { name: 'P', folder: '', song: SONG } }, 404, null), null);
  assert.equal(fallbackRequest('', { type: 'PlayPlaylist', value: { name: 'Rap' } }, 404, null), null);
  assert.equal(fallbackRequest('9', { type: 'PlayPlaylist', value: {} }, 404, null), null);
  assert.equal(fallbackRequest('9', null, 404, null), null);
});
