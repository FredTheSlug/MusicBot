# Playlists Round 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-person ♥ Liked songs (visible to friends, playable, reorderable), "♥ liked by" on now playing, "Play everyone's likes", visible "added by" on playlist rows and the mini-player, and reordering without a mouse.

**Architecture:** A `LikesStore` wraps a second `PlaylistStore` rooted at the hidden `Playlists/.liked` folder (one playlist per Discord id), so likes reuse round 1's file format, locks, song ops and playing. A self-contained `LikeRoutes` exposes them (testable on a bare Javalin like `PlaylistRoutes`), sharing song-input validation and Undo tokens extracted from `PlaylistRoutes`. The panel adds pure Node-tested helpers plus UI; the desktop app adds bridge commands and mini-player UI.

**Tech Stack:** Java 11 (runs on 21), org.json, Javalin 6, JUnit 4; panel `src/main/resources/web/index.html` + small pure scripts tested with Node 22; Tauri 2 / Rust + JS.

**Spec:** `docs/superpowers/specs/2026-10-14-playlists-round2-design.md`

## Global Constraints

- Likes file `Playlists/.liked/<discordUserId>.json`, playlist format 2 (`PlaylistFile`), created on the first like; `.liked` is reserved like `.deleted`/`.legacy-txt` (never listed, rejected as a name, skipped by converter and purge).
- New likes go to the top; liking an already-liked `uri` changes nothing; unliking removes every entry with that `uri`; cap 2000 with the message `You've liked 2000 songs — remove some to like more`; `createdBy` = owner `{id, name}`, refreshed on every change.
- Only the session user's own list ever changes; the user id comes from the login session, never from the request.
- API exactly as spec §3 (paths, bodies, answers); errors `{error}` 400/404/409/500; every change broadcasts WS `likes` `{userId}` (no song data); `userId: "everyone"` = all likes merged, duplicates by `uri` dropped, shuffled, capped at 2000.
- Song input: `uri` http(s) or `ytsearch:`/`ytmsearch:`/`scsearch:` query; a local file only when the identical uri is already stored in some playlist or someone's likes; `artworkUrl` only if http(s).
- Names and song text render as text only (textContent / bidi-isolated where a name sits next to other text).
- Tests: `mvn -o -q test`; `node --test "src/test/js/*.test.js"`; desktop `cargo test --manifest-path desktop/src-tauri/Cargo.toml`, `cd desktop && npm run -s test:js`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- This round is NOT pushed, deployed or released (Fred: "merge but don't release public"): merge into local `main` only; no `git push`, no server deploy, no tag, no version bump.

## Review Focus

1. Someone changing another person's likes — a forged `userId` in a body, the `{userId}` path, `everyone`, path tricks — → never; other lists are read-only. Pinned in Task 2 (`onlyYourOwnListChanges`, `userIdPathIsValidated`).
2. Double-clicking ♥ fast (like/unlike/like racing) → one entry at most, final state = last click. Pinned in Task 1 (`concurrentLikesOfOneSongMakeOneEntry`) and Task 3 (`latestToggleWins`).
3. Liking a local-file song, a search result, a stream → the uri rules apply with a clear message. Pinned in Task 2 (`songInputRulesApply`).
4. A corrupt likes file, or someone at 2000 likes → a clear message, nothing silently lost. Pinned in Task 1 (`brokenFileIsKeptAndANewListStarts`, `fullListRefusesWithItsMessage`).
5. Display names with HTML, bidi controls or that change over time → shown as text, isolated, current. Pinned in Task 1 (`ownerNameIsRefreshed`) and Task 5 (`likesLabelIsIsolatedText`).

---

### Task 1: LikesStore

**Files:** create `src/main/java/com/jagrosh/jmusicbot/playlist/LikesStore.java`; modify `playlist/PlaylistStore.java` (`.liked` added to the reserved folders); test `src/test/java/com/jagrosh/jmusicbot/playlist/LikesStoreTest.java` (+ one case in `PlaylistStoreTest`, one in `PlaylistConverterTest`).

**Produces:** `LikesStore(Path likesDir, Clock clock)` (internally `new PlaylistStore(likesDir, clock)`, playlist name = the Discord id, folder `""`); `Optional<PlaylistFile> get(String userId)`; `Set<String> uris(String userId)`; `int like(Person owner, Song song)` (returns the new count); `int unlike(Person owner, String uri)`; `PlaylistStore.Removed removeSong(Person owner, String songId)`; `PlaylistFile reinsert(Person owner, Song song, int index)`; `PlaylistFile move(Person owner, String songId, int toIndex)`; `List<Liker> people()` with immutable `Liker {id, name, count, durationMs, updatedAt}`; `List<String> whoLiked(String uri, String exceptUserId)`; `List<Song> everyone(int cap)` (merged, first occurrence of each `uri` kept); `static final String FULL = "You've liked 2000 songs — remove some to like more"`. Invalid user ids (not `[0-9]{1,20}`) → `IllegalArgumentException`.

