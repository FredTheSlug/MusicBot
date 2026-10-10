# Desktop Companion App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A small Windows tray app (Tauri 2) that hosts the Clout Kings web panel and adds a mini-player, media keys / Windows media flyout, toasts, and "send to bot" from the Spotify desktop app.

**Architecture:** The main window loads the live panel. An injected bridge script (JS) uses the page's own login to talk to the bot and reports a compact `BotState` to the Rust core through one narrowly permitted command. Rust fans state changes out to the tray, mini-player, media controls and notifier, and sends commands back to the page with `webview.eval`. The bot gains `/api/me` and `requestedById`.

**Tech Stack:** Tauri 2.12 (Rust, WebView2), plugins single-instance 2.5 / notification 2.6 / autostart 2.7 / updater 2.13 / clipboard-manager 2.4 / log 2.10 / opener 2.7 / dialog 2, `souvlaki` 0.8.3, `windows` 0.62 (`Media_Control`), `@tauri-apps/cli` 2.12, Node 22 built-in test runner, Java 11 / JUnit 4 for the bot.

**Spec:** `docs/superpowers/specs/2026-10-10-desktop-companion-design.md`

## Global Constraints

- Windows 10/11 only; NSIS installer, per-user install (no admin), **unsigned**.
- Product name `Clout Kings Music Bot`; identifier `com.fredtheslug.musicbot.desktop`; first version `0.1.0`.
- Default server `https://cloutkings.duckdns.org`; panel URL = server + `/web/index.html`.
- The remote page may invoke **only** `bridge_report`. No other app command or plugin permission is granted to the `main` window.
- Rust never reads or stores cookies or tokens. All bot calls are made by the page.
- Main-window top-level navigation is allowed only to the server origin and `https://discord.com`; everything else opens in the default browser.
- Timings: bridge heartbeat **30 s**, stale threshold **180 s**, WebSocket reconnect backoff **1 s → 30 s** (doubling), mini-player error line clears after **8 s**, Spotify label refresh **5 s**.
- Volume range **0–150**. Media keys: ⏯ → TogglePause, ⏭ → Skip, ⏮ → RestartTrack, ⏹ → **Pause** (never stop).
- No Discord Rich Presence.
- Settings file `%APPDATA%\com.fredtheslug.musicbot.desktop\settings.json`, with defaults `serverUrl` above, `notifyNowPlaying: true`, `notifyUpNext: true`, `pauseSpotifyAfterSend: true`, `startWithWindows: false`, `miniPlayer: {x: null, y: null}`.
- Mini-player about **360 × 140**, frameless, always on top, not in the taskbar, Revival palette (bg `#14100d`, card `#211b17`, accent `#e2825c`, amber `#f2b35e`, text `#f6efe5`, secondary `#cdbcaa`; Fraunces for titles).
- Updater endpoint `https://github.com/FredTheSlug/MusicBot/releases/download/desktop-updates/latest.json`; release tags `desktop-v*`.
- Logs must not contain cookies, tokens, or URLs with query strings.
- Anything outward-facing (installing Rust, deploying the bot, pushing tags, adding GitHub secrets) waits for Fred's explicit OK at that step.
- GUI checks (tray, flyout, toasts, drag and drop) are done by **Fred** on this PC, with the checklist given in the task. The executor can't see native windows.

## Review Focus

1. **Bot restarts while the app runs:** web sessions live in memory on the bot, so a bot restart logs everyone out. Expected: the app shows "Not signed in" (not a stale track, not an infinite "Reconnecting…") and recovers as soon as the user logs in again in the panel, without restarting the app. → Task 3 test `signed_out_state_carries_no_track`, Task 4 test `sign_out_clears_track_and_emits_connection_change`.
2. **App start / reconnect replays the current song:** the first state after launch or after a reconnect must not toast "Now playing". → Task 4 test `first_state_is_initial_track_change`, Task 10 test `initial_track_change_does_not_toast`.
3. **Switching servers in the panel:** messages for the previous guild must be ignored and commands must target the newly selected guild. → Task 3 test `parse_ws_message_keeps_only_watched_guild`, `command_paths_encode_guild_id`.
4. **Odd or long text** (emoji, quotes, backslashes, newlines, 200-char titles) in titles and Spotify metadata: tooltips must truncate without panicking on a multi-byte character, and commands eval'd into the page must round-trip exactly. → Task 6 test `tooltip_truncates_on_char_boundary`, Task 5 test `command_js_round_trips_hostile_text`.
5. **Mini-player saved on a monitor that is no longer connected:** it must reappear on screen at the default spot, not open invisibly. → Task 7 test `saved_position_off_screen_falls_back_to_default`.

---

## File map

```
src/main/java/com/jagrosh/jmusicbot/web/WebServer.java      (modify: /api/me, requestedById)
src/test/java/com/jagrosh/jmusicbot/web/WebPayloadsTest.java (create)
.gitignore                                                   (modify: desktop/ negations + ignores)
.github/workflows/desktop-release.yml                        (create, Task 11)
desktop/
  package.json, package-lock.json        npm scripts: dev = "tauri dev", build = "tauri build", test:js = "node --test bridge/"
  bridge/core.js        pure: payload → BotState, ws parsing, command → request   (UMD: module.exports or window.__cksCore)
  bridge/core.test.js   node:test
  bridge/bridge.js      page glue: guild tracking, /api/me, WebSocket, reports, window.__cksBridge.run
  ui/mini-player/index.html, mini.css, mini.js
  src-tauri/Cargo.toml, build.rs, tauri.conf.json
  src-tauri/capabilities/mini-player.json   (static; the panel capability is added at runtime)
  src-tauri/icons/app-icon.svg (+ generated icons)
  src-tauri/src/main.rs         wiring, plugins, window lifecycle, dispatch, allowed_navigation, start_hidden
  src-tauri/src/settings.rs     Settings load/save/defaults, origin/panel URL
  src-tauri/src/state.rs        BotState types, StateStore, Change, is_my_song_next
  src-tauri/src/bridge.rs       BotCommand, BridgeReport, bridge_report cmd, init_script, command_js, send_command(_await)
  src-tauri/src/tray.rs         tray build/refresh, menu ids, tooltip_text
  src-tauri/src/mini_player.rs  window create/show/hide/position, UiState, mini_* commands
  src-tauri/src/media_keys.rs   souvlaki wrapper, key_to_command
  src-tauri/src/spotify.rs      link parsing, session pick, Windows media session reader, pause
  src-tauri/src/notify.rs       Toast, toasts_for
```

---

### Task 1: Bot — `/api/me` and `requestedById`

