package com.newash.videocast

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newash.videocast.cast.CastPlayer
import com.newash.videocast.server.MediaServer
import com.newash.videocast.server.StreamingService
import com.newash.videocast.subs.EmbeddedSubtitles
import com.newash.videocast.subs.LanguageTag
import com.newash.videocast.subs.OpenSubtitlesClient
import com.newash.videocast.subs.SiblingSubtitles
import com.newash.videocast.subs.SubtitleConverter
import com.newash.videocast.util.localIpv4
import com.newash.videocast.util.readAtMost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

data class VideoFile(val uri: Uri, val name: String, val sizeBytes: Long, val mime: String)

enum class SubtitleSource(val marker: String) { FILE("📄"), ONLINE("🌐"), EMBEDDED("🎞️") }

/** A ready-to-serve subtitle: converted VTT plus everything the UI shows about it. */
data class SubtitleTrack(
    val name: String,
    val vtt: String,
    /** Normalized tag ("en", "pt-br") or null when genuinely unknown. */
    val language: String?,
    val source: SubtitleSource,
    /** True when the app picked it, as opposed to the user. */
    val auto: Boolean = false,
)

/** A running subtitle acquisition (extraction or slow read), shown on the subtitle line. */
data class Extraction(val label: String, val auto: Boolean, val percent: Int? = null, val runId: Int = 0)

data class SearchState(
    val results: List<OpenSubtitlesClient.Result> = emptyList(),
    val searching: Boolean = false,
    /** Error / progress / empty-result message shown inside the dialog. */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** Last submitted (or prefilled) inputs. */
    val query: String = "",
    val languages: String = "en",
)

