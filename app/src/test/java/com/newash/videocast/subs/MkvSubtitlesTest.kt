package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the EBML walker against synthetic MKV files built byte by byte. */
class MkvSubtitlesTest {

    // ------------------------------------------------------------ EBML builder

    private fun vintSize(value: Long): ByteArray {
        // 4-byte size vint: 0x10 marker + 21 data bits — plenty for tests.
        require(value < (1L shl 21) - 1)
        val v = value or (0x10L shl 24)
        return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    private fun idBytes(id: Long): ByteArray = when {
        id <= 0xFF -> byteArrayOf(id.toByte())
        id <= 0xFFFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
        id <= 0xFFFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        else -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    }

    private fun element(id: Long, payload: ByteArray): ByteArray =
        idBytes(id) + vintSize(payload.size.toLong()) + payload

    private fun element(id: Long, vararg children: ByteArray): ByteArray =
        element(id, children.fold(ByteArray(0), ByteArray::plus))

    private fun uint(id: Long, value: Long): ByteArray {
        val bytes = generateSequence(value) { it shr 8 }.takeWhile { it != 0L }
            .map { it.toByte() }.toList().reversed().toByteArray()
        return element(id, if (bytes.isEmpty()) byteArrayOf(0) else bytes)
    }

    private fun str(id: Long, value: String): ByteArray = element(id, value.toByteArray())

    private fun block(track: Int, relativeTs: Int, text: String, flags: Int = 0): ByteArray =
        byteArrayOf((0x80 or track).toByte(), (relativeTs shr 8).toByte(), relativeTs.toByte(), flags.toByte()) +
            text.toByteArray()

    private fun simpleBlock(track: Int, relativeTs: Int, text: String, flags: Int = 0): ByteArray =
        element(0xA3, block(track, relativeTs, text, flags))

    private fun blockGroup(track: Int, relativeTs: Int, durationMs: Long, text: String): ByteArray =
        element(0xA0, element(0xA1, block(track, relativeTs, text)), uint(0x9B, durationMs))

    private fun trackEntry(number: Int, type: Long, codec: String, lang: String? = null, name: String? = null) =
        element(
            0xAE,
            uint(0xD7, number.toLong()),
            uint(0x83, type),
            str(0x86, codec),
            lang?.let { str(0x22B59C, it) } ?: ByteArray(0),
            name?.let { str(0x536E, it) } ?: ByteArray(0),
        )

    private fun mkv(vararg segmentChildren: ByteArray): ByteArray =
        element(0x1A45DFA3, ByteArray(0)) + element(0x18538067, *segmentChildren)

    private val header = element(0x1549A966, uint(0x2AD7B1, 1_000_000)) // Info: 1 ms/unit

    private val tracks = element(
        0x1654AE6B,
        trackEntry(1, type = 0x01, codec = "V_MPEGH/ISO/HEVC"),
        trackEntry(2, type = 0x11, codec = "S_TEXT/UTF8", lang = "hun", name = "Magyar"),
        trackEntry(3, type = 0x11, codec = "S_TEXT/ASS", lang = "eng"),
    )

    

    // ----------------------------------------------------------------- tests

    @Test
    fun `lists only text subtitle tracks`() {
        val data = mkv(header, tracks)
        val listed = MkvSubtitles.listTracks(ByteArrayInputStream(data))
        assertEquals(listOf(2L, 3L), listed.map { it.number })
        assertEquals(listOf("SRT", "ASS"), listed.map { it.format })
        assertEquals("hun", listed[0].language)
        assertEquals("Magyar", listed[0].title)
    }

    @Test
    fun `extracts cues with cluster offsets and durations`() {
        val data = mkv(
            header, tracks,
            element(
                0x1F43B675,
                uint(0xE7, 10_000),
                simpleBlock(1, 0, "video junk"),
                blockGroup(2, 500, durationMs = 1500, text = "Hello"),
            ),
            element(
                0x1F43B675,
                uint(0xE7, 60_000),
                blockGroup(2, 0, durationMs = 2000, text = "World"),
            ),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8")
        assertEquals(
            listOf(
                SubtitleConverter.Cue(10_500, 12_000, "Hello"),
                SubtitleConverter.Cue(60_000, 62_000, "World"),
            ),
            cues,
        )
    }

    @Test
    fun `simple block without duration ends at next cue, capped`() {
        val data = mkv(
            header, tracks,
            element(
                0x1F43B675,
                uint(0xE7, 0),
                simpleBlock(2, 100, "First"),
                simpleBlock(2, 2100, "Second"),
            ),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8")
        assertEquals(2100L, cues[0].endMs) // next cue start
        assertEquals(10_100L, cues[1].endMs) // 8 s fallback cap
    }

    @Test
    fun `ass payload is stripped to plain text`() {
        val payload = """12,0,Default,,0,0,0,,{\i1}Hello{\i0}\Nthere, world"""
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(3, 0, durationMs = 1000, text = payload)),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), 3, "S_TEXT/ASS")
        assertEquals("Hello\nthere, world", cues.single().text)
    }

