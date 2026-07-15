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
            if (header?.startsWith("bytes=") != true) return None
            val spec = header.removePrefix("bytes=").trim().split("-", limit = 2)
            val first = spec[0].toLongOrNull()
            val second = spec.getOrNull(1)?.toLongOrNull()
            val (start, end) = when {
                first == null && second == null -> return None
                first == null -> maxOf(0, total - second!!) to total - 1 // suffix "-N": final N bytes
                else -> first to minOf(second ?: Long.MAX_VALUE, total - 1)
            }
            return if (start >= total || start > end) Unsatisfiable else Partial(start, end)
        }
    }
}
