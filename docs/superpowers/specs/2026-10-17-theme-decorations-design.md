# Theme decorations: your colours, your pieces — Design

Decided with Fred (2026-10-17). A friend wants Revival's spinning record without Revival's peach/brown. Choices:
**pick pieces + any colours**, **all 11 decorated themes this round**, colours that have no slot are **derived from your
colours**. Ships with desktop 0.7.0, which also carries the paste-link button and reopening the last view.

## 1. Today (catalogue of 2026-10-17)

- Custom themes reuse a starting theme's `data-theme` and override 15 colour slots (`ThemePalette.cssVars`). The 11
  decorated themes (ember, steel, grove, sumi, rasta, dawn-dark, cipher, signal, victory, neon, revival) hard-code
  their decoration colours: 264 values in the panel (`index.html`; Revival 83, Neon 48, Signal 30, Victory 27,
  Cipher 26, Ember 16, Rasta 16, Sumi 10, Grove 8, Steel 0, Dawn Dark 0) plus Sumi's tile SVG and Revival's grain
  matrix, and 82 in the mini-player (`desktop/ui/mini-player/themes.css`, all 17 skins). So a custom theme's colours
  don't reach its decorations.
- "Special effects" is one switch (`effects`); off sets `html[data-custom-plain]`, which hides only `body::before/after`,
  `.header::before/after` and `.layout-pane.card::before`. Revival's record, logo sunburst, "revived" badge and ✦ titles
  stay; Signal keeps an empty caption gap; Rasta/Victory keep `header {border-bottom:none}`.
- Some fixed colours beat a custom palette even with effects off: Revival's `.card`/`.header` backgrounds, Neon's
  `#ffffff` text (8 places).
- A custom theme's mini-player version has no decorations at all: `view.js` sets `data-theme="custom"`, which no skin
  matches (a test enforces that). The base skin is only a fallback.
- Colours with no slot: Rasta's red, Cipher's cyan, Ember's yellow flame cores and amber; plus derived tints/shades
  (Revival's creams, peaches and near-black browns).
- In the panel only Revival honours reduced motion; Ember and the shared `bgShift` don't. The mini-player stops all
  animations under reduced motion.

## 2. Colours

- **Every decoration colour is expressed in the theme's slots.** `rgba(R,G,B,a)` of a slot becomes the slot at that
  alpha (`color-mix(in srgb, var(--slot) a%, transparent)`, or the existing `--accent-r/g/b` / `--accent2-r/g/b`
  triples); derived tints and shades become mixes of slots with white/black or with each other (e.g. a Revival cream =
  accent-light mixed toward white). A built-in theme's slots reproduce its old values: **built-in themes look the same.**
- **New slot "Extra decoration colour" (`decoExtra`)** for colours that have no slot: Rasta's red, Cipher's cyan,
  Ember's flame-core yellow (and the amber derived from it). Each built-in theme sets it in its CSS block (Rasta
  `#dc2626`, Cipher `#22d3ee`, Ember `#fde047`); the other themes set it to their accent-light. It is the 16th colour of
  a custom theme (panel; the mini-player version gets it too): it starts as the starting theme's value; when you change
  your colours (main colour, "Use colours from…"), it is re-derived as your accent-light unless you set it yourself in
  Fine-tune (the creator remembers that you did).
- **Pictures that can't take variables:** Ember's flames (inline SVG data URIs) are redrawn as a flame-shaped mask
  filled with slot gradients, so they recolour. Grove's forest photo and Neon's backdrop photo stay photos (each is a
  piece you can switch off); their veils follow your colours. Sumi's kanji tile and Revival's film grain become masks
  filled with a slot colour.
- **Fixed colours that override yours** become slots: Revival's card and header backgrounds, Neon's white text (now
  `var(--text-primary)`), the other slot-equal literals the catalogue lists.

## 3. Pieces

- Each decorated theme's decorations are named **pieces**; one switch per piece, covering the panel and the mini-player
  where both have it. The theme's shape (radius, fonts, density, card style) is not a piece and always comes with the
  starting theme.
- Pieces, defined once in `src/main/resources/web/theme-pieces.json` (`{ "<theme>": [{ "id", "label", "background":
  bool }] }`) and read by the panel, the creator, the server and the mini-player:

