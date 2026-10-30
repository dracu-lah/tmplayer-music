package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleFallbackTest {

    private fun name(fileName: String, artist: String, title: String) {
        assertEquals(fileName, artist to title, TitleFallback.fromFileName(fileName))
    }

    @Test
    fun `artist and title are split on a spaced hyphen`() {
        name("Nadia Okafor - Glass Harbour.mp3", "Nadia Okafor", "Glass Harbour")
        name("The Pale Rivers - Lantern Song - Part Two.flac", "The Pale Rivers", "Lantern Song - Part Two")
    }

    @Test
    fun `a hyphen inside a word does not split`() {
        name("Self-Portrait in Blue.mp3", "", "Self-Portrait in Blue")
    }

    @Test
    fun `unicode dashes split too`() {
        name("Nadia Okafor \u2013 Glass Harbour.mp3", "Nadia Okafor", "Glass Harbour")
        name("Nadia Okafor \u2014 Glass Harbour.mp3", "Nadia Okafor", "Glass Harbour")
        name("Nadia Okafor\u2013Glass Harbour.mp3", "Nadia Okafor", "Glass Harbour")
        name("Nadia Okafor \u2212 Glass Harbour.mp3", "Nadia Okafor", "Glass Harbour")
    }

    @Test
    fun `leading track numbers go`() {
        name("01 - Nadia Okafor - Glass Harbour.flac", "Nadia Okafor", "Glass Harbour")
        name("01. Glass Harbour.mp3", "", "Glass Harbour")
        name("7 Glass Harbour.mp3", "", "Glass Harbour")
        name("1-02 Glass Harbour.mp3", "", "Glass Harbour")
        name("(03) Glass Harbour.mp3", "", "Glass Harbour")
        name("02 \u2013 Glass Harbour.mp3", "", "Glass Harbour")
    }

    @Test
    fun `a four digit number is kept as part of the title`() {
        name("1987 Summer.mp3", "", "1987 Summer")
    }

    @Test
    fun `underscores become spaces`() {
        name("03_Title_Name.mp3", "", "Title Name")
        name("Nadia_Okafor_-_Glass_Harbour.mp3", "Nadia Okafor", "Glass Harbour")
    }

    @Test
    fun `bracketed noise goes, other brackets stay`() {
        name("Title (Official Audio).mp3", "", "Title")
        name("Nadia Okafor - Glass Harbour [Official Music Video].mp3", "Nadia Okafor", "Glass Harbour")
        name("Glass Harbour (Lyrics).mp3", "", "Glass Harbour")
        name("Glass Harbour (Lyric Video).mp3", "", "Glass Harbour")
        name("Glass Harbour (Audio).mp3", "", "Glass Harbour")
        name("Glass Harbour [HQ].mp3", "", "Glass Harbour")
        name("Glass Harbour [HD].mp3", "", "Glass Harbour")
        name("Glass Harbour (320kbps).mp3", "", "Glass Harbour")
        name("Glass Harbour (320 kbps).mp3", "", "Glass Harbour")
        name("Glass Harbour (Free Download).mp3", "", "Glass Harbour")
        name("Glass Harbour (Visualizer).mp3", "", "Glass Harbour")
        name("Glass Harbour (Official Visualiser).mp3", "", "Glass Harbour")
        name("Glass Harbour (Live).mp3", "", "Glass Harbour (Live)")
        name("Glass Harbour (feat. Ilse Varga).mp3", "", "Glass Harbour (feat. Ilse Varga)")
    }

    @Test
    fun `a name that is only a number or noise falls back to the base name`() {
        name("01.mp3", "", "01")
        name("(Official Audio).mp3", "", "(Official Audio)")
        name("Nadia Okafor - .mp3", "", "Nadia Okafor")
        name("2024.flac", "", "2024")
        name(".flac", "", ".flac")
        name("", "", "")
    }

    @Test
    fun `non latin names are never rewritten`() {
        name("\u041D\u0430\u0434\u044F - \u0421\u0442\u0435\u043A\u043B\u043E.mp3", "\u041D\u0430\u0434\u044F", "\u0421\u0442\u0435\u043A\u043B\u043E")
        name("\u5C0F\u3055\u306A\u6E2F.flac", "", "\u5C0F\u3055\u306A\u6E2F")
        name("Caf\u00E9 du Port.mp3", "", "Caf\u00E9 du Port")
    }

    @Test
    fun `display prefers tags, then telegram, then the file name`() {
        assertEquals("Tag Artist" to "Tag Title", TitleFallback.display("Tag Title", "Tag Artist", "Tg Title", "Tg Artist", "A - B.mp3"))
        assertEquals("Tg Artist" to "Tg Title", TitleFallback.display("", " ", "Tg Title", "Tg Artist", "A - B.mp3"))
        assertEquals("Tg Artist" to "Tag Title", TitleFallback.display("Tag Title", null, null, "Tg Artist", "A - B.mp3"))
        assertEquals("Nadia Okafor" to "Glass Harbour", TitleFallback.display(null, null, null, null, "Nadia Okafor - Glass Harbour.mp3"))
        assertEquals("Tag Artist" to "Glass Harbour", TitleFallback.display(null, "Tag Artist", null, null, "Nadia Okafor - Glass Harbour.mp3"))
    }

    @Test
    fun `display takes the name's artist only when the name's title matches`() {
        assertEquals("Nadia Okafor" to "Glass Harbour", TitleFallback.display("Glass Harbour", null, null, null, "Nadia Okafor - Glass Harbour.mp3"))
        assertEquals("" to "Glass Harbour", TitleFallback.display("Glass Harbour", null, null, null, "Glass Harbour - Live at the Hall.mp3"))
    }

    @Test
    fun `display trims and never leaves the title blank`() {
        assertEquals("Nadia Okafor" to "Glass Harbour", TitleFallback.display("  Glass Harbour\u0000", "\tNadia Okafor ", null, null, null))
        assertEquals("" to "01", TitleFallback.display(null, null, null, null, "01.mp3"))
        assertEquals("" to ".flac", TitleFallback.display("", "", "", "", ".flac"))
        assertEquals("" to "", TitleFallback.display(null, null, null, null, null))
    }
}
