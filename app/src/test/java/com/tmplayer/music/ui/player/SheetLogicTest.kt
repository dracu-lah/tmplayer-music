package com.tmplayer.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLogicTest {
    private val height = 2000f
    private val collapsed = 1800f

    @Test
    fun `anchors offer dismissal only when allowed`() {
        val closed = SheetLogic.anchors(height, collapsed, allowDismiss = false)
        assertEquals(mapOf(SheetValue.Expanded to 0f, SheetValue.Collapsed to collapsed), closed)
        val open = SheetLogic.anchors(height, collapsed, allowDismiss = true)
        assertEquals(height, open[SheetValue.Dismissed])
    }

    @Test
    fun `anchors survive odd sizes`() {
        // A mini-player taller than the window (split screen) clamps instead of going negative.
        val tiny = SheetLogic.anchors(containerHeight = 50f, collapsedTop = -30f, allowDismiss = true)
        assertEquals(0f, tiny[SheetValue.Collapsed])
        val zero = SheetLogic.anchors(containerHeight = 0f, collapsedTop = 0f, allowDismiss = true)
        assertFalse(SheetValue.Dismissed in zero)
    }

    @Test
    fun `progress runs from the mini-player to Now Playing`() {
        assertEquals(0f, SheetLogic.progress(collapsed, collapsed))
        assertEquals(1f, SheetLogic.progress(0f, collapsed))
        assertEquals(0.5f, SheetLogic.progress(900f, collapsed), 0.0001f)
        // Pulled below the mini-player (toward dismissal) is still 0, and an unset offset is 0.
        assertEquals(0f, SheetLogic.progress(1950f, collapsed))
        assertEquals(0f, SheetLogic.progress(Float.NaN, collapsed))
        assertEquals(0f, SheetLogic.progress(10f, 0f))
    }

    @Test
    fun `dismiss progress measures the pull below the mini-player`() {
        assertEquals(0f, SheetLogic.dismissProgress(collapsed, collapsed, height))
        assertEquals(0.5f, SheetLogic.dismissProgress(1900f, collapsed, height), 0.0001f)
        assertEquals(0f, SheetLogic.dismissProgress(500f, collapsed, height))
    }

    @Test
    fun `a sheet cut off mid-drag settles on the nearest anchor`() {
        val anchors = SheetLogic.anchors(height, collapsed, allowDismiss = false)
        assertEquals(SheetValue.Expanded, SheetLogic.nearest(400f, anchors))
        assertEquals(SheetValue.Collapsed, SheetLogic.nearest(1500f, anchors))
        assertEquals(SheetValue.Collapsed, SheetLogic.nearest(Float.NaN, anchors))
        assertTrue(SheetLogic.isBetweenAnchors(400f, anchors))
        assertFalse(SheetLogic.isBetweenAnchors(collapsed, anchors))
        assertFalse(SheetLogic.isBetweenAnchors(Float.NaN, anchors))
    }

    @Test
    fun `swipe down dismisses only when paused and collapsed`() {
        assertTrue(SheetLogic.allowDismiss(isPlaying = false, buffering = false, settled = SheetValue.Collapsed))
        assertFalse(SheetLogic.allowDismiss(isPlaying = true, buffering = false, settled = SheetValue.Collapsed))
        assertFalse(SheetLogic.allowDismiss(isPlaying = false, buffering = true, settled = SheetValue.Collapsed))
        assertFalse(SheetLogic.allowDismiss(isPlaying = false, buffering = false, settled = SheetValue.Expanded))
        // Already dismissed: the anchor stays while the queue clears, so the card does not bounce back.
        assertTrue(SheetLogic.allowDismiss(isPlaying = false, buffering = false, settled = SheetValue.Dismissed))
    }

    @Test
    fun `sideways swipe skips past the threshold only`() {
        assertEquals(1, SheetLogic.skipDirection(-100f, 72f))
        assertEquals(-1, SheetLogic.skipDirection(100f, 72f))
        assertEquals(0, SheetLogic.skipDirection(40f, 72f))
    }
}

class WaveLogicTest {
    @Test
    fun `the wave travels only while playing, open, resumed and allowed`() {
        assertTrue(WaveLogic.travels(playing = true, resumed = true, visible = true, reducedMotion = false, lengthKnown = true))
        assertFalse(WaveLogic.travels(playing = false, resumed = true, visible = true, reducedMotion = false, lengthKnown = true))
        assertFalse(WaveLogic.travels(playing = true, resumed = false, visible = true, reducedMotion = false, lengthKnown = true))
        assertFalse(WaveLogic.travels(playing = true, resumed = true, visible = false, reducedMotion = false, lengthKnown = true))
        assertFalse(WaveLogic.travels(playing = true, resumed = true, visible = true, reducedMotion = true, lengthKnown = true))
        assertFalse(WaveLogic.travels(playing = true, resumed = true, visible = true, reducedMotion = false, lengthKnown = false))
    }

