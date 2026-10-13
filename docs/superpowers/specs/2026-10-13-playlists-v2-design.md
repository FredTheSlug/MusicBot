# Playlists v2 (round 1) — Design

Fred (2026-10-13): "a more robust playlist creation system" — build while listening, real track
lists, shared with friends, browse & organize, and "a deleted items folder on a weekly timer instead
of permanent deletion". Split into rounds; this spec is **round 1**: new storage, migration, the
Deleted folder, safe simultaneous edits, "Add to playlist" everywhere, search-and-pick, drag
reorder, play options and link import. Round 2 (♥ Liked songs, "added by" shown everywhere) and
round 3 (covers, sorting/filtering, smart lists) get their own specs.

Decisions taken with Fred: convert every existing playlist up front (album links expanded into
songs); playlists are edited in the panel and mini-player only (Discord keeps listing and playing);
one JSON file per playlist; anyone logged in can edit any playlist (unchanged).

## 1. Today

72 playlists on the server as `Playlists/<folder>/<name>.txt`, one URL or search term per line
(`#shuffle` line = shuffle). Many are a single YouTube playlist link per album. The panel has a
folder tree, create/rename/move/delete, Play All, save-queue-as-playlist, and a detail view showing
raw URLs; "+ Add Track" is a browser `prompt()`; saving rewrites the whole list (simultaneous edits
lose changes). Users of `PlaylistLoader`: the default playlist (`AudioHandler.playFromDefault`),
`play playlist <name>` (`PlayCmd`), `playlists` (`PlaylistsCmd`), the owner's `playlist` and
`autoplaylist` commands, and the web endpoints.

## 2. Storage

### Playlist file — `Playlists/<folder>/<name>.json`

```json
{
  "format": 2,
  "name": "The Chronic",
  "version": 17,
  "createdBy": { "id": "233362518045753345", "name": "fred" },
  "createdAt": "2026-10-13T18:00:00Z",
  "updatedAt": "2026-10-13T18:05:00Z",
  "shuffle": false,
  "songs": [
    {
      "id": "s7f3k2q9",
      "uri": "https://www.youtube.com/watch?v=…",
      "title": "Nuthin' But A G Thang",
      "author": "Dr. Dre",
      "durationMs": 238000,
      "artworkUrl": "https://i.ytimg.com/vi/…/hqdefault.jpg",
      "addedBy": { "id": "…", "name": "fred" },
      "addedAt": "2026-10-13T18:00:00Z",
      "unresolved": false
    }
  ]
}
```

- `id`: random 8-character id per song entry, unique within the playlist; edits address songs by id.
- `version`: incremented on every change; returned by every API answer.
- `unresolved: true` marks a line the conversion/import couldn't load; `title` then holds the
  original text and `uri` the original URL or `ytsearch:` query.
- Names and folders keep today's rules (no `/`, `\`, NUL; path stays inside `Playlists/`).
  `.deleted` and `.legacy-txt` are reserved and never listed as folders.
- Limits: 2000 songs per playlist; text fields cut to 300 characters; files written atomically
  (temp file + move).

### Edits — `PlaylistStore`

A new `jmusicbot.playlist.PlaylistStore` owns all reads and writes. Each change is one operation
applied under a per-playlist lock: create, rename, move to folder, delete (to Deleted), restore,
add songs (at end or at an index), remove song (by id), move song (by id → index), set shuffle,
replace an unresolved song (retry). Operations by id never overwrite each other, so two friends
editing at once both keep their changes. Folder create/delete as today (delete moves its playlists
to Deleted, then removes the empty folder).

### Deleted folder

Deleting a playlist moves its file to `Playlists/.deleted/<timestamp>-<name>.json` with
`deletedBy`, `deletedAt` and `originalFolder` added. Restore puts it back in its folder (recreated
if needed), as `Name (restored)` on a name clash. A check at startup and every hour purges entries
whose `deletedAt` is more than 7 days old. Removing a single song is not a delete: the panel offers
Undo (re-add at the same index) for a few seconds.

### Converting the 72 playlists (one time)

At startup a background worker converts each `.txt` that has no `.json` yet, one network load at a
time:

- YouTube/SoundCloud playlist or album link → its songs (up to the 2000 limit), via lavaplayer.
- Spotify playlist/album/track link → the existing `SpotifyResolver` searches, each loaded as the
  first YouTube result.
- Single video link → one song with its details; search line → its first result.
- A line that fails → an `unresolved` song (kept, flagged, retryable).
- `#shuffle` → `"shuffle": true`. `createdBy` = unknown (`null`), `createdAt` = the file's date.

Each playlist is written atomically and only then is the `.txt` moved to `Playlists/.legacy-txt/`.
Until its turn, a `.txt` playlist keeps working exactly as today (the store reads both formats,
JSON first). Progress: `GET /api/playlists/migration` → `{total, done, failed}`; the panel shows
"Upgrading playlists: 30/72" while it runs. The worker resumes after a restart.

### Playing

`play playlist`, the default playlist and the panel's Play load songs by `uri` in order (no search
per line), honoring `shuffle`; unresolved songs are skipped. Play options: all, shuffled, or "from
song X" (that song now, the rest queued after it).

