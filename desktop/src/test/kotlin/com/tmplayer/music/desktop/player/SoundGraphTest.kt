package com.tmplayer.music.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundGraphTest {
    private val all = EngineCapabilities(speed = true, gain = true, equalizer = true, skipSilence = true)

    @Test
    fun `neutral settings need no filter`() {
        assertEquals("", SoundGraph.filters(SoundSettings.NEUTRAL, all))
        assertEquals(1.0, SoundGraph.speed(SoundSettings.NEUTRAL))
        assertEquals(0.0, SoundGraph.gainDb(SoundSettings.NEUTRAL, 0.5f))
    }

    @Test
    fun `a flat equalizer needs no filter, a moved band one equalizer filter`() {
        val flat = SoundSettings(equalizerOn = true, eqLevelsMb = List(10) { 0 })
        assertEquals("", SoundGraph.filters(flat, all))
        val bass = SoundSettings(equalizerOn = true, eqLevelsMb = listOf(300, 150) + List(8) { 0 })
        val chain = SoundGraph.filters(bass, all)
        // The graph travels as mpv's length prefixed string, so no comma or colon of it is read by mpv.
        val graph = "equalizer=f=31:t=o:w=1:g=3,equalizer=f=62:t=o:w=1:g=1.5"
        assertEquals("lavfi=graph=%${graph.length}%$graph", chain)
    }

    @Test
    fun `a filter the library lacks is left out, never asked for`() {
        val settings = SoundSettings(equalizerOn = true, eqLevelsMb = listOf(300) + List(9) { 0 }, skipSilence = true)
        assertEquals("", SoundGraph.filters(settings, EngineCapabilities(speed = true, gain = true)))
        val onlySilence = SoundGraph.filters(settings, EngineCapabilities(skipSilence = true))
        assertTrue(onlySilence.contains("silenceremove") && !onlySilence.contains("equalizer"))
    }

    @Test
    fun `levels of another length are another device's and give a flat curve`() {
        val settings = SoundSettings(equalizerOn = true, eqLevelsMb = listOf(500, 500, 500))
        assertEquals(List(10) { 0 }, SoundGraph.levels(settings))
        assertEquals("", SoundGraph.filters(settings, all))
        // And a level past the range is held to it.
        val loud = SoundSettings(equalizerOn = true, eqLevelsMb = listOf(9_999) + List(9) { 0 })
        assertEquals(SoundGraph.EQ_RANGE_MB, SoundGraph.levels(loud).first())
    }

    @Test
    fun `speed is held to the apps range and a bad one is 1x`() {
        assertEquals(2.0, SoundGraph.speed(SoundSettings(speed = 9f)))
        assertEquals(0.5, SoundGraph.speed(SoundSettings(speed = 0.01f)))
        assertEquals(1.0, SoundGraph.speed(SoundSettings(speed = Float.NaN)))
    }

    @Test
    fun `the loudness gain is decibels, only while normalise is on, and never absurd`() {
        val on = SoundSettings(normalise = true)
        assertEquals(0.0, SoundGraph.gainDb(SoundSettings(), 0.5f))
        assertEquals(-6.02, SoundGraph.gainDb(on, 0.5f), 0.01)
        assertEquals(0.0, SoundGraph.gainDb(on, 1f))
        assertEquals(0.0, SoundGraph.gainDb(on, Float.NaN))
        assertEquals(0.0, SoundGraph.gainDb(on, -3f))
        assertEquals(12.0, SoundGraph.gainDb(on, 1_000f))
    }

    @Test
    fun `the filter probe finds a registered name between two NULs and nothing else`() {
        val library = byteArrayOf(1, 0) + "aformat".toByteArray() + byteArrayOf(0, 7, 0) + "anull".toByteArray() + byteArrayOf(0)
        assertTrue(FilterProbe.contains(library, byteArrayOf(0) + "aformat".toByteArray() + byteArrayOf(0)))
        assertFalse(FilterProbe.contains(library, byteArrayOf(0) + "equalizer".toByteArray() + byteArrayOf(0)))
        // A name that only appears inside a longer word is not a filter.
        assertFalse(FilterProbe.contains(library, byteArrayOf(0) + "format".toByteArray() + byteArrayOf(0)))
        assertFalse(FilterProbe.contains(ByteArray(0), byteArrayOf(0, 1, 0)))
    }
}
