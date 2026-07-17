package com.newash.videocast.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTagTest {

    @Test
    fun `normalizes container and api tags to iso 639-1`() {
        assertEquals("en", LanguageTag.normalize("eng"))
        assertEquals("hu", LanguageTag.normalize("hun"))
        assertEquals("de", LanguageTag.normalize("ger")) // bibliographic
        assertEquals("de", LanguageTag.normalize("deu")) // terminological
        assertEquals("en", LanguageTag.normalize("EN"))
        assertEquals("pt-br", LanguageTag.normalize("pt-BR"))
        assertEquals("hu", LanguageTag.normalize("hu-HU")) // redundant region is container noise
    }

    @Test
    fun `legacy 1989 codes normalize to their modern spelling`() {
        // Locale's ISO3 reverse map lands on the pre-1989 codes (iw/in/ji);
        // OpenSubtitles and human expectation use he/id/yi.
        assertEquals("he", LanguageTag.normalize("heb"))
        assertEquals("he", LanguageTag.normalize("iw"))
        assertEquals("id", LanguageTag.normalize("ind"))
        assertTrue(LanguageTag.matches("heb", "he"))
        assertTrue(LanguageTag.matches("ind", "id"))
    }

    @Test
    fun `unknown or empty tags become null, unmappable real codes pass through`() {
        assertNull(LanguageTag.normalize(null))
        assertNull(LanguageTag.normalize(""))
        assertNull(LanguageTag.normalize("und"))
        assertNull(LanguageTag.normalize("xx")) // not an ISO 639-1 code
        assertNull(LanguageTag.normalize("forced")) // filename decoration, not a language
        assertEquals("fil", LanguageTag.normalize("fil")) // no 639-1 equivalent: honest beats wrong
    }

    @Test
    fun `matching is exact on the primary subtag`() {
        assertTrue(LanguageTag.matches("eng", "en"))
        assertTrue(LanguageTag.matches("spa", "es"))
        assertTrue(LanguageTag.matches("pt-br", "pt"))
        assertFalse(LanguageTag.matches("est", "es")) // Estonian is not Spanish
        assertFalse(LanguageTag.matches("enm", "en")) // Middle English is not English
        assertFalse(LanguageTag.matches(null, "en"))
    }

    @Test
    fun `filename tags cover two- and three-letter spellings`() {
        assertEquals(setOf("en", "eng"), LanguageTag.fileNameTags("en"))
        assertEquals(setOf("de", "deu", "ger"), LanguageTag.fileNameTags("de"))
    }

    @Test
    fun `display names resolve where the platform knows them`() {
        assertEquals("Hungarian", LanguageTag.displayName("hun"))
        assertNull(LanguageTag.displayName(null))
    }
}
