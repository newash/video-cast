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
            // HEAD is registered too: JLHTTP's default HEAD handler rewrites the
            // method to GET, which would make serveVideo open the content stream
            // for a body that is never sent.
            addContext(VIDEO_PATH, cors(::serveVideo), "GET", "HEAD", "OPTIONS")
            addContext(SUBTITLE_PATH, cors(::serveSubtitles), "GET", "HEAD", "OPTIONS")
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
        val length = bytes.size.toLong()
        // no-store: the VTT can be replaced mid-session (late-finished extraction);
        // a cached copy on the receiver would pin the stale cues.
        resp.headers.add("Cache-Control", "no-store")
        resp.sendHeaders(200, length, -1, null, "text/vtt; charset=utf-8", null)
        resp.sendBody(bytes.inputStream(), length, null)
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
        if (req.method == "HEAD") { // headers only: don't open content for a body we won't send
            resp.sendHeaders(200, total, -1, null, video.mime, range)
            return 0
        }
        // Open the content before committing headers: after sendHeaders a failure
        // can only abort the connection, not produce an error response.
        val stream = try {
            openStream(video)
        } catch (_: IOException) {
            return 404
        }
        stream.use {
            resp.sendHeaders(200, total, -1, null, video.mime, range) // a range flips this to 206
            resp.sendBody(it, total, range)
        }
        return 0
    }

    private fun openStream(video: Video): InputStream =
        context.contentResolver.openInputStream(video.uri)?.let(::SkipByReadingInputStream)
            ?: throw IOException("cannot open ${video.uri}")

    /**
     * JLHTTP's range slicing calls skip() and treats 0 as failure; ContentResolver
     * streams either refuse skip() (return 0) or, when pipe-backed, throw
     * IOException("Illegal seek") — fall back to reading in both cases.
     */
    private class SkipByReadingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        private val scratch = ByteArray(64 * 1024) // reused: pipe seeks skip() thousands of times

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val skipped = try {
                super.skip(n)
            } catch (_: IOException) {
                0L
            }
            if (skipped > 0) return skipped
            val read = read(scratch, 0, minOf(n, scratch.size.toLong()).toInt())
            return if (read < 0) 0 else read.toLong()
        }
    }

    companion object {
        const val VIDEO_PATH = "/video"
        const val SUBTITLE_PATH = "/subs.vtt"
        const val DEFAULT_PORT = 8394

        /** Valid-but-empty sidecar: served while an extraction is still producing the real one. */
        const val EMPTY_VTT = "WEBVTT\n\n"

        // Generous: the Chromecast holds connections open while buffering.
        private const val SOCKET_TIMEOUT_MS = 30_000
    }
}
