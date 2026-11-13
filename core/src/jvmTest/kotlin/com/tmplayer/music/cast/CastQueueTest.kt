package com.tmplayer.music.cast

import com.tmplayer.music.library.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastQueueTest {
    private fun entry(id: String, format: AudioFormat = AudioFormat.MP3, title: String = id) = CastEntry(id, title, format, "", "")

    private fun mp3s(count: Int) = (0 until count).map { entry("t$it") }

    @Test
    fun `a short queue is sent whole and starts at the current track`() {
        val plan = CastQueue.plan(mp3s(5), current = 2)
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), plan.window.map { it.entry.id })
        assertEquals(2, plan.startIndex)
        assertTrue(plan.upcoming.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertEquals("audio/mpeg", plan.window[0].mime)
    }

    @Test
    fun `a long queue is windowed around the current track and the rest waits`() {
        val plan = CastQueue.plan(mp3s(100), current = 50)
        assertEquals(CastQueue.WINDOW, plan.window.size)
        assertEquals("t47", plan.window.first().entry.id)
        assertEquals(CastQueue.BEFORE, plan.startIndex)
        assertEquals("t50", plan.window[plan.startIndex].entry.id)
        assertEquals("t67", plan.upcoming.first().entry.id)
        assertEquals(100 - 67, plan.upcoming.size)
    }

    @Test
    fun `unplayable tracks are skipped with their reason and the start moves on`() {
        val queue = listOf(entry("a"), entry("b", AudioFormat.ALAC), entry("c", AudioFormat.AIFF), entry("d"))
        val plan = CastQueue.plan(queue, current = 1)
        assertEquals(listOf("a", "d"), plan.window.map { it.entry.id })
        assertEquals("d", plan.window[plan.startIndex].entry.id)
        assertEquals(listOf(CastRefusal.Alac, CastRefusal.Aiff), plan.skipped.map { it.reason })
        assertEquals(listOf(1, 2), plan.skipped.map { it.sourceIndex })
    }

    @Test
    fun `a current track with nothing playable after it starts the nearest one before`() {
        val plan = CastQueue.plan(listOf(entry("a"), entry("b"), entry("c", AudioFormat.ALAC)), current = 2)
        assertEquals("b", plan.window[plan.startIndex].entry.id)
    }

    @Test
    fun `nothing playable is an empty plan with every skip`() {
        val plan = CastQueue.plan(listOf(entry("a", AudioFormat.ALAC), entry("b", AudioFormat.AIFF)), current = 0)
        assertTrue(plan.window.isEmpty())
        assertEquals(-1, plan.startIndex)
        assertEquals(2, plan.skipped.size)
    }

    @Test
    fun `an empty queue and an out of range current index are safe`() {
        assertEquals(-1, CastQueue.plan(emptyList(), 0).startIndex)
        assertEquals(1, CastQueue.plan(mp3s(2), 99).startIndex)
        assertEquals(0, CastQueue.plan(mp3s(2), -5).startIndex)
    }

    @Test
    fun `a huge queue is planned without copying it into the window`() {
        val plan = CastQueue.plan(mp3s(20_000), current = 10_000)
        assertEquals(CastQueue.WINDOW, plan.window.size)
        assertEquals(20_000 - 9_997 - CastQueue.WINDOW, plan.upcoming.size)
    }

    @Test
    fun `an item added during a cast is judged by its format`() {
        assertTrue(CastQueue.verdict(entry("x")) is CastVerdict.Playable)
        assertEquals(CastVerdict.Refused(CastRefusal.Alac), CastQueue.verdict(entry("x", AudioFormat.ALAC)))
    }

    @Test
    fun `what was never sent comes back after its neighbour`() {
        val original = listOf("a", "b", "c", "d", "e")
        // b and d were skipped; the cast queue ended as a, c, e.
        assertEquals(listOf("a", "b", "c", "d", "e"), CastQueue.mergeBack(original, setOf("a", "c", "e"), listOf("a", "c", "e")))
    }

    @Test
    fun `something removed in the cast stays removed and something added stays added`() {
        val original = listOf("a", "b", "c")
        // c was sent and removed, z was added during the cast.
        assertEquals(listOf("a", "b", "z"), CastQueue.mergeBack(original, setOf("a", "b", "c"), listOf("a", "b", "z")))
    }

    @Test
    fun `an unsent item whose neighbours are gone goes to the front or after the nearest one left`() {
        // a was never sent, nothing before it: it goes first.
        assertEquals(listOf("a", "c"), CastQueue.mergeBack(listOf("a", "b", "c"), setOf("b", "c"), listOf("c")))
        // the tail waiting to be sent follows the last item that was.
        assertEquals(listOf("a", "b", "c", "d"), CastQueue.mergeBack(listOf("a", "b", "c", "d"), setOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun `several unsent items in a row keep their order`() {
        assertEquals(listOf("a", "b", "c", "d"), CastQueue.mergeBack(listOf("a", "b", "c", "d"), setOf("a", "d"), listOf("a", "d")))
    }

    @Test
    fun `the summary names one track and counts several`() {
        val one = CastQueue.summary(CastQueue.plan(listOf(entry("a"), entry("b", AudioFormat.ALAC, "Slow Song")), 0).skipped)
        assertTrue(one!!.contains("Slow Song"))
        assertTrue(one.contains("Apple Lossless"))
        val many = CastQueue.summary(CastQueue.plan(listOf(entry("a", AudioFormat.ALAC), entry("b", AudioFormat.AIFF), entry("c", AudioFormat.ALAC)), 0).skipped)
        assertTrue(many!!.contains("3 tracks"))
        assertTrue(many.contains("AIFF"))
        assertNull(CastQueue.summary(emptyList()))
    }
}
