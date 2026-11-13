package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHealthTest {
    @Test
    fun `a receiver that asks for nothing while the user wants playback is unreachable after the timeout`() {
        val health = CastHealth(unreachableAfterMs = 20_000)
        assertTrue(health.reachable(0, 0, playing = true))
        assertTrue(health.reachable(19_999, 0, playing = true))
        assertFalse(health.reachable(20_000, 0, playing = true))
    }

    @Test
    fun `paused time does not count and the clock starts again when playback is wanted`() {
        val health = CastHealth(unreachableAfterMs = 20_000)
        assertTrue(health.reachable(0, 0, playing = true))
        assertTrue(health.reachable(15_000, 0, playing = false))
        assertTrue(health.reachable(100_000, 0, playing = true))
        assertTrue(health.reachable(119_000, 0, playing = true))
        assertFalse(health.reachable(120_000, 0, playing = true))
    }

    @Test
    fun `one request from the receiver proves it reaches the phone for good`() {
        val health = CastHealth(unreachableAfterMs = 1_000)
        assertTrue(health.reachable(0, 0, true))
        assertTrue(health.reachable(500, 1, true))
        assertTrue(health.isReached)
        assertTrue(health.reachable(1_000_000, 1, true))
    }

    @Test
    fun `a track that fails before the receiver ever asked is the unreachable case, not a skip`() {
        val health = CastHealth()
        assertEquals(CastFailAction.Unreachable, health.failed("a", "A", served = 0))
        assertEquals(0, health.failedCount)
        assertNull(health.summary())
    }

    @Test
    fun `a failing track is skipped once however often the receiver says so`() {
        val health = CastHealth()
        assertEquals(CastFailAction.Skip, health.failed("a", "A", served = 3))
        assertEquals(CastFailAction.Ignore, health.failed("a", "A", served = 3))
        assertEquals(1, health.failedCount)
    }

    @Test
    fun `the same track failing again after the receiver moved on counts again`() {
        val health = CastHealth()
        assertEquals(CastFailAction.Skip, health.failed("a", "A", 2))
        health.active(playing = false)
        assertEquals(CastFailAction.Skip, health.failed("a", "A", 2))
        assertEquals(1, health.failedCount)
    }

    @Test
    fun `three failures in a row stop the cast and a played track resets the run`() {
        val health = CastHealth(giveUpAfter = 3)
        assertEquals(CastFailAction.Skip, health.failed("a", "A", 5))
        health.active(playing = false)
        assertEquals(CastFailAction.Skip, health.failed("b", "B", 5))
        health.active(playing = true)
        assertEquals(CastFailAction.Skip, health.failed("c", "C", 5))
        health.active(playing = false)
        assertEquals(CastFailAction.Skip, health.failed("d", "D", 5))
        health.active(playing = false)
        assertEquals(CastFailAction.GiveUp, health.failed("e", "E", 5))
    }

    @Test
    fun `the summary names one track and counts several`() {
        val health = CastHealth()
        assertNull(health.summary())
        health.failed("a", "Song", 1)
        val one = health.summary()!!
        assertTrue(one, "Song" in one)
        health.active(false)
        health.failed("b", "Other", 1)
        val many = health.summary()!!
        assertTrue(many, "2" in many)
    }

    @Test
    fun `a track with no title gets a plain name`() {
        val health = CastHealth()
        health.failed("a", "", 1)
        assertTrue(health.summary()!!.isNotBlank())
        assertFalse(health.summary()!!.startsWith(" "))
    }
}
