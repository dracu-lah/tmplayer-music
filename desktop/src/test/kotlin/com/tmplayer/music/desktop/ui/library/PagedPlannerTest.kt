package com.tmplayer.music.desktop.ui.library

import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keep offline planned a page at a time: the whole run's duplicates and free space, not each page's. */
class PagedPlannerTest {
    private val mb = 1024L * 1024

    private fun track(id: Long, size: Long = mb, unique: String = "u$id") = Track(
        chatId = 1, messageId = id, fileId = 0, uniqueId = unique, fileName = "t$id.mp3", mime = "audio/mpeg",
        sizeBytes = size, dateSec = 0, title = "T$id", artist = "A",
    )

    private fun planner(
        free: Long = 1000 * mb,
        kept: Set<TrackId> = emptySet(),
        queued: Set<TrackId> = emptySet(),
        uniques: Set<String> = emptySet(),
    ) = PagedPlanner(kept, queued, uniques, free, reserveBytes = 0)

    @Test
    fun `pages add up in the order asked`() {
        val p = planner()
        p.add(listOf(track(1), track(2)))
        p.add(listOf(track(3)))
        p.add(emptyList())
        val plan = p.finish()
        assertEquals(listOf(1L, 2L, 3L), plan.toKeep.map { it.messageId })
        assertTrue(plan.fitsAll)
        assertEquals(3 * mb, plan.keepBytes)
    }

    @Test
    fun `the same file on two pages is counted once`() {
        val p = planner()
        p.add(listOf(track(1, unique = "same")))
        p.add(listOf(track(2, unique = "same"), track(3)))
        val plan = p.finish()
        assertEquals(listOf(1L, 3L), plan.toKeep.map { it.messageId })
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `stand in unique ids never fold different files`() {
        val p = planner()
        p.add(listOf(track(1, unique = "f7")))
        p.add(listOf(track(2, unique = "f7"), track(3, unique = "")))
        assertEquals(3, p.finish().toKeep.size)
    }

    @Test
    fun `free space is spent across pages and the rest is reported apart`() {
        val p = planner(free = 25 * mb)
        p.add(listOf(track(1, 10 * mb), track(2, 10 * mb)))
        p.add(listOf(track(3, 10 * mb), track(4, 4 * mb)))
        val plan = p.finish()
        assertEquals(listOf(1L, 2L, 4L), plan.toKeep.map { it.messageId })
        assertEquals(listOf(3L), plan.tooBig.map { it.messageId })
        assertFalse(plan.fitsAll)
        assertEquals(34 * mb, plan.neededBytes)
    }

    @Test
    fun `already kept and queued files are counted not planned`() {
        val p = planner(kept = setOf(TrackId(1, 1)), queued = setOf(TrackId(1, 2)))
        p.add(listOf(track(1), track(2), track(3)))
        val plan = p.finish()
        assertEquals(2, plan.alreadyKept)
        assertEquals(listOf(3L), plan.toKeep.map { it.messageId })
    }

    @Test
    fun `a negative free value keeps nothing and reports zero free`() {
        val p = planner(free = -5)
        p.add(listOf(track(1)))
        val plan = p.finish()
        assertTrue(plan.toKeep.isEmpty())
        assertEquals(1, plan.tooBig.size)
        assertEquals(0, plan.freeBytes)
    }

    @Test
    fun `sizes that overflow are held at the maximum`() {
        val p = planner(free = Long.MAX_VALUE)
        p.add(listOf(track(1, Long.MAX_VALUE - 1), track(2, Long.MAX_VALUE - 1)))
        assertEquals(Long.MAX_VALUE, p.finish().neededBytes)
    }
}
