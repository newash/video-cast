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
import com.newash.videocast.server.ServerHolder
import com.newash.videocast.server.StreamingService
import com.newash.videocast.subs.EmbeddedSubtitles
import com.newash.videocast.subs.LanguageTag
import com.newash.videocast.subs.OpenSubtitlesClient
import com.newash.videocast.subs.SiblingSubtitles
import com.newash.videocast.subs.SubtitleConverter
import com.newash.videocast.util.localIpv4
import com.newash.videocast.util.readAtMost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
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
data class Extraction(val label: String, val auto: Boolean, val percent: Int? = null)

/** A user-facing error message shown in the bottom status line (app-level errors). */
data class Note(val text: String)

data class SearchState(
    val results: List<OpenSubtitlesClient.Result> = emptyList(),
    val searching: Boolean = false,
    /** Error / progress / empty-result message shown inside the dialog. */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** Last submitted (or prefilled) inputs — survive dialog recreation on rotation. */
    val query: String = "",
    val languages: String = "en",
)

data class UiState(
    val video: VideoFile? = null,
    val subtitle: SubtitleTrack? = null,
    val cast: CastPlayer.Progress = CastPlayer.Progress(),
    val note: Note? = null,
    /** Full stack of the previous run's crash; shown as a tappable one-liner. */
    val crash: String? = null,
    /** Cast pressed, receiver hasn't reported media yet. */
    val loading: Boolean = false,
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
    // Session termination (ended, or failed start/resume) releases the server,
    // wifi lock, and wake lock: no battery spent after the movie.
    private val castPlayer: CastPlayer? by lazy {
        runCatching { CastPlayer(app) { StreamingService.stop(app) } }.getOrNull()
    }

    private val openSubtitles = OpenSubtitlesClient(BuildConfig.OPENSUBTITLES_API_KEY)

    private var searchJob: Job? = null
    private var probeJob: Job? = null
    private var acquireJob: Job? = null
    private var pickJob: Job? = null

    /** The acquisition's open stream — closed from outside to abort a blocked read. */
    private val acquisitionStream = AtomicReference<AutoCloseable?>()

    /** Bumped per subtitle change: a fresh ?v= makes the receiver re-fetch on reload. */
    private var vttVersion = 0

    /** Early-snapshot hook of a running extraction, nudged by [awaitPrefixVtt]. */
    private class SnapshotHook {
        val vtt = CompletableDeferred<String>()
        val snapshot = EmbeddedSubtitles.Snapshot { vtt.complete(it) }
    }

    private var snapshotHook: SnapshotHook? = null

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
                        _state.update { it.copy(cast = p, loading = it.loading && !p.hasMedia) }
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
        cancelAcquisition() // a previous video's slow acquisition must not land on this one
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            // DocumentsProvider queries are cross-process IPC — keep them off Main.
            val video = withContext(Dispatchers.IO) { app.contentResolver.describeVideo(uri) }
            _state.update {
                it.copy(
                    video = video,
                    embeddedTracks = emptyList(),
                    subtitleFolderHint = null,
                    subtitleError = null,
                    // Error prevention beats error reporting: warn at pick time.
                    note = if (video.sizeBytes <= 0) {
                        Note("Video size unknown — casting will fail; pick it via a different provider")
                    } else {
                        null
                    },
                )
            }
            probeEmbeddedTracks(uri)
        }
    }

    /** Container-header probe: cheap even for network files, so failures stay silent. */
    private fun probeEmbeddedTracks(uri: Uri) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
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
            autoSelectSubtitle(uri, tracks, preferred)
        }
    }

    // ----------------------------------------------------------- auto-select

    /**
     * Auto-pick for a fresh video, in strict order: sibling file tagged with the
     * saved language, then a plain undecorated sibling, then an embedded track
     * in the saved language. Sibling lookup needs a persisted folder grant; when
     * it's missing, the embedded rule still applies and the status line offers
     * the one-time grant.
     */
    private suspend fun autoSelectSubtitle(uri: Uri, tracks: List<EmbeddedSubtitles.Track>, lang: String) {
        val video = _state.value.video?.takeIf { it.uri == uri } ?: return
        val subtitleBefore = _state.value.subtitle
        val sibling = try {
            runInterruptible(Dispatchers.IO) {
                SiblingSubtitles.find(app, video.uri, video.name, LanguageTag.fileNameTags(lang))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SiblingSubtitles.Result.None
        }
        // A newer pick, or a subtitle the user chose meanwhile, wins over the guess.
        if (_state.value.video?.uri != uri || _state.value.subtitle !== subtitleBefore) return
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
        _state.value.video?.uri?.let(::probeEmbeddedTracks)
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
        hook: SnapshotHook? = null,
        onError: (Exception) -> Unit = { e ->
            _state.update { it.copy(subtitleError = "Subtitle error: ${e.message}") }
        },
        onApplied: () -> Unit = {},
        fetch: suspend () -> SubtitleTrack,
    ): Job? {
        if (auto && acquireJob?.isActive == true) return null
        if (!auto) cancelAcquisition()
        snapshotHook = hook
        val videoUri = _state.value.video?.uri
        val previous = acquireJob
        return viewModelScope.launch {
            previous?.join() // serializes the progress-state hand-off
            _state.update { it.copy(extraction = progress?.let { p -> Extraction(p, auto) }, subtitleError = null) }
            try {
                // A stream closed by cancelAcquisition surfaces as IOException before
                // the cancellation does — that must not read as a failure.
                reporting({ e -> if (isActive) onError(e) }) {
                    val track = withContext(Dispatchers.IO) { fetch() }
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
                acquisitionStream.set(null)
                if (snapshotHook === hook) snapshotHook = null
                hook?.vtt?.complete("") // release any gate still waiting on this run
                _state.update { it.copy(extraction = null) }
            }
        }.also { acquireJob = it }
    }

    /** Cancels the running acquisition and unblocks any stalled read by closing its stream. */
    fun cancelAcquisition() {
        acquireJob?.cancel()
        acquisitionStream.getAndSet(null)?.let { stream -> runCatching { stream.close() } }
        // Release anything gating on the dead run's snapshot; its finally may lag.
        snapshotHook?.vtt?.complete("")
        snapshotHook = null
    }

    fun pickEmbeddedTrack(track: EmbeddedSubtitles.Track, auto: Boolean = false) {
        val video = _state.value.video ?: return
        val hook = SnapshotHook()
        acquireSubtitle(
            auto = auto,
            progress = track.label,
            hook = hook,
            onError = { e ->
                val partial = if (hook.vtt.isCompleted) " — the TV keeps the partial subtitles" else ""
                _state.update {
                    it.copy(subtitleError = "Extraction failed (${track.label}): ${e.message}$partial")
                }
            },
        ) {
            val vtt = runInterruptible {
                EmbeddedSubtitles.extractVtt(
                    app, video.uri, track,
                    onOpen = acquisitionStream::set,
                    onProgress = { percent ->
                        _state.update { state ->
                            state.extraction?.takeIf { it.label == track.label }
                                ?.let { state.copy(extraction = it.copy(percent = percent)) }
                                ?: state
                        }
                    },
                    snapshot = hook.snapshot,
                )
            }
            SubtitleTrack(track.plainLabel, vtt, track.language, SubtitleSource.EMBEDDED, auto)
        }
    }

    fun onSubtitlePicked(uri: Uri, auto: Boolean = false) {
        acquireSubtitle(auto = auto, progress = "subtitle file") {
            runInterruptible {
                val name = app.contentResolver.displayName(uri) ?: "subtitle file"
                // Registered so ✕ or a superseding pick can abort a stalled network read.
                val bytes = app.contentResolver.openInputStream(uri)?.also(acquisitionStream::set)
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
        ServerHolder.server?.subtitleVtt = track?.vtt
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
     * Casting during a running extraction doesn't wait for it. The load's
     * subtitle track carries a snapshot of the cues collected so far — the
     * extraction has been running since pick time, so by Play it has usually
     * covered the first many minutes; a short gate handles a lightning-fast
     * tap. The full VTT later swaps in via [recastWithSubtitle].
     */
    private fun startCasting(resumeAtMs: Long) {
        val video = _state.value.video ?: return
        if (video.sizeBytes <= 0) return
        val player = castPlayer
            ?: return _state.update { it.copy(note = Note("Google Cast is unavailable on this device")) }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, note = null) }
            reporting({ e -> onLoadResult(e.message ?: "unknown error") }) {
                val (ip, server) = withContext(Dispatchers.IO) {
                    (localIpv4() ?: error("No Wi-Fi/LAN address found")) to ServerHolder.ensureStarted(app)
                }
                val prefixVtt = _state.value
                    .takeIf { it.video?.uri == video.uri && it.subtitle == null && it.extraction != null }
                    ?.let { awaitPrefixVtt() }
                // Re-read after the gate: the acquisition may just have finished,
                // or a new pick may have replaced the video.
                val current = _state.value
                if (current.video?.uri != video.uri) {
                    _state.update { it.copy(loading = false) }
                    return@reporting
                }
                val subtitlePending = current.subtitle == null && current.extraction != null
                StreamingService.start(app)
                server.video = MediaServer.Video(video.uri, video.mime, video.sizeBytes)
                // null → the subtitle route serves a valid empty VTT by itself.
                server.subtitleVtt = current.subtitle?.vtt ?: prefixVtt
                val base = "http://$ip:${server.port}"
                vttVersion++
                player.load(
                    videoUrl = base + MediaServer.VIDEO_PATH,
                    mime = video.mime,
                    title = video.name,
                    subtitleUrl = (base + MediaServer.SUBTITLE_PATH + "?v=$vttVersion")
                        .takeIf { current.subtitle != null || subtitlePending || prefixVtt != null },
                    subtitleLanguage = current.subtitle?.language ?: rememberedLanguageTag,
                    startPositionMs = resumeAtMs,
                    onResult = ::onLoadResult,
                )
                _state.update { it.copy(castSubtitle = current.subtitle?.name) }
            }
        }
    }

    /**
     * A subtitle arrived while the cast is running: reload at the live position
     * with a fresh track URL — one short hiccup, then complete cues.
     */
    private fun recastWithSubtitle() {
        // Query the SDK directly: the polled state freezes while backgrounded.
        val progress = runCatching { castPlayer?.progress() }.getOrNull() ?: return
        if (progress.hasMedia) startCasting(resumeAtMs = progress.positionMs)
    }

    /** Nudges the running acquisition and briefly awaits its cues-so-far VTT. */
    private suspend fun awaitPrefixVtt(): String? {
        val hook = snapshotHook ?: return null
        hook.snapshot.requestNow()
        return withTimeoutOrNull(PREFIX_VTT_TIMEOUT_MS) { hook.vtt.await() }?.takeIf { it.isNotEmpty() }
    }

    private fun onLoadResult(error: String?) {
        if (error == null) return
        // Clean up only when idle — a still-playing previous cast keeps its server.
        if (!_state.value.cast.hasMedia) StreamingService.stop(app)
        _state.update { it.copy(loading = false, note = Note("Cast failed: $error")) }
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
        StreamingService.stop(app)
    }

    private companion object {
        const val PREF_SUBTITLE_LANGUAGES = "subtitle_languages"
        const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024

        /** Longest wait for the Play-time cue snapshot — usually resolves in milliseconds. */
        const val PREFIX_VTT_TIMEOUT_MS = 4_000L
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
