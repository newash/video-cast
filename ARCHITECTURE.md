# VideoCast — Architecture & Build Plan

A minimal personal Android app: cast local video files to Chromecast with
subtitle support. Replaces Web Video Caster for one specific workflow.

## The core problem shape

Chromecast (default media receiver) cannot read files off the phone. It can
only fetch media over HTTP from a URL reachable on the LAN. So the app is
really three cooperating pieces:

1. **An HTTP server on the phone** that serves the picked video file (with
   `Range`/206 support, or seeking breaks) and a subtitle track (with CORS
   headers, or the receiver silently drops it).
2. **A Cast sender** that tells the default receiver to load
   `http://<phone-ip>:<port>/video` with a sidecar text track.
3. **A subtitle pipeline** that turns whatever we have (local SRT/ASS, or an
   SRT downloaded from OpenSubtitles) into WebVTT, the only sidecar format
   the default receiver reliably renders.

Everything else (UI, foreground service, notification) exists to keep those
three alive and controllable.

## Component overview

```
┌───────────────────────────── Phone ─────────────────────────────┐
│                                                                  │
│  MainActivity (system widgets, one render(state) fn)             │
│   ├─ SAF pickers (video, subtitle)     MainViewModel             │
│   ├─ Cast button (MediaRouteButton)    (single UiState flow)     │
│   └─ transport controls ────────────► CastPlayer ────────────────┼──► Cast SDK
│                                          │  load(MediaInfo +     │    (session,
│  StreamingService (foreground)           │   MediaTrack[VTT])    │     RemoteMediaClient,
│   └─ owns MediaServer (JLHTTP)          │                       │     media notification)
│        GET /video      ◄─────────────────┼───────────────────────┼──◄ Chromecast fetches
│        GET /subs.vtt   ◄─────────────────┘                       │    Range + CORS
│                                                                  │
│  Subtitle pipeline                                               │
│   ├─ SubtitleConverter: SRT/ASS → WebVTT                         │
│   └─ OpenSubtitlesClient (api.opensubtitles.com, title search)   │
└──────────────────────────────────────────────────────────────────┘
```

### Package layout

```
com.newash.videocast
├── App.kt                     Application; notification channel
├── MainActivity.kt            system-widget UI, SAF pickers, render(UiState)
├── MainViewModel.kt           single immutable UiState in a StateFlow (UDF)
├── CastOptionsProvider.kt     default receiver ID + notification options
├── cast/CastPlayer.kt         session mgmt, load w/ tracks, play/pause/seek
├── server/MediaServer.kt      JLHTTP: /video (Range/206), /subs.vtt (CORS)
├── server/ServerHolder.kt     process-wide server lifecycle + port fallback
├── server/StreamingService.kt foreground service; wifi + wake locks
├── subs/SubtitleConverter.kt  SRT/ASS/VTT detection + conversion (unit tested)
├── subs/OpenSubtitlesClient.kt REST client: title search + anonymous download
└── util/NetworkUtils.kt       LAN IPv4 discovery (prefer wlan)
```

## Tech choices

| Concern | Choice | Why |
|---|---|---|
| Language / style | Kotlin, functional-leaning: single immutable `UiState`, `val`s, extensions, pure functions (I/O and server internals stay pragmatically imperative) | Compact, testable; the UI is a dumb function of state. |
| UI | Classic Views, one XML layout of system-default widgets; unicode glyphs (▶ ⏸ ⏪ ⏩ ✕) and vector drawables only | No Compose: saves megabytes of dependencies for a one-screen app. No styling for styling's sake, no bitmaps. Activity extends `AppCompatActivity` because the MediaRouter cast dialog requires an AppCompat theme. |
| Cast | `play-services-cast-framework` (Cast v3 sender) + default media receiver (`CC1AD845`) | The framework handles discovery, session lifecycle, the media notification, and lock-screen controls for free — that's the "Cast notification" requirement done by configuration, not code. No custom receiver to host or register. |
| HTTP server | **JLHTTP 3.2** | See justification below. |
| HTTP client / JSON | `HttpURLConnection` + Android's built-in `org.json` | Two REST endpoints don't justify OkHttp + a serialization library (~2 MB of APK). |
| File access | Storage Access Framework (`ACTION_OPEN_DOCUMENT`) | No storage permissions needed on any Android version; content URIs work on scoped storage. |
| DI / architecture framework | None | Personal app, one screen. Manual wiring in the ViewModel. |

