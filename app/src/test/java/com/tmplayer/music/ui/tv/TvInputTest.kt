package com.tmplayer.music.ui.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvInputTest {

    @Test
    fun `a tap of OK is left to the click handler`() {
        val keys = HoldKeys()
        val down = keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 0)
        val up = keys.onKey(Key.DirectionCenter, KeyEventType.KeyUp, 0)
        assertFalse(down.consumed || down.hold)
        assertFalse(up.consumed || up.hold)
    }

    @Test
    fun `a hold of OK fires once and its release is used up`() {
        val keys = HoldKeys()
        keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 0)
        val first = keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 1)
        val second = keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 2)
        val up = keys.onKey(Key.DirectionCenter, KeyEventType.KeyUp, 0)
        assertTrue(first.hold && first.consumed)
        assertTrue(second.consumed && !second.hold)
        assertTrue(up.consumed && !up.hold)
    }

    @Test
    fun `the next press after a hold is a plain tap again`() {
        val keys = HoldKeys()
        keys.onKey(Key.Enter, KeyEventType.KeyDown, 0)
        keys.onKey(Key.Enter, KeyEventType.KeyDown, 1)
        keys.onKey(Key.Enter, KeyEventType.KeyUp, 0)
        keys.onKey(Key.Enter, KeyEventType.KeyDown, 0)
        val up = keys.onKey(Key.Enter, KeyEventType.KeyUp, 0)
        assertFalse(up.consumed)
    }

    @Test
    fun `a hold whose release never arrives does not swallow the next tap`() {
        // The menu a hold opens can take the key up with it: the next press starts at repeat zero.
        val keys = HoldKeys()
        keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 0)
        keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 1)
        keys.onKey(Key.DirectionCenter, KeyEventType.KeyDown, 0)
        assertFalse(keys.onKey(Key.DirectionCenter, KeyEventType.KeyUp, 0).consumed)
    }

    @Test
    fun `Menu and Info fire once on the way down and are used up both ways`() {
        val keys = HoldKeys()
        for (key in listOf(Key.Menu, Key.Info)) {
            val down = keys.onKey(key, KeyEventType.KeyDown, 0)
            val repeat = keys.onKey(key, KeyEventType.KeyDown, 3)
            val up = keys.onKey(key, KeyEventType.KeyUp, 0)
            assertTrue(down.hold && down.consumed)
            assertTrue(repeat.consumed && !repeat.hold)
            assertTrue(up.consumed && !up.hold)
        }
    }

    @Test
    fun `other keys are not touched`() {
        val keys = HoldKeys()
        for (key in listOf(Key.DirectionDown, Key.Back, Key.A, Key.MediaPlayPause)) {
            val down = keys.onKey(key, KeyEventType.KeyDown, 0)
            assertFalse(down.consumed || down.hold)
        }
    }

    @Test
    fun `a tap seeks ten seconds and a hold speeds up`() {
        val long = 300_000L
        assertEquals(10_000L, SeekKeys.stepMs(0, long))
        assertEquals(2_000L, SeekKeys.stepMs(1, long))
        assertEquals(5_000L, SeekKeys.stepMs(15, long))
        assertEquals(15_000L, SeekKeys.stepMs(40, long))
        // A very long track (a mix of three hours) moves faster than the floor.
        assertEquals(72_000L, SeekKeys.stepMs(40, 3 * 3600 * 1000L))
    }

    @Test
    fun `seeking stays inside the track`() {
        assertEquals(1f, SeekKeys.next(0.99f, forward = true, repeat = 0, durationMs = 60_000), 0f)
        assertEquals(0f, SeekKeys.next(0.05f, forward = false, repeat = 0, durationMs = 60_000), 0f)
        assertEquals(0.6f, SeekKeys.next(0.5f, forward = true, repeat = 0, durationMs = 100_000), 1e-6f)
        assertEquals(0.4f, SeekKeys.next(0.5f, forward = false, repeat = 0, durationMs = 100_000), 1e-6f)
    }

    @Test
    fun `an unknown length is never seeked`() {
        assertEquals(0.3f, SeekKeys.next(0.3f, forward = true, repeat = 0, durationMs = 0), 0f)
        assertEquals(0f, SeekKeys.next(0f, forward = true, repeat = 0, durationMs = -5), 0f)
    }

    @Test
    fun `the seek keys have a direction and nothing else has`() {
        assertEquals(true, SeekKeys.direction(Key.DirectionRight))
        assertEquals(false, SeekKeys.direction(Key.DirectionLeft))
        assertEquals(true, SeekKeys.direction(Key.MediaFastForward))
        assertEquals(false, SeekKeys.direction(Key.MediaRewind))
        assertNull(SeekKeys.direction(Key.DirectionUp))
        assertNull(SeekKeys.direction(Key.DirectionCenter))
    }

    @Test
    fun `the pad adds digits up to the limit and deletes`() {
        assertEquals("12", PadEntry.add("1", '2', 5))
        assertEquals("12345", PadEntry.add("12345", '6', 5))
        assertEquals("1", PadEntry.add("1", 'x', 5))
        assertEquals("1", PadEntry.delete("12"))
        assertEquals("", PadEntry.delete(""))
    }

    @Test
    fun `focus after a removal goes to the row that took its place, else the one before`() {
        assertEquals(2, neighbourAfterRemoval(removed = 2, newSize = 5))
        assertEquals(3, neighbourAfterRemoval(removed = 4, newSize = 4))
        assertEquals(0, neighbourAfterRemoval(removed = 0, newSize = 1))
        assertNull(neighbourAfterRemoval(removed = 0, newSize = 0))
        assertEquals(0, neighbourAfterRemoval(removed = -1, newSize = 3))
    }
}
