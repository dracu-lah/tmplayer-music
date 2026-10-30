// From TMPlayer (GPL-3.0), its DownloadWindowTest.kt.
package com.tmplayer.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek matrix in docs/ARCHITECTURE.md ("Seeking"), expressed against the arithmetic that decides whether a
 * read can be served or the download has to be moved.
 */
class DownloadWindowTest {

    private val size = 1_000_000L

    @Test
    fun `reads inside the downloaded window return what is left of it`() {
        val available = DownloadWindow.availableAt(
            position = 500,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 2_000,
            completed = false,
        )
        assertEquals(1_500, available)
    }

    @Test
    fun `a byte before the window is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 100,
            size = size,
            downloadOffset = 5_000,
            downloadedPrefixSize = 2_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a byte past the window is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 9_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 8_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `the byte exactly at the window edge is not readable yet`() {
        val available = DownloadWindow.availableAt(
            position = 8_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 8_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a fully downloaded file reads to the end from anywhere`() {
        val available = DownloadWindow.availableAt(
            position = 999_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 0,
            completed = true,
        )
        assertEquals(1_000, available)
    }

    @Test
    fun `reading past the end of a finished file yields nothing rather than a negative count`() {
        val available = DownloadWindow.availableAt(
            position = size + 10,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 0,
            completed = true,
        )
        assertEquals(0, available)
    }

