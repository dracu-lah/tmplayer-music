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
    fun `the wave steps about 15 times a second, one wavelength a second`() {
        val perSecond = 1_000f / WaveLogic.STEP_MS
        assertTrue("steps a second: $perSecond", perSecond in 12f..17f)
        assertEquals(0.066f, WaveLogic.nextPhase(0f, WaveLogic.STEP_MS), 0.0001f)
        assertEquals(0.016f, WaveLogic.nextPhase(0.95f, WaveLogic.STEP_MS), 0.0001f)
        var phase = 0f
        repeat(10_000) { phase = WaveLogic.nextPhase(phase, WaveLogic.STEP_MS) }
        assertTrue(phase in 0f..1f)
        assertEquals(0.5f, WaveLogic.nextPhase(0.5f, -40), 0.0001f)
        assertEquals(0f, WaveLogic.nextPhase(Float.NaN, WaveLogic.STEP_MS))
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
