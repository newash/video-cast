package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleConverterTest {

    @Test
    fun `srt timestamps are converted to vtt`() {
        val srt = """
            1
            00:00:01,500 --> 00:00:03,000
            Hello world

            2
            00:01:02,000 --> 00:01:04,250
            Second <i>line</i>
        """.trimIndent()

        val vtt = SubtitleConverter.srtToVtt(srt)

        assertTrue(vtt.startsWith("WEBVTT\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.000"))
        assertTrue(vtt.contains("00:01:02.000 --> 00:01:04.250"))
        assertTrue(vtt.contains("Second <i>line</i>"))
    }

    @Test
    fun `srt short millisecond fields are right-padded`() {
        val vtt = SubtitleConverter.srtToVtt("1\n00:00:01,5 --> 00:00:02,25\nHi\n")
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:02.250"))
    }

    @Test
    fun `ass dialogue is converted with tags stripped`() {
        val ass = """
            [Script Info]
            Title: test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\i1}Hello{\i0} there\Nfriend
            Dialogue: 0,0:01:00.00,0:01:02.00,Default,,0,0,0,,Text, with commas
        """.trimIndent()

        val vtt = SubtitleConverter.assToVtt(ass)

        assertTrue(vtt.startsWith("WEBVTT\n"))
        assertTrue(vtt.contains("00:00:01.500 --> 00:00:03.000"))
        assertTrue(vtt.contains("Hello there\nfriend"))
        // Commas inside the text field must not be split away.
        assertTrue(vtt.contains("Text, with commas"))
    }

    @Test
    fun `format detection prefers content over extension`() {
        assertEquals(
            SubtitleConverter.Format.VTT,
            SubtitleConverter.detectFormat("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi", "x.srt"),
        )
        assertEquals(
            SubtitleConverter.Format.ASS,
            SubtitleConverter.detectFormat("[Script Info]\nTitle: t\n", "x.txt"),
        )
    }

    @Test
    fun `format detection falls back to extension`() {
        assertEquals(SubtitleConverter.Format.SRT, SubtitleConverter.detectFormat("no signature", "x.srt"))
        assertEquals(SubtitleConverter.Format.ASS, SubtitleConverter.detectFormat("no signature", "x.ass"))
        assertEquals(SubtitleConverter.Format.VTT, SubtitleConverter.detectFormat("no signature", "x.vtt"))
    }

    @Test
    fun `utf8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "WEBVTT".toByteArray()
        assertEquals("WEBVTT", SubtitleConverter.decode(bytes))
    }

    @Test
    fun `invalid utf8 falls back to windows-1252`() {
        // 0xE9 is é in windows-1252 but an invalid standalone byte in UTF-8.
        val bytes = "caf".toByteArray() + byteArrayOf(0xE9.toByte())
        assertEquals("café", SubtitleConverter.decode(bytes))
    }

    @Test
    fun `utf-16 boms are honoured`() {
        assertEquals("WEBVTT", SubtitleConverter.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "WEBVTT".toByteArray(Charsets.UTF_16LE)))
        assertEquals("WEBVTT", SubtitleConverter.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "WEBVTT".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `ass cues are emitted sorted by start time`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:00.00,0:01:02.00,Default,,0,0,0,,Second
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First
        """.trimIndent()

        val vtt = SubtitleConverter.assToVtt(ass)
        assertTrue(vtt.indexOf("First") < vtt.indexOf("Second"))
    }

    @Test
    fun `ass without a format line falls back to spec-default field order`() {
        val ass = """
            [Events]
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
        """.trimIndent()

        val vtt = SubtitleConverter.assToVtt(ass)
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.000"))
        assertTrue(vtt.contains("Hello"))
    }

    @Test
    fun `consecutive ass line breaks collapse instead of terminating the cue`() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,foo\N\Nbar
        """.trimIndent()

        val vtt = SubtitleConverter.assToVtt(ass)
        assertTrue(vtt.contains("foo\nbar"))
    }

    @Test
    fun `vtt passthrough strips leading whitespace for the strict receiver`() {
        val vtt = SubtitleConverter.toVtt("\n\nWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi".toByteArray(), "x.vtt")
        assertTrue(vtt.startsWith("WEBVTT"))
    }
}