**Files:**
- Modify: `src/main/java/com/jagrosh/jmusicbot/web/WebServer.java` (`requestedBy` sites at ~413, 470, 501, 613, 2290; route list ~169–210)
- Test: `src/test/java/com/jagrosh/jmusicbot/web/WebPayloadsTest.java`

**Interfaces:**
- Produces (HTTP): `GET /api/me` → `{"id":"<unsigned decimal>","name":"…","avatarUrl":"…"}` (404 when web auth is disabled). Now-playing, queue and history track objects gain `requestedById` (string or null) next to `requestedBy`.
- Produces (Java): `static void putRequester(Map<String,Object> target, RequestMetadata rm, String fallbackName)`, `static Map<String,Object> mePayload(long discordUserId, String name, String avatarUrl)`.

- [ ] **Step 1: Write the failing tests**

```java
public class WebPayloadsTest
{
    @Test
    public void requesterFieldsIncludeNameAndId()
    {
        Map<String, Object> t = new HashMap<>();
        WebServer.putRequester(t, RequestMetadata.fromDiscordWebUser(233362518045753345L, "fred", "0", "", null), "Web");
        assertEquals("fred", t.get("requestedBy"));
        assertEquals("233362518045753345", t.get("requestedById"));
    }

    @Test
    public void requesterFieldsFallBackWhenNoUser()
    {
        Map<String, Object> t = new HashMap<>();
        WebServer.putRequester(t, null, "Web");
        assertEquals("Web", t.get("requestedBy"));
        assertTrue(t.containsKey("requestedById"));
        assertNull(t.get("requestedById"));
        WebServer.putRequester(t, RequestMetadata.EMPTY, "");
        assertEquals("", t.get("requestedBy"));
        assertNull(t.get("requestedById"));
    }

    @Test
    public void mePayloadHasIdNameAndAvatar()
    {
        Map<String, Object> me = WebServer.mePayload(233362518045753345L, "fred", "https://cdn.discordapp.com/avatars/1/a.png");
        assertEquals("233362518045753345", me.get("id"));
        assertEquals("fred", me.get("name"));
        assertEquals("https://cdn.discordapp.com/avatars/1/a.png", me.get("avatarUrl"));
    }
}
```

- [ ] **Step 2: Run to verify failure:** `mvn -o test -Dtest=WebPayloadsTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure (`putRequester` / `mePayload` not found). Then add empty stubs and re-run to see 3 assertion failures.
- [ ] **Step 3: Implement** `putRequester`, using `Long.toUnsignedString(rm.user.id)`, with a null id when `rm == null`, `rm.user == null`, or `id == 0`. Implement `mePayload`. Replace all five `requestedBy` puts with `putRequester`, keeping today's fallbacks: now-playing `""`, queue `"Web"`, history `""`. Add `app.get("/api/me", this::handleMe)`: return 404 `{"error":"Web authentication is disabled"}` when auth is off; otherwise `mePayload(session.discordUserId, session.displayName, session.avatarUrl)` from `ctx.attribute(AuthSession.CTX_KEY)`.
- [ ] **Step 4: Run the full suite:** `mvn -o test`. Expected: all tests pass (36 = 33 existing + 3).
- [ ] **Step 5: Commit:** `git commit -m "Add /api/me and requestedById for the desktop app"`.
- [ ] **Step 6: Deploy (ask Fred first).** Build with `mvn -o -q package`, then use the deploy routine in memory `home-server-deploy`: idle check, scp, hash check, back up as `JMusicBot-<date>-pre-desktop.jar`, swap, restart, verify the startup log. Then confirm `curl -s -o /dev/null -w "%{http_code}" https://cloutkings.duckdns.org/api/me` returns `401` (route exists, needs login).

---

### Task 2: Scaffold, settings, and main window

**Files:**
- Modify: `.gitignore`
- Create: `desktop/package.json`, `desktop/src-tauri/{Cargo.toml,build.rs,tauri.conf.json}`, `desktop/src-tauri/icons/app-icon.svg`, `desktop/src-tauri/src/{main.rs,settings.rs}`, `desktop/ui/mini-player/index.html` (placeholder so `frontendDist` exists)

**Interfaces:**
- Produces: `settings::Settings { server_url: String, notify_now_playing: bool, notify_up_next: bool, pause_spotify_after_send: bool, start_with_windows: bool, mini_player: MiniPos { x: Option<i32>, y: Option<i32> } }` (serde camelCase, `#[serde(default)]`, `Default`, `Clone`, `PartialEq`, `Debug`); `settings::load(path: &Path) -> Settings`; `settings::save(path: &Path, s: &Settings) -> std::io::Result<()>`; `Settings::server_origin(&self) -> Option<String>`; `Settings::panel_url(&self) -> Option<url::Url>`.
- Produces: `main.rs` `fn allowed_navigation(url: &url::Url, origin: &str) -> bool`; managed `AppState { settings: Mutex<Settings>, settings_path: PathBuf }` (later tasks add fields).

- [ ] **Step 1: Install Rust (ask Fred first).** Run `winget install --id Rustlang.Rustup -e`, then `rustup default stable-msvc`. Verify with `cargo --version`.
- [ ] **Step 2: Scaffold.**
  - `.gitignore` additions: `desktop/node_modules/`, `desktop/src-tauri/target/`, `desktop/src-tauri/gen/`, `!desktop/package.json`, `!desktop/package-lock.json`, `!desktop/src-tauri/tauri.conf.json`, `!desktop/src-tauri/capabilities/*.json`.
  - `package.json`: devDependency `@tauri-apps/cli@^2.12`, with the three scripts from the file map. Run `npm install` in `desktop/`.
  - `Cargo.toml`: crate `cloutkings-desktop`; deps `tauri = { version = "2.12", features = ["tray-icon", "image-png"] }`, the plugin crates from the Tech Stack line, `serde` (derive), `serde_json`, `url = "2"`, `log`; dev-dep `tempfile`; build-dep `tauri-build = "2"`. `souvlaki` and `windows` are added in their own tasks.
  - `build.rs`: `tauri_build::try_build(Attributes::new().app_manifest(AppManifest::new().commands(&[…])))`. The command list starts empty and each later task appends its commands. Do not ship a `default` capability that targets `main`.
  - `tauri.conf.json`:
    - `productName`, `identifier` and `version` from Global Constraints
    - `build.frontendDist: "../ui"`
    - `app.withGlobalTauri: true`
    - `app.windows: []` (windows are created in code)
    - `app.security.csp: "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; connect-src ipc: http://ipc.localhost"`
    - `bundle.targets: ["nsis"]`, `bundle.windows.nsis.installMode: "currentUser"`
  - `app-icon.svg`: a 1024 × 1024 rounded square filled `#14100d`, with the Revival 12-ray sunburst polygon from `index.html` (`.logo-icon::before` mask) scaled to about 70% and filled with a linear gradient `#d4694a → #f2b35e`. Generate the icons with `npx tauri icon src-tauri/icons/app-icon.svg`.
