# VideoCast — Architecture

A minimal personal Android app: cast local and NAS video files to Chromecast
with subtitle support. Replaces Web Video Caster for one specific workflow.
(NAS access is deliberately not the app's concern: an FTPS documents
provider — RSAF — makes remote files look like ordinary SAF documents; the
README carries the working recipe.)

## The core problem shape

Chromecast (default media receiver) cannot read files off the phone. It only
fetches media over HTTP from a URL reachable on the LAN. So the app is three
cooperating pieces:

1. **An HTTP server on the phone** that serves the picked video (with
   `Range`/206 support, or seeking breaks) and one subtitle track (with CORS
   headers, or the receiver silently drops it).
2. **A Cast sender** that tells the default receiver to load
   `http://<phone-ip>:<port>/video` with a sidecar text track.
3. **A subtitle pipeline** that turns whatever source is at hand — a sibling
   file, an OpenSubtitles download, a track embedded in the video — into
   WebVTT, the only sidecar format the default receiver reliably renders.

Two receiver constraints shape most of the interesting design below:

- It never demuxes subtitles from progressive streams — embedded tracks must
  be extracted on the phone and served as a sidecar like any other source.
- A loaded item's track list is immutable and the sidecar is fetched once —
  changing subtitles mid-play means reloading the media at the current
  position.

