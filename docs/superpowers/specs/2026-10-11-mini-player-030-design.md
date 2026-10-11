# Mini-player 0.3.0 — Design

Extends `2026-10-10-mini-player-extras-design.md`. App-only: every feature uses endpoints the bot
already has, so no bot deploy. Fred approved implementing these without review stops ("auto
approve"); ideas came from Spotify/Apple Music mini players, FluentFlyout, the YouTube Music desktop
app and Discord bot dashboards.

## 1. Features

1. **Up Next** — a queue button in the bottom row opens a drawer panel listing the next songs (up to
   20, with title, artist, who added it, length) and the total count. Each row can be removed (✕) or
   moved to the top (⤒). Updates live as the queue changes.
2. **Shuffle and repeat** — two buttons in the controls row. Shuffle toggles the bot's shuffle mode;
   repeat cycles Off → All → One (the bot's own order). Both show the bot's current state.
3. **Seek** — clicking or dragging the progress bar seeks (not for streams or unknown lengths); the
   bar's tooltip shows the time under the pointer.
4. **Lyrics pinned to the song** — the lyrics request names the song (title, artist, length), so a
   song change mid-request can't show the next song's lyrics.
5. **Recent / Most played / Playlists** — with the search box empty, the menu shows three tabs; a row
   queues that song, a playlist row plays that playlist. Typing switches to search results.
6. **Sleep timer** — Off / 15 / 30 / 60 minutes / End of song, in ⚙. When it fires the app pauses the
   bot and says so. Session only (not saved). The status line shows when it will stop.
7. **Hide during fullscreen** — when a fullscreen game, video or presentation starts, a visible
   mini-player hides; it comes back (without stealing focus) when fullscreen ends. Only the hide the
   app did itself is undone. Toggle in ⚙, on by default.
8. **Global hotkeys** — play/pause, skip, restart, send my Spotify song, show/hide mini-player. Off by
   default (Ctrl+Alt is AltGr on many keyboards); when on, defaults are Ctrl+Alt+P / N / B / S / M.
   Each binding can be re-recorded in ⚙ by clicking it and pressing a combination; a combination
   another app already owns is reported and not saved.
9. **Compact mode** — a 360×64 bar: artwork, title/artist, play/pause, skip, menu, close, and a thin
   progress line. Toggle in ⚙ and with a button next to ✕ in both modes. Switching keeps the bottom
   edge where it was; menu/lyrics/Up Next still open above (or below) it.

## 2. Architecture

### Bridge (core.js / bridge.js)

- `toBotState` adds `shuffle` (bool, from `nowPlaying.shuffleMode`), `repeat` (`"OFF"|"ALL"|"SINGLE"`,
  default `"OFF"`), `upNext` (first 20 of `queue.tracks`: `{title, author, uri, durationMs,
  requestedBy}`) and `queueLength` (`queue.size` or tracks length).
- New commands → requests: `ToggleShuffle` → POST `/shuffle`; `CycleRepeat` → POST `/repeat`;
  `Seek(ms)` → POST `/seek {position}`; `RemoveFromQueue {index, uri}` → POST `/remove/{index}`;
  `MoveToTop {index, uri}` → POST `/reorder {from: index, to: 0}`; `PlayPlaylist {name, folder}` →
  POST `/playplaylist/{name}[?folder=]`; fetches `History` → GET `/history`, `Frequent` → GET
  `/frequent`, `Playlists` → GET `/playlists-tree`; `Lyrics {title, author, durationMs}` → GET
  `/lyrics?title=&author=&duration=`.
- Queue edits name the song as well as the position: the bridge refuses (`"The queue changed — try
  again"`) when `queue.tracks[index].uri` isn't the expected one, so a friend's edit in between can't
  make you remove the wrong song.

### Rust

- `BotState` gains `shuffle: bool`, `repeat: RepeatMode {Off, All, Single}` (serde
  `"OFF"/"ALL"/"SINGLE"`, default Off), `up_next: Vec<QueueItem>` (capped at 20, strings cut to 200
  chars) and `queue_length: u32`; all `#[serde(default)]` so older bridges still parse.
- `BotCommand` gains the commands above. `lookup` maps history/frequent (`{tracks: [...]}`) to the
  existing `SearchResult` (cap 50) and the playlists tree to `PlaylistInfo {name, folder,
  trackCount}` (cap 100; folders flattened).
- `sleep.rs` (pure + a worker): `SleepMode {Off, At(deadline), EndOfSong}`; `sleep_due(mode, now,
  position, duration, paused) -> bool` (EndOfSong fires within 1.5 s of the end of a non-stream
  track); a worker checks every 500 ms, sends `Pause`, emits a notice, resets to Off.
- `fullscreen.rs`: `SHQueryUserNotificationState`; `is_fullscreen(state)` true for BUSY,
  RUNNING_D3D_FULL_SCREEN and PRESENTATION_MODE; `transition(prev, now) -> Option<Hide|Restore>`
  edge-triggered; polled every second when the setting is on.
- `hotkeys.rs`: `tauri-plugin-global-shortcut`; `HotkeyAction {PlayPause, Skip, Restart,
  SendSpotify, ToggleMiniPlayer}`; settings `hotkeys: {enabled, bindings}`; (re)register on start and
  on change; a failed registration is reported back to the UI for that binding.
- Compact mode: `closed_size(scale, compact)`; `resized_origin(origin, old_h, new_h)` keeps the bottom
  edge; `mini_set_compact(bool)` resizes, saves, emits `compact-changed`.
- Settings (all optional): `miniPlayerCompact` (false), `hideInFullscreen` (true), `hotkeys`
  (`enabled: false`, bindings above).

### Mini-player UI

- Controls row: restart, play/pause, skip, shuffle, repeat (badge "1" for One), volume.
- Bottom row: Spotify, lyrics, Up Next, status, ☰. A compact toggle sits next to ✕.
- Drawer panels: menu (search/tabs/⚙), lyrics, queue. ⚙ gets Compact mode, Hide during fullscreen,
  Sleep timer, Global hotkeys (switch + five recordable bindings); the settings list scrolls.
- Pure helpers in view.js (tested): `repeatView(mode)`, `seekPosition(fraction, durationMs)`,
  `accelFromEvent(e)` (needs Ctrl/Alt/Shift unless F1–F24; keys from `e.code`), `queueRows(state)`,
  `sleepLabel(info, now)`.

## 3. Error handling

Every command failure shows in the status line. Queue edits refused for a changed queue say so.
Hotkey registration failures name the combination. Fullscreen detection failure leaves the
mini-player alone. Sleep timer: if the bot is already paused, nothing is sent.

## 4. Security

Unchanged boundary: the remote page still only calls `bridge_report`; new remote data (queue items,
history, playlists) is capped and rendered as text. Playlist names go into a URL path segment with
`encodeURIComponent`. Global shortcuts are registered from Rust only (no JS permission).

## 5. Testing

JS: request mapping for each new command (encoding of playlist names/folders, lyrics params), the
queue-identity check, `toBotState` new fields, view helpers. Rust: BotState defaults with an old
payload, `RepeatMode` serde, lookup mapping + caps, `sleep_due`, fullscreen `transition`,
`resized_origin`/`closed_size` for both modes, hotkey binding defaults/parse. By hand (Fred): each
feature in the dev build.
