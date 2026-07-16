package com.newash.videocast.subs

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Minimal Matroska (EBML) reader for embedded text subtitle tracks. Android's
 * MediaExtractor never exposes MKV subtitle tracks (its Matroska parser
 * enumerates only video and audio), so this walks the container by hand:
 * [listTracks] reads just the head of the file (up to the Tracks element),
 * [extract] scans the clusters, skipping every non-subtitle block payload.
 */
object MkvSubtitles {

    data class Track(
        val number: Long,
        val codecId: String,
        val language: String?,
        val title: String?,
    ) {
        val format: String
            get() = when (codecId) {
                CODEC_SRT -> "SRT"
                CODEC_ASS, CODEC_SSA -> "ASS"
                CODEC_VTT -> "VTT"
                else -> codecId.removePrefix("S_TEXT/")
            }
    }

    fun isMkv(header: ByteArray): Boolean =
        header.size >= EBML_MAGIC.size && EBML_MAGIC.withIndex().all { (i, b) -> header[i] == b.toByte() }

    /** Text subtitle tracks, from the segment head only — cheap even on network files. */
    fun listTracks(input: InputStream): List<Track> {
        val source = Source(input)
        source.enterSegment()
        val tracks = mutableListOf<Track>()
        // Segment children run to EOF: no terminator IDs at this level.
        source.forEachChild(UNKNOWN_SIZE, emptySet()) { id, size ->
            when (id) {
                ID_TRACKS -> {
                    source.forEachChild(size, TOP_LEVEL_IDS) { childId, childSize ->
                        if (childId == ID_TRACK_ENTRY) source.readTrackEntry(childSize)?.let(tracks::add)
                        else source.skipElement(childId, childSize)
                        true
                    }
                    false
                }
                ID_CLUSTER -> false // media data reached: Tracks won't follow
                else -> {
                    source.skipElement(id, size)
                    true
                }
            }
        }
        return tracks.filter { it.codecId.startsWith("S_TEXT/") }
    }

    /** All cues of [track], in play order. Scans the whole segment, skipping other tracks' data. */
    fun extract(input: InputStream, track: Track): List<SubtitleConverter.Cue> {
        val source = Source(input)
        source.enterSegment()
        var scaleNs = DEFAULT_TIMESTAMP_SCALE_NS
        val blocks = mutableListOf<RawBlock>()
        source.forEachChild(UNKNOWN_SIZE, emptySet()) { id, size ->
            when (id) {
                ID_INFO -> source.forEachChild(size, TOP_LEVEL_IDS) { childId, childSize ->
                    if (childId == ID_TIMESTAMP_SCALE) scaleNs = source.readUInt(childSize)
                    else source.skipElement(childId, childSize)
                    true
                }
                ID_CLUSTER -> source.readCluster(size, track.number, blocks)
                else -> source.skipElement(id, size)
            }
            true
        }
        return blocks.toCues(scaleNs, track.codecId)
    }

    // ---------------------------------------------------------------- parsing

    private class RawBlock(val timestamp: Long, val duration: Long?, val payload: String)

    private fun List<RawBlock>.toCues(scaleNs: Long, codecId: String): List<SubtitleConverter.Cue> {
        fun toMs(units: Long): Long = units * scaleNs / 1_000_000
        val sorted = sortedBy(RawBlock::timestamp)
        return sorted.mapIndexedNotNull { i, block ->
            val text = when (codecId) {
                CODEC_ASS, CODEC_SSA -> SubtitleConverter.assEventToText(block.payload)
                else -> block.payload.trim()
            }
            if (text.isBlank()) return@mapIndexedNotNull null
            val start = toMs(block.timestamp)
            val end = block.duration?.let { start + toMs(it) }
            // No BlockDuration (SimpleBlock-muxed text): show until the next cue, capped.
                ?: minOf(
                    sorted.getOrNull(i + 1)?.let { toMs(it.timestamp) } ?: Long.MAX_VALUE,
                    start + FALLBACK_CUE_MS,
                )
            SubtitleConverter.Cue(start, maxOf(end, start + MIN_CUE_MS), text)
        }
    }