    @Test
    fun `laced and foreign blocks are skipped`() {
        val data = mkv(
            header, tracks,
            element(
                0x1F43B675,
                uint(0xE7, 0),
                simpleBlock(2, 0, "laced-ignored", flags = 0x02),
                simpleBlock(1, 0, "wrong track"),
                simpleBlock(2, 500, "kept"),
            ),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8")
        assertEquals(listOf("kept"), cues.map { it.text })
    }

    @Test
    fun `unknown size segment and cluster end at the next cluster id`() {
        // 0xFF size byte = 1-byte vint with all data bits set = unknown size.
        val unknownSizeCluster = idBytes(0x1F43B675) + byteArrayOf(0xFF.toByte()) +
            uint(0xE7, 0) + simpleBlock(2, 0, "one")
        val nextCluster = element(0x1F43B675, uint(0xE7, 5000), blockGroup(2, 0, 1000, "two"))
        val segment = idBytes(0x18538067) + byteArrayOf(0xFF.toByte()) +
            header + tracks + unknownSizeCluster + nextCluster
        val data = element(0x1A45DFA3, ByteArray(0)) + segment
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8")
        assertEquals(listOf("one", "two"), cues.map { it.text })
        assertEquals(5000L, cues[1].startMs)
    }

    @Test
    fun `sniffs mkv magic`() {
        assertTrue(MkvSubtitles.isMkv(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)))
        assertFalse(MkvSubtitles.isMkv("....ftypisom".toByteArray()))
    }

    @Test
    fun `nul-padded language is trimmed`() {
        val entry = element(
            0xAE,
            uint(0xD7, 2),
            uint(0x83, 0x11),
            str(0x86, "S_TEXT/UTF8"),
            element(0x22B59C, "hun".toByteArray() + byteArrayOf(0, 0)), // NUL-padded EBML string
        )
        val listed = MkvSubtitles.listTracks(ByteArrayInputStream(mkv(header, element(0x1654AE6B, entry))))
        assertEquals("hun", listed.single().language)
    }

