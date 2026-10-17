# Theme Decorations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decorations of the 11 decorated themes (and the 17 mini-player skins) take a theme's own colours, can be switched on/off piece by piece, and custom themes' mini versions get their starting theme's decorations — while built-in themes look exactly as before.

**Architecture:** Snapshot every decoration colour first (golden fixture), then rewrite the CSS so each colour is an expression of the theme's slots (`color-mix`, `var()`, the accent triples, plus a new `--deco-extra` slot), each piece's rules guarded by `:not([data-deco-off~="<id>"])`. One list of pieces (`web/theme-pieces.json`) drives the panel, the creator, the server's `ThemeFile` and the mini-player. Custom themes save `decorationsOff` + `colors.decoExtra`; old `effects` converts on read.

**Tech Stack:** panel `src/main/resources/web/index.html` (CSS + inline JS) + pure scripts `theme-palette.js`, `theme-apply.js`, `theme-creator.js`; Node tests (`node --test "src/test/js/*.test.js"`); Java 11 / JUnit 4 (`theme/ThemeFile.java`, `web/ThemeRoutes.java`); desktop `desktop/ui/mini-player/{themes.css,mini.css,view.js,mini.js}` (+ `view.test.js`), Rust `desktop/src-tauri/src/theme.rs`.

**Spec:** `docs/superpowers/specs/2026-10-17-theme-decorations-design.md`

## Global Constraints

