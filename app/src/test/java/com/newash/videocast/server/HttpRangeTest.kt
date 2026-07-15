package com.newash.videocast.server

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRangeTest {

    @Test
    fun `no header serves the whole file`() {
        assertEquals(HttpRange.None, HttpRange.resolve(null, 1000))
        assertEquals(HttpRange.None, HttpRange.resolve("chunks=0-1", 1000))
        assertEquals(HttpRange.None, HttpRange.resolve("bytes=-", 1000))
        assertEquals(HttpRange.None, HttpRange.resolve("bytes=abc-def", 1000))
    }

    @Test
    fun `explicit range is honoured inclusively`() {
        assertEquals(HttpRange.Partial(100, 199), HttpRange.resolve("bytes=100-199", 1000))
        assertEquals(100L, (HttpRange.resolve("bytes=100-199", 1000) as HttpRange.Partial).length)
        assertEquals(HttpRange.Partial(0, 0), HttpRange.resolve("bytes=0-0", 1000))
    }

    @Test
    fun `open-ended range runs to the last byte`() {
        assertEquals(HttpRange.Partial(500, 999), HttpRange.resolve("bytes=500-", 1000))
    }

    @Test
    fun `end beyond the file is clamped`() {
        assertEquals(HttpRange.Partial(500, 999), HttpRange.resolve("bytes=500-99999", 1000))
    }

    @Test
    fun `suffix range returns the final n bytes`() {
        assertEquals(HttpRange.Partial(900, 999), HttpRange.resolve("bytes=-100", 1000))
        // Suffix longer than the file returns the whole file as a partial.
        assertEquals(HttpRange.Partial(0, 999), HttpRange.resolve("bytes=-5000", 1000))
    }

    @Test
    fun `start past the end is unsatisfiable`() {
        assertEquals(HttpRange.Unsatisfiable, HttpRange.resolve("bytes=1000-", 1000))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.resolve("bytes=200-100", 1000))
    }

    @Test
    fun `zero-length suffix is unsatisfiable`() {
        assertEquals(HttpRange.Unsatisfiable, HttpRange.resolve("bytes=-0", 1000))
    }

    @Test
    fun `multi-range degrades to an open-ended first range`() {
        // Deliberate: multipart responses aren't supported, and the Chromecast only
        // sends single ranges. The emitted Content-Range stays truthful.
        assertEquals(HttpRange.Partial(0, 999), HttpRange.resolve("bytes=0-99,200-299", 1000))
    }
}
