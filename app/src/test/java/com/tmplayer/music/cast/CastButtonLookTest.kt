package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastButtonLookTest {
    @Test
    fun theButtonReadsTheSameEverywhereFromTheStateAlone() {
        assertEquals(CastLook.Idle, castLook(CastState.Idle, problem = false))
        assertEquals(CastLook.Connecting, castLook(CastState.Connecting("TV"), problem = false))
        assertEquals(CastLook.Connected, castLook(CastState.Connected("TV"), problem = false))
        assertEquals(CastLook.Problem, castLook(CastState.Failed("TV"), problem = false))
        assertEquals(CastLook.Problem, castLook(CastState.Idle, problem = true))
        assertEquals(CastLook.Problem, castLook(CastState.Connected("TV"), problem = true))
        // A connection being made is shown as such, whatever was said before.
        assertEquals(CastLook.Connecting, castLook(CastState.Connecting("TV"), problem = true))
    }

    @Test
    fun theMiniPlayerHasRoomOnlyOnWidePhonesOrWhileACastIsOn() {
        assertTrue(castFitsMini(412, CastState.Idle))
        assertFalse(castFitsMini(360, CastState.Idle))
        assertFalse(castFitsMini(320, CastState.Failed("TV")))
        assertTrue(castFitsMini(320, CastState.Connected("TV")))
        assertTrue(castFitsMini(320, CastState.Connecting("TV")))
    }
}
