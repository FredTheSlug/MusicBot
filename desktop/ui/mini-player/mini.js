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
  var COMPACT_ICON = '<path d="M4 14h16v2H4z"/>';
  var FULL_ICON = '<path d="M4 4h16v16H4zm2 2v12h12V6z"/>';
  var compact = false;

  function showCompact(on) {
    compact = !!on;
    document.documentElement.classList.toggle('compact', compact);
    el('compactIcon').innerHTML = compact ? FULL_ICON : COMPACT_ICON;
    el('compactBtn').title = compact ? 'Full size' : 'Compact mode';
    el('compactBtn').setAttribute('aria-label', el('compactBtn').title);
    el('compactOn').checked = compact;
  }

  function setCompact(on) {
    invoke('mini_set_compact', { on: on }).catch(function (e) { showStatus(String(e)); });
  }
  var view = MV.view;
  var appliedThemeVars = [];
  var shownTheme = null;

  function showTheme(theme) {
    shownTheme = theme;
    appliedThemeVars = MV.applyTheme(document.documentElement, theme, appliedThemeVars);
    showPicture(MV.themePicture(theme, reduceMotion.matches));
  }

  // ---- A custom theme's picture: at the back of the card, under a dark layer, faded like the card ----
  // Reduce motion is the system's setting (the mini-player has no switch of its own): a picture that can move
  // (GIF, WebP, PNG) is then drawn to the canvas once, so only its first frame is ever shown.
  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var FRAME_MAX_SIDE = 1024;
  var pictureSeq = 0;
  var pictureWanted = ''; // the picture (and whether frozen) the last call asked for: on show, or loading

  function hidePicture() {
    pictureWanted = '';
    ['bgPic', 'bgFrame', 'bgDim'].forEach(function (id) { el(id).hidden = true; });
    el('bgPic').removeAttribute('src');
  }

  /** Draws the loaded picture (its first frame) to the canvas, at most FRAME_MAX_SIDE wide or tall; false if it can't. */
  function drawFirstFrame(img) {
    var w = img.naturalWidth;
    var h = img.naturalHeight;
    var canvas = el('bgFrame');
    var g = w && h ? canvas.getContext('2d') : null;
    if (!g) return false;
    var shrink = Math.min(1, FRAME_MAX_SIDE / Math.max(w, h));
    canvas.width = Math.max(1, Math.round(w * shrink));
    canvas.height = Math.max(1, Math.round(h * shrink));
    g.drawImage(img, 0, 0, canvas.width, canvas.height);
    return true;
  }

  /**
   * layer = MV.pictureLayer(...) or null. The picture loads first: one that fails shows nothing, not a dark veil.
   * The latest call wins over a load still on its way; asking again for what is already shown (or loading) does nothing.
   */
  function showPicture(layer) {
    var key = layer ? layer.url + '\n' + layer.freeze : '';
    if (layer) el('bgDim').style.setProperty('background-color', layer.overlay);
    if (key === pictureWanted) return;
    pictureWanted = key;
    var seq = ++pictureSeq;
    if (!layer) { hidePicture(); return; }
    var probe = new Image();
    probe.onload = function () {
      if (seq !== pictureSeq) return;
      if (layer.freeze) {
        if (!drawFirstFrame(probe)) { hidePicture(); return; }
        el('bgPic').removeAttribute('src');
      } else {
        el('bgPic').src = layer.url;
      }
      el('bgPic').hidden = layer.freeze;
      el('bgFrame').hidden = !layer.freeze;
      el('bgDim').hidden = false;
    };
    probe.onerror = function () { if (seq === pictureSeq) hidePicture(); };
    probe.src = layer.url;
  }

  function pictureMotionChanged() { if (shownTheme) showPicture(MV.themePicture(shownTheme, reduceMotion.matches)); }
  if (reduceMotion.addEventListener) reduceMotion.addEventListener('change', pictureMotionChanged);
  else if (reduceMotion.addListener) reduceMotion.addListener(pictureMotionChanged);

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
    el('addBtn').disabled = !(v.track || panel === 'picker');
    var paused = !!(s && s.paused);
    el('toggleIcon').innerHTML = paused ? PLAY_ICON : PAUSE_ICON;
    el('toggle').title = paused ? 'Play' : 'Pause';
    el('toggle').setAttribute('aria-label', el('toggle').title);

    el('volume').disabled = !(v.live && v.signedIn);
    if (!volumeDragging && s) el('volume').value = s.volume;
    paintLikes();

    var canControl = !!(v.live && v.signedIn);
    var shuffleOn = !!(s && s.shuffle);
    el('shuffleBtn').disabled = !canControl;
    el('shuffleBtn').classList.toggle('on', shuffleOn);
    el('shuffleBtn').setAttribute('aria-pressed', String(shuffleOn));
    el('shuffleBtn').title = shuffleOn ? 'Shuffle: on' : 'Shuffle: off';
    var rv = MV.repeatView(s && s.repeat);
    el('repeatBtn').disabled = !canControl;
    el('repeatBtn').classList.toggle('on', rv.active);
    el('repeatBtn').title = rv.label;
    el('repeatBtn').setAttribute('aria-label', rv.label);
    el('repeatBadge').textContent = rv.badge;
    el('seek').classList.toggle('off', !canSeek());
    if (panel === 'queue') renderQueue();
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
    if (!seeking) el('progressFill').style.width = pct + '%';
    followLyrics();
    showSleep();
  }

  function showStatus(message) {
    // The line is short (and shorter still with the + beside it); hovering shows the whole message.
    el('status').textContent = message;
    el('status').title = message;
    clearTimeout(statusTimer);
    statusTimer = setTimeout(function () { el('status').textContent = ''; el('status').title = ''; }, STATUS_CLEAR_MS);
  }

  function send(cmd) {
    invoke('mini_command', { cmd: cmd }).catch(function (e) { showStatus(String(e)); });
  }

  // ---- ♥: the song playing (bottom row) and each song in the menu; "♥ My likes (n)" first in the Playlists tab ----
  var likes = null; // MV.likesState of what the app last sent: unknown (null, or uris null) until the bot answers
  var likeClicks = MV.likeClicks(); // per song: the last click, and the one request on its way (see MV.likeToggled)
  var likeSongs = new WeakMap(); // a song row's ♥ → the song it likes (never written into the markup)
  var listing = ''; // what the results list shows: 'playlists' while it is the Playlists tab

  function canLike() {
    var v = view(ui);
    return !!(v.live && v.signedIn);
  }

  function paintHeart(button, song) {
    var hv = MV.heartView(likes, likeClicks, song, canLike());
    button.hidden = hv.hidden;
    button.disabled = hv.disabled;
    button.setAttribute('aria-pressed', String(hv.liked));
    button.title = hv.title;
  }

  /** The row pinned first in the Playlists tab: plays your own likes. Text only. */
  function myLikesItem(row) {
    var li = resultRow('playlist my-likes', row.title, '', '', function () { runAction('mini_play_likes'); });
    li.firstChild.title = 'Play your liked songs';
    return li;
  }

  /** Every ♥ and the "My likes" count, from the bot's list and the clicks on their way. */
  function paintLikes() {
    paintHeart(el('likeBtn'), MV.playlistSong(view(ui).track));
    Array.prototype.forEach.call(el('results').querySelectorAll('.like'), function (button) {
      paintHeart(button, likeSongs.get(button) || null);
    });
    if (listing !== 'playlists') return;
    var list = el('results');
    var shown = list.querySelector('.my-likes');
    var row = MV.myLikesRow(likes, ui && ui.state);
    if (!row) { if (shown) list.removeChild(shown.parentNode); return; }
    if (shown) shown.querySelector('.r-title').textContent = row.title;
    else list.insertBefore(myLikesItem(row), list.firstChild);
  }

  function takeLikes(payload) { likes = MV.likesState(payload, likes); }

  /** Who a ♥ click goes for now (MV.likeAccount): null while no ♥ can be sent. */
  function likeAccount() { return MV.likeAccount(canLike(), likes, ui && ui.state); }

  /**
   * Flips the ♥ at once. A request goes out only when none for this song is on its way (MV.likeToggled decides), so the
   * bot gets a fast like, unlike, like in click order and ends on the last click.
   */
  function toggleLike(song) {
    var account = likeAccount();
    if (!song || account === null) return;
    var click = MV.likeToggled(likes, likeClicks, song.uri, account);
    likeClicks = click.clicks;
    paintLikes();
    if (click.request) sendLike(song, click.request);
  }

  /**
   * One like or unlike; its answer may ask for exactly one more (the clicks made meanwhile). A yes brings the bot's fresh
   * list; a refusal that sends the ♥ back shows the bot's own words.
   */
  function sendLike(song, request) {
    var asked = request.liked ? invoke('mini_like', { song: song }) : invoke('mini_unlike', { uri: song.uri });
    asked.then(function (fresh) {
      takeLikes(fresh);
      settleLike(song, request, true, '');
    }, function (e) {
      settleLike(song, request, false, String(e));
    });
  }

  /** The answer to a like or unlike. A follow-up goes only as a click would now, and for the account that clicked. */
  function settleLike(song, request, ok, message) {
    var answer = MV.likeSettled(likes, likeClicks, song.uri, ok, request.id, likeAccount());
    likeClicks = answer.clicks;
    paintLikes();
    if (answer.reverted) showStatus(message);
    if (answer.request) sendLike(song, answer.request);
  }

  /** A song row's ♥ (beside its +). */
  function likeAction(song) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'icon-btn q-act like';
    button.setAttribute('aria-label', 'Like');
    button.appendChild(el('likeBtn').querySelector('svg').cloneNode(true));
    likeSongs.set(button, song);
    button.addEventListener('click', function () { toggleLike(song); });
    paintHeart(button, song);
    return button;
  }

  el('likeBtn').addEventListener('click', function () { toggleLike(MV.playlistSong(view(ui).track)); });
  listen('likes-changed', function (e) { takeLikes(e.payload); paintLikes(); });
  invoke('mini_likes').then(function (payload) { takeLikes(payload); paintLikes(); });

  el('restart').addEventListener('click', function () { send({ type: 'RestartTrack' }); });
  el('toggle').addEventListener('click', function () { send({ type: 'TogglePause' }); });
  el('skip').addEventListener('click', function () { send({ type: 'Skip' }); });
  el('shuffleBtn').addEventListener('click', function () { send({ type: 'ToggleShuffle' }); });
  el('repeatBtn').addEventListener('click', function () { send({ type: 'CycleRepeat' }); });

  // ---- Seek: click or drag the progress bar ----
  var seeking = false;

  function canSeek() {
    var t = view(ui).track;
    return !!(t && t.durationMs > 0 && !t.isStream);
  }

  function seekFraction(e) {
    var r = el('progress').getBoundingClientRect();
    return r.width > 0 ? (e.clientX - r.left) / r.width : 0;
  }

  function previewSeek(e) {
    el('progressFill').style.width = Math.max(0, Math.min(1, seekFraction(e))) * 100 + '%';
  }

  el('seek').addEventListener('pointerdown', function (e) {
    if (!canSeek()) return;
    seeking = true;
    el('seek').classList.add('dragging');
    el('seek').setPointerCapture(e.pointerId);
    previewSeek(e);
  });
  el('seek').addEventListener('pointermove', function (e) {
    if (!canSeek()) { el('seek').title = ''; return; }
    el('seek').title = MV.formatDuration(MV.seekPosition(seekFraction(e), ui.state.track.durationMs), false);
    if (seeking) previewSeek(e);
  });
  el('seek').addEventListener('pointerup', function (e) {
    if (!seeking) return;
    seeking = false;
    el('seek').classList.remove('dragging');
    if (!canSeek()) return;
    var ms = MV.seekPosition(seekFraction(e), ui.state.track.durationMs);
    // Show the new spot right away; the bot's next report confirms it.
    ui.positionMs = ms;
    ui.positionAtMs = Date.now();
    send({ type: 'Seek', value: ms });
  });
  el('seek').addEventListener('pointercancel', function () {
    seeking = false;
    el('seek').classList.remove('dragging');
  });
  el('volume').addEventListener('input', function () { volumeDragging = true; });
  el('volume').addEventListener('change', function () {
    volumeDragging = false;
    send({ type: 'SetVolume', value: Number(el('volume').value) });
  });
  el('close').addEventListener('click', function () { invoke('mini_hide'); });
  el('openPanel').addEventListener('click', function () { invoke('mini_open_panel'); });
  el('whatsNewBtn').addEventListener('click', function () {
    invoke('mini_show_whats_new').catch(function (e) { showStatus(String(e)); });
  });

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
  // The app reads the clipboard and plays a link on it as the search box would; its answer goes on the status line.
  el('pasteBtn').addEventListener('click', function () { runAction('mini_play_clipboard'); });

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
    el('queuePanel').hidden = next !== 'queue';
    el('pickerPanel').hidden = next !== 'picker';
    el('queueBtn').setAttribute('aria-pressed', String(next === 'queue'));
    el('menuBtn').setAttribute('aria-pressed', String(next === 'menu'));
    el('lyricsBtn').setAttribute('aria-pressed', String(next === 'lyrics'));
    el('lyricsBtn').disabled = !MV.lyricsButtonEnabled(!!view(ui).track, next);
    // The picker only lives while it is showing; whatever it was doing is dropped with it.
    if (next !== 'picker') picker = null;
    el('addBtn').setAttribute('aria-pressed', String(next === 'picker' && !!picker && picker.source === 'current'));
    el('addBtn').disabled = !(view(ui).track || next === 'picker');
    if (next === 'menu') openMenu();
    if (next === 'lyrics') loadLyrics();
    if (next === 'queue') renderQueue();
    if (next === 'picker') showPicker();
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
  el('queueBtn').addEventListener('click', function () { setPanel(panel === 'queue' ? 'none' : 'queue'); });
  el('autoplayBtn').addEventListener('click', function () { send({ type: 'ToggleAutoplay' }); });

  // ---- Up next: the queue, with move-to-top and remove ----
  function queueAction(label, glyph, onClick) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'icon-btn q-act';
    button.title = label;
    button.setAttribute('aria-label', label);
    button.textContent = glyph;
    button.addEventListener('click', onClick);
    return button;
  }

  /** Keeps the first row's columns in line with rows that have a move-to-top button. */
  function spacer() {
    var span = document.createElement('span');
    span.className = 'q-spacer';
    return span;
  }

  function renderQueue() {
    var list = el('queueList');
    while (list.firstChild) list.removeChild(list.firstChild);
    var s = ui && ui.state;
    var total = (s && s.queueLength) || 0;
    el('queueCount').textContent = total ? total + (total === 1 ? ' song' : ' songs') : '';
    var rows = view(ui).live && view(ui).signedIn ? MV.queueRows(s) : [];
    var autoplay = s && typeof s.autoplay === 'boolean' ? s.autoplay : null;
    el('autoplayBtn').hidden = autoplay === null;
    el('autoplayBtn').setAttribute('aria-pressed', String(!!autoplay));
    if (!rows.length) {
      list.appendChild(note(autoplay ? 'Nothing queued — autoplay will pick a related song' : 'Nothing queued'));
      return;
    }
    rows.forEach(function (row) {
      var li = document.createElement('li');
      li.className = 'qrow';
      [['r-title', row.title], ['r-artist', row.sub], ['r-time', row.time]].forEach(function (part) {
        if (!part[1]) return;
        var span = document.createElement('span');
        span.className = part[0];
        span.textContent = part[1];
        li.appendChild(span);
      });
      li.title = row.sub ? row.title + ' – ' + row.sub : row.title;
      var song = { index: row.index, uri: row.uri };
      if (row.index > 0) li.appendChild(queueAction('Play next', '⤒', function () { send({ type: 'MoveToTop', value: song }); }));
      else li.appendChild(spacer());
      li.appendChild(queueAction('Remove from queue', '✕', function () { send({ type: 'RemoveFromQueue', value: song }); }));
      list.appendChild(li);
    });
  }
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' || panel === 'none') return;
    // The picker steps back to where it was opened from (the menu, say); everything else closes.
    if (panel === 'picker') closePicker();
    else setPanel('none');
  });

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

  /** kind 'playlists': the Playlists tab, which gets "♥ My likes (n)" pinned first (see paintLikes). */
  function setResults(items, kind) {
    var list = el('results');
    while (list.firstChild) list.removeChild(list.firstChild);
    items.forEach(function (item) { list.appendChild(item); });
    listing = kind || '';
    if (listing) paintLikes();
  }

  /** onAdd (optional) puts a + beside the row that adds this song to a playlist; likeSong (optional) a ♥ before it. */
  function resultRow(kind, title, artist, time, onPick, onAdd, likeSong) {
    var li = document.createElement('li');
    if (onAdd || likeSong) li.className = 'result-row';
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
    if (likeSong) li.appendChild(likeAction(likeSong));
    if (onAdd) li.appendChild(queueAction('Add to playlist', '+', onAdd));
    return li;
  }

  /** The + handler for a song in a list, or null when it has nothing a playlist could play. */
  function addHandler(item) {
    var song = MV.playlistSong(item);
    return song ? function () { openPicker(song, 'result'); } : null;
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
        return resultRow('', r.title || 'Unknown', r.author, MV.formatDuration(r.durationMs, r.isStream), function () { queue(r); },
          addHandler(r), MV.playlistSong(r));
      }));
    }).catch(function (e) {
      if (searches.isLatest(n)) setResults([note(String(e))]);
    });
  }

  function showSettings(show) {
    el('settings').hidden = !show;
    el('results').hidden = show;
    el('settingsBtn').setAttribute('aria-pressed', String(show));
    showTabs(!show && !el('searchBox').value.trim());
    // The theme list may have moved on since the mini-player opened; what changed comes back as `themes-changed`.
    if (show) invoke('mini_refresh_themes').catch(function () { /* offline or signed out: the list on show stays */ });
  }

  // ---- Menu: quick picks while the search box is empty ----
  var TAB_KEY = 'cks.menuTab';
  var tab = 'recent';
  try { tab = localStorage.getItem(TAB_KEY) || 'recent'; } catch (e) { /* storage unavailable */ }

  function showTabs(show) {
    el('menuTabs').hidden = !show;
    Array.prototype.forEach.call(el('menuTabs').querySelectorAll('.tab'), function (b) {
      b.setAttribute('aria-selected', String(b.getAttribute('data-tab') === tab));
    });
  }

  function loadTab() {
    var n = searches.next();
    linkShown = false;
    setResults([note('Loading…')]);
    var current = tab;
    var cmd = current === 'playlists' ? 'mini_playlists_v2' : current === 'frequent' ? 'mini_frequent' : 'mini_history';
    // The Playlists tab's "♥ My likes (n)" goes first whatever the playlists answer is (see paintLikes).
    var kind = current === 'playlists' ? 'playlists' : '';
    invoke(cmd).then(function (items) {
      if (!searches.isLatest(n)) return;
      if (!items.length) { setResults([note(current === 'playlists' ? 'No saved playlists' : 'Nothing here yet')], kind); return; }
      setResults(items.map(function (it) {
        if (current === 'playlists') {
          return resultRow('playlist', it.name, it.folder || '', MV.playlistCount(it), function () {
            runAction('mini_play_playlist', { name: it.name, folder: it.folder });
          });
        }
        return resultRow('', it.title || 'Unknown', it.author, MV.formatDuration(it.durationMs, it.isStream), function () { queue(it); },
          addHandler(it), MV.playlistSong(it));
      }), kind);
    }).catch(function (e) {
      if (searches.isLatest(n)) setResults([note(String(e))], kind);
    });
  }

  el('menuTabs').addEventListener('click', function (e) {
    var name = e.target && e.target.getAttribute && e.target.getAttribute('data-tab');
    if (!name) return;
    tab = name;
    try { localStorage.setItem(TAB_KEY, tab); } catch (err) { /* storage unavailable */ }
    showTabs(true);
    loadTab();
  });

  function openMenu() {
    showSettings(false);
    el('searchBox').focus();
    el('searchBox').select();
    if (!el('searchBox').value.trim()) loadTab();
    invoke('mini_get_prefs').then(showPrefs);
  }

  el('searchBox').addEventListener('input', function () {
    var text = el('searchBox').value.trim();
    showTabs(!text);
    if (!text) loadTab();
    else if (MV.isLink(text)) showLinkRow(text);
    else if (linkShown) { searches.next(); linkShown = false; setResults([]); }
  });

  el('searchForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var text = el('searchBox').value.trim();
    if (!text) return;
    showSettings(false);
    showTabs(false);
    if (MV.isLink(text)) runAction('mini_play_link', { text: text });
    else search(text);
  });

  el('settingsBtn').addEventListener('click', function () { showSettings(el('settings').hidden); });

  // ---- Add to playlist: the picker in the drawer ----
  var RECENT_KEY = 'cks.playlistRecent';
  var picker = null;

  function readRecent() {
    try {
      var keys = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
      return Array.isArray(keys) ? keys : [];
    } catch (e) { return []; }
  }

  function saveRecent(key) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(MV.rememberRecent(readRecent(), key))); } catch (e) { /* storage unavailable */ }
  }

  /**
   * Opens the picker for `song`. `source` is 'current' (the + in the bottom row) or 'result' (a song in
   * the menu); closing returns to whatever was open before.
   */
  function openPicker(song, source) {
    if (!song) return;
    var returnTo = panel === 'picker' ? (picker ? picker.returnTo : 'none') : panel;
    picker = { song: song, source: source, returnTo: returnTo, playlists: null, loadError: '', query: '', creating: false, busy: false };
    if (panel === 'picker') showPicker();
    else setPanel('picker');
  }

  function closePicker() {
    setPanel(picker ? picker.returnTo : 'none');
  }

  function setPickerError(p, text) {
    el('pickerError').textContent = text;
    el('pickerError').hidden = !text;
  }

  /** While a request is out the lists and the new-playlist form ignore clicks (see .busy in the CSS). */
  function setPickerBusy(p, busy) {
    p.busy = busy;
    el('pickerPanel').classList.toggle('busy', busy);
  }

  function showPicker() {
    var p = picker;
    if (!p) return;
    el('pickerTitle').textContent = 'Add “' + (p.song.title || 'this song') + '” to…';
    el('pickerTitle').title = p.song.title ? p.song.title + (p.song.author ? ' – ' + p.song.author : '') : '';
    el('pickerFilter').value = '';
    el('pickerPanel').classList.remove('busy');
    setPickerError(p, '');
    renderPickerFoot();
    loadPlaylists(p);
    el('pickerFilter').focus();
  }

  function loadPlaylists(p) {
    p.playlists = null;
    p.loadError = '';
    renderPickerList();
    invoke('mini_playlists_v2').then(function (items) {
      if (picker !== p) return;
      p.playlists = items;
      renderPickerList();
    }).catch(function (e) {
      if (picker !== p) return;
      p.loadError = MV.pickerError(e, "Couldn't load your playlists");
      renderPickerList();
    });
  }

  function pickerGroup(text) {
    var li = document.createElement('li');
    li.className = 'group';
    li.textContent = text;
    return li;
  }

  function pickerItem(p, row) {
    var li = resultRow('picker-item', row.name, row.path, row.count, function () { addToPlaylist(p, row); });
    if (row.legacy) {
      // The bot is still converting it; it can't take songs until it has.
      li.firstChild.disabled = true;
      li.firstChild.title = 'Still being upgraded to the new format — try again in a moment';
    }
    return li;
  }

  function renderPickerList() {
    var p = picker;
    if (!p) return;
    var list = el('pickerList');
    while (list.firstChild) list.removeChild(list.firstChild);
    if (p.loadError) {
      var failed = note(p.loadError);
      var retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'tab picker-retry';
      retry.textContent = 'Try again';
      retry.addEventListener('click', function () { loadPlaylists(p); });
      failed.appendChild(retry);
      list.appendChild(failed);
      return;
    }
    if (p.playlists === null) { list.appendChild(note('Loading playlists…')); return; }
    var rows = MV.pickerRows(p.playlists, readRecent(), p.query);
    if (!rows.recent.length && !rows.rest.length) {
      list.appendChild(note(p.query.trim() ? 'No matching playlists' : 'No playlists yet'));
      return;
    }
    var headings = !p.query.trim() && rows.recent.length > 0;
    if (headings) list.appendChild(pickerGroup('Recently used'));
    rows.recent.forEach(function (row) { list.appendChild(pickerItem(p, row)); });
    if (headings && rows.rest.length) list.appendChild(pickerGroup('All playlists'));
    rows.rest.forEach(function (row) { list.appendChild(pickerItem(p, row)); });
  }

  /** "+ New playlist…", or its inline form (name, Create, Cancel) once clicked. */
  function renderPickerFoot() {
    var p = picker;
    if (!p) return;
    var foot = el('pickerFoot');
    while (foot.firstChild) foot.removeChild(foot.firstChild);
    if (!p.creating) {
      var open = document.createElement('button');
      open.type = 'button';
      open.className = 'picker-new';
      open.textContent = '+ New playlist…';
      open.addEventListener('click', function () {
        if (p.busy) return;
        p.creating = true;
        setPickerError(p, '');
        renderPickerFoot();
      });
      foot.appendChild(open);
      return;
    }
    var form = document.createElement('form');
    form.className = 'picker-newform';
    form.autocomplete = 'off';
    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'search-box';
    input.maxLength = 100;
    input.placeholder = 'New playlist name';
    input.setAttribute('aria-label', 'New playlist name');
    input.spellcheck = false;
    input.value = p.query.trim();
    var create = document.createElement('button');
    create.type = 'submit';
    create.className = 'tab';
    create.textContent = 'Create';
    var cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'tab';
    cancel.textContent = 'Cancel';
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      createAndAdd(p, input.value);
    });
    cancel.addEventListener('click', function () {
      if (p.busy) return;
      p.creating = false;
      setPickerError(p, '');
      renderPickerFoot();
    });
    form.appendChild(input);
    form.appendChild(create);
    form.appendChild(cancel);
    foot.appendChild(form);
    input.focus();
    input.select();
  }

  /** The song went in: remember the playlist, say so on the status line and step back. */
  function pickerDone(p, key, message) {
    saveRecent(key);
    showStatus(message || 'Added to the playlist');
    if (picker === p) closePicker();
  }

  /** The bot's own words stay in the picker; if it was closed meanwhile they go to the status line. */
  function pickerFailed(p, e, prefix) {
    var text = (prefix || '') + MV.pickerError(e);
    if (picker !== p) { showStatus(text); return; }
    setPickerBusy(p, false);
    setPickerError(p, text);
  }

  function addToPlaylist(p, row) {
    if (picker !== p || p.busy) return;
    setPickerBusy(p, true);
    setPickerError(p, '');
    invoke('mini_add_to_playlist', { name: row.name, folder: row.folder, song: p.song }).then(
      function (message) { pickerDone(p, row.key, message); },
      function (e) { pickerFailed(p, e); }
    );
  }

  /** Creates the playlist at the top level, then adds the song to it. */
  function createAndAdd(p, rawName) {
    if (picker !== p || p.busy) return;
    var name = String(rawName || '').trim();
    if (!name) { setPickerError(p, 'Give the playlist a name'); return; }
    setPickerBusy(p, true);
    setPickerError(p, '');
    invoke('mini_create_playlist', { name: name, folder: null }).then(function () {
      var made = { name: name, folder: null, songCount: 0, legacy: false };
      return invoke('mini_add_to_playlist', { name: name, folder: null, song: p.song }).then(function (message) {
        pickerDone(p, MV.playlistKey(made), message);
      }, function (e) {
        // It exists now: list it, so picking it is a retry and not a second "create".
        if (Array.isArray(p.playlists)) p.playlists.push(made);
        if (picker === p) { p.creating = false; renderPickerFoot(); renderPickerList(); }
        pickerFailed(p, e, '“' + name + '” was created, but the song was not added: ');
      });
    }, function (e) { pickerFailed(p, e); });
  }

  el('addBtn').addEventListener('click', function () {
    if (panel === 'picker' && picker && picker.source === 'current') closePicker();
    else openPicker(MV.playlistSong(view(ui).track), 'current');
  });
  el('pickerClose').addEventListener('click', closePicker);
  el('pickerFilter').addEventListener('input', function () {
    if (!picker) return;
    picker.query = el('pickerFilter').value;
    renderPickerList();
  });

  // ---- Global hotkeys ----
  var HOTKEY_LABELS = [
    ['playPause', 'Play/pause'], ['skip', 'Skip'], ['restart', 'Restart song'],
    ['sendSpotify', 'Send my Spotify song'], ['toggleMiniPlayer', 'Show/hide mini-player']
  ];
  var recording = null;

  function showHotkeys(hk) {
    el('hotkeysOn').checked = hk.enabled;
    var list = el('hotkeyList');
    list.hidden = !hk.enabled;
    while (list.firstChild) list.removeChild(list.firstChild);
    HOTKEY_LABELS.forEach(function (pair) {
      var row = document.createElement('div');
      row.className = 'hotkey';
      var label = document.createElement('span');
      label.textContent = pair[1];
      var button = document.createElement('button');
      button.type = 'button';
      button.textContent = hk.bindings[pair[0]] || '';
      var failed = hk.failed.indexOf(pair[0]) >= 0;
      button.classList.toggle('failed', failed);
      button.title = failed ? 'Another app is using this combination — click to choose another' : 'Click, then press a new combination (Esc cancels)';
      button.addEventListener('click', function () { startRecording(pair[0], button); });
      row.appendChild(label);
      row.appendChild(button);
      list.appendChild(row);
    });
  }

  function startRecording(action, button) {
    if (recording) stopRecording(recording.previous);
    recording = { action: action, button: button, previous: button.textContent };
    button.classList.add('recording');
    button.textContent = 'Press keys…';
    // Our own combinations would be swallowed (and fire) while recording, so release them.
    invoke('mini_hotkeys_suspend', { suspend: true }).catch(function () {});
    showStatus('Press the new keys (Esc cancels). If nothing happens, another app uses them.');
  }

  function stopRecording(text) {
    if (!recording) return;
    recording.button.classList.remove('recording');
    recording.button.textContent = text;
    recording = null;
    invoke('mini_hotkeys_suspend', { suspend: false }).catch(function () {});
  }

  window.addEventListener('blur', function () { if (recording) stopRecording(recording.previous); });

  // Capture before anything else (Escape would otherwise close the menu).
  document.addEventListener('keydown', function (e) {
    if (!recording) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.key === 'Escape') { stopRecording(recording.previous); return; }
    var accel = MV.accelFromEvent(e);
    if (!accel) return;
    var action = recording.action;
    var previous = recording.previous;
    stopRecording(accel);
    invoke('mini_set_hotkey', { action: action, accel: accel }).then(showHotkeys).catch(function (err) {
      showStatus(String(err));
      invoke('mini_get_hotkeys').then(showHotkeys, function () { stopRecording(previous); });
    });
  }, true);

  el('hotkeysOn').addEventListener('change', function () {
    invoke('mini_set_hotkeys_enabled', { on: el('hotkeysOn').checked }).then(function (hk) {
      showHotkeys(hk);
      if (hk.enabled && hk.failed.length) showStatus('Some hotkeys are taken by other apps');
    });
  });

  // ---- Sleep timer ----
  var sleepInfo = null;

  function showSleep() {
    var label = MV.sleepLabel(sleepInfo, Date.now());
    el('sleepBadge').hidden = !label;
    el('sleepBadge').title = label;
  }

  function setSleepInfo(info) {
    sleepInfo = info;
    el('sleepSelect').value = info && info.mode === 'timer' ? el('sleepSelect').value : info && info.mode === 'end' ? 'end' : 'off';
    showSleep();
  }

  el('sleepSelect').addEventListener('change', function () {
    invoke('mini_set_sleep', { choice: el('sleepSelect').value }).then(function (info) {
      setSleepInfo(info);
      var label = MV.sleepLabel(info, Date.now());
      showStatus(label || 'Sleep timer off');
    }).catch(function (e) { showStatus(String(e)); });
  });
  listen('sleep-changed', function (e) { setSleepInfo(e.payload); });
  invoke('mini_get_sleep').then(setSleepInfo);

  // ---- Menu: mini-player settings ----

  /** menu = {customThemes, theme}: the built-in themes, then the custom ones with a mini-player version (names as text only). */
  function showThemeMenu(menu) {
    var select = el('themeSelect');
    while (select.firstChild) select.removeChild(select.firstChild);
    MV.themeMenu(menu.customThemes).forEach(function (group) {
      var optgroup = document.createElement('optgroup');
      optgroup.label = group.label;
      group.options.forEach(function (item) {
        var option = document.createElement('option');
        option.value = item.value;
        option.textContent = item.label;
        optgroup.appendChild(option);
      });
      select.appendChild(optgroup);
    });
    select.value = menu.theme;
  }

  listen('themes-changed', function (e) { showThemeMenu(e.payload); });

  function showPrefs(prefs) {
    var pct = Math.round(prefs.opacity * 100);
    el('opacity').value = pct;
    el('opacityValue').textContent = pct + '%';
    el('matchPanel').checked = prefs.matchPanel;
    showThemeMenu(prefs);
    el('themeRow').hidden = prefs.matchPanel;
    el('hideFullscreen').checked = prefs.hideInFullscreen;
    el('compactOn').checked = prefs.compact;
    invoke('mini_get_hotkeys').then(showHotkeys);
  }

  el('compactBtn').addEventListener('click', function () { setCompact(!compact); });
  el('compactOn').addEventListener('change', function () { setCompact(el('compactOn').checked); });
  listen('compact-changed', function (e) { showCompact(e.payload.compact); });

  el('hideFullscreen').addEventListener('change', function () {
    invoke('mini_set_hide_in_fullscreen', { on: el('hideFullscreen').checked });
  });

  el('opacity').addEventListener('input', function () {
    var pct = Number(el('opacity').value);
    el('opacityValue').textContent = pct + '%';
    MV.applyOpacity(document.documentElement, pct / 100);
  });
  el('opacity').addEventListener('change', function () {
    invoke('mini_set_opacity', { value: Number(el('opacity').value) / 100 });
  });

  // menuPicked: the person chose in the theme menu. Flipping the switch alone sends no theme (see MV.themeSetArgs).
  function saveTheme(menuPicked) {
    el('themeRow').hidden = el('matchPanel').checked;
    invoke('mini_set_theme', MV.themeSetArgs(el('matchPanel').checked, el('themeSelect').value, menuPicked));
  }
  el('matchPanel').addEventListener('change', function () { saveTheme(false); });
  el('themeSelect').addEventListener('change', function () { saveTheme(true); });

  invoke('mini_get_prefs').then(function (prefs) {
    showCompact(prefs.compact);
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
