# SoundCloud-style AAC fixture

Our own synthetic audio, not SoundCloud's: 3.9 s of a stereo tone (left 1 kHz, right 660 Hz), encoded as
AAC-LC 160 kbit/s, 44.1 kHz, stereo, and cut by ffmpeg's HLS muxer into fragmented-MP4 chunks of about 2 s.
Used by `SoundCloudAacSegmentDecoderTest`.

## How it was made

ffmpeg 7.1 (gyan.dev essentials build), run in this folder from Git Bash:

```sh
ffmpeg -hide_banner -y \
  -f lavfi -i "aevalsrc=0.5*sin(2*PI*1000*t)|0.5*sin(2*PI*660*t):s=44100:d=3.9" \
  -c:a aac -b:a 160k -ar 44100 -ac 2 \
  -f hls -hls_segment_type fmp4 -hls_time 2 -hls_playlist_type vod \
  -hls_fmp4_init_filename init.mp4 -hls_segment_filename "chunk%d.m4s" fixture.m3u8
rm fixture.m3u8
```

No extra `movflags` are needed: ffmpeg's HLS muxer already writes a `sidx` in front of every chunk's `moof`.
Nothing was edited by hand. Running the command again gives byte-identical files.

The 3.9 s length keeps it to two chunks (4 s leaves a third chunk holding one 23 ms frame). The playlist is not
kept; its chunk lengths were:

```
#EXT-X-MAP:URI="init.mp4"
#EXTINF:2.020136,
chunk0.m4s
#EXTINF:1.903084,
chunk1.m4s
```

## Layout (same as SoundCloud's `init.mp4` / `dataNNN.m4s`)

| File | Top-level boxes | Notes |
|---|---|---|
| `init.mp4` | `ftyp` (`iso5`) + `moov` (`mvhd`, `trak`, `mvex`, `udta`) | AudioSpecificConfig `1210` (AAC-LC, 44.1 kHz, 2 ch) plus ffmpeg's "no SBR" tail `56e500`; SoundCloud's is just `1210` |
| `chunk0.m4s` | `styp` (`msdh`) + `sidx` + `moof` + `mdat` | 87 AAC frames, `sidx` 89088 / 44100 (2.020 s) |
| `chunk1.m4s` | `styp` (`msdh`) + `sidx` + `moof` + `mdat` | 82 AAC frames, `sidx` 83926 / 44100 (1.903 s, last frame short) |

The `sidx` is version 1 with timescale 44100 and one reference per chunk, and the `moof` holds one `traf`
(`tfhd` + `tfdt` + `trun`), exactly as in SoundCloud's chunks.
