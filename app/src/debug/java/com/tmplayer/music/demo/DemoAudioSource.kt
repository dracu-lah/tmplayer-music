package com.tmplayer.music.demo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.tmplayer.music.player.tdIsThumb
import com.tmplayer.music.player.tdTrackOf
import java.io.IOException
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The demo's audio: every `tdfile` track is a WAV made on the fly, a quiet soft chord exactly as long
 * as the track's invented duration ([DemoCatalog.durationOf]), so the seek bar, the times and the
 * mini-player show the demo's own lengths. Nothing is stored and nothing asks Telegram: the bytes of
 * any range are computed when read, so seeking anywhere is instant. Debug builds only.
 */
@UnstableApi
internal class DemoAudioSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var end = 0L
    private var dataBytes = 0L
    private var root = 220.0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val track = tdTrackOf(dataSpec.uri)
        if (track == null || tdIsThumb(dataSpec.uri)) throw IOException("the demo has no such file")
        transferInitializing(dataSpec)
        val ms = DemoCatalog.durationOf(track) ?: FALLBACK_MS
        dataBytes = ms * RATE / 1_000 * 2
        root = ROOTS[(track.messageId / 1_048_576L).mod(ROOTS.size.toLong()).toInt()]
        val total = HEADER + dataBytes
        if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) min(total, position + dataSpec.length) else total
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        val n = min(length.toLong(), end - position).toInt()
        for (i in 0 until n) buffer[offset + i] = byteAt(position + i)
        position += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** One byte of the file: the 44 byte RIFF header, then 16 bit mono samples, little endian. */
    private fun byteAt(at: Long): Byte {
        if (at < HEADER) return header(at.toInt())
        val offset = at - HEADER
        val sample = sample(offset / 2)
        return (if (offset % 2 == 0L) sample and 0xFF else (sample shr 8) and 0xFF).toByte()
    }

    private fun sample(index: Long): Int {
        val t = index.toDouble() / RATE
        // A slow swell over 8 s, so it sounds like a pad rather than a test tone.
        val swell = 0.55 + 0.45 * sin(2 * PI * t / 8.0)
        val v = sin(2 * PI * root * t) + 0.6 * sin(2 * PI * root * 1.5 * t) + 0.4 * sin(2 * PI * root * 2.0 * t)
        return (v / 2.0 * swell * AMPLITUDE * Short.MAX_VALUE).toInt()
    }

    private fun header(i: Int): Byte {
        val riff = 36 + dataBytes
        val v: Int = when (i) {
            in 0..3 -> "RIFF"[i].code
            in 4..7 -> ((riff shr (8 * (i - 4))) and 0xFF).toInt()
            in 8..11 -> "WAVE"[i - 8].code
            in 12..15 -> "fmt "[i - 12].code
            16 -> 16
            20 -> 1 // PCM
            22 -> 1 // mono
            in 24..27 -> (RATE shr (8 * (i - 24))) and 0xFF
            in 28..31 -> ((RATE * 2) shr (8 * (i - 28))) and 0xFF
            32 -> 2 // block align
            34 -> 16 // bits per sample
            in 36..39 -> "data"[i - 36].code
            in 40..43 -> ((dataBytes shr (8 * (i - 40))) and 0xFF).toInt()
            else -> 0
        }
        return v.toByte()
    }

    private companion object {
        const val HEADER = 44L
        const val RATE = 16_000
        const val FALLBACK_MS = 180_000L

        /** About -30 dB: audible in a quiet room, never loud. */
        const val AMPLITUDE = 0.04

        val ROOTS = doubleArrayOf(196.0, 220.0, 246.94, 261.63, 293.66)
    }
}
