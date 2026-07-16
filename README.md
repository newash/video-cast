# VideoCast

Personal Android app: cast local video files to a Chromecast (default media
receiver) with subtitle support. A minimal replacement for Web Video Caster —
no ads, no extra features.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design, tech choices, and the
milestone build plan.

## What it does

- Pick a local video (Storage Access Framework), serve it from an embedded
  HTTP server (JLHTTP, with Range/206 for seeking), and cast it via the
  Google Cast sender SDK to the default receiver.
- Subtitles: pick a local SRT/ASS file, search OpenSubtitles by title
  (anonymous downloads), or choose a text track embedded in the video itself
  (SRT/ASS in MKV via a built-in parser, timed text in MP4). Everything is
  converted to WebVTT and served with CORS headers — the only way the
  default receiver renders sidecar text tracks. The last used subtitle
  language is remembered across all sources.
- NAS streaming without any app changes: install
  [RSAF](https://github.com/chenxiaolong/RSAF) and the NAS appears in the
  system file picker; VideoCast streams straight from it. Working Synology
  FTPS recipe — remote options `explicit_tls=true`, `shut_timeout=5s`,
  `close_timeout=5s`, `idle_timeout=5m` (+ `no_check_certificate=true` for a
  self-signed cert), and RSAF per-remote "Custom VFS options"
  `vfs_read_chunk_size=off`, `vfs_cache_mode=full`, `vfs_cache_max_age=1h`.
  Without the timeout/chunking tweaks, seeks stall for up to a minute
  (FTP aborts wait out the server's close status; the mobile-tuned chunking
  reopens a TLS data connection every 8 MiB).
- Play/pause/seek controls, Cast media notification, and a foreground
  service so the server survives screen-off.

CI (GitHub Actions) runs the unit tests on every push and publishes the APK
to the rolling ["latest" release](../../releases/tag/latest) — that's the
install channel (a permanent tap-to-download link, unlike workflow artifacts,
which expire and require a login). Versions derive from git: versionCode is
the commit count, versionName is `<commit date>-r<count> (<sha>)`. Builds are
signed with the committed `debug.keystore` so each APK installs over the
previous one; a debug key confers no authority, which is why committing it
is an acceptable personal-app tradeoff.

## Building

```
./gradlew assembleDebug
```

Requires JDK 21 and the Android SDK (platform 35).

### OpenSubtitles API key

Subtitle search needs an API key from
<https://www.opensubtitles.com/en/consumers>. Without one, search fails
with a clear message (and, on OpenSubtitles' side, HTTP 403
"You cannot consume this service"). Everything else works without it.

- **CI builds (the install channel)**: add a repository secret named
  `OPENSUBTITLES_API_KEY` (GitHub → Settings → Secrets and variables →
  Actions), then re-run the workflow. The key is baked into the APK.
- **Local builds**: put it in `~/.gradle/gradle.properties` (keeps it out
  of this public repo): `OPENSUBTITLES_API_KEY=yourKeyHere`

## Testing

```
./gradlew test          # subtitle conversion + JLHTTP range/CORS contract tests
```

On-device verification of the server (milestone M2 in ARCHITECTURE.md):

```
curl -sI  http://<phone-ip>:8394/video                 # Accept-Ranges: bytes
curl -s -H "Range: bytes=100-199" -o /dev/null \
     -w "%{http_code} %{size_download}\n" \
     http://<phone-ip>:8394/video                      # 206 100
curl -sI  http://<phone-ip>:8394/subs.vtt              # text/vtt + CORS header
```
