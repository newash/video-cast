package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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

    private val srtTrack = MkvSubtitles.Track(2, "S_TEXT/UTF8", "hun", "Magyar")

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
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), srtTrack)
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
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), srtTrack)
        assertEquals(2100L, cues[0].endMs) // next cue start
        assertEquals(10_100L, cues[1].endMs) // 8 s fallback cap
    }

    @Test
    fun `ass payload is stripped to plain text`() {
        val assTrack = MkvSubtitles.Track(3, "S_TEXT/ASS", "eng", null)
        val payload = """12,0,Default,,0,0,0,,{\i1}Hello{\i0}\Nthere, world"""
        val data = mkv(
            header, tracks,
            element(0x1F43B675, uint(0xE7, 0), blockGroup(3, 0, durationMs = 1000, text = payload)),
        )
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), assTrack)
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
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), srtTrack)
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
        val cues = MkvSubtitles.extract(ByteArrayInputStream(data), srtTrack)
        assertEquals(listOf("one", "two"), cues.map { it.text })
        assertEquals(5000L, cues[1].startMs)
    }

    @Test
    fun `sniffs mkv magic`() {
        assertTrue(MkvSubtitles.isMkv(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)))
        assertFalse(MkvSubtitles.isMkv("....ftypisom".toByteArray()))
    }
}