- [ ] **Step 3: Write failing tests** in `settings.rs` and `main.rs`:

```rust
#[test] fn missing_file_gives_defaults() {
    let d = tempfile::tempdir().unwrap();
    let s = load(&d.path().join("settings.json"));
    assert_eq!(s, Settings::default());
    assert_eq!(s.server_url, "https://cloutkings.duckdns.org");
    assert!(s.notify_now_playing && s.notify_up_next && s.pause_spotify_after_send && !s.start_with_windows);
    assert_eq!((s.mini_player.x, s.mini_player.y), (None, None));
}
#[test] fn partial_file_fills_defaults() {
    // file content: {"notifyUpNext": false}
    // → notify_up_next == false, every other field == Settings::default()
}
#[test] fn corrupt_file_gives_defaults_and_is_rewritten() {
    // file content: "{not json" → load == default; afterwards serde_json::from_str::<Settings>(file) is Ok
}
#[test] fn save_then_load_round_trips() { /* mini_player x=100,y=200, notify_now_playing=false */ }
#[test] fn origin_and_panel_url() {
    let mut s = Settings::default();
    assert_eq!(s.server_origin().as_deref(), Some("https://cloutkings.duckdns.org"));
    assert_eq!(s.panel_url().unwrap().as_str(), "https://cloutkings.duckdns.org/web/index.html");
    s.server_url = "http://10.0.0.43:8888/web/".into();
    assert_eq!(s.server_origin().as_deref(), Some("http://10.0.0.43:8888"));
    s.server_url = "not a url".into();
    assert_eq!(s.server_origin(), None);
}
// main.rs
#[test] fn navigation_allowlist() {
    let o = "https://cloutkings.duckdns.org";
    let u = |s: &str| url::Url::parse(s).unwrap();
    assert!(allowed_navigation(&u("https://cloutkings.duckdns.org/api/auth/discord"), o));
    assert!(allowed_navigation(&u("https://discord.com/oauth2/authorize?client_id=1"), o));
    assert!(!allowed_navigation(&u("https://www.youtube.com/watch?v=x"), o));
    assert!(!allowed_navigation(&u("https://cloutkings.duckdns.org.evil.com/"), o));
    assert!(!allowed_navigation(&u("http://cloutkings.duckdns.org/"), o));
    assert!(!allowed_navigation(&u("https://discord.com.evil.io/"), o));
}
```

- [ ] **Step 4: Run to verify failure:** `cargo test --manifest-path desktop/src-tauri/Cargo.toml`. Expected: compile errors or failures for the functions above.
- [ ] **Step 5: Implement** `settings.rs` and `main.rs`:
  - Plugins in this order: single-instance first (its callback shows, unminimizes and focuses `main`), then log (LogDir target, 1 MB files, keep 5), then opener.
  - Settings path: `app.path().app_config_dir()/settings.json`.
  - Create `main` with `WebviewWindowBuilder::new(app, "main", WebviewUrl::External(panel_url))`, title from productName, size 1280 × 860.
  - `on_navigation`: if `!allowed_navigation`, open the URL with the opener plugin and return `false`.
  - `CloseRequested` on `main` → `prevent_close()` + `hide()`.
  - Minimal tray: the app icon plus a menu with `show-panel` and `quit`. `quit` calls `app.exit(0)`. Task 6 replaces it.
- [ ] **Step 6: Run tests:** `cargo test …` → PASS.
- [ ] **Step 7: Run the app:** `npm run dev` in `desktop/`. **Fred checks:**
  - the panel loads
  - Discord login completes
  - after quitting and relaunching, he's still logged in
  - closing the window keeps the tray icon
  - Show panel brings the window back
  - launching a second copy focuses the first
  - clicking a YouTube link in the panel opens the default browser
- [ ] **Step 8: Commit:** `git commit -m "desktop: scaffold Tauri app with panel window, settings, navigation guard"`.

---

### Task 3: Bridge core (pure JS)

**Files:**
- Create: `desktop/bridge/core.js`, `desktop/bridge/core.test.js`

**Interfaces:**
- Produces:
  - `toBotState(snapshot) -> BotState`. `snapshot` = `{connected, signedIn, me, guild, nowPlaying, queue}`, where `nowPlaying` and `queue` are the panel's `/nowplaying` and `/queue` payloads. Output shape is spec §2 `BotState`.
  - `parseWsMessage(text, guildId) -> {type, data} | {type:'error', code, message} | null`.
  - `commandToRequest(guildId, command, state) -> {method:'POST', path, body} | null`, where `command` is `{type, value?}` from Rust's `BotCommand` serialization (Task 5).
  - Exported via `module.exports` under Node, or `window.__cksCore` in the page.

- [ ] **Step 1: Write the failing tests** (`node:test` + `node:assert/strict`):