- [ ] Tests: `firstLikeCreatesTheListAtTheTop`; `likingTwiceChangesNothing`; `unlikeRemovesEveryCopy`; `ownerNameIsRefreshed` (like as `{1,"old"}` then `{1,"new"}` → `createdBy.name == "new"`); `fullListRefusesWithItsMessage` (2000 → `IllegalStateException(FULL)`); `brokenFileIsKeptAndANewListStarts` (`{oops` in `<id>.json` → like works, the old file is now `<id>.json.broken`); `peopleListsOnlyNonEmptyAndSkipsBroken`; `whoLikedExcludesTheAsker`; `everyoneMergesWithoutDuplicates` (and caps); `concurrentLikesOfOneSongMakeOneEntry` (8 threads like the same uri → 1 entry); `invalidUserIdsAreRefused` (`../x`, `a`, `""`); `likedFolderIsHiddenAndReserved` (in `PlaylistStoreTest`: `.liked` not in `tree()`/`folders()`, refused as a name/folder); `converterSkipsTheLikedFolder` (in `PlaylistConverterTest`).
- [ ] FAIL → implement (reuse `PlaylistStore`'s package-private `update` for the owner refresh; check-then-add under the playlist's lock via `update`) → PASS → full suite → commit `bot: liked songs store`.

### Task 2: Likes API and playing

**Files:** create `web/LikeRoutes.java`, `web/SongInput.java` (extracted from `PlaylistRoutes`' song parsing/validation), `web/UndoTokens.java` (extracted from `PlaylistRoutes`' undo registry); modify `web/PlaylistRoutes.java` (use the two extracted classes — behaviour unchanged), `web/WebServer.java` (build `LikesStore` at `<playlists folder>/.liked`, register `LikeRoutes`, implement its play hook), `playlist/PlaylistPlayer.java` (`loadFile(...)`); test `web/LikeRoutesTest.java`, `web/SongInputTest.java`.

**Consumes:** Task 1. **Produces:** `LikeRoutes(LikesStore likes, Predicate<String> uriAlreadyStored, Function<Context, PlaylistFile.Person> currentUser, BiConsumer<String,Object> broadcastAll, LikesPlayHook play, Clock clock)` + `register(Javalin)`; `interface LikesPlayHook { void play(Context ctx, String guildId, PlaylistFile list, String mode, String songId); }`; `PlaylistPlayer.loadFile(Guild, PlaylistFile, String mode, String songId, Consumer<AudioTrack> each, Consumer<Result> done)` (the existing `load` delegates to it for v2 playlists); all routes of spec §3 with exactly its JSON.

