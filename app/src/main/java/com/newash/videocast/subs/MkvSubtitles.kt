package com.newash.videocast.subs

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Minimal Matroska (EBML) reader for embedded text subtitle tracks. Android's
 * MediaExtractor never exposes MKV subtitle tracks (its Matroska parser
 * enumerates only video and audio), so this walks the container by hand.
 *
 * [listTracks] reads just the head of the file (up to the Tracks element).
 * [extract] prefers the Cues index: the spec says every subtitle frame SHOULD
 * be indexed with CueRelativePosition + CueDuration, and mkvmerge/ffmpeg both
 * do so by default — so a seekable file yields its subtitles by jumping
 * straight to the indexed blocks (~0.5% of the bytes). Files without a usable
 * index (or non-seekable streams) fall back to a full cluster scan that skips
 * every non-subtitle block payload.
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
        while (true) {
            val header = source.nextHeader() ?: break
            when (header.id) {
                ID_TRACKS -> {
                    source.forEachChild(header.size) { id, size ->
                        if (id == ID_TRACK_ENTRY) source.readTrackEntry(size)?.let(tracks::add)
                        else source.skipElement(id, size)
                    }
                    break
                }
                ID_CLUSTER -> break // media data reached: Tracks won't follow
                else -> source.skipElement(header.id, header.size)
            }
        }
        return tracks.filter { it.codecId.startsWith("S_TEXT/") }
    }

    /** All cues of [track], in play order: via the Cues index when usable, else a full scan. */
    fun extract(input: InputStream, track: Track): List<SubtitleConverter.Cue> {
        val source = Source(input)
        source.enterSegment()
        val segStart = source.position
        val head = source.readHead(track.number)
        val blocks = source.readIndexedBlocks(segStart, head, track.number)
            ?: source.scanClusters(head, track.number)
        return blocks.toCues(head.scaleNs, track.codecId)
    }

    // ---------------------------------------------------------------- cues

    private class Head(
        var scaleNs: Long = DEFAULT_TIMESTAMP_SCALE_NS,
        /** Segment-relative Cues position from the SeekHead, if announced. */
        var cuesPosition: Long? = null,
        /** Cues stored before the clusters (legal, rare) — already parsed. */
        var cues: List<CueEntry>? = null,
        /** Absolute file position of the first Cluster's ID byte. */
        var firstClusterAt: Long = -1,
    )

    private class CueEntry(
        /** Absolute block timestamp in segment ticks (CueTime). */
        val timeTicks: Long,
        val clusterPos: Long,
        val relPos: Long?,
        val durationTicks: Long?,
    )

    /** Segment children up to (and excluding) the first Cluster, whose header is pushed back. */
    private fun Source.readHead(trackNumber: Long): Head {
        val head = Head()
        while (true) {
            val header = nextHeader() ?: return head
            when (header.id) {
                ID_SEEK_HEAD -> readSeekHead(header.size)[ID_CUES]?.let {
                    if (head.cuesPosition == null) head.cuesPosition = it
                }
                ID_INFO -> forEachChild(header.size) { id, size ->
                    if (id == ID_TIMESTAMP_SCALE) head.scaleNs = readUInt(size) else skipElement(id, size)
                }
                ID_CUES -> head.cues = readCues(header.size, trackNumber)
                ID_CLUSTER -> {
                    head.firstClusterAt = header.start
                    pushBack(header)
                    return head
                }
                else -> skipElement(header.id, header.size)
            }
        }
    }

    /** SeekHead → map of top-level element ID to segment-relative position. */
    private fun Source.readSeekHead(size: Long): Map<Long, Long> {
        val positions = mutableMapOf<Long, Long>()
        forEachChild(size) { id, childSize ->
            if (id == ID_SEEK) {
                var target = -1L
                var pos = -1L
                forEachChild(childSize) { seekId, seekSize ->
                    when (seekId) {
                        // SeekID's payload is the raw element ID bytes — matches our marker-kept constants.
                        ID_SEEK_ID -> target = readUInt(seekSize)
                        ID_SEEK_POSITION -> pos = readUInt(seekSize)
                        else -> skipElement(seekId, seekSize)
                    }
                }
                if (target > 0 && pos >= 0) positions.putIfAbsent(target, pos)
            } else {
                skipElement(id, childSize)
            }
        }
        return positions
    }

    private fun Source.readCues(size: Long, trackNumber: Long): List<CueEntry> {
        val entries = mutableListOf<CueEntry>()
        forEachChild(size) { id, pointSize ->
            if (id == ID_CUE_POINT) {
                var time = -1L
                val positions = mutableListOf<Triple<Long, Long?, Long?>>() // cluster, rel, duration
                forEachChild(pointSize) { childId, childSize ->
                    when (childId) {
                        ID_CUE_TIME -> time = readUInt(childSize)
                        ID_CUE_TRACK_POSITIONS -> {
                            var track = -1L
                            var cluster = -1L
                            var rel: Long? = null
                            var duration: Long? = null
                            forEachChild(childSize) { posId, posSize ->
                                when (posId) {
                                    ID_CUE_TRACK -> track = readUInt(posSize)
                                    ID_CUE_CLUSTER_POSITION -> cluster = readUInt(posSize)
                                    ID_CUE_RELATIVE_POSITION -> rel = readUInt(posSize)
                                    ID_CUE_DURATION -> duration = readUInt(posSize)
                                    else -> skipElement(posId, posSize)
                                }
                            }
                            if (track == trackNumber && cluster >= 0) positions += Triple(cluster, rel, duration)
                        }
                        else -> skipElement(childId, childSize)
                    }
                }
                if (time >= 0) positions.mapTo(entries) { (cluster, rel, duration) ->
                    CueEntry(time, cluster, rel, duration)
                }
            } else {
                skipElement(id, pointSize)
            }
        }
        return entries
    }

    /**
     * The fast path: visit only the index-listed blocks. Returns null whenever
     * the index is absent, empty for this track, or untrustworthy — the caller
     * then falls back to the full scan.
     */
    private fun Source.readIndexedBlocks(segStart: Long, head: Head, trackNumber: Long): List<RawBlock>? {
        if (!seekable) return null
        val entries = head.cues ?: head.cuesPosition?.let { pos ->
            runCatching {
                // Time the jump: it calibrates how big a gap must be to be worth seeking.
                val before = System.nanoTime()
                seekTo(segStart + pos)
                val header = nextHeader()?.takeIf { it.id == ID_CUES } ?: return null
                calibrateSeekCost(System.nanoTime() - before)
                readCues(header.size, trackNumber)
            }.getOrNull() ?: return null
        } ?: return null
        if (entries.isEmpty()) return null

        val sorted = entries
            .sortedWith(compareBy({ it.clusterPos }, { it.relPos ?: 0 }))
            .distinctBy { it.clusterPos to it.relPos }
        val blocks = mutableListOf<RawBlock>()
        var clusterPos = -1L
        var clusterDataStart = -1L
        var clusterScanned = false
        try {
            for (entry in sorted) {
                if (entry.clusterPos != clusterPos) {
                    clusterPos = entry.clusterPos
                    advanceTo(segStart + entry.clusterPos)
                    val cluster = nextHeader()?.takeIf { it.id == ID_CLUSTER } ?: return null
                    clusterDataStart = position
                    clusterScanned = false
                    if (entry.relPos == null) {
                        // Pre-2013 muxer without relative positions: scan this one cluster.
                        readCluster(cluster.size, trackNumber, blocks)
                        clusterScanned = true
                    }
                }
                if (clusterScanned) continue
                val rel = entry.relPos ?: return null // mixed index: bail to the full scan
                advanceTo(clusterDataStart + rel)
                val header = nextHeader() ?: return null
                val (block, groupDuration) = when (header.id) {
                    ID_SIMPLE_BLOCK, ID_BLOCK -> readBlock(header.size, trackNumber) to null
                    ID_BLOCK_GROUP -> readGroupBlock(header.size, trackNumber) ?: (null to null)
                    else -> return null // index points at garbage: don't trust any of it
                }
                val payload = block?.second ?: return null // wrong track at indexed position
                blocks += RawBlock(entry.timeTicks, groupDuration ?: entry.durationTicks, payload)
            }
        } catch (_: EOFException) {
            // Truncated file: keep what we got instead of failing the whole track.
        }
        return blocks.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- scan

    private class RawBlock(val timestamp: Long, val duration: Long?, val payload: String)

    /** The fallback: walk every cluster, skipping other tracks' payloads. */
    private fun Source.scanClusters(head: Head, trackNumber: Long): List<RawBlock> {
        val blocks = mutableListOf<RawBlock>()
        if (head.firstClusterAt < 0) return blocks
        // The cues attempt may have moved us; a pushed-back header means we never left.
        if (seekable && !hasPushedBack) runCatching { seekTo(head.firstClusterAt) }
        try {
            while (true) {
                val header = nextHeader() ?: break
                if (header.id == ID_CLUSTER) readCluster(header.size, trackNumber, blocks)
                else skipElement(header.id, header.size)
            }
        } catch (_: EOFException) {
            // Truncated file: return the cues collected so far.
        }
        return blocks
    }

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
            // No duration anywhere (SimpleBlock-muxed text): show until the next cue, capped.
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
        forEachChild(size) { id, childSize ->
            when (id) {
                ID_TRACK_NUMBER -> number = readUInt(childSize)
                ID_TRACK_TYPE -> type = readUInt(childSize)
                ID_CODEC_ID -> codec = readString(childSize)
                ID_LANGUAGE -> language = readString(childSize)
                ID_LANGUAGE_BCP47 -> bcp47 = readString(childSize)
                ID_TRACK_NAME -> title = readString(childSize)
                else -> skipElement(id, childSize)
            }
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
                ID_BLOCK_GROUP -> readGroupBlock(childSize, trackNumber)?.let { (block, duration) ->
                    block?.let { (rel, payload) -> out += RawBlock(clusterTs + rel, duration, payload) }
                }
                else -> skipElement(id, childSize)
            }
        }
    }

    /** BlockGroup → (Block of [trackNumber] or null, BlockDuration or null); null if no Block at all. */
    private fun Source.readGroupBlock(size: Long, trackNumber: Long): Pair<Pair<Long, String>?, Long?>? {
        var block: Pair<Long, String>? = null
        var duration: Long? = null
        var sawBlock = false
        forEachChild(size) { id, childSize ->
            when (id) {
                ID_BLOCK -> {
                    sawBlock = true
                    block = readBlock(childSize, trackNumber)
                }
                ID_BLOCK_DURATION -> duration = readUInt(childSize)
                else -> skipElement(id, childSize)
            }
        }
        return if (sawBlock) block to duration else null
    }

    /** Block/SimpleBlock payload for [trackNumber] → (relative timestamp, text), else skipped. */
    private fun Source.readBlock(size: Long, trackNumber: Long): Pair<Long, String>? {
        val start = position
        val number = readVintValue()
        val relative = (readByte() shl 8 or readByte()).toShort().toLong()
        val flags = readByte()
        val body = size - (position - start)
        if (body < 0) throw IOException("block header overruns its element")
        // Foreign tracks and laced blocks (never used for text in practice) are skipped unread.
        if (number != trackNumber || flags and LACING_MASK != 0) {
            skip(body)
            return null
        }
        return relative to String(readBytes(body.toInt()), Charsets.UTF_8)
    }

    /**
     * Iterates children of an element of [size] (or [UNKNOWN_SIZE]). Unknown-size
     * elements (streamed segments/clusters) end at any ID in [terminators]; that
     * header is pushed back for the enclosing level to consume.
     */
    private inline fun Source.forEachChild(
        size: Long,
        terminators: Set<Long> = emptySet(),
        step: (id: Long, childSize: Long) -> Unit,
    ) {
        val end = if (size == UNKNOWN_SIZE) Long.MAX_VALUE else position + size
        while (position < end) {
            val header = nextHeader() ?: return
            if (size == UNKNOWN_SIZE && header.id in terminators) {
                pushBack(header)
                return
            }
            step(header.id, header.size)
            if (size != UNKNOWN_SIZE && position > end) throw IOException("child overruns parent element")
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

    // EBML strings may be NUL-padded, and Kotlin's trim() does not trim U+0000.
    private fun Source.readString(size: Long): String =
        String(readBytes(size.toInt()), Charsets.UTF_8).trimEnd { it == '\u0000' || it.isWhitespace() }.trim()

    // ------------------------------------------------------------------ EBML

    class Header internal constructor(val id: Long, val size: Long, val start: Long)

    /** Element header (serving any pushed-back one first), or null at a clean EOF. */
    private fun Source.nextHeader(): Header? {
        consumePushedBack()?.let { return it }
        val start = position
        val first = readByteOrEof()
        if (first < 0) return null
        val id = readVint(first, keepMarker = true)
        val sizeFirst = readByte()
        val sizeLength = vintLength(sizeFirst)
        val size = readVint(sizeFirst, keepMarker = false)
        // All-ones data bits mean "unknown size" (streamed segments/clusters).
        val unknown = size == (1L shl (7 * sizeLength)) - 1
        return Header(id, if (unknown) UNKNOWN_SIZE else size, start)
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
     * Reader with a byte position, single-header pushback, and optional random
     * access (present when the stream is an fd-backed FileInputStream — which
     * both local SAF files and DocumentsProvider proxy descriptors are).
     *
     * Skips below [seekThreshold] read through the stream instead of seeking:
     * network-backed providers stream sequential reads fast but pay seconds to
     * reopen their remote connection after an out-of-order read. The threshold
     * is recalibrated from the one mandatory seek (SeekHead → Cues) so local
     * files seek freely while network files stay near-sequential.
     */
    private class Source(raw: InputStream) {
        private val channel = (raw as? FileInputStream)?.channel
            ?.takeIf { ch -> runCatching { ch.position() }.isSuccess } // pipes throw ESPIPE
        private val rawInput = raw
        private var input: InputStream = BufferedInputStream(raw, BUFFER_SIZE)

        val seekable get() = channel != null

        var position = channel?.position() ?: 0L
            private set

        private var pushedBack: Header? = null
        private val scratch = ByteArray(SKIP_CHUNK)
        private var seekThreshold = DEFAULT_SEEK_THRESHOLD
        private var readBytesTotal = 0L
        private var readNanosTotal = 0L

        val hasPushedBack get() = pushedBack != null

        // The header's bytes are already consumed, so position stays untouched.
        fun pushBack(header: Header) {
            pushedBack = header
        }

        fun consumePushedBack(): Header? = pushedBack?.also { pushedBack = null }

        fun seekTo(target: Long) {
            val ch = channel ?: throw IOException("stream is not seekable")
            pushedBack = null
            ch.position(target)
            input = BufferedInputStream(rawInput, BUFFER_SIZE) // drop stale read-ahead
            position = target
        }

        /** Forward moves below the threshold read through; everything else seeks. */
        fun advanceTo(target: Long) {
            pushedBack = null // jumping invalidates any header held for the sequential walk
            when {
                target == position -> Unit
                target > position && (!seekable || target - position < seekThreshold) -> skip(target - position)
                else -> seekTo(target)
            }
        }

        /** Gap must cost more time to read than a seek's reopen penalty to be worth seeking. */
        fun calibrateSeekCost(penaltyNanos: Long) {
            val bytesPerNano =
                if (readNanosTotal > 0) readBytesTotal.toDouble() / readNanosTotal else DEFAULT_BYTES_PER_NANO
            seekThreshold = (penaltyNanos * bytesPerNano).toLong()
                .coerceIn(MIN_SEEK_THRESHOLD, MAX_SEEK_THRESHOLD)
        }

        fun readByteOrEof(): Int = input.read().also { if (it >= 0) position++ }

        fun readByte(): Int = readByteOrEof().also { if (it < 0) throw EOFException() }

        fun readBytes(n: Int): ByteArray {
            val bytes = ByteArray(n)
            var done = 0
            val started = if (n >= TIMING_MIN_BYTES) System.nanoTime() else 0L
            while (done < n) {
                if (Thread.interrupted()) throw InterruptedIOException("extraction cancelled")
                val read = input.read(bytes, done, n - done)
                if (read < 0) throw EOFException()
                done += read
            }
            if (started != 0L) {
                readNanosTotal += System.nanoTime() - started
                readBytesTotal += n
            }
            position += n
            return bytes
        }

        fun skip(n: Long) {
            if (n <= 0) return
            if (seekable && n >= seekThreshold) return seekTo(position + n)
            var left = n
            if (!seekable && n >= DEFAULT_SEEK_THRESHOLD) {
                // Non-seekable but skippable streams (plain files behind a wrapper).
                while (left > 0) {
                    val skipped = try {
                        input.skip(left)
                    } catch (_: IOException) {
                        0L // pipe-backed streams throw instead of returning 0
                    }
                    if (skipped <= 0) break
                    left -= skipped
                    position += skipped
                }
            }
            val started = if (left >= TIMING_MIN_BYTES) System.nanoTime() else 0L
            val toRead = left
            while (left > 0) {
                if (Thread.interrupted()) throw InterruptedIOException("extraction cancelled")
                val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (read < 0) throw EOFException()
                left -= read
                position += read
            }
            if (started != 0L) {
                readNanosTotal += System.nanoTime() - started
                readBytesTotal += toRead
            }
        }
    }

    private const val UNKNOWN_SIZE = -1L
    private const val TRACK_TYPE_SUBTITLE = 0x11L
    private const val LACING_MASK = 0x06
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
    private const val FALLBACK_CUE_MS = 8_000L
    private const val MIN_CUE_MS = 500L
    private const val BUFFER_SIZE = 64 * 1024
    private const val SKIP_CHUNK = 64 * 1024
    private const val DEFAULT_SEEK_THRESHOLD = 256L * 1024
    private const val MIN_SEEK_THRESHOLD = 64L * 1024
    private const val MAX_SEEK_THRESHOLD = 64L * 1024 * 1024
    private const val TIMING_MIN_BYTES = 4 * 1024
    private const val DEFAULT_BYTES_PER_NANO = 0.05 // ~50 MB/s when unmeasured

    const val CODEC_SRT = "S_TEXT/UTF8"
    const val CODEC_ASS = "S_TEXT/ASS"
    const val CODEC_SSA = "S_TEXT/SSA"
    const val CODEC_VTT = "S_TEXT/WEBVTT"

    private val EBML_MAGIC = intArrayOf(0x1A, 0x45, 0xDF, 0xA3)

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
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
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L
    private const val ID_CUE_RELATIVE_POSITION = 0xF0L
    private const val ID_CUE_DURATION = 0xB2L

    private val TOP_LEVEL_IDS = setOf(
        ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CLUSTER, ID_CUES,
        0x1043A770L, // Chapters
        0x1941A469L, // Attachments
        0x1254C367L, // Tags
    )
}
