# Playlists v2 (round 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JSON playlists with real song details, safe simultaneous edits, a 7-day Deleted folder, one-time conversion of the 72 `.txt` playlists, link import, and the panel/mini-player experience to build playlists while listening.

**Architecture:** A new `playlist` model + store (pure file I/O, unit-tested in temp dirs) behind a `TrackResolver` interface (real: lavaplayer + SpotifyResolver; tests: fake). A converter worker and an import-job manager use the resolver. A self-contained `PlaylistRoutes` class registers the HTTP API on Javalin (testable on a bare Javalin like `WebAuthMiddlewareTest`). Panel and mini-player call the new API; old endpoints become thin wrappers for one release.

**Tech Stack:** Java 11, org.json (as `SettingsManager`), lavaplayer 2.2.7, Javalin 6, JUnit 4; panel `src/main/resources/web/index.html`; desktop app (Tauri/Rust, JS).

**Spec:** `docs/superpowers/specs/2026-10-13-playlists-v2-design.md`

## Global Constraints

- File: `Playlists/<folder>/<name>.json`, `"format": 2`, fields exactly as spec §2; song `id` = 8 random chars from `[a-z0-9]`.
- Limits: 2000 songs per playlist; text fields cut to 300 chars; at most 3 import jobs overall, 1 per user.
- Reserved folders `.deleted`, `.legacy-txt` (never listed, rejected as names). Name rules as `PlaylistLoader.resolvePlaylistFile` (no `/`, `\`, NUL; inside `Playlists/`).
- Deleted entries purged when `deletedAt` is more than 7 days old; checked at startup and hourly. Restore clash → `Name (restored)`.
- Writes are atomic (write `*.tmp` in the same folder, then `Files.move` with `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING`).
- Converter: one network load at a time; `.txt` moved to `.legacy-txt/` only after its `.json` is written; `.txt` keeps playing until converted.
- Old endpoints (`/api/playlist/*`, `/api/playlists`, `/api/playlists-tree`, `/api/playlist-detail/*`, `/api/guild/{id}/playplaylist/*`, folder endpoints) keep working for one release.
- Discord: `playlists`, `play playlist <Folder/Name>`, default playlist and `autoplaylist` use the store; `playlist make/append/delete/…` reply `Edit playlists in the panel: <panel URL>`.
- Tests: `mvn -o -q test`; desktop `cargo test --manifest-path desktop/src-tauri/Cargo.toml`, `cd desktop && npm run -s test:js`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Deploy only when nobody is in voice.

## Review Focus

1. Two friends editing the same playlist at the same moment (add while someone reorders/removes) → both changes kept, no song lost or duplicated. Pinned in Task 3 (interleaved ops) and Task 8 (two concurrent HTTP adds).
2. Conversion meeting a dead/private YouTube link, a Spotify link without Spotify configured, or a bot restart halfway → nothing lost: unresolved entries, resumes, `.txt` untouched until its `.json` exists. Pinned in Task 5.
3. Names with quotes, emoji, `..`, slashes or the reserved folder names, in create/rename/move/restore → rejected or stored safely, never escaping `Playlists/`. Pinned in Task 2 and Task 4.
4. Restoring a deleted playlist whose name or folder was reused or removed meanwhile → restored as `Name (restored)`, folder recreated. Pinned in Task 4.
5. A huge import (a 1000-song YouTube playlist, a Spotify playlist at the resolver cap) or someone spamming imports → capped, one job per user, panel shows progress, bot stays responsive. Pinned in Task 7.

---

### Task 1: Playlist file model

**Files:** create `src/main/java/com/jagrosh/jmusicbot/playlist/PlaylistFile.java`; test `src/test/java/com/jagrosh/jmusicbot/playlist/PlaylistFileTest.java`.

**Produces:** `PlaylistFile` (mutable, package fields via getters) with `name, version (long), createdBy (Person|null), createdAt, updatedAt (Instant), shuffle, songs (List<Song>)`; nested `Song {id, uri, title, author, durationMs, artworkUrl, addedBy (Person|null), addedAt, unresolved}`, `Person {id, name}`; `static PlaylistFile fromJson(String)` (throws `IllegalArgumentException` on non-JSON / wrong format), `String toJson()` (pretty, 2-space); `static String newSongId(Random)`; `static String cut(String)` (300 chars).

- [ ] Tests: `roundTrip` (all fields survive toJson→fromJson); `capsAndDefaults` (missing optional fields → null/false/0; 400-char title → 300; songs beyond 2000 dropped on read); `rejectsJunk` (`"x"`, `{"format":1}` → IAE); `songIdsAreEightLowercaseAlnum`.
- [ ] FAIL → implement (org.json) → PASS → commit `bot: playlist v2 file model`.

### Task 2: PlaylistStore — playlists and folders

**Files:** create `playlist/PlaylistStore.java`; test `playlist/PlaylistStoreTest.java`.

**Consumes:** Task 1. **Produces:** `PlaylistStore(Path root, Clock clock)`; `List<Entry> tree()` where `Entry {name, folder ("" = top), songCount, durationMs, createdBy, updatedAt, legacy (true for an unconverted .txt)}` plus `List<String> folders()`; `Optional<PlaylistFile> get(String name, String folder)` (JSON, else a read-only view of a legacy `.txt` via the old line format with `unresolved` songs = its lines); `PlaylistFile create(String name, String folder, Person by)`; `rename(name, folder, newName)`; `move(name, folder, toFolder)`; `createFolder(path)`; `Path pathFor(name, folder)` (validates; throws `IllegalArgumentException`). All mutations take a per-playlist lock (`ConcurrentHashMap<Path,Object>`), bump `version`/`updatedAt`, write atomically, and return the new `PlaylistFile`.

- [ ] Tests: `createGetRenameMove` (folder `"Dr. Dre"`, name `"The Chronic"` — files land where expected, `.txt` legacy entries listed with `legacy=true`); `rejectsBadNames` (`"../x"`, `"a/b"`, `"a\\b"`, `".deleted"`, `".legacy-txt"`, `""`, NUL → IAE; `"Ke$ha \"Tik\" 🎵"` accepted); `reservedFoldersHidden` (`.deleted/`, `.legacy-txt/` never in `tree()`/`folders()`); `writesAreAtomic` (no `*.tmp` left behind; a pre-existing `.json` is replaced); `corruptFileSkipped` (a `.json` holding `{oops` is left on disk, missing from `tree()`, and `get` returns empty).
- [ ] FAIL → implement → PASS → commit `bot: playlist store`.

### Task 3: Song operations

**Files:** `PlaylistStore.java`; test `PlaylistStoreSongsTest.java`.

**Produces:** `addSongs(name, folder, List<Song> songs, Integer index, Person by)` (assigns ids, `addedBy/addedAt`, caps at 2000 → `IllegalStateException("Playlist is full")`); `removeSong(name, folder, songId)` → removed `Song` + its index (for Undo); `moveSong(name, folder, songId, toIndex)` (clamped); `setShuffle(name, folder, boolean)`; `replaceSong(name, folder, songId, Song resolved)` (retry). Unknown playlist/song → `NoSuchElementException`.

- [ ] Tests: `addAtEndAndIndex`; `removeReturnsIndexForUndo`; `moveClamps`; `fullPlaylistRejectsMore`; `interleavedEditsKeepEverything` — load the playlist twice ("two friends"), friend A removes song X by id, friend B moves song Y by id and adds song Z → final list has no X, Y at its new place, Z present, nothing else changed; `concurrentAddsFromThreads` (8 threads × 25 adds → 200 songs, unique ids).
- [ ] FAIL → implement → PASS → commit `bot: playlist song operations by id`.

### Task 4: Deleted folder

**Files:** `PlaylistStore.java`; test `PlaylistTrashTest.java`.

**Produces:** `delete(name, folder, Person by)` → file moved to `.deleted/<epochMillis>-<name>.json` with `deletedBy, deletedAt, originalFolder`; `deleteFolder(path, Person by)` (each playlist to Deleted, then the empty folder removed); `List<Deleted> deleted()` `{id (file name), name, originalFolder, deletedBy, deletedAt, daysLeft}`; `PlaylistFile restore(String deletedId)` (recreates folder; clash → `Name (restored)`, then `Name (restored 2)`…); `int purgeExpired()` (older than 7 days by the store's `Clock`).

- [ ] Tests: `deleteAndRestore`; `restoreClashGetsRestoredSuffix` (and a second clash → `(restored 2)`); `restoreRecreatesMissingFolder`; `purgeAfterSevenDays` (fake clock: 6d23h kept, 7d1m purged); `deleteFolderSendsEachPlaylist`; `restoreRejectsForgedIds` (`"../x"` → IAE).
- [ ] FAIL → implement → PASS → commit `bot: Deleted folder with weekly purge`.

### Task 5: Resolver and one-time conversion

**Files:** create `playlist/TrackResolver.java` (interface), `playlist/LavaTrackResolver.java`, `playlist/PlaylistConverter.java`; test `PlaylistConverterTest.java`.

**Produces:** `interface TrackResolver { Resolved resolve(String lineOrLink); }` with `Resolved {List<Song> songs, String title, String error}` (blocking; the real one uses `PlayerManager.loadItemOrdered` with a 30 s timeout, `SpotifyResolver` for Spotify links → each query's first YouTube result, `ytsearch:` lines → first result, playlists capped at 2000); `PlaylistConverter(PlaylistStore, TrackResolver)` with `int convertPending()` (converts every legacy `.txt`, one resolver call at a time; per playlist writes `.json` atomically, then moves `.txt` to `.legacy-txt/<folder>/`; `#shuffle` → `shuffle=true`; failed lines → `unresolved` songs with the original text as `title`) and `Progress progress()` `{total, done, failed}`.

- [ ] Tests (fake resolver): `albumLinkExpandsToSongs`; `failedLineBecomesUnresolved`; `shuffleLineSetsFlag`; `txtMovedOnlyAfterJsonWritten` (resolver throws mid-file → `.txt` still in place, no `.json`); `resumesAfterRestart` (two playlists, first converted, new converter instance converts only the second); `progressCounts`.
- [ ] FAIL → implement → PASS (`LavaTrackResolver` covered by hand in Task 12) → commit `bot: convert .txt playlists to v2`.

### Task 6: Playing and Discord

**Files:** create `playlist/PlaylistPlayer.java`; modify `audio/AudioHandler.java` (`playFromDefault`), `commands/music/PlayCmd.java` (playlist subcommand), `commands/music/PlaylistsCmd.java`, `commands/owner/AutoplaylistCmd.java`, `commands/owner/PlaylistCmd.java`, `Bot.java` (construct store/converter/resolver; start the converter on a background thread and the hourly purge); test `PlaylistPlayerTest.java`.

**Produces:** `PlaylistPlayer.order(PlaylistFile pl, String mode /* all|shuffle|from */, String songId, Random rnd) -> List<Song>` (skips unresolved; `from` = that song first, then the ones after it, then the ones before; `shuffle` flag or mode shuffles); `PlaylistStore.find(String nameOrPath)` (`"Folder/Name"` or a unique bare name); queuing loads each `uri` via `loadItemOrdered` (no searches).

- [ ] Tests: `orderAllShuffleFrom`; `skipsUnresolved`; `findByFolderPathOrUniqueName` (ambiguous bare name → empty).
- [ ] FAIL → implement, rewire the commands (PlaylistCmd editing subcommands reply with the panel URL from `BotConfig`) → PASS + full `mvn -o -q test` → commit `bot: play v2 playlists everywhere`.

### Task 7: Import jobs

**Files:** create `playlist/ImportJobs.java`; test `ImportJobsTest.java`.

**Produces:** `ImportJobs(PlaylistStore, TrackResolver, Executor, Consumer<Progress> onProgress)`; `String start(String link, String name, String folder, String into, Person by)` (http(s) only; `IllegalStateException` when this user has a job running or 3 are running); songs appended as each batch resolves; `Progress {jobId, done, total, failedCount, playlistName, folder, finished, error}`.

- [ ] Tests (fake resolver, direct executor): `importCreatesPlaylistNamedAfterSource`; `importIntoExistingAppends`; `onePerUserThreeOverall`; `rejectsNonHttp`; `capsAt2000`.
- [ ] FAIL → implement → PASS → commit `bot: playlist import jobs`.

### Task 8: HTTP API

**Files:** create `web/PlaylistRoutes.java`; modify `web/WebServer.java` (register routes; `broadcastAll(String type, Object data)` sending to every WS client with that client's own `guildId` in the payload; a package-visible `currentUser(Context) -> PlaylistFile.Person`; old endpoints delegate to the store); test `web/PlaylistRoutesTest.java`.

**Consumes:** Tasks 2–7. **Produces:** the endpoints of spec §4, JSON bodies as listed there; errors → `{error}` with 400 (IAE), 404 (`NoSuchElementException`), 409 (`IllegalStateException`); every change → `broadcastAll("playlists", {name, folder, version} or {tree:true})`; import progress → `broadcastAll("playlist-import", progress)`.

- [ ] Tests on a bare Javalin with a temp-dir store and a fixed user (like `WebAuthMiddlewareTest`): `createAddMoveRemove`; `badNameIs400AndMissingIs404`; `twoConcurrentAddsBothLand` (two threads POST `/songs/add` at once → both songs present); `deleteRestoreViaApi`; `migrationProgressEndpoint`; `oldEndpointsStillWork` (`/api/playlists-tree` shape and `/api/playlist-detail/{name}` items for a v2 playlist).
- [ ] FAIL → implement → PASS + full suite → commit `bot: playlists v2 API`.

### Task 9: Panel — "Add to playlist" everywhere

**Files:** `src/main/resources/web/index.html`.

- [ ] Add a ＋ button to the now-playing card, queue rows, History, Most played and Add Music search results; a picker popup (filter box, recently used first — remembered in `localStorage` — folder path beside each name, "+ New playlist…") calling `/api/playlists/songs/add` with the row's `{uri,title,author,durationMs,artworkUrl}`. Text rendered with the existing `escHtml`/`escHtmlAttr`. Visual check in the static preview (themes nebula, dawn, revival) with injected sample data. Commit `web: add to playlist from anywhere`.

### Task 10: Panel — playlist page, adding, import, Deleted

**Files:** `src/main/resources/web/index.html`.

- [ ] Replace the detail view: header (name, creator, count, total length; Play, Shuffle, Add songs, Rename, Move, Delete), rows (thumbnail, title, artist, length, "added by" tooltip, drag handle → `/songs/move`, ▶ play from → play mode `from`, ⋯ Play next / Add to another playlist / Remove with a 6 s Undo → `/songs/add` at the old index), unresolved rows "Couldn't load" + Retry; inline "Add songs" search (Add per result; pasted link → "Import N songs from …" via `/api/playlists/import`); "New playlist from link" atop the tree; "Deleted (n)" section with Restore; migration banner from `/api/playlists/migration`; live refresh on `playlists` / `playlist-import` WS messages. Visual check as Task 9 (+ drag reorder in the browser preview). Commit `web: playlist page, import and Deleted folder`.

### Task 11: Desktop app

**Files:** `desktop/bridge/core.js` (+test), `desktop/src-tauri/src/bridge.rs`, `lookup.rs`, `mini_player.rs`, `main.rs`, `build.rs`, capability; `desktop/ui/mini-player/*` (+view tests).

**Produces:** `BotCommand::{PlaylistsTreeV2, AddToPlaylist{name, folder, song}, CreatePlaylist{name, folder}}` → `GET /api/playlists/tree`, `POST /api/playlists/songs/add`, `POST /api/playlists`; `PlayPlaylist` → `POST /api/guild/{id}/playlists/play {mode:"all"}` (fall back to the old `/playplaylist` when the bot answers 404); mini-player ＋ in the bottom row (current song) and on menu search results opening a playlist picker in the drawer.

- [ ] Tests: JS mapping for the three commands and the fallback; Rust tree mapping (caps) and command shapes; view helper for the picker's filter/recent ordering. FAIL → implement → PASS → visual check → commit `desktop: add songs to playlists from the mini-player`.

### Task 12: Deploy, conversion check, hand-off

- [ ] Final whole-branch review and fix pass; back up `~/Playlists` on the server (`tar czf ~/Playlists-2026-10-13.tgz Playlists`), deploy when idle, watch the converter in the log/banner until 72/72 (note failures), spot-check three converted albums in the panel, import one YouTube and one Spotify playlist, drag-reorder in the app; then the desktop release (version bump, tag) after Fred's hand test.
