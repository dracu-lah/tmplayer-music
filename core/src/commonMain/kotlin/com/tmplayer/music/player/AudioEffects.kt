package com.tmplayer.music.player

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Playback speed limits and the pitch that goes with a speed. */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 2f
    const val STEP = 0.05f

    /** The quick picks next to the slider. */
    val QUICK = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** [value] held to [MIN]..[MAX] and rounded to [STEP]; a NaN or an infinity is 1x. */
    fun snap(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return 1f
        val steps = (value.coerceIn(MIN, MAX) / STEP).roundToInt()
        return (steps * STEP).coerceIn(MIN, MAX)
    }

    /** The pitch factor: 1 when the pitch is kept, the speed itself when it is not (a tape going faster). */
    fun pitch(speed: Float, keepPitch: Boolean): Float = if (keepPitch) 1f else snap(speed)

    fun label(speed: Float): String {
        val s = snap(speed)
        val text = if (s == s.roundToInt().toFloat()) s.roundToInt().toString() else ((s * 100).roundToInt() / 100f).toString().trimEnd('0')
        return "${text}x"
    }
}

/** One equalizer preset: [levels] in millibels, one per band. Built-ins have no [name] (the screen words them by [id]). */
data class EqPreset(val id: String, val name: String?, val levels: List<Int>)

/**
 * The equalizer's presets and level handling, in plain numbers so it is tested without a device.
 * The built-in curves are made from the bands' centre frequencies, so they fit a device with five
 * bands or ten; the device's own presets are added by the player service from what the device lists.
 */
object EqPresets {
    const val FLAT = "flat"
    const val BASS = "bass"
    const val TREBLE = "treble"
    const val VOCAL = "vocal"
    const val CUSTOM = "custom"
    private const val DEVICE = "device:"

    fun deviceId(name: String): String = DEVICE + name.take(48)

    /** The curves, gain in dB at [frequencyHz], before they are held to the device's range. */
    private fun curve(id: String, frequencyHz: Double): Double {
        val f = frequencyHz.coerceAtLeast(10.0)
        return when (id) {
            // Up to +6 dB under 60 Hz, fading out by 500 Hz.
            BASS -> 6.0 * (octaves(500.0, f) / octaves(500.0, 60.0)).coerceIn(0.0, 1.0)
            // Up to +6 dB from 8 kHz, fading out by 1 kHz.
            TREBLE -> 6.0 * (octaves(f, 1000.0) / octaves(8000.0, 1000.0)).coerceIn(0.0, 1.0)
            // A lift around 2.5 kHz, a little less low rumble.
            VOCAL -> 4.5 * 2.0.pow(-(octaves(f, 2500.0) * octaves(f, 2500.0)) / 1.6) - 2.0 * (octaves(250.0, f) / 2.5).coerceIn(0.0, 1.0)
            else -> 0.0
        }
    }

    private fun octaves(high: Double, low: Double): Double = ln(high / low) / ln(2.0)

    /** Flat, Bass boost, Treble boost and Vocal for bands centred at [centerHz], within [minMb]..[maxMb]. */
    fun builtIn(centerHz: List<Int>, minMb: Int, maxMb: Int): List<EqPreset> =
        listOf(FLAT, BASS, TREBLE, VOCAL).map { id ->
            EqPreset(id, null, centerHz.map { hz -> (curve(id, hz.toDouble()) * 100).roundToInt().coerceIn(minMb, maxMb) })
        }

    /**
     * The id of the preset whose levels are exactly [levels], else [CUSTOM]. The built-in ones win
     * over a device preset with the same levels, so "Flat" is Flat.
     */
    fun match(levels: List<Int>, presets: List<EqPreset>): String = presets.firstOrNull { it.levels == levels }?.id ?: CUSTOM

    /**
     * [levels] made to fit a device with [bands] bands in [minMb]..[maxMb]: missing bands are 0, extra
     * ones are dropped, each is held to the range. A list of another length than [bands] is a different
     * device's, so it gives all zero when [strict] is set.
     */
    fun fit(levels: List<Int>, bands: Int, minMb: Int, maxMb: Int, strict: Boolean = true): List<Int> {
        if (bands <= 0) return emptyList()
        if (strict && levels.size != bands) return List(bands) { 0.coerceIn(minMb, maxMb) }
        return List(bands) { (levels.getOrNull(it) ?: 0).coerceIn(minMb, maxMb) }
    }

    /**
     * The levels to show and to apply: the saved ones when they are for a device with [bands] bands,
     * else the chosen preset's, else flat.
     */
    fun effective(saved: List<Int>, presetId: String, presets: List<EqPreset>, bands: Int, minMb: Int, maxMb: Int): List<Int> {
        if (saved.size == bands) return fit(saved, bands, minMb, maxMb)
        return fit(presets.firstOrNull { it.id == presetId }?.levels.orEmpty(), bands, minMb, maxMb)
    }

    /** "0,300,-150" for the store. */
    fun encode(levels: List<Int>): String = levels.joinToString(",")

