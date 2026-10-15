# Playlists round 2 — Liked songs, "added by", reorder without a mouse — Design

Round 2 of playlists v2 (`2026-10-13-playlists-v2-design.md` named it: "♥ Liked songs, 'added by' shown everywhere").
Decided with Fred (2026-10-14): Liked songs are **visible to friends** (yours to edit; anyone logged in can open and
play them); ♥ buttons in the **panel** (now playing + every song list) and the **mini-player**, no Discord command;
"added by" as visible text on **playlist rows** and **mini-player now playing**; plus **reorder without a mouse**
from round 1's leftovers; storage approach A (a hidden per-person playlist). Fred then handed over: "approve
autonomously … add any features you think would be good … merge but don't release public" — so this round also adds
"♥ liked by …" on the now-playing card and "Play everyone's likes", is merged into main locally, and is NOT pushed,
deployed or released until Fred says so.

## 1. Today

Now playing, queue and History already show the requester ("Dr. Dre · fred"). Playlist rows show "Added by fred ·
Oct 13" only as a hover tooltip (nothing on touch). The mini-player shows no requester; its bridge state already
carries `requestedBy`. Songs can only be reordered by mouse drag. There are no likes.

## 2. Liked songs — storage

- Each person's likes are one playlist file in a hidden folder: `Playlists/.liked/<discordUserId>.json`, the same
  format 2 as other playlists (`PlaylistFile`), created on the first like. `.liked` joins `.deleted` and `.legacy-txt`
  as a reserved folder: never listed in the playlist tree, rejected as a name, skipped by the converter and purge.
- A `LikesStore` wraps a `PlaylistStore` rooted at `Playlists/.liked` (playlist name = the Discord id, folder top
  level), so likes reuse the same atomic writes, per-file locks, song ids and song operations.
- `createdBy` = the owner `{id, name}`, refreshed on every change so the display name stays current.
- New likes go to the **top** (newest first). Liking a song already liked (same `uri`) changes nothing. Unliking
  removes every entry with that `uri`. Cap 2000 ("You've liked 2000 songs — remove some to like more").
- Only the owner changes their likes: the user id always comes from the login session, never from the request.

## 3. Web API (all under the Discord login)

| Method & path | Does |
|---|---|
| `GET /api/likes/mine` | `{uris: [...], count, max: 2000}` — what the ♥ buttons need |
| `GET /api/likes/people` | `[{id, name, count, durationMs, updatedAt}]` — everyone with at least one like, me first, then by name |
| `GET /api/likes/{userId}` | that person's list in the playlist page shape (`songs`, `version`, …) plus `owner {id, name}` and `mine`; 404 when they have none |
| `GET /api/likes/who?uri=` | `{names: [...]}` — friends (not you) who liked this song |
| `POST /api/likes/add` | `{song: {uri, title, author, durationMs, artworkUrl}}` → `{liked: true, count}` |
| `POST /api/likes/remove` | `{uri}` → `{liked: false, count}` (the ♥ toggled off) |
| `POST /api/likes/remove-song` | `{songId}` → `{count, undo}` (Remove on your Liked page, with Undo) |
| `POST /api/likes/undo` | `{token}` → puts the song back exactly (same id, date), as playlists' Undo |
| `POST /api/likes/move` | `{songId, toIndex}` (final position, as playlists) |
| `POST /api/guild/{id}/likes/play` | `{userId, mode: all\|shuffle\|from, songId?}` — or `userId: "everyone"`: every person's likes merged, duplicates (same `uri`) dropped, shuffled, capped at 2000 |

- Song input follows the playlists rules (R16 there): `uri` must be http(s) or a `ytsearch:`/`ytmsearch:`/`scsearch:`
  query; a local file only when the identical uri is already stored in some playlist or someone's likes;
  `artworkUrl` kept only if http(s). Errors `{error}`: 400 / 404 / 409 (full) / 500, as playlists.
- Every change broadcasts WebSocket `likes` `{userId}` to all clients (no song data).
- Play goes through the existing `PlaylistPlayer` (all modes append to the shared queue, as playlists).

## 4. Panel

- **♥ buttons:** on the now-playing card, queue rows, History, Most played, Add Music search results and playlist rows.
  Filled when the song (by `uri`) is in your likes. Click toggles, updates at once and reverts with a toast on an
  error; the first like shows "Added to Liked songs". Local-file songs that the server refuses show its message.
- **"♥ liked by …"** on the now-playing card: "♥ anna, fred" (or "♥ anna and 3 others") when friends liked the song
  (from `/api/likes/who`, refreshed on song change and on `likes` messages). Hidden when nobody else did.
- **Playlists pane:** a "♥ Liked songs" section at the top of the tree: "My likes (n)", then each friend's
  "anna's likes (n)", then "▶ Everyone's likes" (plays the merged, shuffled mix).
- **Liked page:** the playlist page look with header "<name>'s likes", song count, total length, Play, Shuffle.
  Rows show "liked Oct 13". Your own page: drag, the ⋯ menu's Move items, Remove with Undo, Add to playlist. A
  friend's page: read-only rows with ♥ (like it yourself), Add to playlist, Play next, play from here.
- **"Added by" visible:** playlist rows show "added by fred · Oct 13" under the artist (on phones too); unknown parts
  are left out; the hover tooltip goes.
- **Reorder without a mouse:** each row's ⋯ menu on a playlist page and on your Liked page gains Move up, Move down,
  Move to top, Move to bottom (hidden where they would do nothing; disabled for read-only lists).
- Live refresh: `likes` messages update ♥ states, the Liked section counts, an open Liked page and the "liked by" line.

## 5. Mini-player (desktop app; built now, released later as 0.6.0 when Fred says)

- **♥** in the bottom row for the current song (filled when liked; toggles) and on each menu search result.
- **"requested by fred"** under the current song (from the existing `requestedBy` state; nothing when unknown).
- **Playlists tab:** "♥ My likes (n)" pinned first; ▶ plays it.
- Bridge commands: `LikesMine` (fetch), `Like {song}`, `Unlike {uri}`, `PlayLikes {userId}`; the like state refreshes
  on the current song changing and on `likes` messages.

## 6. Patch notes

A new `patch-notes.json` entry for this release (title "Liked songs"), written plainly; it goes live with the deploy
Fred approves later.

## 7. Errors and safety

Likes changes only ever touch the session user's own file; another person's list is read-only through every route.
Names and song text render as text only. The same caps and uri rules as playlists. A broken likes file is skipped in
`people` with a warning and treated as empty for its owner (not overwritten until they like something, which then
starts it fresh — the broken file is kept as `<id>.json.broken`).

## 8. Testing

- Java: LikesStore (add to top, no duplicates, unlike by uri, cap, owner name refresh, broken file kept), the `.liked`
  folder hidden/reserved in the main store and skipped by converter/purge, every route (only your own list changes,
  others' read-only, 404s, `who` excludes yourself, everyone-mix dedupe + cap), broadcasts carry no song data.
- JS (panel): ♥ state helpers, "liked by" wording, Move up/down/top/bottom index math, "added by" text.
- Desktop: bridge command shapes, the likes state mapping, the pinned "My likes" entry.
- By hand (at Fred's hand test): like from every place, a friend's likes page, everyone's mix, phone reorder, mini-player ♥.
