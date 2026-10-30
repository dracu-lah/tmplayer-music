package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat

/**
 * MPEG audio (MP3, MP2) and ADTS AAC frame headers: sample rate, channels, bitrate, and a
 * duration from a Xing, Info or VBRI header, or estimated from the bitrate and the file size.
 * Written from the ISO 11172-3 and 13818-3 header layout and the published Xing and VBRI fields.
 */
internal object Mpeg {

    /** How far past the tag a first frame is looked for; junk beyond this means it is not MPEG. */
    const val SEARCH_WINDOW = 64 * 1024

    private val bitratesV1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
    )
    private val bitratesV2Layer1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val bitratesV2Layer23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val adtsRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** One MPEG audio frame header. [mpeg1] is MPEG-1; otherwise MPEG-2 or 2.5. */
    class Frame(
        val mpeg1: Boolean,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val mono: Boolean,
        val samplesPerFrame: Int,
        val length: Int,
    )

    /** The MPEG audio frame header at [i], or null when the bytes there are not a valid one. */
    fun frameAt(b: ByteArray, i: Int): Frame? {
        if (!b.fits(i, 4)) return null
        val b1 = b.u8(i + 1)
        if (b.u8(i) != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 3
        val layerBits = (b1 shr 1) and 3
        if (version == 1 || layerBits == 0) return null
        val layer = 4 - layerBits
        val b2 = b.u8(i + 2)
        val bitrateIndex = b2 shr 4
        val rateIndex = (b2 shr 2) and 3
        if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val padding = (b2 shr 1) and 1
        val mpeg1 = version == 3
        val bitrate = when {
            mpeg1 -> bitratesV1[layer - 1][bitrateIndex]
            layer == 1 -> bitratesV2Layer1[bitrateIndex]
            else -> bitratesV2Layer23[bitrateIndex]
        }
        val rate = when (version) {
            3 -> intArrayOf(44100, 48000, 32000)
            2 -> intArrayOf(22050, 24000, 16000)
            else -> intArrayOf(11025, 12000, 8000)
        }[rateIndex]
        val samples = when {
            layer == 1 -> 384
            layer == 2 || mpeg1 -> 1152
            else -> 576
        }
        val length = if (layer == 1) {
            (12 * bitrate * 1000 / rate + padding) * 4
        } else {
            samples / 8 * bitrate * 1000 / rate + padding
        }
        return Frame(mpeg1, layer, bitrate, rate, (b.u8(i + 3) shr 6) == 3, samples, length)
    }

    /**
     * The first MPEG frame at or after [from], confirmed by a second frame right after it when the
     * bytes reach that far, or -1. Looks no further than [SEARCH_WINDOW] bytes.
     */
    fun findFrame(b: ByteArray, from: Int): Int {
        val last = minOf(b.size.toLong() - 4, from.toLong() + SEARCH_WINDOW).toInt()
        var i = maxOf(from, 0)
        while (i <= last) {
            if (b.u8(i) == 0xFF) {
                val f = frameAt(b, i)
                if (f != null && f.length > 4) {
                    val next = i + f.length
                    if (next + 4 > b.size) return i
                    val g = frameAt(b, next)
                    if (g != null && g.sampleRate == f.sampleRate && g.layer == f.layer) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * Reads the MPEG frame at [start] into [out]: format, rate, channels, and a duration and
     * bitrate from a Xing, Info or VBRI header, or for a constant bitrate file from [sizeBytes].
     */
    fun parse(b: ByteArray, start: Int, sizeBytes: Long, trailing: Long, out: TagBuilder) {
        val f = frameAt(b, start) ?: return
        out.format = AudioFormat.MP3
        out.sampleRate = f.sampleRate
        out.channels = if (f.mono) 1 else 2
        val audioBytes = if (sizeBytes > start + trailing) sizeBytes - start - trailing else 0L
        var frames = 0L
        var bytes = 0L
        val sideInfo = if (f.mpeg1) (if (f.mono) 17 else 32) else (if (f.mono) 9 else 17)
        val x = start + 4 + sideInfo
        val vbri = start + 36
        if (b.at(x, "Xing") || b.at(x, "Info")) {
            val flags = b.u32be(x + 4)
            var p = x + 8
            if (flags and 1L != 0L && b.fits(p, 4)) {
                frames = b.u32be(p)
                p += 4
            }
            if (flags and 2L != 0L && b.fits(p, 4)) bytes = b.u32be(p)
        } else if (b.at(vbri, "VBRI") && b.fits(vbri, 18)) {
            bytes = b.u32be(vbri + 10)
            frames = b.u32be(vbri + 14)
        }
        if (frames > 0) {
            val ms = frames * f.samplesPerFrame * 1000 / f.sampleRate
            if (ms > 0) {
                out.durationMs = ms
                val total = if (bytes > 0) bytes else audioBytes
                if (total > 0) out.bitrateKbps = (total * 8 / ms).toInt()
            }
        } else {
            out.bitrateKbps = f.bitrateKbps
            if (audioBytes > 0) out.durationMs = audioBytes * 8 / f.bitrateKbps
        }
    }

    /** Whether the bytes at [i] start an ADTS (raw AAC) frame. */
    fun isAdts(b: ByteArray, i: Int): Boolean =
        b.fits(i, 7) && b.u8(i) == 0xFF && b.u8(i + 1) and 0xF6 == 0xF0 && ((b.u8(i + 2) shr 2) and 0xF) < adtsRates.size

    /**
     * Reads ADTS AAC from [start]: rate and channels from the first header, and a bitrate from the
     * average frame length over the frames the head holds, which gives a duration from [sizeBytes].
     */
    fun parseAdts(b: ByteArray, start: Int, sizeBytes: Long, out: TagBuilder) {
        if (!isAdts(b, start)) return
        out.format = AudioFormat.AAC
        val rate = adtsRates[(b.u8(start + 2) shr 2) and 0xF]
        out.sampleRate = rate
        val ch = ((b.u8(start + 2) and 1) shl 2) or (b.u8(start + 3) shr 6)
        if (ch > 0) out.channels = if (ch == 7) 8 else ch
        var p = start
        var frames = 0
        var bytes = 0L
        while (frames < 500 && isAdts(b, p)) {
            val len = ((b.u8(p + 3) and 3) shl 11) or (b.u8(p + 4) shl 3) or (b.u8(p + 5) shr 5)
            if (len < 7) break
            frames++
            bytes += len
            p += len
        }
        if (frames == 0) return
        val kbps = bytes * 8 * rate / (frames * 1024L) / 1000
        if (kbps <= 0) return
        out.bitrateKbps = kbps.toInt()
        val audioBytes = sizeBytes - start
        if (audioBytes > 0) out.durationMs = audioBytes * 8 / kbps
    }
}
