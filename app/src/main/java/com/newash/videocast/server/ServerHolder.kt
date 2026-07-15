package com.newash.videocast.server

import android.content.Context
import java.io.IOException

/**
 * Process-wide owner of the single [MediaServer] instance. The ViewModel starts
 * it synchronously (so the media URL is valid before the Cast load request is
 * sent) and [StreamingService] keeps the process alive while it runs.
 */
object ServerHolder {

    @Volatile
    var server: MediaServer? = null
        private set

    @Synchronized
    fun ensureStarted(context: Context): MediaServer {
        server?.let { if (it.isAlive) return it }
        var lastError: IOException? = null
        for (port in MediaServer.DEFAULT_PORT until MediaServer.DEFAULT_PORT + 10) {
            val candidate = MediaServer(context.applicationContext, port)
            try {
                candidate.start(SOCKET_READ_TIMEOUT_MS, false)
                server = candidate
                return candidate
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("could not start media server")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    // Generous read timeout: the Chromecast holds connections open while buffering.
    private const val SOCKET_READ_TIMEOUT_MS = 30_000
}
