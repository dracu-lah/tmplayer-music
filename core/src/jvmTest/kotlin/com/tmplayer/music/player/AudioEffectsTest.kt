package com.tmplayer.music.player

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsTest {

    private val fiveBands = listOf(60, 230, 910, 3600, 14000)

    @Test
    fun speedIsHeldToItsRangeAndSnapped() {
        assertEquals(0.5f, PlaybackSpeed.snap(0.1f), 0f)
        assertEquals(2f, PlaybackSpeed.snap(9f), 0f)
        assertEquals(1.25f, PlaybackSpeed.snap(1.2312f), 0.0001f)
        assertEquals(1f, PlaybackSpeed.snap(Float.NaN), 0f)
        assertEquals(1f, PlaybackSpeed.snap(Float.POSITIVE_INFINITY), 0f)
        assertEquals("1x", PlaybackSpeed.label(1f))
        assertEquals("1.25x", PlaybackSpeed.label(1.25f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
        assertEquals("2x", PlaybackSpeed.label(2f))
    }

    @Test
    fun pitchFollowsTheSpeedOnlyWhenNotKept() {
        assertEquals(1f, PlaybackSpeed.pitch(1.5f, keepPitch = true), 0f)
        assertEquals(1.5f, PlaybackSpeed.pitch(1.5f, keepPitch = false), 0f)
    }

    @Test
    fun builtInPresetsFitTheBandsAndTheRange() {
        val presets = EqPresets.builtIn(fiveBands, -1500, 1500)
        assertEquals(listOf("flat", "bass", "treble", "vocal"), presets.map { it.id })
        assertTrue(presets.all { it.levels.size == 5 && it.levels.all { l -> l in -1500..1500 } })
        assertEquals(List(5) { 0 }, presets[0].levels)
        // Bass boost lifts the lowest band and leaves the highest alone; treble the other way round.
        assertTrue(presets[1].levels.first() > 400 && presets[1].levels.last() == 0)
        assertTrue(presets[2].levels.last() > 400 && presets[2].levels.first() == 0)
        // Vocal lifts the middle most.
        assertTrue(presets[3].levels[3] > presets[3].levels[0] && presets[3].levels[3] > presets[3].levels[4])
    }

    @Test
    fun aNarrowDeviceRangeHoldsThePresets() {
        val presets = EqPresets.builtIn(fiveBands, -300, 300)
        assertTrue(presets.all { p -> p.levels.all { it in -300..300 } })
        assertEquals(300, presets[1].levels.first())
    }

    @Test
    fun tenBandsGetTenLevels() {
        val ten = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        assertTrue(EqPresets.builtIn(ten, -1200, 1200).all { it.levels.size == 10 })
    }

    @Test
    fun fitHandlesAnotherDevicesBands() {
        assertEquals(listOf(100, 200), EqPresets.fit(listOf(100, 200), 2, -1500, 1500))
        assertEquals(listOf(0, 0, 0), EqPresets.fit(listOf(100, 200), 3, -1500, 1500))
        assertEquals(listOf(1500, -1500), EqPresets.fit(listOf(9000, -9000), 2, -1500, 1500))
        assertEquals(emptyList<Int>(), EqPresets.fit(listOf(1), 0, -1, 1))
    }

    @Test
    fun effectiveLevelsPreferTheSavedOnesThenThePreset() {
        val presets = EqPresets.builtIn(fiveBands, -1500, 1500)
        assertEquals(listOf(1, 2, 3, 4, 5), EqPresets.effective(listOf(1, 2, 3, 4, 5), "custom", presets, 5, -1500, 1500))
        assertEquals(presets[1].levels, EqPresets.effective(emptyList(), "bass", presets, 5, -1500, 1500))
        // Saved for a ten band device, preset gone: flat.
        assertEquals(List(5) { 0 }, EqPresets.effective(List(10) { 5 }, "device:gone", presets, 5, -1500, 1500))
    }

    @Test
    fun matchFindsAPresetOrCustom() {
        val presets = EqPresets.builtIn(fiveBands, -1500, 1500)
        assertEquals("bass", EqPresets.match(presets[1].levels, presets))
        assertEquals("custom", EqPresets.match(listOf(1, 2, 3, 4, 5), presets))
    }

    @Test
    fun levelsEncodeAndDecodeAndGarbageIsEmpty() {
        assertEquals(listOf(0, 300, -150), EqPresets.decode(EqPresets.encode(listOf(0, 300, -150))))
        for (bad in listOf(null, "", "1,x,3", "1,,3", "9".repeat(600), List(40) { 1 }.joinToString(","))) {
            assertEquals("[$bad]", emptyList<Int>(), EqPresets.decode(bad))
        }
    }

    @Test
    fun labels() {
        assertEquals("60 Hz", EqPresets.frequencyLabel(60))
        assertEquals("3.6 kHz", EqPresets.frequencyLabel(3600))
        assertEquals("14 kHz", EqPresets.frequencyLabel(14000))
        assertEquals("+3 dB", EqPresets.levelLabel(300))
        assertEquals("-1.5 dB", EqPresets.levelLabel(-150))
        assertEquals("0 dB", EqPresets.levelLabel(0))
    }

    @Test
    fun choicesStartNeutralAndAreRemembered() {
        val store = MemoryKeyValues()
        val first = AudioEffectsChoices(store)
        assertFalse(first.equalizerOn.value)
        assertFalse(first.skipSilence.value)
        assertFalse(first.normalise.value)
        assertTrue(first.keepPitch.value)
        assertEquals(1f, first.speed.value, 0f)
        assertEquals(EqPresets.FLAT, first.preset.value)
        assertEquals(emptyList<Int>(), first.levels.value)

        first.setEqualizerOn(true)
        first.setSpeed(1.5f)
        first.setKeepPitch(false)
        first.setSkipSilence(true)
        first.setNormalise(true)
        first.choosePreset("bass", listOf(600, 300, 0, 0, 0))

        val again = AudioEffectsChoices(store)
        assertTrue(again.equalizerOn.value)
        assertEquals(1.5f, again.speed.value, 0f)
        assertFalse(again.keepPitch.value)
        assertTrue(again.skipSilence.value)
        assertTrue(again.normalise.value)
        assertEquals("bass", again.preset.value)
        assertEquals(listOf(600, 300, 0, 0, 0), again.levels.value)
    }

    @Test
    fun aSpeedOutsideTheRangeIsHeldWhenSetAndWhenRead() {
        val c = AudioEffectsChoices(MemoryKeyValues())
        c.setSpeed(40f)
        assertEquals(2f, c.speed.value, 0f)
        c.setSpeed(-3f)
        assertEquals(0.5f, c.speed.value, 0f)
        val store = MemoryKeyValues().apply { putLong("fx.speed", 99_000L) }
        assertEquals(2f, AudioEffectsChoices(store).speed.value, 0f)
        store.putLong("fx.speed", -5L)
        assertEquals(0.5f, AudioEffectsChoices(store).speed.value, 0f)
    }

    @Test
    fun aStoreThatThrowsNeverBreaksTheChoices() {
        val broken = object : KeyValues {
            override fun getBoolean(key: String, default: Boolean): Boolean = error("no")
            override fun putBoolean(key: String, value: Boolean) = error("no")
            override fun getLong(key: String, default: Long): Long = error("no")
            override fun putLong(key: String, value: Long) = error("no")
            override fun getString(key: String): String? = error("no")
            override fun putString(key: String, value: String) = error("no")
        }
        val c = AudioEffectsChoices(broken)
        assertEquals(1f, c.speed.value, 0f)
        c.setNormalise(true)
        c.setSpeed(1.25f)
        c.choosePreset("flat", listOf(0))
        assertTrue(c.normalise.value)
        assertEquals(1.25f, c.speed.value, 0f)
    }

    @Test
    fun aSavedPresetNameThatIsGarbageFallsBackToFlat() {
        val store = MemoryKeyValues().apply {
            putString("fx.eq.preset", "x".repeat(500))
            putString("fx.eq.levels", "not,numbers")
        }
        val c = AudioEffectsChoices(store)
        assertEquals(EqPresets.FLAT, c.preset.value)
        assertEquals(emptyList<Int>(), c.levels.value)
    }
}