- The "already stored" check for local files (`PlaylistRoutes.StoredUris`) moves into `SongInput` and covers both stores: a local file in someone's likes may be added to a playlist (Add to playlist from a Liked page), and one in a playlist may be liked.
- [ ] Tests (bare Javalin, temp dirs, two users): `likeUnlikeAndMine`; `onlyYourOwnListChanges` (a `userId` in a body is ignored; `remove-song`/`move` on ids from someone else's list → 404); `userIdPathIsValidated` (`/api/likes/..%2Fx`, `/api/likes/abc` → 400/404); `peopleAndAFriendsList`; `whoExcludesYou`; `songInputRulesApply` (`file:`/`/etc/passwd` refused unless stored, `javascript:` artwork dropped); `removeSongAndUndo` (exact restore, token single-use, expires); `moveToFinalIndex`; `fullIs409`; `everyChangeBroadcastsLikesWithoutSongData`; `playEveryoneMergesDedupesAndShuffles` (play hook receives one list, no duplicate uris, ≤ 2000); `everyRouteNeedsLogin` (real middleware order); `PlaylistRoutesTest` still green after the extraction; `SongInputTest` for the extracted rules.
- [ ] FAIL → implement → PASS → full suite → commit `bot: liked songs API`.

### Task 3: Panel — ♥ everywhere and "liked by"

**Files:** create `src/main/resources/web/likes.js` (pure; `window.Likes` + `module.exports`); test `src/test/js/likes.test.js`; modify `src/main/resources/web/index.html`.

**Produces:** `Likes.createState()`; `Likes.isLiked(state, uri)`; `Likes.toggle(state, uri) -> {state, request: {path, body}}` (optimistic); `Likes.settle(state, uri, ok, serverLiked)` (latest toggle wins; a failed toggle reverts only if no newer toggle happened); `Likes.likedByText(names) -> string` ("♥ anna", "♥ anna, fred", "♥ anna and 3 others"); `Likes.fromMine(json)`.

- [ ] Tests: `toggleIsOptimistic`; `latestToggleWins` (like, unlike, like with answers arriving out of order → liked); `failedToggleReverts`; `likedByText` cases (0 names → "", 1, 2, 3, 5); `fromMineIgnoresJunk`.
- [ ] Panel: ♥ buttons (filled/outline, `aria-pressed`, title "Like" / "Unlike") on the now-playing card, queue rows, History, Most played, Add Music search results and playlist rows; song objects stay in JS (no data attributes or inline handlers with song text); first like toasts "Added to Liked songs"; errors show the server's message; "♥ liked by …" line under the now-playing artist from `/api/likes/who`; WS `likes` → refresh `mine` and the line (debounced). Visual check in the static preview (stubbed API) in three themes and at phone width. `mvn -o -q test` + Node tests. Commit `web: like songs from anywhere`.

### Task 4: Panel — visible "added by" and reorder without a mouse

**Files:** create `src/main/resources/web/playlist-rows.js` (pure); test `src/test/js/playlist-rows.test.js`; modify `index.html`.

**Produces:** `PlaylistRows.addedByText(song, now)` ("added by fred · Oct 13", parts left out when unknown); `PlaylistRows.moveTargets(index, length) -> [{label, toIndex}]` (Move up / Move down / Move to top / Move to bottom; items that would do nothing are left out; `toIndex` = final position as `/songs/move` expects).

- [ ] Tests: `addedByText` (name + date, name only, date only, nothing); `moveTargets` (first row, last row, middle, single row → empty, two rows).
- [ ] Panel: playlist rows show the added-by line under the artist (also on phones); the old tooltip goes; the row ⋯ menu gains the Move items (on editable lists only) calling `/api/playlists/songs/move` through the existing move path (same optimistic update, revert and live-refresh protections as drag). Visual check (phone width: reorder via the menu). Commit `web: added by on playlist rows, reorder from the menu`.

### Task 5: Panel — Liked songs section, Liked page, everyone's mix

**Files:** modify `index.html` (and `likes.js` for pure label helpers); test additions in `src/test/js/likes.test.js`.

**Consumes:** Tasks 2–4. **Produces:** `Likes.likesLabel(name, isMe)` ("My likes" / "anna's likes", bidi-isolated name).

- [ ] Tests: `likesLabelIsIsolatedText` (HTML and bidi characters stay text; FSI/PDI around the name), `peopleOrder` (me first, then by name).
- [ ] Panel: a "♥ Liked songs" section at the top of the playlist tree ("My likes (n)", each friend's "anna's likes (n)", "▶ Everyone's likes"); the Liked page reuses the playlist page look (header "<name>'s likes", count, length, Play, Shuffle; rows "liked Oct 13"); own page: drag + Move items + Remove with Undo (`/api/likes/remove-song`, `/api/likes/undo`) + Add to playlist; a friend's page: read-only rows with ♥, Add to playlist, Play next, play from here; Play/Shuffle/from via `/api/guild/{id}/likes/play`; "Everyone's likes" plays `userId: "everyone"`; live refresh on `likes`. Visual check (own page, friend's page, phone width). Commit `web: liked songs pages`.

### Task 6: Desktop — ♥, "requested by", My likes

**Files:** `desktop/bridge/{core.js,bridge.js}` (+tests), `desktop/src-tauri/src/{bridge.rs,mini_player.rs,lookup.rs,main.rs,build.rs}`, `capabilities/mini-player.json`, `desktop/ui/mini-player/{index.html,mini.js,view.js,mini.css}` (+view tests).

**Produces:** `BotCommand::{LikesMine, Like { song }, Unlike { uri }, PlayLikes { user_id }}` → `GET /api/likes/mine`, `POST /api/likes/add`, `POST /api/likes/remove`, `POST /api/guild/{id}/likes/play {userId, mode:"all"}`; a bridge report `{type:"likes"}` on WS `likes` (debounced, like `themes`) → Rust refreshes the like state; `mini_like`, `mini_unlike`, `mini_play_likes` Tauri commands (mini-player window only).

- [ ] Tests: JS mapping of the four commands and the `likes` report; Rust serde shapes; the like-state mapping (`uris` set, count); the view helper for the ♥ state and "requested by" text (`requestedByText(state)` → "requested by fred" / ""; Autoplay picks show "autoplay"); the pinned "♥ My likes (n)" Playlists entry.
- [ ] Mini-player: ♥ in the bottom row (current song) and on menu search results, "requested by" under the current song, "♥ My likes (n)" pinned first in the Playlists tab (▶ plays it). No version bump. Visual check in `mini-player-static` with a stubbed Tauri API. Commit `desktop: likes in the mini-player`.

### Task 7: Patch note, review, merge (no release)

- [ ] Add a `patch-notes.json` entry (id = the date it is written, `-N` if taken; title "Liked songs"; Panel: ♥ songs from anywhere, Liked songs in the playlists list, friends' likes, Everyone's likes, "liked by" on now playing, added by on playlist rows, Move up/down in the ⋯ menu; Mini-player: ♥ and requested by (with the next app update)).
- [ ] Final whole-branch review and fix pass. Merge `playlists-round2` into local `main` (fast-forward). Do NOT push, deploy, tag or bump versions; report to Fred what is ready to release.
