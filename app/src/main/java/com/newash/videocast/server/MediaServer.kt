package com.newash.videocast.server

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

/**
 * LAN HTTP server the Chromecast fetches media from. Two routes:
 *
 * - `/video`    — the picked video file, with Range/206 support (required for seeking)
 * - `/subs.vtt` — the converted WebVTT subtitle track
 *
 * Every response carries permissive CORS headers: the default media receiver
 * fetches text tracks with CORS enforced and silently drops the track without them.
 */
class MediaServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    data class Video(val uri: Uri, val mime: String, val length: Long)

    @Volatile
    var video: Video? = null

    @Volatile
    var subtitleVtt: String? = null

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            when {
                session.method == Method.OPTIONS ->
                    newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                session.uri == VIDEO_PATH -> serveVideo(session)
                session.uri == SUBTITLE_PATH -> serveSubtitles()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        return response
    }

    private fun serveSubtitles(): Response {
        val vtt = subtitleVtt
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no subtitles loaded")
        return newFixedLengthResponse(Response.Status.OK, "text/vtt; charset=utf-8", vtt)
    }

    private fun serveVideo(session: IHTTPSession): Response {
        val video = this.video
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no video loaded")
        val total = video.length
        val response = when (val range = HttpRange.resolve(session.headers["range"], total)) {
            is HttpRange.None ->
                newFixedLengthResponse(Response.Status.OK, video.mime, openStream(video), total)
            is HttpRange.Unsatisfiable ->
                newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "range not satisfiable")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            is HttpRange.Partial -> {
                val stream = openStream(video)
                try {
                    stream.skipFully(range.start)
                } catch (e: IOException) {
                    stream.close()
                    throw e
                }
                // NanoHTTPD's fixed-length send path caps the body at the declared
                // length, so the stream needs no explicit bounding.
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, video.mime, stream, range.length)
                    .apply { addHeader("Content-Range", "bytes ${range.start}-${range.end}/$total") }
            }
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
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
                // Some content streams refuse skip(); fall back to reading.
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
    }
}