data class UiState(
    val video: VideoFile? = null,
    val subtitle: SubtitleTrack? = null,
    val cast: CastPlayer.Progress = CastPlayer.Progress(),
    /** App-level error message shown in the bottom status line. */
    val note: String? = null,
    /** Full stack of the previous run's crash; shown as a tappable one-liner. */
    val crash: String? = null,
    /** Cast pressed, receiver hasn't accepted the load yet. */
    val loading: Boolean = false,
    /**
     * The video this app run loaded on the receiver; null means any media there
     * is a leftover stream from a previous run (controllable, but not seekable —
     * its server is gone) and must never be re-cast over.
     */
    val castVideo: VideoFile? = null,
    /**
     * Subtitle name active on the receiver. Arrivals auto-re-cast, so the manual
     * re-cast affordance effectively surfaces only after clearing mid-cast.
     */
    val castSubtitle: String? = null,
    /** null = search dialog closed. */
    val search: SearchState? = null,
    /** Text subtitle tracks found inside the picked video. */
    val embeddedTracks: List<EmbeddedSubtitles.Track> = emptyList(),
    /** The subtitle acquisition in progress; null when idle. */
    val extraction: Extraction? = null,
    /** Subtitle-step error, shown red on the subtitle line (not the bottom status). */
    val subtitleError: String? = null,
    /** The remembered subtitle language(s), shown in the step header. */
    val languages: String = "en",
    /** Set when sibling-subtitle auto-pick needs folder access; tapping the status line grants it. */
    val subtitleFolderHint: Uri? = null,
) {
    val defaultQuery: String get() = video?.name?.toSearchQuery().orEmpty()
    val subtitleDirty: Boolean get() = cast.hasMedia && subtitle?.name != castSubtitle
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // null when Play Services is unavailable — casting is then simply off.
    private val castPlayer: CastPlayer? by lazy {
        runCatching { CastPlayer(app, ::onSessionTerminated) }.getOrNull()
    }

    private val openSubtitles = OpenSubtitlesClient(BuildConfig.OPENSUBTITLES_API_KEY)

    /**
     * The LAN server, owned solely by the ViewModel: started (synchronously,
     * before the load request needs its URL) at cast, stopped at session end.
     * One owner means the stop-then-play races the old process-wide holder
     * defended against cannot happen. Volatile: started on IO, read on Main.
     */
    @Volatile
    private var server: MediaServer? = null

    /** Call on IO (socket bind). Tries a few ports: 8394 may linger in TIME_WAIT. */
    @Synchronized
    private fun ensureServer(): MediaServer = server ?: run {
        var lastError: IOException? = null
        (MediaServer.DEFAULT_PORT until MediaServer.DEFAULT_PORT + 10)
            .firstNotNullOfOrNull { port ->
                try {
                    MediaServer(app, port).also(MediaServer::start)
                } catch (e: IOException) {
                    lastError = e
                    null
                }
            }?.also { server = it } ?: throw (lastError ?: IOException("could not start media server"))
    }

    /**
     * One subtitle acquisition run — everything the run owns, cancellable as a
     * unit. Per-run ownership is the concurrency model: a lagging cancelled
     * run (blocked network read) can only ever touch its own object, so no
     * cross-run identity checks are needed. Supersession = cancel + replace,
     * atomic on Main.
     */
    private class Acquisition(val runId: Int) {
        lateinit var job: Job

        /** The open stream — closed from outside to abort a blocked read. */
        val stream = AtomicReference<AutoCloseable?>()

        /** Cues-so-far of an MKV extraction, published at block boundaries on the IO thread. */
        @Volatile
        var cues: List<SubtitleConverter.Cue>? = null

        /** True when a cast went out carrying this run's partial cues. */
        var prefixCast = false

        fun cancel() {
            job.cancel()
            stream.getAndSet(null)?.let { runCatching(it::close) }
        }
    }

    private var acquisition: Acquisition? = null
    private var nextRunId = 0

    private var searchJob: Job? = null
    private var probeJob: Job? = null

    /** The auto-select chain. Any explicit subtitle action cancels it — the
     * app's guess must never race the user's choice. */
    private var autoJob: Job? = null

    /** The one load in flight: starting a new cast cancels it, so a superseded
     * load's verdict never acts. */
    private var castJob: Job? = null

    /** Bumped per subtitle change: a fresh ?v= makes the receiver re-fetch on reload. */
    private var vttVersion = 0

    private val prefs get() = app.getSharedPreferences("videocast", Context.MODE_PRIVATE)

    /** One remembered subtitle language, shared by OpenSubtitles search and auto-select. */
    private var rememberedLanguages: String
        get() = prefs.getString(PREF_SUBTITLE_LANGUAGES, null)?.takeIf { it.isNotBlank() } ?: "en"
        set(value) {
            value.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(PREF_SUBTITLE_LANGUAGES, it).apply() }
            _state.update { it.copy(languages = rememberedLanguages) }
        }

    /** First remembered language as a normalized tag — the auto-select target. */
    private val rememberedLanguageTag: String
        get() = LanguageTag.primary(rememberedLanguages.substringBefore(',')) ?: "en"

    init {
        _state.update { it.copy(languages = rememberedLanguages) }
        App.consumeLastCrash(app)?.let { crash -> _state.update { it.copy(crash = crash) } }
        // The Cast SDK requires main-thread access; poll instead of juggling
        // per-session progress listeners. Skip the work while nothing collects
        // (screen off / app backgrounded).
        viewModelScope.launch {
            val player = castPlayer ?: return@launch
            while (isActive) {
                if (_state.subscriptionCount.value > 0) {
                    runCatching(player::progress).onSuccess { p ->
                        // loading clears when the load's verdict lands — old media
                        // lingering through a re-cast must not re-enable Play mid-load.
                        _state.update { it.copy(cast = p) }
                    }
                }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        castPlayer?.release()
    }

    fun dismissCrash() = _state.update { it.copy(crash = null) }

    /** Runs [block], reporting any failure through [onError]; cancellation passes through. */
    private suspend fun reporting(onError: (Exception) -> Unit, block: suspend () -> Unit) =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }

    /** Applies [transform] only while [uri] is still the picked video — picks race async work. */
    private fun updateIfCurrent(uri: Uri, transform: (UiState) -> UiState) =
        _state.update { if (it.video?.uri == uri) transform(it) else it }

    // ------------------------------------------------------------ video pick

    fun onVideoPicked(uri: Uri) {
        cancelAcquisition() // a previous video's acquisition (or pending guess) must not land on this one
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            // DocumentsProvider queries are cross-process IPC — keep them off Main.
            val video = withContext(Dispatchers.IO) { app.contentResolver.describeVideo(uri) }
            _state.update {
                it.copy(
                    video = video,
                    subtitle = null, // the old video's cues must never be cast with this one
                    embeddedTracks = emptyList(),
                    subtitleFolderHint = null,
                    subtitleError = null,
                    // Error prevention beats error reporting: warn at pick time.
                    note = if (video.sizeBytes <= 0) {
                        "Video size unknown — casting will fail; pick it via a different provider"
                    } else {
                        null
                    },
                )
            }
            probeAndAutoSelect(uri)
        }
    }

    /**
     * Container-header probe (cheap even for network files, so failures stay
     * silent), then the auto-select chain. The chain runs in its own [autoJob]
     * so an explicit subtitle action can kill the guess without killing the
     * probe — the "In video" button must still light up.
     */
    private suspend fun probeAndAutoSelect(uri: Uri) {
        val preferred = rememberedLanguageTag
        // runInterruptible: cancellation must actually stop the blocking reads —
        // and must pass through, not be swallowed into an empty result.
        val tracks = try {
            runInterruptible(Dispatchers.IO) { EmbeddedSubtitles.listTracks(app, uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }.sortedByDescending { LanguageTag.matches(it.language, preferred) }
        updateIfCurrent(uri) { it.copy(embeddedTracks = tracks) }
        autoJob = viewModelScope.launch { autoSelectSubtitle(uri, tracks, preferred) }
    }

    // ----------------------------------------------------------- auto-select

    /**
     * Auto-pick for a fresh video, in strict order: sibling file tagged with the
     * saved language, then a plain undecorated sibling, then an embedded track
     * in the saved language. Sibling lookup needs a persisted folder grant; when
     * it's missing, the embedded rule still applies and the status line offers
     * the one-time grant.
     *
     * Runs inside [autoJob], which every explicit subtitle action cancels — so
     * past the entry check, the guess cannot race the user's choice.
     */
    private suspend fun autoSelectSubtitle(uri: Uri, tracks: List<EmbeddedSubtitles.Track>, lang: String) {
        // The guess fills a vacuum only: a subtitle chosen (or being acquired)
        // before this job even started wins outright.
        if (_state.value.subtitle != null || acquisition?.job?.isActive == true) return
        val video = _state.value.video?.takeIf { it.uri == uri } ?: return
        val sibling = try {
            runInterruptible(Dispatchers.IO) {
                SiblingSubtitles.find(app, video.uri, video.name, LanguageTag.fileNameTags(lang))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SiblingSubtitles.Result.None
        }
        // Rules 1–2 blocked by the sandbox: offer the grant even when rule 3
        // lands, since a granted folder would outrank the embedded pick next time.
        if (sibling is SiblingSubtitles.Result.NoAccess) {
            updateIfCurrent(uri) { it.copy(subtitleFolderHint = uri) }
        }
        when {
            sibling is SiblingSubtitles.Result.Found -> onSubtitlePicked(sibling.uri, auto = true)
            else -> tracks.firstOrNull { LanguageTag.matches(it.language, lang) }
                ?.let { pickEmbeddedTrack(it, auto = true) }
        }
    }

    /** The one-time folder grant from the status-line hint; then auto-pick reruns. */
    fun onSubtitleFolderPicked(treeUri: Uri) {
        cancelAcquisition() // the re-probe supersedes any auto-started extraction
        runCatching {
            app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _state.update { it.copy(subtitleFolderHint = null) }
        val uri = _state.value.video?.uri ?: return
        probeJob?.cancel()
        probeJob = viewModelScope.launch { probeAndAutoSelect(uri) }
    }

    // -------------------------------------------------- subtitle acquisition

    /**
     * Shared tail of every subtitle source — embedded, file, online. Owns job
     * bookkeeping, the subtitle-line progress/error surfaces, language memory,
     * the stale-apply guard, and the final applySubtitle + re-cast. [fetch]
     * runs on IO and returns the ready track; per-source code reduces to that
     * fetch plus its surface deltas.
     *
     * An explicit pick supersedes whatever acquisition is running; an [auto]
     * pick yields to it instead — the app's guess must never kill the user's
     * choice. [progress] labels the subtitle line while fetching; null leaves
     * the line alone (the search dialog paints its own progress). [onApplied]
     * runs between apply and the cast re-cast.
     */
    private fun acquireSubtitle(
        auto: Boolean = false,
        progress: String? = null,
        onError: (Exception) -> Unit = { e ->
            _state.update { it.copy(subtitleError = "Subtitle error: ${e.message}") }
        },
        onApplied: () -> Unit = {},
        fetch: suspend (Acquisition) -> SubtitleTrack,
    ): Job? {
        // Explicit picks supersede anything, including the pending guess. Auto
        // picks only ever start from autoJob, which every explicit action has
        // already cancelled — so they can never kill the user's choice.
        if (!auto) cancelAcquisition()
        val run = Acquisition(++nextRunId)
        acquisition = run
        val videoUri = _state.value.video?.uri
        run.job = viewModelScope.launch {
            _state.update {
                it.copy(extraction = progress?.let { p -> Extraction(p, auto, runId = run.runId) }, subtitleError = null)
            }
            try {
                // A stream closed by Acquisition.cancel surfaces as IOException
                // before the cancellation does — that must not read as a failure.
                reporting({ e -> if (isActive) onError(e) }) {
                    val track = withContext(Dispatchers.IO) { fetch(run) }
                    // An explicit pick in a known language becomes the preference.
                    if (!auto) track.language?.let { rememberedLanguages = it }
                    // Only apply to the still-current video (picks race async work).
                    if (_state.value.video?.uri == videoUri) {
                        applySubtitle(track)
                        onApplied()
                        // Late finish during playback: put the new cues on the TV.
                        recastWithSubtitle()
                    }
                }
            } finally {
                // A cancelled run's finally can lag past its successor's start
                // (blocked IO). Everything here is the run's own — except the
                // shared progress line, cleared only while still tagged as ours.
                run.stream.set(null)
                _state.update { if (it.extraction?.runId == run.runId) it.copy(extraction = null) else it }
                if (acquisition === run) acquisition = null
            }
        }
        return run.job
    }

    /** Cancels the pending guess and the running acquisition, unblocking any stalled read. */
    fun cancelAcquisition() {
        autoJob?.cancel()
        acquisition?.cancel()
        acquisition = null
    }

    fun pickEmbeddedTrack(track: EmbeddedSubtitles.Track, auto: Boolean = false) {
        val video = _state.value.video ?: return
        acquireSubtitle(
            auto = auto,
            progress = track.label,
            onError = { e ->
                // Only the still-current run reaches onError, so this is our own flag.
                val partial = if (acquisition?.prefixCast == true) " — the TV keeps the partial subtitles" else ""
                _state.update {
                    it.copy(subtitleError = "Extraction failed (${track.label}): ${e.message}$partial")
                }
            },
        ) { run ->
            val vtt = runInterruptible {
                EmbeddedSubtitles.extractVtt(
                    app, video.uri, track,
                    onOpen = run.stream::set,
                    onProgress = { percent ->
                        _state.update { state ->
                            state.extraction?.takeIf { it.runId == run.runId }
                                ?.let { state.copy(extraction = it.copy(percent = percent)) }
                                ?: state
                        }
                    },
                    onCues = { cues -> run.cues = cues },
                )
            }
            SubtitleTrack(track.plainLabel, vtt, track.language, SubtitleSource.EMBEDDED, auto)
        }
    }

    fun onSubtitlePicked(uri: Uri, auto: Boolean = false) {
        acquireSubtitle(auto = auto, progress = "subtitle file") { run ->
            runInterruptible {
                val name = app.contentResolver.displayName(uri) ?: "subtitle file"
                // Registered so ✕ or a superseding pick can abort a stalled network read.
                val bytes = app.contentResolver.openInputStream(uri)?.also(run.stream::set)
                    ?.use { it.readAtMost(MAX_SUBTITLE_BYTES) }
                    ?: error("cannot read subtitle file")
                // The filename's own language token; untagged files stay honestly unknown.
                val language = name.languageFromFileName()
                SubtitleTrack(name, SubtitleConverter.toVtt(bytes, name), language, SubtitleSource.FILE, auto)
            }
        }
    }

    fun clearSubtitle() {
        cancelAcquisition() // ✕ during an acquisition means "stop that"
        applySubtitle(null)
    }

    fun downloadSubtitle(result: OpenSubtitlesClient.Result) {
        if (_state.value.search?.searching == true) return // list stays clickable during a download
        searchJob?.cancel() // a late search response must not paint over the download state
        updateSearch { it.copy(searching = true, message = app.getString(R.string.downloading, result.name), messageIsError = false) }
        // Registered in both slots: closeSearch or a new search still cancels the
        // download; any other pick supersedes it through the pipeline.
        searchJob = acquireSubtitle(
            onError = { e ->
                updateSearch { it.copy(searching = false, message = app.getString(R.string.download_failed, e.message), messageIsError = true) }
            },
            // Close without cancelling: closeSearch() would cancel this very job.
            onApplied = { _state.update { it.copy(search = null) } },
        ) {
            val vtt = SubtitleConverter.toVtt(openSubtitles.download(result.fileId), result.name)
            SubtitleTrack(result.name, vtt, LanguageTag.normalize(result.language), SubtitleSource.ONLINE)
        }
    }

    private fun applySubtitle(track: SubtitleTrack?) {
        server?.subtitleVtt = track?.vtt
        // No status message: arrivals re-cast automatically; only clearing
        // mid-cast leaves the button as the manual re-cast affordance.
        // The folder hint survives on purpose: a grant upgrades future auto-picks.
        _state.update { it.copy(subtitle = track, note = null, subtitleError = null) }
    }

    // ----------------------------------------------------------------- search

    fun openSearch() {
        val query = _state.value.defaultQuery
        val languages = rememberedLanguages
        _state.update { it.copy(search = SearchState(query = query, languages = languages)) }
        searchSubtitles(query, languages)
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = null) }
    }

    /** Updates search state only while the dialog is still open — a response must not resurrect it. */
    private fun updateSearch(transform: (SearchState) -> SearchState) =
        _state.update { it.copy(search = it.search?.let(transform)) }

    fun searchSubtitles(query: String, languages: String) {
        if (query.isBlank()) {
            updateSearch { it.copy(message = app.getString(R.string.enter_title), messageIsError = false) }
            return
        }
        searchJob?.cancel() // a stale response must not paint into a newer dialog generation
        rememberedLanguages = languages
        searchJob = viewModelScope.launch {
            updateSearch { it.copy(searching = true, message = null, query = query, languages = languages) }
            reporting({ e ->
                updateSearch { it.copy(searching = false, message = app.getString(R.string.search_failed, e.message), messageIsError = true) }
            }) {
                val results = openSubtitles.search(query, languages)
                updateSearch {
                    it.copy(
                        results = results,
                        searching = false,
                        message = if (results.isEmpty()) app.getString(R.string.no_results) else null,
                        messageIsError = false,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ cast

    fun startCasting() = startCasting(resumeAtMs = 0)

    /**
     * Casting during a running extraction doesn't wait for it: the extraction
     * has been publishing its cues-so-far since pick time, so the load's
     * subtitle track carries subtitles from 0:00 immediately. The full VTT
     * later swaps in via [recastWithSubtitle].
     */
    private fun startCasting(resumeAtMs: Long) {
        val video = _state.value.video ?: return
        if (video.sizeBytes <= 0) return
        val player = castPlayer
            ?: return _state.update { it.copy(note = "Google Cast is unavailable on this device") }
        castJob?.cancel() // the superseded load's verdict must not act
        castJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, note = null) }
            reporting({ e -> loadFailed(e.message ?: "unknown error") }) {
                StreamingService.start(app)
                val (ip, server) = withContext(Dispatchers.IO) {
                    (localIpv4() ?: error("No Wi-Fi/LAN address found")) to ensureServer()
                }
                // Re-read after the suspension: the acquisition may just have
                // finished, or a new pick may have replaced the video.
                val current = _state.value
                if (current.video?.uri != video.uri) {
                    _state.update { it.copy(loading = false) }
                    if (!current.cast.hasMedia) stopStreaming() // nothing needs the server
                    return@reporting
                }
                val subtitlePending = current.subtitle == null && current.extraction != null
                val run = acquisition
                val prefixVtt = run?.cues
                    ?.takeIf { subtitlePending && it.isNotEmpty() }
                    ?.let(SubtitleConverter::cuesToVtt)
                if (prefixVtt != null) run.prefixCast = true
                server.video = MediaServer.Video(video.uri, video.mime, video.sizeBytes)
                // null → the subtitle route serves a valid empty VTT by itself.
                server.subtitleVtt = current.subtitle?.vtt ?: prefixVtt
                val base = "http://$ip:${server.port}"
                vttVersion++
                val error = player.load(
                    videoUrl = base + MediaServer.VIDEO_PATH,
                    mime = video.mime,
                    title = video.name,
                    subtitleUrl = (base + MediaServer.SUBTITLE_PATH + "?v=$vttVersion")
                        .takeIf { current.subtitle != null || subtitlePending },
                    subtitleLanguage = current.subtitle?.language ?: rememberedLanguageTag,
                    startPositionMs = resumeAtMs,
                )
                if (error != null) return@reporting loadFailed(error)
                _state.update {
                    it.copy(loading = false, castVideo = video, castSubtitle = current.subtitle?.name)
                }
                // A subtitle that arrived during the load round-trip missed its
                // auto-re-cast (castVideo wasn't recorded yet) — catch it up now.
                val subtitle = _state.value.subtitle
                if (subtitle != null && subtitle.name != current.subtitle?.name) recastWithSubtitle()
            }
        }
    }

    /**
     * A subtitle arrived while the cast is running: reload at the live position
     * with a fresh track URL — one short hiccup, then complete cues.
     */
    private fun recastWithSubtitle() {
        // Only over our own, still-current video: a late subtitle for a newly
        // picked video must not hijack what's playing on the TV.
        val castUri = _state.value.castVideo?.uri ?: return
        if (_state.value.video?.uri != castUri) return
        // Query the SDK directly: the polled state freezes while backgrounded.
        val progress = runCatching { castPlayer?.progress() }.getOrNull() ?: return
        if (progress.hasMedia) startCasting(resumeAtMs = progress.positionMs)
    }

    private fun loadFailed(error: String) {
        // Clean up only when idle — a still-playing previous cast keeps its server.
        if (!_state.value.cast.hasMedia) stopStreaming()
        _state.update { it.copy(loading = false, note = "Cast failed: $error") }
    }

    fun togglePlayPause() {
        castPlayer?.togglePlayPause()
    }

    fun seekBy(deltaMs: Long) {
        castPlayer?.seekBy(deltaMs)
    }

    fun seekToFraction(fraction: Float) {
        val player = castPlayer ?: return
        val duration = _state.value.cast.durationMs.takeIf { it > 0 } ?: return
        val target = (duration * fraction).toLong()
        player.seekTo(target)
        // Optimistic position so the thumb doesn't snap back while the receiver seeks.
        _state.update { it.copy(cast = it.cast.copy(positionMs = target)) }
    }

    fun setVolume(fraction: Float) {
        castPlayer?.setVolume(fraction)
    }

    fun stopCasting() {
        castPlayer?.stop()
        stopStreaming()
    }

    /**
     * Session termination (ended, or failed start/resume) releases the server,
     * wifi lock, and wake lock: no battery spent after the movie.
     */
    private fun onSessionTerminated() {
        stopStreaming()
        _state.update { it.copy(loading = false, castVideo = null, castSubtitle = null) }
    }

    private fun stopStreaming() {
        StreamingService.stop(app)
        server?.stop()
        server = null
    }

    private companion object {
        const val PREF_SUBTITLE_LANGUAGES = "subtitle_languages"
        const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024
    }
}

// ------------------------------------------------------- resolver helpers

private fun ContentResolver.describeVideo(uri: Uri): VideoFile {
    val name = displayName(uri) ?: uri.lastPathSegment ?: "video"
    return VideoFile(
        uri = uri,
        name = name,
        sizeBytes = videoSize(uri),
        mime = getType(uri)?.takeIf { it.startsWith("video/") } ?: name.videoMime(),
    )
}

private fun ContentResolver.displayName(uri: Uri): String? =
    queryColumn(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

/** SIZE column when the provider reports it, else the opened descriptor's length. */
private fun ContentResolver.videoSize(uri: Uri): Long =
    queryColumn(uri, OpenableColumns.SIZE) { it.getLong(0) }?.takeIf { it > 0 }
        ?: runCatching { openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
            ?.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it > 0 }
        ?: 0L

private fun <T> ContentResolver.queryColumn(uri: Uri, column: String, read: (Cursor) -> T): T? =
    query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        cursor.takeIf { it.moveToFirst() }?.let(read)
    }

private fun String.videoMime(): String = when (substringAfterLast('.', "").lowercase()) {
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    else -> "video/mp4"
}

private fun String.toSearchQuery(): String =
    substringBeforeLast('.').replace(Regex("[._]+"), " ").trim()

/** Language token from a subtitle filename's tail ("movie.en.forced.srt" → "en"). */
private fun String.languageFromFileName(): String? =
    split('.').dropLast(1).takeLast(2).firstNotNullOfOrNull(LanguageTag::normalize)