```js
test('maps now playing, queue and me into BotState', () => {
  const s = toBotState({ connected: true, signedIn: true, me: { id: '1', name: 'fred', avatarUrl: '' }, guild: { id: '9', name: 'CK' },
    nowPlaying: { playing: true, paused: false, title: 'T', author: 'A', artworkUrl: 'u', uri: 'yt', duration: 214000, position: 85000, isStream: false, requestedBy: 'fred', requestedById: '1', volume: 80 },
    queue: { tracks: [{ title: 'Next', requestedById: '2' }, { title: 'Later', requestedById: '1' }] } });
  assert.deepEqual(s.track, { title: 'T', author: 'A', artworkUrl: 'u', uri: 'yt', durationMs: 214000, positionMs: 85000, isStream: false, requestedBy: 'fred', requestedById: '1' });
  assert.equal(s.paused, false); assert.equal(s.volume, 80);
  assert.deepEqual(s.queueHead, { title: 'Next', requestedById: '2' });
});
test('nothing playing gives null track and empty queue gives null head', () => { /* playing:false, volume:100, tracks:[] → track null, queueHead null, volume 100 */ });
test('missing requestedById and empty requestedBy become null', () => { /* nowPlaying without requestedById and with requestedBy: "" → track.requestedById === null && track.requestedBy === null */ });
test('signed_out_state_carries_no_track', () => {
  const s = toBotState({ connected: true, signedIn: false, me: null, guild: null, nowPlaying: { playing: true, title: 'T' }, queue: { tracks: [{ title: 'x' }] } });
  assert.equal(s.signedIn, false); assert.equal(s.track, null); assert.equal(s.queueHead, null); assert.equal(s.me, null);
});
test('parse_ws_message_keeps_only_watched_guild', () => {
  assert.deepEqual(parseWsMessage('{"type":"player","guildId":"9","data":{"playing":false}}', '9'), { type: 'player', data: { playing: false } });
  assert.equal(parseWsMessage('{"type":"player","guildId":"8","data":{}}', '9'), null);
  assert.deepEqual(parseWsMessage('{"type":"error","guildId":"9","code":"NO_MATCHES","message":"No results"}', '9'), { type: 'error', code: 'NO_MATCHES', message: 'No results' });
  assert.equal(parseWsMessage('pong', '9'), null);
  assert.equal(parseWsMessage('{bad', '9'), null);
});
test('commands map to panel endpoints', () => {
  const g = '9', st = { paused: false };
  assert.deepEqual(commandToRequest(g, { type: 'TogglePause' }, st), { method: 'POST', path: '/api/guild/9/pause', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'Skip' }, st), { method: 'POST', path: '/api/guild/9/skip', body: null });
  assert.deepEqual(commandToRequest(g, { type: 'RestartTrack' }, st), { method: 'POST', path: '/api/guild/9/seek', body: { position: 0 } });
  assert.deepEqual(commandToRequest(g, { type: 'SetVolume', value: 200 }, st).body, { volume: 150 });
  assert.deepEqual(commandToRequest(g, { type: 'SetVolume', value: -5 }, st).body, { volume: 0 });
  assert.deepEqual(commandToRequest(g, { type: 'PlayQuery', value: 'A - T' }, st), { method: 'POST', path: '/api/guild/9/play', body: { query: 'A - T' } });
  assert.deepEqual(commandToRequest(g, { type: 'PlaySpotifyLink', value: 'https://open.spotify.com/playlist/x' }, st).body, { query: 'https://open.spotify.com/playlist/x' });
  assert.equal(commandToRequest(g, { type: 'Pause' }, { paused: true }), null);
  assert.equal(commandToRequest(g, { type: 'Pause' }, st).path, '/api/guild/9/pause');
  assert.equal(commandToRequest('', { type: 'Skip' }, st), null);
  assert.equal(commandToRequest(g, { type: 'Bogus' }, st), null);
});
test('command_paths_encode_guild_id', () => {
  assert.equal(commandToRequest('9/../x', { type: 'Skip' }, { paused: false }).path, '/api/guild/9%2F..%2Fx/skip');
});
```

- [ ] **Step 2: Run to verify failure:** `node --test desktop/bridge/`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** `core.js` with exactly the three exported functions. No DOM or network access.
- [ ] **Step 4: Run:** `node --test desktop/bridge/` → PASS.
- [ ] **Step 5: Commit:** `git commit -m "desktop: bridge core mapping with tests"`.

---

### Task 4: StateStore

**Files:**
- Create: `desktop/src-tauri/src/state.rs`
- Modify: `desktop/src-tauri/src/main.rs` (`AppState.store: Mutex<StateStore>`, `fn dispatch(app: &AppHandle, changes: Vec<Change>)`, a 5 s ticker thread calling `check_stale`)

**Interfaces:**
- Produces (serde camelCase, `Clone`, `Debug`, `PartialEq`):
  - `BotState { connected: bool, signed_in: bool, me: Option<Me>, guild: Option<Guild>, track: Option<Track>, paused: bool, volume: u8, queue_head: Option<QueueHead> }`
  - `Me { id, name, avatar_url: Option<String> }`
  - `Guild { id, name }`
  - `Track { title, author, artwork_url: Option<String>, uri, duration_ms: u64, position_ms: u64, is_stream: bool, requested_by: Option<String>, requested_by_id: Option<String> }`
  - `QueueHead { title, requested_by_id: Option<String> }`
- Produces: `enum Change { TrackChanged { track: Track, initial: bool }, PlaybackChanged { paused: bool }, Cleared, QueueHeadChanged(Option<QueueHead>), ConnectionChanged { connected: bool, signed_in: bool } }`; `pub const STALE_AFTER: Duration = Duration::from_secs(180)`.
- Produces: `StateStore::new()`, `apply(&mut self, next: BotState, now: Instant) -> Vec<Change>`, `check_stale(&mut self, now: Instant) -> Vec<Change>`, `position_ms(&self, now: Instant) -> Option<u64>`, `current(&self) -> Option<&BotState>`, `is_stale(&self) -> bool`; `fn is_my_song_next(head: Option<&QueueHead>, me: Option<&Me>) -> bool`.
- Track identity = `(uri, title)`. "Initial" means the first `TrackChanged` after construction, after a disconnect, or after being stale.

- [ ] **Step 1: Write failing tests** (helper `fn st(title: &str, paused: bool, pos: u64) -> BotState` builds a connected, signed-in state with `duration_ms: 214_000`):
  - `first_state_is_initial_track_change`: first `apply` contains `TrackChanged { initial: true, .. }`.
  - `new_track_is_not_initial`: `apply(st("A"))` then `apply(st("B"))` → `TrackChanged { initial: false }` with title "B".
  - `same_track_progress_is_no_change`: `st("A", false, 1000)` then `st("A", false, 9000)` → empty.
  - `pause_and_resume_emit_playback_changed`: paused true → `PlaybackChanged { paused: true }`; then false → `{ paused: false }`.
  - `track_to_none_emits_cleared`.
  - `sign_out_clears_track_and_emits_connection_change`: then a state with `signed_in: false, track: None` → contains `ConnectionChanged { connected: true, signed_in: false }` and `Cleared`.
  - `reconnect_marks_next_track_initial`: `apply(A)`, `apply(connected: false)`, then `apply(B)` → `TrackChanged { initial: true }`.
  - `queue_head_change_emitted_once_per_head`: same head twice → one `QueueHeadChanged`.
  - `stale_after_180s_marks_disconnected`: `check_stale(t0 + 179 s)` → `[]`; `check_stale(t0 + 181 s)` → `[ConnectionChanged { connected: false, .. }]`; checking again → `[]`.
  - `position_interpolates_and_clamps`: playing at 85 000 → `position_ms(t0 + 10 s) == Some(95_000)`, `position_ms(t0 + 1000 s) == Some(214_000)`; paused → stays `85_000`.
  - `streams_and_zero_duration_do_not_interpolate`: `is_stream: true` or `duration_ms: 0` → returns the reported position and never panics.
  - `is_my_song_next_rules`: same id → true; `me` None → false; head id None → false; different ids → false.
