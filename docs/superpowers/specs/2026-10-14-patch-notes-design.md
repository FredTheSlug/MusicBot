# Patch notes ("What's new") — Design

Fred (2026-10-14): "add patch notes that show up after an update so people can see what's new. add patch
notes for the last 3 updates as well." Decided with Fred: one shared list covering the bot/panel and the
desktop app, shown in the panel after an update and in the app after it updates itself.

## 1. The list

One file in the repo, `src/main/resources/web/patch-notes.json`, served with the panel's other static files
at `/web/patch-notes.json` (public; nothing in it is secret):

```json
{
  "notes": [
    {
      "id": "2026-10-14",
      "date": "2026-10-14",
      "title": "Playlists, rebuilt",
      "app": "0.4.0",
      "items": [
        { "area": "Panel", "text": "Playlists now show real songs …" }
      ]
    }
  ]
}
```

- `notes` newest first; `id` unique (the date, plus `-2`, `-3` for a second entry on one day); `date`
  `YYYY-MM-DD`; `title` ≤ 60 characters; `app` = the desktop version released with it (optional);
  `items[].area` one of `Panel`, `Mini-player`, `App`, `Discord`; `items[].text` plain text (shown with
  `textContent`), ≤ 300 characters.
- Every future bot deploy or app release adds an entry (added to the release routine).

## 2. Panel

- After login the panel fetches the list. The newest id the person has seen is kept in `localStorage`
  (`jmusicbot_seen_notes_v1`; every access in try/catch). Unseen = entries above that id in the list.
  - Never seen anything (or the stored id no longer exists) → show only the newest entry, not the history.
  - Something unseen → a "What's new" dialog lists the unseen entries newest first (date, title, items
    grouped by area with a small area label); "Got it" stores the newest id and closes it.
- A "What's new" link in the panel header opens the full history any time; it carries a small dot while
  something is unseen.
- The dialog uses the panel's theme variables, works at phone width, closes on Escape, and never opens
  over the login screen.
- `window.openWhatsNew()` opens the full history (used by the desktop app, §3).

## 3. Desktop app

The app's main window shows the panel, so the dialog appears there too. Added in the next app release:

- **After an update:** the app remembers the version it last ran (`lastRunVersion` in its settings). On start,
  if that differs from its own version — or it is missing while a settings file already existed (an update
  from an app version older than this feature) — it shows the main window once so the panel's dialog is
  seen. A fresh install doesn't. Then it stores the current version.
- **Mini-player:** a "What's new" item in its ☰ menu shows the main window and asks the panel to open the
  full history (a bridge command that calls `window.openWhatsNew()` in the panel page).

## 4. The last three updates (initial content; Fred may edit the wording)

1. **2026-10-11 — "Autoplay, skip non-music and a bigger mini-player"** (app 0.3.0): Autoplay keeps the
   music going with related songs when the queue runs out (Autoplay pill / `autoplay` command); "Skip
   non-music" jumps over intros and talking in YouTube videos (SponsorBlock, on by default;
   `sponsorblock` command); mini-player: Up Next, shuffle and repeat, drag to seek, Recent / Most played /
   Playlists tabs, a sleep timer, hides during fullscreen games on its monitor, optional hotkeys (off by
   default) and a compact mode.
2. **2026-10-13 — "Move panes in the app"** (app 0.3.1): the panel's panes can be dragged around inside the
   desktop app like in the browser, and audio files can be dropped onto it to upload.
3. **2026-10-14 — "Playlists, rebuilt"** (app 0.4.0): playlists show real songs with artwork, artists and
   lengths (old playlists were upgraded automatically); ＋ Add to playlist from now playing, the queue,
   History, Most played and search; drag to reorder; Undo when removing; Retry for songs that couldn't
   load; import a YouTube, Spotify or SoundCloud playlist or album link; a Deleted folder keeps deleted
   playlists for 7 days; friends can edit the same playlist at once; play from any song; mini-player ＋ and
   real song counts; Discord `play playlist Folder/Name` (editing moved to the panel).

## 5. Rollout

The panel part ships with the next bot deploy (works inside the current app too, since its main window is
the panel). The app part (§3) ships with the next desktop release.

## 6. Testing

- Java: the bundled `patch-notes.json` parses, ids are unique, dates valid and newest first, areas known,
  lengths within limits (guards hand edits).
- Desktop (Rust): the "just updated" decision (same version / different / missing with and without an
  existing settings file); the new bridge command's shape.
- By hand: first visit shows one entry; an unseen entry pops once; the header link and dot; the app opening
  its window after an update; the mini-player menu item.
