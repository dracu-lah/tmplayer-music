package com.tmplayer.music.data

import com.tmplayer.music.data.LibraryStatus.Banner
import com.tmplayer.music.library.TagCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The banner's Hide: progress goes, what needs the user stays, and the choice outlives a scan and a restart. */
class ScanBannerTest {
    private val reading = EnrichStatus(running = true, counts = TagCounts(pending = 750, read = 250))
    private val idle = EnrichStatus(counts = TagCounts(read = 5))

    private fun view(scan: ScanStatus = ScanStatus(), tags: EnrichStatus = reading) =
        LibraryStatus.view(scan, tags, paused = false, online = true)

    @Test
    fun `shown by default, the progress shows while reading`() {
        assertEquals(Banner.Progress, LibraryStatus.banner(view(), showProgress = true))
    }

    @Test
    fun `hidden, reading and scanning show nothing`() {
        assertEquals(Banner.None, LibraryStatus.banner(view(), showProgress = false))
        val scan = ScanStatus(running = true, chatsTotal = 3, chatsDone = 1)
        assertEquals(Banner.None, LibraryStatus.banner(view(scan), showProgress = false))
    }

    @Test
    fun `a chat that could not be read shows even when hidden, once the scan ended`() {
        val failed = ScanStatus(failed = mapOf(7L to "No access"))
        val v = view(failed, idle)
        assertEquals(1, v.failedChats)
        assertEquals(Banner.Attention, LibraryStatus.banner(v, showProgress = false))
        assertEquals(Banner.Attention, LibraryStatus.banner(v, showProgress = true))
    }

    @Test
    fun `while a scan runs the failures wait and the progress wins when shown`() {
        val running = ScanStatus(running = true, chatsTotal = 2, failed = mapOf(7L to "No access"))
        val v = view(running)
        assertEquals(0, v.failedChats)
        assertEquals(Banner.Progress, LibraryStatus.banner(v, showProgress = true))
        assertEquals(Banner.None, LibraryStatus.banner(v, showProgress = false))
    }

    @Test
    fun `nothing to say and no view yet show nothing`() {
        assertEquals(Banner.None, LibraryStatus.banner(view(tags = idle), showProgress = true))
        assertEquals(Banner.None, LibraryStatus.banner(null, showProgress = true))
    }

    @Test
    fun `the choice is on for a new install and survives a restart`() {
        val store = MemoryKeyValues()
        val first = LibraryChoices(store)
        assertTrue(first.showProgress.value)
        first.setShowProgress(false)
        assertFalse(LibraryChoices(store).showProgress.value)
        LibraryChoices(store).setShowProgress(true)
        assertTrue(LibraryChoices(store).showProgress.value)
    }
}
