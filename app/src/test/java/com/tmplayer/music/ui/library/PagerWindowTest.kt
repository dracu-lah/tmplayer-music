package com.tmplayer.music.ui.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Songs list's window: a jump reads one page, scrolling both ways keeps the order, memory stays bounded. */
class PagerWindowTest {

    @Test
    fun `window arithmetic`() {
        assertEquals(0, PagerWindow.dropFromStart(PagerWindow.MAX_WINDOW, canReadBack = true))
        assertEquals(200, PagerWindow.dropFromStart(PagerWindow.MAX_WINDOW + 200, canReadBack = true))
        assertEquals(0, PagerWindow.dropFromStart(PagerWindow.MAX_WINDOW + 200, canReadBack = false))
        assertEquals(200, PagerWindow.dropFromEnd(PagerWindow.MAX_WINDOW + 200))
        assertEquals(0, PagerWindow.windowStart(10, canReadBack = true))
        assertEquals(90_000 - PagerWindow.PAGE / 4, PagerWindow.windowStart(90_000, canReadBack = true))
        assertEquals(0, PagerWindow.windowStart(90_000, canReadBack = false))
    }

    /** A sorted list of [size] numbers read like the index: keyset after, offset, keyset before. */
    private class Source(val size: Int) {
        var rowsRead = 0
        fun after(after: Int?, limit: Int): List<Int> {
            val from = (after ?: -1) + 1
            return (from until minOf(size, from + limit)).toList().also { rowsRead += it.size }
        }
        fun at(offset: Int, limit: Int): List<Int> = (offset until minOf(size, offset + limit)).toList().also { rowsRead += it.size }
        fun before(before: Int, limit: Int): List<Int> =
            (maxOf(0, before - limit) until before).toList().also { rowsRead += it.size }
    }

    private fun pager(source: Source) = Pager<Int, Int>(
        load = { after, limit -> source.after(after, limit) },
        cursorOf = { it },
        loadAt = { offset, limit -> source.at(offset, limit) },
        loadBefore = { before, limit -> source.before(before, limit) },
    )

    @Test
    fun `a jump far down reads one page and scrolling both ways stays in order`() = runBlocking {
        val source = Source(100_000)
        val pager = pager(source)
        pager.reload()
        source.rowsRead = 0
        val index = pager.jumpTo(90_000)
        assertEquals(90_000, pager.items[index])
        assertTrue("rows read for the jump: ${source.rowsRead}", source.rowsRead <= Pager.PAGE)
        assertEquals(pager.start, pager.items.first())
        // Down: pages follow on, and the window never grows past its bound.
        repeat(20) { pager.more() }
        assertTrue(pager.items.size <= PagerWindow.MAX_WINDOW)
        assertEquals((pager.start until pager.start + pager.items.size).toList(), pager.items.toList())
        // Up: rows before the window come back in order, until the top of the list.
        repeat(1_000) { if (pager.start > 0) pager.less() }
        assertEquals(0, pager.start)
        assertEquals(0, pager.items.first())
        assertEquals((0 until pager.items.size).toList(), pager.items.toList())
        assertTrue(pager.items.size <= PagerWindow.MAX_WINDOW)
    }

    @Test
    fun `a jump inside the window reads nothing and a jump past the end is clamped`() = runBlocking {
        val source = Source(500)
        val pager = pager(source)
        pager.reload()
        source.rowsRead = 0
        assertEquals(120, pager.jumpTo(120))
        assertEquals(0, source.rowsRead)
        // Past the end (the list shrank): the list stays as it was.
        val before = pager.items.toList()
        val last = pager.jumpTo(10_000)
        assertEquals(before, pager.items.toList())
        assertEquals(pager.items.lastIndex, last)
    }

    @Test
    fun `an index change reloads the window where it is`() = runBlocking {
        val source = Source(5_000)
        val pager = pager(source)
        pager.reload()
        pager.jumpTo(4_000)
        val start = pager.start
        pager.reload()
        assertEquals(start, pager.start)
        assertEquals(start, pager.items.first())
    }

    @Test
    fun `an empty list jumps nowhere`() = runBlocking {
        val pager = pager(Source(0))
        pager.reload()
        assertEquals(0, pager.jumpTo(50))
        assertTrue(pager.items.isEmpty())
    }
}