- Built-in themes must look the same: every rewritten decoration colour of a built-in theme evaluates (with that theme's slots) to its old value within ±1 per channel (golden test, Task 1).
- Pieces and ids exactly as spec §3's table; `theme-pieces.json` is the single source (format `{ "<theme>": [{ "id", "label", "background": bool }] }`); the six undecorated themes have one mini-only piece `glow`.
- Off switch: `<html data-deco-off="id id">`; piece rules use `html[data-theme="x"]:not([data-deco-off~="id"])`; off removes the piece including its layout effects. `data-custom-plain` is removed.
- `decoExtra` (CSS `--deco-extra`): Rasta `#dc2626`, Cipher `#22d3ee`, Ember `#fde047`, every other theme its accent-light. Custom themes: 16th colour; starts as the starting theme's value; re-derived as accent-light on any colour change unless set by hand in Fine-tune.
- `background: true` pieces (spec §3) are off for a custom theme with a picture.
- Animated pieces stop under the panel's "Reduce motion" and `prefers-reduced-motion` (Ember and the shared `bgShift` included).
- Saved theme: `decorationsOff: [ids]` (unknown ids dropped, duplicates removed, order kept), `colors.decoExtra` `#rrggbb`; `effects` no longer written; old themes convert on read (`effects:false` → all off; else none off; missing `decoExtra` → the starting theme's value). Mini version colours gain `decoExtra`.
- Mini-player: a custom version renders with `data-theme="<base>"` + inline custom colours + `data-deco-off`; a 0.6.0 app keeps working with the new server (only additive fields).
- No real photos change; no new dependencies; CSS `color-mix(in srgb, …)` is allowed (WebView2/modern browsers).
- Tests: `mvn -o -q test`; `node --test "src/test/js/*.test.js"`; `cargo test --manifest-path desktop/src-tauri/Cargo.toml`; `cd desktop && npm run -s test:js`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never kill processes by image name. No push/deploy/tag/version bump inside tasks. Write `\u` escapes as escapes and verify by code point.

## Review Focus

1. A built-in theme rendered after the rewrite → identical colours (golden test) and identical layout (the piece guards add no specificity that loses to other rules). Pinned in Task 1 (golden) and every rewrite task (`<theme>MatchesItsGolden`).
2. All pieces off on a decorated base → no decoration rule active and no leftover gaps (Signal caption gutter, Rasta/Victory header border). Pinned in Tasks 2-4 (`allPiecesOffLeavesNoDecoration`).
3. A custom theme whose colours make `--deco-extra` equal to a background → decorations still visible? (accept: it's the user's colour) but never invisible text: text colours stay text slots. Pinned in Task 4 (`neonTextUsesTheTextSlot`).
4. An old saved theme (effects false/true, no decoExtra) → opens, renders, edits and re-saves in the new form without losing anything. Pinned in Task 5 (`oldThemesConvertOnRead`, `roundTripKeepsEverything`).
5. A custom theme in the 0.6.0 app and in 0.7.0 → 0.6.0 unchanged; 0.7.0 shows base skin + colours + pieces. Pinned in Task 7 (`customMiniUsesItsBaseSkin`, `oldPayloadStillWorks`).

---

### Task 1: Pieces list, golden colours, colour evaluator

**Files:** create `src/main/resources/web/theme-pieces.json`; `src/test/js/css-colors.js` (test helper: parse colours and evaluate expressions); `scripts/theme-golden.js` (Node script that writes the snapshot); fixture `src/test/resources/theme-golden/{panel.json,mini.json}`; test `src/test/js/theme-golden.test.js`.

**Produces:** `theme-pieces.json` (spec §3 table, exact ids/labels, `background` flags). `css-colors.js` exports `evaluate(expr, vars) -> [r,g,b,a]` supporting `#hex`(3/6/8), `rgb()/rgba()` with numbers or `var(--x-r)` triples, `var(--x)` (resolved from `vars`), `color-mix(in srgb, A p%, B [q%])` incl. `transparent`, `white`, `black`, nested; `themeVars(css, themeId)` (the theme block's custom properties over `:root`); `decorationColours(css, themeId)` → `[{ key, expr }]` where `key = normalizedSelector + "|" + property + "|" + n` (normalized = whitespace collapsed, `:not([data-deco-off~=…])` guards removed). The golden files hold, per theme, `{ key: "rgba(r,g,b,a)" }` evaluated from TODAY's stylesheets (panel `index.html` rules under `html[data-theme="x"]` incl. pseudo-elements and the theme's keyframes; mini `themes.css` rules under the skin).
- [ ] Tests: `evaluatorHandlesEveryForm` (hex/rgba/var/triples/color-mix with transparent/white/black/nested); `piecesListMatchesTheSpec` (11 decorated + 6 glow-only, exact ids, background flags); `goldenIsTodaysStylesheet` (re-evaluating today's CSS reproduces the fixture — the test every later task keeps green); `goldenCoversEveryDecoratedTheme`.
- [ ] Generate the fixture with the script BEFORE touching any CSS; commit `themes: decoration pieces and golden colours`.

### Task 2: Shared CSS mechanics + Revival (panel)

**Files:** `index.html` (shared ambient layer, the `--deco-extra` slot in every theme block and `:root`, reduced-motion rules, removal of the `data-custom-plain` block, Revival's block); `theme-palette.js` (`cssVars` emits `--deco-extra`); tests in `src/test/js/theme-golden.test.js`, `theme-palette.test.js`.

**Consumes:** Task 1. **Produces:** the guard pattern; `--deco-extra` per theme; `ThemePalette.cssVars(colors)` includes `'--deco-extra': colors.decoExtra || colors.accentLight`.
- [ ] Revival: all 83 colours as slot expressions (creams/peaches = mixes with white, near-black browns = mixes of bg slots with black), card/header backgrounds via slots, grain matrix + star mask as masks filled with slot colours; its 7 pieces guarded; the record keeps black vinyl grooves.
- [ ] Tests: `revivalMatchesItsGolden`; `everyRevivalDecorationRuleIsUnderOnePiece`; `allPiecesOffLeavesNoDecoration` (revival); `reducedMotionStopsEveryAnimatedPiece` (panel CSS: every animation in a decorated theme's rules has a reduced-motion override); `cssVarsHasDecoExtra`. Visual check by the controller.
- [ ] Commit `themes: Revival decorations follow the theme's colours`.

### Task 3: Panel — Neon, Signal, Victory, Cipher

**Files:** `index.html` (those four blocks); tests in `theme-golden.test.js`. **Consumes:** Task 2's pattern.
- [ ] Rewrite colours + guards per spec §3 pieces (Neon's white text → `var(--text-primary)`; Signal's caption gutter belongs to `caption`; Cipher's cyan → `--deco-extra`).
- [ ] Tests: `<theme>MatchesItsGolden` ×4, `everyDecorationRuleIsUnderOnePiece` ×4, `allPiecesOffLeavesNoDecoration` ×4 (incl. Signal's gutter gone), `neonTextUsesTheTextSlot`.
- [ ] Commit `themes: Neon, Signal, Victory and Cipher decorations follow the theme's colours`.

### Task 4: Panel — Ember, Rasta, Sumi, Grove, Steel, Dawn Dark

**Files:** `index.html`; tests. **Consumes:** Task 2's pattern.
- [ ] Ember flames: inline SVGs become one flame-shape mask (data URI, no colours) over slot gradients (accent / accent2 / `--deco-extra` core); flicker under reduced motion stops. Rasta red → `--deco-extra`; Rasta/Victory header border restored when `stripe`/`rule` is off. Sumi kanji tile → mask filled with `--text-primary` at its alpha. Grove photo stays; veil → `--bg-card`. Steel/Dawn Dark: their ambient tuning becomes the `wash` piece.
- [ ] Tests as Task 3 for the six themes; `emberFlamesAreMasksNotColours` (no colour literal inside the flame data URI).
- [ ] Commit `themes: Ember, Rasta, Sumi, Grove, Steel and Dawn Dark decorations follow the theme's colours`.

### Task 5: Saving pieces and the extra colour (server + panel apply)

**Files:** `theme/ThemeFile.java` (`decorationsOff`, `decoExtra` in `COLOR_SLOTS` and the mini colours, conversion of old files), `web/ThemeRoutes.java` (validation via the pieces list), a small `theme/ThemePieces.java` reading `/web/theme-pieces.json` from the classpath; `theme-apply.js` (`decoOffFor(theme) -> string` incl. the picture rule); `index.html` `applyCustomTheme` (sets/clears `data-deco-off`, drops `data-custom-plain`); tests `ThemeFileTest`, `ThemeRoutesTest`, `theme-apply.test.js`.

**Produces:** `ThemeFile.getDecorationsOff(): List<String>`; JSON `decorationsOff` + `colors.decoExtra` (+ mini `colors.decoExtra`); `ThemeApply.decoOffFor(theme, hasPicture) -> "id id"`.
- [ ] Tests: `unknownPieceIdsAreDropped`, `duplicatesRemovedOrderKept`, `decoExtraMustBeAColour`, `oldThemesConvertOnRead` (effects false → all off; true/absent → none; missing decoExtra → the base's value from the pieces list/table), `roundTripKeepsEverything`, `effectsIsNoLongerWritten`, `aPictureTurnsBackgroundPiecesOff`, `applyCustomThemeSetsDecoOff` (inline-script parse + apply test as the panel tests do).
- [ ] Commit `themes: save pieces and the extra decoration colour`.

### Task 6: Creator — colours from any theme, piece switches, extra colour

**Files:** `theme-creator.js` (state: `decorationsOff`, `decoExtraSetByHand`; actions `useColoursFrom(themeId)`, `setPiece(id, on)`, `allPieces(on)`, `setDecoExtra(hex)`; `buildValues` writes `decorationsOff` + `decoExtra`; BASE_PALETTES gain `decoExtra`), `index.html` (Colors step chips "Use colours from…", Fine-tune piece switches replacing "Keep special effects", live preview with `data-deco-off`), `pom.xml` (copy `desktop/ui/mini-player/themes.css` to `web/mini-themes.css` at build) + the creator's mini preview using it; tests `theme-creator.test.js`.
- [ ] Tests: `useColoursFromFillsEverySlot` (incl. decoExtra); `decoExtraFollowsColoursUntilSetByHand`; `piecesStartFromTheSavedTheme`; `allOnAllOff`; `pictureForcesBackgroundPiecesOff`; `baseDecoExtraTable` (Rasta/Cipher/Ember values, others accentLight); `startingPalettesStillMatchTheStylesheet` (existing test extended to decoExtra). Visual check by the controller (creator flow: Revival + Nebula colours, record on, rays off).
- [ ] Commit `themes: creator picks pieces and colours from any theme`.

### Task 7: Mini-player skins and custom versions (desktop)

**Files:** `desktop/ui/mini-player/themes.css` (17 skins' colours as slot expressions, pieces guarded, `--deco-extra`), `mini.css` (shared), `view.js` (`applyTheme`: custom → `data-theme=<base>` + vars + `data-deco-off`), `desktop/src-tauri/src/theme.rs` (`CustomMini` carries `decorations_off`, `deco_extra`; parse new fields, old payloads still parse), tests `view.test.js`, Rust tests; the mini golden from Task 1.
- [ ] Tests: `<skin>MatchesItsGolden` (mini, 17); `everyMiniDecorationRuleIsUnderOnePiece`; `customMiniUsesItsBaseSkin` (replaces the old "custom has no rule" test); `decoOffReachesTheMini`; `oldPayloadStillWorks` (Rust + JS: a 0.6.0-shaped theme payload renders as before); reduced motion still stops all animation.
- [ ] Commit `desktop: mini-player decorations follow the theme's colours and pieces`.

### Task 8: Patch note

- [ ] New FIRST `patch-notes.json` entry, by text in the file's style: id = the local date when written (`-N` if taken), title "More control over your themes", `app` "0.7.0"; items: decorations follow your colours; switch decorations piece by piece; start colours from any theme; custom themes' mini-player versions get their theme's decorations (needs 0.7.0); plus the 0.7.0 mini-player items (Play link from clipboard button; the app reopens the view you used last). `PatchNotesFileTest` + Node green → commit `patch notes: theme control and the 0.7.0 app`.
