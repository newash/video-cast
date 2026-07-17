package com.newash.videocast.subs

/** Matches subtitle files sitting next to a video, e.g. "Movie.en.srt" for "Movie.mkv". */
object SiblingSubtitles {

    private val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    /**
     * Best sibling subtitle among [names]: a file tagged with one of [langTags]
     * ("Movie.en.srt", "Movie.eng.forced.srt") beats a plain undecorated
     * sibling ("Movie.srt"); files tagged with other languages never match.
     */
    fun bestMatch(videoName: String, langTags: Set<String>, names: List<String>): String? {
        val base = videoName.substringBeforeLast('.')
        fun rank(name: String): Int? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in EXTENSIONS || !name.startsWith("$base.", ignoreCase = true)) return null
            val tokens = name.drop(base.length + 1).dropLast(ext.length + 1)
                .split('.').filter(String::isNotEmpty).map(String::lowercase)
            return when {
                tokens.isEmpty() -> 2 // plain "base.ext"
                tokens.any(langTags::contains) -> 1 // carries the saved language
                else -> null // some other language's (or unrelated) file
            }
        }
        return names.mapNotNull { name -> rank(name)?.let { it to name } }
            // Ties break toward the least-decorated name.
            .minWithOrNull(compareBy({ it.first }, { it.second.length }))?.second
    }
}