- [ ] **Step 2: Run to verify failure:** `cargo test … state::` → FAIL.
- [ ] **Step 3: Implement** `state.rs`. In `main.rs`, add `dispatch` (logs changes for now) and the ticker. Task 5 wires `bridge_report(State)` into `apply`.
- [ ] **Step 4: Run:** `cargo test …` → PASS.
- [ ] **Step 5: Commit:** `git commit -m "desktop: StateStore with change detection and staleness"`.

---

### Task 5: Bridge glue, commands, and the panel capability

**Files:**
- Create: `desktop/bridge/bridge.js`, `desktop/src-tauri/src/bridge.rs`
- Modify: `desktop/src-tauri/build.rs` (commands: `bridge_report`), `desktop/src-tauri/src/main.rs`

**Interfaces:**
- Consumes: Task 3 `window.__cksCore`; Task 2 `AppState`, `Settings::server_origin`; Task 4 `state::BotState`, `StateStore::apply`, `dispatch`.
- Produces:
  - `bridge::BotCommand` enum (`#[serde(tag = "type", content = "value")]`, `PartialEq`, `Debug`, `Clone`): `TogglePause, Pause, Skip, RestartTrack, SetVolume(u8), PlayQuery(String), PlaySpotifyLink(String)`.
  - `bridge::BridgeReport` (`#[serde(tag = "type", rename_all = "camelCase")]`): `State { state: state::BotState }`, `Notice { level: String, message: String }`, `Result { id: u64, ok: bool, message: Option<String> }`.
  - `#[tauri::command] fn bridge_report(app: AppHandle, report: BridgeReport)`.
  - `bridge::init_script(origin: &str) -> String`, `bridge::command_js(id: u64, cmd: &BotCommand) -> String`, `bridge::send_command(app: &AppHandle, cmd: BotCommand) -> Result<u64, String>`, `bridge::send_command_await(app: &AppHandle, cmd: BotCommand, timeout: Duration) -> Result<(), String>`.
  - Page API: `window.__cksBridge.run(id, cmd)`.

- [ ] **Step 1: Write failing tests** in `bridge.rs`:

```rust
#[test] fn init_script_embeds_origin_and_both_scripts() {
    let s = init_script("https://cloutkings.duckdns.org");
    assert!(s.contains("\"https://cloutkings.duckdns.org\""));
    assert!(s.contains("__cksCore") && s.contains("__cksBridge"));
}
#[test] fn command_js_round_trips_hostile_text() {
    let cmd = BotCommand::PlayQuery("Ke$ha \"Tik\" \\ Tok\n</script>\u{2028}🎵".into());
    let js = command_js(7, &cmd);
    let prefix = "window.__cksBridge && window.__cksBridge.run(7, ";
    assert!(js.starts_with(prefix) && js.ends_with(");"));
    let back: BotCommand = serde_json::from_str(&js[prefix.len()..js.len() - 2]).unwrap();
    assert_eq!(back, cmd);
}
#[test] fn bot_command_json_shape() {
    assert_eq!(serde_json::to_string(&BotCommand::Skip).unwrap(), r#"{"type":"Skip"}"#);
    assert_eq!(serde_json::to_string(&BotCommand::SetVolume(80)).unwrap(), r#"{"type":"SetVolume","value":80}"#);
}
#[test] fn bridge_report_deserializes_notice_and_result() {
    let n: BridgeReport = serde_json::from_str(r#"{"type":"notice","level":"error","message":"No results"}"#).unwrap();
    assert!(matches!(n, BridgeReport::Notice { ref message, .. } if message == "No results"));
    let r: BridgeReport = serde_json::from_str(r#"{"type":"result","id":3,"ok":false,"message":"No voice channel available"}"#).unwrap();
    assert!(matches!(r, BridgeReport::Result { id: 3, ok: false, .. }));
}
```

- [ ] **Step 2: Run to verify failure:** `cargo test …` → FAIL.
- [ ] **Step 3: Implement `bridge.rs`.**
  - `init_script` = `window.__CKS_ORIGIN = <json>;` + `include_str!("../../bridge/core.js")` + `include_str!("../../bridge/bridge.js")`, wrapped so nothing runs unless `location.origin === window.__CKS_ORIGIN`.
  - `command_js` uses `serde_json::to_string`. Never use string concatenation for command data.
  - `send_command` assigns an id from an `AtomicU64` and evals into the `main` webview.
  - `send_command_await` registers a `std::sync::mpsc` sender in a `Mutex<HashMap<u64, Sender<Result<(), String>>>>` on `AppState`. On timeout it returns `Err("The panel isn't loaded yet")`.
  - `bridge_report`:
    - `State` → `StateStore::apply` + `dispatch` (Task 4)
    - `Notice` → `dispatch_notice`, logged now and shown in Tasks 7 and 10
    - `Result` → completes the pending sender
- [ ] **Step 4: Implement `bridge.js`.**
  - Watched guild = `localStorage.defaultGuild`, updated on `#guildSelect` `change` and `storage` events.
  - On load and on each (re)connect, call `GET /api/me`: 401 → `signedIn:false`; 404 → `me:null`.
  - Initial `/nowplaying` + `/queue` fetch, then its own `WebSocket` (`wss://host/ws?guild=ID`), reconnecting with backoff 1 s → 30 s.
  - Report via `window.__TAURI__.core.invoke('bridge_report', {report})` on every relevant message, on open and close, and on a 30 s heartbeat.
  - `run(id, cmd)`: `commandToRequest` → `fetch` with `credentials: 'same-origin'`, then report `Result`. Use the server's `error`/`message` JSON field as the message on failure.
  - WS `error` messages → `Notice { level: "error" }`.
- [ ] **Step 5: Add the panel capability at runtime in `main.rs`:** `CapabilityBuilder::new("panel").window("main").remote(format!("{origin}/*")).permission("allow-bridge-report")` via `app.add_capability`. Also register `.initialization_script(bridge::init_script(&origin))` on the `main` builder from Task 2.
- [ ] **Step 6: Run tests:** `cargo test …` and `node --test desktop/bridge/` → PASS.
- [ ] **Step 7: Verify scoping (risk §10).** Run `npm run dev`, then use the main window's devtools console:
  - `await window.__TAURI__.core.invoke('bridge_report', {report:{type:'notice',level:'info',message:'hi'}})` → resolves, and the app log shows the notice.
  - `await window.__TAURI__.core.invoke('plugin:clipboard-manager|read_text')` → rejects as not allowed.
  - The app log shows a `State` report with the current track within a few seconds of playing something in the panel.
- [ ] **Step 8: Commit:** `git commit -m "desktop: bridge glue, command channel, scoped panel capability"`.

