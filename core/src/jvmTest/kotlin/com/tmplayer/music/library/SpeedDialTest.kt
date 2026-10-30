package com.tmplayer.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Speed dial's rules: what a kept position is, order, folding, hiding and the bounds. */
class SpeedDialTest {

    private fun item(n: Long, at: Long, key: String = "k$n", position: Long = 0, duration: Long = 200_000, chat: Long = 1) = DialItem(
        key = key, track = TrackId(chat, n), songKey = key, title = "T$n", artist = "A",
        positionMs = position, durationMs = duration, atMs = at,
    )

    @Test
    fun `the first seconds and the last stretch are not kept`() {
        assertFalse(SpeedDial.worthKeeping(5_000, 200_000))
        assertTrue(SpeedDial.worthKeeping(60_000, 200_000))
        // 200 s: the margin is 10 s.
        assertTrue(SpeedDial.worthKeeping(189_000, 200_000))
        assertFalse(SpeedDial.worthKeeping(190_000, 200_000))
        // An hour: 5% would be 3 minutes, capped at a minute.
        assertTrue(SpeedDial.worthKeeping(3_539_000, 3_600_000))
        assertFalse(SpeedDial.worthKeeping(3_540_000, 3_600_000))
    }

    @Test
    fun `an unknown duration keeps its position`() {
        assertTrue(SpeedDial.worthKeeping(500_000, 0))
        assertFalse(SpeedDial.nearEnd(10_000_000, 0))
    }

    @Test
    fun `current first, then the newest`() {
        val current = item(9, at = 0)
        val out = SpeedDial.pick(current, resumes = listOf(item(1, at = 100, position = 30_000)), plays = listOf(item(2, at = 300), item(3, at = 200)))
        assertEquals(listOf(9L, 2L, 3L, 1L), out.map { it.track.messageId })
    }

    @Test
    fun `a song in both lists is one card that resumes and moves up with its newest play`() {
        val out = SpeedDial.pick(null, resumes = listOf(item(1, at = 100, position = 40_000)), plays = listOf(item(1, at = 500), item(2, at = 400)))
        assertEquals(listOf(1L, 2L), out.map { it.track.messageId })
        assertEquals(40_000, out[0].positionMs)
        assertTrue(out[0].partlyPlayed)
        assertFalse(out[1].partlyPlayed)
    }

    @Test
    fun `two versions of one song are one card, the newest`() {
        val a = item(1, at = 100, key = "song", position = 30_000)
        val b = item(2, at = 200, key = "song", position = 50_000)
        val out = SpeedDial.pick(null, listOf(a, b), emptyList())
        assertEquals(listOf(2L), out.map { it.track.messageId })
    }

    @Test
    fun `the current song is not shown again from the lists`() {
        val current = item(1, at = 0, key = "song")
        val out = SpeedDial.pick(current, listOf(item(2, at = 100, key = "song", position = 30_000)), listOf(item(1, at = 50, key = "song")))
        assertEquals(listOf(1L), out.map { it.track.messageId })
    }

    @Test
    fun `a finished or barely started position does not make a card from resumes alone`() {
        val out = SpeedDial.pick(null, listOf(item(1, at = 10, position = 195_000), item(2, at = 20, position = 2_000)), emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a hidden card stays off until it is played again`() {
        val hidden = listOf(DialHidden("k1", atMs = 1_000))
        assertTrue(SpeedDial.pick(null, emptyList(), listOf(item(1, at = 900)), hidden).isEmpty())
        assertEquals(1, SpeedDial.pick(null, emptyList(), listOf(item(1, at = 1_100)), hidden).size)
        // What plays now is never hidden.
        assertEquals(1, SpeedDial.pick(item(1, at = 0), emptyList(), emptyList(), hidden).size)
    }

    @Test
    fun `at most three pages, nine a page`() {
        val plays = (1L..100L).map { item(it, at = it) }
        val out = SpeedDial.pick(null, emptyList(), plays)
        assertEquals(SpeedDial.MAX_SHOWN, out.size)
        assertEquals(100L, out.first().track.messageId)
        val pages = SpeedDial.pages(out)
        assertEquals(listOf(9, 9, 9), pages.map { it.size })
        assertEquals(listOf(9, 1), SpeedDial.pages(out.take(10)).map { it.size })
        assertTrue(SpeedDial.pages(emptyList()).isEmpty())
        assertTrue(SpeedDial.pick(null, emptyList(), plays, limit = 0).isEmpty())
        assertEquals(SpeedDial.MAX_SHOWN, SpeedDial.pick(null, emptyList(), plays, limit = 1_000).size)
    }

    @Test
    fun `the same file twice is one card`() {
        val out = SpeedDial.pick(null, emptyList(), listOf(item(1, at = 10, key = "a"), item(1, at = 5, key = "b")))
        assertEquals(1, out.size)
    }

    @Test
    fun `equal times keep a stable order`() {
        val out = SpeedDial.pick(null, emptyList(), listOf(item(3, at = 5), item(1, at = 5), item(2, at = 5)))
        assertEquals(listOf(1L, 2L, 3L), out.map { it.track.messageId })
    }
}