## 3. Import

`POST /api/playlists/import {link, name?, folder?, into?}` starts a background job (one at a time
per user; at most 3 running overall) that expands the link as in the conversion and either creates
a playlist (`name` defaults to the album/playlist title) or appends to `into`. Progress arrives as
WebSocket `playlist-import` messages (`{jobId, done, total, failedCount, playlist?}`); the job's
songs are added as they resolve so the panel can show them filling in.

## 4. Web API

All under the existing auth middleware; playlists are shared across servers, so these aren't under
`/guild/{id}` except playing.

| Method & path | Does |
|---|---|
| `GET /api/playlists/tree` | folders + playlists `{name, folder, songCount, durationMs, createdBy, updatedAt}`, plus `deleted[]` |
| `GET /api/playlists/item?name=&folder=` | the full playlist |
| `POST /api/playlists` | create `{name, folder}` |
| `POST /api/playlists/rename`, `/move`, `/delete`, `/restore` | as named |
| `POST /api/playlists/songs/add` | `{name, folder, songs:[{uri,title,author,durationMs,artworkUrl}], index?}` |
| `POST /api/playlists/songs/remove` | `{name, folder, songId}` |
| `POST /api/playlists/songs/move` | `{name, folder, songId, toIndex}` |
| `POST /api/playlists/songs/retry` | `{name, folder, songId}` |
| `POST /api/playlists/shuffle` | `{name, folder, on}` |
| `POST /api/playlists/import` | §3 |
| `GET /api/playlists/migration` | §2 |
| `POST /api/guild/{id}/playlists/play` | `{name, folder, mode: all|shuffle|from, songId?}` |

Every change broadcasts a WebSocket `playlists` message (`{name, folder, version}` or
`{tree: true}`) to every connected client so open panels and mini-players refresh. The old
endpoints (`/api/playlist/*`, `/api/playlists-tree`, `/playplaylist`) stay for one release as thin
wrappers (the 0.3.0 desktop app's Playlists tab uses `/api/playlists-tree` and `/playplaylist`).

## 5. Panel

- **"Add to playlist"**: a ＋ on the now-playing card, each queue row, History, Most played and
  Add Music search results. It opens a picker popup: filter box, recently used playlists first,
  folder path beside each name, "+ New playlist…" (create and add in one step).
- **Playlist page** (replaces the detail view): header with name, creator, song count, total length;
  Play, Shuffle, Add songs, Rename, Move, Delete. Rows: artwork thumbnail, title, artist, length,
  "added by" tooltip; drag handle to reorder (HTML5 drag, works in the app since the drag-and-drop
  fix); ▶ play from here; ⋯ Play next / Add to another playlist / Remove (with Undo). Unresolved rows
  show "Couldn't load" + Retry.
- **Add songs** (inline): the Add Music search with "Add" per result; a pasted link shows "Import N
  songs from *title*".
- **New playlist from link** button atop the tree.
- **Deleted (n)** section at the bottom of the tree: name, "deleted by X · N days left", Restore.
- Live refresh on `playlists` / `playlist-import` messages.

## 6. Desktop app (mini-player)

A ＋ in the bottom row (current song) and on each menu search result opens the same picker in the
drawer (playlist list with filter, "+ New playlist…"). New bridge commands: `PlaylistsTree`
(fetch), `AddToPlaylist {name, folder, song}`, `CreatePlaylist {name, folder}`. The Playlists tab
reads the new tree (real song counts) and plays via the new play endpoint. Released as the next
desktop version; the 0.3.0 app keeps working against the wrapped old endpoints.

## 7. Discord

`playlists` lists from the store (folders shown as `Folder/Name`); `play playlist <name>` and the
default playlist play JSON playlists (`Folder/Name` accepted); the owner's `autoplaylist` checks the
store. `playlist make/append/delete/…` reply "Edit playlists in the panel: <panel URL>".

## 8. Error handling

Lookups failing during conversion/import → `unresolved` songs, never a lost line. A corrupt JSON
file is skipped in listings with a log warning and left on disk. Operations on a missing playlist or
song id → 404 with a message the panel shows. Disk errors → 500, no partial writes (atomic move).

## 9. Security

Same auth and name/path rules as today (path traversal tests extended to the new store and the
reserved folders). Song text from YouTube/Spotify is stored as data and rendered with text-only
helpers. Import links must be http(s) and are only loaded through lavaplayer/the Spotify resolver.
Caps (2000 songs, 300-character fields, 3 concurrent imports) bound memory and work.

## 10. Testing

Java (JUnit 4, temp directories): file format round-trip and old-file reading; every operation,
including two interleaved edits by id; reserved names and path rules; Deleted folder restore,
clash naming and the 7-day purge with a fake clock; conversion with a fake loader (album link →
songs, failing line → unresolved, `#shuffle`, resume after restart); API endpoints against a real
Javalin server as in `WebAuthMiddlewareTest`. JS: picker and row helpers. By hand: the real
conversion of the 72 playlists after deploy (spot-check albums), drag reorder in the browser and
the app, import of a YouTube and a Spotify playlist.
