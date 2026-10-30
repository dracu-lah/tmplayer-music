package com.tmplayer.music.tags

/**
 * ID3v2.2, 2.3 and 2.4 tags (at the start of MP3s, and of some FLAC and AAC files, and inside WAV
 * and AIFF chunks) and the 128 byte ID3v1 tag at the end of a file. Written from the id3.org
 * specifications. A frame that is cut short, compressed or encrypted is skipped; the frames
 * before it are kept.
 */
internal object Id3 {

    /** Frames beyond this many in one tag are not read; real tags have a few dozen. */
    private const val MAX_FRAMES = 4096

    /**
     * The whole tag's size at [off], header and footer included, as its header says, or -1 when
     * there is no ID3v2 tag there.
     */
    fun tagSize(b: ByteArray, off: Int): Long {
        if (!b.fits(off, 10) || !b.at(off, "ID3")) return -1
        val major = b.u8(off + 3)
        if (major < 2 || major > 4 || b.u8(off + 4) == 0xFF) return -1
        val size = b.syncsafe(off + 6)
        if (size < 0) return -1
        val footer = if (major == 4 && b.u8(off + 5) and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /** Reads the ID3v2 tag at [off] into [out], as much of it as [b] holds. */
    fun parseV2(b: ByteArray, off: Int, out: TagBuilder) {
        if (tagSize(b, off) < 0) return
        val major = b.u8(off + 3)
        val flags = b.u8(off + 5)
        if (major == 2 && flags and 0x40 != 0) return // v2.2 "compression" has no defined scheme
        val bodyStart = off + 10
        val bodyEnd = minOf(b.size.toLong(), bodyStart + b.syncsafe(off + 6)).toInt()
        if (bodyEnd <= bodyStart) return
        val wholeUnsync = flags and 0x80 != 0
        val arr: ByteArray
        val s: Int
        val e: Int
        if (wholeUnsync && major < 4) {
            arr = resync(b, bodyStart, bodyEnd)
            s = 0
            e = arr.size
        } else {
            arr = b
            s = bodyStart
            e = bodyEnd
        }
        var pos = s
        if (major >= 3 && flags and 0x40 != 0) {
            val skip = if (major == 3) 4 + arr.u32be(pos) else arr.syncsafe(pos)
            if (skip < 4 || skip > e - pos) return
            pos += skip.toInt()
        }
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        val pending = Pending()
        var count = 0
        while (pos + headerLen <= e && count++ < MAX_FRAMES) {
            if (!validId(arr, pos, idLen)) break
            val id = arr.ascii(pos, idLen)
            val size = when (major) {
                2 -> arr.u24be(pos + 3)
                3 -> arr.u32be(pos + 4)
                else -> v24FrameSize(arr, pos, e)
            }
            if (size < 0 || size > e - pos - headerLen) break
            val fs = pos + headerLen
            val fe = fs + size.toInt()
            val frameFlags = if (major == 2) 0 else arr.u8(pos + 9)
            frame(id, arr, fs, fe, major, frameFlags, wholeUnsync, out, pending)
            pos = fe
        }
        if (out.fallbackYear == 0) out.fallbackYear = pending.originalYear
    }

    private class Pending {
        var originalYear = 0
    }

    /**
     * A v2.4 frame's size. The spec says syncsafe, but many taggers wrote plain 32 bit sizes, so
     * when the syncsafe reading does not land on another frame (or the padding, or the end) and
     * the plain one does, the plain one is believed.
     */
    private fun v24FrameSize(arr: ByteArray, pos: Int, e: Int): Long {
        val safe = arr.syncsafe(pos + 4)
        val plain = arr.u32be(pos + 4)
        if (safe < 0) return plain
        if (safe == plain) return safe
        fun landsWell(n: Long): Boolean {
            val next = pos + 10L + n
            if (next == e.toLong()) return true
            if (next > e) return false
            val i = next.toInt()
            return i + 4 > e || validId(arr, i, 4) || isPadding(arr, i, e)
        }
        if (landsWell(safe)) return safe
        if (landsWell(plain)) return plain
        return safe
    }

    /**
     * Whether [i] starts the tag's padding: zeros up to the end, judged on the next 4 KB so a
     * zero byte inside a frame's data is not taken for it.
     */
    private fun isPadding(arr: ByteArray, i: Int, e: Int): Boolean {
        val end = minOf(e, i + 4096, arr.size)
        for (k in i until end) if (arr[k] != 0.toByte()) return false
        return true
    }

    private fun validId(arr: ByteArray, pos: Int, len: Int): Boolean {
        if (!arr.fits(pos, len.toLong())) return false
        for (k in 0 until len) {
            val c = arr.u8(pos + k)
            if (c !in 'A'.code..'Z'.code && c !in '0'.code..'9'.code) return false
        }
        return true
    }

    /** Undoes unsynchronisation: every 0xFF 0x00 pair becomes 0xFF. */
    private fun resync(b: ByteArray, s: Int, e: Int): ByteArray {
        val out = ByteArray(maxOf(e - s, 0))
        var n = 0
        var i = s
        while (i < e) {
            val c = b[i]
            out[n++] = c
            i += if (c == 0xFF.toByte() && i + 1 < e && b[i + 1] == 0.toByte()) 2 else 1
        }
        return out.copyOf(n)
    }

    private fun frame(
        id: String,
        arr: ByteArray,
        fs: Int,
        fe: Int,
        major: Int,
        flags: Int,
        tagUnsync: Boolean,
        out: TagBuilder,
        pending: Pending,
    ) {
        if (!wanted(id)) return
        var s = fs
        var data = arr
        var e = fe
        when (major) {
            3 -> {
                if (flags and 0xC0 != 0) return // compressed or encrypted
                if (flags and 0x20 != 0) s += 1 // grouping identity
            }
            4 -> {
                if (flags and 0x0C != 0) return // compressed or encrypted
                if (flags and 0x40 != 0) s += 1
                if (flags and 0x01 != 0) s += 4 // data length indicator
                if (s > e) return
                if (flags and 0x02 != 0 || tagUnsync) {
                    data = resync(arr, s, e)
                    s = 0
                    e = data.size
                }
            }
        }
        if (s >= e) return
        when (id) {
            "TIT2", "TT2" -> out.title(text(data, s, e))
            "TPE1", "TP1" -> out.artist(text(data, s, e))
            "TALB", "TAL" -> out.album(text(data, s, e))
            "TPE2", "TP2" -> out.albumArtist(text(data, s, e))
            "TYER", "TYE", "TDRC" -> out.year(text(data, s, e))
            "TORY", "TOR", "TDOR" -> if (pending.originalYear == 0) pending.originalYear = TagBuilder.parseYear(text(data, s, e))
            "TRCK", "TRK" -> out.track(text(data, s, e))
            "TPOS", "TPA" -> out.disc(text(data, s, e))
            "TLEN", "TLE" -> if (out.fallbackDurationMs == 0L) {
                out.fallbackDurationMs = text(data, s, e).takeWhile { it in '0'..'9' }.take(12).toLongOrNull() ?: 0
            }
            "USLT", "ULT" -> lyrics(data, s, e, out)
            "APIC" -> picture(data, s, e, out, v22 = false)
            "PIC" -> picture(data, s, e, out, v22 = true)
        }
    }

    private val wantedIds = setOf(
        "TIT2", "TT2", "TPE1", "TP1", "TALB", "TAL", "TPE2", "TP2", "TYER", "TYE", "TDRC", "TORY", "TOR",
        "TDOR", "TRCK", "TRK", "TPOS", "TPA", "TLEN", "TLE", "USLT", "ULT", "APIC", "PIC",
    )

    private fun wanted(id: String): Boolean = id in wantedIds

    /** A text frame's first non-blank value (ID3v2.4 separates several values with NULs). */
    private fun text(arr: ByteArray, s: Int, e: Int): String = firstValue(arr.u8(s), arr, s + 1, e)

    private fun lyrics(arr: ByteArray, s: Int, e: Int, out: TagBuilder) {
        val enc = arr.u8(s)
        val descStart = s + 4 // encoding byte and a three letter language
        if (descStart > e) return
        val descEnd = terminator(enc, arr, descStart, e)
        val textStart = descEnd + termLength(enc)
        if (textStart >= e) return
        out.lyrics(decode(enc, arr, textStart, terminator(enc, arr, textStart, e)))
    }

    private fun picture(arr: ByteArray, s: Int, e: Int, out: TagBuilder, v22: Boolean) {
        val enc = arr.u8(s)
        var p = s + 1
        val mime: String
        if (v22) {
            if (p + 3 > e) return
            mime = arr.ascii(p, 3)
            p += 3
        } else {
            val mimeEnd = arr.indexOfByte(0, p, e)
            if (mimeEnd >= e) return
            mime = arr.ascii(p, mimeEnd - p)
            p = mimeEnd + 1
        }
        if (mime == "-->") return // a link to an outside file, not a picture
        if (p >= e) return
        val type = arr.u8(p)
        p += 1
        val descEnd = terminator(enc, arr, p, e)
        p = descEnd + termLength(enc)
        if (p >= e) return
        out.cover(mime, arr, p, (e - p).toLong(), type)
    }

    private fun termLength(enc: Int): Int = if (enc == 1 || enc == 2) 2 else 1

    /** Where the NUL that ends a string in encoding [enc] starts, or [e] when there is none. */
    private fun terminator(enc: Int, arr: ByteArray, s: Int, e: Int): Int {
        val end = minOf(e, arr.size)
        if (enc == 1 || enc == 2) {
            var i = s
            while (i + 1 < end) {
                if (arr[i] == 0.toByte() && arr[i + 1] == 0.toByte()) return i
                i += 2
            }
            return e
        }
        return arr.indexOfByte(0, s, end).let { if (it >= end) e else it }
    }

    private fun firstValue(enc: Int, arr: ByteArray, s: Int, e: Int): String {
        var p = s
        var guard = 0
        while (p < e && guard++ < 64) {
            val t = terminator(enc, arr, p, e)
            val value = decode(enc, arr, p, t)
            if (value.isNotBlank()) return value
            p = t + termLength(enc)
        }
        return ""
    }

    /** One string in ID3 text encoding [enc]: 0 Latin-1 (often not), 1 UTF-16 with BOM, 2 UTF-16BE, 3 UTF-8. */
    fun decode(enc: Int, arr: ByteArray, s: Int, e: Int): String {
        val start = maxOf(s, 0)
        val end = minOf(e, arr.size)
        if (start >= end) return ""
        return when (enc) {
            1 -> utf16(arr, start, end)
            2 -> TextDecoding.clean(String(arr, start, (end - start) and 1.inv(), Charsets.UTF_16BE))
            else -> TextDecoding.decodeLegacy(arr.copyOfRange(start, end))
        }
    }

    /**
     * UTF-16 by its byte order mark. Without one (against the spec, but common) the byte order is
     * guessed from where the zero bytes of ASCII characters fall.
     */
    private fun utf16(arr: ByteArray, s: Int, e: Int): String {
        var start = s
        val bigEndian: Boolean
        when {
            arr.u8(s) == 0xFE && arr.u8(s + 1) == 0xFF -> {
                bigEndian = true
                start += 2
            }
            arr.u8(s) == 0xFF && arr.u8(s + 1) == 0xFE -> {
                bigEndian = false
                start += 2
            }
            else -> {
                var evenZeros = 0
                var oddZeros = 0
                var i = s
                while (i + 1 < e && i < s + 512) {
                    if (arr[i] == 0.toByte()) evenZeros++
                    if (arr[i + 1] == 0.toByte()) oddZeros++
                    i += 2
                }
                bigEndian = evenZeros > oddZeros
            }
        }
        val len = maxOf(e - start, 0) and 1.inv()
        if (len == 0) return ""
        return TextDecoding.clean(String(arr, start, len, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE))
    }

    /** Reads an ID3v1 (or v1.1) tag from the last 128 bytes of [tail] into [out]'s blank fields. */
    fun parseV1(tail: ByteArray, out: TagBuilder) {
        if (tail.size < 128) return
        val o = tail.size - 128
        if (!tail.at(o, "TAG")) return
        out.title(v1Text(tail, o + 3, 30))
        out.artist(v1Text(tail, o + 33, 30))
        out.album(v1Text(tail, o + 63, 30))
        out.year(tail.ascii(o + 93, 4))
        if (tail.u8(o + 125) == 0 && tail.u8(o + 126) != 0 && out.trackNumber == 0) out.trackNumber = tail.u8(o + 126)
    }

    /** Whether [tail] ends with an ID3v1 tag, whose 128 bytes are not audio. */
    fun hasV1(tail: ByteArray?): Boolean = tail != null && tail.size >= 128 && tail.at(tail.size - 128, "TAG")

    private fun v1Text(b: ByteArray, off: Int, len: Int): String {
        val end = b.indexOfByte(0, off, off + len).coerceAtMost(off + len)
        return TextDecoding.decodeLegacy(b.copyOfRange(off, end))
    }
}
