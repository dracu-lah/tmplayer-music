package com.tmplayer.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class GainAudioProcessorTest {

    private fun processor(encoding: Int): GainAudioProcessor =
        GainAudioProcessor().also {
            it.configure(AudioFormat(44_100, 2, encoding))
            it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    private fun shorts(vararg values: Int): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder()).also { b ->
            values.forEach { b.putShort(it.toShort()) }
            b.flip()
        }

    private fun GainAudioProcessor.run(input: ByteBuffer): ByteBuffer {
        queueInput(input)
        return output
    }

    private fun ByteBuffer.shorts(): List<Int> = List(remaining() / 2) { getShort(position() + it * 2).toInt() }

    @Test
    fun noGainPassesTheSamplesUntouched() {
        val p = processor(C.ENCODING_PCM_16BIT)
        assertEquals(listOf(100, -200, 32767, -32768), p.run(shorts(100, -200, 32767, -32768)).shorts())
    }

    @Test
    fun aFactorScalesAfterItsRampAndNeverWraps() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.setFactor(0.5f)
        // Run past the ramp (4096 samples), then look at a block.
        p.run(shorts(*IntArray(8192) { 1000 }))
        val steady = p.run(shorts(1000, -1000, 32767, -32768)).shorts()
        assertEquals(listOf(500, -500, 16384, -16384), steady)
        p.setFactor(4f)
        p.run(shorts(*IntArray(16384) { 1000 }))
        val loud = p.run(shorts(1000, 20000, -20000)).shorts()
        assertEquals(listOf(4000, 32767, -32768), loud)
    }

    @Test
    fun aChangeRampsInsteadOfJumping() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.setFactor(0.25f)
        val first = p.run(shorts(*IntArray(64) { 10_000 })).shorts()
        assertTrue("starts near full level: ${first.first()}", first.first() > 9_900)
        assertTrue("moves down: ${first.last()} < ${first.first()}", first.last() < first.first())
        assertTrue(first.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun floatAudioIsScaledAndHeldToFullScale() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        p.setFactor(2f)
        val warm = ByteBuffer.allocateDirect(4 * 8192).order(ByteOrder.nativeOrder())
        repeat(8192) { warm.putFloat(0.1f) }
        warm.flip()
        p.run(warm)
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).putFloat(0.25f).putFloat(0.9f).flip()
        val out = p.run(input as ByteBuffer)
        assertEquals(0.5f, out.getFloat(out.position()), 0.0001f)
        assertEquals(1f, out.getFloat(out.position() + 4), 0f)
    }

    @Test
    fun anUnknownFormatMakesItInactiveInsteadOfFailingPlayback() {
        val p = GainAudioProcessor()
        p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        assertFalse(p.isActive)
        p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertTrue(p.isActive)
    }

    @Test
    fun nonsenseFactorsAreHeldOrIgnored() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.setFactor(Float.NaN)
        assertEquals(listOf(1000), p.run(shorts(1000)).shorts())
        p.setFactor(-5f)
        p.run(shorts(*IntArray(8192) { 1000 }))
        assertEquals(listOf(0), p.run(shorts(1000)).shorts())
    }

    @Test
    fun anOddByteCountDropsTheHalfSampleWithoutThrowing() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.setFactor(0.5f)
        val b = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        b.putShort(400).put(1).flip()
        p.queueInput(b)
        assertTrue(p.output.remaining() in 2..3)
    }
}
