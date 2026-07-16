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
        server?.let { return it }
        var lastError: IOException? = null
        val started = (MediaServer.DEFAULT_PORT until MediaServer.DEFAULT_PORT + 10)
            .firstNotNullOfOrNull { port ->
                try {
                    MediaServer(context.applicationContext, port).also(MediaServer::start)
                } catch (e: IOException) {
                    lastError = e
                    null
                }
            } ?: throw (lastError ?: IOException("could not start media server"))
        server = started
        return started
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }
}
