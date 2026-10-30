package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat

/**
 * MP4 and M4A (AAC or ALAC): the iTunes style item list under moov/udta/meta/ilst, the duration
 * from mvhd and the rate from the first audio sample entry in stsd. Written from ISO/IEC 14496-12
 * (the base media file format) and the published layout of the ilst items. A file whose moov
 * comes after the audio is read from the tail when one is given.
 */
internal object Mp4 {

    private const val MAX_DEPTH = 8
    private const val MAX_ATOMS = 4096

    private val containers = setOf("moov", "udta", "trak", "mdia", "minf", "stbl")

    private class State {
        var moov = false
        var sampleEntry = ""
        var timescale = 0L
        var duration = 0L
        var atoms = 0
    }

    fun parse(b: ByteArray, sizeBytes: Long, tail: ByteArray?, out: TagBuilder) {
        out.format = AudioFormat.M4A
        val st = State()
        walk(b, 0, b.size, 0, st, out)
        if (!st.moov && tail != null) {
            val at = findMoov(tail)
            if (at >= 0) walk(tail, at, tail.size, 0, st, out)
        }
        if (st.sampleEntry == "alac") out.format = AudioFormat.ALAC
        if (st.timescale > 0 && st.duration > 0 && st.duration < Long.MAX_VALUE / 1000) {
            out.durationMs = st.duration * 1000 / st.timescale
        }
        if (out.bitrateKbps == 0 && sizeBytes > 0 && out.durationMs > 0) {
            out.bitrateKbps = (sizeBytes * 8 / out.durationMs).toInt()
        }
    }

    /** Where a whole moov atom starts in [tail], which begins at no atom boundary, or -1. */
    private fun findMoov(tail: ByteArray): Int {
        var i = 4
        while (i + 4 <= tail.size) {
            if (tail.u8(i) == 'm'.code && tail.at(i, "moov")) {
                val size = tail.u32be(i - 4)
                if (size >= 8 && i - 4 + size <= tail.size) return i - 4
            }
            i++
        }
        return -1
    }

