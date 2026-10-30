package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat
import java.io.ByteArrayOutputStream

/**
 * Ogg Vorbis, Opus and FLAC-in-Ogg: the identification and comment headers, which are the first
 * two packets of the first logical stream. A comment header with a cover often spans many pages,
 * so packets are put back together from the pages' segment tables (RFC 3533). The duration needs
 * the last page's granule position, so it is only known when the file's tail is given.
 */
internal object Ogg {

    private const val MAX_PAGES = 4096

    fun parse(b: ByteArray, sizeBytes: Long, tail: ByteArray?, out: TagBuilder) {
        out.format = AudioFormat.OGG
        val (serial, packets) = firstPackets(b)
        val ident = packets.getOrNull(0) ?: return
        var preSkip = 0L
        var rate = 0L
        var opus = false
        when {
            ident.at(0, "\u0001vorbis") && ident.size >= 30 -> {
                out.channels = ident.u8(11)
                rate = ident.u32le(12)
                if (rate in 1..1_000_000) out.sampleRate = rate.toInt()
                val nominal = ident.u32le(20).toInt()
                if (nominal > 0) out.bitrateKbps = nominal / 1000
            }
            ident.at(0, "OpusHead") && ident.size >= 19 -> {
                opus = true
                out.format = AudioFormat.OPUS
                out.channels = ident.u8(9)
                preSkip = ident.u16le(10).toLong()
                rate = 48000
                out.sampleRate = 48000
            }
            ident.at(0, "\u007FFLAC") && ident.at(9, "fLaC") -> {
                out.format = AudioFormat.FLAC
                Flac.streamInfo(ident, 17, ident.size, out)
                rate = out.sampleRate.toLong()
            }
        }
        val comments = packets.getOrNull(1)
        if (comments != null) {
            when {
                comments.at(0, "\u0003vorbis") -> Flac.vorbisComment(comments, 7, comments.size, out)
                comments.at(0, "OpusTags") -> Flac.vorbisComment(comments, 8, comments.size, out)
                out.format == AudioFormat.FLAC && comments.u8(0) and 0x7F == 4 -> Flac.vorbisComment(comments, 4, comments.size, out)
            }
        }
        if (tail != null && rate > 0) {
            val granule = lastGranule(tail, serial)
            if (granule > 0 && granule < Long.MAX_VALUE / 1000) {
                val samples = if (opus) granule - preSkip else granule
                if (samples > 0) out.durationMs = samples * 1000 / rate
            }
        }
        if (out.bitrateKbps == 0 && sizeBytes > 0 && out.durationMs > 0) {
            out.bitrateKbps = (sizeBytes * 8 / out.durationMs).toInt()
        }
    }

    /**
     * The serial number of the first logical stream and its first two packets. A packet cut short
     * by the end of [b] is returned as far as it goes; pages of other streams are skipped.
     */
    private fun firstPackets(b: ByteArray): Pair<Long, List<ByteArray>> {
        val packets = ArrayList<ByteArray>(2)
        val current = ByteArrayOutputStream()
        var serial = -1L
        var p = 0
        var pages = 0
        while (packets.size < 2 && pages++ < MAX_PAGES && b.fits(p, 27) && b.at(p, "OggS")) {
            val segments = b.u8(p + 26)
            if (!b.fits(p + 27, segments.toLong())) break
            var bodyLen = 0
            for (k in 0 until segments) bodyLen += b.u8(p + 27 + k)
            val pageSerial = b.u32le(p + 14)
            val body = p + 27 + segments
            if (serial < 0) serial = pageSerial
            if (pageSerial == serial) {
                var d = body
                for (k in 0 until segments) {
                    val lace = b.u8(p + 27 + k)
                    val take = minOf(lace, b.size - d)
                    if (take > 0) current.write(b, d, take)
                    d += lace
                    if (current.size() > MAX_LEN) return serial to packets
                    if (lace < 255) {
                        packets += current.toByteArray()
                        current.reset()
                        if (packets.size == 2) break
                    }
                }
            }
            p = body + bodyLen
        }
        if (packets.size < 2 && current.size() > 0) packets += current.toByteArray()
        return serial to packets
    }

    /** The granule position of the last page of stream [serial] in [tail], or -1. */
    private fun lastGranule(tail: ByteArray, serial: Long): Long {
        var i = tail.size - 27
        while (i >= 0) {
            if (tail.u8(i) == 'O'.code && tail.at(i, "OggS") && (serial < 0 || tail.u32le(i + 14) == serial)) {
                return tail.u64le(i + 6)
            }
            i--
        }
        return -1
    }
}
