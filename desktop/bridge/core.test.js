'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { toBotState, parseWsMessage, commandToRequest, isOwnLoadError } = require('./core.js');

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