---

### Task 6: Tray

**Files:**
- Create: `desktop/src-tauri/src/tray.rs`
- Modify: `desktop/src-tauri/src/main.rs` (replace the minimal tray; `dispatch` calls `tray::refresh`)

**Interfaces:**
- Consumes: `StateStore`, `bridge::send_command`, `BotCommand`.
- Produces: menu id constants `NOW_PLAYING, TOGGLE_PAUSE, SKIP, SEND_SPOTIFY, PASTE_SPOTIFY, SHOW_PANEL, MINI_PLAYER, NOTIFY_NOW_PLAYING, NOTIFY_UP_NEXT, PAUSE_SPOTIFY_AFTER_SEND, START_WITH_WINDOWS, CHECK_UPDATES, QUIT` (string values: kebab-case of the name); `tray::build(app: &AppHandle) -> tauri::Result<()>`; `tray::refresh(app: &AppHandle)`; `fn tooltip_text(state: Option<&BotState>, stale: bool) -> String`.
- This task builds: now-playing label (disabled), Play/Pause, Skip, separator, Show panel, separator, Quit. Tasks 7, 9, 10 and 11 insert their items at the positions in spec §3.

- [ ] **Step 1: Write failing tests:**
  - `tooltip_shows_title_and_artist`: → `"Small Worlds — Mac Miller"`.
  - `tooltip_nothing_playing`: state with `track: None` → `"Nothing playing"`.
  - `tooltip_signed_out`: `signed_in: false` → `"Not signed in"`.
  - `tooltip_unreachable`: `stale: true`, or `None` / `connected: false` → `"Can't reach the bot"`.
  - `tooltip_truncates_on_char_boundary`: title `"🎵".repeat(100)` + artist → `result.encode_utf16().count() <= 127` and the result ends with `…`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `tray.rs`:
  - The left-click handler is a no-op until Task 7.
  - The Play/Pause label switches between "Pause" and "Play".
  - Menu actions call `send_command`. Show panel shows and focuses `main`. Quit exits.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Fred checks** (`npm run dev`, with a song playing in the panel): the tooltip shows the song; Play/Pause and Skip work from the tray; the tooltip says "Can't reach the bot" about 3 minutes after the PC loses network.
- [ ] **Step 6: Commit:** `git commit -m "desktop: tray with now playing, play/pause, skip"`.

---

### Task 7: Mini-player

**Files:**
- Create: `desktop/src-tauri/src/mini_player.rs`, `desktop/ui/mini-player/{index.html,mini.css,mini.js}`, `desktop/src-tauri/capabilities/mini-player.json`
- Modify: `build.rs` (commands `mini_get_state, mini_command, mini_open_panel, mini_hide`), `main.rs`, `tray.rs` (left-click toggles; `MINI_PLAYER` item)

**Interfaces:**
- Produces:
  - `UiState { state: Option<BotState>, position_ms: Option<u64>, position_at_ms: u64 /* unix epoch ms */, stale: bool }` (serde camelCase)
  - events `"state-changed"` (UiState) and `"notice"` (`{ level, message }`)
  - commands `mini_get_state() -> UiState`, `mini_command(cmd: BotCommand) -> Result<(), String>` (uses `send_command_await`, 10 s timeout), `mini_open_panel()`, `mini_hide()`
  - `mini_player::toggle(app)`, `mini_player::is_visible(app) -> bool`
  - `fn default_position(work_area: Rect, size: (u32, u32)) -> (i32, i32)`, `fn resolve_position(saved: (Option<i32>, Option<i32>), monitors: &[Rect], size: (u32, u32)) -> (i32, i32)`, where `Rect { x: i32, y: i32, w: u32, h: u32 }`
- Capability `mini-player.json`: windows `["mini-player"]`; permissions `core:event:default`, `core:window:allow-start-dragging`, `allow-mini-get-state`, `allow-mini-command`, `allow-mini-open-panel`, `allow-mini-hide` (Tasks 9 and 10 append theirs).

- [ ] **Step 1: Write failing tests:**
  - `default_position_is_bottom_right_with_margin`: work area `{0,0,1920,1040}`, size `(360,140)` → `(1920-360-12, 1040-140-12)`.
  - `saved_position_on_screen_is_kept`: `(Some(100), Some(100))` inside a monitor → `(100,100)`.
  - `saved_position_off_screen_falls_back_to_default`: `(Some(3000), Some(100))` with only `{0,0,1920,1080}` → the default for the first monitor.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement `mini_player.rs`.**
  - Window: label `mini-player`, URL `mini-player/index.html`, 360 × 140, `decorations(false)`, `always_on_top(true)`, `skip_taskbar(true)`, `resizable(false)`, `visible(false)`, `drag_and_drop(false)`.
  - Save the position on `Moved`, at most once a second.
  - `dispatch` emits `state-changed`. Notices go to the mini-player when it's visible; otherwise they're logged (Task 10 adds toasts).
- [ ] **Step 4: Implement the UI.**
  - Layout: artwork (64 px, rounded), title (Fraunces), "Artist · requested by Name", a progress bar interpolated locally from `positionMs` + `positionAtMs` while not paused, ⏮ ⏯ ⏭, a volume slider 0–150 (sends `SetVolume` on `change`), and a close button (`mini_hide`).
  - The title area is draggable (`data-tauri-drag-region`).
  - States: Nothing playing; Not signed in, with an "Open panel" button; Reconnecting… (when `stale` or `!connected`); an error line from `notice` or a rejected `mini_command`, cleared after 8 s.
  - Leave an empty Spotify row container for Task 9.
- [ ] **Step 5: Run tests** → PASS.
- [ ] **Step 6: Fred checks:**
  - tray left-click opens and closes the mini-player
  - it updates live and its buttons and volume work
  - drag it, quit, relaunch → same spot
  - log out in the panel → "Not signed in"
  - stop the bot's network or point `serverUrl` at a dead host → "Reconnecting…" (within 3 min)
- [ ] **Step 7: Commit:** `git commit -m "desktop: always-on-top mini-player"`.

---

### Task 8: Media keys and Windows media flyout

**Files:**
- Create: `desktop/src-tauri/src/media_keys.rs`
- Modify: `Cargo.toml` (`souvlaki = "0.8.3"`), `main.rs` (`AppState.media: Mutex<Option<MediaKeys>>`, created after `main` exists, using `main.hwnd()`; `dispatch` calls `update`)

**Interfaces:**
- Produces: `fn key_to_command(event: &souvlaki::MediaControlEvent, paused: bool) -> Option<BotCommand>`; `MediaKeys::new(hwnd, app: AppHandle) -> Result<Self, String>`; `MediaKeys::update(&mut self, state: Option<&BotState>, position_ms: Option<u64>)`.

