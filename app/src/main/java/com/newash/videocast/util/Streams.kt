package com.newash.videocast.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bounded whole-stream read: an oversized (mispicked) source fails instead of eating the heap. */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > limit) throw IOException("larger than ${limit / (1 shl 20)} MB — refusing to read whole")
    }
}
