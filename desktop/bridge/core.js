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

  var UP_NEXT_MAX = 20;
  var REPEAT_MODES = ['OFF', 'ALL', 'SINGLE'];

  /** The next songs, as the mini-player's Up Next lists them. */
  function toUpNext(queue) {
    var tracks = queue && Array.isArray(queue.tracks) ? queue.tracks : [];
    return tracks.slice(0, UP_NEXT_MAX).map(function (t) {
      t = t || {};
      return {
        title: str(t.title),
        author: str(t.author),
        uri: str(t.uri),
        durationMs: Math.max(0, num(t.duration, 0)),
        requestedBy: strOrNull(t.requestedBy)
      };
    });
  }

  function queueLength(queue) {
    if (!queue) return 0;
    var size = num(queue.size, NaN);
    if (isFinite(size)) return Math.max(0, size);
    return Array.isArray(queue.tracks) ? queue.tracks.length : 0;
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
      queueHead: signedIn ? toQueueHead(snap.queue) : null,
      shuffle: signedIn && !!np.shuffleMode,
      repeat: signedIn && REPEAT_MODES.indexOf(np.repeatMode) >= 0 ? np.repeatMode : 'OFF',
      upNext: signedIn ? toUpNext(snap.queue) : [],
      queueLength: signedIn ? queueLength(snap.queue) : 0,
      // null when the bot doesn't have the setting (older bot): the app hides the switch.
      autoplay: signedIn && typeof np.autoplay === 'boolean' ? np.autoplay : null
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
  function fetchRequest(path) { return { method: 'GET', path: path, body: null }; }

  /** {index, uri} of a queued song, or null when the index isn't a usable position. */
  function queueIndex(value) {
    var index = value && value.index;
    return typeof index === 'number' && index >= 0 && Math.floor(index) === index ? index : null;
  }

  var QUEUE_CHANGED = 'The queue changed — try again';

  /**
   * Queue edits name the song as well as its position; refuse when the song at that position is no
   * longer the one the user clicked (a friend edited the queue in between).
   */
  function queueEditError(queue, index, uri) {
    var tracks = queue && Array.isArray(queue.tracks) ? queue.tracks : [];
    var track = tracks[index];
    return track && str(track.uri) === uri ? null : QUEUE_CHANGED;
  }

  function lyricsPath(base, song) {
    if (!song || typeof song.title !== 'string' || !song.title) return base + 'lyrics';
    return base + 'lyrics?title=' + encodeURIComponent(song.title) +
      '&author=' + encodeURIComponent(str(song.author)) +
      '&duration=' + Math.max(0, Math.floor(num(song.durationMs, 0)));
  }

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
      case 'Search':
        return typeof command.value === 'string' && command.value.trim()
          ? fetchRequest(base + 'search?q=' + encodeURIComponent(command.value.trim()))
          : null;
      case 'Lyrics': return fetchRequest(lyricsPath(base, command.value));
      case 'ToggleShuffle': return request(base + 'shuffle', null);
      case 'CycleRepeat': return request(base + 'repeat', null);
      case 'ToggleAutoplay': return request(base + 'autoplay', null);
      case 'Seek':
        return typeof command.value === 'number' && command.value >= 0
          ? request(base + 'seek', { position: Math.floor(command.value) })
          : null;
      case 'RemoveFromQueue': {
        var removeAt = queueIndex(command.value);
        return removeAt === null ? null : request(base + 'remove/' + removeAt, null);
      }
      case 'MoveToTop': {
        var moveFrom = queueIndex(command.value);
        return moveFrom === null ? null : request(base + 'reorder', { from: moveFrom, to: 0 });
      }
      case 'PlayPlaylist': {
        var pl = command.value || {};
        if (typeof pl.name !== 'string' || !pl.name) return null;
        var folder = typeof pl.folder === 'string' && pl.folder ? '?folder=' + encodeURIComponent(pl.folder) : '';
        return request(base + 'playplaylist/' + encodeURIComponent(pl.name) + folder, null);
      }
      case 'History': return fetchRequest(base + 'history');
      case 'Frequent': return fetchRequest(base + 'frequent');
      // Playlists are shared by every server, so this one isn't under /guild/.
      case 'Playlists': return fetchRequest('/api/playlists-tree');
      default: return null;
    }
  }

  /** The panel theme's variables the mini-player borrows (the app drops any other name). */
  var THEME_VARS = ['--bg-primary', '--bg-secondary', '--bg-card', '--bg-elevated', '--bg-hover', '--accent',
    '--accent-light', '--accent-glow', '--accent-gradient', '--text-primary', '--text-secondary', '--text-tertiary',
    '--border', '--border-light', '--success', '--font-family'];

  /** themeAttr = <html data-theme> (absent for Nebula); getVar(name) = the computed value. */
  function readTheme(themeAttr, getVar) {
    var vars = {};
    THEME_VARS.forEach(function (name) {
      var value = String(getVar(name) || '').trim();
      if (value) vars[name] = value;
    });
    return { id: themeAttr || 'nebula', vars: vars };
  }

  var api = {
    toBotState: toBotState, parseWsMessage: parseWsMessage, commandToRequest: commandToRequest,
    isOwnLoadError: isOwnLoadError, readTheme: readTheme, THEME_VARS: THEME_VARS, queueEditError: queueEditError
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksCore = api;
})(typeof window !== 'undefined' ? window : this);
