package com.tmplayer.music.data

import com.tmplayer.music.library.TagCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStatusTest {
    private val idleScan = ScanStatus()
    private val reading = EnrichStatus(running = true, counts = TagCounts(pending = 750, read = 240, failed = 10))

    @Test
    fun `nothing running and nothing pending is up to date, with no banner`() {
        val v = LibraryStatus.view(idleScan, EnrichStatus(counts = TagCounts(read = 5)), paused = false, online = true)
        assertEquals(LibraryPhase.UpToDate, v.phase)
        assertFalse(v.showBanner)
    }

    @Test
    fun `reading tags says n of m and that listening goes on`() {
        val v = LibraryStatus.view(idleScan, reading, paused = false, online = true)
        assertEquals(LibraryPhase.Reading, v.phase)
        assertEquals("Reading track details: 250 of 1,000", v.line)
        assertEquals("You can keep listening while this runs.", v.sub)
        assertEquals(0.25f, v.progress)
    }

    @Test
    fun `the scan comes before the tags`() {
        val scan = ScanStatus(running = true, chatsTotal = 4, chatsDone = 1, found = 1)
        val v = LibraryStatus.view(scan, reading, paused = false, online = true)
        assertEquals(LibraryPhase.Scanning, v.phase)
        assertEquals("Scanning 2 of 4 chats, 1 track so far", v.line)
        assertEquals(0.25f, v.progress)
    }

    @Test
    fun `pause wins, also with work left but nothing running`() {
        val v = LibraryStatus.view(idleScan, EnrichStatus(counts = TagCounts(pending = 3, read = 1)), paused = true, online = true)
        assertEquals(LibraryPhase.Paused, v.phase)
        assertEquals("Paused, track details read for 1 of 4", v.line)
        assertTrue(v.showBanner)
    }

    @Test
    fun `pause with everything done is up to date`() {
        val v = LibraryStatus.view(idleScan, EnrichStatus(counts = TagCounts(read = 4)), paused = true, online = true)
        assertEquals(LibraryPhase.UpToDate, v.phase)
    }

    @Test
    fun `offline while busy waits for Telegram`() {
        val v = LibraryStatus.view(idleScan, reading, paused = false, online = false)
        assertEquals(LibraryPhase.Waiting, v.phase)
        assertEquals("Waiting for Telegram to connect", v.line)
        val backingOff = LibraryStatus.view(idleScan, reading.copy(waiting = true), paused = false, online = true)
        assertEquals(LibraryPhase.Waiting, backingOff.phase)
    }

    @Test
    fun `a flood wait names the seconds`() {
        val v = LibraryStatus.view(ScanStatus(running = true, chatsTotal = 1, waitingSeconds = 30), reading, paused = false, online = true)
        assertEquals(LibraryPhase.Waiting, v.phase)
        assertEquals("Telegram asked us to wait 30 s before reading more", v.line)
        assertNull(v.progress)
    }

    @Test
    fun `an empty library reads zero of zero without dividing by it`() {
        val v = LibraryStatus.view(idleScan, EnrichStatus(running = true), paused = false, online = true)
        assertEquals("Reading track details: 0 of 0", v.line)
        assertNull(v.progress)
    }
}
