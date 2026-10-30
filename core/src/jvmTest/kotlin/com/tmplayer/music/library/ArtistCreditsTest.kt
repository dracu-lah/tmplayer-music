package com.tmplayer.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Splitting a credit into the artists the Artists tab lists (decision k). */
class ArtistCreditsTest {

    private fun names(credit: String) = ArtistCredits.split(credit).map { it.name }

    @Test
    fun aSingleArtistIsOne() {
        assertEquals(listOf("Korvane"), names("Korvane"))
        assertEquals(listOf(CreditedArtist("Zoë Lindqvist", "zoe lindqvist")), ArtistCredits.split("  Zoë   Lindqvist "))
    }

    @Test
    fun commasSplitWithOrWithoutSpaces() {
        assertEquals(listOf("Ana Rell", "Brio Dane", "Cato Venn"), names("Ana Rell, Brio Dane,Cato Venn"))
        assertEquals(listOf("A.R. Velan", "Odo Kanagar"), names("A.R. Velan, Odo Kanagar"))
    }

    @Test
    fun aCommaInsideANumberIsNotASeparator() {
        assertEquals(listOf("10,000 Lanterns"), names("10,000 Lanterns"))
        assertEquals(listOf("10,000 Lanterns", "Brio"), names("10,000 Lanterns, Brio"))
    }

    @Test
    fun featWordsSplit() {
        assertEquals(listOf("Ana", "Brio"), names("Ana feat. Brio"))
        assertEquals(listOf("Ana", "Brio"), names("Ana feat Brio"))
        assertEquals(listOf("Ana", "Brio"), names("Ana ft. Brio"))
        assertEquals(listOf("Ana", "Brio"), names("Ana FT Brio"))
        assertEquals(listOf("Ana", "Brio"), names("Ana Featuring Brio"))
        assertEquals(listOf("Ana", "Brio"), names("Ana (feat. Brio)"))
        assertEquals(listOf("Ana", "Brio"), names("Ana [ft. Brio]"))
    }

    @Test
    fun featInsideAWordIsNotASeparator() {
        assertEquals(listOf("Softness"), names("Softness"))
        assertEquals(listOf("Leftfeat Brio"), names("Leftfeat Brio"))
    }

    @Test
    fun withIsNotASeparator() {
        assertEquals(listOf("Ana with Brio"), names("Ana with Brio"))
    }

    @Test
    fun pipeAndNulSplit() {
        assertEquals(listOf("Ana", "Brio", "Cato"), names("Ana|Brio | Cato"))
        assertEquals(listOf("Ana", "Brio"), names("Ana\u0000Brio"))
    }

    @Test
    fun slashAndSemicolonNeverSplit() {
        assertEquals(listOf("AB/CD"), names("AB/CD"))
        assertEquals(listOf("Ana / Brio"), names("Ana / Brio"))
        assertEquals(listOf("Ana; Brio"), names("Ana; Brio"))
    }

    @Test
    fun aLoneAmpersandOrAndKeepsTheName() {
        assertEquals(listOf("Ana & The Pale Rivers"), names("Ana & The Pale Rivers"))
        assertEquals(listOf("Ana and Brio"), names("Ana and Brio"))
        assertEquals(listOf("Ana&Brio"), names("Ana&Brio"))
    }

    @Test
    fun ampersandAndAndSplitInAList() {
        assertEquals(listOf("Ana", "Brio", "Cato"), names("Ana, Brio & Cato"))
        assertEquals(listOf("Ana", "Brio", "Cato"), names("Ana, Brio and Cato"))
        assertEquals(listOf("Ana", "Brio", "Cato"), names("Ana & Brio feat. Cato"))
        assertEquals(listOf("Ana", "Brio", "Cato"), names("Ana | Brio AND Cato"))
        // A bare ampersand, without spaces, is part of a name even in a list.
        assertEquals(listOf("Ana&Brio", "Cato"), names("Ana&Brio, Cato"))
    }

    @Test
    fun placeholdersAndEmptyNamesAreDropped() {
        assertEquals(emptyList(), names(""))
        assertEquals(emptyList(), names("   "))
        assertEquals(emptyList(), names("Unknown"))
        assertEquals(emptyList(), names("<unknown>"))
        assertEquals(emptyList(), names("Unknown Artist"))
        assertEquals(listOf("Ana"), names("Ana, <unknown>, , "))
        assertEquals(listOf("Ana"), names("Unknown feat. Ana"))
    }

    @Test
    fun variousArtistsStaysOneArtist() {
        assertEquals(listOf("Various Artists"), names("Various Artists"))
    }

    @Test
    fun onlySeparatorsIsNobody() {
        assertEquals(emptyList(), names(",,,"))
        assertEquals(emptyList(), names(" | | "))
        assertEquals(emptyList(), names(", & ,"))
        assertEquals(emptyList(), names("feat. , ft. ,"))
    }

    @Test
    fun theSameArtistTwiceIsOneSpelledAsFirstSeen() {
        val split = ArtistCredits.split("Zoë, ZOE, zoe feat. Brio")
        assertEquals(listOf("Zoë", "Brio"), split.map { it.name })
        assertEquals(listOf("zoe", "brio"), split.map { it.key })
    }

    @Test
    fun theKeyIsTheExistingFold() {
        for (name in listOf("A.R. Velan", "Émile", "Ana-Brio", "光の音")) {
            assertEquals(TextFold.fold(name), ArtistCredits.split(name).single().key)
        }
        assertEquals("", ArtistCredits.primaryKey(""))
        assertEquals("ana", ArtistCredits.primaryKey("Ana, Brio"))
    }

    @Test
    fun aLongListIsCut() {
        val credit = (1..50).joinToString(", ") { "Artist $it" }
        val split = ArtistCredits.split(credit)
        assertEquals(ArtistCredits.MAX_ARTISTS, split.size)
        assertEquals("Artist 1", split.first().name)
    }

    @Test
    fun aHugeNameIsCut() {
        val split = ArtistCredits.split("x".repeat(100_000) + ", Brio")
        assertTrue(split.all { it.name.length <= ArtistCredits.MAX_NAME })
        assertTrue(split.isNotEmpty())
        // A surrogate pair is never cut in half.
        val emoji = "🎵".repeat(300)
        val cut = ArtistCredits.split(emoji).single().name
        assertTrue(cut.length <= ArtistCredits.MAX_NAME)
        assertTrue(!cut.last().isHighSurrogate())
    }

    @Test
    fun controlCharactersAreSpaces() {
        assertEquals(listOf("Ana Brio"), names("Ana\tBrio\r\n"))
        assertEquals(listOf("Ana", "Brio"), names("\u0007Ana,\u001BBrio"))
    }

    @Test
    fun aClosingBracketThatBelongsToTheNameStays() {
        assertEquals(listOf("Ana (UK)"), names("Ana (UK)"))
        assertEquals(listOf("Ana (UK)", "Brio"), names("Ana (UK) (feat. Brio)"))
    }
}
