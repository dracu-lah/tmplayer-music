package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat
import kotlin.math.roundToLong

/**
 * WAV (RIFF, little-endian chunks) and AIFF or AIFC (IFF, big-endian chunks): the format chunk for
 * rate, depth and channels, the audio chunk's size for the duration, the text chunks, and an ID3
 * chunk when a tagger added one. Chunks are padded to an even length.
 */
internal object Riff {

    private const val MAX_CHUNKS = 1024

    fun wav(b: ByteArray, sizeBytes: Long, out: TagBuilder) {
        out.format = AudioFormat.WAV
        var byteRate = 0L
        var dataSize = -1L
        var dataStart = 0
        var p = 12
        var n = 0
        while (p + 8 <= b.size && n++ < MAX_CHUNKS) {
            val id = b.ascii(p, 4)
            val size = b.u32le(p + 4)
            val ds = p + 8
            val de = minOf(ds + size, b.size.toLong()).toInt()
            when (id) {
                "fmt " -> if (de - ds >= 16) {
                    out.channels = b.u16le(ds + 2)
                    val rate = b.u32le(ds + 4)
                    if (rate in 1..10_000_000) out.sampleRate = rate.toInt()
                    byteRate = b.u32le(ds + 8)
                    out.bitDepth = b.u16le(ds + 14)
                }
                "data" -> {
                    dataSize = size
                    dataStart = ds
                }
                "LIST" -> if (b.at(ds, "INFO")) info(b, ds + 4, de, out)
                "id3 ", "ID3 " -> Id3.parseV2(b, ds, out)
            }
            val next = ds + size + (size and 1)
            if (next > b.size) break
            p = next.toInt()
        }
        if (byteRate == 0L) byteRate = out.sampleRate.toLong() * out.channels * out.bitDepth / 8
        if (byteRate <= 0) return
        out.bitrateKbps = (byteRate * 8 / 1000).toInt()
        val audio = when {
            dataSize in 1L until 0xFFFFFFFFL && (sizeBytes <= 0 || dataSize <= sizeBytes) -> dataSize
            dataStart > 0 && sizeBytes > dataStart -> sizeBytes - dataStart
            else -> 0
        }
        if (audio > 0) out.durationMs = audio * 1000 / byteRate
    }

    /** LIST/INFO sub-chunks: zero-terminated text under four letter ids. */
    private fun info(b: ByteArray, s: Int, e: Int, out: TagBuilder) {
        var p = s
        var n = 0
        while (p + 8 <= e && n++ < MAX_CHUNKS) {
            val id = b.ascii(p, 4)
            val size = b.u32le(p + 4)
            val ds = p + 8
            if (size > e - ds) return
            val text = { TextDecoding.decodeLegacy(b.copyOfRange(ds, b.indexOfByte(0, ds, ds + size.toInt()).coerceAtMost(ds + size.toInt()))) }
            when (id) {
                "INAM" -> out.title(text())
                "IART" -> out.artist(text())
                "IPRD" -> out.album(text())
                "ICRD" -> out.year(text())
                "ITRK", "IPRT" -> out.track(text())
            }
            p = ds + size.toInt() + (size.toInt() and 1)
        }
    }

    fun aiff(b: ByteArray, out: TagBuilder) {
        out.format = AudioFormat.AIFF
        var frames = 0L
        var p = 12
        var n = 0
        while (p + 8 <= b.size && n++ < MAX_CHUNKS) {
            val id = b.ascii(p, 4)
            val size = b.u32be(p + 4)
            val ds = p + 8
            val de = minOf(ds + size, b.size.toLong()).toInt()
            when (id) {
                "COMM" -> if (de - ds >= 18) {
                    out.channels = b.u16be(ds)
                    frames = b.u32be(ds + 2)
                    out.bitDepth = b.u16be(ds + 6)
                    val rate = extended(b, ds + 8)
                    if (rate in 1.0..10_000_000.0) out.sampleRate = rate.roundToLong().toInt()
                }
                "NAME" -> out.title(TextDecoding.decodeLegacy(b.copyOfRange(ds, de)))
                "AUTH" -> out.artist(TextDecoding.decodeLegacy(b.copyOfRange(ds, de)))
                "ID3 ", "id3 " -> Id3.parseV2(b, ds, out)
            }
            val next = ds + size + (size and 1)
            if (next > b.size) break
            p = next.toInt()
        }
        if (out.sampleRate > 0 && frames > 0) out.durationMs = frames * 1000 / out.sampleRate
        val bitrate = out.sampleRate.toLong() * out.channels * out.bitDepth / 1000
        if (bitrate in 1..Int.MAX_VALUE) out.bitrateKbps = bitrate.toInt()
    }

    /** An 80 bit IEEE 754 extended float (AIFF's sample rate), or 0 when it is not a sane one. */
    internal fun extended(b: ByteArray, i: Int): Double {
        val signExp = b.u16be(i)
        val mantissa = b.u64be(i + 2)
        if (signExp and 0x8000 != 0) return 0.0
        val exp = signExp and 0x7FFF
        if (exp == 0 || exp == 0x7FFF || mantissa == 0L) return 0.0
        val m = (mantissa ushr 1).toDouble() * 2 + (mantissa and 1)
        return Math.scalb(m, exp - 16383 - 63)
    }
}
