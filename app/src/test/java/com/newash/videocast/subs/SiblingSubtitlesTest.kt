package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiblingSubtitlesTest {

    private val video = "House.of.the.Dragon.S01E01.720p.x265.mkv"
    private val tags = setOf("en", "eng")

    private fun best(vararg names: String): String? =
        SiblingSubtitles.bestMatch(video, tags, names.toList())

    @Test
    fun `language-tagged sibling beats plain sibling`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.en.srt",
            best(
                "House.of.the.Dragon.S01E01.720p.x265.srt",
                "House.of.the.Dragon.S01E01.720p.x265.en.srt",
            ),
        )
    }

    @Test
    fun `plain sibling matches when no tagged one exists`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.srt",
            best("House.of.the.Dragon.S01E01.720p.x265.srt", "unrelated.en.srt"),
        )
    }

    @Test
    fun `three-letter code and extra tokens still count as the language`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.eng.forced.srt",
            best("House.of.the.Dragon.S01E01.720p.x265.eng.forced.srt"),
        )
    }

    @Test
    fun `other languages and non-subtitle files never match`() {
        assertNull(
            best(
                "House.of.the.Dragon.S01E01.720p.x265.hu.srt", // foreign language
                "House.of.the.Dragon.S01E01.720p.x265.nfo", // wrong extension
                "House.of.the.Dragon.S01E02.720p.x265.srt", // different episode
            ),
        )
    }

    @Test
    fun `region-tagged spelling of the saved language matches`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.pt-br.srt",
            SiblingSubtitles.bestMatch(
                video, setOf("pt", "por"),
                listOf("House.of.the.Dragon.S01E01.720p.x265.pt-br.srt"),
            ),
        )
        // Estonian must not match Spanish through primary-code normalization.
        assertNull(
            SiblingSubtitles.bestMatch(
                video, setOf("es", "spa"),
                listOf("House.of.the.Dragon.S01E01.720p.x265.est.srt"),
            ),
        )
    }

    @Test
    fun `tie between tagged names goes to the least decorated`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.en.ass",
            best(
                "House.of.the.Dragon.S01E01.720p.x265.en.forced.srt",
                "House.of.the.Dragon.S01E01.720p.x265.en.ass",
            ),
        )
    }

    @Test
    fun `extension casing is ignored`() {
        assertEquals(
            "House.of.the.Dragon.S01E01.720p.x265.EN.SRT",
            best("House.of.the.Dragon.S01E01.720p.x265.EN.SRT"),
        )
    }

    @Test
    fun `parent document ids follow saf path conventions`() {
        assertEquals("primary:Movies", SiblingSubtitles.parentDocumentId("primary:Movies/a.mkv"))
        assertEquals("primary:", SiblingSubtitles.parentDocumentId("primary:a.mkv")) // storage root
        assertEquals("msf:", SiblingSubtitles.parentDocumentId("msf:123")) // non-path id: query will just fail
        assertEquals(null, SiblingSubtitles.parentDocumentId("noseparator"))
    }

    @Test
    fun `tree membership requires a path boundary`() {
        assertTrue(SiblingSubtitles.isUnderTree("primary:Movies/a.mkv", "primary:Movies"))
        assertFalse(SiblingSubtitles.isUnderTree("primary:MoviesHD/a.mkv", "primary:Movies"))
        assertTrue(SiblingSubtitles.isUnderTree("primary:a.mkv", "primary:")) // tree at storage root
        assertTrue(SiblingSubtitles.isUnderTree("nas:Films/x.mkv", "nas:"))
    }
}
