package com.tmplayer.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueFocusTest {

    @Test
    fun `moving a row up keeps the Up button on it, so a hold keeps moving it`() {
        assertEquals(3 to QueueButton.Up, QueueFocus.afterMove(from = 4, to = 3, size = 8, up = true))
    }

    @Test
    fun `moving a row down keeps the Down button on it`() {
        assertEquals(5 to QueueButton.Down, QueueFocus.afterMove(from = 4, to = 5, size = 8, up = false))
    }

    @Test
    fun `at the top the useless Up button gives the focus to Down`() {
        assertEquals(0 to QueueButton.Down, QueueFocus.afterMove(from = 1, to = 0, size = 8, up = true))
    }

    @Test
    fun `at the bottom the useless Down button gives the focus to Up`() {
        assertEquals(7 to QueueButton.Up, QueueFocus.afterMove(from = 6, to = 7, size = 8, up = false))
    }

    @Test
    fun `a destination outside the queue is pulled inside`() {
        assertEquals(0 to QueueButton.Down, QueueFocus.afterMove(from = 0, to = -1, size = 3, up = true))
    }

    @Test
    fun `removing a row puts the D-pad on the Remove button of the row that took its place`() {
        assertEquals(2 to QueueButton.Remove, QueueFocus.afterRemove(removed = 2, newSize = 5))
        assertEquals(3 to QueueButton.Remove, QueueFocus.afterRemove(removed = 4, newSize = 4))
    }

    @Test
    fun `removing the last row leaves no focus target`() {
        assertNull(QueueFocus.afterRemove(removed = 0, newSize = 0))
    }
}