    @Test
    fun `truncated file yields the cues collected so far`() {
        val data = mkv(
            header, tracks,
            element(
                0x1F43B675,
                uint(0xE7, 0),
                blockGroup(2, 0, durationMs = 1000, text = "kept"),
                blockGroup(2, 5000, durationMs = 1000, text = "lost to truncation"),
            ),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data.copyOf(data.size - 6)), 2, "S_TEXT/UTF8")
        assertEquals(listOf("kept"), cues.map { it.text })
    }

    // ------------------------------------------------------- cues index route

    private fun uintFixed(id: Long, value: Long, width: Int): ByteArray =
        element(id, ByteArray(width) { i -> (value shr (8 * (width - 1 - i))).toByte() })

    private fun seekHeadToCues(cuesPos: Long): ByteArray = element(
        0x114D9B74,
        element(
            0x4DBB,
            element(0x53AB, byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)) +
                uintFixed(0x53AC, cuesPos, 8), // fixed width keeps SeekHead length stable
        ),
    )

    private fun cuePoint(timeMs: Long, track: Int, clusterPos: Long, relPos: Long?, durationMs: Long?) =
        element(
            0xBB,
            uint(0xB3, timeMs) + element(
                0xB7,
                uint(0xF7, track.toLong()) + uint(0xF1, clusterPos) +
                    (relPos?.let { uint(0xF0, it) } ?: ByteArray(0)) +
                    (durationMs?.let { uint(0xB2, it) } ?: ByteArray(0)),
            ),
        )

    private fun File.extractSrt(): List<SubtitleConverter.Cue> =
        FileInputStream(this).use { MkvSubtitles.extract(it, 2, "S_TEXT/UTF8") }

    private fun tempMkv(bytes: ByteArray): File =
        File.createTempFile("mkvtest", ".mkv").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun `cues index route reads only the indexed blocks`() {
        val timestamp = uint(0xE7, 0)
        val decoy = simpleBlock(2, 100, "decoy: in the track but not indexed")
        val indexed = blockGroup(2, 500, durationMs = 1200, text = "indexed")
        val cluster = element(0x1F43B675, timestamp, decoy, indexed)
        val shLen = seekHeadToCues(0).size.toLong()
        val clusterPos = shLen + header.size + tracks.size
        val cuesPos = clusterPos + cluster.size
        val relPos = (timestamp.size + decoy.size).toLong()
        val cues = element(0x1C53BB6B, cuePoint(500, 2, clusterPos, relPos, durationMs = null))
        val file = tempMkv(mkv(seekHeadToCues(cuesPos), header, tracks, cluster, cues))
        // The decoy's absence proves the index route ran instead of the scan.
        assertEquals(
            listOf(SubtitleConverter.Cue(500, 1700, "indexed")),
            file.extractSrt(),
        )
    }

    @Test
    fun `cue duration substitutes for a missing block duration`() {
        val timestamp = uint(0xE7, 0)
        val block = simpleBlock(2, 700, "no group duration")
        val cluster = element(0x1F43B675, timestamp, block)
        val shLen = seekHeadToCues(0).size.toLong()
        val clusterPos = shLen + header.size + tracks.size
        val cuesPos = clusterPos + cluster.size
        val cues = element(0x1C53BB6B, cuePoint(700, 2, clusterPos, timestamp.size.toLong(), durationMs = 2500))
        val file = tempMkv(mkv(seekHeadToCues(cuesPos), header, tracks, cluster, cues))
        assertEquals(
            listOf(SubtitleConverter.Cue(700, 3200, "no group duration")),
            file.extractSrt(),
        )
    }

    @Test
    fun `bogus cues position falls back to the full scan`() {
        val cluster = element(
            0x1F43B675,
            uint(0xE7, 0),
            simpleBlock(2, 100, "one"),
            blockGroup(2, 900, durationMs = 800, text = "two"),
        )
        // SeekHead points into the middle of the cluster — not a Cues element.
        val file = tempMkv(mkv(seekHeadToCues(5), header, tracks, cluster))
        assertEquals(listOf("one", "two"), file.extractSrt().map { it.text })
    }

    @Test
    fun `index without relative positions scans just that cluster`() {
        val timestamp = uint(0xE7, 0)
        val cluster = element(
            0x1F43B675,
            timestamp,
            blockGroup(2, 300, durationMs = 600, text = "found by cluster scan"),
        )
        val shLen = seekHeadToCues(0).size.toLong()
        val clusterPos = shLen + header.size + tracks.size
        val cuesPos = clusterPos + cluster.size
        val cues = element(0x1C53BB6B, cuePoint(300, 2, clusterPos, relPos = null, durationMs = null))
        val file = tempMkv(mkv(seekHeadToCues(cuesPos), header, tracks, cluster, cues))
        assertEquals(
            listOf(SubtitleConverter.Cue(300, 900, "found by cluster scan")),
            file.extractSrt(),
        )
    }

    // ------------------------------------------------------------- snapshots

    private class SnapProbe(untilMs: Long, now: Boolean = false) {
        val calls = mutableListOf<List<SubtitleConverter.Cue>>()
        val request = MkvSubtitles.SnapshotRequest(untilMs, AtomicBoolean(now)) { calls += it }
    }

    @Test
    fun `scan path snapshot fires once the walk passes untilMs`() {
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(2, 0, 1000, "one")),
            element(0x1F43B675, uint(0xE7, 40_000), blockGroup(2, 0, 1000, "two")),
            element(0x1F43B675, uint(0xE7, 80_000), blockGroup(2, 0, 1000, "three")),
        )
        val probe = SnapProbe(untilMs = 20_000)
        val full = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8", snapshot = probe.request)
        // The crossing cluster's cues are included: prefix is a superset in time.
        assertEquals(listOf(listOf("one", "two")), probe.calls.map { call -> call.map { it.text } })
        assertEquals(listOf("one", "two", "three"), full.map { it.text })
    }

    @Test
    fun `nudge set before any cue emits an empty snapshot`() {
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(2, 0, 1000, "only")),
        )
        val probe = SnapProbe(untilMs = Long.MAX_VALUE, now = true)
        val full = MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8", snapshot = probe.request)
        assertEquals(listOf(emptyList<SubtitleConverter.Cue>()), probe.calls)
        assertEquals(listOf("only"), full.map { it.text })
    }

    @Test
    fun `indexed path snapshot at the entry threshold`() {
        val timestamp = uint(0xE7, 0)
        val decoy = simpleBlock(1, 0, "video decoy")
        val first = simpleBlock(2, 500, "early")
        val second = simpleBlock(2, 5000, "late")
        val cluster = element(0x1F43B675, timestamp, decoy, first, second)
        val shLen = seekHeadToCues(0).size.toLong()
        val clusterPos = shLen + header.size + tracks.size
        val cuesPos = clusterPos + cluster.size
        val relFirst = (timestamp.size + decoy.size).toLong()
        val relSecond = relFirst + first.size
        val cues = element(
            0x1C53BB6B,
            cuePoint(500, 2, clusterPos, relFirst, durationMs = null),
            cuePoint(5000, 2, clusterPos, relSecond, durationMs = null),
        )
        val probe = SnapProbe(untilMs = 1000)
        val file = tempMkv(mkv(seekHeadToCues(cuesPos), header, tracks, cluster, cues))
        val full = FileInputStream(file).use {
            MkvSubtitles.extract(it, 2, "S_TEXT/UTF8", snapshot = probe.request)
        }
        // Prefix's last cue carries the fallback cap; the full set corrects it.
        assertEquals(listOf(SubtitleConverter.Cue(500, 8500, "early")), probe.calls.single())
        assertEquals(5000L, full.first().endMs)
    }

    @Test
    fun `snapshot then truncation - full result is the same partial`() {
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(2, 0, 1000, "one")),
            element(0x1F43B675, uint(0xE7, 40_000), blockGroup(2, 0, 1000, "two")),
            element(0x1F43B675, uint(0xE7, 80_000), blockGroup(2, 0, 1000, "lost")),
        )
        val probe = SnapProbe(untilMs = 10_000)
        val full = MkvSubtitles.extract(
            ByteArrayInputStream(data.copyOf(data.size - 6)), 2, "S_TEXT/UTF8", snapshot = probe.request
        )
        assertEquals(listOf("one", "two"), probe.calls.single().map { it.text })
        assertEquals(listOf("one", "two"), full.map { it.text })
    }

    @Test
    fun `untilMs beyond the last cue never snapshots`() {
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(2, 0, 1000, "one")),
        )
        val probe = SnapProbe(untilMs = Long.MAX_VALUE)
        MkvSubtitles.extract(ByteArrayInputStream(data), 2, "S_TEXT/UTF8", snapshot = probe.request)
        assertTrue(probe.calls.isEmpty())
    }
}
