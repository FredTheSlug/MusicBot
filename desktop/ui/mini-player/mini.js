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

  var MV = window.__cksMiniView;
  var view = MV.view;
  var appliedThemeVars = [];

  function showTheme(theme) {
    appliedThemeVars = MV.applyTheme(document.documentElement, theme, appliedThemeVars);
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
    el('lyricsBtn').disabled = !MV.lyricsButtonEnabled(!!v.track, panel);
    var paused = !!(s && s.paused);
    el('toggleIcon').innerHTML = paused ? PLAY_ICON : PAUSE_ICON;
    el('toggle').title = paused ? 'Play' : 'Pause';
    el('toggle').setAttribute('aria-label', el('toggle').title);

    el('volume').disabled = !(v.live && v.signedIn);
    if (!volumeDragging && s) el('volume').value = s.volume;
    if (panel === 'lyrics' && MV.trackKey(s) !== lyricsKey) loadLyrics();
    tick();
  }

  /** Where the song is now: the last reported position, advanced while it plays. */
  function currentPosition() {
    var s = ui && ui.state;
    var t = s && s.track;
    if (!t || ui.positionMs == null) return 0;
    var pos = ui.positionMs;
    if (!s.paused && !t.isStream && s.connected && !ui.stale) pos += Date.now() - ui.positionAtMs;
    return pos;
  }

  /** Advances the progress bar (and the lyrics) between state reports. */
  function tick() {
    var t = ui && ui.state && ui.state.track;
    var pct = t && t.durationMs > 0 ? Math.max(0, Math.min(100, (currentPosition() / t.durationMs) * 100)) : 0;
    el('progressFill').style.width = pct + '%';
    followLyrics();
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

  // ---- Spotify: the logo queues Spotify's current song; links can also be dropped on the window ----
  function renderSpotify(status) {
    var sv = MV.spotifyView(status);
    var btn = el('spotifyBtn');
    btn.disabled = !sv.playable;
    btn.classList.toggle('playable', sv.playable);
    btn.title = sv.tooltip;
    btn.setAttribute('aria-label', sv.tooltip);
  }

  /** Runs a command that answers with a message for the status line. */
  function runAction(cmd, args) {
    invoke(cmd, args).then(function (msg) { if (msg) showStatus(msg); }).catch(function (e) { showStatus(String(e)); });
  }

  el('spotifyBtn').addEventListener('click', function () { runAction('mini_send_spotify'); });

  var dragDepth = 0;
  window.addEventListener('dragenter', function (e) { e.preventDefault(); dragDepth++; el('mini').classList.add('dropping'); });
  window.addEventListener('dragleave', function () { if (--dragDepth <= 0) { dragDepth = 0; el('mini').classList.remove('dropping'); } });
  window.addEventListener('dragover', function (e) { e.preventDefault(); });
  window.addEventListener('drop', function (e) {
    e.preventDefault();
    dragDepth = 0;
    el('mini').classList.remove('dropping');
    var dt = e.dataTransfer;
    var text = dt.getData('text/uri-list') || dt.getData('text/plain') || '';
    runAction('mini_play_spotify_text', { text: text });
  });

  listen('spotify-changed', function (e) { renderSpotify(e.payload); });
  invoke('mini_spotify_now').then(renderSpotify);

  // ---- The menu and the lyrics share the space the window grows into (up, or down near the top) ----
  var panel = 'none';

  function layoutPanel(next, direction) {
    el('shell').classList.toggle('down', direction === 'down');
    el('drawer').hidden = next === 'none';
    el('menuPanel').hidden = next !== 'menu';
    el('lyricsPanel').hidden = next !== 'lyrics';
    el('menuBtn').setAttribute('aria-pressed', String(next === 'menu'));
    el('lyricsBtn').setAttribute('aria-pressed', String(next === 'lyrics'));
    el('lyricsBtn').disabled = !MV.lyricsButtonEnabled(!!view(ui).track, next);
    if (next === 'menu') openMenu();
    if (next === 'lyrics') loadLyrics();
  }

  function setPanel(next) {
    if (next === panel) return;
    var previous = panel;
    panel = next;
    if (next === 'none') layoutPanel('none', null);
    invoke('mini_set_panel', { panel: next }).then(function (direction) {
      if (panel === next && next !== 'none') layoutPanel(next, direction);
    }).catch(function (e) {
      panel = previous;
      showStatus(String(e));
    });
  }

  // The app closed the space (the mini-player was hidden): forget the open panel.
  listen('panel-reset', function () {
    panel = 'none';
    layoutPanel('none', null);
  });

  el('menuBtn').addEventListener('click', function () { setPanel(panel === 'menu' ? 'none' : 'menu'); });
  el('lyricsBtn').addEventListener('click', function () { setPanel(panel === 'lyrics' ? 'none' : 'lyrics'); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && panel !== 'none') setPanel('none'); });

  // ---- Lyrics for the song playing; timed lyrics follow along ----
  var lyricsRequests = MV.latestOnly();
  var lyrics = null;
  var lyricsKey = null;
  var lyricLine = -1;

  function clearLyrics() {
    var body = el('lyricsBody');
    while (body.firstChild) body.removeChild(body.firstChild);
    lyrics = null;
    lyricLine = -1;
    return body;
  }

  function lyricsMessage(text) {
    var p = document.createElement('p');
    p.className = 'note';
    p.textContent = text;
    clearLyrics().appendChild(p);
  }

  function loadLyrics() {
    var s = ui && ui.state;
    lyricsKey = MV.trackKey(s);
    el('lyricsTitle').textContent = s && s.track ? s.track.title : '';
    el('lyricsNote').textContent = '';
    var n = lyricsRequests.next();
    if (!lyricsKey) { lyricsMessage('Nothing playing'); return; }
    lyricsMessage('Loading…');
    invoke('mini_lyrics').then(function (result) {
      if (!lyricsRequests.isLatest(n)) return;
      if (result.instrumental) { lyricsMessage('Instrumental'); return; }
      if (!result.lines.length) { lyricsMessage(result.error || 'No lyrics found'); return; }
      showLyrics(result);
    }).catch(function (e) {
      if (lyricsRequests.isLatest(n)) lyricsMessage(String(e));
    });
  }

  function showLyrics(result) {
    var body = clearLyrics();
    result.lines.forEach(function (line) {
      var p = document.createElement('p');
      p.className = 'lyric';
      p.textContent = line.text || '♪';
      body.appendChild(p);
    });
    body.scrollTop = 0;
    el('lyricsNote').textContent = result.synced ? '' : 'not synced';
    lyrics = result;
    followLyrics();
  }

  /** Highlights the line being sung and keeps it in the middle (timed lyrics only). */
  function followLyrics() {
    if (panel !== 'lyrics' || !lyrics || !lyrics.synced) return;
    var i = MV.currentLyricIndex(lyrics.lines, currentPosition());
    if (i === lyricLine) return;
    var body = el('lyricsBody');
    var previous = body.children[lyricLine];
    if (previous) previous.classList.remove('now');
    lyricLine = i;
    var line = body.children[i];
    if (!line) return;
    line.classList.add('now');
    body.scrollTo({ top: line.offsetTop - body.clientHeight / 2 + line.offsetHeight / 2, behavior: 'smooth' });
  }

  // ---- Menu: search or paste a link ----
  var searches = MV.latestOnly();
  var linkShown = false;

  function note(text) {
    var li = document.createElement('li');
    li.className = 'note';
    li.textContent = text;
    return li;
  }

  function setResults(items) {
    var list = el('results');
    while (list.firstChild) list.removeChild(list.firstChild);
    items.forEach(function (item) { list.appendChild(item); });
  }

  function resultRow(kind, title, artist, time, onPick) {
    var li = document.createElement('li');
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'result' + (kind ? ' ' + kind : '');
    [['r-title', title], ['r-artist', artist], ['r-time', time]].forEach(function (part) {
      if (!part[1]) return;
      var span = document.createElement('span');
      span.className = part[0];
      span.textContent = part[1];
      button.appendChild(span);
    });
    button.title = artist ? title + ' – ' + artist : title;
    button.addEventListener('click', onPick);
    li.appendChild(button);
    return li;
  }

  function showLinkRow(link) {
    searches.next();
    linkShown = true;
    setResults([resultRow('link', 'Play this link', '', '', function () { runAction('mini_play_link', { text: link }); })]);
  }

  function queue(result) {
    var title = result.title || 'Unknown';
    invoke('mini_command', { cmd: { type: 'PlayQuery', value: result.uri } })
      .then(function () { showStatus('Queued “' + title + '”'); })
      .catch(function (e) { showStatus(String(e)); });
  }

  function search(query) {
    var n = searches.next();
    linkShown = false;
    setResults([note('Searching…')]);
    invoke('mini_search', { query: query }).then(function (results) {
      if (!searches.isLatest(n)) return;
      if (!results.length) { setResults([note('No results')]); return; }
      setResults(results.map(function (r) {
        return resultRow('', r.title || 'Unknown', r.author, MV.formatDuration(r.durationMs, r.isStream), function () { queue(r); });
      }));
    }).catch(function (e) {
      if (searches.isLatest(n)) setResults([note(String(e))]);
    });
  }

  function showSettings(show) {
    el('settings').hidden = !show;
    el('results').hidden = show;
    el('settingsBtn').setAttribute('aria-pressed', String(show));
  }

  function openMenu() {
    showSettings(false);
    el('searchBox').focus();
    el('searchBox').select();
    invoke('mini_get_prefs').then(showPrefs);
  }

  el('searchBox').addEventListener('input', function () {
    var text = el('searchBox').value.trim();
    if (MV.isLink(text)) showLinkRow(text);
    else if (linkShown || !text) { searches.next(); linkShown = false; setResults([]); }
  });

  el('searchForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var text = el('searchBox').value.trim();
    if (!text) return;
    showSettings(false);
    if (MV.isLink(text)) runAction('mini_play_link', { text: text });
    else search(text);
  });

  el('settingsBtn').addEventListener('click', function () { showSettings(el('settings').hidden); });

  // ---- Menu: mini-player settings ----
  MV.THEMES.forEach(function (t) {
    var option = document.createElement('option');
    option.value = t.id;
    option.textContent = t.label;
    el('themeSelect').appendChild(option);
  });

  function showPrefs(prefs) {
    var pct = Math.round(prefs.opacity * 100);
    el('opacity').value = pct;
    el('opacityValue').textContent = pct + '%';
    el('matchPanel').checked = prefs.matchPanel;
    el('themeSelect').value = prefs.theme;
    el('themeRow').hidden = prefs.matchPanel;
  }

  el('opacity').addEventListener('input', function () {
    var pct = Number(el('opacity').value);
    el('opacityValue').textContent = pct + '%';
    MV.applyOpacity(document.documentElement, pct / 100);
  });
  el('opacity').addEventListener('change', function () {
    invoke('mini_set_opacity', { value: Number(el('opacity').value) / 100 });
  });

  function saveTheme() {
    el('themeRow').hidden = el('matchPanel').checked;
    invoke('mini_set_theme', { matchPanel: el('matchPanel').checked, theme: el('themeSelect').value });
  }
  el('matchPanel').addEventListener('change', saveTheme);
  el('themeSelect').addEventListener('change', saveTheme);

  invoke('mini_get_prefs').then(function (prefs) {
    MV.applyOpacity(document.documentElement, prefs.opacity);
    showTheme(prefs.effective);
    showPrefs(prefs);
  });
  listen('theme-changed', function (e) { showTheme(e.payload); });

  listen('state-changed', function (e) { render(e.payload); });
  listen('notice', function (e) { showStatus(e.payload.message); });
  invoke('mini_get_state').then(render);
  setInterval(tick, 250);
})();
