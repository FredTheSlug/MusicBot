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

  // ---- Custom themes: the mini-player versions people made in the panel ----

  /** The 14 colors of a mini-player version (a theme's 15 without glass). */
  var CUSTOM_SLOTS = ['bgPrimary', 'bgSecondary', 'bgCard', 'bgElevated', 'bgHover', 'accent', 'accentLight', 'accent2',
    'textPrimary', 'textSecondary', 'textTertiary', 'border', 'borderLight', 'success'];

  /** The fonts a theme may use, with the fallbacks the panel gives each (ThemeApply.FONT_STACKS; a test keeps them equal). */
  var FONT_STACKS = Object.freeze({
    'Inter': "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    'DM Sans': "'DM Sans', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    'Outfit': "'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    'Syne': "'Syne', 'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    'Fraunces': "'Fraunces', Georgia, 'Times New Roman', serif",
    'IBM Plex Mono': "'IBM Plex Mono', ui-monospace, 'Cascadia Code', 'Consolas', monospace",
    'Noto Sans JP': "'Noto Sans JP', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    'Shippori Mincho': "'Shippori Mincho', 'Noto Serif JP', 'Yu Mincho', Georgia, serif"
  });

  var COLOR = /^#(?:[0-9a-f]{6}|[0-9a-f]{8})$/i;
  var THEME_ID = /^[a-z0-9]{12}$/;

  /** The glow behind text when the background is see-through, for dark text: the one Dawn's skin sets in themes.css. */
  var LIGHT_LEGIBILITY_SHADOW = '0 1px 3px rgba(255, 255, 255, 0.95), 0 0 8px rgba(255, 255, 255, 0.7)';

  /** The font-family value for a theme's font; anything unknown is Inter. */
  function fontStack(font) {
    return typeof font === 'string' && Object.prototype.hasOwnProperty.call(FONT_STACKS, font) ? FONT_STACKS[font] : FONT_STACKS.Inter;
  }

  function linear(channel) {
    var c = channel / 255;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  /** Luminance of #rrggbb or #rrggbbaa laid over black (how the panel's contrast() counts a color with alpha). */
  function luminanceOverBlack(hex) {
    var m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})?$/i.exec(hex);
    var alpha = m[4] === undefined ? 1 : parseInt(m[4], 16) / 255;
    var rgb = [m[1], m[2], m[3]].map(function (part) { return linear(Math.max(0, Math.min(255, Math.round(parseInt(part, 16) * alpha)))); });
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
  }

  /**
   * The color for what sits on the accent (the play icon): '#000000' or '#ffffff', whichever has the higher WCAG
   * contrast with it, white when they tie. The same rule as the panel's ThemePalette.onAccent (a test keeps them equal).
   * Anything that is not a color gets white, the mini-player's own default.
   */
  function onAccent(accentHex) {
    if (typeof accentHex !== 'string' || !COLOR.test(accentHex)) return '#ffffff';
    var l = luminanceOverBlack(accentHex);
    var againstBlack = (l + 0.05) / 0.05;
    var againstWhite = 1.05 / (l + 0.05);
    return againstBlack > againstWhite ? '#000000' : '#ffffff';
  }

  /**
   * The inline variables for a custom mini-player version: the 16 the panel's themes set (glow and gradient derived from
   * the accent as the panel derives them, the font from the theme's font), --on-accent, and for dark text the light
   * --legibility-shadow. null when any of the 14
   * colors is not a plain #rrggbb / #rrggbbaa, so nothing from the page's data ever becomes more than a color.
   * custom = {colors, font, ...} as the app sends it.
   */
  function customVars(custom) {
    var colors = custom && typeof custom === 'object' ? custom.colors : null;
    if (!colors || typeof colors !== 'object') return null;
    for (var i = 0; i < CUSTOM_SLOTS.length; i++) {
      var color = colors[CUSTOM_SLOTS[i]];
      if (typeof color !== 'string' || !COLOR.test(color)) return null;
    }
    var vars = {
      '--bg-primary': colors.bgPrimary,
      '--bg-secondary': colors.bgSecondary,
      '--bg-card': colors.bgCard,
      '--bg-elevated': colors.bgElevated,
      '--bg-hover': colors.bgHover,
      '--accent': colors.accent,
      '--accent-light': colors.accentLight,
      // The accent at 32% (0x52 of 255), whatever alpha the accent has itself.
      '--accent-glow': colors.accent.slice(0, 7).toLowerCase() + '52',
      '--accent-gradient': 'linear-gradient(135deg, ' + colors.accent + ', ' + colors.accent2 + ')',
      '--text-primary': colors.textPrimary,
      '--text-secondary': colors.textSecondary,
      '--text-tertiary': colors.textTertiary,
      '--border': colors.border,
      '--border-light': colors.borderLight,
      '--success': colors.success,
      '--font-family': fontStack(custom.font),
      '--on-accent': onAccent(colors.accent)
    };
    // Dark text (a light version) must not get the default black halo below 60% Background: nearly unreadable over a
    // dark wallpaper. It gets Dawn's light glow instead; light text keeps mini.css's dark default.
    if (onAccent(colors.textPrimary) === '#ffffff') vars['--legibility-shadow'] = LIGHT_LEGIBILITY_SHADOW;
    return vars;
  }

  /**
   * Shows theme {id, vars, custom?}: data-theme picks the built-in skin, inline vars (the panel's live colors)
   * override it. A custom mini-player version (custom) wears the neutral skin ("custom": the base look of mini.css, no
   * built-in decorations) with its own variables; when its palette is unusable the base skin (id) shows instead.
   * Vars from the previous theme that this one lacks are removed. Returns the names set.
   */
  function applyTheme(root, theme, previous) {
    var vars = (theme && theme.vars) || {};
    var skin = (theme && theme.id) || 'nebula';
    var custom = theme && theme.custom ? customVars(theme.custom) : null;
    if (custom) {
      vars = custom;
      skin = 'custom';
    }
    var names = Object.keys(vars);
    (previous || []).forEach(function (name) { if (names.indexOf(name) < 0) root.style.removeProperty(name); });
    names.forEach(function (name) { root.style.setProperty(name, vars[name]); });
    root.setAttribute('data-theme', skin);
    return names;
  }

  /* Direction controls could reorder the text around a name; what leaves nothing to see is no name at all. */
  var BIDI_CONTROLS = /[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g;
  var INVISIBLE = /[\u0000-\u0020\u007F-\u00A0\u00AD\u034F\u115F\u1160\u17B4\u17B5\u180B-\u180F\u2000-\u200F\u2028-\u202F\u205F-\u206F\u2800\u3000\u3164\uFE00-\uFE0F\uFEFF\uFFA0\uFFF9-\uFFFB\u{1D173}-\u{1D17A}\u{E0000}-\u{E0FFF}]/gu;
  var FSI = '\u2068';
  var PDI = '\u2069';

  function visibleName(name) {
    if (typeof name !== 'string') return '';
    var text = name.replace(BIDI_CONTROLS, '');
    return text.replace(INVISIBLE, '') === '' ? '' : text.trim();
  }

  /**
   * How a custom theme reads in the theme menu, as the panel's picker words it: a community theme names its creator
   * ("Name — by creator"); one of yours is marked when private. Each name is isolated so it can't reorder the text around it.
   */
  function themeLabel(theme) {
    var t = theme || {};
    var name = FSI + (visibleName(t.name) || 'Untitled theme') + PDI;
    if (t.mine === true) return t.private === true ? name + ' (private)' : name;
    return name + ' \u2014 by ' + FSI + (visibleName(t.owner) || 'someone') + PDI;
  }

  /**
   * The theme menu's groups, each {label, options: [{value, label}]}: the built-in themes, then Community and My themes
   * (each only when it has something). A custom option's value is custom:<id>. customs come from the app in the order to show.
   */
  function themeMenu(customs) {
    var groups = [{ label: 'Built-in', options: THEMES.map(function (t) { return { value: t.id, label: t.label }; }) }];
    var community = [];
    var mine = [];
    (Array.isArray(customs) ? customs : []).forEach(function (theme) {
      if (!theme || typeof theme !== 'object' || typeof theme.id !== 'string' || !THEME_ID.test(theme.id)) return;
      (theme.mine === true ? mine : community).push({ value: 'custom:' + theme.id, label: themeLabel(theme) });
    });
    if (community.length) groups.push({ label: 'Community', options: community });
    if (mine.length) groups.push({ label: 'My themes', options: mine });
    return groups;
  }

  /* The bot's secret picture link, exactly as the app builds it from the bot's address; anything else is not shown. */
  var PICTURE_URL = /^https?:\/\/(?:[A-Za-z0-9.\-]+|\[[0-9A-Fa-f:]+\])(?::[0-9]{1,5})?\/theme-images\/[0-9a-f]{32}\.(?:jpg|png|webp|gif)$/;
  /* Any type but JPEG may move: GIF, animated WebP and an animated PNG (which the bot stores as .png). */
  var ANIMATED_PICTURE = /\.(?:gif|webp|png)$/;

  /**
   * The picture behind a custom version's card, or null for none: {url, overlay, freeze}. overlay is the dark layer over it
   * (the version's dim, 0-90%); freeze says to show only its first frame (Reduce motion, and a picture that can move).
   * The app sends a picture only when the version uses the theme's picture and the theme has one.
   */
  function pictureLayer(custom, reduceMotion) {
    var url = custom && typeof custom.picture === 'string' ? custom.picture : '';
    if (!PICTURE_URL.test(url)) return null;
    var dim = Number(custom.dim);
    dim = isFinite(dim) ? Math.max(0, Math.min(90, dim)) : 0;
    return { url: url, overlay: 'rgba(0, 0, 0, ' + dim / 100 + ')', freeze: !!reduceMotion && ANIMATED_PICTURE.test(url) };
  }

  /**
   * The arguments for mini_set_theme. The theme is sent only when the person picked one in the theme menu; flipping
   * "Match the panel" sends none (null), so the app keeps the stored pick: the menu may be showing a stand-in for a
   * custom theme that isn't in the list right now, and that must never become the pick.
   */
  function themeSetArgs(matchPanel, menuValue, menuPicked) {
    var picked = menuPicked === true && typeof menuValue === 'string' && menuValue !== '';
    return { matchPanel: !!matchPanel, theme: picked ? menuValue : null };
  }

  /** The picture for theme {id, vars, custom?}: only a custom version whose colors are in use brings one (else its base skin shows). */
  function themePicture(theme, reduceMotion) {
    return theme && theme.custom && customVars(theme.custom) ? pictureLayer(theme.custom, reduceMotion) : null;
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
    customVars: customVars, onAccent: onAccent, fontStack: fontStack, themeMenu: themeMenu, themeLabel: themeLabel,
    pictureLayer: pictureLayer, themePicture: themePicture, CUSTOM_SLOTS: CUSTOM_SLOTS, FONT_STACKS: FONT_STACKS,
    LIGHT_LEGIBILITY_SHADOW: LIGHT_LEGIBILITY_SHADOW, themeSetArgs: themeSetArgs,
    spotifyView: spotifyView, isLink: isLink, formatDuration: formatDuration, latestOnly: latestOnly,
    currentLyricIndex: currentLyricIndex, trackKey: trackKey
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksMiniView = api;
})(typeof window !== 'undefined' ? window : this);
