package com.tmplayer.music.library

import com.tmplayer.music.tags.TagBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Titles that look like file names get spaces back; placeholder artists are nobody (2026-10-30). */
class NameTidyTest {

    @Test
    fun `underscored names get their spaces back`() {
        assertEquals("Some Movie Video Songs - Title Words", TitleFallback.tidyTitle("#Some_Movie_Video_Songs_-_Title_Words"))
        assertEquals("Title Words", TitleFallback.tidyTitle("Title__Words_"))
        // A stray underscore in a spaced title is kept.
        assertEquals("Mix of a b_c and more", TitleFallback.tidyTitle("Mix of a b_c and more"))
    }

    @Test
    fun `spaces collapse and a hashtag mark goes`() {
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("  Glass    Harbour "))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("##Glass Harbour"))
        assertEquals("#1 Crush", TitleFallback.tidyTitle("#1 Crush"))
        assertEquals("#", TitleFallback.tidyTitle("#"))
    }

    @Test
    fun `only a clear track number goes`() {
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("01 Glass Harbour"))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("01. Glass Harbour"))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("1. Glass Harbour"))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("(03) Glass Harbour"))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("12 - Glass Harbour"))
        assertEquals("Glass Harbour", TitleFallback.tidyTitle("07 \u2013 Glass Harbour"))
        // Numbers that are part of a name stay.
        assertEquals("7 Rings", TitleFallback.tidyTitle("7 Rings"))
        assertEquals("99 Lanterns", TitleFallback.tidyTitle("99 Lanterns"))
        assertEquals("1999", TitleFallback.tidyTitle("1999"))
        assertEquals("1.5 Degrees", TitleFallback.tidyTitle("1.5 Degrees"))
        assertEquals("01", TitleFallback.tidyTitle("01"))
        assertEquals("01 - 02", TitleFallback.tidyTitle("01 - 02"))
    }

    @Test
    fun `display tidies titles from every source`() {
        assertEquals("" to "Some Movie Title", TitleFallback.display("Some_Movie_Title", "", "", "", "x.mp3"))
        assertEquals("" to "Song Words", TitleFallback.display("", "", "#Song_Words", "", ""))
        assertEquals(
            "Some Movie Video Songs" to "Title Words",
            TitleFallback.display("", "", "", "", "#Some_Movie_Video_Songs_-_Title_Words.mp3"),
        )
    }

    @Test
    fun `placeholder artists are nobody`() {
        for (artist in listOf("<unknown>", "unknown", "Unknown Artist", "UNKNOWN", " <Unknown> ", "", "   ")) {
            assertEquals(artist, "", TitleFallback.tidyArtist(artist))
            assertEquals(artist, true, TitleFallback.isPlaceholderArtist(artist))
        }
        assertEquals("Nadia Okafor", TitleFallback.tidyArtist("Nadia_Okafor"))
        assertEquals("50 Lanterns", TitleFallback.tidyArtist("50 Lanterns"))
        assertEquals("Unknown Pleasures Band", TitleFallback.tidyArtist("Unknown Pleasures Band"))
    }

    @Test
    fun `the tag reader skips placeholder artists for the next tag`() {
        val b = TagBuilder()
        b.artist("<unknown>")
        b.albumArtist("Unknown Artist")
        assertEquals("", b.build().artist)
        assertEquals("", b.build().albumArtist)
        // ID3v1 read after a placeholder ID3v2 still names the artist.
        b.artist("Nadia Okafor")
        assertEquals("Nadia Okafor", b.build().artist)
    }
}
