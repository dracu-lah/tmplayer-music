package com.tmplayer.music.desktop.player

import com.tmplayer.music.player.EqPresets
import com.tmplayer.music.player.PlaybackSpeed
import java.util.Locale
import kotlin.math.abs

/**
 * What the user chose for the sound (the same choices the phone keeps, [com.tmplayer.music.player.AudioEffectsChoices]),
 * as the desktop's player takes them. [eqLevelsMb] are millibels, one per band of [SoundGraph.EQ_BANDS_HZ].
 */
data class SoundSettings(
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val equalizerOn: Boolean = false,
    val eqLevelsMb: List<Int> = emptyList(),
    val skipSilence: Boolean = false,
    val normalise: Boolean = false,
) {
    companion object {
        val NEUTRAL = SoundSettings()
    }
}

/**
 * What the engine can honour; a control the engine cannot is not offered, never a crash. The FFmpeg
 * inside mediamp's libmpv is a small build (only `aformat`, `anull`, `aresample` and `atrim` among
 * the audio filters, found on 2026-11-14), so the equalizer and skip silence are offered only where
 * the filters they need are really in the library ([FilterProbe]); the speed and the loudness gain
 * are mpv's own properties.
 */
data class EngineCapabilities(
    /** `speed` and `audio-pitch-correction`. */
    val speed: Boolean = false,
    /** mpv's `volume-gain`, for the ReplayGain of the track. */
    val gain: Boolean = false,
    /** The `equalizer` filter. */
    val equalizer: Boolean = false,
    /** The `silenceremove` filter. */
    val skipSilence: Boolean = false,
) {
    companion object {
        val NONE = EngineCapabilities()
    }
}

/**
 * The sound settings as mpv's own properties: plain strings, so the mapping is a unit test and the
 * engine only hands them over. The equalizer is ffmpeg's `equalizer` filter, one per band, inside a
 * single `lavfi` filter; skip silence is `silenceremove`; the loudness gain of a track is `volume`.
 */
object SoundGraph {
    /** Ten graphic bands, an octave apart. The phone's presets are made from band centres, so they fit these. */
    val EQ_BANDS_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** A band moves this far either way, in millibels (12 dB). */
    const val EQ_RANGE_MB = 1200

    /** A loudness factor this close to 1 is not worth applying. */
    private const val GAIN_EPS = 0.005f

    /** mpv's `volume-gain` in decibels for a loudness [gain] factor; 0 for none, a bad factor or normalise being off. */
    fun gainDb(settings: SoundSettings, gain: Float): Double {
        if (!settings.normalise || !gain.isFinite() || gain <= 0f || abs(gain - 1f) <= GAIN_EPS) return 0.0
        return (20.0 * Math.log10(gain.toDouble())).coerceIn(-24.0, 12.0)
    }

    /** The `speed` property: held to the app's range, 1 when it is not a number. */
    fun speed(settings: SoundSettings): Double = PlaybackSpeed.snap(settings.speed).toDouble()

    /** The levels to apply: the saved ones fitted to our bands (a list of another length is another device's, so flat). */
    fun levels(settings: SoundSettings): List<Int> =
        EqPresets.fit(settings.eqLevelsMb, EQ_BANDS_HZ.size, -EQ_RANGE_MB, EQ_RANGE_MB)

    /**
     * The `af` property for [settings] with what the library can do ([caps]). Empty when nothing
     * needs filtering, which clears the chain.
     */
    fun filters(settings: SoundSettings, caps: EngineCapabilities): String {
        val parts = ArrayList<String>()
        if (settings.equalizerOn && caps.equalizer) {
            val levels = levels(settings)
            EQ_BANDS_HZ.forEachIndexed { i, hz ->
                val mb = levels.getOrNull(i) ?: 0
                if (mb != 0) parts += "equalizer=f=$hz:t=o:w=1:g=${num(mb / 100.0)}"
            }
        }
        if (settings.skipSilence && caps.skipSilence) {
            parts += "silenceremove=start_periods=0:stop_periods=-1:stop_duration=0.5:stop_threshold=-50dB"
        }
        if (parts.isEmpty()) return ""
        // mpv's length prefixed string (%length%text) passes the graph through whatever mpv's own
        // list syntax would make of its commas and colons; every build of libmpv reads it alike.
        val graph = parts.joinToString(",")
        return "lavfi=graph=%${graph.length}%$graph"
    }

    /** A number as ffmpeg reads it: a dot, never a locale's comma, no needless digits. */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}