    /** The inverse of [encode]; a bad entry makes the whole list empty, and a very long list is not read. */
    fun decode(text: String?): List<Int> {
        if (text.isNullOrBlank() || text.length > 512) return emptyList()
        val parts = text.split(',')
        if (parts.size > MAX_BANDS) return emptyList()
        return parts.map { it.trim().toIntOrNull() ?: return emptyList() }
    }

    /** No equalizer has more bands than this; a longer saved list is garbage. */
    const val MAX_BANDS = 32

    /** A band's centre frequency as a short label: "60 Hz", "3.6 kHz". */
    fun frequencyLabel(hz: Int): String {
        if (hz < 1000) return "$hz Hz"
        val k = hz / 1000.0
        val text = if (abs(k - k.roundToInt()) < 0.05) k.roundToInt().toString() else ((k * 10).roundToInt() / 10.0).toString()
        return "$text kHz"
    }

    /** A level as dB with one decimal at most: "+3 dB", "-1.5 dB". */
    fun levelLabel(mb: Int): String {
        val db = mb / 100.0
        val text = if (mb % 100 == 0) (mb / 100).toString() else ((mb / 10) / 10.0).toString()
        return (if (db > 0) "+" else "") + text + " dB"
    }
}

/**
 * The user's sound choices, kept across restarts: the equalizer, the playback speed with its keep
 * pitch switch, skip silence and normalise loudness. The screens write them; the player service
 * reads them and applies each at once. Everything is off or neutral until the user turns it on.
 */
class AudioEffectsChoices(private val store: KeyValues) {
    private val _equalizerOn = MutableStateFlow(read { store.getBoolean(EQ_ON, false) } ?: false)
    val equalizerOn: StateFlow<Boolean> = _equalizerOn.asStateFlow()

    private val _preset = MutableStateFlow(read { store.getString(EQ_PRESET) }?.takeIf { it.isNotBlank() && it.length <= 64 } ?: EqPresets.FLAT)

    /** The chosen preset's id, [EqPresets.CUSTOM] once a band was moved by hand. */
    val preset: StateFlow<String> = _preset.asStateFlow()

    private val _levels = MutableStateFlow(EqPresets.decode(read { store.getString(EQ_LEVELS) }))

    /** The band levels in millibels; empty until the user first sets some (the preset's then apply). */
    val levels: StateFlow<List<Int>> = _levels.asStateFlow()

    private val _speed = MutableStateFlow(PlaybackSpeed.snap((read { store.getLong(SPEED, 1000L) } ?: 1000L) / 1000f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _keepPitch = MutableStateFlow(read { store.getBoolean(KEEP_PITCH, true) } ?: true)
    val keepPitch: StateFlow<Boolean> = _keepPitch.asStateFlow()

    private val _skipSilence = MutableStateFlow(read { store.getBoolean(SKIP_SILENCE, false) } ?: false)
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    private val _normalise = MutableStateFlow(read { store.getBoolean(NORMALISE, false) } ?: false)
    val normalise: StateFlow<Boolean> = _normalise.asStateFlow()

    fun setEqualizerOn(on: Boolean) {
        _equalizerOn.value = on
        write { store.putBoolean(EQ_ON, on) }
    }

    /** Chooses a preset; [levels] are its band levels, kept so the next start does not have to ask the device. */
    fun choosePreset(id: String, levels: List<Int>) {
        _preset.value = id
        _levels.value = levels
        write {
            store.putString(EQ_PRESET, id)
            store.putString(EQ_LEVELS, EqPresets.encode(levels))
        }
    }

    /** Sets the bands by hand: the preset becomes [EqPresets.CUSTOM] unless [matching] names the one these levels are. */
    fun setLevels(levels: List<Int>, matching: String = EqPresets.CUSTOM) = choosePreset(matching, levels)

    fun setSpeed(value: Float) {
        val snapped = PlaybackSpeed.snap(value)
        _speed.value = snapped
        write { store.putLong(SPEED, (snapped * 1000).roundToInt().toLong()) }
    }

    fun setKeepPitch(on: Boolean) {
        _keepPitch.value = on
        write { store.putBoolean(KEEP_PITCH, on) }
    }

    fun setSkipSilence(on: Boolean) {
        _skipSilence.value = on
        write { store.putBoolean(SKIP_SILENCE, on) }
    }

    fun setNormalise(on: Boolean) {
        _normalise.value = on
        write { store.putBoolean(NORMALISE, on) }
    }

    private inline fun <T> read(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A store that cannot be written keeps the choice for this run only.
        }
    }

    private companion object {
        const val EQ_ON = "fx.eq.on"
        const val EQ_PRESET = "fx.eq.preset"
        const val EQ_LEVELS = "fx.eq.levels"
        const val SPEED = "fx.speed"
        const val KEEP_PITCH = "fx.keepPitch"
        const val SKIP_SILENCE = "fx.skipSilence"
        const val NORMALISE = "fx.normalise"
    }
}

/**
 * Where the app keeps its [AudioEffectsChoices]: in memory (all neutral) until [start] gives it the
 * app's store, which the app does first thing, before the player service can run.
 */
object AudioEffectsSetting {
    @Volatile
    var choices: AudioEffectsChoices = AudioEffectsChoices(MemoryKeyValues())
        private set

    fun start(store: KeyValues) {
        choices = AudioEffectsChoices(store)
    }
}
