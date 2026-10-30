package com.tmplayer.music.platform

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test
    fun limiterLetsOneThroughPerGap() {
        val limiter = HapticLimiter(50)
        assertTrue(limiter.allow(1_000))
        assertFalse(limiter.allow(1_010))
        assertFalse(limiter.allow(1_049))
        assertTrue(limiter.allow(1_050))
        assertFalse(limiter.allow(1_051))
        assertTrue(limiter.allow(2_000))
    }

    @Test
    fun limiterRecoversFromAClockThatGoesBack() {
        val limiter = HapticLimiter(50)
        assertTrue(limiter.allow(5_000))
        assertTrue(limiter.allow(100))
        assertFalse(limiter.allow(120))
    }

    @Test
    fun rapidTapsGiveAtMostOnePer50Ms() {
        val limiter = HapticLimiter()
        val played = (0L until 1_000L step 10).count { limiter.allow(it) }
        assertEquals(20, played)
    }

    @Test
    fun switchIsOnByDefaultAndKeptAcrossRestarts() {
        val store = MemoryKeyValues()
        assertTrue(HapticChoices(store).enabled.value)
        HapticChoices(store).setEnabled(false)
        assertFalse(HapticChoices(store).enabled.value)
        HapticChoices(store).setEnabled(true)
        assertTrue(HapticChoices(store).enabled.value)
    }

    @Test
    fun switchAppliesLiveAndSurvivesABrokenStore() {
        val broken = object : KeyValues {
            override fun getBoolean(key: String, default: Boolean): Boolean = error("disk")
            override fun putBoolean(key: String, value: Boolean) = error("disk")
            override fun getLong(key: String, default: Long): Long = error("disk")
            override fun putLong(key: String, value: Long) = error("disk")
            override fun getString(key: String): String? = error("disk")
            override fun putString(key: String, value: String) = error("disk")
        }
        val choices = HapticChoices(broken)
        assertTrue(choices.enabled.value)
        choices.setEnabled(false)
        assertFalse(choices.enabled.value)
    }

    @Test
    fun settingHolderSwapsToTheAppStore() {
        val store = MemoryKeyValues()
        HapticChoices(store).setEnabled(false)
        HapticSetting.start(store)
        assertFalse(HapticSetting.choices.enabled.value)
        HapticSetting.start(MemoryKeyValues())
        assertTrue(HapticSetting.choices.enabled.value)
    }

    @Test
    fun allowedNeedsTheSwitchAVibratorAndAScreenInUse() {
        assertTrue(HapticRules.allowed(enabled = true, hasVibrator = true, interactive = true))
        assertFalse(HapticRules.allowed(enabled = false, hasVibrator = true, interactive = true))
        assertFalse(HapticRules.allowed(enabled = true, hasVibrator = false, interactive = true))
        assertFalse(HapticRules.allowed(enabled = true, hasVibrator = true, interactive = false))
    }

    @Test
    fun constantsFallBackOnOlderAndroid() {
        assertEquals(HapticRules.TOGGLE_ON, HapticRules.constant(Haptic.ToggleOn, 34))
        assertEquals(HapticRules.VIRTUAL_KEY, HapticRules.constant(Haptic.ToggleOn, 23))
        assertEquals(HapticRules.CLOCK_TICK, HapticRules.constant(Haptic.ToggleOff, 23))
        assertEquals(HapticRules.CONFIRM, HapticRules.constant(Haptic.Confirm, 30))
        assertEquals(HapticRules.VIRTUAL_KEY, HapticRules.constant(Haptic.Confirm, 29))
        assertEquals(HapticRules.TEXT_HANDLE_MOVE, HapticRules.constant(Haptic.FrequentTick, 27))
        assertEquals(HapticRules.CLOCK_TICK, HapticRules.constant(Haptic.FrequentTick, 26))
        assertEquals(HapticRules.GESTURE_END, HapticRules.constant(Haptic.GestureEnd, 30))
        assertNull(HapticRules.constant(Haptic.GestureEnd, 23))
        // Every event has something to play on every supported version but the old release.
        for (sdk in 23..37) for (event in Haptic.entries) {
            if (event == Haptic.GestureEnd && sdk < HapticRules.API_O_MR1) continue
            assertTrue("$event on $sdk", HapticRules.constant(event, sdk) != null)
        }
    }

    @Test
    fun seekMarksTickOnlyAtQuartersAndEnds() {
        val marks = MarkCrossing()
        assertFalse(marks.move(0f)) // the press itself
        assertFalse(marks.move(0.1f)) // leaving the start is not a mark
        assertFalse(marks.move(0.2f))
        assertTrue(marks.move(0.3f)) // past a quarter
        assertFalse(marks.move(0.4f))
        assertTrue(marks.move(0.5f)) // exactly half
        assertFalse(marks.move(0.51f))
        assertTrue(marks.move(1f)) // crosses three quarters and reaches the end: one tick
        assertFalse(marks.move(1f))
        assertTrue(marks.move(0.7f)) // back under three quarters
        assertTrue(marks.move(0f))
        assertFalse(marks.move(null))
        assertFalse(marks.move(0.6f)) // a new drag starts without a tick
        assertFalse(marks.move(Float.NaN))
    }

    @Test
    fun changeTickFollowsTheSlot() {
        val slot = ChangeTick<Int>()
        assertFalse(slot.next(3))
        assertFalse(slot.next(3))
        assertTrue(slot.next(4))
        assertTrue(slot.next(3))
        assertFalse(slot.next(null))
        assertFalse(slot.next(7))
    }

    @Test
    fun enterTickPlaysOncePerPass() {
        val pass = EnterTick()
        assertFalse(pass.next(10f, 72f))
        assertTrue(pass.next(-80f, 72f))
        assertFalse(pass.next(-120f, 72f))
        assertFalse(pass.next(-20f, 72f))
        assertTrue(pass.next(90f, 72f))
        assertFalse(pass.next(0f, 72f))
        assertFalse(pass.next(500f, 0f))
    }
}
