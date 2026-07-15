package com.newash.videocast.server

import android.content.Context
import android.net.Uri
import net.freeutils.httpserver.HTTPServer
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
 * them. Range parsing, 206/Content-Range emission, and HEAD handling come from
 * JLHTTP (RFC 9110-conformant); only the stream pre-skip is ours.
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
            addContext(VIDEO_PATH, { req, resp -> withCors(req, resp, ::serveVideo) }, "GET", "OPTIONS")
            addContext(SUBTITLE_PATH, { req, resp -> withCors(req, resp, ::serveSubtitles) }, "GET", "OPTIONS")
        }
    }

    @Throws(IOException::class)
    fun start() = server.start()

    fun stop() = server.stop()

    private fun withCors(
        req: HTTPServer.Request,
        resp: HTTPServer.Response,
        handler: (HTTPServer.Request, HTTPServer.Response) -> Int,
    ): Int {
        resp.headers.run {
            add("Access-Control-Allow-Origin", "*")
            add("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            add("Access-Control-Allow-Headers", "Content-Type, Range")
            add("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        }
        return if (req.method == "OPTIONS") {
            resp.sendHeaders(204)
            0
        } else {
            handler(req, resp)
        }
    }

    private fun serveSubtitles(req: HTTPServer.Request, resp: HTTPServer.Response): Int {
        val bytes = (subtitleVtt ?: return 404).toByteArray(Charsets.UTF_8)
        resp.headers.add("Content-Type", "text/vtt; charset=utf-8")
        resp.sendHeaders(200, bytes.size.toLong(), -1, null, null, null)
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
        openStream(video).use { stream ->
            // Pre-skip with a read fallback: some DocumentsProvider streams refuse
            // skip(), which JLHTTP's own range skipping relies on exclusively.
            stream.skipFully(range?.get(0) ?: 0)
            resp.sendBody(stream, range?.let { it[1] - it[0] + 1 } ?: total, null)
        }
        return 0
    }

    private fun openStream(video: Video): InputStream =
        context.contentResolver.openInputStream(video.uri)
            ?: throw IOException("cannot open ${video.uri}")

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        var buffer: ByteArray? = null
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val buf = buffer ?: ByteArray(64 * 1024).also { buffer = it }
                val read = read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                if (read < 0) throw IOException("EOF while seeking to range start")
                remaining -= read
            }
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
