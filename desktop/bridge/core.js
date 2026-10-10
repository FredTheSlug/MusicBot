/*
 * Pure mapping between the web panel's API/WebSocket payloads and the desktop app.
 * No DOM or network access: bridge.js does the I/O, this file decides what it means.
 * Loaded by Node for tests (module.exports) and injected into the panel page (window.__cksCore).
 */
(function (root) {
  'use strict';

  function str(v) { return typeof v === 'string' ? v : ''; }
  function strOrNull(v) { return typeof v === 'string' && v !== '' ? v : null; }
  function num(v, fallback) { return typeof v === 'number' && isFinite(v) ? v : fallback; }
  function clampVolume(v) { return Math.max(0, Math.min(150, Math.round(num(Number(v), 0)))); }

  function toTrack(np) {
    if (!np || !np.playing) return null;
    var isStream = !!np.isStream;
    return {
      title: str(np.title),
      author: str(np.author),
      artworkUrl: strOrNull(np.artworkUrl),
      uri: str(np.uri),
      // Lavaplayer reports streams with a huge duration; the app treats 0 as "no known length".
      durationMs: isStream ? 0 : Math.max(0, num(np.duration, 0)),
      positionMs: Math.max(0, num(np.position, 0)),
      isStream: isStream,
      requestedBy: strOrNull(np.requestedBy),
      requestedById: strOrNull(np.requestedById)
    };
  }

  function toQueueHead(queue) {
    var first = queue && Array.isArray(queue.tracks) ? queue.tracks[0] : null;
    if (!first) return null;
    return { title: str(first.title), requestedById: strOrNull(first.requestedById) };
  }

  /** snapshot = {connected, signedIn, me, guild, nowPlaying, queue} → BotState (spec §2). */
  function toBotState(snapshot) {
    var snap = snapshot || {};
    var signedIn = !!snap.signedIn;
    var np = signedIn ? (snap.nowPlaying || {}) : {};
    return {
      connected: !!snap.connected,
      signedIn: signedIn,
      me: signedIn && snap.me ? { id: str(snap.me.id), name: str(snap.me.name), avatarUrl: typeof snap.me.avatarUrl === 'string' ? snap.me.avatarUrl : null } : null,
      guild: snap.guild ? { id: str(snap.guild.id), name: str(snap.guild.name) } : null,
      track: signedIn ? toTrack(np) : null,
      paused: !!np.paused,
      volume: np.volume === undefined ? 100 : clampVolume(np.volume),
      queueHead: signedIn ? toQueueHead(snap.queue) : null
    };
  }

  /** Returns the message if it's for the watched guild, else null (other guilds, pongs, junk). */
  function parseWsMessage(text, guildId) {
    var msg;
    try { msg = JSON.parse(text); } catch (e) { return null; }
    if (!msg || typeof msg !== 'object' || typeof msg.type !== 'string') return null;
    if (String(msg.guildId) !== String(guildId)) return null;
    if (msg.type === 'error') return { type: 'error', code: str(msg.code), message: str(msg.message) };
    return { type: msg.type, data: msg.data === undefined ? null : msg.data };
  }

  function request(path, body) { return { method: 'POST', path: path, body: body }; }

  /** command = {type, value?} (Rust BotCommand) → panel API request, or null when there's nothing to do. */
  function commandToRequest(guildId, command, state) {
    if (!guildId || !command) return null;
    var base = '/api/guild/' + encodeURIComponent(guildId) + '/';
    switch (command.type) {
      case 'TogglePause': return request(base + 'pause', null);
      case 'Pause': return state && state.paused ? null : request(base + 'pause', null);
      case 'Skip': return request(base + 'skip', null);
      case 'RestartTrack': return request(base + 'seek', { position: 0 });
      case 'SetVolume': return request(base + 'volume', { volume: clampVolume(command.value) });
      case 'PlayQuery':
      case 'PlaySpotifyLink':
        return typeof command.value === 'string' && command.value.trim()
          ? request(base + 'play', { query: command.value.trim() })
          : null;
      default: return null;
    }
  }

  var api = { toBotState: toBotState, parseWsMessage: parseWsMessage, commandToRequest: commandToRequest };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksCore = api;
})(typeof window !== 'undefined' ? window : this);