| Theme | Pieces (id: label) |
|---|---|
| revival | sunrise: Sunrise glow · rays: Sunburst rays · logo: Turning logo · badge: "revived" badge · sparkles: ✦ titles · record: Spinning record · glows: Warm glows |
| ember | flames: Corner flames · edge: Header flame edge · fire: Fire glow |
| neon | photo: Backdrop photo · rails: Lime rails · glows: Glows and outlines · title: Glowing title and progress bar |
| cipher | grid: Terminal grid · underline: Neon underline · glows: Glows and outlines · prompt: Prompt and blinking caret |
| signal | scanlines: CRT scanlines · caption: "LINK ESTABLISHED" caption · line: Header glow line · hud: HUD frames and outlines |
| victory | glows: Gold glows · rule: Header gold rule · gilded: Gilded cards and glows |
| sumi | mist: Ink mist · kanji: Kanji watermark · lines: Vermilion header lines · hanko: Hanko card edge · seal: Seal and brush stroke |
| rasta | stripe: Flag stripe · titles: Flag-coloured titles · bar: Flag drag bar · lyric: Lyric glow |
| grove | photo: Forest photo · hairlines: Slate hairlines · glows: Corner glows |
| steel | wash: Accent wash · sheen: Top sheen and brushed lines |
| dawn-dark | wash: Ambient wash · edge: Gradient edge |
| nebula, aurora, crimson, dawn, verdant, grapevine | glow: Glow (mini-player only) |

  `background: true` for pieces that paint the page background: revival sunrise/rays, neon photo, cipher grid, signal
  scanlines, victory glows, sumi mist/kanji, grove photo, steel wash, dawn-dark wash.
- **Switching off:** `<html data-deco-off="rays record">` (space-separated ids); every piece's rules are written as
  `html[data-theme="x"]:not([data-deco-off~="id"]) …`. Off removes the piece completely, including its layout effects
  (Signal's caption gutter, Rasta's/Victory's header border).
- Built-in themes: no `data-deco-off`, all pieces on (unchanged look).
- A custom theme with a **background picture** has its `background` pieces off (others stay on).
- **Motion:** every animated piece stops under the panel's "Reduce motion" and `prefers-reduced-motion` (Ember's
  flicker and the shared `bgShift` included).
- `data-custom-plain` goes away (replaced by the pieces); the effects flag maps to pieces (§4).

## 4. Creator, saving, existing themes

- **Colors step:** new "Use colours from…" — the 17 built-in themes as chips; one click fills all 16 slots (incl.
  `decoExtra`) from that theme. The main-colour picker and suggestions work as today on top.
- **Fine-tune step:** the single "Keep special effects" switch becomes the starting theme's piece list (a switch each,
  plus "All on" / "All off"); live preview (panel and mini-player) updates as you flip; pieces turned off by a picture
  show as off with a note. `decoExtra` joins the colour list for decorated starting themes.
- **Saved theme (`ThemeFile`):** `decorationsOff: ["id", …]` (only ids the starting theme has; unknown ids dropped,
  duplicates removed, order kept) and `colors.decoExtra` (`#rrggbb`); the mini version's colours gain `decoExtra` too.
  `effects` is no longer written.
- **Existing themes** (read-time conversion, written in the new form on the next save): `effects: false` → all of the
  starting theme's pieces off; `effects: true`/absent → none off; missing `decoExtra` → the starting theme's own value
  (so e.g. Rasta's red stays red). Their decorations now take their own colours — that's the fix.
- Editing reopens a theme with its pieces and colours. Moderation and ownership are unchanged.

## 5. Mini-player (desktop 0.7.0)

- All 17 skins' decoration colours are rewritten the same way (`decoExtra` included); pieces as in §3 (the six
  undecorated panel themes get one "Glow" piece).
- A custom theme's mini version uses its **starting theme's skin** (`data-theme="<base>"`) with the custom colours as
  inline variables and `data-deco-off` for its pieces — instead of the neutral `custom` look. The mini tweaks (radius
  etc.) still apply; "Match the panel" works the same way. `theme.rs`/`view.js` carry `decorationsOff` and `decoExtra`.
- The creator's mini-player preview uses the real `desktop/ui/mini-player/themes.css`, copied into the bot's web
  resources at build time (`/web/mini-themes.css`), so the preview matches the app.
- A 0.6.0 app with the new bot ignores the new fields and keeps today's behaviour.

## 6. Testing

- **Golden colours:** before any rewrite, a script snapshots every decoration colour of every built-in theme (panel and
  mini-player) into a committed fixture; a test evaluates each rewritten expression (`color-mix`, `rgba(var(--x-r)…)`,
  `var()`) with that theme's slots and requires a match within ±1 per channel.
- **Pieces:** `theme-pieces.json` is the only list — tests check the panel CSS, the mini CSS, the creator, the server
  (`ThemeFile` validation) and the mini-player use exactly its ids; every decoration rule of a decorated theme sits
  under exactly one piece guard; `data-deco-off` with all ids leaves no decoration rule active.
- **Creator:** "Use colours from…" fills all slots; `decoExtra` follows your colours until set by hand; piece switches;
  the picture rule. **Server:** validation; conversion of old themes. **Mini-player:** custom → base skin + colours +
  pieces; old payloads still work.
- **Visual:** all 17 themes, panel and mini-player, before vs after in the preview; plus Fred's hand test: Revival with
  other colours, record on, rays off; one theme of each style.

## 7. Shipping

Branch `theme-decorations`; the bot deploys first (safe with 0.6.0 apps), then desktop 0.7.0 (with the paste-link
button and reopening the last view). Patch note "More control over your themes".
