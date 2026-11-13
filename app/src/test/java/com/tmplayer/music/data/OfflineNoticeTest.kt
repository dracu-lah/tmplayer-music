package com.tmplayer.music.data

import com.tmplayer.music.library.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Keep offline notification says, from the queue's state. */
class OfflineNoticeTest {

    private fun running(title: String, done: Long, total: Long) = KeepRunning(TrackId(1, 1), title, done, total)

    @Test
    fun `the title is the track being fetched, else the plain title`() {
        assertEquals("Song", OfflineNotice.title(KeepState(running = listOf(running("Song", 0, 10)))))
        assertEquals("Keeping tracks offline", OfflineNotice.title(KeepState()))
        assertEquals("Keeping tracks offline", OfflineNotice.title(KeepState(running = listOf(running("  ", 0, 10)))))
    }

    @Test
    fun `the text counts what is done of what was asked, never past the total`() {
        assertEquals("3 of 10", OfflineNotice.text(KeepState(total = 10, done = 3)))
        assertEquals("10 of 10", OfflineNotice.text(KeepState(total = 10, done = 12)))
        assertEquals("0 of 0", OfflineNotice.text(KeepState()))
    }

    @Test
    fun `the bar is the share of the track fetched, and absent when its size is not known`() {
        assertEquals(0.25f, OfflineNotice.fraction(KeepState(running = listOf(running("a", 25, 100))))!!, 0.0001f)
        assertEquals(1f, OfflineNotice.fraction(KeepState(running = listOf(running("a", 500, 100))))!!, 0.0001f)
        assertNull(OfflineNotice.fraction(KeepState(running = listOf(running("a", 5, 0)))))
        assertNull(OfflineNotice.fraction(KeepState()))
    }
}