    @Test
    fun `a low memory phone or Battery Saver keeps the wave still`() {
        assertTrue(WaveLogic.stillDevice(lowRam = true, powerSave = false))
        assertTrue(WaveLogic.stillDevice(lowRam = false, powerSave = true))
        assertFalse(WaveLogic.stillDevice(lowRam = false, powerSave = false))
        assertFalse(
            WaveLogic.travels(playing = true, resumed = true, visible = true, reducedMotion = false, lengthKnown = true, stillDevice = true),
        )
        assertTrue(
            WaveLogic.travels(playing = true, resumed = true, visible = true, reducedMotion = false, lengthKnown = true, stillDevice = false),
        )
        // Still, not flat: the wave keeps its height while playing.
        assertEquals(1f, WaveLogic.amplitude(playing = true, lengthKnown = true, seeking = false))
    }

    @Test
    fun `the wave steps about 16 times a second, one wavelength a second`() {
        val perSecond = 1_000f / WaveLogic.STEP_MS
        // Every step is a full window redraw: about 16 a second keeps it under a quarter of a core.
        assertTrue("steps a second: $perSecond", perSecond in 14f..25f)
        // The loop sleeps less than a step, by half a frame, so waiting for the frame does not slow it.
        assertTrue(WaveLogic.SLEEP_MS in 1 until WaveLogic.STEP_MS)
        val landed = 1_000f / (WaveLogic.SLEEP_MS + WaveLogic.FRAME_MS / 2)
        assertTrue("steps landed a second: $landed", landed in 14f..22f)
        assertEquals(0f, WaveLogic.phaseAt(0))
        assertEquals(0.25f, WaveLogic.phaseAt(250_000_000L), 0.0001f)
        // Whole seconds come back to where they started.
        assertEquals(0.3f, WaveLogic.phaseAt(5_300_000_000L), 0.0001f)
        // Continues from where it stopped, wrapping at one wavelength.
        assertEquals(0.05f, WaveLogic.phaseAt(100_000_000L, from = 0.95f), 0.0001f)
        assertEquals(0.4f, WaveLogic.phaseAt(-40, from = 0.4f), 0.0001f)
        // Hours of play stay in range.
        assertTrue(WaveLogic.phaseAt(Long.MAX_VALUE) in 0f..1f)
        assertEquals(0f, WaveLogic.phaseAt(1_000L, from = Float.NaN), 0.001f)
        assertTrue(WaveLogic.phaseAt(0, from = -0.25f) in 0f..1f)
    }

    @Test
    fun `the cached pattern is drawn only at full height`() {
        assertEquals(WaveLogic.Draw.Flat, WaveLogic.draw(0f))
        assertEquals(WaveLogic.Draw.Flat, WaveLogic.draw(Float.NaN))
        assertEquals(WaveLogic.Draw.Flat, WaveLogic.draw(-1f))
        assertEquals(WaveLogic.Draw.Path, WaveLogic.draw(0.5f))
        assertEquals(WaveLogic.Draw.Pattern, WaveLogic.draw(1f))
        // A spring that overshoots stays on the pattern.
        assertEquals(WaveLogic.Draw.Pattern, WaveLogic.draw(1.05f))
    }

    @Test
    fun `the pattern is a whole number of pixels and bounded`() {
        assertEquals(110, WaveLogic.patternWidth(110.0f))
        assertEquals(110, WaveLogic.patternWidth(109.6f))
        assertEquals(8, WaveLogic.patternWidth(0f))
        assertEquals(8, WaveLogic.patternWidth(Float.NaN))
        assertEquals(8, WaveLogic.patternWidth(Float.POSITIVE_INFINITY))
        assertEquals(4096, WaveLogic.patternWidth(1e9f))
    }

    @Test
    fun `the wave is flat when paused, unknown or scrubbing`() {
        assertEquals(1f, WaveLogic.amplitude(playing = true, lengthKnown = true, seeking = false))
        assertEquals(0f, WaveLogic.amplitude(playing = false, lengthKnown = true, seeking = false))
        assertEquals(0f, WaveLogic.amplitude(playing = true, lengthKnown = false, seeking = false))
        assertEquals(0f, WaveLogic.amplitude(playing = true, lengthKnown = true, seeking = true))
    }

    @Test
    fun `fraction is clamped and safe without a length`() {
        assertEquals(0.5f, WaveLogic.fraction(500, 1000), 0.0001f)
        assertEquals(1f, WaveLogic.fraction(5000, 1000))
        assertEquals(0f, WaveLogic.fraction(-5, 1000))
        assertEquals(0f, WaveLogic.fraction(500, 0))
        assertEquals(0f, WaveLogic.fraction(500, -1))
    }

    @Test
    fun belowTheMiniPlayerOnlyDistanceDismisses() {
        // Mini-player top at 2000, screen 2264: 264 px to dismissal.
        assertEquals(null, SheetLogic.settleBelowMini(1990f, 2000f, 2264f, canDismiss = true))
        assertEquals(null, SheetLogic.settleBelowMini(2000f, 2000f, 2264f, canDismiss = true))
        // A quick flick of 55 px settles back, whatever its speed.
        assertEquals(SheetValue.Collapsed, SheetLogic.settleBelowMini(2055f, 2000f, 2264f, canDismiss = true))
        assertEquals(SheetValue.Dismissed, SheetLogic.settleBelowMini(2140f, 2000f, 2264f, canDismiss = true))
        // Playing: never dismissed.
        assertEquals(SheetValue.Collapsed, SheetLogic.settleBelowMini(2250f, 2000f, 2264f, canDismiss = false))
        assertEquals(null, SheetLogic.settleBelowMini(Float.NaN, 2000f, 2264f, canDismiss = true))
    }
}
