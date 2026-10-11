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

  var api = { view: view, THEMES: THEMES, applyTheme: applyTheme, applyOpacity: applyOpacity };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksMiniView = api;
})(typeof window !== 'undefined' ? window : this);
