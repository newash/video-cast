package com.newash.videocast.subs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.IOException

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

    fun listTracks(context: Context, uri: Uri): List<Track> = when (sniff(context.contentResolver, uri)) {
        Container.MKV -> context.contentResolver.reading(uri) { stream ->
            MkvSubtitles.listTracks(stream).map { track ->
                Track(track.number, Container.MKV, track.format, track.language, track.title)
            }
        }
        Container.MP4 -> Mp4Subtitles.listTracks(context, uri).map { track ->
            Track(track.index.toLong(), Container.MP4, "TX3G", track.language, title = null)
        }
        null -> emptyList()
    }

    /** Extracts the track and renders it as WebVTT. */
    fun extractVtt(context: Context, uri: Uri, track: Track): String {
        val cues = when (track.container) {
            Container.MKV -> context.contentResolver.reading(uri) { stream ->
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

    private fun sniff(resolver: ContentResolver, uri: Uri): Container? = resolver.reading(uri) { stream ->
        val header = ByteArray(12)
        var read = 0
        while (read < header.size) {
            val n = stream.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        when {
            MkvSubtitles.isMkv(header) -> Container.MKV
            Mp4Subtitles.isMp4(header) -> Container.MP4
            else -> null
        }
    }

    private fun <T> ContentResolver.reading(uri: Uri, block: (java.io.InputStream) -> T): T =
        openInputStream(uri)?.use(block) ?: throw IOException("cannot open $uri")
}
