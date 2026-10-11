# Mini-player Extras — Design

Follow-up to `2026-10-10-desktop-companion-design.md` (the "companion spec"); everything there still
holds unless this document changes it.

## 1. Intent

Fred and his friends use the mini-player as their everyday control for the Clout Kings bot. This
round makes it do more without opening the panel, and makes it look like the panel:

1. **Transparency** — a slider fades the mini-player's background card; text, buttons and artwork
   stay solid.
2. **Menu (opens upward)** — a ☰ button opens a menu above the player with a mini search (text →
   result list, click to queue; a pasted link → "Play this link") and the mini-player's settings.
3. **Lyrics** — a lyrics button opens the current song's lyrics above the player, same width; timed
   lyrics follow the song.
4. **Spotify logo** — the "▶ Send …" text button becomes a Spotify logo: green when Spotify is
   playing something the bot can queue, grey otherwise. The "Paste Spotify link" button is removed
   (the search box takes links).
5. **Theme skins** — a mini-player look for each of the panel's 17 themes, synced live with the
   theme chosen in the panel; a "Match panel theme" switch turns syncing off and lets the user pick a
   mini-player theme of their own.
6. **Clout Kings by default** — when no server has been picked yet, the panel (in browsers too) and
   therefore the app select Clout Kings.

Success: a friend who installs the app sees Clout Kings' song in a mini-player that matches their
panel theme, can find and queue a song or play a link from it, read along with the lyrics, and fade it
over their game — without opening the panel.

Unchanged: the tray menu (including "Play Spotify link from clipboard"), dropping a Spotify link on
the mini-player, toasts, media keys, and the security model (the remote page can still call only
`bridge_report`).

## 2. Architecture

### Data flows (new)

```
panel page ──theme report──▶ Rust (sanitize, save) ──theme-changed──▶ mini-player
mini-player ──mini_search / mini_lyrics──▶ Rust ──BotCommand::Search/Lyrics──▶ bridge
bridge ──GET /api/guild/{id}/search|lyrics (page's own login)──▶ bot
bridge ──result report {id, ok, message, data}──▶ Rust (validate, cap) ──▶ mini-player
```

### Bridge (desktop/bridge)

- **Theme**: on start and whenever `<html data-theme>` changes (MutationObserver — no polling), report
  `{type:'theme', theme:{id, vars}}`. `id` = the `data-theme` value, or `"nebula"` when absent.
  `vars` = computed values of the theme variables listed below, read from `document.documentElement`.
- **Fetch commands**: `commandToRequest` maps `Search` → `GET /api/guild/{id}/search?q=<encoded>` and
  `Lyrics` → `GET /api/guild/{id}/lyrics`. For GET requests `run()` parses the JSON body and reports
  it as `data` in the result: `{type:'result', id, ok, message, data}`. Non-2xx or a body with `error`
  and no usable data → `ok:false` with the message.

### Theme variables

`--bg-primary --bg-secondary --bg-card --bg-elevated --bg-hover --accent --accent-light --accent-glow
--accent-gradient --text-primary --text-secondary --text-tertiary --border --border-light --success
--font-family` (16 names). Rust drops any other name.

### Rust

- `BotCommand` gains `Search(String)` and `Lyrics` (no value).
- `BridgeReport::Result` gains `data: Option<serde_json::Value>`; `BridgeReport` gains
  `Theme { theme: PanelTheme }`. `send_command_await` keeps its signature; a new
  `send_command_fetch(app, cmd, timeout) -> Result<serde_json::Value, String>` returns the data.
- **Theme sanitizing** (`theme.rs`, pure): `id` must match `[a-z0-9-]{1,32}` (else ignored). A value is
  kept only if it is ≤ 200 chars, uses only `A–Z a–z 0–9 space # ( ) , . % ' " _ + -`, and does not
  contain `url(` (case-insensitive). The cleaned theme is saved as `settings.panelTheme` and, if it
  changed, sent to the mini-player.
