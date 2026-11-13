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
 * A change of factor ramps over [RAMP_FRAMES] frames (about 45 ms at 44.1 kHz), one step per frame
 * so every channel of a frame has the same gain, and a track change never clicks. A track change
 * inside a queue does not ramp at all: the engine hands over the next item's factor with [setNext],
 * and it takes hold at the stream boundary (the end of the last stream), before the next track's
 * first buffer, so there is no stretch at the old gain. A format it does not know (24 bit and
 * wider PCM that reaches it unconverted) makes it inactive: the audio passes untouched.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var target = 1f

    /** The factor the next stream starts at, or NaN for none; read and cleared at the stream boundary. */
    @Volatile
    private var next = Float.NaN

    // Only the playback thread touches these.
    private var current = 1f
    private var aimedAt = 1f
    private var step = 0f

    /** Sets the factor for what is played next; 1 is no change. */
    fun setFactor(factor: Float) {
        target = held(factor)
    }

    /** The factor the stream after this one starts at, applied at the boundary between them; null for none. */
    fun setNext(factor: Float?) {
        next = if (factor == null) Float.NaN else held(factor)
    }

    private fun held(factor: Float) = if (factor.isNaN() || factor.isInfinite()) 1f else factor.coerceIn(0f, MAX_FACTOR)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    override fun onQueueEndOfStream() {
        // Everything of this stream has been processed; what comes next is the next track, at its own gain.
        val upcoming = next
        if (upcoming.isNaN()) return
        next = Float.NaN
        target = upcoming
        current = upcoming
        aimedAt = upcoming
        step = 0f
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
                step = if (goal == current) 0f else (goal - current) / RAMP_FRAMES
            }
            val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) floats(inputBuffer, out, channels) else shorts(inputBuffer, out, channels)
        }
        out.flip()
    }

    /** The gain of the next frame: every channel of a frame shares it, so the ramp advances once per frame. */
    private fun advance(): Float {
        if (step != 0f) {
            current += step
            if ((step > 0f && current >= aimedAt) || (step < 0f && current <= aimedAt)) {
                current = aimedAt
                step = 0f
            }
        }
        return current
    }

    private fun shorts(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        var i = input.position()
        val limit = input.limit()
        while (i + 2 <= limit) {
            // A cut-off last frame keeps the gain of the last whole one.
            val gain = if (i + 2 * channels <= limit) advance() else current
            val count = minOf(channels, (limit - i) / 2)
            for (c in 0 until count) {
                val scaled = (input.getShort(i) * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(scaled.toShort())
                i += 2
            }
        }
        input.position(limit)
    }

    private fun floats(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        var i = input.position()
        val limit = input.limit()
        while (i + 4 <= limit) {
            val gain = if (i + 4 * channels <= limit) advance() else current
            val count = minOf(channels, (limit - i) / 4)
            for (c in 0 until count) {
                out.putFloat((input.getFloat(i) * gain).coerceIn(-1f, 1f))
                i += 4
            }
        }
        input.position(limit)
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
        const val RAMP_FRAMES = 2048f
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
