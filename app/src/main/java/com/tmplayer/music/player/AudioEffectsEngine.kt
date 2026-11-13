package com.tmplayer.music.player

import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tmplayer.music.library.ReplayGain
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the device's equalizer offers: its bands (centre frequency in Hz) and level range in millibels. */
data class EqualizerInfo(
    val centerHz: List<Int>,
    val minMb: Int,
    val maxMb: Int,
    /** Flat, Bass boost, Treble boost and Vocal, then the device's own presets. */
    val presets: List<EqPreset>,
)

/** Read by the effects sheet: null while there is no equalizer to show (none on this device, or it failed to start). */
object AudioEffectsState {
    private val _equalizer = MutableStateFlow<EqualizerInfo?>(null)
    val equalizer: StateFlow<EqualizerInfo?> = _equalizer.asStateFlow()

    internal fun publish(info: EqualizerInfo?) {
        _equalizer.value = info
    }

    /** The extra on a media item that carries its ReplayGain ([ReplayGain.encode]). */
    const val GAIN_KEY = "tmplayer.replayGain"
}

/**
 * Carries the user's sound choices ([AudioEffectsChoices]) out on [player], for the life of [scope]
 * (the service's): the equalizer on the player's audio session, the speed with or without pitch
 * change, skip silence and the ReplayGain factor of the item that plays. Every effect is optional:
 * a device with no audio effects, or an effect that will not start, only leaves it out (the
 * equalizer then reads as unavailable) and never stops playback.
 */
@OptIn(UnstableApi::class)
class AudioEffectsEngine(
    private val player: ExoPlayer,
    private val gain: GainAudioProcessor,
    private val scope: CoroutineScope,
    private val choices: AudioEffectsChoices = AudioEffectsSetting.choices,
) {
    private var equalizer: Equalizer? = null
    private var info: EqualizerInfo? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private val jobs = ArrayList<Job>()

    fun start() {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = openEqualizer(audioSessionId)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyGain()

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = applyGain()

            // What plays next changes with these.
            override fun onRepeatModeChanged(repeatMode: Int) = applyGain()

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = applyGain()
        })
        openEqualizer(player.audioSessionId)
        jobs += scope.launch {
            combine(choices.speed, choices.keepPitch) { speed, keep -> PlaybackParameters(speed, PlaybackSpeed.pitch(speed, keep)) }
                .collect { runCatching { player.playbackParameters = it } }
        }
        jobs += scope.launch { choices.skipSilence.collect { runCatching { player.skipSilenceEnabled = it } } }
        jobs += scope.launch { choices.normalise.collect { applyGain() } }
        jobs += scope.launch {
            combine(choices.equalizerOn, choices.preset, choices.levels) { on, preset, levels -> Triple(on, preset, levels) }
                .collect { applyEqualizer() }
        }
    }

    fun release() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        closeEqualizer()
    }

    /**
     * The factor of the item that plays now, or 1 when the switch is off or the item has no gain.
     * The sink works ahead of the position, so the change of item is seen late: the next item's
     * factor is also handed to the processor, which takes it at the boundary between the two
     * streams, before the next track's first buffer. This is the fallback when that boundary never
     * comes (a skip, a seek, a new queue): the factor of the item that plays then ramps in.
     */
    private fun applyGain() {
        val on = choices.normalise.value
        gain.setFactor(if (on) factorOf(player.currentMediaItem) else 1f)
        val upcoming = player.nextMediaItemIndex
        gain.setNext(
            when {
                !on -> 1f
                upcoming == C.INDEX_UNSET || upcoming !in 0 until player.mediaItemCount -> null
                else -> factorOf(player.getMediaItemAt(upcoming))
            },
        )
    }

    private fun openEqualizer(session: Int) {
        if (session == sessionId && equalizer != null) return
        closeEqualizer()
        sessionId = session
        // Session 0 is the global mix: never put an effect there.
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        // Held outside the try so every failure path can release it.
        var eq: Equalizer? = null
        try {
            val made = Equalizer(0, session)
            eq = made
            val bands = made.numberOfBands.toInt()
            val range = made.bandLevelRange
            if (bands <= 0 || bands > EqPresets.MAX_BANDS || range.size < 2) {
                runCatching { made.release() }
                return
            }
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()
            val centers = List(bands) { (made.getCenterFreq(it.toShort()) / 1000).coerceAtLeast(1) }
            val device = ArrayList<EqPreset>()
            for (i in 0 until made.numberOfPresets.toInt()) {
                val name = runCatching { made.getPresetName(i.toShort()) }.getOrNull()?.trim().orEmpty()
                if (name.isEmpty()) continue
                made.usePreset(i.toShort())
                device += EqPreset(EqPresets.deviceId(name), name, List(bands) { made.getBandLevel(it.toShort()).toInt() })
            }
            val builtIn = EqPresets.builtIn(centers, minMb, maxMb)
            // A device preset that is just flat or one of ours adds nothing.
            val extra = device.filter { d -> builtIn.none { it.levels == d.levels } }.distinctBy { it.id }
            equalizer = made
            info = EqualizerInfo(centers, minMb, maxMb, builtIn + extra)
            AudioEffectsState.publish(info)
            applyEqualizer()
        } catch (e: Throwable) {
            // No audio effects here (a TV box without them, an effect limit reached): the equalizer is just hidden.
            Logger.w(TAG, "the equalizer could not start: ${e.javaClass.simpleName}")
            runCatching { eq?.release() }
            closeEqualizer()
        }
    }

    private fun closeEqualizer() {
        runCatching { equalizer?.release() }
        equalizer = null
        info = null
        AudioEffectsState.publish(null)
    }

    private fun applyEqualizer() {
        val eq = equalizer ?: return
        val details = info ?: return
        try {
            val levels = levelsFor(details)
            for (band in levels.indices) eq.setBandLevel(band.toShort(), levels[band].toShort())
            eq.setEnabled(choices.equalizerOn.value)
        } catch (e: Throwable) {
            Logger.w(TAG, "setting the equalizer failed: ${e.javaClass.simpleName}")
            closeEqualizer()
        }
    }

    private fun levelsFor(details: EqualizerInfo): List<Int> =
        EqPresets.effective(choices.levels.value, choices.preset.value, details.presets, details.centerHz.size, details.minMb, details.maxMb)

    companion object {
        private const val TAG = "AudioEffects"

        /** The ReplayGain factor of [item], from the gain it carries; 1 for no item or no tags. */
        fun factorOf(item: MediaItem?): Float =
            ReplayGain.decode(item?.mediaMetadata?.extras?.getString(AudioEffectsState.GAIN_KEY)).factor()
    }
}
