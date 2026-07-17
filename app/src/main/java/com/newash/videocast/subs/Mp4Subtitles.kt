package com.newash.videocast.subs

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Embedded MP4 timed-text (tx3g / "mov_text") subtitle tracks via the platform
 * MediaExtractor. Sample-table driven: listing tracks reads only the moov box,
 * and extraction preads just the text track's samples — kilobytes on local
 * files. (On network-backed files the samples' spread still pulls much of the
 * file through the provider's read-ahead; user-invoked, spinner-guarded.)
 */
object Mp4Subtitles {

    data class Track(val index: Int, val language: String?)

    fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && FTYP.withIndex().all { (i, b) -> header[i + 4] == b.code.toByte() }

    fun listTracks(context: Context, uri: Uri): List<Track> = withExtractor(context, uri) { extractor ->
        (0 until extractor.trackCount).mapNotNull { index ->
            val format = extractor.getTrackFormat(index)
            Track(index, format.language()).takeIf { format.getString(MediaFormat.KEY_MIME) == MIME_TX3G }
        }
    }

    fun extract(context: Context, uri: Uri, trackIndex: Int): List<SubtitleConverter.Cue> =
        withExtractor(context, uri) { extractor ->
            val durationUs = extractor.getTrackFormat(trackIndex)
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            val samples = generateSequence {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) return@generateSequence null
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes, 0, size)
                val sample = extractor.sampleTime to parseSample(bytes)
                extractor.advance()
                sample
            }.toList()
            cuesFromSamples(samples, durationUs)
        }

    /** tx3g sample: big-endian u16 text length + UTF-8 text (+ ignored style boxes). */
    fun parseSample(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val length = (bytes[0].toInt() and 0xFF shl 8 or (bytes[1].toInt() and 0xFF))
            .coerceAtMost(bytes.size - 2)
        return String(bytes, 2, length, Charsets.UTF_8)
    }

    /**
     * Timed-text tracks are contiguous: every instant has a sample, and gaps are
     * empty-text samples. So each cue ends where the next sample starts, and the
     * last one at the track duration.
     */
    fun cuesFromSamples(samples: List<Pair<Long, String>>, trackDurationUs: Long?): List<SubtitleConverter.Cue> =
        samples.mapIndexedNotNull { i, (timeUs, text) ->
            if (text.isBlank()) return@mapIndexedNotNull null
            val endUs = samples.getOrNull(i + 1)?.first
                ?: trackDurationUs?.takeIf { it > timeUs }
                ?: (timeUs + FALLBACK_CUE_US)
            SubtitleConverter.Cue(timeUs / 1000, endUs / 1000, text.trim())
        }

    private fun <T> withExtractor(context: Context, uri: Uri, block: (MediaExtractor) -> T): T {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            block(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.language(): String? =
        getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }

    private const val MIME_TX3G = "text/3gpp-tt"
    private const val FTYP = "ftyp"
    private const val MAX_SAMPLE_BYTES = 64 * 1024
    private const val FALLBACK_CUE_US = 8_000_000L
}