- [ ] **Step 1: Write failing tests** for `key_to_command`:
  - `Toggle` → `TogglePause`
  - `Play` → `TogglePause` when paused, else `None`
  - `Pause` → `Pause`
  - `Next` → `Skip`
  - `Previous` → `RestartTrack`
  - `Stop` → `Pause`
  - `Raise` / `Quit` / `OpenUri(_)` → `None`
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
  - `PlatformConfig { display_name: "Clout Kings Music Bot", dbus_name: "cloutkings_musicbot", hwnd: Some(hwnd) }`.
  - `update` with a track: attach if detached; `set_metadata` (title, artist, album `"Clout Kings Music Bot"`, `cover_url`, duration); `set_playback` Playing/Paused with progress.
  - With no track, or signed out: `set_playback(Stopped)` then `detach()`.
  - Event handler: `key_to_command`, then `send_command` (fire-and-forget).
  - An init failure is logged and the app continues without media keys.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Fred checks:**
  - the Windows volume/media flyout shows the bot's song and artwork
  - the keyboard's ⏯ ⏭ ⏮ keys work, including while a game or another window is focused
  - with the bot idle and Spotify playing, media keys control Spotify
- [ ] **Step 6: Commit:** `git commit -m "desktop: media keys and Windows media flyout"`.

---

### Task 9: Spotify — send current song, play links

**Files:**
- Create: `desktop/src-tauri/src/spotify.rs`
- Modify:
  - `Cargo.toml`: `windows = { version = "0.62", features = ["Media_Control", "Foundation", "Foundation_Collections"] }`
  - `build.rs`: commands `mini_spotify_now, mini_send_spotify, mini_play_spotify_text, mini_play_spotify_clipboard`
  - `mini-player.json`: matching `allow-*` entries
  - `mini_player.rs` + UI: the Spotify row and the drop target
  - `tray.rs`: `SEND_SPOTIFY`, `PASTE_SPOTIFY`
  - `main.rs`: the 5 s ticker also polls Spotify and emits `"spotify-changed"`

**Interfaces:**
- Produces:
  - `fn parse_spotify_link(text: &str) -> Option<String>`: scans whitespace-separated tokens and returns the first as canonical `https://open.spotify.com/{playlist|album|track|artist}/{id}`, where id matches `[A-Za-z0-9]+`.
  - `struct MediaSessionInfo { app_id: String, title: String, artist: String, playing: bool }`
  - `fn pick_spotify_session<'a>(sessions: &'a [MediaSessionInfo], own_app_id: &str) -> Option<&'a MediaSessionInfo>`: app id contains `spotify` case-insensitively and isn't `own_app_id`; prefers a playing session.
  - `struct SpotifyNow { title, artist, playing }` (serde camelCase)
  - `fn query_for(now: &SpotifyNow) -> String` → `"{artist} - {title}"`
  - `fn current() -> Option<SpotifyNow>` and `fn pause_spotify() -> Result<(), String>` (Windows; run off the main thread)
- Produces (commands): `mini_spotify_now() -> Option<SpotifyNow>`, `mini_send_spotify() -> Result<(), String>`, `mini_play_spotify_text(text: String) -> Result<(), String>`, `mini_play_spotify_clipboard() -> Result<(), String>`.

- [ ] **Step 1: Write failing tests:**

