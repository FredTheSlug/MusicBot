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
