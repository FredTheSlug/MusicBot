# Patch Notes ("What's new") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A shared patch-notes list shown as a "What's new" dialog in the panel after an update (and in the desktop app after it updates itself), seeded with notes for the last three updates.

**Architecture:** One JSON file shipped with the panel's static files; a tiny pure JS helper (`whats-new.js`) decides what's unseen, and the panel renders a dialog + header link. The desktop app remembers the version it last ran, shows its main window once after an update, and gets a mini-player menu item that asks the panel page to open the history.

**Tech Stack:** org.json + JUnit 4 (validation test); panel `src/main/resources/web/index.html`; Node's built-in test runner for the new web JS helper; Tauri 2 / Rust + JS (desktop).

**Spec:** `docs/superpowers/specs/2026-10-14-patch-notes-design.md`

## Global Constraints

- File `src/main/resources/web/patch-notes.json`, served at `/web/patch-notes.json`; shape `{"notes":[{id, date, title, app?, items:[{area, text}]}]}`, newest first.
- `id` unique (date, plus `-2`, `-3` for a second entry on one day); `date` `YYYY-MM-DD`; `title` ≤ 60 chars; `area` one of `Panel`, `Mini-player`, `App`, `Discord`; `text` ≤ 300 chars, plain text rendered with `textContent`.
- Seen marker `localStorage` key `jmusicbot_seen_notes_v1` (newest id seen); every access in try/catch.
- Never seen / stored id not in the list → show only the newest entry; otherwise show the unseen entries newest first; "Got it" stores the newest id.
- `window.openWhatsNew()` opens the full history.
- Desktop: settings `lastRunVersion`; show the main window once when it differs from the app version, or is missing while a settings file already existed; never on a fresh install.
- Tests: `mvn -o -q test`; web JS `node --test "src/test/js/*.test.js"`; desktop `cargo test --manifest-path desktop/src-tauri/Cargo.toml`, `cd desktop && npm run -s test:js`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Deploy only when nobody is in voice.

## Review Focus

1. `patch-notes.json` missing, unreachable or broken JSON → the panel works normally, no dialog, no repeated errors. Pinned in Task 2 (`unseenNotes` with null/garbage input → nothing to show).
2. Storage blocked (private window, blocked site data) → the dialog must not pop on every page load; only the header link works. Pinned in Task 2 (`shouldAutoShow` false without storage).
3. The stored id no longer in the list (an entry renamed or removed) → treated as a first visit (newest only), never "everything". Pinned in Task 2.
4. App run for the first time after updating from 0.4.0 (no `lastRunVersion` yet, settings file exists) → counts as updated; a fresh install doesn't. Pinned in Task 3.
5. Note text containing `<`, quotes or emoji → shown literally. Pinned in Task 2 (by hand, with a hostile entry in the preview).

---

### Task 1: The notes file and its validation

**Files:** create `src/main/resources/web/patch-notes.json`; test `src/test/java/com/jagrosh/jmusicbot/web/PatchNotesFileTest.java`.

- [ ] Write `PatchNotesFileTest` (reads the resource from the classpath at `/web/patch-notes.json` with org.json): `parsesAndHasNotes` (≥ 3 entries); `idsUniqueAndNewestFirst` (dates `\d{4}-\d{2}-\d{2}`, parseable, non-increasing; ids unique and equal to the date or `date-N`); `fieldsWithinLimits` (title 1–60, each item area in the four allowed, text 1–300, `app` absent or `\d+\.\d+\.\d+`). Run → FAIL (file missing).
- [ ] Create the file with the three entries of spec §4 (ids `2026-10-14`, `2026-10-13`, `2026-10-11`; titles exactly as in §4; `app` `0.4.0`, `0.3.1`, `0.3.0`), one item per clause of §4, each tagged with its area (Panel / Mini-player / App / Discord), written plainly for friends. Run → PASS; full suite. Commit `web: patch notes for the last three updates`.

### Task 2: "What's new" in the panel

