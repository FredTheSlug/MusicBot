/*
 * Runs inside the panel page (injected by the desktop app, only on the bot's own site).
 * Uses the page's own login to watch the selected server and reports a compact state to the
 * app; runs commands the app sends back via window.__cksBridge.run(id, command).
 */
(function () {
  'use strict';
  if (window.__cksBridge) return;
  var core = window.__cksCore;

  var HEARTBEAT_MS = 30000;
  var GUILD_POLL_MS = 2000;
  var BACKOFF_START_MS = 1000;
  var BACKOFF_MAX_MS = 30000;

  var snap = { connected: false, signedIn: true, me: null, guild: null, nowPlaying: null, nowPlayingAt: 0, queue: null };
  var guildId = '';
  var ws = null;
  var backoff = BACKOFF_START_MS;
  var reconnectTimer = null;
  var lastPlayAt = 0;

  function invoke(cmd, args) {
    var tauri = window.__TAURI__;
    var internals = window.__TAURI_INTERNALS__;
    if (tauri && tauri.core && tauri.core.invoke) return tauri.core.invoke(cmd, args);
    if (internals && internals.invoke) return internals.invoke(cmd, args);
    return Promise.reject(new Error('Tauri IPC unavailable'));
  }

  function report(r) { invoke('bridge_report', { report: r }).catch(function () {}); }
  function reportState() { report({ type: 'state', state: core.toBotState(snap, Date.now()) }); }

  /** The bot sends positions only on events, so remember when this one arrived. */
  function setNowPlaying(np) {
    snap.nowPlaying = np;
    snap.nowPlayingAt = Date.now();
  }
  function notice(level, message) { report({ type: 'notice', level: level, message: message }); }

  function selectedGuild() {
    try { return localStorage.getItem('defaultGuild') || ''; } catch (e) { return ''; }
  }

  function guildName(id) {
    var sel = document.getElementById('guildSelect');
    if (!sel) return '';
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === id) return sel.options[i].textContent.replace(/\s*\(.*$/, '').replace(/\s*[●○]\s*$/, '');
    }
    return '';
  }

  function getJson(path) {
    return fetch(path, { credentials: 'same-origin' }).then(function (resp) {
      return resp.text().then(function (text) {
        var body = null;
        try { body = JSON.parse(text); } catch (e) { /* not JSON */ }
        return { status: resp.status, body: body };
      });
    });
  }

  function signedOut() {
    snap.signedIn = false;
    snap.me = null;
    snap.nowPlaying = null;
    snap.queue = null;
    reportState();
  }

  /** Re-reads who we are and the selected server's player and queue. */
  function refresh() {
    return getJson('/api/me').then(function (me) {
      if (me.status === 401) { signedOut(); return; }
      snap.signedIn = true;
      snap.me = me.status === 200 ? me.body : null;
      if (!guildId) { reportState(); return; }
      var base = '/api/guild/' + encodeURIComponent(guildId);
      return Promise.all([getJson(base + '/nowplaying'), getJson(base + '/queue')]).then(function (res) {
        if (res[0].status === 401 || res[1].status === 401) { signedOut(); return; }
        setNowPlaying(res[0].status === 200 ? res[0].body : null);
        snap.queue = res[1].status === 200 ? res[1].body : null;
        reportState();
      });
    }).catch(function () { /* network blip: the WebSocket close path handles reconnecting */ });
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
  }

  function connect() {
    clearTimeout(reconnectTimer);
    if (ws) { ws.onclose = null; try { ws.close(); } catch (e) { /* already closed */ } ws = null; }
    guildId = selectedGuild();
    snap.guild = guildId ? { id: guildId, name: guildName(guildId) } : null;
    snap.nowPlaying = null;
    snap.queue = null;
    if (!guildId) { snap.connected = true; refresh(); return; }

    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws?guild=' + encodeURIComponent(guildId));
    ws.onopen = function () {
      backoff = BACKOFF_START_MS;
      snap.connected = true;
      refresh();
    };
    ws.onmessage = function (event) {
      var msg = core.parseWsMessage(event.data, guildId);
      if (!msg) return;
      if (msg.type === 'error') {
        if (core.isOwnLoadError(Date.now(), lastPlayAt)) notice('error', msg.message || 'The bot reported an error');
        return;
      }
      if (msg.type === 'player') { setNowPlaying(msg.data); reportState(); }
      else if (msg.type === 'queue') { snap.queue = msg.data; reportState(); }
    };
    ws.onclose = function (event) {
      snap.connected = false;
      if (event.code === 4401) snap.signedIn = false;
      reportState();
      scheduleReconnect();
    };
  }

  /** Executes an app command against the panel API and reports whether the bot accepted it. */
  function run(id, cmd) {
    function result(ok, message) { report({ type: 'result', id: id, ok: ok, message: message || null }); }
    if (!guildId) { result(false, 'Pick a server in the panel first'); return; }
    var req = core.commandToRequest(guildId, cmd, core.toBotState(snap));
    if (!req) { result(true); return; }
    if (cmd.type === 'PlayQuery' || cmd.type === 'PlaySpotifyLink') lastPlayAt = Date.now();
    fetch(req.path, {
      method: req.method,
      credentials: 'same-origin',
      headers: req.body ? { 'Content-Type': 'application/json' } : {},
      body: req.body ? JSON.stringify(req.body) : undefined
    }).then(function (resp) {
      return resp.text().then(function (text) {
        var body = null;
        try { body = JSON.parse(text); } catch (e) { /* not JSON */ }
        var message = body && (body.error || body.message);
        // /play answers 200 with this message when it can't join voice.
        var noVoice = typeof message === 'string' && /no voice channel/i.test(message);
        if (resp.ok && !(body && body.error) && !noVoice) result(true);
        else result(false, message || text || ('HTTP ' + resp.status));
      });
    }).catch(function () { result(false, 'Could not reach the bot'); });
  }

  window.__cksBridge = { run: run };

  function start() {
    connect();
    var sel = document.getElementById('guildSelect');
    if (sel) sel.addEventListener('change', function () { if (selectedGuild() !== guildId) connect(); });
    // Same-document localStorage writes fire no 'storage' event, so watch the selection directly.
    setInterval(function () { if (selectedGuild() !== guildId) connect(); }, GUILD_POLL_MS);
    setInterval(reportState, HEARTBEAT_MS);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
