/*
 * Pure mapping from the app's state-changed payload to what the mini-player shows.
 * Loaded by Node for tests (module.exports) and by index.html (window.__cksMiniView).
 */
(function (root) {
  'use strict';

  /** u = {state, stale, positionMs, positionAtMs} → {title, meta, track, live, signedIn}. */
  function view(u) {
    var s = u && u.state;
    var live = !!(s && s.connected && !u.stale);
    var signedIn = !!(s && s.signedIn);
    var track = live && signedIn ? s.track : null;
    // signedIn is only false after the bot itself answered 401/4401, so a fresh report of it wins
    // over `connected` (after a bot restart the socket is rejected, which also clears connected).
    if (s && !u.stale && !signedIn) return { title: 'Not signed in', meta: 'Sign in from the panel', track: null, live: true, signedIn: false };
    if (!live) return { title: 'Reconnecting…', meta: "Can't reach the bot right now", track: null, live: false, signedIn: signedIn };
    if (!track) return { title: 'Nothing playing', meta: s.guild && s.guild.name ? s.guild.name : '', track: null, live: true, signedIn: true };
    var meta = track.author + (track.requestedBy ? ' · requested by ' + track.requestedBy : '');
    return { title: track.title || 'Unknown', meta: meta, track: track, live: true, signedIn: true };
  }

  /** The panel's themes, in its picker's order. */
  var THEMES = [
    ['nebula', 'Nebula'], ['aurora', 'Aurora'], ['ember', 'Ember'], ['crimson', 'Crimson'], ['dawn', 'Dawn'],
    ['dawn-dark', 'Dawn Dark'], ['steel', 'Steel'], ['verdant', 'Verdant'], ['grove', 'Grove'], ['grapevine', 'Grapevine'],
    ['sumi', 'Sumi'], ['rasta', 'Rasta'], ['cipher', 'Cipher'], ['signal', 'Signal'], ['victory', 'Victory'],
    ['neon', 'Neon'], ['revival', 'Revival']
  ].map(function (t) { return { id: t[0], label: t[1] }; });

  /**
   * Shows theme {id, vars}: data-theme picks the built-in skin, inline vars (the panel's live colors)
   * override it. Vars from the previous theme that this one lacks are removed. Returns the names set.
   */
  function applyTheme(root, theme, previous) {
    var vars = (theme && theme.vars) || {};
    var names = Object.keys(vars);
    (previous || []).forEach(function (name) { if (names.indexOf(name) < 0) root.style.removeProperty(name); });
    names.forEach(function (name) { root.style.setProperty(name, vars[name]); });
    root.setAttribute('data-theme', (theme && theme.id) || 'nebula');
    return names;
  }

  var LOW_ALPHA = 0.6;

  /** Background card opacity (0.15–1); below 0.6 the text gets a shadow to stay readable. */
  function applyOpacity(root, value) {
    root.style.setProperty('--card-alpha', String(value));
    root.classList.toggle('low-alpha', value < LOW_ALPHA);
  }

  /** The Spotify logo button: green (playable) only when Spotify is playing something to queue. */
  function spotifyView(status) {
    var now = status && status.available ? status.now : null;
    if (!now) return { playable: false, tooltip: "Spotify isn't open" };
    if (!now.playing) return { playable: false, tooltip: "Spotify isn't playing" };
    var song = now.title + (now.artist ? ' – ' + now.artist : '');
    return { playable: true, tooltip: 'Queue “' + song + '” on the bot' };
  }

  /** True when the search box holds a link to play rather than words to search for. */
  function isLink(text) {
    var t = String(text || '').trim();
    return /^spotify:/i.test(t) || /(^|\s)https?:\/\/\S/i.test(t);
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /** m:ss (h:mm:ss from an hour), or LIVE for streams. */
  function formatDuration(ms, isStream) {
    if (isStream) return 'LIVE';
    var total = Math.max(0, Math.floor((ms || 0) / 1000));
    var h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), sec = total % 60;
    return h > 0 ? h + ':' + pad2(m) + ':' + pad2(sec) : m + ':' + pad2(sec);
  }

  /** Numbers requests so a slow, older answer can be ignored once a newer request exists. */
  function latestOnly() {
    var last = 0;
    return {
      next: function () { return ++last; },
      isLatest: function (n) { return n === last; }
    };
  }

  /** Index of the lyric line being sung at positionMs (the last line that has started), or -1. */
  function currentLyricIndex(lines, positionMs) {
    var lo = 0, hi = (lines ? lines.length : 0) - 1, found = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (lines[mid].ms <= positionMs) { found = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    return found;
  }

  /** Identifies the song in a state report, so lyrics reload only when the song changes. */
  function trackKey(state) {
    var t = state && state.track;
    return t ? t.title + '\n' + t.uri : null;
  }

  /** Needs a song to open, but must stay usable to close the lyrics once the song has ended. */
  function lyricsButtonEnabled(hasTrack, panel) {
    return hasTrack || panel === 'lyrics';
  }

  var REPEAT_VIEWS = {
    OFF: { label: 'Repeat: off', badge: '', active: false },
    ALL: { label: 'Repeat: whole queue', badge: '', active: true },
    SINGLE: { label: 'Repeat: this song', badge: '1', active: true }
  };

  /** The repeat button for the bot's mode (Off → All → One, the bot's own order). */
  function repeatView(mode) {
    return REPEAT_VIEWS[mode] || REPEAT_VIEWS.OFF;
  }

  /** Milliseconds for a click at `fraction` (0–1) of the progress bar; 0 when the length is unknown. */
  function seekPosition(fraction, durationMs) {
    if (!(durationMs > 0)) return 0;
    return Math.round(Math.max(0, Math.min(1, fraction)) * durationMs);
  }

  /** Up Next rows; `index` and `uri` identify the song for remove / move to top. */
  function queueRows(state) {
    var items = (state && state.upNext) || [];
    return items.map(function (item, index) {
      var sub = [item.author, item.requestedBy].filter(Boolean).join(' · ');
      return { index: index, uri: item.uri, title: item.title || 'Unknown', sub: sub, time: formatDuration(item.durationMs, false) };
    });
  }

  /** "Stops in 14 min" / "Stops after this song" / "" for the sleep timer's state. */
  function sleepLabel(info, nowMs) {
    if (!info) return '';
    if (info.mode === 'end') return 'Stops after this song';
    if (info.mode === 'timer' && info.endsAtMs) return 'Stops in ' + Math.max(1, Math.ceil((info.endsAtMs - nowMs) / 60000)) + ' min';
    return '';
  }

  var KEY_NAMES = {
    Space: 'Space', Enter: 'Enter', Backspace: 'Backspace', Delete: 'Delete', Insert: 'Insert', Home: 'Home',
    End: 'End', PageUp: 'PageUp', PageDown: 'PageDown', ArrowLeft: 'Left', ArrowRight: 'Right', ArrowUp: 'Up',
    ArrowDown: 'Down', Comma: 'Comma', Period: 'Period', Slash: 'Slash', Semicolon: 'Semicolon', Quote: 'Quote',
    BracketLeft: 'BracketLeft', BracketRight: 'BracketRight', Backslash: 'Backslash', Minus: 'Minus',
    Equal: 'Equal', Backquote: 'Backquote', Pause: 'Pause', ScrollLock: 'ScrollLock'
  };

  /**
   * The combination a keydown records, as the app's hotkey parser reads it ("Ctrl+Alt+P"), or null
   * for a lone modifier, Escape (cancels) or a plain key without Ctrl/Alt/Shift (except F-keys).
   */
  function accelFromEvent(e) {
    var code = (e && e.code) || '';
    var key = /^Key[A-Z]$/.test(code) ? code.slice(3)
      : /^Digit[0-9]$/.test(code) ? code.slice(5)
      : /^(F([1-9]|1[0-9]|2[0-4])|Numpad[0-9])$/.test(code) ? code
      : KEY_NAMES[code];
    if (!key) return null;
    var mods = [];
    if (e.ctrlKey) mods.push('Ctrl');
    if (e.altKey) mods.push('Alt');
    if (e.shiftKey) mods.push('Shift');
    if (e.metaKey) mods.push('Super');
    if (!mods.length && !/^F\d+$/.test(key)) return null;
    return mods.concat([key]).join('+');
  }

  // ---- Adding a song to a playlist: the picker in the drawer ----

  var PICKER_RECENT_SHOWN = 5;
  var PICKER_RECENT_STORED = 8;
  var PICKER_ERROR_MAX = 160;

  /** Names a playlist in the recently-used list (a top-level playlist has no folder). */
  function playlistKey(playlist) {
    return ((playlist && playlist.folder) || '') + '\n' + ((playlist && playlist.name) || '');
  }

  /** "12 songs" / "1 song"; "upgrading…" while the bot is still converting an old playlist. */
  function playlistCount(playlist) {
    if (playlist.legacy) return 'upgrading…';
    var n = playlist.songCount || 0;
    return n + (n === 1 ? ' song' : ' songs');
  }

  function pickerRow(playlist) {
    return {
      key: playlistKey(playlist), name: playlist.name, folder: playlist.folder || null,
      path: playlist.folder || '', count: playlistCount(playlist), legacy: !!playlist.legacy
    };
  }

  /**
   * The picker's list: {recent, rest}. `recent` = up to 5 of the recently used playlists that still
   * exist, newest first (recentKeys is newest first); `rest` = every other playlist in the order given.
   * The filter keeps playlists whose folder path and name together hold every word typed, in any case.
   */
  function pickerRows(playlists, recentKeys, filter) {
    var all = Array.isArray(playlists) ? playlists.filter(function (p) { return p && typeof p.name === 'string' && p.name; }) : [];
    var byKey = {};
    all.forEach(function (p) { byKey[playlistKey(p)] = p; });
    var used = {};
    var recent = [];
    (Array.isArray(recentKeys) ? recentKeys : []).forEach(function (k) {
      if (typeof k !== 'string' || !Object.prototype.hasOwnProperty.call(byKey, k) || used[k] || recent.length >= PICKER_RECENT_SHOWN) return;
      used[k] = true;
      recent.push(byKey[k]);
    });
    var rest = all.filter(function (p) { return !used[playlistKey(p)]; });
    var words = String(filter || '').toLowerCase().split(/\s+/).filter(Boolean);
    if (words.length) {
      var matches = function (p) {
        var hay = ((p.folder ? p.folder + '/' : '') + p.name).toLowerCase();
        return words.every(function (w) { return hay.indexOf(w) >= 0; });
      };
      recent = recent.filter(matches);
      rest = rest.filter(matches);
    }
    return { recent: recent.map(pickerRow), rest: rest.map(pickerRow) };
  }

  /** The recently-used list after using `key`: newest first, no repeats, the last 8. */
  function rememberRecent(keys, key) {
    var older = (Array.isArray(keys) ? keys : []).filter(function (k) { return typeof k === 'string' && k !== key; });
    return [key].concat(older).slice(0, PICKER_RECENT_STORED);
  }

  /**
   * The five fields a playlist keeps of a song, from the song playing or a search/history result;
   * null when there is no uri. A live stream has no length; artwork is kept only if it is a web link.
   */
  function playlistSong(item) {
    if (!item || typeof item.uri !== 'string' || !item.uri.trim()) return null;
    var length = item.isStream ? 0 : Number(item.durationMs);
    return {
      uri: item.uri,
      title: typeof item.title === 'string' ? item.title : '',
      author: typeof item.author === 'string' ? item.author : '',
      durationMs: isFinite(length) && length > 0 ? Math.round(length) : 0,
      artworkUrl: typeof item.artworkUrl === 'string' && /^https?:\/\//i.test(item.artworkUrl) ? item.artworkUrl : null
    };
  }

  /** An error for the picker's one-line slot: the bot's own words, whitespace squeezed and cut at 160. */
  function pickerError(error, fallback) {
    var text = String(error && error.message !== undefined ? error.message : error || '').replace(/\s+/g, ' ').trim();
    if (!text) return fallback || "Couldn't add the song";
    return text.length > PICKER_ERROR_MAX ? text.slice(0, PICKER_ERROR_MAX - 1) + '…' : text;
  }

  var api = {
    pickerRows: pickerRows, playlistKey: playlistKey, rememberRecent: rememberRecent, playlistSong: playlistSong,
    pickerError: pickerError, playlistCount: playlistCount,
    accelFromEvent: accelFromEvent,
    sleepLabel: sleepLabel,
    repeatView: repeatView, seekPosition: seekPosition, queueRows: queueRows,
    lyricsButtonEnabled: lyricsButtonEnabled,
    view: view, THEMES: THEMES, applyTheme: applyTheme, applyOpacity: applyOpacity,
    spotifyView: spotifyView, isLink: isLink, formatDuration: formatDuration, latestOnly: latestOnly,
    currentLyricIndex: currentLyricIndex, trackKey: trackKey
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksMiniView = api;
})(typeof window !== 'undefined' ? window : this);
