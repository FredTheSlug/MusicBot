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

  var api = { view: view };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.__cksMiniView = api;
})(typeof window !== 'undefined' ? window : this);
