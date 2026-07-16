package com.newash.videocast

import android.app.Application
import android.content.ContentResolver
import android.content.Context
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
import com.newash.videocast.subs.OpenSubtitlesClient
import com.newash.videocast.subs.SubtitleConverter
import com.newash.videocast.util.localIpv4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoFile(val uri: Uri, val name: String, val sizeBytes: Long, val mime: String)

data class SubtitleTrack(val name: String, val vtt: String, val language: String)

/** A user-facing error message shown in the status line. */
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
    /** Subtitle name active on the receiver — drives the re-cast affordance. */
    val castSubtitle: String? = null,
    /** null = search dialog closed. */
    val search: SearchState? = null,
    /** Text subtitle tracks found inside the picked video. */
    val embeddedTracks: List<EmbeddedSubtitles.Track> = emptyList(),
    /** An embedded track is being extracted from the container. */
    val extracting: Boolean = false,
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

    private val prefs get() = app.getSharedPreferences("videocast", Context.MODE_PRIVATE)

    /** One remembered subtitle language, shared by OpenSubtitles search and embedded tracks. */
    private var rememberedLanguages: String
        get() = prefs.getString(PREF_SUBTITLE_LANGUAGES, null)?.takeIf { it.isNotBlank() } ?: "en"
        set(value) {
            value.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(PREF_SUBTITLE_LANGUAGES, it).apply() }
        }

    /** First language of the remembered list, as a two-letter cast track tag. */
    private val rememberedLanguageTag: String
        get() = rememberedLanguages.substringBefore(',').trim().take(2).lowercase().ifBlank { "en" }

    init {
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

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            // DocumentsProvider queries are cross-process IPC — keep them off Main.
            val video = withContext(Dispatchers.IO) { app.contentResolver.describeVideo(uri) }
            _state.update {
                it.copy(
                    video = video,
                    embeddedTracks = emptyList(),
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
            val tracks = withContext(Dispatchers.IO) {
                runCatching { EmbeddedSubtitles.listTracks(app, uri) }.getOrDefault(emptyList())
            }.sortedByDescending { it.language?.startsWith(preferred) == true }
            // Only annotate the still-current video (picks can race the probe).
            _state.update { if (it.video?.uri == uri) it.copy(embeddedTracks = tracks) else it }
        }
    }

    fun pickEmbeddedTrack(track: EmbeddedSubtitles.Track) {
        val video = _state.value.video ?: return
        viewModelScope.launch {
            _state.update { it.copy(extracting = true) }
            reporting({ e ->
                _state.update { it.copy(extracting = false, note = Note("Extraction failed: ${e.message}")) }
            }) {
                val vtt = withContext(Dispatchers.IO) { EmbeddedSubtitles.extractVtt(app, video.uri, track) }
                track.language?.let { rememberedLanguages = it }
                _state.update { it.copy(extracting = false) }
                applySubtitle(SubtitleTrack(track.label, vtt, track.language?.take(2) ?: rememberedLanguageTag))
            }
        }
    }

    fun onSubtitlePicked(uri: Uri) {
        viewModelScope.launch {
            reporting({ e -> _state.update { it.copy(note = Note("Subtitle error: ${e.message}")) } }) {
                val track = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.displayName(uri) ?: "subtitle"
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read subtitle file")
                    SubtitleTrack(name, SubtitleConverter.toVtt(bytes, name), rememberedLanguageTag)
                }
                applySubtitle(track)
            }
        }
    }

    fun clearSubtitle() = applySubtitle(null)

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
            updateSearch { it.copy(message = "Enter a title to search", messageIsError = false) }
            return
        }
        searchJob?.cancel() // a stale response must not paint into a newer dialog generation
        rememberedLanguages = languages
        searchJob = viewModelScope.launch {
            updateSearch { it.copy(searching = true, message = null, query = query, languages = languages) }
            reporting({ e ->
                updateSearch { it.copy(searching = false, message = "Search failed: ${e.message}", messageIsError = true) }
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

    fun downloadSubtitle(result: OpenSubtitlesClient.Result) {
        if (_state.value.search?.searching == true) return // list stays clickable during a download
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            updateSearch { it.copy(searching = true, message = "Downloading ${result.name}…", messageIsError = false) }
            reporting({ e ->
                updateSearch { it.copy(searching = false, message = "Download failed: ${e.message}", messageIsError = true) }
            }) {
                val track = withContext(Dispatchers.IO) {
                    val vtt = SubtitleConverter.toVtt(openSubtitles.download(result.fileId), result.name)
                    SubtitleTrack(result.name, vtt, result.language.take(2).ifBlank { "en" })
                }
                applySubtitle(track)
                closeSearch()
            }
        }
    }

    private fun applySubtitle(track: SubtitleTrack?) {
        ServerHolder.server?.subtitleVtt = track?.vtt
        // No status message: the Cast button itself turns into the re-cast
        // affordance when the loaded subtitle differs from the cast one.
        _state.update { it.copy(subtitle = track, note = null) }
    }

    fun startCasting() {
        val current = _state.value
        val video = current.video ?: return
        if (video.sizeBytes <= 0) return
        val player = castPlayer
            ?: return _state.update { it.copy(note = Note("Google Cast is unavailable on this device")) }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, note = null) }
            reporting({ e -> onLoadResult(e.message ?: "unknown error") }) {
                val (ip, server) = withContext(Dispatchers.IO) {
                    (localIpv4() ?: error("No Wi-Fi/LAN address found")) to ServerHolder.ensureStarted(app)
                }
                StreamingService.start(app)
                server.video = MediaServer.Video(video.uri, video.mime, video.sizeBytes)
                server.subtitleVtt = current.subtitle?.vtt
                val base = "http://$ip:${server.port}"
                player.load(
                    videoUrl = base + MediaServer.VIDEO_PATH,
                    mime = video.mime,
                    title = video.name,
                    subtitleUrl = current.subtitle?.let { base + MediaServer.SUBTITLE_PATH },
                    subtitleLanguage = current.subtitle?.language ?: "en",
                    onResult = ::onLoadResult,
                )
                _state.update { it.copy(castSubtitle = current.subtitle?.name) }
            }
        }
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
    }
}

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