    private fun walk(b: ByteArray, s: Int, e: Int, depth: Int, st: State, out: TagBuilder) {
        if (depth > MAX_DEPTH) return
        var p = s
        while (p + 8 <= e && st.atoms++ < MAX_ATOMS) {
            var size = b.u32be(p)
            val type = b.ascii(p + 4, 4)
            var header = 8
            if (size == 1L) {
                if (p + 16 > e) return
                size = b.u64be(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (e - p).toLong()
            }
            if (size < header) return
            val end = p + size
            val cs = p + header
            val ce = minOf(end, e.toLong()).toInt()
            when (type) {
                in containers -> {
                    if (type == "moov") st.moov = true
                    walk(b, cs, ce, depth + 1, st, out)
                }
                "meta" -> {
                    // ISO meta is a full box (4 bytes of version and flags); QuickTime's is not.
                    val children = if (b.at(cs + 4, "hdlr")) cs else cs + 4
                    walk(b, children, ce, depth + 1, st, out)
                }
                "ilst" -> items(b, cs, ce, out)
                "mvhd" -> mvhd(b, cs, ce, st)
                "stsd" -> stsd(b, cs, ce, st, out)
            }
            if (end >= e) return
            p = end.toInt()
        }
    }

    private fun mvhd(b: ByteArray, s: Int, e: Int, st: State) {
        if (st.timescale > 0) return
        if (b.u8(s) == 1) {
            if (s + 32 > e) return
            st.timescale = b.u32be(s + 20)
            st.duration = b.u64be(s + 24)
        } else {
            if (s + 20 > e) return
            st.timescale = b.u32be(s + 12)
            val d = b.u32be(s + 16)
            st.duration = if (d == 0xFFFFFFFFL) 0 else d
        }
    }

    /** The first audio sample entry: its codec, and channels, sample size and rate. */
    private fun stsd(b: ByteArray, s: Int, e: Int, st: State, out: TagBuilder) {
        if (st.sampleEntry.isNotEmpty()) return
        val q = s + 8 // version, flags and the entry count
        if (q + 36 > e) return
        val size = b.u32be(q)
        val type = b.ascii(q + 4, 4)
        if (type != "mp4a" && type != "alac") return
        st.sampleEntry = type
        out.channels = b.u16be(q + 24)
        val rate = b.u32be(q + 32) ushr 16
        if (rate > 0) out.sampleRate = rate.toInt()
        if (type == "alac") {
            out.bitDepth = b.u16be(q + 26)
            val entryEnd = minOf(q + size, e.toLong()).toInt()
            var i = q + 36
            while (i + 36 <= entryEnd) {
                if (b.at(i + 4, "alac") && b.u32be(i) >= 36) {
                    val bits = b.u8(i + 17)
                    val channels = b.u8(i + 21)
                    val avg = b.u32be(i + 28)
                    val sampleRate = b.u32be(i + 32)
                    if (bits > 0) out.bitDepth = bits
                    if (channels > 0) out.channels = channels
                    if (avg > 0) out.bitrateKbps = (avg / 1000).toInt()
                    if (sampleRate in 1..10_000_000) out.sampleRate = sampleRate.toInt()
                    break
                }
                i++
            }
        }
    }

    /** The ilst items, each holding one or more "data" atoms with a type code and a value. */
    private fun items(b: ByteArray, s: Int, e: Int, out: TagBuilder) {
        var p = s
        var n = 0
        while (p + 8 <= e && n++ < MAX_ATOMS) {
            val size = b.u32be(p)
            if (size < 8) return
            val name = b.ascii(p + 4, 4)
            val end = minOf(p + size, e.toLong()).toInt()
            val data = firstData(b, p + 8, end)
            if (data != null) item(name, b, data.first, data.second, end, out)
            if (p + size >= e) return
            p = (p + size).toInt()
        }
    }

    /** The first "data" atom in [s, e): its type code and where its value starts. */
    private fun firstData(b: ByteArray, s: Int, e: Int): Pair<Int, Int>? {
        var p = s
        var n = 0
        while (p + 16 <= e && n++ < 64) {
            val size = b.u32be(p)
            if (size < 8) return null
            if (b.at(p + 4, "data") && size >= 16) return (b.u32be(p + 8) and 0xFFFFFF).toInt() to p + 16
            p = (p + size).let { if (it > e) return null else it.toInt() }
        }
        return null
    }

    private fun item(name: String, b: ByteArray, type: Int, vs: Int, itemEnd: Int, out: TagBuilder) {
        // The value runs to the end of its data atom, which is the item's only child in practice.
        val dataStart = vs - 16
        val ve = minOf(dataStart + b.u32be(dataStart), itemEnd.toLong()).toInt()
        if (ve < vs) return
        fun text(): String = when (type) {
            2 -> String(b, vs, (ve - vs) and 1.inv(), Charsets.UTF_16BE)
            else -> String(b, vs, ve - vs, Charsets.UTF_8)
        }
        when (name) {
            "©nam" -> out.title(text())
            "©ART" -> out.artist(text())
            "©alb" -> out.album(text())
            "aART" -> out.albumArtist(text())
            "©day" -> out.year(text())
            "©lyr" -> out.lyrics(text())
            "trkn" -> if (ve - vs >= 4 && out.trackNumber == 0) out.trackNumber = b.u16be(vs + 2)
            "disk" -> if (ve - vs >= 4 && out.discNumber == 0) out.discNumber = b.u16be(vs + 2)
            "covr" -> {
                val mime = when (type) {
                    13 -> "image/jpeg"
                    14 -> "image/png"
                    27 -> "image/bmp"
                    else -> ""
                }
                out.cover(mime, b, vs, (ve - vs).toLong(), 3)
            }
        }
    }
}
