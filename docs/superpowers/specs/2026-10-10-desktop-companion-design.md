# Desktop Companion App — Design

Date: 2026-10-10
Status: Draft for review

## 1. Intent

**What Fred asked for:** a desktop app that accompanies the Clout Kings music bot and its web panel, built with Tauri, for Fred and his friends on Windows. Agreed features: the panel in its own window, a tray icon with a mini-player, media keys and the Windows media flyout, track-change notifications, sending the song currently playing in the Spotify desktop app to the bot, and playing a Spotify playlist/album/track link on the bot. Discord Rich Presence was explicitly dropped.

**Success looks like:**
- A friend downloads one small installer from GitHub, signs in with Discord once, and from then on controls the bot from the tray, the mini-player, or their keyboard's media keys without opening a browser.
- One click sends their current Spotify song, or a pasted/dragged Spotify playlist, to the bot.
- The app updates itself.

**Decisions made during brainstorming:**

| Topic | Decision |
|---|---|
| Users / platforms | Fred + friends, Windows 10/11 only |
| Framework | Tauri 2 (Rust core + system WebView2) |
| Code signing | Unsigned for now (SmartScreen warning accepted); keep a slot to add signing later |
| Rich Presence | Not included |
| Spotify | Read Spotify desktop's current song via Windows media sessions (no Spotify login); playlists via drag-and-drop or "play link from clipboard" |
| Architecture | The panel page does the work: an injected bridge script talks to the bot; the native side never handles credentials |

**Assumptions (not stated by Fred, correct me if wrong):**
- The app always talks to one server, `https://cloutkings.duckdns.org`. The address can be changed only by editing the settings file.
- The mini-player uses the Revival palette regardless of the panel's selected theme.
- "Start with Windows" defaults to off; both notification types default to on.

## 2. Architecture

```
Windows PC                                                    Home server
┌──────────────────────── Tauri app ─────────────────────────┐
│ Main window (WebView2) ── https://cloutkings.duckdns.org/web/
│   └ bridge (injected) ── own WebSocket /ws + fetch /api/* ──┼──► bot (cookie session)
│        │ invoke("bridge_report", …)   ▲ webview.eval(cmd)   │
│        ▼                              │                      │
│ Rust core: StateStore ─┬─► Tray icon + menu                  │
│                        ├─► Mini-player window (bundled UI)   │
│                        ├─► Media controls (SMTC + media keys)│
│                        └─► Notifier (toasts)                 │
│            SpotifyReader (Windows media sessions) ──► StateStore / commands
└─────────────────────────────────────────────────────────────┘
```

### Units

Each unit has one job, a small interface, and can be understood without reading the others.

| Unit | Language | Responsibility | Depends on |
|---|---|---|---|
| `bridge/core.js` | JS | Pure functions: turn panel WebSocket/API payloads into `BotState`; decide which API call a `BotCommand` maps to | nothing (unit-tested with `node --test`) |
| `bridge/bridge.js` | JS | Glue inside the panel page: track the selected guild, open the WebSocket, call `/api/me`, report state and notices to Rust, execute commands Rust evals in | `core.js`, page DOM/localStorage |
| `state.rs` | Rust | `StateStore`: holds the latest `BotState`, interpolates playback position, emits change events (`TrackChanged`, `PlaybackChanged`, `QueueHeadChanged`, `ConnectionChanged`, `Cleared`) | nothing (pure; unit-tested) |
| `bridge.rs` | Rust | The `bridge_report` command; builds the injected script; `send_command(BotCommand)` via `webview.eval` | `state.rs` |
| `tray.rs` | Rust | Tray icon, tooltip, menu, menu actions | `state.rs`, `settings.rs`, `bridge.rs` |
| `mini_player.rs` | Rust | Create/show/hide/position the mini-player window; its commands | `state.rs`, `bridge.rs`, `spotify.rs`, `settings.rs` |
| `media_keys.rs` | Rust | Windows SMTC via `souvlaki`: metadata, play state, key events → commands | `state.rs`, `bridge.rs` |
| `spotify.rs` | Rust | Read Spotify desktop's session (title/artist/artwork/status), pause it, parse Spotify links | `windows` crate (Media.Control) |
| `notify.rs` | Rust | "Now playing" and "Your song is up next" toasts | `state.rs`, `settings.rs` |
| `settings.rs` | Rust | Load/save settings JSON with defaults | nothing |
| `main.rs` | Rust | Wire everything, plugins, window lifecycle | all |
| `ui/mini-player/` | HTML/CSS/JS | Mini-player UI, no framework | Tauri IPC (local capability only) |

