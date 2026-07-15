package com.newash.videocast.server

import android.content.Context
import android.net.Uri
import net.freeutils.httpserver.HTTPServer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * LAN HTTP server (JLHTTP) the Chromecast fetches media from. Two routes:
 *
 * - `/video`    — the picked video file, with Range/206 support (required for seeking)
 * - `/subs.vtt` — the converted WebVTT subtitle track
 *
 * Every response carries permissive CORS headers: the default media receiver
 * fetches text tracks with CORS enforced and silently drops the track without
 * them. Range parsing, 206/Content-Range emission, body slicing, and HEAD
 * handling are all JLHTTP's (RFC 9110-conformant); ours is only CORS and the
 * skip-by-reading stream wrapper.
 */
class MediaServer(private val context: Context, val port: Int) {

    data class Video(val uri: Uri, val mime: String, val length: Long)

    @Volatile
    var video: Video? = null

    @Volatile
    var subtitleVtt: String? = null

    private val server = HTTPServer(port).apply {
        setSocketTimeout(SOCKET_TIMEOUT_MS)
        getVirtualHost(null).run {
            // Registering OPTIONS routes CORS preflights through our handlers
            // instead of JLHTTP's default OPTIONS response (which lacks CORS).
            addContext(VIDEO_PATH, cors(::serveVideo), "GET", "OPTIONS")
            addContext(SUBTITLE_PATH, cors(::serveSubtitles), "GET", "OPTIONS")
        }
    }

    @Throws(IOException::class)
    fun start() = server.start()

    fun stop() = server.stop()

    private fun cors(handler: HTTPServer.ContextHandler) = HTTPServer.ContextHandler { req, resp ->
        resp.headers.run {
            add("Access-Control-Allow-Origin", "*")
            add("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            add("Access-Control-Allow-Headers", "Content-Type, Range")
            add("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        }
        if (req.method == "OPTIONS") {
            resp.sendHeaders(204)
            0
        } else {
            handler.serve(req, resp)
        }
    }

    private fun serveSubtitles(req: HTTPServer.Request, resp: HTTPServer.Response): Int {
        val bytes = (subtitleVtt ?: return 404).toByteArray(Charsets.UTF_8)
        resp.sendHeaders(200, bytes.size.toLong(), -1, null, "text/vtt; charset=utf-8", null)
        resp.sendBody(bytes.inputStream(), bytes.size.toLong(), null)
        return 0
    }

    private fun serveVideo(req: HTTPServer.Request, resp: HTTPServer.Response): Int {
        val video = this.video ?: return 404
        val total = video.length
        resp.headers.add("Accept-Ranges", "bytes")
        // null on a missing or invalid Range header → serve the whole file [RFC9110#14.2].
        val range = req.getRange(total)
        if (range != null && range[0] >= total) {
            resp.headers.add("Content-Range", "bytes */$total")
            return 416
        }
        resp.sendHeaders(200, total, -1, null, video.mime, range) // a range flips this to 206
        openStream(video).use { resp.sendBody(it, total, range) }
        return 0
    }

    private fun openStream(video: Video): InputStream =
        context.contentResolver.openInputStream(video.uri)?.let(::SkipByReadingInputStream)
            ?: throw IOException("cannot open ${video.uri}")

    /**
     * JLHTTP's range slicing calls skip() and treats 0 as failure; some
     * DocumentsProvider streams refuse skip(), so fall back to reading.
     */
    private class SkipByReadingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0 || n <= 0) return skipped
            val buffer = ByteArray(minOf(n, 64L * 1024).toInt())
            val read = read(buffer)
            return if (read < 0) 0 else read.toLong()
        }
    }

    companion object {
        const val VIDEO_PATH = "/video"
        const val SUBTITLE_PATH = "/subs.vtt"
        const val DEFAULT_PORT = 8394

        // Generous: the Chromecast holds connections open while buffering.
        private const val SOCKET_TIMEOUT_MS = 30_000
    }
}
