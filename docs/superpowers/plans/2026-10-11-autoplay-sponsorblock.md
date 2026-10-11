# Autoplay & SponsorBlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-server autoplay (YouTube mix radio when the queue runs out) and SponsorBlock (skip non-music sections), with toggles in the panel, Discord and the mini-player.

**Architecture:** Two small audio helpers (`Autoplay`, `SponsorBlock`) with pure, tested logic; `AudioHandler` calls them at track end/start; settings persisted per guild; web endpoints + panel buttons + Discord commands; the desktop app maps the autoplay flag and toggle.

**Tech Stack:** Java 11, JDA 6, lavaplayer 2.2.7 (TrackMarker), youtube-source (mix loading), Javalin 6, JUnit 4; desktop app (Tauri/JS).

**Spec:** `docs/superpowers/specs/2026-10-11-autoplay-sponsorblock-design.md`

## Global Constraints

- Defaults: autoplay **off**, SponsorBlock **on**. JSON keys `autoplay` (written only when true) and `sponsorblock` (written only when false).
- SponsorBlock: category `music_offtopic` only; segments < 1000 ms dropped; overlaps merged; max 20; HTTP timeout 5 s; never throws into the audio thread.
- Autoplay: one related track per empty queue; never after `stopAndClear`, never while the default playlist has tracks, never with repeat on; requester shown as "Autoplay"; skips the current song and the last 50 history entries.
- Tests: `mvn -o -q test`; desktop `cargo test …` and `npm run -s test:js`. Deploy only when nobody is in voice (usual routine); Fred pre-approved ("auto approve").
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Autoplay after an explicit stop, the bot leaving voice, or a skip of the last song → only the skip continues. Pinned in Task 2 (flag + by hand).
2. A mix that only contains songs already played, or loads nothing → playback ends normally, no loop. Pinned in Task 2 (`pick` returns -1).
3. SponsorBlock answering after the song changed, or a segment covering the start/end of the song → seeks only the song it was fetched for; a 0:00 intro is skipped at once. Pinned in Task 3.
4. Hostile/odd SponsorBlock JSON (wrong categories, huge lists, negative/inverted times) → ignored safely. Pinned in Task 3.
5. Older `serversettings.json` / older bot without the new fields in the app → defaults, nothing breaks. Pinned in Tasks 1 and 5.

---

### Task 1: Settings, web toggles, Discord commands

**Files:** `settings/Settings.java`, `settings/SettingsManager.java`, `web/WebServer.java`, new `commands/dj/AutoplayCmd.java`, `commands/dj/SponsorblockCmd.java`, `JMusicBot.java`; test `settings/SettingsExtrasTest.java`.

**Produces:** `Settings.getAutoplay()/setAutoplay(boolean)`, `getSponsorBlock()/setSponsorBlock(boolean)`; `SettingsManager.readExtras(JSONObject, Settings)` and `writeExtras(Settings, JSONObject)` (static, package-visible); POST `/api/guild/{id}/autoplay` → `{autoplay}`, POST `/api/guild/{id}/sponsorblock` → `{sponsorBlock}`; now-playing payload `autoplay`, `sponsorBlock`.

- [ ] Test `extrasDefaultAndRoundTrip`: empty object → autoplay false, sponsorBlock true; write with defaults → no keys; autoplay on + sponsorBlock off → `{"autoplay":true,"sponsorblock":false}` → read back equal.  FAIL → implement → PASS → commit `bot: autoplay and SponsorBlock settings, toggles and commands`.

### Task 2: Autoplay

**Files:** new `audio/Autoplay.java`; `audio/AudioHandler.java`; test `audio/AutoplayTest.java`.

**Produces:** `Autoplay.isYoutubeId(String)`, `Autoplay.mixUrl(String id)`, `Autoplay.pick(List<String> candidates, Set<String> recent, String current) -> int`, `Autoplay.queueRelated(AudioHandler, AudioTrack ended)` (async); `AudioHandler` `stopping` flag in `stopAndClear`.

- [ ] Tests: `youtubeIds` (11 chars `[A-Za-z0-9_-]` only), `mixUrl` (`https://www.youtube.com/watch?v=ID&list=RDID`), `pickSkipsCurrentAndRecent` (returns first fresh index; all stale → -1; empty → -1).  FAIL → implement (+ onTrackEnd hook) → PASS → commit `bot: autoplay related songs when the queue runs out`.

### Task 3: SponsorBlock

**Files:** new `audio/SponsorBlock.java`; `audio/AudioHandler.java` (`onTrackStart`); test `audio/SponsorBlockTest.java`.

**Produces:** `SponsorBlock.Segment(long startMs, long endMs)`; `parseSegments(String json) -> List<Segment>`; `nextStep(List<Segment>, long positionMs) -> Step(Long seekTo, Long markAt)`; `apply(AudioTrack, AudioHandler)` (fetch async, then markers on that track only).

- [ ] Tests: `parsesMusicOfftopicOnly`, `mergesOverlapsAndDropsTiny`, `ignoresJunk` (invalid JSON, inverted/negative times, > 20 segments capped), `nextStepBeforeInsideAfter`.  FAIL → implement → PASS → commit `bot: skip non-music sections with SponsorBlock`.

### Task 4: Panel buttons

**Files:** `src/main/resources/web/index.html`.

- [ ] Add `renderAutoplayButton(data)` / `renderSponsorBlockButton(data)` next to shuffle/repeat (same `ctrl-btn` + an `*-active` class), `toggleAutoplay()` / `toggleSponsorBlock()` POSTs, include both flags in the now-playing render key; check in the static preview across a few themes. Commit `web: autoplay and SponsorBlock buttons`.

### Task 5: Desktop app autoplay switch

**Files:** `desktop/bridge/core.js` (+test), `desktop/src-tauri/src/state.rs`, `bridge.rs`, `desktop/ui/mini-player/*`.

**Produces:** JS `toBotState().autoplay` (`true`/`false`/`null` when the bot doesn't report it); Rust `BotState.autoplay: Option<bool>`; `BotCommand::ToggleAutoplay` → POST `/autoplay`; Up Next header switch (hidden when `autoplay` is null).

- [ ] Tests: JS `autoplay flag maps and toggles` (true/false/missing → null; command → POST `/api/guild/9/autoplay`); Rust `old_state_payload_gets_defaults` extended (autoplay None).  FAIL → implement → PASS → commit `desktop: autoplay switch in Up Next`.

### Task 6: Review, deploy, hand-off

- [ ] Final whole-branch review; fix pass; build jar; deploy when idle; verify `/api/guild/…/nowplaying` carries the flags (logged in) and the panel shows the buttons; start the dev app; tell Fred what to test.
