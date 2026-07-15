package com.newash.videocast

import android.app.Application
import android.content.ContentResolver
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
import kotlinx.coroutines.Dispatchers
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

    private val castPlayer by lazy {
        CastPlayer(app) { connected -> _state.update { it.copy(cast = it.cast.copy(connected = connected)) } }
    }

    private val openSubtitles = OpenSubtitlesClient(BuildConfig.OPENSUBTITLES_API_KEY)

    init {
        // The Cast SDK requires main-thread access; poll instead of juggling
        // per-session progress listeners.
        viewModelScope.launch {
            while (isActive) {
                runCatching(castPlayer::progress).onSuccess { p -> _state.update { it.copy(cast = p) } }
                delay(500)
            }
        }
    }

    fun onVideoPicked(uri: Uri) =
        _state.update { it.copy(video = app.contentResolver.describeVideo(uri), status = null) }

    fun onSubtitlePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val name = app.contentResolver.displayName(uri) ?: "subtitle"
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("cannot read subtitle file")
                }
                setSubtitle(SubtitleTrack(name, SubtitleConverter.toVtt(bytes, name), language = "en"))
            }.onFailure { e -> _state.update { it.copy(status = "Subtitle error: ${e.message}") } }
        }
    }

    fun clearSubtitle() {
        ServerHolder.server?.subtitleVtt = null
        _state.update { it.copy(subtitle = null) }
    }

    fun openSearch() {
        _state.update { it.copy(search = SearchState()) }
        searchSubtitles(_state.value.defaultQuery, "en")
    }

    fun closeSearch() = _state.update { it.copy(search = null) }

    fun searchSubtitles(query: String, languages: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(search = SearchState(searching = true), status = null) }
            runCatching { openSubtitles.search(query, languages) }
                .onSuccess { results -> _state.update { it.copy(search = SearchState(results)) } }
                .onFailure { e ->
                    _state.update { it.copy(search = SearchState(), status = "Search failed: ${e.message}") }
                }
        }
    }

    fun downloadSubtitle(result: OpenSubtitlesClient.Result) {
        viewModelScope.launch {
            _state.update { it.copy(search = it.search?.copy(searching = true)) }
            runCatching {
                val vtt = SubtitleConverter.toVtt(openSubtitles.download(result.fileId), result.name)
                setSubtitle(SubtitleTrack(result.name, vtt, result.language.take(2).ifBlank { "en" }))
            }.onSuccess {
                closeSearch()
            }.onFailure { e ->
                _state.update {
                    it.copy(search = it.search?.copy(searching = false), status = "Download failed: ${e.message}")
                }
            }
        }
    }

    private fun setSubtitle(track: SubtitleTrack) {
        ServerHolder.server?.subtitleVtt = track.vtt
        _state.update {
            it.copy(
                subtitle = track,
                status = "Subtitle loaded — press Cast again to apply".takeIf { _ -> it.cast.hasMedia },
            )
        }
    }

    fun startCasting() {
        val current = _state.value
        val video = current.video ?: return _state.update { it.copy(status = "Pick a video first") }
        viewModelScope.launch {
            runCatching {
                val ip = localIpv4() ?: error("No Wi-Fi/LAN address found")
                val server = withContext(Dispatchers.IO) { ServerHolder.ensureStarted(app) }
                StreamingService.start(app)
                server.video = MediaServer.Video(video.uri, video.mime, video.sizeBytes)
                server.subtitleVtt = current.subtitle?.vtt
                val base = "http://$ip:${server.listeningPort}"
                castPlayer.load(
                    videoUrl = base + MediaServer.VIDEO_PATH,
                    mime = video.mime,
                    title = video.name,
                    subtitleUrl = current.subtitle?.let { base + MediaServer.SUBTITLE_PATH },
                    subtitleLanguage = current.subtitle?.language ?: "en",
                )
            }.fold(
                onSuccess = { _state.update { it.copy(status = null) } },
                onFailure = { e -> _state.update { it.copy(status = "Cast failed: ${e.message}") } },
            )
        }
    }

    fun togglePlayPause() = castPlayer.togglePlayPause()

    fun seekBy(deltaMs: Long) = castPlayer.seekBy(deltaMs)

    fun seekToFraction(fraction: Float) = _state.value.cast.durationMs
        .takeIf { it > 0 }
        ?.let { castPlayer.seekTo((it * fraction).toLong()) }
        ?: Unit

    fun stopCasting() {
        castPlayer.stop()
        StreamingService.stop(app)
    }
}

private fun ContentResolver.describeVideo(uri: Uri): VideoFile {
    val name = displayName(uri) ?: uri.lastPathSegment ?: "video"
    return VideoFile(
        uri = uri,
        name = name,
        sizeBytes = querySize(uri),
        mime = getType(uri)?.takeIf { it.startsWith("video/") } ?: name.videoMime(),
    )
}

private fun ContentResolver.displayName(uri: Uri): String? =
    queryColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index -> cursor.getString(index) }

private fun ContentResolver.querySize(uri: Uri): Long =
    queryColumn(uri, OpenableColumns.SIZE) { cursor, index -> cursor.getLong(index) } ?: 0L

private fun <T> ContentResolver.queryColumn(
    uri: Uri,
    column: String,
    read: (android.database.Cursor, Int) -> T,
): T? = query(uri, arrayOf(column), null, null, null)?.use { cursor ->
    cursor.takeIf { it.moveToFirst() }
        ?.getColumnIndex(column)?.takeIf { it >= 0 }
        ?.let { read(cursor, it) }
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
