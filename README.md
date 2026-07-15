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
- Subtitles: pick a local SRT/ASS file, or search OpenSubtitles by title
  (anonymous downloads). Everything is converted to WebVTT and served with
  CORS headers — the only way the default receiver renders sidecar text
  tracks.
- Play/pause/seek controls, Cast media notification, and a foreground
  service so the server survives screen-off.

CI (GitHub Actions) runs the unit tests and uploads a debug APK artifact on
every push — that's the install channel.

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
