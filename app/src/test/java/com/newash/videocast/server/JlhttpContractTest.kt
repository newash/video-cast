package com.newash.videocast.server

import net.freeutils.httpserver.HTTPServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Contract test mirroring MediaServer's JLHTTP usage (file-backed instead of
 * ContentResolver): pins the range/CORS/OPTIONS/HEAD semantics over real HTTP.
 */
class JlhttpContractTest {

    private var port = 0
    private lateinit var file: File
    private lateinit var server: HTTPServer
    private val body = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun startServer() {
        file = File.createTempFile("video", ".bin").apply { writeBytes(body) }
        // Ephemeral ports with a bind retry: test variants run concurrently in CI,
        // and the probed port can be stolen before HTTPServer rebinds it.
        server = (1..3).firstNotNullOfOrNull {
            val candidate = ServerSocket(0).use { it.localPort }
            runCatching { buildServer(candidate).also(HTTPServer::start) }.getOrNull()
                ?.also { port = candidate }
        } ?: error("could not bind a test server port")
    }

    // Mirrors MediaServer: HEAD registered explicitly, which bypasses JLHTTP's
    // default HEAD handling — so the handlers themselves must guard the body.
    private fun buildServer(port: Int): HTTPServer = HTTPServer(port).apply {
        getVirtualHost(null).run {
            addContext("/video", { req, resp ->
                resp.headers.run {
                    add("Access-Control-Allow-Origin", "*")
                    add("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                }
                if (req.method == "OPTIONS") {
                    resp.sendHeaders(204)
                    return@addContext 0
                }
                val total = file.length()
                resp.headers.add("Accept-Ranges", "bytes")
                val range = req.getRange(total)
                if (range != null && range[0] >= total) {
                    resp.headers.add("Content-Range", "bytes */$total")
                    return@addContext 416
                }
                if (req.method == "HEAD") {
                    resp.sendHeaders(200, total, -1, null, "video/mp4", range)
                    return@addContext 0
                }
                resp.sendHeaders(200, total, -1, null, "video/mp4", range)
                file.inputStream().use { resp.sendBody(it, total, range) }
                0
            }, "GET", "HEAD", "OPTIONS")
            addContext("/subs.vtt", { req, resp ->
                resp.sendHeaders(200, SUBS.size.toLong(), -1, null, "text/vtt; charset=utf-8", null)
                if (req.method != "HEAD") resp.sendBody(SUBS.inputStream(), SUBS.size.toLong(), null)
                0
            }, "GET", "HEAD")
        }
    }

    @After
    fun stopServer() {
        server.stop()
        file.delete()
    }

    private fun request(rangeHeader: String? = null, method: String = "GET", query: String = ""): HttpURLConnection =
        (URL("http://127.0.0.1:$port/video$query").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            rangeHeader?.let { setRequestProperty("Range", it) }
            setRequestProperty("Connection", "close") // no keep-alive reuse across per-test server restarts
            connect()
        }

    @Test
    fun `full file without range`() = request().run {
        assertEquals(200, responseCode)
        assertEquals("bytes", getHeaderField("Accept-Ranges"))
        assertEquals("*", getHeaderField("Access-Control-Allow-Origin"))
        assertEquals(body.toList(), inputStream.readBytes().toList())
    }

    @Test
    fun `explicit range returns 206 with exact bytes`() = request("bytes=100-199").run {
        assertEquals(206, responseCode)
        assertEquals("bytes 100-199/1000", getHeaderField("Content-Range"))
        assertEquals("100", getHeaderField("Content-Length"))
        assertEquals(body.slice(100..199), inputStream.readBytes().toList())
    }

    @Test
    fun `open-ended and suffix ranges`() {
        request("bytes=900-").run {
            assertEquals(206, responseCode)
            assertEquals("bytes 900-999/1000", getHeaderField("Content-Range"))
            assertEquals(body.slice(900..999), inputStream.readBytes().toList())
        }
        request("bytes=-100").run {
            assertEquals(206, responseCode)
            assertEquals("bytes 900-999/1000", getHeaderField("Content-Range"))
        }
    }

    @Test
    fun `range past the end is 416 with total`() = request("bytes=5000-").run {
        assertEquals(416, responseCode)
        assertEquals("bytes */1000", getHeaderField("Content-Range"))
    }

    @Test
    fun `invalid range degrades to 200`() = request("bytes=abc-def").run {
        assertEquals(200, responseCode)
        assertNull(getHeaderField("Content-Range"))
    }

    @Test
    fun `options preflight carries cors headers`() = request(method = "OPTIONS").run {
        assertEquals(204, responseCode)
        assertEquals("*", getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test
    fun `head returns headers without body`() = request(method = "HEAD").run {
        assertEquals(200, responseCode)
        assertEquals("1000", getHeaderField("Content-Length"))
        assertEquals(-1, inputStream.read())
    }

    @Test
    fun `query strings do not affect context routing`() = request(query = "?v=3").run {
        // The subtitle URL carries a cache-busting ?v=N; routing must ignore it.
        assertEquals(200, responseCode)
        assertEquals("1000", getHeaderField("Content-Length"))
    }

    @Test
    fun `explicitly registered head sends no body bytes`() {
        // Raw socket: HttpURLConnection ignores an (incorrect) HEAD body, but on
        // a keep-alive connection it would desync every following response.
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream()
                .write("HEAD /subs.vtt HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.lowercase().contains("content-length: ${SUBS.size}"))
            assertEquals("", response.substringAfter("\r\n\r\n"))
        }
    }

    private companion object {
        val SUBS = "WEBVTT\n\nhello".toByteArray()
    }
}
