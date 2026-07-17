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
        val format: String,
        val language: String?,
        val title: String?,
    ) {
        /** Dialog row / applied-subtitle name, e.g. "[en] English SDH · SRT". */
        val label: String
            get() = listOfNotNull(
                language?.let { "[$it]" },
                title ?: "Track $id",
                "· $format",
            ).joinToString(" ")
    }

    enum class Container { MKV, MP4 }

    /** One stream serves both the sniff and the MKV header walk (MP4 needs its own fd). */
    fun listTracks(context: Context, uri: Uri): List<Track> = context.contentResolver.reading(uri) { raw ->
        val stream = BufferedInputStream(raw, SNIFF_BYTES)
        stream.mark(SNIFF_BYTES)
        val header = stream.readAtMostBytes(SNIFF_BYTES)
        stream.reset()
        when {
            MkvSubtitles.isMkv(header) -> MkvSubtitles.listTracks(stream).map { track ->
                Track(track.number, Container.MKV, track.format, track.language, track.title)
            }
            Mp4Subtitles.isMp4(header) -> Mp4Subtitles.listTracks(context, uri).map { track ->
                Track(track.index.toLong(), Container.MP4, "TX3G", track.language, title = null)
            }
            else -> emptyList()
        }
    }

    /** Extracts the track and renders it as WebVTT. */
    fun extractVtt(context: Context, uri: Uri, track: Track): String {
        val cues = when (track.container) {
            // A real file descriptor unlocks the walker's Cues-index fast path
            // (both local SAF files and provider proxy descriptors are seekable).
            Container.MKV -> context.contentResolver.seekableOrStream(uri) { stream ->
                MkvSubtitles.extract(stream, MkvSubtitles.Track(track.id, mkvCodecId(track.format), null, null))
            }
            Container.MP4 -> Mp4Subtitles.extract(context, uri, track.id.toInt())
        }
        if (cues.isEmpty()) throw IOException("no cues in track")
        return SubtitleConverter.cuesToVtt(cues)
    }

    private fun mkvCodecId(format: String): String = when (format) {
        "ASS" -> MkvSubtitles.CODEC_ASS
        "VTT" -> MkvSubtitles.CODEC_VTT
        else -> MkvSubtitles.CODEC_SRT
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
