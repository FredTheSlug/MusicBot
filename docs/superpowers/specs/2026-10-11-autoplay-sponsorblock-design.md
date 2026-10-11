# Autoplay & SponsorBlock — Design

Bot-side round requested with "try implementing a bunch of these, auto approve" (Fred, 2026-10-11),
after the mini-player 0.3.0 app work. Ideas from popular Discord music bots (autoplay/radio) and the
YouTube Music desktop app (SponsorBlock).

## 1. Features

1. **Autoplay (radio)** — per server, off by default. When the queue runs out after a song finishes
   or is skipped, the bot queues one related song: the first track of YouTube's mix for the song that
   just ended (`watch?v=ID&list=RDID`) that isn't the same song and hasn't played recently (the
   guild's history, last 50). Non-YouTube songs first look up their YouTube equivalent
   (`ytsearch:author title`). Autoplay never continues after `stop`, after the bot leaves voice, or
   when the default playlist (autoplaylist) is in use. Autoplayed songs show "Autoplay" as their
   requester. Toggle: panel button (next to shuffle/repeat), Discord DJ command `autoplay`, and the
   mini-player's Up Next header.
2. **SponsorBlock** — per server, on by default. When a YouTube song starts, the bot asks the public
   SponsorBlock API (`https://sponsor.ajay.app/api/skipSegments?videoID=…&categories=["music_offtopic"]`)
   for "non-music section" segments and seeks past each one as it's reached (a lavaplayer track
   marker per segment, no polling). If the song is already inside a segment when the answer arrives
   (e.g. a talking intro at 0:00), it seeks at once. No answer or an error → nothing is skipped.
   Toggle: panel button and Discord DJ command `sponsorblock`.

## 2. Architecture

- `Settings` gains `autoplay` (false) and `sponsorBlock` (true), saved in `serversettings.json` as
  `autoplay` / `sponsorblock`; older files load with the defaults.
- `AudioHandler`: a `stopping` flag set by `stopAndClear()` around `stopTrack()` so its track end
  never autoplays; in `onTrackEnd`, when the queue is empty, the default queue is empty, repeat is
  off and the setting is on → `Autoplay.queueRelated(...)` (async load; on success queues and
  starts the track, on failure falls through to the usual "nothing playing" path). In
  `onTrackStart`, when the setting is on and the source is YouTube → `SponsorBlock.apply(track, …)`.
- `jmusicbot.audio.Autoplay` (pure helpers + loader): `mixUrl(videoId)`, `isYoutubeId(id)`,
  `pick(List<String> candidateIds, Set<String> recentIds, String currentId) -> int` (index or -1).
- `jmusicbot.audio.SponsorBlock` (pure helpers + fetch): `parseSegments(String json) -> List<Segment>`
  (category `music_offtopic` only, sorted, overlaps merged, < 1 s dropped, at most 20),
  `nextStep(List<Segment>, long positionMs) -> Step {seekTo, markAt}` (inside a segment → seek to its
  end; else mark the next segment's start; none → nothing). Java 11 `HttpClient`, 5 s timeout,
  User-Agent `JMusicBot`.
- Web: POST `/api/guild/{id}/autoplay` and `/api/guild/{id}/sponsorblock` toggle (DJ-free, like
  shuffle/repeat) and answer the new value; the now-playing payload adds `autoplay` and
  `sponsorBlock`; the panel renders two toggle buttons next to shuffle/repeat (same style).
- Discord: `AutoplayCmd`, `SponsorblockCmd` (DJ commands, toggle + reply with the new state).
- Desktop app: `BotState.autoplay`, `BotCommand::ToggleAutoplay` → POST `/autoplay`; an Autoplay
  switch in the Up Next header. Older bots without the field read as off and the switch hides.

## 3. Error handling

Autoplay load failures (no mix, nothing new in it, YouTube error) end playback as today. SponsorBlock
network errors/timeouts and 404 ("no segments") skip nothing. Neither ever throws into the audio
thread. Seeks only happen on the track the segments were fetched for.

## 4. Privacy & security

SponsorBlock receives YouTube video IDs of songs played (public API, no user data). Autoplay only
loads YouTube mix URLs built from a validated 11-character video id. The new web endpoints use the
existing auth and guild-access checks.

## 5. Testing

Java (JUnit 4): `Autoplay.mixUrl/isYoutubeId/pick`, `SponsorBlock.parseSegments` (categories,
merge, tiny, cap, junk JSON), `nextStep` (before/inside/after segments), settings round-trip with
and without the new keys, now-playing payload includes both flags, `stopAndClear` doesn't autoplay
(handler-level test with a fake loader if practical; else by hand). App: BotState field + command
mapping tests. By hand after deploy: queue one song with autoplay on and let it end; play a music
video with a known talking intro.
