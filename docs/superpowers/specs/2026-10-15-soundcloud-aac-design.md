# SoundCloud AAC playback — Design

Decided with Fred (2026-10-15): teach the bot SoundCloud's plain AAC streams (approach A: plug into lavaplayer's
SoundCloud loader, no fork); tracks SoundCloud only offers encrypted fall back to YouTube, with a visible note.

## 1. The problem

Since about 2026-10-11..13, SoundCloud's api-v2 (the API lavaplayer uses) no longer offers MP3 or Opus for many
tracks. Every SoundCloud play fails with "Did not detect any supported formats"
(`DefaultSoundCloudFormatHandler.chooseBestFormat`), and so does `scsearch:` as soon as one result has no usable format.
lavaplayer 2.2.7 is the newest release; upstream issue lavalink-devs/lavaplayer#189 has been open since May 2026 with no fix.

What SoundCloud offers now (checked on six tracks that played fine Oct 6–10):
- **Plain AAC** (independent uploads): `hls | audio/mp4; codecs="mp4a.40.2"` as `aac_160k` and `aac_96k`, plus an
  adaptive `hls | audio/mpegurl` (`abr_sq`). The HLS playlist is VOD: one `#EXT-X-MAP` init file (`init.mp4`:
  `ftyp` + `moov`) and ~10 s chunks (`dataNNN.m4s`: `styp` + `sidx` + `moof` + `mdat`), all on signed links.
- **Encrypted only** (label uploads): `cbc-encrypted-hls` / `ctr-encrypted-hls`. DRM; no bot can play these.

A throwaway probe confirmed lavaplayer's own `MpegFileLoader` (fragmented provider) + native `AacDecoder` decode
init + chunk with no failures: AAC-LC, 44.1 kHz, stereo, 431 frames per 10.0 s chunk, duration read from the chunk's `sidx`.

## 2. Components (new package `com.jagrosh.jmusicbot.audio.soundcloud`)

1. **`CksSoundCloudFormatHandler implements SoundCloudFormatHandler`** — wraps lavaplayer's default handler.
   - Choice order: whatever the default handler would choose (progressive MP3, HLS MP3, HLS Opus) → `hls` +
     `audio/mp4` AAC with preset `aac_160k` → the same at `aac_96k` → any other plain `hls` + `audio/mp4`.
     `abr_sq` / `audio/mpegurl` is never chosen.
   - Only encrypted formats → a "protected" marker format (so the track still loads with its SoundCloud info).
   - No formats at all → the same error as today.
   - `buildFormatIdentifier`: default identifiers unchanged; AAC → `A:` + lookup URL; protected → `X:` + lookup URL.
   - `getM3uInfo("A:…")` → `SoundCloudM3uInfo(lookupUrl, AAC decoder factory)`; other prefixes → the default handler.
2. **`SoundCloudAacSegmentDecoder implements SoundCloudSegmentDecoder`** — lavaplayer's `SoundCloudM3uAudioTrack`
   keeps fetching the playlist, downloading chunks in order, seeking by chunk and refreshing expired links (it ignores
   `#EXT-X-MAP`). The decoder:
   - reads the playlist itself once per track to find the `#EXT-X-MAP` URI (quoted, absolute or relative to the
     playlist) and downloads the init file once (kept in memory for the track);
   - for each chunk: init + chunk → `MpegFileLoader` → AAC track consumer that writes to the track's
     `AudioProcessingContext`; a seek into the middle of a chunk drops audio up to the requested position;
   - releases the native decoder on close.
3. **`SoundCloudSource`** — the `AudioSourceManager` registered in `PlayerManager` in place of
   `SoundCloudAudioSourceManager.createDefault()`. It wraps
   `SoundCloudAudioSourceManager.builder().withFormatHandler(new CksSoundCloudFormatHandler(…)).build()` with the same
   other settings as `createDefault()`, delegates every call, and replaces any loaded track whose identifier starts
   with `X:` (also inside playlists and search results) with a `YouTubeFallbackTrack`.
4. **`YouTubeFallbackTrack extends DelegatedAudioTrack`** — keeps the SoundCloud `AudioTrackInfo` (title, author,
   length, uri, artwork), so queue, History, likes and playlists keep pointing at SoundCloud. When it starts playing it
   searches YouTube (`ytsearch:` + author + title) through the bot's YouTube source, takes the first of the top 5
   results whose length is within 15 % of the SoundCloud length (else the first that is not a live stream), and plays
   it as its delegate.

`PlayerManager` registers `SoundCloudSource` where `SoundCloudAudioSourceManager.createDefault()` is today; nothing
else in the play paths changes (Discord commands, panel, playlists, likes, autoplay all use the same manager).

## 3. What people see

- **Plain AAC:** the song just plays (length, seeking, artwork as before). Nothing new is shown.
- **Protected → YouTube:** queue/History/likes keep the SoundCloud details. While the YouTube match plays:
  - panel now-playing: "Artist · fred · via YouTube" (the now-playing payload gains `via: "youtube"`, absent otherwise);
  - Discord now-playing message footer: "Source: <author> · playing from YouTube (SoundCloud protects the original)".
  - The mini-player shows no note (left out on purpose).

## 4. Errors

| Case | Result |
|---|---|
| Protected, YouTube finds nothing or errors | Song skipped: "SoundCloud protects this track and YouTube had no match" |
| A chunk can't be downloaded | lavaplayer's existing SoundCloud HLS handling (retry / link refresh); if it still fails, the normal play error |
| Playlist has no `#EXT-X-MAP`, or the init file can't be read | Song fails: "SoundCloud sent a stream the bot can't read yet"; the playlist's `#` lines are logged with signed query strings removed |
| SoundCloud offers MP3/Opus again for a track | Picked automatically (old formats come first) |

Logging: one INFO line per YouTube swap ("SoundCloud protects <soundcloud uri>; playing YouTube <video id> instead").
Signed SoundCloud links (query strings) are never logged.

## 5. Testing

- Format handler: MP3/Opus still preferred; AAC 160k then 96k; `abr_sq` ignored; encrypted-only → protected; empty →
  today's error; identifiers and `getM3uInfo` routing.
- Init lookup: quoted/absolute/relative `#EXT-X-MAP` URIs; missing map → the "can't read yet" error; logged lines
  have no query strings.
- Decoder on real audio: a committed fixture generated with ffmpeg from a synthetic tone (AAC-LC 160k, 44.1 kHz,
  stereo), laid out like SoundCloud's (init `ftyp`+`moov`; chunks `styp`+`sidx`+`moof`+`mdat`, ~2 s each). Init +
  2 chunks decode to the right length; a seek into the middle of a chunk starts at the right sample. (No real
  SoundCloud audio is committed.)
- Fallback track: picks the first result within 15 % of the length among the top 5, else the first of them that is
  not a live stream (a live stream is never picked); no result → the message above; the SoundCloud info is kept.
- `SoundCloudSource`: normal tracks pass through unchanged; `X:` tracks become fallback tracks, also inside a search
  result page, so one protected result no longer breaks a whole `scsearch`.
- Panel/Discord note: `via` present only for a playing fallback track; footer text.
- By hand after the deploy: `soundcloud.com/booyabooya/jhbd-4-2026` (plain AAC, seek), `soundcloud.com/liluzivert/444222a`
  (protected → YouTube, "via YouTube"), an `scsearch`, a playlist with SoundCloud lines (Retry on "couldn't load").

## 6. Shipping

Bot only (no desktop release). A patch-notes entry ("SoundCloud plays again", area Panel/Discord). Branch
`soundcloud-aac`; deploy when nobody is in voice; push after Fred's OK.
