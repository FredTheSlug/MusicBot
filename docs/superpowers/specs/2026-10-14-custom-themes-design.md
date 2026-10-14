# Custom themes — Design

Fred (2026-10-14): "users can create their own themes and upload their own backgrounds to them if they
wish. The themes they create can be either public and go along with the others or private and usable just
by them. Upon creating the theme you can choose to make a miniplayer version as well or skip that and it
won't be an option in the miniplayer. Make it guided so someone inexperienced can create their own theme."

Decided with Fred: themes are data applied as CSS values (never stored CSS); a creator + bot-owner
moderation model; base theme + generated palette + optional fine-tune; still and animated image
backgrounds up to 8 MB; an auto-generated mini-player version with live preview and tweaks; backgrounds
served through secret (unguessable) links. Ships after the patch-notes feature
(`2026-10-14-patch-notes-design.md`), as a bot deploy plus desktop 0.5.0, each with a "What's new" entry.

## 1. Today

17 built-in themes in `src/main/resources/web/index.html`, each an `html[data-theme="…"]` block of ~60
CSS variables (colors, `--accent-r/g/b`, `--accent2-r/g/b`, radii, spacing, `--font-family`). Twelve add
decorative layers (`body::before`/`body::after`: Ember flames, Grove/Neon pictures, Sumi tiles, …). The
choice is kept per browser (`jmusicbot_theme_v1`) and applied by `applyTheme`. Fonts loaded by the panel:
Inter, DM Sans, Outfit, Syne, Fraunces, IBM Plex Mono, Noto Sans JP, Shippori Mincho. The desktop
mini-player has a hand-tuned skin per built-in theme (`desktop/ui/mini-player/themes.css`, `THEME_IDS`)
and a "match the panel" mode that copies the panel's 16 `THEME_VARS` (`desktop/bridge/core.js`), reported
by the bridge when `data-theme` changes.

## 2. A theme

Stored as `Themes/<id>.json` (folder next to `Playlists/`; `id` = 12 random `[a-z0-9]`), written atomically
(temp file + move), one lock per theme:

```json
{
  "format": 1,
  "id": "k3j9x0q2m7ab",
  "name": "Midnight Drive",
  "owner": { "id": "233362518045753345", "name": "fred" },
  "visibility": "public",
  "hiddenByOwner": false,
  "version": 3,
  "createdAt": "2026-10-14T18:00:00Z",
  "updatedAt": "2026-10-14T18:05:00Z",
  "base": "ember",
  "effects": false,
  "colors": {
    "bgPrimary": "#0b0d14", "bgSecondary": "#10131c", "bgCard": "#161a26", "bgElevated": "#1c2131",
    "bgHover": "#262c42", "accent": "#7c5cff", "accentLight": "#a08bff", "accent2": "#22d3ee",
    "textPrimary": "#eef0f8", "textSecondary": "#9aa0b8", "textTertiary": "#646a82",
    "border": "#ffffff12", "borderLight": "#ffffff1f", "success": "#34d399", "glass": "#10131ce0"
  },
  "radiusScale": 100,
  "font": "Outfit",
  "background": { "image": "9f2c…e1.webp", "dim": 45, "blur": 4, "fit": "cover" },
  "mini": {
    "colors": { "bgPrimary": "…", "bgSecondary": "…", "bgCard": "…", "bgElevated": "…", "bgHover": "…",
                "accent": "…", "accentLight": "…", "accent2": "…", "textPrimary": "…",
                "textSecondary": "…", "textTertiary": "…", "border": "…", "borderLight": "…",
                "success": "…" },
    "useImage": true, "dim": 55
  }
}
```

- `visibility` `public`|`private`. `base` one of the 17 built-in ids. `effects` keeps the base theme's
  decorative layers and built-in picture (forced off while a background picture is set).
- `colors`: exactly these 15 slots, each `#rrggbb` or `#rrggbbaa`. The panel derives the remaining
  variables: `--accent-glow` (accent at 32% alpha), `--accent-gradient` (accent → accent2),
  `--accent-r/g/b`, `--accent2-r/g/b`.
