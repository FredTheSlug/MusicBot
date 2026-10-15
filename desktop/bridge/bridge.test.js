'use strict';
// Runs the real bridge.js against a stand-in page (no DOM, no network), the way the app injects it.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const CORE = fs.readFileSync(path.join(__dirname, 'core.js'), 'utf8');
const BRIDGE = fs.readFileSync(path.join(__dirname, 'bridge.js'), 'utf8');

/**
 * A page with server 9 picked. reports = what the app was sent, in order; timers = pending setTimeouts.
 * opts.me = what /api/me answers once the socket opens (by default nothing ever answers).
 */
function loadBridge(opts) {
  const me = opts && opts.me;
  const reports = [];
  const sockets = [];
  const timers = [];
  const win = {
    __TAURI_INTERNALS__: {
      invoke(cmd, args) {
        // As the app receives it: JSON, so the objects are this realm's own.
        if (cmd === 'bridge_report') reports.push(JSON.parse(JSON.stringify(args.report)));
        return Promise.resolve();
      }
    }
  };
  const root = { getAttribute: () => null };
  class FakeSocket {
    constructor(url) { this.url = url; sockets.push(this); }
    close() {}
  }
  const sandbox = {
    window: win,
    document: { readyState: 'complete', documentElement: root, getElementById: () => null, addEventListener() {} },
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    localStorage: { getItem: (key) => (key === 'defaultGuild' ? '9' : null) },
    location: { protocol: 'https:', host: 'bot.example' },
    WebSocket: FakeSocket,
    fetch: (url) => {
      if (!me) return new Promise(() => {});
      const body = url === '/api/me' ? me : {};
      return Promise.resolve({ status: 200, ok: true, text: () => Promise.resolve(JSON.stringify(body)) });
    },
    MutationObserver: class { observe() {} },
    setInterval: () => 0,
    setTimeout: (fn, ms) => { const t = { fn, ms, live: true }; timers.push(t); return t; },
    clearTimeout: (t) => { if (t) t.live = false; }
  };
  vm.createContext(sandbox);
  vm.runInContext(CORE, sandbox);
  vm.runInContext(BRIDGE, sandbox);
  return {
    reports, timers,
    socket: sockets[0],
    /** Runs the timers that are waiting (what the clock would do), once. */
    tick() { timers.splice(0).filter((t) => t.live).forEach((t) => t.fn()); },
    themesReports() { return reports.filter((r) => r.type === 'themes'); },
    likesReports() { return reports.filter((r) => r.type === 'likes'); },
    /** The socket opens: the bridge reads who is signed in (and the player), as on a real connect. */
    async open() {
      sockets[0].onopen();
      for (let i = 0; i < 10; i++) await new Promise((resolve) => setImmediate(resolve));
    }
  };
}

const THEMES_MESSAGE = (guild) => JSON.stringify({ type: 'themes', guildId: guild, data: { id: 'k3j9x0q2m7ab' } });

test('aThemesMessageFromTheBotTellsTheAppSoItRefreshesTheMiniPlayer', () => {
  const page = loadBridge();
  assert.ok(page.socket, 'the bridge watches the picked server');
  page.socket.onmessage({ data: THEMES_MESSAGE('9') });
  page.tick();
  assert.deepEqual(page.themesReports(), [{ type: 'themes' }]);
});

test('aBurstOfThemesMessagesIsOneReportAfterTheLastOne', () => {
  const page = loadBridge();
  page.socket.onmessage({ data: THEMES_MESSAGE('9') });
  page.socket.onmessage({ data: THEMES_MESSAGE('9') });
  page.socket.onmessage({ data: THEMES_MESSAGE('9') });
  assert.equal(page.themesReports().length, 0, 'nothing is sent while the burst may still go on');
  const waiting = page.timers.filter((t) => t.live);
  assert.equal(waiting.length, 1, 'one timer waits, not one per message');
  assert.ok(waiting[0].ms > 0 && waiting[0].ms <= 1000, 'a short wait: ' + waiting[0].ms);
  page.tick();
  assert.equal(page.themesReports().length, 1);
});

test('otherMessagesAndOtherServersSendNoThemesReport', () => {
  const page = loadBridge();
  page.socket.onmessage({ data: JSON.stringify({ type: 'playlists', guildId: '9', data: {} }) });
  page.socket.onmessage({ data: JSON.stringify({ type: 'queue', guildId: '9', data: { tracks: [] } }) });
  page.socket.onmessage({ data: THEMES_MESSAGE('8') });
  page.socket.onmessage({ data: 'pong' });
  page.tick();
  assert.equal(page.themesReports().length, 0);
});

// ---- Likes: the app asks for your list again when the bot says it changed ----

const LIKES_MESSAGE = (guild, userId) => JSON.stringify({ type: 'likes', guildId: guild, data: { userId: userId } });

test('aChangeToYourOwnLikesTellsTheAppOnceAfterABurst', async () => {
  const page = loadBridge({ me: { id: '1', name: 'fred', avatarUrl: null } });
  await page.open();
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '1') });
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '1') });
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '1') });
  assert.equal(page.likesReports().length, 0, 'nothing is sent while the burst may still go on');
  const waiting = page.timers.filter((t) => t.live);
  assert.equal(waiting.length, 1, 'one timer waits, not one per message');
  assert.ok(waiting[0].ms > 0 && waiting[0].ms <= 1000, 'a short wait: ' + waiting[0].ms);
  page.tick();
  assert.deepEqual(page.likesReports(), [{ type: 'likes' }], 'a bare report: no song data, no user id');
});

test('aFriendsLikesChangeNothingTheAppShows', async () => {
  const page = loadBridge({ me: { id: '1', name: 'fred', avatarUrl: null } });
  await page.open();
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '2') });
  page.tick();
  assert.equal(page.likesReports().length, 0);
});

test('beforeTheBridgeKnowsWhoIsSignedInEveryLikesMessageCounts', () => {
  const page = loadBridge();
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '2') });
  page.tick();
  assert.equal(page.likesReports().length, 1);
});

test('likesAndThemesReportsWaitOnTheirOwnTimers', () => {
  const page = loadBridge();
  page.socket.onmessage({ data: THEMES_MESSAGE('9') });
  page.socket.onmessage({ data: LIKES_MESSAGE('9', '1') });
  page.socket.onmessage({ data: LIKES_MESSAGE('8', '1') });
  page.tick();
  assert.equal(page.themesReports().length, 1, 'a likes message does not cancel a themes report');
  assert.equal(page.likesReports().length, 1, "another server's message is not this page's");
});
