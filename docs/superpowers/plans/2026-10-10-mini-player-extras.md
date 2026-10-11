# Mini-player Extras Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the desktop mini-player a search/link menu, lyrics, a Spotify logo button, adjustable background transparency and a skin per panel theme (synced or chosen), and make Clout Kings the default server.

**Architecture:** New bot data (search results, lyrics, theme colors) travels only through the existing bridge in the panel page (page's own login), is validated and capped in Rust, and reaches the bundled mini-player as typed events/command results. The mini-player window grows upward (or downward near the top of the screen) while a panel is open. The bot gains one config option and one field on `/api/guilds`.

**Tech Stack:** Tauri 2.12 (Rust), plain JS/CSS in `desktop/ui/mini-player` and `desktop/bridge`, Java 11 / Javalin 6 for the bot.

**Spec:** `docs/superpowers/specs/2026-10-10-mini-player-extras-design.md` (extends `docs/superpowers/specs/2026-10-10-desktop-companion-design.md`)

## Global Constraints

- Windows only. No new Rust crates and no new npm packages.
- The remote panel page may call only `bridge_report`; Rust never reads or stores cookies or tokens; every bot request runs in the page with `credentials: 'same-origin'`.
- Text from the bot or Spotify is rendered with `textContent`, never `innerHTML`.
- Mini-player width 360, closed height 170, extra height while a panel is open 250 (logical px).
- Settings keys (camelCase): `miniPlayerOpacity` (0.15–1.0, default 1.0), `miniPlayerMatchPanel` (default true), `miniPlayerTheme` (default `"nebula"`), `panelTheme` (default null).
- Theme ids (17, in panel order) and labels: nebula Nebula, aurora Aurora, ember Ember, crimson Crimson, dawn Dawn, dawn-dark Dawn Dark, steel Steel, verdant Verdant, grove Grove, grapevine Grapevine, sumi Sumi, rasta Rasta, cipher Cipher, signal Signal, victory Victory, neon Neon, revival Revival.
- Theme variables (16): `--bg-primary --bg-secondary --bg-card --bg-elevated --bg-hover --accent --accent-light --accent-glow --accent-gradient --text-primary --text-secondary --text-tertiary --border --border-light --success --font-family`.
- Caps: 16 search results, 200 chars per result field, 500 lyric lines, 300 chars per line; timeouts: search 10 s, lyrics 15 s.
- Spotify green `#1ED760`.
- Test commands: `cargo test --manifest-path desktop/src-tauri/Cargo.toml` · `(cd desktop && npm run -s test:js)` · `mvn -o -q test`. Stop any running `cloutkings-desktop.exe` before building.
- No GitHub steps (push, tags, releases). Bot deploys only after asking Fred. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Hostile or odd theme values from the panel (`url(...)`, `;}`, 5 KB gradients, a theme missing some variables) → the mini-player keeps a sane look and loads nothing remote. Pinned in Task 4.
2. Mini-player near the top of a screen or on a monitor left of/above the primary (negative coordinates) when opening the menu or lyrics → it grows down, stays on that monitor, returns exactly to its spot when closed, and the saved position is the closed one. Pinned in Task 5.
3. Fast typing / slow searches → only the newest search's results are shown; a late reply never replaces newer results. Pinned in Task 7.
4. Lyrics open while the song is seeked, paused or changes → the highlighted line follows the position, a new song refetches, and the old song's lyrics never land on the new one. Pinned in Task 8.
5. Long or odd text in results and lyrics (emoji, 300+ chars, `<b>`-like strings) → shown as text, cut with an ellipsis, layout intact. Pinned in Task 3 (caps) and Task 7 (ellipsis CSS + textContent).

---

### Task 1: Clout Kings as the default server (bot)

**Files:**
- Modify: `src/main/java/com/jagrosh/jmusicbot/BotConfig.java` (next to `webHost`), `src/main/resources/reference.conf`, `config.txt.example`
- Modify: `src/main/java/com/jagrosh/jmusicbot/web/WebServer.java` (`handleGetGuilds`)
- Modify: `src/main/resources/web/index.html` (`loadGuilds`)
- Test: `src/test/java/com/jagrosh/jmusicbot/web/WebPayloadsTest.java`

**Interfaces:**
- Produces: config `webdefaultguild` (string, default `""`); `BotConfig.getWebDefaultGuild(): String`; `static boolean WebServer.isDefaultGuild(String guildId, String configured)`; `/api/guilds` items gain `"isDefault": boolean`.

- [ ] **Step 1: Write the failing test** in `WebPayloadsTest`:

```java
@Test public void defaultGuildMatchesOnlyTheConfiguredId() {
    assertTrue(WebServer.isDefaultGuild("123", "123"));
    assertFalse(WebServer.isDefaultGuild("456", "123"));
    assertFalse(WebServer.isDefaultGuild("123", ""));
    assertFalse(WebServer.isDefaultGuild("123", null));
    assertTrue(WebServer.isDefaultGuild("123", " 123 "));
}
```

- [ ] **Step 2:** `mvn -o -q test -Dtest=WebPayloadsTest` → FAIL (cannot find symbol `isDefaultGuild`).
- [ ] **Step 3: Implement** `isDefaultGuild` (trimmed equality, false for null/blank), the config option (`config.hasPath("webdefaultguild") ? config.getString("webdefaultguild").trim() : ""`), the `reference.conf` entry `webdefaultguild = ""` with a comment, the `config.txt.example` line `webdefaultguild = "" // server id the web panel selects when a user hasn't picked one`, and `g.put("isDefault", isDefaultGuild(guild.getId(), bot.getConfig().getWebDefaultGuild()))` in `handleGetGuilds`.
- [ ] **Step 4: Panel** — in `loadGuilds`, after reading `saved`: if `!saved && !currentGuild`, take the first guild with `isDefault === true`; set `currentGuild` to its id, `localStorage.setItem('defaultGuild', id)`, then fall through to the existing `guildSelect.value = currentGuild; ensureWebSocket();`.
- [ ] **Step 5:** `mvn -o -q test` → exit 0, 39 tests.
- [ ] **Step 6: Commit** `bot: webdefaultguild selects a server for users who haven't picked one`.

### Task 2: Bridge fetch commands (Search, Lyrics) and result data

**Files:**
- Modify: `desktop/bridge/core.js`, `desktop/bridge/bridge.js`, `desktop/bridge/core.test.js`
- Modify: `desktop/src-tauri/src/bridge.rs`, `desktop/src-tauri/src/main.rs` (`AppState.pending` type)

**Interfaces:**
- Produces (JS): `commandToRequest` handles `{type:'Search', value}` → `{method:'GET', path:'/api/guild/<id>/search?q=<encodeURIComponent(trimmed)>', body:null}` (null when blank) and `{type:'Lyrics'}` → `{method:'GET', path:'/api/guild/<id>/lyrics', body:null}`. `run()` reports `{type:'result', id, ok, message, data}`; for GET requests `data` is the parsed JSON body when the response is 2xx (ok:true even if the body has an `error` field), else ok:false with `body.error || 'HTTP <status>'`; `data` is `null` otherwise.
- Produces (Rust): `BotCommand::Search(String)`, `BotCommand::Lyrics`; `BridgeReport::Result { id, ok, message, #[serde(default)] data: Option<serde_json::Value> }`; `AppState.pending: Mutex<HashMap<u64, Sender<Result<Option<serde_json::Value>, String>>>>`; `bridge::send_command_fetch(app: &AppHandle, cmd: BotCommand, timeout: Duration) -> Result<serde_json::Value, String>` (Err `"The bot sent no data"` when ok without data); `send_command_await` keeps its signature.

- [ ] **Step 1: Write the failing tests.** JS (`core.test.js`):

```js
test('fetch commands map to GET endpoints', () => {
  assert.deepEqual(commandToRequest('9', { type: 'Search', value: '  Ke$ha & "Tik" Tok ' }, {}),
    { method: 'GET', path: '/api/guild/9/search?q=Ke%24ha%20%26%20%22Tik%22%20Tok', body: null });
  assert.equal(commandToRequest('9', { type: 'Search', value: '   ' }, {}), null);
  assert.deepEqual(commandToRequest('9', { type: 'Lyrics' }, {}), { method: 'GET', path: '/api/guild/9/lyrics', body: null });
});
```

Rust (`bridge.rs` tests): `result_report_carries_data` — `{"type":"result","id":4,"ok":true,"message":null,"data":[{"title":"x"}]}` parses to `Result{id:4, ok:true, data:Some(array)}`, and a report without `data` parses with `data: None`; `search_command_round_trips` — `command_js(1, &BotCommand::Search("a \"b\"\n".into()))` round-trips like the existing hostile-text test, and `BotCommand::Lyrics` serializes as `{"type":"Lyrics"}`.
- [ ] **Step 2:** run both suites → JS FAIL (null vs object), Rust FAIL to compile (`no variant Search`).
- [ ] **Step 3: Implement** the JS mapping and `run()` data reporting, the Rust variants, the `data` field, the pending type, `send_command_fetch` (shared private waiter used by both senders).
- [ ] **Step 4:** both suites PASS.
- [ ] **Step 5: Commit** `desktop: bridge fetch commands return the bot's data`.

### Task 3: Search results, lyrics and links in Rust

**Files:**
- Create: `desktop/src-tauri/src/lookup.rs` (+ `mod lookup;` in `main.rs`)
- Modify: `desktop/src-tauri/src/mini_player.rs` (commands), `main.rs` (handler list), `build.rs`, `capabilities/mini-player.json`

**Interfaces:**
- Consumes: `bridge::send_command_fetch`, `bridge::send_command_await` (Task 2); `spotify::parse_spotify_link`.
- Produces: `SearchResult { title, author, uri, duration_ms: u64, is_stream: bool, source }` and `LyricLine { ms: u64, text }`, `Lyrics { synced, instrumental, lines: Vec<LyricLine>, error: Option<String> }` (all `Serialize`, camelCase); `lookup::search_results(data: &Value) -> Vec<SearchResult>`; `lookup::lyrics_from(data: &Value) -> Lyrics`; `lookup::link_command(text: &str) -> Result<BotCommand, String>`. Commands: `mini_search(query: String) -> Result<Vec<SearchResult>, String>` (10 s), `mini_lyrics() -> Result<Lyrics, String>` (15 s), `mini_play_link(text: String) -> Result<String, String>` returning `"Playing the link"`. All three run the blocking wait via the existing `blocking()` helper.

- [ ] **Step 1: Write the failing tests** in `lookup.rs`:
  - `search_maps_bot_fields`: `[{"title":"T","author":"A","uri":"https://y/1","duration":214000,"isStream":false,"source":"youtube"}]` → one result with `duration_ms: 214000`.
  - `search_drops_items_without_uri_and_caps_at_16`: 20 items (2 without `uri`) → 16 results; non-array data → empty.
  - `search_cuts_long_fields`: a 250-char emoji title → 200 chars ending in `…` (count chars, not bytes).
  - `lyrics_map_synced_lines`: `{"synced":true,"lines":[{"ms":1000,"text":"a"}]}` → synced, one line; `{"synced":false,"lines":[],"error":"No lyrics found"}` → error kept; `{"instrumental":true}` → instrumental; 600 lines → 500; a 400-char line → 300 chars ending in `…`.
  - `links_route_by_kind`: `"https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"` → `PlaySpotifyLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")`; `"spotify:track:4uLU6hMCjMI75M1A2tKUQC"` → `PlaySpotifyLink(..)`; `"watch https://youtu.be/abc now"` → `PlayQuery("https://youtu.be/abc")`; `"just words"` → `Err("That's not a link")`.
- [ ] **Step 2:** `cargo test ... lookup` → FAIL to compile (module missing).
- [ ] **Step 3: Implement** `lookup.rs`, the three commands, register them in `generate_handler!`, `build.rs` and the capability (`allow-mini-search`, `allow-mini-lyrics`, `allow-mini-play-link`).
- [ ] **Step 4:** full cargo suite PASS.
- [ ] **Step 5: Commit** `desktop: search, lyrics and link commands for the mini-player`.

### Task 4: Theme sync plumbing and mini-player preferences

**Files:**
- Create: `desktop/src-tauri/src/theme.rs` (+ `mod theme;`)
- Modify: `desktop/bridge/core.js`, `desktop/bridge/bridge.js`, `desktop/bridge/core.test.js`, `desktop/src-tauri/src/settings.rs`, `bridge.rs`, `mini_player.rs`, `main.rs`, `build.rs`, `capabilities/mini-player.json`

**Interfaces:**
- Produces (JS): `core.THEME_VARS` (the 16 names); `core.readTheme(themeAttr: string|null, getVar: (name) => string) -> {id, vars}` (`id` = attr or `'nebula'`; only non-empty trimmed values). Bridge reports `{type:'theme', theme}` on start and on every `data-theme` attribute change (MutationObserver on `document.documentElement`, `attributeFilter: ['data-theme']`).
- Produces (Rust): `theme::THEME_IDS: [&str; 17]`, `theme::THEME_VARS: [&str; 16]`; `PanelTheme { id: String, vars: BTreeMap<String, String> }` (Serialize/Deserialize, Default, PartialEq); `theme::sanitize(raw: PanelTheme) -> Option<PanelTheme>` (None if id fails `[a-z0-9-]{1,32}`; drops unknown names and bad values per spec §2); `theme::effective(settings: &Settings) -> PanelTheme`; `BridgeReport::Theme { theme: PanelTheme }`; `Settings` fields `mini_player_opacity: f64`, `mini_player_match_panel: bool`, `mini_player_theme: String`, `panel_theme: Option<PanelTheme>` plus `Settings::opacity(&self) -> f64` (clamped 0.15–1.0); `MiniPrefs { opacity, match_panel, theme, effective: PanelTheme }` (camelCase); commands `mini_get_prefs() -> MiniPrefs`, `mini_set_opacity(value: f64)`, `mini_set_theme(match_panel: bool, theme: String)` (an id outside `THEME_IDS` keeps the old one); `mini_player::emit_theme(app)` emits `theme-changed` with `theme::effective`.

- [ ] **Step 1: Write the failing tests.** JS: `readTheme builds id and vars` — fake `getVar` returning `' #fff '` for `--accent`, `''` for others → `{id:'neon', vars:{'--accent':'#fff'}}`; null attr → id `nebula`. Rust `theme.rs`:
  - `sanitize_keeps_colors_gradients_and_fonts`: `#7c5cfc`, `rgba(124, 92, 252, 0.3)`, `linear-gradient(135deg, #7c5cfc, #e04cf0)`, `'IBM Plex Mono', ui-monospace, monospace` all kept.
  - `sanitize_rejects_injection`: `url(https://x/y.png)`, `URL(x)`, `red; background: url(x)`, `red}`, `<b>`, a 201-char value, and `--evil` name → all dropped.
  - `sanitize_rejects_bad_ids`: `Neon`, `neon theme`, `""`, 33 chars → None.
  - `effective_follows_match_setting`: match on + panel theme → panel theme; match on + none seen → `nebula`, no vars; match off → `{id: mini_player_theme, vars: {}}`.
  Rust `settings.rs`: `extras_default_and_clamp` — defaults are 1.0 / true / `"nebula"` / None; opacity 0.01 → 0.15, 7.0 → 1.0; an old settings.json without the new keys still loads.
- [ ] **Step 2:** run → JS FAIL (`readTheme` undefined), Rust FAIL to compile.
- [ ] **Step 3: Implement** the JS reader + observer, `theme.rs`, settings fields, the `Theme` report handling (sanitize; if it differs from `panel_theme`, save settings and `emit_theme`), the three commands (save settings; `mini_set_theme` calls `emit_theme`), registration in handler list / `build.rs` / capability.
- [ ] **Step 4:** both suites PASS.
- [ ] **Step 5: Commit** `desktop: panel theme sync and mini-player preferences`.

### Task 5: Window growth and transparency

**Files:**
- Modify: `desktop/src-tauri/src/mini_player.rs`, `main.rs` (close-request path), `build.rs`, `capabilities/mini-player.json`

**Interfaces:**
- Produces: `Direction { Up, Down }` (Serialize, lowercase); `EXTRA_HEIGHT: f64 = 250.0`; `expanded_rect(collapsed: Rect, extra: u32, monitor: Rect) -> (Rect, Direction)` — up when `collapsed.y - extra >= monitor.y`, else down; when down overflows the monitor's bottom, shift up so the bottom fits but never above `monitor.y`; `collapsed_origin(window: (i32, i32), offset: i32) -> (i32, i32)` = `(x, y + offset)` where `offset = collapsed.y - expanded.y`; command `mini_set_panel(panel: String) -> Result<Option<Direction>, String>` (`"none"` collapses → `None`; `"menu"`/`"lyrics"` expand, already-expanded keeps the direction); the current offset is kept in a module static and used by `on_moved` so the saved position is always the closed one; `mini_player::collapse(app)` used before every hide (tray toggle, `mini_hide`, close request) and emits `panel-reset` to the UI. Window builder adds `.transparent(true).shadow(false)`. Pixel math is physical: `extra = (EXTRA_HEIGHT * scale_factor).round()`; the monitor is the window's current monitor work area.

- [ ] **Step 1: Write the failing tests** in `mini_player.rs`:
  - `grows_up_when_room_above`: monitor `{0,0,1920,1040}`, collapsed `{1548,858,360,170}`, extra 250 → `({1548,608,360,420}, Up)`.
  - `grows_down_at_top_edge`: collapsed `{100,20,360,170}` → `({100,20,360,420}, Down)`.
  - `grows_down_on_monitor_above_primary`: monitor `{-1920,-1080,1920,1040}`, collapsed `{-400,-1070,360,170}` → `({-400,-1070,360,420}, Down)`.
  - `clamps_when_neither_fits`: monitor `{0,0,1280,400}`, collapsed `{0,100,360,170}` → `({0,0,360,420}, Down)` (offset 100).
  - `saved_position_is_the_closed_one`: `collapsed_origin((1548,608), 250) == (1548,858)`; `collapsed_origin((5,5), 0) == (5,5)`.
- [ ] **Step 2:** `cargo test ... mini_player` → FAIL to compile.
- [ ] **Step 3: Implement** the helpers, the command, the offset bookkeeping in `on_moved`, `collapse` before hides, and the builder flags; register `mini_set_panel` (handler list, `build.rs`, `allow-mini-set-panel`).
- [ ] **Step 4:** full cargo suite PASS.
- [ ] **Step 5: Commit** `desktop: mini-player grows up or down for panels; transparent window`.

### Task 6: Theme skins

**Files:**
- Create: `desktop/ui/mini-player/themes.css`
- Modify: `desktop/ui/mini-player/mini.css` (switch to the panel's variable names; Nebula values at `:root`), `index.html` (Google Fonts for Inter, DM Sans, Noto Sans JP, Shippori Mincho, Outfit, IBM Plex Mono, Syne, Fraunces; `themes.css` after `mini.css`), `view.js`, `view.test.js`, `mini.js`

**Interfaces:**
- Consumes: `mini_get_prefs`, `theme-changed` (Task 4).
- Produces (view.js): `THEMES` (17 `{id, label}` in Global Constraints order); `applyTheme(root, theme, previous: string[]) -> string[]` — sets `data-theme` to `theme.id`, `style.setProperty` for each var, `removeProperty` for previously applied names not in this theme, returns the applied names; `applyOpacity(root, value)` sets `--card-alpha`. CSS: card background `color-mix(in srgb, var(--bg-card) calc(var(--card-alpha) * 100%), transparent)`; `html, body` transparent; `.low-alpha` (set when opacity < 0.6) adds a text shadow.

- [ ] **Step 1: Write the failing tests** in `view.test.js`: `THEMES lists the 17 panel themes in order` (ids equal the Global Constraints list); `themes.css has a block per theme` (read the file with `fs`, expect `[data-theme="<id>"]` for all 17); `applyTheme swaps inline vars` (fake root: apply `{id:'neon', vars:{'--accent':'#0ff','--bg-card':'#000'}}`, then `{id:'ember', vars:{'--accent':'#f80'}}` → `--bg-card` removed, `data-theme` = `ember`, returns `['--accent']`).
- [ ] **Step 2:** `npm run -s test:js` → FAIL.
- [ ] **Step 3: Implement** the helpers and wire `mini.js` (prefs on load, `theme-changed` listener). Write `themes.css`: for each theme, copy that theme's values for the 16 variables from its block in `src/main/resources/web/index.html`, and add one CSS-only signature touch taken from that theme's panel CSS that is visible at mini-player size (no remote images). Move the current Revival look (record art, sunburst) into the `revival` block; `:root` becomes Nebula.
- [ ] **Step 4:** `npm run -s test:js` PASS; build the dev app and switch a few panel themes to see the mini-player follow (screenshot not required; Fred checks all 17 in Task 9).
- [ ] **Step 5: Commit** `desktop: a mini-player skin for every panel theme`.

### Task 7: Bottom row, Spotify logo and the menu (search + settings)

**Files:**
- Modify: `desktop/ui/mini-player/index.html`, `mini.css`, `mini.js`, `view.js`, `view.test.js`
- Modify: `desktop/src-tauri/src/mini_player.rs`, `main.rs`, `build.rs`, `capabilities/mini-player.json` (remove `mini_play_spotify_clipboard`; the tray's `spotify::play_clipboard` stays)

**Interfaces:**
- Consumes: `mini_search`, `mini_play_link`, `mini_command` (PlayQuery with a result's `uri`), `mini_set_panel`, `mini_set_opacity`, `mini_set_theme`, `mini_get_prefs`, `panel-reset`, `spotify-changed`.
- Produces (view.js): `spotifyView(status) -> {playable: bool, tooltip: string}` — playable when `available && now && now.playing`; tooltips `Queue “<title> – <artist>” on the bot` (no ` – <artist>` when empty), `Spotify isn't playing`, `Spotify isn't open` (status null/unavailable); `isLink(text) -> bool` (trimmed text contains an `http://`/`https://` word or starts with `spotify:`); `formatDuration(ms, isStream) -> string` (`m:ss`, `h:mm:ss` from one hour, `LIVE` for streams); `latestOnly() -> {next(): number, isLatest(n): boolean}`.

- [ ] **Step 1: Write the failing tests** (`view.test.js`): `spotifyView states` (three tooltips above, playable only for the first); `isLink` (`https://youtu.be/x` ✓, `  spotify:album:1 ` ✓, `watch https://x.y now` ✓, `httpx` ✗, `daft punk` ✗); `formatDuration` (`61000 → 1:01`, `3723000 → 1:02:03`, `0 → 0:00`, stream → `LIVE`); `latestOnly` (`a=next(); b=next(); isLatest(a) === false; isLatest(b) === true`).
- [ ] **Step 2:** `npm run -s test:js` → FAIL.
- [ ] **Step 3: Implement.** Bottom row: Spotify logo button (inline SVG, `#1ED760` when playable, grey + `disabled` otherwise, `title` = tooltip; click → `mini_send_spotify` as today), lyrics button (Task 8 wires it; disabled when no track), ☰ menu button. Remove the paste button and its handler. Menu: `mini_set_panel('menu')`, lay out above (Up) or below (Down) via a body class; search box focused; Enter → if `isLink` show one "Play this link" row (click/Enter → `mini_play_link`), else `mini_search` guarded by `latestOnly`; rows show title, artist, `formatDuration`, single-line with `text-overflow: ellipsis`, built with `textContent`; click → `mini_command({type:'PlayQuery', value: uri})` then status `Queued “<title>”`; failure → one row with the message; no results → "No results". Settings: Background slider 15–100 (`input` updates `--card-alpha` live, `change` → `mini_set_opacity(value/100)`), "Match panel theme" switch, theme `<select>` from `THEMES` shown when the switch is off (`mini_set_theme`). Esc / ☰ / `panel-reset` close the menu (`mini_set_panel('none')`).
- [ ] **Step 4:** `npm run -s test:js` and cargo suite PASS; dev build: search a song, queue it, paste a YouTube and a Spotify link, move the slider, flip the switch.
- [ ] **Step 5: Commit** `desktop: mini-player menu with search, links and settings; Spotify logo button`.

### Task 8: Lyrics panel

**Files:**
- Modify: `desktop/ui/mini-player/index.html`, `mini.css`, `mini.js`, `view.js`, `view.test.js`

**Interfaces:**
- Consumes: `mini_lyrics`, `mini_set_panel`, `state-changed` (UiState with `positionMs`, `positionAtMs`), `latestOnly`.
- Produces (view.js): `currentLyricIndex(lines, positionMs) -> number` (index of the last line with `ms <= positionMs`, `-1` before the first or for empty lines; binary or linear search); `trackKey(state) -> string|null` (`title + '\n' + uri` of `state.track`, null without a track).

- [ ] **Step 1: Write the failing tests:** `currentLyricIndex` — lines at 1000/5000/9000: pos 0 → -1, 1000 → 0, 4999 → 0, 5000 → 1, 99999 → 2, `[]` → -1; `trackKey` — same title+uri → equal keys, different uri → different, no track → null.
- [ ] **Step 2:** `npm run -s test:js` → FAIL.
- [ ] **Step 3: Implement:** the lyrics button toggles the lyrics panel (`mini_set_panel('lyrics')`, closes the menu), states Loading… / No lyrics found / Instrumental / Nothing playing / error message; timed lyrics highlight `currentLyricIndex` on every `tick()` and scroll it to the vertical center when it changes; untimed lyrics scroll freely with a "not synced" label; while open, a `trackKey` change refetches (guarded by `latestOnly`, so an old reply can't render over the new song); Esc / button / `panel-reset` close it.
- [ ] **Step 4:** suites PASS; dev build: open lyrics on a playing song, seek in the panel, skip.
- [ ] **Step 5: Commit** `desktop: lyrics in the mini-player`.

### Task 9: Deploy the bot and hand checks

- [ ] **Step 1:** Ask Fred before deploying. Then find Clout Kings' guild id on the server (bot log or `serversettings.json`), add `webdefaultguild = "<id>"` to `~/config.txt`, and deploy the jar with the usual routine (idle check, scp, hash check, backup, swap, TERM, wait for "Finished Loading").
- [ ] **Step 2:** In a private browser window with no saved server, log in → Clout Kings is selected. `GET /api/guilds` (logged in) shows `isDefault: true` for it only.
- [ ] **Step 3:** Fred checks in the dev build: every theme in the panel → mini-player follows; switch off → picker works and the panel no longer changes it; slider over a bright wallpaper; menu and lyrics near the top of the screen; Spotify logo green/grey.
