package com.newash.videocast.subs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Converts SRT and ASS/SSA subtitles to WebVTT — the only sidecar text format
 * the default Chromecast media receiver renders. Styling beyond basic inline
 * tags is discarded; text-only output is the design scope.
 */
object SubtitleConverter {

    enum class Format { SRT, ASS, VTT }

    fun toVtt(bytes: ByteArray, fileName: String?): String = decode(bytes).let { text ->
        when (detectFormat(text, fileName)) {
            Format.VTT -> text
            Format.SRT -> srtToVtt(text)
            Format.ASS -> assToVtt(text)
        }
    }

    /** UTF-8 when valid, otherwise windows-1252 (the common legacy-SRT encoding). */
    fun decode(bytes: ByteArray): String = bytes.withoutBom().let { body ->
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    fun detectFormat(text: String, fileName: String?): Format = when {
        text.trimStart().startsWith("WEBVTT") -> Format.VTT
        text.trimStart().startsWith("[Script Info]") || "\n[Events]" in text -> Format.ASS
        else -> when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "vtt" -> Format.VTT
            "ass", "ssa" -> Format.ASS
            else -> Format.SRT
        }
    }

    fun srtToVtt(text: String): String =
        "WEBVTT\n\n" + text.normalizedLines().joinToString("\n") { line ->
            line.replace(SRT_TIMING) { m ->
                val g = m.groupValues
                "${vttTime(g[1], g[2], g[3], g[4])} --> ${vttTime(g[5], g[6], g[7], g[8])}"
            }
        } + "\n"

    fun assToVtt(text: String): String {
        val events = text.normalizedLines()
            .dropWhile { !it.trim().equals("[Events]", ignoreCase = true) }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
        val fields = events.firstNotNullOfOrNull { it.sectionValue("Format:") }
            ?.split(',')?.map { field -> field.trim().lowercase() }
            .orEmpty()
        return events
            .mapNotNull { it.sectionValue("Dialogue:")?.toAssCue(fields) }
            .sortedBy(AssCue::startMs)
            .joinToString(separator = "\n\n", prefix = "WEBVTT\n\n", postfix = "\n") { cue ->
                "${cue.startMs.toVttTime()} --> ${cue.endMs.toVttTime()}\n${cue.text}"
            }
    }

    private data class AssCue(val startMs: Long, val endMs: Long, val text: String)

    private fun String.toAssCue(fields: List<String>): AssCue? {
        if (fields.isEmpty()) return null
        val values = split(",", limit = fields.size).map(String::trim)
        if (values.size < fields.size) return null
        fun field(name: String): String? = fields.indexOf(name).takeIf { it >= 0 }?.let(values::getOrNull)
        return AssCue(
            startMs = field("start")?.parseAssTime() ?: return null,
            endMs = field("end")?.parseAssTime() ?: return null,
            text = field("text")?.cleanAssText() ?: return null,
        ).takeIf { it.text.isNotBlank() }
    }

    /** "Key: value" line → value, or null when the line is not that key. */
    private fun String.sectionValue(key: String): String? =
        trim().takeIf { it.startsWith(key, ignoreCase = true) }?.substringAfter(':')?.trim()

    // ASS times look like "0:01:02.34" (centiseconds).
    private fun String.parseAssTime(): Long? = ASS_TIME.matchEntire(trim())?.destructured
        ?.let { (h, m, s, cs) -> ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000) + cs.toLong() * 10 }

    private fun String.cleanAssText(): String = this
        .replace(ASS_OVERRIDE, "")
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ")
        .trim()

    private fun String.normalizedLines(): List<String> =
        replace("\r\n", "\n").replace('\r', '\n').split("\n")

    private fun ByteArray.withoutBom(): ByteArray =
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
            copyOfRange(3, size)
        } else {
            this
        }

    private fun vttTime(h: String, m: String, s: String, ms: String): String =
        "%02d:%02d:%02d.%03d".format(h.toInt(), m.toInt(), s.toInt(), ms.padEnd(3, '0').toInt())

    private fun Long.toVttTime(): String =
        "%02d:%02d:%02d.%03d".format(this / 3_600_000, this / 60_000 % 60, this / 1000 % 60, this % 1000)

    // 00:01:02,345 --> 00:01:04,567  (SRT allows . or , and 1-3 ms digits)
    private val SRT_TIMING = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{2})""")
    private val ASS_OVERRIDE = Regex("""\{[^}]*}""")
}
