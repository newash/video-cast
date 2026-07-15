package com.newash.videocast

import android.app.Application
import android.content.ContentResolver
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

data class SearchState(
    val results: List<OpenSubtitlesClient.Result> = emptyList(),
    val searching: Boolean = false,
    /** Error or empty-result message shown inside the dialog. */
    val message: String? = null,
    /** Last submitted (or prefilled) inputs — survive dialog recreation on rotation. */
    val query: String = "",
    val languages: String = "en",
)

data class UiState(
    val video: VideoFile? = null,
    val subtitle: SubtitleTrack? = null,
    val cast: CastPlayer.Progress = CastPlayer.Progress(),
    val status: String? = null,
    /** null = search dialog closed. */
    val search: SearchState? = null,
) {
    val defaultQuery: String get() = video?.name?.toSearchQuery().orEmpty()
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

    init {
        // The Cast SDK requires main-thread access; poll instead of juggling
        // per-session progress listeners. Skip the work while nothing collects
        // (screen off / app backgrounded).
        viewModelScope.launch {
            val player = castPlayer ?: return@launch
            while (isActive) {
                if (_state.subscriptionCount.value > 0) {
                    runCatching(player::progress).onSuccess { p -> _state.update { it.copy(cast = p) } }
                }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        castPlayer?.release()
    }

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            // DocumentsProvider queries are cross-process IPC — keep them off Main.
            val video = withContext(Dispatchers.IO) { app.contentResolver.describeVideo(uri) }
            _state.update { it.copy(video = video, status = null) }
        }
    }

    fun onSubtitlePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val track = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.displayName(uri) ?: "subtitle"
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read subtitle file")
                    SubtitleTrack(name, SubtitleConverter.toVtt(bytes, name), language = "en")
                }
                setSubtitle(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "Subtitle error: ${e.message}") }
            }
        }
    }

    fun clearSubtitle() {
        ServerHolder.server?.subtitleVtt = null
        _state.update { it.copy(subtitle = null, status = null) }
    }

    fun openSearch() {
        val query = _state.value.defaultQuery
        _state.update { it.copy(search = SearchState(query = query)) }
        searchSubtitles(query, "en")
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = null) }
    }

    /** Updates search state only while the dialog is still open — a response must not resurrect it. */
    private fun updateSearch(transform: (SearchState) -> SearchState) =
        _state.update { it.copy(search = it.search?.let(transform)) }

    fun searchSubtitles(query: String, languages: String) {
        if (query.isBlank()) return
        searchJob?.cancel() // a stale response must not paint into a newer dialog generation
        searchJob = viewModelScope.launch {
            updateSearch { it.copy(searching = true, message = null, query = query, languages = languages) }
            try {
                val results = openSubtitles.search(query, languages)
                updateSearch {
                    it.copy(
                        results = results,
                        searching = false,
                        message = app.getString(R.string.no_results).takeIf { _ -> results.isEmpty() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateSearch { it.copy(searching = false, message = "Search failed: ${e.message}") }
            }
        }
    }

    fun downloadSubtitle(result: OpenSubtitlesClient.Result) {
        if (_state.value.search?.searching == true) return // list stays clickable during a download
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            updateSearch { it.copy(searching = true, message = null) }
            try {
                val vtt = SubtitleConverter.toVtt(openSubtitles.download(result.fileId), result.name)
                setSubtitle(SubtitleTrack(result.name, vtt, result.language.take(2).ifBlank { "en" }))
                closeSearch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateSearch { it.copy(searching = false, message = "Download failed: ${e.message}") }
            }
        }
    }

    private fun setSubtitle(track: SubtitleTrack) {
        ServerHolder.server?.subtitleVtt = track.vtt
        _state.update {
            it.copy(
                subtitle = track,
                status = if (it.cast.hasMedia) "Subtitle loaded — press Cast again to apply" else null,
            )
        }
    }

    fun startCasting() {
        val current = _state.value
        val video = current.video ?: return _state.update { it.copy(status = "Pick a video first") }
        if (video.sizeBytes <= 0) {
            return _state.update { it.copy(status = "Video size unknown — pick it via a different provider") }
        }
        val player = castPlayer
            ?: return _state.update { it.copy(status = "Google Cast is unavailable on this device") }
        viewModelScope.launch {
            try {
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
                )
                _state.update { it.copy(status = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Clean up only when idle — a still-playing previous cast keeps its server.
                if (!_state.value.cast.hasMedia) StreamingService.stop(app)
                _state.update { it.copy(status = "Cast failed: ${e.message}") }
            }
        }
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

    fun stopCasting() {
        castPlayer?.stop()
        StreamingService.stop(app)
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
