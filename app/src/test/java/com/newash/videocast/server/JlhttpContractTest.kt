package com.newash.videocast.server

import net.freeutils.httpserver.HTTPServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Contract test mirroring MediaServer's JLHTTP usage (file-backed instead of
 * ContentResolver): pins the range/CORS/OPTIONS/HEAD semantics over real HTTP.
 */
class JlhttpContractTest {

    // Ephemeral: debug and release unit-test variants run concurrently in CI.
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var file: File
    private lateinit var server: HTTPServer
    private val body = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun startServer() {
        file = File.createTempFile("video", ".bin").apply { writeBytes(body) }
        server = HTTPServer(port).apply {
            getVirtualHost(null).addContext("/video", { req, resp ->
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
                resp.sendHeaders(200, total, -1, null, "video/mp4", range)
                file.inputStream().use { resp.sendBody(it, total, range) }
                0
            }, "GET", "OPTIONS")
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
        file.delete()
    }

    private fun request(rangeHeader: String? = null, method: String = "GET"): HttpURLConnection =
        (URL("http://127.0.0.1:$port/video").openConnection() as HttpURLConnection).apply {
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
}
