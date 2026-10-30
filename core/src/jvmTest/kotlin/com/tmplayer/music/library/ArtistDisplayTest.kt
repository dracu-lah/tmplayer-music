package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistDisplayTest {

    @Test
    fun `pipes read as commas`() {
        assertEquals("Ana Brio, Cato Dell, Music Director", ArtistDisplay.of("Ana Brio|Cato Dell|Music Director"))
        assertEquals("Ana, Brio", ArtistDisplay.of("Ana | Brio"))
        assertEquals("Ana, Brio", ArtistDisplay.of("Ana  |  Brio"))
    }

    @Test
    fun `NULs read as commas`() {
        assertEquals("Ana, Brio", ArtistDisplay.of("Ana\u0000Brio"))
        assertEquals("Ana, Brio", ArtistDisplay.of("Ana\u0000Brio\u0000"))
    }

    @Test
    fun `empty names between separators are dropped`() {
        assertEquals("Ana, Brio", ArtistDisplay.of("|Ana||| |Brio|"))
        assertEquals("Ana, Brio", ArtistDisplay.of("Ana|\u0000|Brio"))
    }

    @Test
    fun `a credit with nothing to change is returned as it is`() {
        val credit = "Ana, Brio & Cato feat. Dell"
        assertSame(credit, ArtistDisplay.of(credit))
        assertEquals("AC/DC", ArtistDisplay.of("AC/DC"))
    }

    @Test
    fun `only separators, blanks and null read as nothing`() {
        assertEquals("", ArtistDisplay.of(null))
        assertEquals("", ArtistDisplay.of(""))
        assertEquals("", ArtistDisplay.of("|||"))
        assertEquals("", ArtistDisplay.of(" | \u0000 | "))
        assertEquals("", ArtistDisplay.of("\u0000"))
    }

    @Test
    fun `control characters become single spaces`() {
        assertEquals("Ana Brio, Cato", ArtistDisplay.of("Ana\n\tBrio|Cato\u0007"))
    }

    @Test
    fun `huge credits stay bounded`() {
        val shown = ArtistDisplay.of("a|".repeat(500_000))
        assertTrue(shown.length <= TextFold.MAX_FIELD)
        assertTrue(shown.startsWith("a, a, a"))
        assertTrue(ArtistDisplay.of("x".repeat(1_000_000)).length <= TextFold.MAX_FIELD)
    }
}
