package com.newash.videocast.subs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Embedded text subtitle tracks of a picked video: MKV via [MkvSubtitles]
 * (hand-rolled EBML walk), MP4 via [Mp4Subtitles] (platform MediaExtractor).
 * The container is sniffed from the first bytes, not the file name.
 */
object EmbeddedSubtitles {

    data class Track(
        /** MKV track number or MP4 track index. */
        val id: Long,
        val container: Container,
        /** MKV codec ID ("S_TEXT/UTF8"); empty for MP4. */
        val codecId: String,
        /** Display format: "SRT", "ASS", "VTT", "TX3G". */
        val format: String,
        /** Normalized language tag ("en", "pt-br"), or null when untagged. */
        val language: String?,
        val title: String?,
    ) {
        /** Standalone name with language, e.g. "[en] English SDH · SRT" — dialog rows, progress. */
        val label: String
            get() = listOfNotNull(language?.let { "[$it]" }, plainLabel).joinToString(" ")

        /** Name without the language bracket — for contexts that show the language separately. */
        val plainLabel: String
            get() = listOfNotNull(
                title ?: LanguageTag.displayName(language) ?: "Track $id",
                "· $format",
            ).joinToString(" ")
    }

    enum class Container { MKV, MP4 }

    /**
     * Early-VTT hook for [extractVtt], MKV only: [onVtt] fires (at most once, on
     * the extraction thread) with a valid VTT of the cues collected so far, when
     * the walk passes [untilMs] or [requestNow] is called. The final full VTT
     * supersedes it. Inert for MP4 (its extraction has no incremental phase).
     */
    class Snapshot(val untilMs: Long, val onVtt: (String) -> Unit) {
        internal val now = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Any thread: emit at the next block boundary instead of waiting for [untilMs]. */
        fun requestNow() = now.set(true)
    }

    /** One stream serves both the sniff and the MKV header walk (MP4 needs its own fd). */
    fun listTracks(context: Context, uri: Uri): List<Track> = context.contentResolver.reading(uri) { raw ->
        val stream = BufferedInputStream(raw, SNIFF_BYTES)
        stream.mark(SNIFF_BYTES)
        val header = stream.readAtMostBytes(SNIFF_BYTES)
        stream.reset()
        when {
            MkvSubtitles.isMkv(header) -> MkvSubtitles.listTracks(stream).map { track ->
                Track(
                    id = track.number,
                    container = Container.MKV,
                    codecId = track.codecId,
                    format = track.format,
                    language = LanguageTag.normalize(track.language),
                    title = track.title,
                )
            }
            Mp4Subtitles.isMp4(header) -> Mp4Subtitles.listTracks(context, uri).map { track ->
                Track(
                    id = track.index.toLong(),
                    container = Container.MP4,
                    codecId = "",
                    format = "TX3G",
                    language = LanguageTag.normalize(track.language),
                    title = null,
                )
            }
            else -> emptyList()
        }
    }

    /**
     * Extracts the track and renders it as WebVTT. [onOpen] hands out the live
     * stream so the caller can close it to abort a blocked read on cancel;
     * [onProgress] reports work done in percent (MKV only).
     */
    fun extractVtt(
        context: Context,
        uri: Uri,
        track: Track,
        onOpen: (AutoCloseable) -> Unit = {},
        onProgress: (Int) -> Unit = {},
        snapshot: Snapshot? = null,
    ): String {
        val cues = when (track.container) {
            // A real file descriptor unlocks the walker's Cues-index fast path
            // (both local SAF files and provider proxy descriptors are seekable).
            Container.MKV -> context.contentResolver.seekableOrStream(uri) { stream ->
                onOpen(stream)
                MkvSubtitles.extract(
                    stream, track.id, track.codecId, onProgress,
                    snapshot?.let { s ->
                        MkvSubtitles.SnapshotRequest(s.untilMs, s.now) { cues ->
                            s.onVtt(SubtitleConverter.cuesToVtt(cues))
                        }
                    },
                )
            }
            Container.MP4 -> Mp4Subtitles.extract(context, uri, track.id.toInt())
        }
        if (cues.isEmpty()) throw IOException("no cues in track")
        return SubtitleConverter.cuesToVtt(cues)
    }

    private fun InputStream.readAtMostBytes(n: Int): ByteArray {
        val bytes = ByteArray(n)
        var done = 0
        while (done < n) {
            val read = read(bytes, done, n - done)
            if (read < 0) break
            done += read
        }
        return bytes.copyOf(done)
    }

    private fun <T> ContentResolver.reading(uri: Uri, block: (InputStream) -> T): T =
        openInputStream(uri)?.use(block) ?: throw IOException("cannot open $uri")

    private fun <T> ContentResolver.seekableOrStream(uri: Uri, block: (InputStream) -> T): T =
        runCatching { openFileDescriptor(uri, "r") }.getOrNull()
            ?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).use(block) }
            ?: reading(uri, block)

    private const val SNIFF_BYTES = 12
}