Total third-party dependency surface: JLHTTP (~59 KB) and
juniversalchardet (~250 KB, subtitle charset detection) plus the unavoidable
androidx/Cast libraries.

### Why JLHTTP (the server justification)

Candidates considered:

- **JLHTTP** (`net.freeutils:jlhttp`, the choice) — a single-source-file,
  ~59 KB, zero-dependency, actively maintained, RFC 9110/9112-conformant
  embedded server on plain blocking sockets. Crucially for this app it has
  *native* Range support: `Request.getRange()` parses (and RFC-correctly
  ignores invalid) Range headers, and `Response.sendHeaders(..., range)`
  emits 206/`Content-Range` — the classic Chromecast-seek failure points are
  library code, not ours. HEAD and OPTIONS are handled per spec. Our code is
  two small context handlers: CORS headers (the silent-subtitle-drop
  failure point) plus a stream pre-skip with a read fallback, because some
  `DocumentsProvider` streams refuse `skip()`, which JLHTTP's own range
  skipping relies on. A JVM contract test (`JlhttpContractTest`) pins the
  range/CORS/HEAD semantics over real HTTP.
- **NanoHTTPD** — the traditional choice (and this project's original one):
  same weight class, but abandoned since 2016 with an unpatched CVE, no
  built-in ranges (we hand-rolled them), and quirks like sending full bodies
  for HEAD. Swapped out once JLHTTP proved a strict superset; done before
  first on-device testing so the substrate is only verified once.
- **microhttp** — modern and tiny, but buffers response bodies fully in
  memory: disqualifying for multi-GB video.
- **Ktor (CIO engine)** — modern and maintained, and `PartialContent` +
  `CORS` plugins do the hard parts. But it pulls in a large dependency tree,
  slows builds, grows the APK by megabytes, and its plugin abstractions get
  in the way when debugging why a Chromecast rejected a specific byte-range
  response. Overkill for two routes.
- **Jetty / Netty / Undertow / AndroidServer / http4k** — heavier still,
  unmaintained on Android, or not designed for embedding in an app process.

### Serving details that make or break casting

- **Range/206** (`/video`): parsing and 206/`Content-Range`/416 emission are
  JLHTTP's (RFC 9110); we add `Accept-Ranges: bytes` and open a fresh
  `ContentResolver` stream per request, pre-skipped to the offset with a
  read fallback — Chromecast issues a new range request on every seek, and
  open-skip is the only approach that works across all `DocumentsProvider`s.
- **CORS** (`/subs.vtt` — and harmlessly on everything): the default
  receiver's player fetches text tracks with CORS enforced;
  `Access-Control-Allow-Origin: *` (plus `OPTIONS` preflight handling) or
  subs silently fail. This is baked into every response the server sends.
- **MIME**: video content type from `ContentResolver`/extension (fallback
  `video/mp4`); subtitles served as `text/vtt; charset=utf-8`.
- **Fixed port with fallback** (8394, then +1 up to a few tries), bound on
  all interfaces; URL built from the Wi-Fi interface IPv4.

### Subtitle pipeline

- **Conversion**: the default receiver renders only WebVTT/TTML sidecars, so
  everything converges on VTT, generated in memory and held by the server:
  - SRT → VTT: `WEBVTT` header, `,` → `.` in timestamps, cue text passed
    through (basic `<i>/<b>` tags survive fine).
  - ASS → VTT: take the `[Events]` section, use its `Format:` line for field
    order, strip `{\...}` override tags, `\N` → newline, `H:MM:SS.cc` → VTT
    timestamps. Styling is discarded — text-only is the stated scope.
  - Already-VTT files pass through.
  - Encoding: BOM detection (UTF-8/16LE/16BE), then Mozilla's charset
    detector (juniversalchardet, ~250 KB — the one hand-rolled piece a small
    library genuinely beats: legacy SRTs come in windows-125x/ISO-8859-x/CJK
    codepages), then strict UTF-8 with a windows-1252 fallback.
- **OpenSubtitles** (`api.opensubtitles.com/api/v1`, baked-in API key, no
  login): title-query search only (`GET /subtitles?query=…&languages=…`,
  prefilled from the cleaned filename), ordered by download count. Download
  is `POST /download {file_id}` → temp link → fetch SRT → same conversion
  path. Anonymous quota (5/day) is fine for personal use.
- **Casting the track**: `MediaTrack(id=1, TYPE_TEXT, SUBTYPE_SUBTITLES,
  contentId=http://…/subs.vtt, contentType=text/vtt)` attached to
  `MediaInfo`, activated via `setActiveTrackIds([1])` on the load request —
  activating at load time is far more reliable than toggling after.

### Surviving screen-off

`StreamingService` is a foreground service (`mediaPlayback` type) that owns
the JLHTTP instance, holds a `WifiLock` (`FULL_HIGH_PERF`) and a partial
wake lock while a session is active, and shows a minimal persistent
notification. The Cast framework's own `MediaNotificationService` (enabled
via `CastOptionsProvider`) provides the rich media notification with
play/pause controls. The service starts when casting begins and stops —
releasing both locks and the server — as soon as the Cast session terminally
ends (`onSessionEnded`); transient suspensions (brief Wi-Fi drops) keep it
alive so playback can recover.

