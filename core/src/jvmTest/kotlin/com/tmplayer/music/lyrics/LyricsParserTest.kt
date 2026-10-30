package com.tmplayer.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    @Test
    fun `timed lines parse in order with every stamp form`() {
        val lyrics = LyricsParser.parse("[00:12.50]Second\n[00:01]First\n[01:02.345]Third\n[00:30:20]Colon form")!!
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_000L, 12_500L, 30_200L, 62_345L), lyrics.lines.map { it.timeMs })
        assertEquals("First", lyrics.lines[0].text)
    }

    @Test
    fun `one line with several stamps repeats`() {
        val lyrics = LyricsParser.parse("[00:10.00][00:40.00]Chorus line")!!
        assertEquals(listOf(10_000L, 40_000L), lyrics.lines.map { it.timeMs })
        assertTrue(lyrics.lines.all { it.text == "Chorus line" })
    }

    @Test
    fun `headers are dropped and offset moves every line earlier`() {
        val lyrics = LyricsParser.parse("[ar:Nadia Okafor]\n[ti:Morning Tide]\n[offset:+500]\n[00:10.00]Line")!!
        assertEquals(1, lyrics.lines.size)
        assertEquals(9_500L, lyrics.lines[0].timeMs)
    }

    @Test
    fun `a negative offset moves lines later and never below zero`() {
        assertEquals(10_250L, LyricsParser.parse("[offset:-250]\n[00:10.00]A")!!.lines[0].timeMs)
        assertEquals(0L, LyricsParser.parse("[offset:+90000]\n[00:10.00]A")!!.lines[0].timeMs)
    }

    @Test
    fun `enhanced word stamps are taken out of the text`() {
        val lyrics = LyricsParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")!!
        assertEquals("Hello world", lyrics.lines[0].text)
    }

    @Test
    fun `text with no stamps is plain lyrics`() {
        val lyrics = LyricsParser.parse("First verse\nSecond line\n\nChorus")!!
        assertFalse(lyrics.synced)
        assertEquals("First verse\nSecond line\n\nChorus", lyrics.plain)
    }

    @Test
    fun `square brackets that are not tags stay in plain text`() {
        val lyrics = LyricsParser.parse("[Chorus]\nSing it")!!
        assertFalse(lyrics.synced)
        assertTrue(lyrics.plain.startsWith("[Chorus]"))
    }

    @Test
    fun `right to left and non latin text is kept as is`() {
        val arabic = "مرحبا"
        val japanese = "こんにちは"
        val lyrics = LyricsParser.parse("[00:01.00]$arabic\n[00:02.00]$japanese")!!
        assertEquals(listOf(arabic, japanese), lyrics.lines.map { it.text })
    }

    @Test
    fun `utf8 with a bom and utf16 bytes decode`() {
        val body = "[00:01.00]Café"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(Charsets.UTF_8)
        assertEquals("Café", LyricsParser.parse(bom)!!.lines[0].text)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        assertEquals("Café", LyricsParser.parse(utf16)!!.lines[0].text)
    }

    @Test
    fun `empty, blank and header only files are no lyrics`() {
        assertNull(LyricsParser.parse(""))
        assertNull(LyricsParser.parse("   \n  "))
        assertNull(LyricsParser.parse("[ar:Someone]\n[ti:Something]"))
        assertNull(LyricsParser.parse(ByteArray(0)))
    }

    @Test
    fun `lineAt finds the playing line with a latency allowance`() {
        val lyrics = LyricsParser.parse("[00:10.00]A\n[00:20.00]B\n[00:30.00]C")!!
        assertEquals(-1, lyrics.lineAt(5_000))
        assertEquals(0, lyrics.lineAt(10_000))
        assertEquals(1, lyrics.lineAt(29_999))
        assertEquals(2, lyrics.lineAt(29_800, latencyMs = 300))
        assertEquals(2, lyrics.lineAt(99_000))
    }

    @Test
    fun `seconds past 59 are not a stamp`() {
        assertFalse(LyricsParser.parse("[00:75.00]Not a stamp")!!.synced)
    }
}
