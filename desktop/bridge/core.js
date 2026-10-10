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

  /** elapsedMs = time since the page received np; a playing track has moved on by that much. */
  function toTrack(np, elapsedMs) {
    if (!np || !np.playing) return null;
    var isStream = !!np.isStream;
    var durationMs = isStream ? 0 : Math.max(0, num(np.duration, 0));
    var positionMs = Math.max(0, num(np.position, 0));
    if (!np.paused && !isStream && elapsedMs > 0) {
      positionMs += elapsedMs;
      if (durationMs > 0) positionMs = Math.min(positionMs, durationMs);
    }
    return {
      title: str(np.title),
      author: str(np.author),
      artworkUrl: strOrNull(np.artworkUrl),
      uri: str(np.uri),
      // Lavaplayer reports streams with a huge duration; the app treats 0 as "no known length".
      durationMs: durationMs,
      positionMs: positionMs,
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

  /**
   * snapshot = {connected, signedIn, me, guild, nowPlaying, nowPlayingAt, queue} → BotState (spec §2).
   * nowPlayingAt and now (ms clock) let the position advance past the last `player` message.
   */
  function toBotState(snapshot, now) {
    var snap = snapshot || {};
    var signedIn = !!snap.signedIn;
    var np = signedIn ? (snap.nowPlaying || {}) : {};
    var elapsedMs = num(now, NaN) - num(snap.nowPlayingAt, NaN);
    return {
      connected: !!snap.connected,
      signedIn: signedIn,
      me: signedIn && snap.me ? { id: str(snap.me.id), name: str(snap.me.name), avatarUrl: typeof snap.me.avatarUrl === 'string' ? snap.me.avatarUrl : null } : null,
      guild: snap.guild ? { id: str(snap.guild.id), name: str(snap.guild.name) } : null,
      track: signedIn ? toTrack(np, isFinite(elapsedMs) ? elapsedMs : 0) : null,
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

  var OWN_LOAD_ERROR_MS = 30000;

  /**
   * The bot broadcasts load errors ("No results found…") to everyone watching the server, so treat
   * one as ours only if this app asked to play something in the last 30 s (lastPlayAt, 0 = never).
   */
  function isOwnLoadError(now, lastPlayAt) {
    return lastPlayAt > 0 && now - lastPlayAt <= OWN_LOAD_ERROR_MS;
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

  var api = { toBotState: toBotState, parseWsMessage: parseWsMessage, commandToRequest: commandToRequest, isOwnLoadError: isOwnLoadError };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksCore = api;
})(typeof window !== 'undefined' ? window : this);