Everything else (UI, foreground service, notification) exists to keep those
pieces alive and controllable.

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
│  Subtitle pipeline (all sources → one acquisition path)          │
│   ├─ SubtitleConverter: SRT/ASS → WebVTT (cues → VTT rendering)  │
│   ├─ OpenSubtitlesClient (api.opensubtitles.com, title search)   │
│   ├─ EmbeddedSubtitles: MKV (own EBML walker) + MP4 tracks       │
│   ├─ SiblingSubtitles: same-folder files via a SAF tree grant    │
│   └─ LanguageTag: ISO 639 normalization every tag converges on   │
└──────────────────────────────────────────────────────────────────┘
```

### Package layout

```
com.newash.videocast
├── App.kt                     Application; notification channel; crash file
├── MainActivity.kt            system-widget UI, SAF pickers, render(UiState)
├── MainViewModel.kt           single immutable UiState in a StateFlow (UDF)
├── CastOptionsProvider.kt     default receiver ID + notification options
├── cast/CastPlayer.kt         session mgmt, load w/ tracks, play/pause/seek
├── server/MediaServer.kt      JLHTTP: /video (Range/206), /subs.vtt (CORS)
├── server/ServerHolder.kt     process-wide server lifecycle + port fallback
├── server/StreamingService.kt foreground service; wifi + wake locks
├── subs/SubtitleConverter.kt  SRT/ASS/VTT detection + conversion (unit tested)
├── subs/OpenSubtitlesClient.kt REST client: title search + anonymous download
├── subs/EmbeddedSubtitles.kt  container sniff + track facade over the two below
├── subs/MkvSubtitles.kt       hand-rolled EBML walker w/ Cues fast path (tested)
├── subs/Mp4Subtitles.kt       tx3g timed text via platform MediaExtractor
├── subs/SiblingSubtitles.kt   sibling-file matcher + SAF tree lookup (tested)
├── subs/LanguageTag.kt        ISO 639 normalization/matching (tested)
├── util/NetworkUtils.kt       LAN IPv4 discovery (prefer wlan)
└── util/Streams.kt            bounded whole-stream read
```

## Tech choices

| Concern | Choice | Why |
|---|---|---|
| Language / style | Kotlin, functional-leaning: single immutable `UiState`, `val`s, extensions, pure functions (I/O and server internals stay pragmatically imperative) | Compact, testable; the UI is a dumb function of state. |
| UI | Classic Views, one XML layout of system-default widgets; unicode glyphs (▶ ⏸ ⏪ ⏩ ✕) and vector drawables only | No Compose: saves megabytes of dependencies for a one-screen app. No styling for styling's sake, no bitmaps. Activity extends `AppCompatActivity` because the MediaRouter cast dialog requires an AppCompat theme. |
| Cast | `play-services-cast-framework` (Cast v3 sender) + default media receiver (`CC1AD845`) | The framework handles discovery, session lifecycle, the media notification, and lock-screen controls for free. No custom receiver to host or register. |
| HTTP server | **JLHTTP 3.2** | See below. |
| HTTP client / JSON | `HttpURLConnection` + Android's built-in `org.json` | Two REST endpoints don't justify OkHttp + a serialization library (~2 MB of APK). |
| File access | Storage Access Framework (`ACTION_OPEN_DOCUMENT`) | No storage permissions needed on any Android version; content URIs work on scoped storage — and NAS providers like RSAF plug in transparently. |
| DI / architecture framework | None | Personal app, one screen. Manual wiring in the ViewModel. |

Total third-party dependency surface: JLHTTP (~59 KB) and juniversalchardet
(~250 KB, subtitle charset detection) plus the unavoidable androidx/Cast
libraries.

### Why JLHTTP

`net.freeutils:jlhttp` is a single-source-file, ~59 KB, zero-dependency,
actively maintained, RFC 9110/9112-conformant embedded server on plain
blocking sockets. Crucially, Range handling is *library* code: it parses
(and RFC-correctly ignores invalid) `Range` headers and emits
206/`Content-Range`/416 itself — the classic Chromecast-seek failure points
are not ours to get wrong. HEAD and OPTIONS are handled per spec. Our code
is two small context handlers; a JVM contract test (`JlhttpContractTest`)
pins the range/CORS/HEAD semantics over real HTTP.

Rejected: NanoHTTPD (the original choice — abandoned since 2016, unpatched
CVE, hand-rolled ranges), microhttp (buffers whole bodies in memory —
disqualifying for multi-GB video), Ktor (megabytes of dependency tree for
two routes), Jetty/Netty/Undertow (not meant for embedding in an app
process).

### Serving details that make or break casting

- **Range/206** (`/video`): JLHTTP does the protocol; we add
  `Accept-Ranges: bytes` and open a fresh `ContentResolver` stream per
  request, wrapped so `skip()` falls back to reading — Chromecast issues a
  new range request on every seek, and some `DocumentsProvider` streams
  refuse `skip()`, which JLHTTP's slicing relies on.
- **CORS** (matters for `/subs.vtt`, applied to every response): the
  receiver fetches text tracks with CORS enforced —
  `Access-Control-Allow-Origin: *` plus OPTIONS preflight, or subs silently
  fail.
- **`/subs.vtt` never 404s**: with no subtitle set it serves an empty VTT.
  A failed sidecar fetch can abort the receiver's whole media load, so the
  route must always answer. `Cache-Control: no-store` keeps the receiver
  from reusing a stale track across reloads.
- **MIME**: video content type from `ContentResolver`/extension (fallback
  `video/mp4`); subtitles as `text/vtt; charset=utf-8`.
- **Fixed port with fallback** (8394, +1 up to ten tries), bound on all
  interfaces; URL built from the Wi-Fi interface IPv4.

## The subtitle system

### One acquisition pipeline, three sources

Every subtitle — sibling file, OpenSubtitles download, embedded track —
flows through a single ViewModel pipeline (`acquireSubtitle`); a source
contributes only its fetch step. The pipeline owns everything the sources
share:

- **Supersession**: at most one acquisition runs; starting a new one cancels
  the previous — except an *automatic* pick never cancels an *explicit* user
  choice (the app's guess must not kill the user's action).
- **Language memory**: explicit picks update the remembered languages
  (the app's only persisted preference, shared by auto-select and the
  OpenSubtitles search field); automatic picks don't.
- **Stale-apply guard**: a result is dropped if the video changed while it
  was being fetched.
- **Progress and error surfaces**: one status line shows "reading … · N%"
  during extraction and the error afterwards; auto and manual runs are
  labeled differently.
- **Apply + re-cast**: a subtitle arriving mid-play triggers the
  reload-at-position described below — no matter which source it came from.

Nothing is written to disk: fetched and extracted subtitles live in memory
(the served VTT is a string field on the server).

### Conversion — everything becomes VTT

SRT → VTT is a header plus `,` → `.` timestamps; ASS → VTT parses the
`[Events]` section by its `Format:` line, strips `{\...}` override tags,
and discards styling (text-only is the stated scope); VTT passes through.
All routes converge on one cue model (`Cue` → `cuesToVtt`), which sorts and
drops empty cues. Encoding: BOM detection, then Mozilla's charset detector
(juniversalchardet — the one job a small library genuinely beats
hand-rolling: legacy SRTs come in windows-125x/ISO-8859-x/CJK codepages),
then strict UTF-8 with a windows-1252 fallback.

### Embedded tracks — the MKV walker

MP4 (tx3g timed text) goes through the platform `MediaExtractor`:
sample-table driven, reads only the text track's bytes. MKV needs
`MkvSubtitles`, a hand-rolled EBML walker (SRT/ASS/VTT codecs, track
language and title), because Android's Matroska extractor silently drops
subtitle tracks on every Android version.

Extraction prefers the MKV **Cues index** (SeekHead → Cues; mkvmerge and
ffmpeg index every subtitle frame by default): on a seekable descriptor it
jumps straight to the indexed subtitle blocks and reads well under 1% of
the file. Files without a usable index fall back to a cluster scan whose
skip-vs-read threshold is calibrated from the measured seek cost — network
providers (seconds per out-of-order read) stay near-sequential, local files
seek freely. A cheap header probe at pick time decides whether the
"In video" button lights up; extraction is cancellable, with interrupt
checks in the read loops so cancellation actually stops the I/O.

### Auto-select on pick

When a video is picked, the app tries, in strict order, first hit wins:
(1) a sibling file tagged with a saved language (`.en.` / `.eng.`, extra
tokens like `.forced.` allowed); (2) a plain undecorated sibling
(`base.srt`); (3) an embedded track in a saved language. SAF grants are
per-document, so rules 1–2 need a persisted tree grant
(`ACTION_OPEN_DOCUMENT_TREE`, offered once via the tappable status line,
then matched to picked videos by document-ID prefix); rule 3 needs nothing.
Sibling matching is a pure function (`SiblingSubtitles.bestMatch`).

### Casting barely waits, and subtitles cover from 0:00

The naive orders are both wrong: extract-then-cast delays playback by the
extraction (minutes on a cold NAS file), cast-then-extract loses the
subtitles of the opening scene, because the receiver fetches the sidecar
once and the track list is immutable after load. The resolution:

- Extraction starts at pick time, so by Play it has typically covered many
  minutes of the file.
- Play asks the running extraction for a snapshot of the cues collected so
  far (emitted at the next block boundary — usually milliseconds; a ~4 s
  gate bounds the wait) and loads with that snapshot as the active track.
  Near-instant start, subtitles from 0:00.
- When the extraction completes mid-playback, the app reloads at the live
  position with a bumped `?v=` track URL — one short hiccup, then the
  complete cue set. The same reload path serves any subtitle picked or
  downloaded mid-play.

The extraction's sequential read overlaps the rclone VFS cache of the cast
stream, so network cost stays ~1× the file. Evaluated and rejected: a tee
inside the server (cues trail the playhead), growing/streamed sidecars (the
receiver fetches once), and multi-track activation ratchets (rest on
undocumented receiver fetch timing).

### Attaching the track

`MediaTrack(id=1, TYPE_TEXT, SUBTYPE_SUBTITLES, contentId=…/subs.vtt?v=N,
contentType=text/vtt)` on the `MediaInfo`, activated via
`setActiveTrackIds([1])` *on the load request* — activating at load time is
reliable, toggling afterwards is not.

## Surviving screen-off

`StreamingService` is a foreground service (`mediaPlayback` type) that
keeps the process — and with it `ServerHolder`'s JLHTTP instance — alive,
holding a `WifiLock` (`FULL_HIGH_PERF`) and a partial wake lock. The Cast
framework's own `MediaNotificationService` provides the rich media
notification with transport controls; the service's own notification exists
only to satisfy the foreground requirement. The service starts when casting
begins and stops — releasing locks and server — when the Cast session
terminally ends; transient suspensions (brief Wi-Fi drops) keep it alive so
playback can recover.

Control from the TV remote needs no phone involvement: CEC goes to the
Chromecast and the receiver handles play/pause/seek itself. The phone only
sees the consequences (a seek arrives as a new Range request) and the UI
stays in sync by polling `RemoteMediaClient`.

## CI & releases

GitHub Actions (`.github/workflows/android.yml`): on every push, run the
unit tests and build the debug APK. Master builds also publish the APK to
the rolling `latest` prerelease — that's the install channel (workflow
artifacts expire and require a login; see README). Versions derive from git
(`versionCode` = commit count) and builds are signed with the committed
debug keystore so updates install over each other.

## Non-goals

Web video detection, non-Chromecast receivers, queues/playlists,
transcoding, image-based subtitles (PGS/VobSub), Play Store publishing,
moviehash-based subtitle lookup (title search is enough here).
