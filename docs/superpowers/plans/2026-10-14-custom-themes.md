# Custom Themes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Logged-in users create their own themes in a guided creator (optional uploaded background, public or private, optional mini-player version), with owner moderation, applied in the panel and the desktop mini-player.

**Architecture:** A `theme` package on the bot: a validated data model (`ThemeFile`), picture storage with magic-byte detection (`ThemeImages`), and a store with visibility/limit/owner rules (`ThemeStore`), exposed by a self-contained `ThemeRoutes` (testable on a bare Javalin like `PlaylistRoutes`). The panel applies themes as individual CSS properties over a built-in base; palette math lives in a pure, Node-tested `theme-palette.js`. The desktop app lists and applies the mini-player version.

**Tech Stack:** Java 11, org.json, Javalin 6 (multipart), JUnit 4; panel `src/main/resources/web/index.html` + `theme-palette.js` (Node test runner); Tauri 2 / Rust + JS.

**Spec:** `docs/superpowers/specs/2026-10-14-custom-themes-design.md`

## Global Constraints

- `Themes/<id>.json` (folder next to `Playlists/`, from the playlists folder's parent), `id` 12 random `[a-z0-9]`, `"format": 1`, fields exactly as spec §2; atomic writes (temp + `ATOMIC_MOVE`, fallback `REPLACE_EXISTING`), one lock per theme.
- `base` ∈ nebula, aurora, ember, crimson, dawn, dawn-dark, steel, verdant, grove, grapevine, sumi, rasta, cipher, signal, victory, neon, revival. `font` ∈ Inter, DM Sans, Outfit, Syne, Fraunces, IBM Plex Mono, Noto Sans JP, Shippori Mincho.
- `colors`: exactly bgPrimary, bgSecondary, bgCard, bgElevated, bgHover, accent, accentLight, accent2, textPrimary, textSecondary, textTertiary, border, borderLight, success, glass — each `#rrggbb` or `#rrggbbaa`. `mini.colors`: the same minus `glass`.
- `radiusScale` 0–200; `background` {image `[0-9a-f]{32}\.(jpg|png|webp|gif)`, dim 0–90, blur 0–20, fit `cover|contain|center`}; `mini` {colors, useImage, dim 0–90} optional; name 1–40 chars, trimmed, no control characters; `effects` forced false while a background is set.
- Max 10 themes per user; pictures ≤ 8 MB, JPEG `FF D8 FF` / PNG `89 50 4E 47 0D 0A 1A 0A` / GIF `GIF87a|GIF89a` / WebP `RIFF????WEBP`; stored `Themes/images/<32 hex>.<ext>`; served at `GET /theme-images/{name}` without login, with `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`, long immutable cache.
- Owner = the Discord id of the bot's `owner` config (`BotConfig.getOwnerId()`); owner may delete or hide/un-hide public themes, never sees others' private ones; a hidden theme is private with `hiddenByOwner: true` and its creator can't make it public.
- Errors `{error}`: 400 invalid, 403 not allowed, 404 missing or not visible, 409 limit / version changed, 413 too large. Every change broadcasts WS `themes` `{id}` via `broadcastAll`.
- Readability bars: main text ≥ 4.5:1 on bgPrimary and bgCard; secondary text ≥ 3:1 on bgCard.
- Values are applied with `style.setProperty` (never CSS text); names/text via `textContent`.
- Tests: `mvn -o -q test`; `node --test "src/test/js/*.test.js"`; desktop `cargo test --manifest-path desktop/src-tauri/Cargo.toml`, `cd desktop && npm run -s test:js`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Deploy only when nobody is in voice.

## Review Focus

1. A private theme or its picture name reaching another user (in `GET /api/themes`, a WS message, an error text, the owner's view) → never. Pinned in Task 4 (`privateNeverLeaks`: second user's list, the WS payload, a 404 body for a guessed id).
2. A "picture" that isn't one — renamed `.svg`/`.html`/`.exe`, a polyglot starting with JPEG bytes then HTML, an empty file, 8 MB + 1, a client lying about `Content-Type` → refused or served only as an image with `nosniff`/`sandbox`; nothing half-written. Pinned in Tasks 2 and 4.
3. Two people saving the same theme at once, or saving after it was deleted/hidden → 409/404 with a message, no lost write, no orphan picture. Pinned in Task 3 (`concurrentSaves`, `saveAfterDeleteIs404AndPictureRemoved`).
4. A hand-edited or old theme file with bad values (a color `red; background:url(x)`, `radiusScale` 999, unknown base) → skipped with a warning, the rest of the list works. Pinned in Task 3 (`corruptOrInvalidFileSkipped`).
5. Unreadable colors chosen in fine-tune, or very light/very saturated main colors → the generated palette still meets the bars and "Fix it" repairs any pick. Pinned in Task 5 (`manyRandomPairsMeetTheBars`, `fixReadabilityRepairs`).

---

### Task 1: Theme model and validation

**Files:** create `src/main/java/com/jagrosh/jmusicbot/theme/ThemeFile.java`; test `src/test/java/com/jagrosh/jmusicbot/theme/ThemeFileTest.java`.

**Produces:** `ThemeFile` (mutable, getters/setters) with `id, name, owner (Person{id,name}), visibility ("public"|"private"), hiddenByOwner, version (long), createdAt, updatedAt (Instant), base, effects, colors (Map<String,String>), radiusScale (int), font, background (Background{image, dim, blur, fit} | null), mini (Mini{colors, useImage, dim} | null)`; constants `BASES`, `FONTS`, `COLOR_SLOTS` (15, ordered as spec), `MINI_SLOTS` (14), `MAX_NAME = 40`; `static ThemeFile fromJson(String)` (IAE on non-JSON / format ≠ 1 / any invalid value); `String toJson()`; `static ThemeFile valuesFromClient(JSONObject)` (reads only the editable values: name, visibility, base, effects, colors, radiusScale, font, background {dim, blur, fit} without `image`, mini; IAE with a user-facing message naming the bad field); `static String newId(Random)` (12 `[a-z0-9]`).

- [ ] Tests: `roundTrip`; `rejectsBadColors` (`#fff`, `red`, `#12345g`, `#1234567`, `url(x)`, `#000000; x` → IAE); `rejectsOutOfRange` (radiusScale −1/201, dim 91, blur 21, fit `stretch`); `rejectsUnknownBaseAndFont`; `nameRules` (`""`, 41 chars, `"a\u0007b"` → IAE; `"  Midnight  "` → `Midnight`; emoji kept); `missingOrExtraColorSlotsRejected`; `miniOptionalAndValidated`; `effectsForcedOffWithBackground`; `clientCannotSetIdOwnerVersionOrImage` (`valuesFromClient` ignores them).
- [ ] FAIL → implement (org.json) → PASS → full suite → commit `bot: custom theme model`.

### Task 2: Pictures

**Files:** create `theme/ThemeImages.java`; test `ThemeImagesTest.java`.

**Produces:** `ThemeImages(Path dir)`; `static String extensionOf(byte[] head)` → `jpg|png|gif|webp` or null; `String save(InputStream in, long maxBytes)` → the new name (32 random hex + ext) — reads at most `maxBytes + 1` bytes into a temp file in `dir`, refuses (`IllegalArgumentException("That file isn't a picture we can use (JPG, PNG, WebP or GIF)")`, or `PictureTooLargeException("Pictures can be up to 8 MB")`) and deletes the temp file; `void delete(String name)` (quiet if missing); `Optional<Path> resolve(String name)` (only names matching the constraint pattern, inside `dir`); `static String contentType(String name)`.

- [ ] Tests: `detectsRealHeaders` (minimal real JPEG/PNG/GIF87a/GIF89a/WebP byte prefixes); `refusesOthers` (`<svg`, `<!DOCTYPE html`, `MZ`, `%PDF`, empty, `RIFF....WAVE`); `polyglotIsStoredAsTheDetectedImageOnly` (JPEG bytes followed by HTML → saved as `.jpg`; served content type `image/jpeg`); `tooLargeRefusedAndNothingLeft` (8 MB + 1 → exception, `dir` has no new files); `exactlyMaxIsAccepted`; `namesAreRandomAndValid`; `resolveRejectsTraversal` (`../x.jpg`, `a/b.jpg`, uppercase hex, 31 hex, `.svg`).
- [ ] FAIL → implement → PASS → commit `bot: theme pictures`.

### Task 3: Theme store

**Files:** create `theme/ThemeStore.java`, `theme/ThemeForbiddenException.java`; test `ThemeStoreTest.java`.

**Consumes:** Tasks 1–2. **Produces:** `ThemeStore(Path themesDir, ThemeImages images, Clock clock)`; `List<ThemeFile> visibleTo(String viewerId)` (public + viewer's own, sorted by name case-insensitively; corrupt/invalid files skipped with one log warning per file); `Optional<ThemeFile> get(String id, String viewerId)` (not visible → empty); `ThemeFile create(ThemeFile values, Person owner, PictureUpload picture /* nullable */)`; `ThemeFile update(String id, String viewerId, long expectedVersion, ThemeFile values, PictureUpload picture, boolean removeBackground)`; `void delete(String id, String viewerId, boolean viewerIsOwner)`; `ThemeFile hide(String id, boolean viewerIsOwner, boolean hidden)`; `int countOwnedBy(String userId)`; nested `PictureUpload {InputStream in, long declaredSize}`. Errors: IAE (400), `ThemeForbiddenException` (403), `NoSuchElementException` (404), `IllegalStateException` (409 — "You can have up to 10 themes" / "This theme changed — reload it"), `PictureTooLargeException` (413).

Rules: create/update write the picture first (via `ThemeImages.save`, 8 MB), then the JSON; if the JSON write fails, delete the new picture; after a successful update delete the replaced/removed picture; delete removes the picture; `version` +1 and `updatedAt` on every change; only the creator may update/delete their theme (owner may delete or hide public ones; private themes of others are 404 for the owner); a hidden theme can't be made public by its creator (403 "Hidden by the bot owner").

- [ ] Tests: `createAndListOwnAndPublic`; `privateInvisibleToOthersAndOwner`; `limitOfTen` (11th → 409); `onlyCreatorEdits` (other user → 403, owner on a public theme → 403 for edit); `ownerHidesAndUnhides`; `creatorCannotRepublishHidden`; `ownerDeletesPublic`, `ownerCannotSeeOrDeletePrivate` (404); `versionConflict` (stale version → 409, file unchanged); `concurrentSaves` (two threads update with the same version → exactly one wins, the other 409); `pictureReplacedDeletesOld`; `removeBackgroundDeletesPicture`; `failedJsonWriteRemovesNewPicture` (unwritable theme path); `saveAfterDeleteIs404AndPictureRemoved`; `corruptOrInvalidFileSkipped` (`{oops`, a file with color `red; background:url(x)`, `radiusScale` 999 → skipped, others listed, file left on disk).
- [ ] FAIL → implement → PASS → commit `bot: theme store`.

### Task 4: Theme API

**Files:** create `web/ThemeRoutes.java`; modify `web/WebServer.java` (construct `ThemeImages`/`ThemeStore` for `Themes/` next to `Playlists/`, register routes like `registerPlaylistRoutes`, serve `/theme-images/{name}`); test `web/ThemeRoutesTest.java`.

**Consumes:** Task 3, `WebServer.currentUser(Context)`, `WebServer.broadcastAll(String, Object)`, `BotConfig.getOwnerId()`. **Produces:** `ThemeRoutes(ThemeStore store, ThemeImages images, Function<Context, PlaylistFile.Person> currentUser /* converted to ThemeFile.Person; null = not logged in → sees public only */, LongSupplier ownerId, BiConsumer<String, Object> broadcastAll)` + `void register(Javalin app)`; JSON per spec §4: `GET /api/themes` → `{themes:[{id, name, owner, visibility, hiddenByOwner, mine, version, updatedAt, base, effects, colors, radiusScale, font, background: {url: "/theme-images/<name>", dim, blur, fit} | null, mini}], me: {id, isOwner}, limit: {max: 10, used}}`; create/edit multipart parts `theme` (JSON text) and `background` (file), edit also `version` and `removeBackground`.

- [ ] Tests on a bare Javalin with a temp store, two fake users and an owner id, building multipart bodies by hand: `createWithPictureAndList`; `editAndVersionConflict` (409); `privateNeverLeaks` (second user's list, the broadcast payloads, and a 404 body for a guessed id contain neither the theme nor its picture name); `ownerHideAndDelete`, `othersForbidden` (403); `limitIs409`; `badValuesAre400WithFieldMessage`; `fakePictureIs400` (an `.png`-named HTML file with `Content-Type: image/png`); `tooLargeIs413`; `pictureServedWithSafetyHeaders` (no login needed; `nosniff`, the CSP, `image/jpeg`; unknown or malformed names → 404; `..%2F` → 404); `everyChangeBroadcastsThemes`; `everyApiRouteNeedsLogin` (with the real `authMiddleware` registration order, like `PlaylistRoutesTest`).
- [ ] FAIL → implement → PASS + full suite → commit `bot: theme API`.

### Task 5: Palette math

**Files:** create `src/main/resources/web/theme-palette.js` (pure, no DOM; `window.ThemePalette` in the browser, `module.exports` in Node); test `src/test/js/theme-palette.test.js`.

**Produces:** `contrast(hexA, hexB) -> number` (WCAG ratio, alpha composited on black); `generate(accentHex, backgroundHex, mode /* 'dark'|'light' */) -> colors` (the 15 slots); `readability(colors) -> {ok, problems: [slot]}` (bars from Global Constraints); `fixReadability(colors) -> colors` (moves text lightness until the bars hold, keeps hue); `suggestBackgrounds(accentHex, mode) -> hex[4]`; `surprise(randomFn) -> {accent, background, mode}`; `mini(colors) -> 14-slot colors`; `cssVars(colors, base?) -> {"--bg-primary": …}` (incl. derived `--accent-glow` at 32% alpha, `--accent-gradient` accent→accent2, `--accent-r/g/b`, `--accent2-r/g/b`); `suggestDim(averageLuminance /* 0..1 */) -> 0..90`.

- [ ] Tests: `contrastKnownValues` (black/white 21, same color 1); `manyRandomPairsMeetTheBars` (500 seeded accent/background pairs in both modes → `readability(generate(…)).ok`); `fixReadabilityRepairs` (white text on white, mid-grey pairs → ok after fix); `generateKeepsTheAccent`; `surpriseIsReadable`; `miniDropsGlass`; `cssVarsDerivesGlowGradientAndRgb`; `suggestDimDarkensBrightPictures` (1.0 → ≥ 50, 0.1 → ≤ 20).
- [ ] FAIL → implement → PASS → commit `web: theme palette math`.

### Task 6: Panel — picker, applying, background, reduce motion

**Files:** `src/main/resources/web/index.html` (load `theme-palette.js`; theme select ~line 5143; `applyTheme`/`initTheme` ~5411; CSS).

**Consumes:** Task 4 API, Task 5 `cssVars`. **Produces:** `loadCustomThemes()`, `applyThemeChoice(choice)` where `choice` = a built-in id or `custom:<id>`, `openThemeCreator(themeOrNull)` (stub that Task 7 fills), `#themeBackdrop` layer, `data-custom-plain` attribute.

- [ ] Picker groups Built-in / Community ("Name — by creator") / My themes (Private marked) + "＋ Create a theme", Edit/Delete on mine, Hide/Delete for the owner on community themes (in-panel confirm, toast); applying = `data-theme` = base, then `style.setProperty` for each `cssVars` value and radius variables scaled by `radiusScale` from the base's computed values, `--font-family` from `font`, `data-custom-plain` when `effects` is false (CSS hides the base's `body::before/::after` and built-in picture), background layer with dim overlay / blur / fit; switching away clears every custom property; choice stored as `custom:<id>` in `jmusicbot_theme_v1`, a missing/forbidden theme → its base; WS `themes` → reload the list and re-apply the active one; "Reduce motion" switch in settings (`jmusicbot_reduce_motion_v1`, default from `prefers-reduced-motion`) shows animated pictures as their first frame (canvas). Visual check in the static preview with stubbed API answers (a theme on ember with effects off, one with a picture at dim 60/blur 6, an animated GIF with Reduce motion on/off) in nebula/dawn/revival and phone width. `mvn -o -q test`. Commit `web: custom themes in the picker`.

### Task 7: Panel — the guided creator

**Files:** `src/main/resources/web/index.html`.

**Consumes:** Tasks 4–6.

- [ ] Fill `openThemeCreator`: guided window with step dots, Back/Next, live preview on the real page (restored on Cancel with "Discard this theme?"), full-screen at phone width. Steps per spec §6: 1 Start from (built-ins + community cards in their colors); 2 Colors (16 suggested main colors + any color, Dark/Light, `suggestBackgrounds`, `generate`, "Readable ✓" badge, "Surprise me"); 3 Background picture (drop/choose, local preview via object URL, Darken starting at `suggestDim` of the picture's measured average luminance, Blur, Fit with the plain labels, "Animated GIFs work too", big Skip); 4 Fine-tune (collapsed; plain-named color pickers, Corners slider, Font on sample text, Keep special effects only when the base has decorations; unreadable → warning + "Fix it"); 5 a placeholder screen with "Skip" that Task 8 replaces; 6 Name + Public/Private + Save → one multipart request (`POST /api/themes` or `/api/themes/{id}` with `version`), switch to it, toast "Theme saved"; 409 limit → explain + open My themes; 409 changed → "This theme changed — reload it?"; 404 on edit → "Save as a new theme". Editing opens at step 2 prefilled; the creator never trusts its own values over the server's answer. Visual + behaviour check in the static preview with a stubbed API (full create with a picture, edit, limit, conflict), themes nebula/dawn/revival, phone width. Commit `web: guided theme creator`.

### Task 8: Panel — mini-player step

**Files:** `src/main/resources/web/index.html`.

- [ ] Step 5: "Make a mini-player version too?" — Skip (no `mini`) or a live mini-player preview (artwork, title, artist, the transport buttons, styled with the 16 mini variables from `ThemePalette.mini`), its own main/background color pickers (regenerating the mini palette, readability badge), "Use my background picture" + Darken. Save sends `mini` (or none). Visual check of the preview in three base themes. Commit `web: mini-player version in the theme creator`.

### Task 9: Desktop app

**Files:** `desktop/bridge/{core.js,bridge.js}` (+tests), `desktop/src-tauri/src/{bridge.rs,theme.rs,mini_player.rs,settings.rs,main.rs,build.rs}`, `capabilities/mini-player.json`, `desktop/ui/mini-player/{mini.js,view.js,mini.css,index.html}` (+view tests).

**Produces:** `BotCommand::Themes` (fetch → `GET /api/themes`); the bridge's theme report gains the panel's active choice (`builtin id` or `custom:<id>`) and observes inline `style` changes too; Rust `theme::custom_minis(data: &Value) -> Vec<CustomMini>` (only themes with `mini`, colors validated with the same hex rule, groups Community / My themes, capped at 200); mini-player setting `miniPlayerTheme` accepts `custom:<id>`.

- [ ] Tests: JS mapping for `Themes`; the report includes the active choice; Rust `custom_minis` (skips themes without `mini`, invalid colors, groups, cap); match-mode resolution `resolve_mini_theme(panel_choice, customs) -> MiniThemeChoice` (custom with mini → it; without → its base skin; unknown → base/Nebula); view helper that turns a mini palette into the 16 CSS variables; picture layer respects opacity and Reduce motion (view test for the decision, hand check for the canvas freeze). FAIL → implement → PASS.
- [ ] Mini-player theme menu groups Built-in / Community / My themes; applying sets the 16 variables + font and, with `useImage`, the picture from its secret link behind the content (dim, faded by the existing opacity slider); a deleted/hidden theme → its base skin. Visual check in the `mini-player-static` preview with a stubbed Tauri API. Both desktop suites. Commit `desktop: custom themes in the mini-player`.

### Task 10: Ship

- [ ] Final whole-branch review and fix pass. Add a `patch-notes.json` entry for this release (id = release date; app `0.5.0`; Panel: create your own theme with the guided creator, backgrounds incl. animated GIFs, public or private, Reduce motion; Mini-player: custom themes with their own version and picture; App: What's new after updates and in the ☰ menu). Deploy the bot when nobody is in voice; check `/api/themes` (401 without login) and a picture link's headers. After Fred's hand test: bump the desktop to 0.5.0 (`tauri.conf.json`, `Cargo.toml`, `package.json` via `npm version 0.5.0 --no-git-tag-version`), merge, push, tag `desktop-v0.5.0`, verify `latest.json` and the download link.
