package com.newash.videocast.subs

import java.util.Locale

/**
 * Normalizes the language tags this app meets — MKV/MP4 container tags
 * (ISO 639-2 "eng", "spa", bibliographic "ger"), OpenSubtitles codes ("en",
 * "pt-br"), filename tokens — to one shape: lowercase ISO 639-1 with an
 * optional region ("en", "pt-br"). Everything (display, matching, storage,
 * cast track tag) converges on this.
 */
object LanguageTag {

    /** Canonical tag, or null for unknown/blank/"und". Unmappable real codes pass through. */
    fun normalize(tag: String?): String? {
        val parts = tag?.trim()?.lowercase()?.split('-', '_')?.filter(String::isNotEmpty) ?: return null
        val language = parts.firstOrNull()?.takeIf { it.all(Char::isLetter) } ?: return null
        if (language.isBlank() || language == "und") return null
        val primary = when (language.length) {
            2 -> language.takeIf { it in TWO_LETTER } ?: return null
            3 -> ISO3_TO_ISO1[language] ?: BIBLIOGRAPHIC[language] ?: language // honest beats wrong
            else -> return null
        }
        // A region only stays when it says something the language doesn't:
        // "pt-br" is meaningful, "hu-hu" is container noise.
        val region = parts.getOrNull(1)
            ?.takeIf { it.length == 2 && it.all(Char::isLetter) && it != primary }
        return listOfNotNull(primary, region).joinToString("-")
    }

    /** Language part alone: "pt-br" → "pt". */
    fun primary(tag: String?): String? = normalize(tag)?.substringBefore('-')

    /** True when both tags name the same language ("en" vs "eng", "pt" vs "pt-br"). */
    fun matches(a: String?, b: String?): Boolean {
        val pa = primary(a) ?: return false
        return pa == primary(b)
    }

    /** All spellings of [tag] a filename token might use: {"de", "deu", "ger"}. */
    fun fileNameTags(tag: String): Set<String> {
        val primary = primary(tag) ?: return setOf(tag.lowercase())
        val iso3 = runCatching { Locale(primary).isO3Language.lowercase() }.getOrNull()
        val biblio = BIBLIOGRAPHIC.entries.firstOrNull { it.value == primary }?.key
        return setOfNotNull(primary, iso3?.takeIf(String::isNotBlank), biblio)
    }

    /** Human name for a tag ("hu" → "Hungarian"), or null when Android has none. */
    fun displayName(tag: String?): String? = primary(tag)
        ?.let { Locale(it).displayLanguage }
        ?.takeIf { it.isNotBlank() && !it.equals(primary(tag), ignoreCase = true) }

    private val TWO_LETTER: Set<String> = Locale.getISOLanguages().toSet()

    private val ISO3_TO_ISO1: Map<String, String> = TWO_LETTER.associateBy { two ->
        Locale(two).isO3Language
    }.filterKeys { it.isNotBlank() }

    // ISO 639-2 bibliographic variants, which Locale's terminological mapping misses.
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
        "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
        "gre" to "el", "ice" to "is", "mac" to "mk", "may" to "ms", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )
}