### Data shapes

`BotState` (reported by the bridge, owned by `StateStore`):
```json
{
  "connected": true,
  "signedIn": true,
  "me": { "id": "233362518045753345", "name": "fred", "avatarUrl": "…" },
  "guild": { "id": "310887883680514049", "name": "Clout Kings" },
  "track": {
    "title": "…", "author": "…", "artworkUrl": "…",
    "uri": "…", "durationMs": 214000, "positionMs": 85000,
    "isStream": false, "requestedBy": "fred", "requestedById": "2333…"
  },
  "paused": false,
  "volume": 80,
  "queueHead": { "title": "…", "requestedById": "…" }
}
```
`track` and `queueHead` are `null` when nothing is playing or the queue is empty. `me` is `null` when `/api/me` is unavailable (older bot).

`BotCommand` (from tray, mini-player, or media keys → Rust → bridge):
`TogglePause`, `Pause`, `Skip`, `RestartTrack` (seek to 0), `SetVolume(0..=150)`, `PlayQuery(String)`, `PlaySpotifyLink(String)`.

The bridge maps each command to the panel's existing endpoints: `/pause` (toggle; `Pause` only calls it when not already paused), `/skip`, `/seek {position:0}`, `/volume {volume}`, `/play {query}`. Spotify links also go to `/play`, which already resolves them.

### Bridge mechanics
- Rust registers an **initialization script** on the main webview. It runs on every page load, checks that `location.origin` equals the configured server origin, and otherwise does nothing.
- **Page → Rust:** a single command, `bridge_report({type: "state" | "notice", …})`. A Tauri capability scoped to the server origin allows only this command. Nothing else is callable from the remote page.
- **Rust → page:** `webview.eval("window.__cksBridge.run(<json command>)")`. This needs no page-side permission, so the remote page gets no event-listening rights.
- **Throttling:** WebView2 throttles timers in hidden windows, possibly to once a minute. So:
  - connection state is reported from WebSocket open/close/message events, not timers;
  - playback position is interpolated in Rust from the last report;
  - the staleness watchdog is lenient.
  - *Refined from the chat:* a 30 s heartbeat timer and a **180 s** stale threshold, instead of 15 s / 45 s, so a throttled hidden window isn't mistaken for a dead connection.

## 3. Features

### Main window
- Loads `https://cloutkings.duckdns.org/web/index.html`.
- Close hides it to the tray; the webview stays alive. **Quit** (tray) exits.
- Single instance: a second launch focuses the existing window.
- **Navigation guard:** top-level navigation is allowed only to the server origin and `https://discord.com` (OAuth). Any other link opens in the default browser.

