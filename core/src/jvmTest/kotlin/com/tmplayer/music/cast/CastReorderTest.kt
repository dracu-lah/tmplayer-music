package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastReorderTest {
    private fun ids(count: Int) = (0 until count).map { "t$it" }

    @Test
    fun `the same input always gives the same shuffle`() {
        val queue = ids(40)
        val seed = CastReorder.seedOf(queue)
        assertEquals(CastReorder.shuffled(queue, seed), CastReorder.shuffled(queue, seed))
        assertEquals(seed, CastReorder.seedOf(ids(40)))
    }

    @Test
    fun `a shuffle keeps every track once and really moves them`() {
        val queue = ids(40)
        val out = CastReorder.shuffled(queue, CastReorder.seedOf(queue))
        assertEquals(queue.toSet(), out.toSet())
        assertEquals(queue.size, out.size)
        assertNotEquals(queue, out)
    }

    @Test
    fun `different seeds give different orders`() {
        val queue = ids(30)
        assertNotEquals(CastReorder.shuffled(queue, 1), CastReorder.shuffled(queue, 2))
    }

    @Test
    fun `empty and single queues shuffle to themselves`() {
        assertEquals(emptyList<String>(), CastReorder.shuffled(emptyList(), 7))
        assertEquals(listOf("a"), CastReorder.shuffled(listOf("a"), 7))
    }

    @Test
    fun `shuffle on puts the playing track first and keeps the rest`() {
        val queue = ids(12)
        val order = CastReorder.order(queue, "t5", shuffle = true, original = queue, seed = CastReorder.seedOf(queue))!!
        assertEquals("t5", order.first())
        assertEquals(queue.toSet(), order.toSet())
        assertEquals(queue.size, order.size)
    }

    @Test
    fun `shuffle order does not depend on how the receiver's queue is arranged now`() {
        val queue = ids(12)
        val seed = CastReorder.seedOf(queue)
        val a = CastReorder.order(queue, "t3", true, queue, seed)
        val b = CastReorder.order(queue.reversed(), "t3", true, queue, seed)
        assertEquals(a, b)
    }

    @Test
    fun `shuffle off restores the queue's own order and keeps what was added`() {
        val original = ids(6)
        val now = listOf("t4", "new", "t1", "t0", "t5", "t2", "t3")
        val order = CastReorder.order(now, "t1", shuffle = false, original = original, seed = 9)!!
        assertEquals(listOf("t0", "t1", "t2", "t3", "t4", "t5", "new"), order)
    }

    @Test
    fun `shuffle off leaves out what the user removed`() {
        val original = ids(5)
        val order = CastReorder.order(listOf("t3", "t0", "t4"), "t0", false, original, 1)!!
        assertEquals(listOf("t0", "t3", "t4"), order)
    }

    @Test
    fun `no order when the playing track is missing or an id repeats`() {
        assertNull(CastReorder.order(ids(3), "zzz", true, ids(3), 1))
        assertNull(CastReorder.order(listOf("a", "b", "a"), "a", true, listOf("a", "b"), 1))
    }

    @Test
    fun `layout cuts a window around the playing track and leaves the rest upcoming`() {
        val order = ids(100)
        val layout = CastReorder.layout(order, "t50")!!
        assertEquals(CastQueue.WINDOW, layout.window.size)
        assertEquals("t50", layout.window[layout.startIndex])
        assertEquals(CastQueue.BEFORE, layout.startIndex)
        assertEquals(order.drop(47 + CastQueue.WINDOW), layout.upcoming)
    }

    @Test
    fun `layout of a short queue is whole and a first track has nothing before it`() {
        val layout = CastReorder.layout(ids(5), "t0")!!
        assertEquals(ids(5), layout.window)
        assertEquals(0, layout.startIndex)
        assertTrue(layout.upcoming.isEmpty())
        assertNull(CastReorder.layout(ids(5), "x"))
    }

    @Test
    fun `sent after a reorder drops what left the receiver but keeps what the user removed`() {
        val all = ids(10)
        // t99 was sent and then removed by the user: it is not in the queue any more.
        val sent = setOf("t0", "t1", "t2", "t3", "t99")
        val window = listOf("t5", "t6", "t7")
        val after = CastReorder.sentAfter(sent, all, window)
        assertEquals(setOf("t99", "t5", "t6", "t7"), after)
        // Folded back at the end, the unsent tracks of the queue return and t99 does not.
        val merged = CastQueue.mergeBack(all + "t99", after, window)
        assertEquals(all.toSet(), merged.toSet())
    }

    @Test
    fun `strange ids do not break the ordering`() {
        val weird = listOf("", " ", "\u0000", "a".repeat(10_000), "🎵")
        val out = CastReorder.order(weird, "", true, weird, CastReorder.seedOf(weird))!!
        assertEquals(weird.toSet(), out.toSet())
        assertEquals("", out.first())
    }
}
