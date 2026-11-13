package com.tmplayer.music.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Scales PCM audio by one factor, the ReplayGain a track asks for. ExoPlayer's own volume stops at
 * 1.0, so a track that needs turning up cannot use it; this works on the samples instead, and
 * clips nothing: results are held to the sample range (the factor itself is already held below the
 * track's peak, see [com.tmplayer.music.library.ReplayGain.factor]).
 *
 * A change of factor ramps over [RAMP_SAMPLES] samples (about 45 ms of stereo at 44.1 kHz) so a
 * track change never clicks. A format it does not know (24 bit and wider PCM that reaches it
 * unconverted) makes it inactive: the audio passes untouched and playback is not affected.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var target = 1f
    private var current = 1f
    private var aimedAt = 1f
    private var step = 0f

    /** Sets the factor for what is played next; 1 is no change. */
    fun setFactor(factor: Float) {
        target = if (factor.isNaN() || factor.isInfinite()) 1f else factor.coerceIn(0f, MAX_FACTOR)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val goal = target
        if (current == goal && goal == 1f && step == 0f) {
            out.put(inputBuffer)
        } else {
            if (goal != aimedAt) {
                aimedAt = goal
                step = if (goal == current) 0f else (goal - current) / RAMP_SAMPLES
            }
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) floats(inputBuffer, out) else shorts(inputBuffer, out)
        }
        out.flip()
    }

    private fun next(): Float {
        if (step != 0f) {
            current += step
            if ((step > 0f && current >= aimedAt) || (step < 0f && current <= aimedAt)) {
                current = aimedAt
                step = 0f
            }
        }
        return current
    }

    private fun shorts(input: ByteBuffer, out: ByteBuffer) {
        var i = input.position()
        val end = input.limit() - 1
        while (i < end) {
            val scaled = (input.getShort(i) * next()).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(scaled.toShort())
            i += 2
        }
        input.position(input.limit())
    }

    private fun floats(input: ByteBuffer, out: ByteBuffer) {
        var i = input.position()
        val end = input.limit() - 3
        while (i < end) {
            out.putFloat((input.getFloat(i) * next()).coerceIn(-1f, 1f))
            i += 4
        }
        input.position(input.limit())
    }

    override fun onReset() {
        // A new playback starts at its factor, not ramping from the last one.
        current = target
        aimedAt = target
        step = 0f
    }

    companion object {
        /** About 3.99, the most the factor of a track can be (+12 dB). */
        private const val MAX_FACTOR = 4f
        const val RAMP_SAMPLES = 4096f
    }
}

/**
 * The renderers with the [gain] stage in front of the audio output. The same FFmpeg fallback
 * decoders as before; only the sink differs, and it is the default sink otherwise.
 */
@OptIn(UnstableApi::class)
class EffectsRenderersFactory(context: Context, private val gain: GainAudioProcessor) : NextRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gain))
            .build()
}
