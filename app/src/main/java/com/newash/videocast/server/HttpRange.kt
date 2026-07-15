package com.newash.videocast.server

/**
 * HTTP `Range` header resolution against a resource of known total size.
 * Pure logic, kept separate from [MediaServer] so it can be unit tested —
 * broken range handling is the classic cause of broken Chromecast seeking.
 */
sealed class HttpRange {

    /** No (or malformed) Range header: respond 200 with the whole resource. */
    data object None : HttpRange()

    /** Respond 206 with bytes [start]..[end] inclusive. */
    data class Partial(val start: Long, val end: Long) : HttpRange() {
        val length: Long get() = end - start + 1
    }

    /** Respond 416. */
    data object Unsatisfiable : HttpRange()

    companion object {
        fun resolve(header: String?, total: Long): HttpRange {
            if (header == null || !header.startsWith("bytes=")) return None
            val spec = header.removePrefix("bytes=").trim().split("-", limit = 2)
            var start = spec.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            var end = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            if (start == null && end == null) return None
            if (start == null) {
                // Suffix form "bytes=-N": the final N bytes.
                start = maxOf(0, total - end!!)
                end = total - 1
            } else if (end == null || end >= total) {
                end = total - 1
            }
            if (start >= total || start > end) return Unsatisfiable
            return Partial(start, end)
        }
    }
}