```rust
#[test] fn parses_accepted_link_forms() {
    assert_eq!(parse_spotify_link("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc").as_deref(), Some("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"));
    assert_eq!(parse_spotify_link("https://open.spotify.com/intl-de/album/1ATL5GLyefJaxhQzSPVrLX").as_deref(), Some("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX"));
    assert_eq!(parse_spotify_link("spotify:track:4uLU6hMCjMI75M1A2tKUQC").as_deref(), Some("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"));
    assert_eq!(parse_spotify_link("  https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF  ").as_deref(), Some("https://open.spotify.com/artist/0OdUWJ0sBjDrqHygGUXeCF"));
    assert_eq!(parse_spotify_link("https://open.spotify.com/track/AAA\nhttps://open.spotify.com/track/BBB").as_deref(), Some("https://open.spotify.com/track/AAA"));
}
#[test] fn rejects_non_spotify_and_unsupported() {
    for t in ["hello", "https://example.com/playlist/x", "https://open.spotify.com/episode/abc", "https://open.spotify.com/playlist/", "https://open.spotify.com/track/ab$cd", "spotify:user:x", "https://open.spotify.com.evil.io/track/abc"] {
        assert_eq!(parse_spotify_link(t), None, "{t}");
    }
}
#[test] fn picks_spotify_session() {
    let s = |app: &str, playing| MediaSessionInfo { app_id: app.into(), title: "T".into(), artist: "A".into(), playing };
    let list = [s("chrome", true), s("Spotify.exe", false), s("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", true)];
    assert_eq!(pick_spotify_session(&list, "com.fredtheslug.musicbot.desktop").unwrap().app_id, "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify");
    assert_eq!(pick_spotify_session(&list[..2], "x").unwrap().app_id, "Spotify.exe");
    assert!(pick_spotify_session(&list[..1], "x").is_none());
    assert!(pick_spotify_session(&[s("Spotify.exe", true)], "Spotify.exe").is_none());
}
#[test] fn query_is_artist_dash_title() {
    assert_eq!(query_for(&SpotifyNow { title: "Small Worlds".into(), artist: "Mac Miller".into(), playing: true }), "Mac Miller - Small Worlds");
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
  - `current()` uses `GlobalSystemMediaTransportControlsSessionManager::RequestAsync()?.get()`, then `GetSessions`, `SourceAppUserModelId`, `TryGetMediaPropertiesAsync().get()` and `GetPlaybackInfo().PlaybackStatus()`.
  - `mini_send_spotify`:
    1. `current()`; if it's missing or not playing → `Err("Spotify isn't playing anything")`.
    2. `send_command_await(PlayQuery(query_for(..)))`.
    3. Only on `Ok`, and when `pause_spotify_after_send` is set → `pause_spotify()`.
  - `mini_play_spotify_text` / `_clipboard` (clipboard-manager `read_text`): `parse_spotify_link`, else `Err("That's not a Spotify link")`, then `send_command_await(PlaySpotifyLink(link))`.
  - UI: the Spotify row label is "▶ Send *Title – Artist*", disabled unless `playing`. A "Paste Spotify link" button. `dragover`/`drop` on the whole window reads `text/uri-list`, then `text/plain`, and calls `mini_play_spotify_text`.
  - Tray items call the same functions; failures are shown as a notice.
  - If the media-session API errors, the Spotify send items are hidden.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Fred checks** (Spotify desktop installed and playing):
  - the label shows the current song
  - Send queues it on the bot and pauses Spotify
  - toggling "Pause Spotify after sending" off leaves Spotify playing
  - Share → Copy link on a playlist, then "Paste Spotify link" queues up to 100 tracks
  - **drag a playlist from the Spotify app onto the mini-player.** Record what arrives in the app log. If it isn't a link, note it in the PR, and the clipboard path stays the supported one (spec §10).
- [ ] **Step 6: Commit:** `git commit -m "desktop: send Spotify song and play Spotify links"`.

---

### Task 10: Notifications, toggles, start with Windows

**Files:**
- Create: `desktop/src-tauri/src/notify.rs`
- Modify: `tray.rs` (check items `NOTIFY_NOW_PLAYING`, `NOTIFY_UP_NEXT`, `PAUSE_SPOTIFY_AFTER_SEND`, `START_WITH_WINDOWS`, bound to settings and saved on toggle), `main.rs` (`dispatch` → `notify::toasts_for` → notification plugin; notices toast when the mini-player is hidden; autostart plugin with args `["--hidden"]`; `fn start_hidden(args: &[String]) -> bool`)

**Interfaces:**
- Produces: `struct Toast { title: String, body: String }` (`PartialEq`, `Debug`); `fn toasts_for(changes: &[Change], state: Option<&BotState>, settings: &Settings, main_focused: bool, mini_visible: bool) -> Vec<Toast>`.

- [ ] **Step 1: Write failing tests:**
  - `track_change_toasts_title_and_artist_with_requester`: `TrackChanged { initial: false }` with "Small Worlds" / "Mac Miller" / requested_by "gunner" → `[Toast { title: "Small Worlds", body: "Mac Miller · requested by gunner" }]`.
  - `requester_missing_body_is_artist_only` → body `"Mac Miller"`.
  - `initial_track_change_does_not_toast` → `[]`.
  - `focus_or_mini_visible_suppresses_now_playing`: `main_focused` → `[]`; `mini_visible` → `[]`.
  - `now_playing_toggle_off` → `[]`.
  - `my_song_up_next_toasts`: `QueueHeadChanged(Some(head with my id))` with `me` set → `[Toast { title: "Your song is up next", body: head.title }]`, even when `main_focused`.
  - `up_next_not_mine_or_toggle_off_or_no_me` → `[]` in each case.
  - `start_hidden_flag`: `["app.exe", "--hidden"]` → true; `["app.exe"]` → false.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** On launch with `--hidden`, `main` is created but not shown. The `START_WITH_WINDOWS` toggle calls `autolaunch().enable()` / `disable()` and saves the setting.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Fred checks:**
  - a toast appears on song change with the panel minimized, and none with the panel in front
  - queue something as yourself behind another song → "Your song is up next" when it reaches the front
  - each toggle sticks after relaunch
  - Start with Windows → sign out of Windows and back in → the app is in the tray, with no window
- [ ] **Step 6: Commit:** `git commit -m "desktop: notifications, settings toggles, start with Windows"`.

---

### Task 11: Release pipeline and auto-update

**Files:**
- Create: `.github/workflows/desktop-release.yml`
- Modify: `Cargo.toml` (`tauri-plugin-updater`, `tauri-plugin-dialog`), `tauri.conf.json` (`bundle.createUpdaterArtifacts: true`, `plugins.updater.pubkey`, `plugins.updater.endpoints: ["https://github.com/FredTheSlug/MusicBot/releases/download/desktop-updates/latest.json"]`), `tray.rs` (`CHECK_UPDATES`), `main.rs` (startup check)

**Interfaces:**
- Produces: `fn check_for_updates(app: &AppHandle, interactive: bool)`.
  - Startup calls it with `interactive = false`: errors are logged only.
  - The tray calls it with `true`: errors and "You're up to date" appear in a dialog.
  - Either way, an available update triggers a dialog: "Version X is available — install now?" → `download_and_install`, then `app.restart()`.

- [ ] **Step 1: Generate the updater key.** Run `npx tauri signer generate --ci -w %USERPROFILE%\.tauri\cloutkings-desktop.key` (no password). Put the public key in `tauri.conf.json`. **Fred** adds the secret himself: `gh secret set TAURI_SIGNING_PRIVATE_KEY < %USERPROFILE%\.tauri\cloutkings-desktop.key`, and sets `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` to empty.
- [ ] **Step 2: Implement** `check_for_updates` and the tray item.
- [ ] **Step 3: Write the workflow.**
  - Trigger: `push: tags: ['desktop-v*']`. `permissions: contents: write`. `runs-on: windows-latest`.
  - Steps:
    1. checkout
    2. `actions/setup-node@v4` (node 22, npm cache on `desktop/package-lock.json`)
    3. `dtolnay/rust-toolchain@stable`
    4. `swatinem/rust-cache@v2` (workspaces `desktop/src-tauri`)
    5. `npm ci` (in `desktop`)
    6. `node --test desktop/bridge/`
    7. `cargo test --manifest-path desktop/src-tauri/Cargo.toml`
    8. `gh release create $TAG --title "Desktop $TAG" --notes "Clout Kings Music Bot desktop app" --latest=false`, then read its numeric id with `gh api repos/${{ github.repository }}/releases/tags/$TAG --jq .id`
    9. `tauri-apps/tauri-action@v0` with `projectPath: desktop`, `releaseId` from step 8, `includeUpdaterJson: true`, and env `GITHUB_TOKEN`, `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`
    10. `gh release download $TAG -p latest.json`; `gh release view desktop-updates || gh release create desktop-updates --title "Desktop updates" --notes "Update manifest for the desktop app" --prerelease`; `gh release upload desktop-updates latest.json --clobber`
- [ ] **Step 4: First release (ask Fred first).** Set version `0.1.0`, commit, push the branch, then push tag `desktop-v0.1.0`. Expected: the workflow passes, release `desktop-v0.1.0` has the setup `.exe` and `.sig`, `desktop-updates` has `latest.json`, and the jar release `v0.4.4` is still "Latest" on the releases page.
- [ ] **Step 5: Update test (ask Fred first).** Fred installs `0.1.0` from the release (SmartScreen: More info → Run anyway). Bump to `0.1.1`, then tag `desktop-v0.1.1`. **Fred checks:** relaunching (or tray → Check for updates) offers 0.1.1; after installing, the app restarts at 0.1.1, still logged in, with settings kept.
- [ ] **Step 6: Commit:** `git commit -m "desktop: release workflow and auto-update"`.