    // --- Seek case 1: backwards into an area TDLib has moved past ---
    @Test
    fun `seeking backwards restarts the download`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 1_000,
                downloadOffset = 500_000,
                downloadedPrefixSize = 10_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 2: a long jump forward the running download will never reach in time ---
    @Test
    fun `seeking far forward restarts the download`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 500_000_000,
                downloadOffset = 0,
                downloadedPrefixSize = 1_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 3: a small nudge forward is cheaper to wait out than to restart ---
    @Test
    fun `seeking just ahead of the buffer lets the running download catch up`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 1_000_000 + 1_000,
                downloadOffset = 0,
                downloadedPrefixSize = 1_000_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 4: normal sequential playback inside the window ---
    @Test
    fun `reading inside the window never restarts`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 400_000,
                downloadOffset = 0,
                downloadedPrefixSize = 500_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 5: TDLib gave up while we sat at the edge of the window ---
    @Test
    fun `a stalled download at the buffer edge is restarted`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 500_000,
                downloadOffset = 0,
                downloadedPrefixSize = 500_000,
                active = false,
                completed = false,
            ),
        )
    }

    // --- Seek case 6: everything is already on disk, so any position is fine ---
    @Test
    fun `a completed file never restarts`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 0,
                downloadOffset = 900_000,
                downloadedPrefixSize = 0,
                active = false,
                completed = true,
            ),
        )
    }

    /**
     * The sequence that took playback down on a real film: seek forward, watch, seek back.
     *
     * TDLib had moved its window to the forward position, so the far end of that window was a
     * long way past the byte the player now wanted. Judging the read on that end alone said
     * "half a megabyte available" for bytes that had been freed, and the partial file keeps its
     * length, so the read succeeded and returned a hole. The extractor died on the zeroes.
     * Only the near end of the window rules that out.
     */
    @Test
    fun `a byte behind a window that has moved forward is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 120_000,
            size = size,
            downloadOffset = 600_000,
            downloadedPrefixSize = 500_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a negative position reads nothing`() {
        assertEquals(
            0,
            DownloadWindow.availableAt(
                position = -1,
                size = size,
                downloadOffset = 0,
                downloadedPrefixSize = 5_000,
                completed = false,
            ),
        )
    }

    // --- Low storage mode: the rolling window, the same seek matrix with a budget on disk ---

    private val mb = 1024L * 1024
    private val film = 4_000L * 1024 * 1024
    private val w = LowStorageWindow.WINDOW_BYTES
    private fun r(start: Long, end: Long) = LowStorageWindow.Range(start, end)

    @Test
    fun `the first request asks for one window, not the whole film`() {
        val request = LowStorageWindow.plan(emptyList(), 0, film, w)
        assertEquals(w, request.limit)
        assertFalse(request.deleteFirst)
        assertEquals(listOf(r(0, w)), request.ranges)
    }

    @Test
    fun `a window that reaches the end of the film asks for the rest, and no more`() {
        val request = LowStorageWindow.plan(emptyList(), film - 50 * mb, film, w)
        // Never 0: TDLib reads that as no limit and, past the end, fetches the film from the start.
        assertEquals(50 * mb, request.limit)
        assertEquals(listOf(r(film - 50 * mb, film)), request.ranges)
    }

    @Test
    fun `reading in the middle of the window does not roll`() {
        assertFalse(LowStorageWindow.shouldRoll(100 * mb, listOf(r(0, w)), film))
    }

    @Test
    fun `reading near the end of the window rolls`() {
        assertTrue(LowStorageWindow.shouldRoll(w - 1 * mb, listOf(r(0, w)), film))
        assertTrue(LowStorageWindow.shouldRoll(w - LowStorageWindow.ROLL_MARGIN_BYTES, listOf(r(0, w)), film))
        assertFalse(LowStorageWindow.shouldRoll(w - LowStorageWindow.ROLL_MARGIN_BYTES - 1, listOf(r(0, w)), film))
    }

    @Test
    fun `the last window of the film never rolls`() {
        assertFalse(LowStorageWindow.shouldRoll(film - 1, listOf(r(film - w, film)), film))
    }

    @Test
    fun `a read outside every range is a seek, which the seek path restarts`() {
        assertFalse(LowStorageWindow.shouldRoll(900 * mb, listOf(r(0, w)), film))
    }

    @Test
    fun `a roll deletes the file and restarts a little behind the read position`() {
        val position = w - 3 * mb
        val from = LowStorageWindow.rollStart(position)
        assertTrue(from <= position - LowStorageWindow.BACK_BYTES)
        assertTrue(position - from < LowStorageWindow.BACK_BYTES + LowStorageWindow.ALIGN_BYTES)
        assertEquals(0, from % LowStorageWindow.ALIGN_BYTES)
        val request = LowStorageWindow.plan(listOf(r(0, w)), from, film, w, roll = true)
        assertTrue(request.deleteFirst)
        assertEquals(listOf(r(from, from + w)), request.ranges)
        assertTrue(LowStorageWindow.bytes(request.ranges) <= w)
    }

    @Test
    fun `after a roll the read position is within the wait that does not restart`() {
        // The roll lands behind the read position; a restart there would undo it.
        val position = 777 * mb + 12_345
        val from = LowStorageWindow.rollStart(position)
        assertFalse(
            DownloadWindow.needsRestart(
                position = position,
                downloadOffset = from,
                downloadedPrefixSize = 0,
                active = true,
                completed = false,
            ),
        )
    }

    @Test
    fun `a roll at the start of the file does not go below zero`() {
        assertEquals(0, LowStorageWindow.rollStart(1 * mb))
    }

    @Test
    fun `a far seek forward deletes first`() {
        val request = LowStorageWindow.plan(listOf(r(0, w)), 2_000 * mb, film, w)
        assertTrue(request.deleteFirst)
        assertEquals(listOf(r(2_000 * mb, 2_000 * mb + w)), request.ranges)
    }

    @Test
    fun `seeking back past the window deletes and fetches again`() {
        val request = LowStorageWindow.plan(listOf(r(1_000 * mb, 1_000 * mb + w)), 10 * mb, film, w)
        assertTrue(request.deleteFirst)
        assertEquals(listOf(r(10 * mb, 10 * mb + w)), request.ranges)
    }

    @Test
    fun `a short seek back keeps what is on disk when it stays inside the budget`() {
        // Ten megabytes back from a window that started at 300 MB: the two overlap, so the disk
        // holds one window and a little, inside the slack.
        val request = LowStorageWindow.plan(listOf(r(300 * mb, 300 * mb + w)), 290 * mb, film, w)
        assertFalse(request.deleteFirst)
        assertEquals(listOf(r(290 * mb, 300 * mb + w)), request.ranges)
    }

    @Test
    fun `the Matroska index at the far end fits in the slack beside the first window`() {
        val tail = film - 3 * mb
        val request = LowStorageWindow.plan(listOf(r(0, w)), tail, film, w)
        assertFalse(request.deleteFirst)
        assertEquals(3 * mb, request.limit)
        assertEquals(w + 3 * mb, LowStorageWindow.bytes(request.ranges))
    }

    @Test
    fun `a whole film played through never holds more than a window and its slack`() {
        var ranges = LowStorageWindow.plan(emptyList(), 0, film, w).ranges
        var rolls = 0
        var position = 0L
        while (position < film) {
            if (LowStorageWindow.shouldRoll(position, ranges, film)) {
                val request = LowStorageWindow.plan(ranges, LowStorageWindow.rollStart(position), film, w, roll = true)
                assertTrue(request.deleteFirst)
                ranges = request.ranges
                rolls++
            }
            assertTrue(LowStorageWindow.bytes(ranges) <= w + LowStorageWindow.SLACK_BYTES)
            position += 512 * 1024
        }
        // Each roll moves on by the window less its margin and the back buffer.
        assertTrue(rolls in 19..22)
    }

    @Test
    fun `a seek back inside what is on disk keeps it, after the index at the far end was read`() {
        // Seen on the TV emulator with an MP4 whose index sits at the end: the head window and the
        // index are on disk, and a seek to 135 MB, inside the head, used to delete them both and
        // fetch the same bytes again.
        val held = listOf(r(0, w), r(film - 10 * mb, film))
        val request = LowStorageWindow.plan(held, 135 * mb, film, w)
        assertFalse(request.deleteFirst)
        // Grown only as far as the budget allows, and the reading position stays readable.
        assertTrue(LowStorageWindow.bytes(request.ranges) <= w + LowStorageWindow.SLACK_BYTES)
        assertEquals(w + LowStorageWindow.SLACK_BYTES - 10 * mb, request.limit + 135 * mb)
        // And once reading nears that end, the window rolls on in the ordinary way.
        assertTrue(LowStorageWindow.shouldRoll(135 * mb + request.limit - 1 * mb, request.ranges, film))
    }

    @Test
    fun `a seek inside what is on disk with no room left to grow still deletes`() {
        // Right at the end of a full window there is nothing to read before a roll would be due.
        val held = listOf(r(0, w + LowStorageWindow.SLACK_BYTES))
        val request = LowStorageWindow.plan(held, w + LowStorageWindow.SLACK_BYTES - 2 * mb, film, w)
        assertTrue(request.deleteFirst)
    }

    @Test
    fun `the window shrinks on a nearly full disk but never below the minimum`() {
        val headroom = 400 * mb
        assertEquals(w, LowStorageWindow.windowBytes(5_000 * mb, headroom))
        assertEquals(98 * mb, LowStorageWindow.windowBytes(500 * mb, headroom))
        assertEquals(LowStorageWindow.MIN_WINDOW_BYTES, LowStorageWindow.windowBytes(100 * mb, headroom))
    }

    @Test
    fun `the room a stream needs is the window and its back buffer, or the rest when that is less`() {
        assertEquals(w + LowStorageWindow.BACK_BYTES, LowStorageWindow.roomNeeded(film, w))
        assertEquals(30 * mb, LowStorageWindow.roomNeeded(30 * mb, w))
    }

    // --- Low storage mode: bytes TDLib already has on the disk ---


    @Test
    fun `a head on the disk at the start is read and the window carries on from its end`() {
        val answer = LowStorageWindow.onDisk(emptyList(), 0, 1 * mb, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.ReadAndFollow, answer.use)
        assertEquals(listOf(r(0, 1 * mb)), answer.ranges)
        // The request from its end that follows fits, and the stretch joins it: no roll at once.
        val follow = LowStorageWindow.plan(answer.ranges, 1 * mb, film, w)
        assertFalse(follow.deleteFirst)
        assertFalse(LowStorageWindow.shouldRoll(0, follow.ranges, film))
        assertTrue(LowStorageWindow.bytes(follow.ranges) <= w + LowStorageWindow.SLACK_BYTES)
    }

    @Test
    fun `an index at the end of the file on the disk is read without moving the download`() {
        val held = listOf(r(0, w))
        val answer = LowStorageWindow.onDisk(held, film - 300 * 1024, 300 * 1024, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.Read, answer.use)
        // Counted against the budget, and never a reason to roll.
        assertEquals(w + 300 * 1024, LowStorageWindow.bytes(answer.ranges))
        assertFalse(LowStorageWindow.shouldRoll(film - 100 * 1024, answer.ranges, film))
        // The jump back to the picture is inside what is held, and deletes nothing.
        assertFalse(LowStorageWindow.plan(answer.ranges, 2 * mb, film, w).deleteFirst)
    }

    @Test
    fun `an index part way through the last megabytes is held to the end, so reading it never rolls`() {
        // Cues on the disk, Tags after them not: the stretch stops short of the end.
        val start = film - 3 * mb
        val answer = LowStorageWindow.onDisk(listOf(r(0, w)), start, 512 * 1024, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.Read, answer.use)
        assertTrue(answer.ranges.contains(r(start, film)))
        assertFalse(LowStorageWindow.shouldRoll(start + 400 * 1024, answer.ranges, film))
    }

    @Test
    fun `a long stretch on the disk from an earlier play is read, and the window rolls near its end`() {
        // 300 MB of the film from a play without the mode, the window full elsewhere.
        val held = listOf(r(1_000 * mb, 1_000 * mb + w))
        val answer = LowStorageWindow.onDisk(held, 0, 300 * mb, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.ReadThenRoll, answer.use)
        assertFalse(LowStorageWindow.shouldRoll(10 * mb, answer.ranges, film))
        assertTrue(LowStorageWindow.shouldRoll(298 * mb, answer.ranges, film))
        // The roll there starts afresh, back within the window.
        val roll = LowStorageWindow.plan(answer.ranges, LowStorageWindow.rollStart(298 * mb), film, w, roll = true)
        assertTrue(roll.deleteFirst)
        assertTrue(LowStorageWindow.bytes(roll.ranges) <= w)
    }

    @Test
    fun `a scrap on the disk where the window cannot carry on is fetched the ordinary way`() {
        val held = listOf(r(0, w + LowStorageWindow.SLACK_BYTES / 2))
        val answer = LowStorageWindow.onDisk(held, 2_000 * mb, 1 * mb, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.Fetch, answer.use)
        // Nothing recorded for it: the seek's own request deletes and starts afresh.
        assertEquals(held, answer.ranges)
        assertTrue(LowStorageWindow.plan(answer.ranges, 2_000 * mb, film, w).deleteFirst)
    }

    @Test
    fun `nothing on the disk is nothing to read`() {
        assertEquals(LowStorageWindow.OnDisk.Use.Fetch, LowStorageWindow.onDisk(listOf(r(0, w)), 5 * mb, 0, film, w).use)
    }

    @Test
    fun `a seek back into the window's own stretch is read from the disk`() {
        val held = listOf(r(0, w))
        // TDLib's download had reached 120 MB: the window grows from there within its budget.
        val answer = LowStorageWindow.onDisk(held, 50 * mb, 70 * mb, film, w)
        assertEquals(LowStorageWindow.OnDisk.Use.ReadAndFollow, answer.use)
        assertEquals(held, answer.ranges)
        // Had it reached the end of the window, there is no room to grow: read, then roll.
        assertEquals(LowStorageWindow.OnDisk.Use.ReadThenRoll, LowStorageWindow.onDisk(held, 50 * mb, w - 50 * mb, film, w).use)
    }

    @Test
    fun `an index probe is held to the end of the file and fits only within the budget`() {
        val tail = film - 2 * mb
        assertEquals(r(tail, film), LowStorageWindow.indexRange(tail, tail + 1 * mb, film))
        // Away from the end, a probe is held as fetched.
        assertEquals(r(100 * mb, 101 * mb), LowStorageWindow.indexRange(100 * mb, 101 * mb, film))
        assertTrue(LowStorageWindow.probeFits(listOf(r(0, w)), tail, tail + 1 * mb, film, w))
        val full = listOf(r(0, w + LowStorageWindow.SLACK_BYTES - 1 * mb))
        assertFalse(LowStorageWindow.probeFits(full, tail, tail + 1 * mb, film, w))
    }

    @Test
    fun `only the last megabytes of a file count as its index`() {
        assertTrue(LowStorageWindow.nearTheEnd(film - 1, film))
        assertTrue(LowStorageWindow.nearTheEnd(film - LowStorageWindow.INDEX_TAIL_BYTES, film))
        assertFalse(LowStorageWindow.nearTheEnd(film - LowStorageWindow.INDEX_TAIL_BYTES - 1, film))
        assertFalse(LowStorageWindow.nearTheEnd(film, film))
        assertFalse(LowStorageWindow.nearTheEnd(5, 0))
        assertTrue(LowStorageWindow.INDEX_TAIL_BYTES <= LowStorageWindow.SLACK_BYTES)
    }

    @Test
    fun `a start, its index and a seek to the end keep the disk within the budget`() {
        // Head found on the disk, the window followed from it, the Cues read at the end, the jump
        // back, then the viewer jumps to the last minutes: never more than the window and slack,
        // save what was on the disk before the mode began.
        var ranges = LowStorageWindow.onDisk(emptyList(), 0, 1 * mb, film, w).ranges
        ranges = LowStorageWindow.plan(ranges, 1 * mb, film, w).ranges
        ranges = LowStorageWindow.onDisk(ranges, film - 400 * 1024, 400 * 1024, film, w).ranges
        assertTrue(LowStorageWindow.bytes(ranges) <= w + LowStorageWindow.SLACK_BYTES)
        val back = LowStorageWindow.plan(ranges, 2 * mb, film, w)
        assertFalse(back.deleteFirst)
        ranges = back.ranges
        val toTheEnd = LowStorageWindow.plan(ranges, film - 30 * mb, film, w)
        assertTrue(toTheEnd.deleteFirst)
        assertTrue(LowStorageWindow.bytes(toTheEnd.ranges) <= w + LowStorageWindow.SLACK_BYTES)
    }

    @Test
    fun `a probe hands its read the bytes once enough at the read's byte is on the disk`() {
        val mb = 1024L * 1024
        val ready = 64L * 1024
        // An MKV's Cues 300 KB into a two megabyte probe: 300 KB before the byte, 64 KB after it.
        assertEquals(300 * 1024 + ready, DownloadWindow.probeReady(10 * mb, 12 * mb, 10 * mb + 300 * 1024, ready))
        // At the probe's first byte: only the first 64 KB.
        assertEquals(ready, DownloadWindow.probeReady(10 * mb, 11 * mb, 10 * mb, ready))
    }

    @Test
    fun `a probe that ends sooner than the ready amount waits for all of itself`() {
        val start = 50L * 1024 * 1024
        // The last 20 KB of the file: the probe is cut at its end, so the whole of it is the amount.
        val end = start + 120 * 1024
        assertEquals(end - start, DownloadWindow.probeReady(start, end, end - 20 * 1024, 64L * 1024))
    }

    @Test
    fun `the ready amount never runs outside the probe`() {
        assertEquals(1_000L, DownloadWindow.probeReady(0, 1_000, 5_000, 64L * 1024))
        assertEquals(64L * 1024, DownloadWindow.probeReady(100, 1_000_000, 0, 64L * 1024))
    }

    @Test
    fun `the back buffer and its alignment stay inside the seek slack`() {
        assertTrue(LowStorageWindow.BACK_BYTES + LowStorageWindow.ALIGN_BYTES <= DownloadWindow.FORWARD_SLACK_BYTES)
    }
}
