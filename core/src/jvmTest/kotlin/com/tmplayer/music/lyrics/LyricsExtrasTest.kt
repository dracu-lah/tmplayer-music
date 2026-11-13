package com.tmplayer.music.lyrics

import com.tmplayer.music.data.MemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsExtrasTest {

    @Test
    fun `enhanced lrc gives words with their times`() {
        val lyrics = LyricsParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world<00:02.40>")!!
        val line = lyrics.lines.single()
        assertTrue(lyrics.hasWords)
        assertEquals("Hello big world", line.text)
        assertEquals(listOf("Hello ", "big ", "world"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L, 2_000L), line.words.map { it.timeMs })
        assertEquals(2_400L, line.endMs)
    }

    @Test
    fun `text before the first word stamp starts with the line`() {
        val line = LyricsParser.parse("[00:05.00]Oh <00:05.40>yeah")!!.lines.single()
        assertEquals(listOf(5_000L, 5_400L), line.words.map { it.timeMs })
        assertEquals("Oh yeah", line.text)
    }

    @Test
    fun `lines without word stamps stay line timed beside word timed ones`() {
        val lyrics = LyricsParser.parse("[00:01.00]Plain line\n[00:05.00]<00:05.00>Timed <00:05.50>line")!!
        assertFalse(lyrics.lines[0].hasWords)
        assertEquals("Plain line", lyrics.lines[0].text)
        assertTrue(lyrics.lines[1].hasWords)
    }

    @Test
    fun `plain lrc has no words`() {
        val lyrics = LyricsParser.parse("[00:01.00]One\n[00:02.00]Two")!!
        assertFalse(lyrics.hasWords)
    }

    @Test
    fun `word times going backwards fall back to the line`() {
        val line = LyricsParser.parse("[00:01.00]<00:03.00>a <00:02.00>b")!!.lines.single()
        assertFalse(line.hasWords)
        assertEquals("a b", line.text)
    }

    @Test
    fun `an offset header moves the words with the line`() {
        val line = LyricsParser.parse("[offset:+500]\n[00:02.00]<00:02.00>a <00:03.00>b<00:04.00>")!!.lines.single()
        assertEquals(1_500L, line.timeMs)
        assertEquals(listOf(1_500L, 2_500L), line.words.map { it.timeMs })
        assertEquals(3_500L, line.endMs)
    }

    @Test
    fun `a repeated line moves its words with each stamp`() {
        val lines = LyricsParser.parse("[00:01.00][00:11.00]<00:01.00>a <00:02.00>b")!!.lines
        assertEquals(listOf(1_000L, 2_000L), lines[0].words.map { it.timeMs })
        assertEquals(listOf(11_000L, 12_000L), lines[1].words.map { it.timeMs })
    }

    @Test
    fun `too many words fall back to the line`() {
        val body = (0 until 500).joinToString("") { "<00:01.00>w$it " }
        val line = LyricsParser.parse("[00:01.00]$body")!!.lines.single()
        assertFalse(line.hasWords)
        assertTrue(line.text.startsWith("w0 w1"))
    }

    @Test
    fun `wordAt and wordFraction follow the clock`() {
        val line = LyricsParser.parse("[00:01.00]<00:01.00>a <00:02.00>b <00:03.00>c<00:04.00>")!!.lines.single()
        assertEquals(-1, line.wordAt(500))
        assertEquals(0, line.wordAt(1_000))
        assertEquals(1, line.wordAt(2_999))
        assertEquals(2, line.wordAt(60_000))
        assertEquals(0.5f, line.wordFraction(0, 1_500, 9_000), 0.001f)
        assertEquals(0.5f, line.wordFraction(2, 3_500, 9_000), 0.001f)
        assertEquals(1f, line.wordFraction(2, 9_000, 9_000), 0.001f)
        assertEquals(0f, line.wordFraction(7, 1_500, 9_000), 0.001f)
    }

    @Test
    fun `the last word of a line with no end stamp ends at the next line but not later than a moment`() {
        val line = LyricsParser.parse("[00:01.00]<00:01.00>a <00:02.00>b")!!.lines.single()
        assertEquals(0.5f, line.wordFraction(1, 2_500, nextLineMs = 3_000), 0.001f)
        assertEquals(1f, line.wordFraction(1, 2_000 + LyricLine.MAX_LAST_WORD_MS, nextLineMs = 60_000), 0.001f)
    }

    @Test
    fun `online lyrics with word stamps keep them`() {
        val lyrics = GatewayAnswer.lyricsOf("[00:01.00]<00:01.00>a <00:02.00>b", null)!!
        assertTrue(lyrics.hasWords)
    }

    @Test
    fun `offset steps are a tenth of a second and held within thirty seconds`() {
        assertEquals(100L, LyricsOffsets.step(0, 1))
        assertEquals(-300L, LyricsOffsets.step(-200, -1))
        assertEquals(30_000L, LyricsOffsets.step(29_950, 1))
        assertEquals(-30_000L, LyricsOffsets.step(Long.MIN_VALUE, -1))
    }

    @Test
    fun `offsets are remembered per track and survive a restart`() {
        val store = MemoryKeyValues()
        val offsets = LyricsOffsets(store)
        offsets.set("track-a", 300)
        offsets.set("track-b", -200)
        assertEquals(300L, LyricsOffsets(store).get("track-a"))
        assertEquals(-200L, LyricsOffsets(store).get("track-b"))
        assertEquals(0L, LyricsOffsets(store).get("track-c"))
        offsets.set("track-a", 0)
        assertEquals(0L, LyricsOffsets(store).get("track-a"))
        offsets.clear()
        assertEquals(0L, LyricsOffsets(store).get("track-b"))
    }

    @Test
    fun `offsets stay bounded and drop the oldest`() {
        val offsets = LyricsOffsets(MemoryKeyValues())
        for (i in 0 until LyricsOffsets.MAX_ENTRIES + 20) offsets.set("t$i", 100)
        assertEquals(0L, offsets.get("t0"))
        assertEquals(100L, offsets.get("t${LyricsOffsets.MAX_ENTRIES + 19}"))
    }

    @Test
    fun `a key with a line break is not stored and a broken line is skipped on read`() {
        val offsets = LyricsOffsets(MemoryKeyValues())
        offsets.set("a\nb", 100)
        assertEquals(0L, offsets.get("a\nb"))
        assertEquals(mapOf("ok" to 200L), LyricsOffsets.decode("junk\nx\tno\n200\tok\n0\tzero"))
    }

    @Test
    fun `sharing a line adds title and artist as one footer`() {
        assertEquals("Hello\n\nSong, Band", LyricsShare.line(" Hello ", "Song", "Band"))
        assertEquals("Hello\n\nSong", LyricsShare.line("Hello", "Song", null))
        assertEquals("Hello", LyricsShare.line("Hello", "", " "))
        assertNull(LyricsShare.line("   ", "Song", "Band"))
        assertNull(LyricsShare.line("♪", "Song", "Band"))
    }

    @Test
    fun `sharing all lyrics keeps stanza breaks and drops music only gaps`() {
        val lyrics = LyricsParser.parse("[00:01.00]One\n[00:02.00]Two\n[00:03.00]\n[00:04.00]\n[00:05.00]Three")!!
        assertEquals("One\nTwo\n\nThree\n\nSong, Band", LyricsShare.all(lyrics, "Song", "Band"))
        val plain = Lyrics(emptyList(), "First\n\n\nSecond")
        assertEquals("First\n\nSecond\n\nSong", LyricsShare.all(plain, "Song", null))
    }

    @Test
    fun `sharing very long lyrics is cut at a line`() {
        val text = Lyrics(emptyList(), "a line of words\n".repeat(20_000))
        val shared = LyricsShare.all(text, "t", "a")!!
        assertTrue(shared.length < LyricsShare.MAX_CHARS + 100)
        assertTrue(shared.contains("a line of words\n\nt, a"))
    }
}
