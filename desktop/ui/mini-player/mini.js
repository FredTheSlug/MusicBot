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

  var view = window.__cksMiniView.view;

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

  // ---- Spotify: send the current song, paste a link, or drop one onto the window ----
  function renderSpotify(status) {
    var send = el('spotifySend');
    send.hidden = !(status && status.available);
    var now = status && status.now;
    var playing = !!(now && now.playing);
    send.disabled = !playing;
    send.textContent = playing ? '▶ Send ' + now.title + (now.artist ? ' – ' + now.artist : '') : "Spotify isn't playing";
  }

  function spotifyAction(cmd, args) {
    invoke(cmd, args).then(function (msg) { if (msg) showStatus(msg); }).catch(function (e) { showStatus(String(e)); });
  }

  el('spotifySend').addEventListener('click', function () { spotifyAction('mini_send_spotify'); });
  el('spotifyPaste').addEventListener('click', function () { spotifyAction('mini_play_spotify_clipboard'); });

  var dragDepth = 0;
  window.addEventListener('dragenter', function (e) { e.preventDefault(); dragDepth++; el('mini').classList.add('dropping'); });
  window.addEventListener('dragleave', function () { if (--dragDepth <= 0) { dragDepth = 0; el('mini').classList.remove('dropping'); } });
  window.addEventListener('dragover', function (e) { e.preventDefault(); });
  window.addEventListener('drop', function (e) {
    e.preventDefault();
    dragDepth = 0;
    el('mini').classList.remove('dropping');
    var dt = e.dataTransfer;
    // Whatever arrives is logged by the app, so we can see what Spotify's drag data looks like.
    var text = dt.getData('text/uri-list') || dt.getData('text/plain') || ('[no text; types: ' + Array.prototype.join.call(dt.types, ', ') + ']');
    spotifyAction('mini_play_spotify_text', { text: text });
  });

  listen('spotify-changed', function (e) { renderSpotify(e.payload); });
  invoke('mini_spotify_now').then(renderSpotify);

  listen('state-changed', function (e) { render(e.payload); });
  listen('notice', function (e) { showStatus(e.payload.message); });
  invoke('mini_get_state').then(render);
  setInterval(tick, 500);
})();