- `radiusScale` 0–200 (%; scales every radius variable of the base). `font` one of the 8 loaded fonts.
- `background` optional: `image` = `[0-9a-f]{32}\.(jpg|png|webp|gif)`, `dim` 0–90, `blur` 0–20,
  `fit` `cover`|`contain`|`center`.
- `mini` optional (absent = the theme isn't offered in the mini-player): 14 color slots (the 15 minus
  `glass`), `useImage`, `dim` 0–90. These map to the mini-player's 16 `THEME_VARS` (glow and gradient
  derived; font from `font`).
- Name 1–40 characters (trimmed; no control characters); shown as text everywhere.

**Images:** `Themes/images/<32 hex random>.<ext>`; type detected from the first bytes — JPEG `FF D8 FF`,
PNG `89 50 4E 47 0D 0A 1A 0A`, GIF `GIF87a`/`GIF89a`, WebP `RIFF????WEBP` — anything else refused (no SVG);
≤ 8 MB. A replaced or removed picture's file is deleted; a theme's delete deletes its picture.

## 3. Who can do what

- Anyone logged in: create up to **10** themes; use every public theme and their own private ones.
- The creator: edit, switch public/private, delete their own.
- The bot owner (Discord id = the bot's `owner` config): delete any public theme, or **hide** one (it
  becomes private to its creator, `hiddenByOwner: true`, and the creator sees "Hidden by the bot owner"; the
  creator can't make it public again until the owner un-hides it). The owner never sees others' private
  themes.
- Deletes are permanent (confirmation in the panel). Someone using a theme that is deleted, hidden or made
  private falls back to its base theme (panel and mini-player).

## 4. Web API

All under `/api/*` (Discord login); errors `{error}` with 400 (invalid), 403 (not allowed), 404 (missing or
not visible to you), 409 (limit reached / changed meanwhile), 413 (picture too large).

| Method & path | Does |
|---|---|
| `GET /api/themes` | `{themes: [...], me: {id, isOwner}, limit: {max: 10, used}}` — public themes + mine; each with `mine`, `owner`, values, `background.url` (secret link) and `mini` |
| `POST /api/themes` | create — multipart: `theme` (JSON values) + optional `background` (file); saved in one go |
| `POST /api/themes/{id}` | edit mine — same parts, plus `removeBackground`; `version` must match (else 409) |
| `POST /api/themes/{id}/delete` | creator, or owner for a public theme |
| `POST /api/themes/{id}/hide` | owner only; `{hidden: true|false}` |
| `GET /theme-images/{name}` | the picture by its secret name — no login; `Content-Type` from the extension, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`, long immutable cache (a new picture gets a new name) |

- Create/edit validate everything before writing; the picture is written first, then the theme; if the
  theme write fails the new picture is deleted. A private theme's picture name is only ever sent to its
  creator.
- Every change broadcasts a WebSocket `themes` message (`{id}`) to all clients; panels and the mini-player
  re-fetch.

## 5. Panel

- **Picker:** the theme dropdown gets groups Built-in / Community ("Name — by creator") / My themes
  (private marked), plus "＋ Create a theme", and Edit / Delete for your own (Hide / Delete on community
  themes for the owner).
- **Applying:** set `data-theme` to the base, then each custom value with `style.setProperty` (never CSS
  text); radius variables scaled from the base's computed values; `data-custom-plain` (when `effects` is
  off) hides the base's `body::before/::after` layers and built-in picture; the background is a fixed layer
  behind the content (`dim` as a dark overlay, `blur` as a filter, `fit` as `background-size/position`).
  The choice is remembered per browser as `custom:<id>`; a missing/forbidden theme falls back to its base.
- **Reduce motion:** a per-browser switch in settings (on by default when the system asks for reduced
  motion) shows animated pictures as their first frame (drawn to a canvas).
- **Live updates:** a `themes` message refreshes the list; if the active theme changed it is re-applied.

## 6. The guided creator

A guided window over the panel; the real panel behind it previews the theme live. Step dots, Back/Next on
every step, sensible defaults so Next always works; Cancel ("Discard this theme?") restores the previous
theme; full-screen at phone width.

1. **Start from** — cards for the 17 built-ins and community themes, each a small preview in its colors.
2. **Colors** — "Main color" (16 suggested swatches + any color), "Background" (Dark/Light + a few
   suggestions matched to the main color); the rest generated, with text adjusted until readable (contrast
   ≥ 4.5:1 for main text on background and cards, ≥ 3:1 for secondary text) and a "Readable ✓" badge;
   "Surprise me" picks a random good pair.
3. **Background picture** (optional, big Skip) — drop or choose a file; previewed at once (local, uploaded
   on Save); Darken (starting value suggested from the picture's measured brightness), Blur, Fit ("Fill
   the screen" / "Show the whole picture" / "Center it"); "Animated GIFs work too".
4. **Fine-tune** (collapsed "Want to tweak more?") — each color with plain names (Cards, Panels, Hover,
   Main text, Secondary text, Borders), Corners slider (square ↔ round), Font (shown on sample text), "Keep
   special effects" (only when the base has them); unreadable choices show a warning with "Fix it".
5. **Mini-player** — "Make a mini-player version too?" Skip, or a live mini-player preview (artwork, song,
   buttons) generated from the theme, with its own main/background colors and "Use my background picture"
   + Darken.
6. **Name and share** — name (required), Public ("everyone in the server can pick it") / Private ("only
   you"), Save → upload + save in one request, switch to it, "Theme saved". At 10 themes it explains and
   offers "My themes".

Editing reopens the same steps filled in, at step 2. The palette math (generate from two colors, contrast,
readable fixes, "Surprise me", mini palette) lives in its own file `src/main/resources/web/theme-palette.js`
(no DOM), loaded by the panel and unit-tested.

## 7. Desktop app and mini-player (0.5.0)

- The mini-player's theme menu gets Built-in / Community / My themes; only themes with `mini` are listed.
  The list comes from `GET /api/themes` through the bridge (follows the panel's login).
- Applying: the 16 `THEME_VARS` from `mini.colors` (+ derived glow/gradient, `font`); with `useImage` the
  picture from its secret link (already allowed by the mini-player's `img-src https:`) behind the content,
  dimmed, faded by the existing opacity slider, frozen by Reduce motion.
- "Match the panel": the bridge reports the panel's active theme id as well as its colors (and also
  observes inline style changes). A custom theme with `mini` → that version; without → the base theme's
  hand-tuned skin. A theme that disappears → its base skin.
- Older apps (≤ 0.4.0) keep working: in match mode they copy the panel's colors (no picture); they don't
  list custom themes.

## 8. Errors

Plain messages for refused or oversized pictures; nothing half-saved (atomic writes; orphan picture
removed); corrupt theme files skipped with a log warning; a picture that fails to load is simply not shown;
saving a theme that was deleted/hidden meanwhile → message + "Save as a new theme"; edit conflicts (version
changed) → "This theme changed — reload it?".

## 9. Security

Login on every `/api/themes` route; values validated by allow-lists (color patterns, ranges, base ids,
fonts) and applied as individual CSS properties; names as text only; pictures sniffed by magic bytes, size
capped before writing, served from random names with `nosniff`, `sandbox` CSP and no login (accepted
trade-off: anyone given a link can view that picture); private themes and their links never reach other
users; per-user cap of 10.

## 10. Testing

- Java (JUnit 4, temp dirs, bare Javalin like `PlaylistRoutesTest`): create/edit/delete; the 10 limit;
  visibility (private never in another user's list, its picture name never sent); owner hide/un-hide/delete
  and everyone else refused; every validation rule; magic-byte detection (real JPG/PNG/WebP/GIF accepted;
  renamed SVG/HTML/EXE refused; 8 MB + 1 refused); secret-link headers; version conflict; two saves at
  once; a failed theme write removes the new picture.
- JS (`node --test`): `theme-palette.js` — generated palettes meet the contrast bars for many random color
  pairs, "Fix it" repairs unreadable picks, mini palette derivation.
- Desktop: theme-list mapping (only `mini` themes, groups), applying a mini palette, match-mode fallback
  to the base skin.
- By hand: the creator in a few base themes and at phone width; an animated GIF with Reduce motion; the
  mini-player with and without a picture; owner hide/delete from a second account.
