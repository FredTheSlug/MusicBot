# SoundCloud AAC Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SoundCloud tracks that now only come as plain AAC HLS play again; tracks SoundCloud only offers encrypted play a YouTube match with a visible "via YouTube" note.

**Architecture:** A custom `SoundCloudFormatHandler` plugged into lavaplayer's own SoundCloud source picks plain AAC when MP3/Opus are gone and hands lavaplayer's existing HLS track an AAC chunk decoder (init file + chunk → lavaplayer's `MpegFileLoader` + `MpegAacTrackConsumer`). A thin `SoundCloudSource` wrapper turns encrypted-only ("protected") tracks into a `YouTubeFallbackTrack` that searches YouTube when it starts playing.

**Tech Stack:** Java 11 (runs on 21), Maven, lavaplayer 2.2.7 (`dev.arbjerg`), JUnit 4; panel `src/main/resources/web/index.html`; ffmpeg (on the dev PC, chocolatey) only to generate a test fixture.

**Spec:** `docs/superpowers/specs/2026-10-15-soundcloud-aac-design.md`

## Global Constraints

- No lavaplayer fork and no version change: everything goes through `SoundCloudAudioSourceManager.builder().withFormatHandler(…).build()` (same other settings as `createDefault()`: search allowed, preview tracks not filtered).
- Format order: the default handler's choice (progressive MP3, HLS MP3, HLS Opus) → `hls` + `audio/mp4` preset `aac_160k` → preset `aac_96k` → any other `hls` + `audio/mp4`; never `audio/mpegurl` (`abr_sq`). Encrypted protocols (`cbc-encrypted-hls`, `ctr-encrypted-hls`) are never chosen; encrypted-only → protected.
- Identifiers: AAC → `A:` + lookup URL; protected → `X:` + lookup URL; other prefixes exactly as the default handler builds them.
- Messages (exact): `SoundCloud protects this track and YouTube had no match`; `SoundCloud sent a stream the bot can't read yet`; Discord footer suffix ` · playing from YouTube (SoundCloud protects the original)`; panel suffix ` · via YouTube`.
- YouTube match: `ytsearch:` + author + " " + title; among the top 5 results the first whose length is within 15 % of the SoundCloud length, else the first result; searched only when the fallback track starts playing.
- Never log a signed URL's query string; one INFO line per swap: `SoundCloud protects <soundcloud uri>; playing YouTube <video id> instead`.
- New code in package `com.jagrosh.jmusicbot.audio.soundcloud` (Allman braces, javadoc like `playlist/`). No real SoundCloud audio is committed.
- Tests: `mvn -o -q test`; `node --test "src/test/js/*.test.js"`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never kill processes by image name. No push, no deploy, no tag inside the tasks.

## Review Focus

1. A track offering both encrypted and plain AAC (or plain AAC plus `abr_sq`) → plain AAC 160k plays from SoundCloud, never the fallback. Pinned in Task 2 (`plainAacBeatsEncryptedAndAdaptive`).
2. Seeking to a chunk boundary, inside a chunk, back to 0, and into the last chunk → audio starts at the requested position (± one AAC frame). Pinned in Task 1 (`seekInsideAChunkStartsAtTheRequestedSample`, `seekToAChunkBoundary`).
3. A protected track whose first YouTube result is a 1-hour video → the closer-length result is picked. Pinned in Task 3 (`picksTheClosestLengthAmongTheTopFive`).
4. A 50-song SoundCloud set with protected songs → loading it does zero YouTube searches. Pinned in Task 3 (`loadingProtectedTracksSearchesNothing`).
5. A fallback track played again (repeat / History replay / shallow clone) → searches again and plays; it never reuses a finished delegate. Pinned in Task 3 (`aCloneSearchesAgain`).

---

### Task 1: AAC chunk decoder (with a generated fixture)

**Files:** create `src/main/java/com/jagrosh/jmusicbot/audio/soundcloud/SoundCloudAacSegmentDecoder.java`; fixture `src/test/resources/soundcloud-aac/{init.mp4,chunk0.m4s,chunk1.m4s}` + `src/test/resources/soundcloud-aac/README.md` (the exact ffmpeg command, synthetic 1 kHz/660 Hz tone, AAC-LC 160k, 44.1 kHz, stereo, ~2 s chunks); test `src/test/java/com/jagrosh/jmusicbot/audio/soundcloud/SoundCloudAacSegmentDecoderTest.java`.

**Produces:** `SoundCloudAacSegmentDecoder(Supplier<SeekableInputStream> nextStream, InitLoader init)` implementing `SoundCloudSegmentDecoder`, with `interface InitLoader { byte[] load() throws IOException; }` (nested). `prepareStream` loads the init once per decoder; `playStream(context, startPosition, desiredPosition)` reads the next chunk, joins init + chunk, parses with `MpegFileLoader`, plays the first `mp4a` track through `MpegAacTrackConsumer` and, when `desiredPosition > startPosition`, calls `seekPerformed(desiredPosition, startPosition)` so audio before the target is dropped; `close` releases the consumer.

- [ ] Fixture step: generate with ffmpeg so each chunk is `styp`+`sidx`+`moof`+`mdat` and the init is `ftyp`+`moov` (e.g. `-c:a aac -b:a 160k -ar 44100 -ac 2 -f hls -hls_segment_type fmp4 -hls_time 2` plus the `+global_sidx`/`frag_*` flags needed to get a per-chunk `sidx` — record the command that worked in the README). Test `fixtureLooksLikeSoundCloud` checks the box order of the three files.
- [ ] Tests (real `AudioProcessingContext` with `new AudioConfiguration()`, a `NonAllocatingAudioFrameBuffer`/`AllocatingAudioFrameBuffer` big enough, `new AudioPlayerOptions()`, `StandardAudioDataFormats.COMMON_PCM_S16_LE`): `decodesTwoChunksToTheirLength` (total PCM frames = chunk durations × 44100 ± 1 AAC frame); `seekInsideAChunkStartsAtTheRequestedSample` (desired = start + 700 ms → output length = remaining length ± 1 frame); `seekToAChunkBoundary`; `initIsLoadedOncePerTrack` (counting loader, 2 chunks → 1 call); `missingInitIsTheCantReadYetError`.
- [ ] FAIL → implement → PASS → `mvn -o -q test` → commit `bot: decode SoundCloud AAC chunks`.

### Task 2: Format handler and init lookup

**Files:** create `src/main/java/com/jagrosh/jmusicbot/audio/soundcloud/CksSoundCloudFormatHandler.java`, `.../soundcloud/HlsInitLookup.java`; tests `src/test/java/com/jagrosh/jmusicbot/audio/soundcloud/CksSoundCloudFormatHandlerTest.java`, `HlsInitLookupTest.java`.

**Consumes:** Task 1 (`SoundCloudAacSegmentDecoder`, `InitLoader`). **Produces:**
- `CksSoundCloudFormatHandler(Supplier<HttpInterface> http)` implementing `SoundCloudFormatHandler`; `static final String AAC_PREFIX = "A:"`, `PROTECTED_PREFIX = "X:"`; `static boolean isProtected(AudioTrack track)` (identifier starts with `X:`). `getM3uInfo("A:…")` returns `new SoundCloudM3uInfo(lookupUrl, stream -> new SoundCloudAacSegmentDecoder(stream, () -> HlsInitLookup.loadInit(http.get(), lookupUrl)))` (Task 1 class); `getM3uInfo`/`getMp3LookupUrl` for `X:` throw `FriendlyException(<protected message>, COMMON, null)`; everything else delegates to `DefaultSoundCloudFormatHandler`.
- `HlsInitLookup`: `static String initUri(String playlistText, String playlistUrl)` (the `#EXT-X-MAP:URI="…"` value, resolved against the playlist URL; `IllegalStateException(<can't read yet message>)` when missing); `static String redact(String playlistText)` (only `#` lines, query strings removed); `static byte[] loadInit(HttpInterface http, String lookupUrl) throws IOException` (`SoundCloudHelper.loadPlaybackUrl` → GET playlist → `initUri` → GET bytes; on a missing map logs `redact(...)` at WARN and throws `FriendlyException(<can't read yet>, SUSPICIOUS, cause)`).

- [ ] Tests (formats built with `DefaultSoundCloudTrackFormat(trackId, protocol, mimeType, lookupUrl)`): `mp3AndOpusStillWin`; `aac160kThen96k`; `plainAacBeatsEncryptedAndAdaptive`; `encryptedOnlyIsProtected` (identifier `X:<lookup>`); `adaptiveOnlyIsTodaysError`; `emptyIsTodaysError` (same exception type/message as the default handler); `aacIdentifierRoutesToTheAacDecoder` (`getM3uInfo("A:u").lookupUrl == "u"`, factory not null); `protectedNeverPlaysDirectly` (both lookups throw the protected message); `initUriQuotedAbsoluteAndRelative`; `missingMapIsTheCantReadYetError`; `redactDropsQueryStringsAndMediaLines`.
- [ ] FAIL → implement → PASS → `mvn -o -q test` → commit `bot: SoundCloud AAC format handler`.

### Task 3: Protected tracks → YouTube, and registration

**Files:** create `.../soundcloud/YouTubeFallbackTrack.java`, `.../soundcloud/SoundCloudSource.java`; modify `src/main/java/com/jagrosh/jmusicbot/audio/PlayerManager.java:56-58` (register `new SoundCloudSource(youtubeSource, this)` instead of `SoundCloudAudioSourceManager.createDefault()`, reusing the `YoutubeSources.create(...)` instance); tests `YouTubeFallbackTrackTest.java`, `SoundCloudSourceTest.java`.

**Consumes:** Task 2 (`CksSoundCloudFormatHandler`, `isProtected`). **Produces:**
- `interface YouTubeSearch { List<AudioTrack> search(String query) throws Exception; }` (nested in `YouTubeFallbackTrack`).
- `YouTubeFallbackTrack(AudioTrackInfo soundCloudInfo, YouTubeSearch search, AudioSourceManager source)` extends `DelegatedAudioTrack`; `static AudioTrack pick(List<AudioTrack> results, long soundCloudLengthMs)` (the 15 % / top-5 rule); `boolean isPlayingFromYouTube()` (true once a delegate started); no match → `FriendlyException(<protected message>, COMMON, null)`; the INFO swap line; `makeShallowClone` → a fresh fallback track (searches again).
- `SoundCloudSource(AudioSourceManager youtube, AudioPlayerManager manager)` implements `AudioSourceManager`, `getSourceName() == "soundcloud"`; builds the inner manager with `builder().withFormatHandler(new CksSoundCloudFormatHandler(() -> inner.getHttpInterface())).build()`; `loadItem` delegates and maps every protected track (single, playlist, search page) to a `YouTubeFallbackTrack` whose search is `youtube.loadItem(manager, new AudioReference("ytsearch:" + q, null))` (playlist → its tracks, else empty); `isTrackEncodable/encodeTrack/decodeTrack/shutdown` delegate (a decoded `X:` track becomes a fallback track again).
- [ ] Tests (fake `YouTubeSearch`, fake inner `AudioSourceManager` for the wrapper): `picksTheClosestLengthAmongTheTopFive` (1 h video first, 3:05 third for a 3:00 track → third); `firstResultWhenNoneIsClose`; `onlySearchesTheTopFive`; `noResultIsTheProtectedMessage`; `keepsTheSoundCloudInfo`; `aCloneSearchesAgain`; `normalTracksPassThrough` (same instance); `protectedSingleBecomesAFallback`; `protectedTracksInsideAPlaylistAndASearchPage` (the page keeps all results, order kept); `loadingProtectedTracksSearchesNothing`; `sourceNameIsSoundcloud`; `decodedProtectedTrackIsAFallbackAgain`.
- [ ] FAIL → implement → PASS → `mvn -o -q test` → commit `bot: protected SoundCloud tracks play from YouTube`.

### Task 4: "via YouTube" note in the panel and on Discord

**Files:** modify `src/main/java/com/jagrosh/jmusicbot/web/WebServer.java` (now-playing payload ~500-535: `via: "youtube"` only when the playing track is a `YouTubeFallbackTrack` with `isPlayingFromYouTube()`), `src/main/java/com/jagrosh/jmusicbot/audio/AudioHandler.java:~441` (footer `"Source: " + author` + the Global Constraints suffix for such a track), `src/main/resources/web/index.html` (`renderNowPlaying` `.track-author` line appends ` · via YouTube` when `data.via === 'youtube'`; include `via` in `nowPlayingStableKey` so the line updates when the swap happens); tests in `src/test/java/com/jagrosh/jmusicbot/web/WebPayloadsTest.java` (or the test that covers the now-playing payload — find it) and `AudioHandler`'s footer helper.

**Consumes:** Task 3 (`YouTubeFallbackTrack.isPlayingFromYouTube`). **Produces:** a small static helper per side so it is testable without JDA, e.g. `WebServer.viaOf(AudioTrack) -> String|null` and `AudioHandler.footerFor(AudioTrack) -> String`.
- [ ] Tests: `viaIsYoutubeOnlyForAPlayingFallback` (normal track → absent; fallback before start → absent; after → "youtube"); `footerSaysPlayingFromYoutube`; the panel inline-script parse test stays green.
- [ ] FAIL → implement → PASS → `mvn -o -q test` + `node --test "src/test/js/*.test.js"` → commit `web: say when a SoundCloud song plays from YouTube`.

### Task 5: Patch note

- [ ] Add a new FIRST `patch-notes.json` entry by text in the file's style (id `2026-10-16`, or `-2` if taken — use the date it is written; no `app` field; areas Panel/Discord): SoundCloud songs play again; songs SoundCloud locks (mostly label uploads) play from YouTube instead and say "via YouTube". `PatchNotesFileTest` + Node tests green → commit `patch notes: SoundCloud plays again`.

## Changes during execution

Where the finished code differs from the plan above:

- **Task 2:** the test `aac160kThen96k` became `firstPlainAacInSoundCloudsOrderWins`. lavaplayer 2.2.7 does not expose the bitrate preset, and SoundCloud lists `aac_160k` first, so the handler takes the first plain AAC in SoundCloud's own order.
- **Task 3:** `YouTubeFallbackTrack.pick`'s last resort changed in the final review from "the first result" to "the first of the top 5 that is not a live stream" (none: the protected message), test `aLiveStreamIsNeverTheFallback`; the test `picksTheClosestLengthAmongTheTopFive` became `firstWithinFifteenPercentAmongTheTopFive` (the rule is "first within 15 %", not "closest"). `isPlayingFromYouTube()` was removed (see Task 4).
- **Task 4:** the "via YouTube" note keys on `instanceof YouTubeFallbackTrack` (`WebServer.viaOf`, `AudioHandler.footerFor`; test `viaIsYoutubeForAFallbackTrack`), not on `isPlayingFromYouTube()`: the now-playing message is built before the track's `process()` runs the YouTube search, so nothing would signal the flip.
- **Task 1:** the decoder tests run at Discord's 48 kHz output (`StandardAudioDataFormats.DISCORD_PCM_S16_LE`, which resamples from the 44.1 kHz source), not at 44.1 kHz (`COMMON_PCM_S16_LE`).
