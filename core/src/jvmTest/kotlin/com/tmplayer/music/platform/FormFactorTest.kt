package com.tmplayer.music.platform

import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FormFactorTest {
    @After
    fun reset() = FormFactor.override(null)

    @Test
    fun anyOneSignalMeansTv() {
        assertFalse(FormFactor.decide(FormFactor.Signals()))
        assertTrue(FormFactor.decide(FormFactor.Signals(television = true)))
        assertTrue(FormFactor.decide(FormFactor.Signals(leanback = true)))
        assertTrue(FormFactor.decide(FormFactor.Signals(fireTv = true)))
    }

    @Test
    fun readOnceThenCached() {
        FormFactor.override(null)
        var reads = 0
        assertTrue(FormFactor.isTv { reads++; FormFactor.Signals(television = true) })
        assertTrue(FormFactor.isTv { reads++; FormFactor.Signals() })
        assertEquals(1, reads)
        assertTrue(FormFactor.known)
    }

    @Test
    fun aFailedReadIsAPhone() {
        FormFactor.override(null)
        assertFalse(FormFactor.isTv { error("no service") })
        assertFalse(FormFactor.known)
    }

    @Test
    fun safeMarginsAreFivePercentAndNeverNegative() {
        assertEquals(48f to 27f, TvLayout.safeMargins(960f, 540f))
        assertEquals(0f to 0f, TvLayout.safeMargins(-1f, Float.NaN))
    }
}