    private fun Source.enterSegment() {
        val ebml = nextHeader() ?: throw IOException("empty stream")
        if (ebml.id != ID_EBML) throw IOException("not an EBML file")
        skipElement(ebml.id, ebml.size)
        val segment = nextHeader() ?: throw IOException("no segment")
        if (segment.id != ID_SEGMENT) throw IOException("no segment")
        // Descend: everything read from here on is a segment child.
    }

    private fun Source.readTrackEntry(size: Long): Track? {
        var number = -1L
        var type = -1L
        var codec = ""
        var language: String? = null
        var bcp47: String? = null
        var title: String? = null
        forEachChild(size, TOP_LEVEL_IDS) { id, childSize ->
            when (id) {
                ID_TRACK_NUMBER -> number = readUInt(childSize)
                ID_TRACK_TYPE -> type = readUInt(childSize)
                ID_CODEC_ID -> codec = readString(childSize)
                ID_LANGUAGE -> language = readString(childSize)
                ID_LANGUAGE_BCP47 -> bcp47 = readString(childSize)
                ID_TRACK_NAME -> title = readString(childSize)
                else -> skipElement(id, childSize)
            }
            true
        }
        return Track(number, codec, (bcp47 ?: language)?.takeIf { it.isNotBlank() && it != "und" }, title)
            .takeIf { type == TRACK_TYPE_SUBTITLE && number > 0 }
    }

    private fun Source.readCluster(size: Long, trackNumber: Long, out: MutableList<RawBlock>) {
        var clusterTs = 0L
        forEachChild(size, TOP_LEVEL_IDS + ID_CLUSTER) { id, childSize ->
            when (id) {
                ID_CLUSTER_TIMESTAMP -> clusterTs = readUInt(childSize)
                ID_SIMPLE_BLOCK -> readBlock(childSize, trackNumber)?.let { (rel, payload) ->
                    out += RawBlock(clusterTs + rel, duration = null, payload = payload)
                }
                ID_BLOCK_GROUP -> {
                    var block: Pair<Long, String>? = null
                    var duration: Long? = null
                    forEachChild(childSize, TOP_LEVEL_IDS) { groupId, groupSize ->
                        when (groupId) {
                            ID_BLOCK -> block = readBlock(groupSize, trackNumber)
                            ID_BLOCK_DURATION -> duration = readUInt(groupSize)
                            else -> skipElement(groupId, groupSize)
                        }
                        true
                    }
                    block?.let { (rel, payload) -> out += RawBlock(clusterTs + rel, duration, payload) }
                }
                else -> skipElement(id, childSize)
            }
            true
        }
    }

    /** Block/SimpleBlock payload for [trackNumber] → (relative timestamp, text), else skipped. */
    private fun Source.readBlock(size: Long, trackNumber: Long): Pair<Long, String>? {
        val start = position
        val number = readVintValue()
        val relative = (readByte() shl 8 or readByte()).toShort().toLong()
        val flags = readByte()
        val body = size - (position - start)
        // Foreign tracks and laced blocks (never used for text in practice) are skipped unread.
        if (number != trackNumber || flags and LACING_MASK != 0) {
            skip(body)
            return null
        }
        return relative to String(readBytes(body.toInt()), Charsets.UTF_8)
    }

    /**
     * Iterates children of an element of [size] (or [UNKNOWN_SIZE]) until the
     * element ends, EOF, or [step] returns false. Unknown-size elements
     * (streamed segments/clusters) end at any ID in [terminators]; that header
     * is pushed back for the enclosing level to consume.
     */
    private inline fun Source.forEachChild(
        size: Long,
        terminators: Set<Long>,
        step: (id: Long, childSize: Long) -> Boolean,
    ) {
        val end = if (size == UNKNOWN_SIZE) Long.MAX_VALUE else position + size
        while (position < end) {
            val header = nextHeader() ?: return
            if (size == UNKNOWN_SIZE && header.id in terminators) {
                pushBack(header)
                return
            }
            if (!step(header.id, header.size)) return
        }
    }

    private fun Source.skipElement(id: Long, size: Long) {
        if (size == UNKNOWN_SIZE) throw IOException("unknown-size element 0x${id.toString(16)} not skippable")
        skip(size)
    }

    private fun Source.readUInt(size: Long): Long {
        if (size > 8) throw IOException("uint too large")
        return readBytes(size.toInt()).fold(0L) { acc, b -> acc shl 8 or (b.toLong() and 0xFF) }
    }