**Files:** create `src/main/resources/web/whats-new.js` (pure, no DOM); test `src/test/js/whats-new.test.js`; modify `src/main/resources/web/index.html` (load the script, header link, dialog, CSS).

**Produces:** `WhatsNew.unseenNotes(list, lastSeenId) -> Array` (list newest first; `lastSeenId` null/unknown → `[newest]`; known → the entries before it; invalid list → `[]`); `WhatsNew.shouldAutoShow(storageOk, unseen) -> boolean` (false when storage is unavailable or nothing is unseen); `window.openWhatsNew()` (full history). The file works in the browser (`window.WhatsNew`) and in Node (`module.exports`).

- [ ] Tests (`node --test "src/test/js/*.test.js"`): `firstVisitShowsOnlyNewest`; `showsEverythingNewerThanSeen` (seen = 3rd → first two); `nothingWhenUpToDate`; `unknownSeenIdIsAFirstVisit`; `garbageListShowsNothing` (`null`, `{}`, `[{}]` without ids → `[]`); `noAutoShowWithoutStorage`. FAIL → implement → PASS.
- [ ] Panel: after login, fetch `/web/patch-notes.json` (failure → silently nothing); read/write the seen marker in try/catch; auto-show the dialog when `shouldAutoShow`; "Got it" stores the newest id; a "What's new" link in `.header-actions` (next to the theme select, ~line 5136) opens the full history, with a dot while something is unseen; dialog: entries newest first — date, title, items grouped by area with a small area label — all via `textContent`; theme CSS variables; Escape closes; phone width; never over the login overlay. Visual check in the static preview (`web-panel-static`) with a stubbed fetch, in themes nebula, dawn, revival, incl. a hostile entry (`<img onerror>`, quotes, emoji) shown literally. `mvn -o -q test`. Commit `web: what's new dialog`.

### Task 3: Desktop app

**Files:** `desktop/src-tauri/src/settings.rs` (`last_run_version: Option<String>`, serde `lastRunVersion`), new `desktop/src-tauri/src/whats_new.rs`, `main.rs` (startup check, command registration), `bridge.rs` (`BotCommand::ShowWhatsNew`), `mini_player.rs` (`mini_show_whats_new`), `build.rs`, `capabilities/mini-player.json`, `desktop/bridge/bridge.js`, `desktop/ui/mini-player/{index.html,mini.js}`.

**Produces:** `whats_new::just_updated(last_run: Option<&str>, current: &str, settings_existed: bool) -> bool`; `BotCommand::ShowWhatsNew` (unit variant, serialized `{"type":"ShowWhatsNew"}`); Tauri command `mini_show_whats_new`.

- [ ] Rust tests: `same_version_is_not_an_update`; `different_version_is_an_update` (incl. a downgrade); `missing_with_existing_settings_is_an_update`; `fresh_install_is_not`; `show_whats_new_serializes` (shape). FAIL → implement → PASS.
- [ ] Wire it: at startup decide with `just_updated` (`settings_existed` = the settings file existed before `load`), call `show_main` once when true, then store the current version. `bridge.js` `run()`: handle `ShowWhatsNew` before the server check and without HTTP — call `window.openWhatsNew` when present, report ok. Mini-player ☰ menu gets "What's new" → `mini_show_whats_new` → `show_main` + send `ShowWhatsNew`. JS test in `desktop/bridge/core.test.js` or a new bridge test only if logic moves into `core.js`. Run both desktop suites. Commit `desktop: what's new after updates`. (Released with the next app version — the custom themes plan's 0.5.0.)

### Task 4: Ship the panel part

- [ ] Final whole-branch review and fix pass; `mvn -o -q test` + `node --test "src/test/js/*.test.js"`; build the jar; deploy when nobody is in voice (backup jar, swap, restart, wait for "Web interface started"); confirm `/web/patch-notes.json` is served and the dialog shows in the panel. No separate entry for this feature itself: the dialog announces itself, and the newest entry (2026-10-14) is what first-time viewers should see. The desktop part ships with 0.5.0.