### Tray
- Icon from the bot favicon. Tooltip is `Title — Artist`, `Nothing playing`, or `Can't reach the bot`.
- Left-click toggles the mini-player.
- Menu:
  - Now playing label (disabled)
  - Play/Pause, Skip
  - ▶ Send my Spotify song *(disabled when Spotify isn't playing)*
  - Play Spotify link from clipboard
  - Show panel, Mini-player
  - Toggles: Now-playing notifications, "Up next" notifications, Pause Spotify after sending, Start with Windows
  - Check for updates, Quit

### Mini-player
- Frameless, always on top, not in the taskbar, about 360 × 140 px.
- Opens above the tray area by default; draggable; position saved.
- Content:
  - artwork, title, "Artist · requested by Name"
  - live progress bar (interpolated)
  - ⏮ (restart), ⏯, ⏭, volume slider (0–150)
  - Spotify row: "▶ Send *Song – Artist*" plus a "Paste Spotify link" button
- Drop target: accepts dragged Spotify links. The window sets `dragDropEnabled: false` so the HTML drop events receive text and URL data.
- States: Nothing playing · Not signed in (button: Open panel) · Reconnecting… · error line (the bot's message, auto-clears after 8 s).
- Updates come from Rust `state-changed` events. It never polls the bot itself.

### Media keys and the Windows media flyout
- `souvlaki` attached to the main window handle.
- The flyout shows title, artist, artwork, and playing/paused state.
- Key mapping: ⏯ → `TogglePause`, ⏭ → `Skip`, ⏮ → `RestartTrack`, ⏹ → `Pause` (never stop, which would clear the shared queue).
- When nothing is playing, the session is detached so media keys fall back to other apps (e.g. Spotify). It reattaches on the next track.

### Spotify: send current song
- `spotify.rs` lists Windows media sessions and picks the one whose app id contains `Spotify` (`Spotify.exe`), excluding this app's own session. It reads title, artist and playback status.
- Sending posts `PlayQuery("Artist - Title")`. The bot searches YouTube, the same way it handles Spotify links today, so occasionally it picks a different version.
- If "Pause Spotify after sending" is on (default), the app calls the session's pause after the bot accepts the request.
- While the mini-player is visible, the label refreshes every 5 s.

### Spotify: play a playlist / album / track link
- Inputs: a drag onto the mini-player, or **Play Spotify link from clipboard**. The clipboard is read only when that action is clicked.
- Accepted forms:
  - `https://open.spotify.com/{playlist|album|track|artist}/{id}`, including optional `intl-xx/` path segments and query strings
  - `spotify:{type}:{id}`
- Anything else → "That's not a Spotify link". The cleaned link is sent as `PlaySpotifyLink`.
- Limits are the bot's existing ones: up to 100 tracks per playlist. Spotify-made editorial/algorithmic playlists may be refused by Spotify; the bot's error is shown.

### Notifications
- **Now playing:** on `TrackChanged`, only if the main window isn't focused and the mini-player isn't visible. Title, then "Artist · requested by Name".
- **Your song is up next:** when `queueHead.requestedById == me.id` and that head entry differs from the last one notified. Skipped when `me` is unknown.
- Each type can be toggled in the tray.

### Settings
Stored in `%APPDATA%\com.fredtheslug.musicbot.desktop\settings.json`:
```json
{
  "serverUrl": "https://cloutkings.duckdns.org",
  "notifyNowPlaying": true,
  "notifyUpNext": true,
  "pauseSpotifyAfterSend": true,
  "startWithWindows": false,
  "miniPlayer": { "x": null, "y": null }
}
```
Missing fields get defaults. If the file can't be parsed, the app uses defaults and rewrites it. "Start with Windows" uses `tauri-plugin-autostart`.

## 4. Bot (server) changes

1. **`GET /api/me`** returns `{ id, name, avatarUrl }` from the web session. It sits behind the existing auth middleware; with web auth disabled it returns 404.
2. **`requestedById`** is added alongside `requestedBy` in now-playing, queue, and history payloads (`RequestMetadata.user.id`, or `null` for autoplay/unknown).
3. The new logic lives in small static helpers in `WebServer`, unit-tested like `WebAccessTest`.

No other server changes. Deploy order: bot first. The app tolerates a bot without `/api/me` by skipping "up next" notifications.

## 5. Project layout, build, release, update

```
desktop/
  package.json            (devDependency: @tauri-apps/cli only)
  bridge/core.js          bridge/bridge.js          bridge/core.test.js
  ui/mini-player/index.html  mini.css  mini.js
  src-tauri/
    Cargo.toml  build.rs  tauri.conf.json
    capabilities/panel.json  capabilities/mini-player.json
    icons/  (generated from src/main/resources/web/favicon.png)
    src/main.rs state.rs bridge.rs tray.rs mini_player.rs
        media_keys.rs spotify.rs notify.rs settings.rs
```
- The root `.gitignore` ignores `*.json` and `*.txt`. Add negations for the `desktop/` JSON files (`package.json`, `package-lock.json`, `tauri.conf.json`, `capabilities/*.json`), and ignore `desktop/node_modules/` and `desktop/src-tauri/target/`.
- Rust plugins: `tray-icon` (core), `single-instance`, `notification`, `autostart`, `updater`, `clipboard-manager`, `log`. Crates: `souvlaki`, `windows` (`Media_Control`), `serde`, `url`.
- **Toolchain:** install Rust (rustup, stable MSVC) on this PC once, with Fred's OK. Node, the MSVC build tools and WebView2 are already present.
- **Installer:** NSIS, per-user install (no admin), unsigned.
- **Release workflow:** `.github/workflows/desktop-release.yml`.
  - Triggers on tags `desktop-v*`. The existing jar workflow triggers on `v*`, so the two don't overlap.
  - Runs on `windows-latest`: `cargo test`, `node --test desktop/bridge`, then `tauri-action` builds and publishes release `desktop-vX.Y.Z` with the installer and its `.sig`.
  - It also replaces `latest.json` on a fixed release tagged `desktop-updates`.
- **Updater:**
  - Endpoint: `https://github.com/FredTheSlug/MusicBot/releases/download/desktop-updates/latest.json`.
  - Checks at startup (silent on failure) and from the tray (errors shown). Always asks before installing.
  - Update artifacts are signed with a Tauri updater key. The public key goes in `tauri.conf.json`; the private key and its password go in GitHub Actions secrets, added only with Fred's OK.
- Version: `desktop/src-tauri/tauri.conf.json` `version`, independent of the bot jar.

## 6. Error handling

| Situation | Behavior |
|---|---|
| Not signed in (`/api/*` → 401) | `signedIn:false`; mini-player shows "Not signed in" + Open panel |
| WebSocket closed | `connected:false` immediately; bridge reconnects with backoff 1 s → 30 s |
| No report for 180 s | StateStore marks disconnected; tray tooltip "Can't reach the bot" |
| Command rejected / bot `error` message | Notice shown in mini-player (or a toast if it's hidden) |
| Spotify not running / not playing | Send action disabled |
| Windows media sessions unavailable | Spotify send hidden; links still work |
| Clipboard/drop isn't a Spotify link | "That's not a Spotify link"; nothing sent |
| souvlaki / notifications fail to init | Logged; app continues without that feature |
| Settings unreadable | Defaults, file rewritten |
| Update check fails | Silent at startup; shown when triggered from tray |

Logging: `tauri-plugin-log` to the app log dir with rotation; no cookies, tokens or URLs with query strings are logged.

## 7. Security

- The bridge runs only when `location.origin` matches the configured server.
- The remote page can invoke only `bridge_report`. It has no event, filesystem, shell, or other plugin permissions.
- The mini-player (bundled, local origin) has its own capability with the app commands it needs.
- Rust never reads or stores cookies or tokens; all bot calls are made by the page with its existing session.
- Top-level navigation is limited to the server and discord.com; everything else opens externally.
- Spotify data read from Windows is limited to the current song's title, artist and status. Nothing is sent anywhere except the chosen song query to the bot.

## 8. Testing

- **Rust unit tests:**
  - `StateStore` transitions: new track, pause/resume, stop, reconnect, stale watchdog, and position interpolation with a fake clock
  - the "up next" rule
  - Spotify link parsing (all accepted forms plus rejects)
  - Spotify session selection from a fake session list (including excluding our own)
  - settings defaults and corrupt-file recovery
- **JS unit tests** (`node --test`): `bridge/core.js` payload → `BotState` mapping and command → endpoint mapping.
- **Java unit tests:** `/api/me` payload helper and `requestedById` mapping; the full existing suite stays green.
- **CI:** the release workflow runs all of the above before building.
- **Manual verification on this PC** before the first release:
  - install from the built installer, log in with Discord
  - live updates in the mini-player
  - media keys and the Windows flyout, including handing keys back when idle
  - Spotify send with pause-after-send
  - clipboard playlist, and drag from the Spotify app (verify the drag data format early)
  - both toasts, close-to-tray, single instance, start with Windows
  - updating from `0.1.0` to `0.1.1` through the real updater

## 9. Build order

Each step is independently working and reviewable:
1. Bot changes (`/api/me`, `requestedById`) and deploy.
2. App shell: main window, bridge, StateStore, tray (Play/Pause, Skip, Show, Quit), close-to-tray, single instance.
3. Mini-player.
4. Media keys + Windows flyout.
5. Spotify: send current song, clipboard/drag links.
6. Notifications, settings toggles, start with Windows.
7. Release workflow, updater keys, first `desktop-v0.1.0` release, update test.

## 10. Risks to verify early

- **Remote-origin IPC:** Tauri 2 capability scoping of `bridge_report` to `https://cloutkings.duckdns.org` works as expected and grants nothing else (step 2).
- **souvlaki on Windows:** confirm it works with the hidden main window's handle (step 4).
- **Spotify drag data:** confirm what dragging a playlist out of the Spotify app produces. If it isn't a usable link, the clipboard button is the supported path (step 5).
- **Discord OAuth inside WebView2:** confirm the login completes and the session cookie persists across app restarts (step 2).
