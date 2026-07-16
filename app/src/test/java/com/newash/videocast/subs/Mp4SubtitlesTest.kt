package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure parsing/assembly halves of the MP4 timed-text path (the extractor loop is platform code). */
class Mp4SubtitlesTest {

    private fun sample(text: String, trailing: ByteArray = ByteArray(0)): ByteArray {
        val bytes = text.toByteArray()
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes + trailing
    }

    @Test
    fun `parses tx3g sample text and ignores style boxes`() {
        val styleBox = byteArrayOf(0, 0, 0, 8, 's'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'l'.code.toByte())
        assertEquals("Hello", Mp4Subtitles.parseSample(sample("Hello", styleBox)))
        assertEquals("", Mp4Subtitles.parseSample(sample("")))
        assertEquals("", Mp4Subtitles.parseSample(ByteArray(0)))
    }

    @Test
    fun `length prefix is clamped to available bytes`() {
        val lying = byteArrayOf(0x7F, 0xFF.toByte()) + "abc".toByteArray()
        assertEquals("abc", Mp4Subtitles.parseSample(lying))
    }

    @Test
    fun `cues end at the next sample and blanks only terminate`() {
        val cues = Mp4Subtitles.cuesFromSamples(
            samples = listOf(
                0L to "First",
                2_000_000L to "", // gap: closes "First", emits nothing
                5_000_000L to "Second",
            ),
            trackDurationUs = 7_000_000L,
        )
        assertEquals(
            listOf(
                SubtitleConverter.Cue(0, 2_000, "First"),
                SubtitleConverter.Cue(5_000, 7_000, "Second"),
            ),
            cues,
        )
    }

    @Test
    fun `missing track duration falls back to a capped cue`() {
        val cues = Mp4Subtitles.cuesFromSamples(listOf(1_000_000L to "Only"), trackDurationUs = null)
        assertEquals(SubtitleConverter.Cue(1_000, 9_000, "Only"), cues.single())
    }

    @Test
    fun `cues render as webvtt`() {
        val vtt = SubtitleConverter.cuesToVtt(
            listOf(
                SubtitleConverter.Cue(61_500, 63_000, "Two"),
                SubtitleConverter.Cue(500, 2_000, "One"),
                SubtitleConverter.Cue(90_000, 90_000, "zero-length dropped"),
            )
        )
        assertEquals(
            "WEBVTT\n\n00:00:00.500 --> 00:00:02.000\nOne\n\n00:01:01.500 --> 00:01:03.000\nTwo\n",
            vtt,
        )
    }

    @Test
    fun `ass event payload keeps text commas and strips overrides`() {
        assertEquals(
            "Hi, there",
            SubtitleConverter.assEventToText("""7,0,Default,,0,0,0,,{\b1}Hi, there"""),
        )
    }
}