- **Effective theme** sent to the mini-player as `theme-changed {id, vars}`: if
  `miniPlayerMatchPanel` → the saved panel theme (or `nebula`, no vars, before one is seen); else
  `{id: miniPlayerTheme, vars: {}}` (the mini-player's built-in colors for that theme).
- **Search results**: data must be an array; each item maps to
  `SearchResult {title, author, uri, durationMs, isStream, source}` from the bot's `title, author, uri,
  duration, isStream, source`. Items without a `uri` are dropped; strings are cut to 200 chars; at
  most 16 results. Timeout 10 s.
- **Lyrics**: data maps to `Lyrics {synced, instrumental, lines:[{ms, text}], error}`; at most 500
  lines, text cut to 300 chars. Timeout 15 s.
- **Links** (`mini_play_link(text)`): a Spotify link/URI (existing `parse_spotify_link`) →
  `PlaySpotifyLink(normalized)`; otherwise the first `http(s)://` word → `PlayQuery(url)`; otherwise
  "That's not a link".
- **Window growth** (`mini_player.rs`, pure helper): `expanded_rect(collapsed: Rect, extra: u32,
  monitor: Rect) -> (Rect, Direction)` — grow up by `extra` when the space above fits, else grow down;
  never leaves the monitor. `mini_set_panel(panel: "none"|"menu"|"lyrics") -> Direction` resizes and
  returns the direction so the UI lays the panel out above or below. The saved position is always the
  collapsed player's (when expanded up, saved y = window y + extra). Hiding the mini-player collapses
  it first; it always reopens closed.

### Settings (settings.json, all optional with defaults)

| Key | Type | Default |
|---|---|---|
| `miniPlayerOpacity` | number, clamped 0.15–1.0 | 1.0 |
| `miniPlayerMatchPanel` | bool | true |
| `miniPlayerTheme` | string (theme id) | `"nebula"` |
| `panelTheme` | `{id, vars}` or null | null |

### Mini-player (desktop/ui/mini-player)

- Window: transparent, no shadow, 360 px wide; closed height as now (170); open height closed + 250.
- `html, body` transparent; the card's background is
  `color-mix(in srgb, var(--bg-card) calc(var(--card-alpha) * 100%), transparent)` with `--card-alpha`
  from `miniPlayerOpacity`; below 0.6 text gets a subtle shadow for legibility over any wallpaper.
- Bottom row: Spotify logo (inline SVG; `#1ED760` when playable, grey otherwise; tooltip
  `Queue “Title – Artist” on the bot`, "Spotify isn't playing", or "Spotify isn't open"), lyrics
  button (greyed when nothing plays), and ☰ on the right.
- **Menu**: search box focused on open; Enter searches; results (title, artist, m:ss) up to ~8 visible
  rows, scrollable; clicking queues via the existing `PlayQuery` command with the result's `uri` and
  the status line says "Queued …". A link in the box shows one "Play this link" row instead. Esc or ☰
  closes. A newer search supersedes an unfinished one (sequence number; stale replies ignored).
  Settings: "Background" slider (15–100 %), "Match panel theme" switch, and a theme picker (17
  themes, panel labels) shown when the switch is off.
- **Lyrics**: loading / "No lyrics found" / "Instrumental" / "Nothing playing" states. Timed lyrics:
  the current line (last line with `ms ≤ position`) is highlighted and kept centered, using the same
  interpolated position as the progress bar. Untimed: free scroll plus a "not synced" label. While
  open, a track change (title + uri) refetches.
- Menu and lyrics share the space: opening one closes the other.
- All text from the bot or Spotify is rendered with `textContent`, never `innerHTML`.
- **Themes** (`themes.css`): one block per theme id under `html[data-theme="…"]` with the theme's
  built-in colors and font (copied from the panel's CSS) plus one signature touch chosen from that
  theme's panel CSS (e.g. Revival's record and sunburst, Neon's glow, Cipher's monospace caret,
  Sumi's ink underline). Live vars from the panel, when synced, are set inline on `<html>` and so
  override the built-in colors; switching sync off removes them. Unknown ids get the base look with
  whatever vars arrived. Google Fonts load under the existing CSP; offline, fallbacks apply.

## 3. Bot changes

- Config `webdefaultguild` (string, default `""`), documented in `reference.conf` and
  `config.txt.example`; `BotConfig.getWebDefaultGuild()`.
- `GET /api/guilds` adds `isDefault: true|false` per guild (true only when the id equals a non-empty
  `webdefaultguild`).
- Panel `loadGuilds`: when nothing is saved and nothing is selected, select the `isDefault` guild,
  save it as `defaultGuild`, and connect as if picked. A saved choice always wins. Users who aren't in
  that server never see it, so nothing changes for them.
- Deploy: set `webdefaultguild` to Clout Kings' id in the server's `config.txt` (asked before deploy).

## 4. Error handling

- Search/lyrics with no server selected or signed out: same messages as the other buttons.
- Search failure → one row with the message; zero results → "No results".
- Lyrics failure → the message in the lyrics panel; a retry happens on the next open or track change.
- A theme report that fails sanitizing changes nothing.
- Window growth that fits neither up nor down (tiny screen) → grow down, clamped to the monitor.

## 5. Security

The remote page's permissions are unchanged (`bridge_report` only). New remote → Rust data (theme
vars, search results, lyrics) is validated and capped in Rust and rendered as text. Theme values are
allow-listed characters only, with `url(` rejected, so a theme can't load remote resources or break
out of a CSS value. Links passed to the bot are only Spotify links or `http(s)` URLs; the bot already
validated these inputs for the panel.

## 6. Testing

- JS (`node --test`): Search/Lyrics request mapping and query encoding; theme read-out builds
  `{id, vars}` from a fake style source; link detection for the "Play this link" row; lyric current-line
  selection; view helpers for the Spotify logo state.
- Rust: theme sanitizing (good values, `url(`, `;`, long values, bad ids); effective-theme choice;
  settings defaults and opacity clamping; search/lyrics data mapping and caps; link routing;
  `expanded_rect` up/down/clamp; saved position while expanded.
- Java: `isDefault` only for the configured id and never when unset.
- By hand in the app: each theme's look, transparency over a wallpaper, menu and lyrics opening up and
  near the top of the screen, search/link/lyrics against the live bot, sync on/off.
