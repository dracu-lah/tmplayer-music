package com.tmplayer.music.platform

import com.tmplayer.music.player.ArtworkBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogReportTest {

    private val facts = LogReport.Facts("TMPlayer Music", "0.1.0 (1), debug", "16 (API 36)", "Xiaomi POCO", 2)

    @Test
    fun `the header holds only the version, android, device and counts`() {
        val text = LogReport.build(facts, listOf("one", "two"))
        val header = text.substringBefore("Log:")
        assertTrue(header.contains("App version: 0.1.0 (1), debug"))
        assertTrue(header.contains("Android: 16 (API 36)"))
        assertTrue(header.contains("Device: Xiaomi POCO"))
        assertTrue(header.contains("Background failures contained in this run: 2"))
        assertTrue(header.contains("Log lines: 2 of 2"))
        assertTrue(text.trimEnd().endsWith("one\ntwo"))
    }

    @Test
    fun `every line and the crash are redacted`() {
        val crash = CrashJournal.Crash(1, "--- crash\nscanning My Secret Chat -1001234567890 failed\n--- recent log\n")
        val text = LogReport.build(
            facts,
            listOf("open /x/My Song.mp3 for +15551234567", "chat My Secret Chat"),
            crash,
            known = listOf("My Secret Chat"),
        )
        assertFalse(text.contains("My Secret Chat"))
        assertFalse(text.contains("1234567890"))
        assertFalse(text.contains("My Song"))
        assertFalse(text.contains("5551234567"))
        assertTrue(text.contains("Last crash"))
    }

    @Test
    fun `a huge log is cut to about 1 MB, newest lines kept`() {
        val lines = List(100_000) { "line $it ".padEnd(100, 'x') }
        val text = LogReport.build(facts, lines)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes", bytes <= LogReport.MAX_BYTES)
        assertTrue(bytes > LogReport.MAX_BYTES - 2_000)
        assertTrue(text.trimEnd().endsWith(lines.last()))
        assertFalse(text.contains("line 0 "))
    }

    @Test
    fun `a huge crash cannot push every log line out`() {
        val crash = CrashJournal.Crash(1, "x".repeat(1_000) + "\n" + List(5_000) { "frame $it" }.joinToString("\n"))
        val text = LogReport.build(facts, listOf("the last line"), crash, maxBytes = 200_000)
        assertTrue(text.toByteArray().size <= 200_000)
        assertTrue(text.trimEnd().endsWith("the last line"))
        assertTrue(text.contains("frame 4999"))
    }

    @Test
    fun `newest that fit counts multibyte text in bytes`() {
        val lines = listOf("ab", "éé", "🎵")
        assertEquals(listOf("🎵"), LogReport.newestThatFit(lines, 5))
        assertEquals(listOf("éé", "🎵"), LogReport.newestThatFit(lines, 10))
        assertEquals(emptyList<String>(), LogReport.newestThatFit(lines, 0))
    }

    @Test
    fun `the file name keeps only safe characters`() {
        assertEquals("tmplayer-music-logs-20261030-140501.txt", LogReport.fileName("20261030-140501"))
        assertEquals("tmplayer-music-logs-a.txt", LogReport.fileName("../a/"))
    }

    @Test
    fun `about formats the version with code and build type`() {
        assertEquals("0.1.0 (1), release", About.version("0.1.0", 1, "release"))
        assertEquals("0.2.0-beta.1 (12), debug", About.version(" 0.2.0-beta.1 ", 12, "Debug"))
        assertEquals("? (3)", About.version("  ", 3, ""))
        assertEquals("1.0, release", About.version("1.0", -1, "release"))
        assertEquals("a b (1), x", About.version("a\n b", 1, "x\n"))
        assertTrue(About.FEEDBACK_URL.startsWith("https://github.com/") && About.FEEDBACK_URL.endsWith("/issues"))
    }

    @Test
    fun `artwork bounds`() {
        assertEquals(1, ArtworkBounds.sampleSize(500, 500))
        assertEquals(2, ArtworkBounds.sampleSize(1_200, 1_200))
        assertEquals(8, ArtworkBounds.sampleSize(5_000, 4_100))
        assertEquals(1, ArtworkBounds.sampleSize(0, -1))
        assertEquals(512 to 256, ArtworkBounds.fit(2_000, 1_000))
        assertEquals(256 to 512, ArtworkBounds.fit(1_000, 2_000))
        assertEquals(300 to 200, ArtworkBounds.fit(300, 200))
        assertEquals(512 to 1, ArtworkBounds.fit(100_000, 1))
        assertEquals(0 to 0, ArtworkBounds.fit(0, 10))
        assertFalse(ArtworkBounds.fits(null))
        assertFalse(ArtworkBounds.fits(ByteArray(0)))
        assertTrue(ArtworkBounds.fits(ByteArray(ArtworkBounds.MAX_BYTES)))
        assertFalse(ArtworkBounds.fits(ByteArray(ArtworkBounds.MAX_BYTES + 1)))
    }
}
