package com.newash.videocast.subs

import org.mozilla.universalchardet.UniversalDetector
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
            // The receiver requires "WEBVTT" at offset 0, so shed any leading whitespace.
            Format.VTT -> text.trimStart()
            Format.SRT -> srtToVtt(text)
            Format.ASS -> assToVtt(text)
        }
    }

    /**
     * BOM first, then Mozilla's charset detector (legacy SRTs come in windows-125x,
     * ISO-8859-x, and CJK codepages), then strict UTF-8 with a windows-1252 fallback.
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.hasPrefix(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.hasPrefix(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.hasPrefix(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> detectCharset(bytes)?.let { String(bytes, it) } ?: utf8OrWindows1252(bytes)
    }

    private fun detectCharset(bytes: ByteArray): Charset? = runCatching {
        UniversalDetector(null).run {
            handleData(bytes, 0, minOf(bytes.size, 64 * 1024))
            dataEnd()
            detectedCharset?.let(Charset::forName)
        }
    }.getOrNull()

    private fun utf8OrWindows1252(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }

    fun detectFormat(text: String, fileName: String?): Format {
        val head = text.trimStart()
        return when {
            head.startsWith("WEBVTT") -> Format.VTT
            head.startsWith("[Script Info]") || ASS_EVENTS_HEADER.containsMatchIn(text) -> Format.ASS
            else -> when (fileName?.substringAfterLast('.', "")?.lowercase()) {
                "vtt" -> Format.VTT
                "ass", "ssa" -> Format.ASS
                else -> Format.SRT
            }
        }
    }

    fun srtToVtt(text: String): String =
        "WEBVTT\n\n" + text.normalized().replace(SRT_TIMING) { m ->
            val g = m.groupValues
            "${srtTime(g[1], g[2], g[3], g[4])} --> ${srtTime(g[5], g[6], g[7], g[8])}"
        } + "\n"

    fun assToVtt(text: String): String {
        val events = text.normalized().split("\n")
            .dropWhile { !it.trim().equals("[Events]", ignoreCase = true) }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
        val fields = events.firstNotNullOfOrNull { it.sectionValue("Format:") }
            ?.split(',')?.map { field -> field.trim().lowercase() }
            ?: ASS_DEFAULT_FIELDS
        return events
            .mapNotNull { it.sectionValue("Dialogue:")?.toAssCue(fields) }
            .sortedBy(AssCue::startMs)
            .joinToString(separator = "\n\n", prefix = "WEBVTT\n\n", postfix = "\n") { cue ->
                "${cue.startMs.toVttTime()} --> ${cue.endMs.toVttTime()}\n${cue.text}"
            }
    }

    private data class AssCue(val startMs: Long, val endMs: Long, val text: String)

    private fun String.toAssCue(fields: List<String>): AssCue? {
        val values = split(",", limit = fields.size).map(String::trim)
        if (values.size < fields.size) return null
        fun field(name: String): String? = fields.indexOf(name).takeIf { it >= 0 }?.let(values::get)
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

    private fun String.normalized(): String = replace("\r\n", "\n").replace('\r', '\n')

    private fun ByteArray.hasPrefix(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.withIndex().all { (i, b) -> this[i] == b.toByte() }

    private fun srtTime(h: String, m: String, s: String, ms: String): String =
        ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong()).toVttTime()

    private fun Long.toVttTime(): String =
        "%02d:%02d:%02d.%03d".format(this / 3_600_000, this / 60_000 % 60, this / 1000 % 60, this % 1000)

    // 00:01:02,345 --> 00:01:04,567  (SRT allows . or , and 1-3 ms digits).
    // Horizontal-only whitespace keeps the match line-scoped on the full text.
    private val SRT_TIMING = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})[ \t]*-->[ \t]*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val ASS_TIME = Regex("""(\d{1,4}):(\d{2}):(\d{2})[.:](\d{2})""")
    private val ASS_OVERRIDE = Regex("""\{[^}]*}""")
    private val ASS_EVENTS_HEADER = Regex("""(?m)^\s*\[Events]""")

    // Spec-default v4+ field order, used when a malformed file omits its Format: line.
    private val ASS_DEFAULT_FIELDS = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
    )
}