Control from the TV remote needs no phone involvement: CEC commands go to
the Chromecast and the default receiver handles play/pause/seek itself. The
phone only sees the consequences (a seek arrives as a new Range request) and
the app UI stays in sync by polling `RemoteMediaClient`.

## CI

GitHub Actions (`.github/workflows/android.yml`): on every push, run the
unit tests, build the debug APK, and upload it as a workflow artifact —
that's the install channel for a personal app (no Play Store).

## Build plan — verifiable milestones

Each milestone leaves the repo in a state where something concrete can be
checked.

**M1 — Skeleton that builds.**
Gradle project, manifest, empty activity, all dependencies resolved; CI
workflow in place.
✔ Verify: the Android CI workflow is green and produces an APK artifact.

**M2 — File picking + HTTP server with Range.**
SAF video picker; `StreamingService` + `MediaServer` serving `/video`.
✔ Verify: the `JlhttpContractTest` range/CORS/HEAD contract tests; then pick a file on the phone and,
from a laptop on the same LAN:
`curl -sI http://<phone>:8394/video` shows `Accept-Ranges: bytes`;
`curl -s -H "Range: bytes=100-199" -o /dev/null -w "%{http_code} %{size_download}"`
prints `206 100`; and `curl` with no range returns the full byte count.

**M3 — Basic casting.**
Cast button, session management, load `/video` on the default receiver,
play/pause/±seek buttons and a position slider.
✔ Verify: video plays on TV; seeking works (this proves M2's ranges under
the real client); play/pause from the app works; Cast media notification
appears; playback continues with the phone screen off (proves the
foreground service + locks).

**M4 — Local subtitles.**
SAF subtitle picker, SRT→VTT and ASS→VTT conversion, `/subs.vtt` with CORS,
track attached and active at load.
✔ Verify: unit tests for the converters (timestamps, ASS tag stripping,
windows-1252 fallback); on-TV: subs render for an SRT and an ASS file;
`curl -sI http://<phone>:8394/subs.vtt` shows `Access-Control-Allow-Origin: *`
and `Content-Type: text/vtt`.

**M5 — OpenSubtitles.**
Title-query search (prefilled from the filename), result list, anonymous
download, feed into the M4 pipeline.
✔ Verify: search a known movie title end-to-end and see the downloaded subs
render on TV; error paths (bad key, quota, no results) show readable
messages.

**M6 — Polish.**
Error surfacing (no Wi-Fi, port busy, no session), remember last language,
app icon.
✔ Verify: manual pass through each failure path shows a readable message
instead of a crash.

## Non-goals (unchanged from the brief)

Web video detection, non-Chromecast receivers, queues/playlists,
transcoding, image-based subtitles (PGS/VobSub), Play Store publishing,
moviehash-based subtitle lookup (title search is enough here).