    private fun Source.readString(size: Long): String =
        String(readBytes(size.toInt()), Charsets.UTF_8).trimEnd(' ').trim()

    // ------------------------------------------------------------------ EBML

    class Header internal constructor(val id: Long, val size: Long)

    /** Element header (serving any pushed-back one first), or null at a clean EOF. */
    private fun Source.nextHeader(): Header? {
        consumePushedBack()?.let { return it }
        val first = readByteOrEof()
        if (first < 0) return null
        val id = readVint(first, keepMarker = true)
        val sizeFirst = readByte()
        val sizeLength = vintLength(sizeFirst)
        val size = readVint(sizeFirst, keepMarker = false)
        // All-ones data bits mean "unknown size" (streamed segments/clusters).
        val unknown = size == (1L shl (7 * sizeLength)) - 1
        return Header(id, if (unknown) UNKNOWN_SIZE else size)
    }

    private fun Source.readVintValue(): Long = readVint(readByte(), keepMarker = false)

    private fun Source.readVint(first: Int, keepMarker: Boolean): Long {
        val length = vintLength(first)
        var value = (if (keepMarker) first else first xor (1 shl (8 - length))).toLong()
        repeat(length - 1) { value = value shl 8 or readByte().toLong() }
        return value
    }

    private fun vintLength(first: Int): Int {
        if (first == 0) throw IOException("bad vint")
        return Integer.numberOfLeadingZeros(first) - 23
    }

    /**
     * Sequential reader with a byte position and pushback of one element header.
     * Small skips read through the stream: on network-backed content:// files a
     * real seek can force the provider to reopen its remote connection, which
     * costs far more than reading a video block's worth of bytes.
     */
    private class Source(private val input: InputStream) {
        var position = 0L
            private set

        private var pushedBack: Header? = null
        private val scratch = ByteArray(SKIP_CHUNK)

        // The header's bytes are already consumed, so position stays untouched.
        fun pushBack(header: Header) {
            pushedBack = header
        }

        fun consumePushedBack(): Header? = pushedBack?.also { pushedBack = null }

        fun readByteOrEof(): Int = input.read().also { if (it >= 0) position++ }

        fun readByte(): Int = readByteOrEof().also { if (it < 0) throw EOFException() }

        fun readBytes(n: Int): ByteArray {
            val bytes = ByteArray(n)
            var done = 0
            while (done < n) {
                val read = input.read(bytes, done, n - done)
                if (read < 0) throw EOFException()
                done += read
            }
            position += n
            return bytes
        }

        fun skip(n: Long) {
            var left = n
            if (n >= SEEK_THRESHOLD) {
                while (left > 0) {
                    val skipped = try {
                        input.skip(left)
                    } catch (_: IOException) {
                        0L // pipe-backed streams throw instead of returning 0
                    }
                    if (skipped <= 0) break
                    left -= skipped
                }
            }
            while (left > 0) {
                val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (read < 0) throw EOFException()
                left -= read
            }
            position += n
        }
    }

    private const val UNKNOWN_SIZE = -1L
    private const val TRACK_TYPE_SUBTITLE = 0x11L
    private const val LACING_MASK = 0x06
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
    private const val FALLBACK_CUE_MS = 8_000L
    private const val MIN_CUE_MS = 500L
    private const val SEEK_THRESHOLD = 256L * 1024
    private const val SKIP_CHUNK = 64 * 1024

    const val CODEC_SRT = "S_TEXT/UTF8"
    const val CODEC_ASS = "S_TEXT/ASS"
    const val CODEC_SSA = "S_TEXT/SSA"
    const val CODEC_VTT = "S_TEXT/WEBVTT"

    private val EBML_MAGIC = intArrayOf(0x1A, 0x45, 0xDF, 0xA3)

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_BCP47 = 0x22B59DL
    private const val ID_TRACK_NAME = 0x536EL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private val TOP_LEVEL_IDS = setOf(
        0x114D9B74L, // SeekHead
        ID_INFO, ID_TRACKS, ID_CLUSTER,
        0x1C53BB6BL, // Cues
        0x1043A770L, // Chapters
        0x1941A469L, // Attachments
        0x1254C367L, // Tags
    )
}
