package com.tmplayer.music.data

import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Keep offline asks before writing: new tracks, what fits, and when to ask first. */
class OfflinePlannerTest {

    private fun track(id: Long, size: Long, unique: String = "u$id", chat: Long = 1) = Track(
        chatId = chat, messageId = id, fileId = 0, uniqueId = unique, fileName = "t$id.mp3", mime = "audio/mpeg",
        sizeBytes = size, dateSec = 0, title = "T$id", artist = "A",
    )

    private val mb = 1024L * 1024

    private fun plan(
        tracks: List<Track>,
        free: Long,
        kept: Set<TrackId> = emptySet(),
        queued: Set<TrackId> = emptySet(),
        uniques: Set<String> = emptySet(),
        reserve: Long = 0,
    ) = OfflinePlanner.plan(tracks, kept, queued, uniques, free, reserve)

    @Test
    fun `everything that fits is kept in order`() {
        val p = plan(listOf(track(1, 10 * mb), track(2, 20 * mb)), free = 100 * mb)
        assertEquals(listOf(1L, 2L), p.toKeep.map { it.messageId })
        assertTrue(p.fitsAll)
        assertEquals(30 * mb, p.neededBytes)
        assertEquals(30 * mb, p.keepBytes)
    }

    @Test
    fun `what does not fit is offered apart and smaller ones after it still fit`() {
        val p = plan(listOf(track(1, 60 * mb), track(2, 50 * mb), track(3, 30 * mb)), free = 100 * mb)
        assertEquals(listOf(1L, 3L), p.toKeep.map { it.messageId })
        assertEquals(listOf(2L), p.tooBig.map { it.messageId })
        assertFalse(p.fitsAll)
        assertEquals(140 * mb, p.neededBytes)
        assertEquals(90 * mb, p.keepBytes)
        assertEquals(100 * mb, p.freeBytes)
    }

    @Test
    fun `the headroom is left free`() {
        val p = plan(listOf(track(1, 10 * mb)), free = 105 * mb, reserve = CachePolicy.HEADROOM)
        assertTrue(p.toKeep.isEmpty())
        assertEquals(1, p.tooBig.size)
    }

    @Test
    fun `an unknown size counts as the stand in so a full disk is not waved through`() {
        val p = plan(listOf(track(1, 0)), free = 10 * mb)
        assertEquals(1, p.tooBig.size)
        assertEquals(CachePolicy.UNKNOWN_SIZE, p.neededBytes)
        assertEquals(1, plan(listOf(track(1, 0)), free = 200 * mb).toKeep.size)
    }

    @Test
    fun `kept, queued and repeated tracks are not asked for twice`() {
        val p = plan(
            listOf(track(1, mb), track(2, mb), track(3, mb), track(3, mb), track(4, mb, unique = "same"), track(5, mb, unique = "same", chat = 2)),
            free = 100 * mb,
            kept = setOf(TrackId(1, 1)),
            queued = setOf(TrackId(1, 2)),
        )
        assertEquals(listOf(3L, 4L), p.toKeep.map { it.messageId })
        assertEquals(2, p.alreadyKept)
        assertEquals(1, p.duplicates)
    }

    @Test
    fun `a forward of a kept file in another chat is linked, not fetched again`() {
        val p = plan(listOf(track(7, mb, unique = "abc", chat = 9)), free = 100 * mb, uniques = setOf("abc"))
        assertTrue(p.toKeep.isEmpty() && p.tooBig.isEmpty())
        assertFalse(p.nothingNew)
        assertEquals(listOf(7L), p.links.map { it.messageId })
        assertEquals(1, p.duplicates)
    }

    @Test
    fun `the stand in id the mapper makes up never marks two files as one`() {
        val p = plan(listOf(track(1, mb, unique = "f12"), track(2, mb, unique = "f12")), free = 100 * mb)
        assertEquals(2, p.toKeep.size)
    }

    @Test
    fun `a big step asks first`() {
        assertFalse(plan((1..24L).map { track(it, mb) }, free = 10_000 * mb).needsConfirm)
        assertTrue(plan((1..25L).map { track(it, mb) }, free = 10_000 * mb).needsConfirm)
        assertTrue(plan(listOf(track(1, 1100 * mb)), free = 10_000 * mb).needsConfirm)
        assertFalse(plan(listOf(track(1, 100 * mb)), free = 10_000 * mb).needsConfirm)
    }

    @Test
    fun `nothing free keeps nothing and an empty selection is nothing new`() {
        assertEquals(1, plan(listOf(track(1, mb)), free = 0).tooBig.size)
        assertTrue(plan(emptyList(), free = 10 * mb).nothingNew)
        assertEquals(1, plan(listOf(track(1, mb)), free = -5).tooBig.size)
    }

    @Test
    fun `a huge library plans without overflow`() {
        val tracks = (1..2000L).map { track(it, Long.MAX_VALUE / 100) }
        val p = plan(tracks, free = Long.MAX_VALUE)
        assertTrue(p.neededBytes > 0)
    }
}
