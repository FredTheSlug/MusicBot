/* Mini-player UI: renders the app's state-changed events and sends commands back. */
(function () {
  'use strict';

  var invoke = window.__TAURI__.core.invoke;
  var listen = window.__TAURI__.event.listen;
  var STATUS_CLEAR_MS = 8000;
  var PAUSE_ICON = '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>';
  var PLAY_ICON = '<path d="M8 5v14l11-7z"/>';

  function el(id) { return document.getElementById(id); }

  var ui = null;
  var statusTimer = null;
  var volumeDragging = false;

  function view(u) {
    var s = u && u.state;
    var live = !!(s && s.connected && !u.stale);
    var signedIn = !!(s && s.signedIn);
    var track = live && signedIn ? s.track : null;
    if (!live) return { title: 'Reconnecting…', meta: "Can't reach the bot right now", track: null, live: false, signedIn: signedIn };
    if (!signedIn) return { title: 'Not signed in', meta: 'Sign in from the panel', track: null, live: true, signedIn: false };
    if (!track) return { title: 'Nothing playing', meta: s.guild && s.guild.name ? s.guild.name : '', track: null, live: true, signedIn: true };
    var meta = track.author + (track.requestedBy ? ' · requested by ' + track.requestedBy : '');
    return { title: track.title || 'Unknown', meta: meta, track: track, live: true, signedIn: true };
  }

  function render(u) {
    ui = u;
    var v = view(u);
    var s = u && u.state;
    el('title').textContent = v.title;
    el('meta').textContent = v.meta;
    el('openPanel').hidden = !(v.live && !v.signedIn);

    var art = el('art');
    if (v.track && v.track.artworkUrl) {
      if (art.getAttribute('src') !== v.track.artworkUrl) art.src = v.track.artworkUrl;
      art.hidden = false;
    } else {
      art.hidden = true;
      art.removeAttribute('src');
    }
    art.classList.toggle('spinning', !!(v.track && s && !s.paused));

    ['restart', 'toggle', 'skip'].forEach(function (id) { el(id).disabled = !v.track; });
    var paused = !!(s && s.paused);
    el('toggleIcon').innerHTML = paused ? PLAY_ICON : PAUSE_ICON;
    el('toggle').title = paused ? 'Play' : 'Pause';
    el('toggle').setAttribute('aria-label', el('toggle').title);

    el('volume').disabled = !(v.live && v.signedIn);
    if (!volumeDragging && s) el('volume').value = s.volume;
    tick();
  }

  /** Advances the progress bar between state reports. */
  function tick() {
    var s = ui && ui.state;
    var t = s && s.track;
    var pct = 0;
    if (t && t.durationMs > 0 && ui.positionMs != null) {
      var pos = ui.positionMs;
      if (!s.paused && !t.isStream && s.connected && !ui.stale) pos += Date.now() - ui.positionAtMs;
      pct = Math.max(0, Math.min(100, (pos / t.durationMs) * 100));
    }
    el('progressFill').style.width = pct + '%';
  }

  function showStatus(message) {
    el('status').textContent = message;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(function () { el('status').textContent = ''; }, STATUS_CLEAR_MS);
  }

  function send(cmd) {
    invoke('mini_command', { cmd: cmd }).catch(function (e) { showStatus(String(e)); });
  }

  el('restart').addEventListener('click', function () { send({ type: 'RestartTrack' }); });
  el('toggle').addEventListener('click', function () { send({ type: 'TogglePause' }); });
  el('skip').addEventListener('click', function () { send({ type: 'Skip' }); });
  el('volume').addEventListener('input', function () { volumeDragging = true; });
  el('volume').addEventListener('change', function () {
    volumeDragging = false;
    send({ type: 'SetVolume', value: Number(el('volume').value) });
  });
  el('close').addEventListener('click', function () { invoke('mini_hide'); });
  el('openPanel').addEventListener('click', function () { invoke('mini_open_panel'); });

  listen('state-changed', function (e) { render(e.payload); });
  listen('notice', function (e) { showStatus(e.payload.message); });
  invoke('mini_get_state').then(render);
  setInterval(tick, 500);
})();
