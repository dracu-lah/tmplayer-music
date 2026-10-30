package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat

/**
 * FLAC metadata (STREAMINFO, VORBIS_COMMENT, PICTURE), and the Vorbis comment and FLAC picture
 * structures that Ogg Vorbis and Opus reuse. Written from the FLAC format description and the
 * Vorbis I comment header specification.
 */
internal object Flac {

    /** Metadata blocks beyond this many are not walked; real files have a handful. */
    private const val MAX_BLOCKS = 1024

    /** Comments beyond this many in one header are not read. */
    private const val MAX_COMMENTS = 10_000

    /**
     * Reads the FLAC stream starting with "fLaC" at [start]. A block cut short by the end of [b]
     * is read as far as it goes, then the walk stops.
     */
    fun parse(b: ByteArray, start: Int, sizeBytes: Long, out: TagBuilder) {
        if (!b.at(start, "fLaC")) return
        out.format = AudioFormat.FLAC
        var p = start + 4
        var n = 0
        while (p + 4 <= b.size && n++ < MAX_BLOCKS) {
            val header = b.u8(p)
            val type = header and 0x7F
            val len = b.u24be(p + 1)
            val ds = p + 4
            val de = minOf(ds + len, b.size.toLong()).toInt()
            when (type) {
                0 -> streamInfo(b, ds, de, out)
                4 -> vorbisComment(b, ds, de, out)
                6 -> picture(b, ds, de, out)
            }
            if (header and 0x80 != 0 || ds + len > b.size) break
            p = ds + len.toInt()
        }
        if (sizeBytes > 0 && out.durationMs > 0 && out.bitrateKbps == 0) {
            out.bitrateKbps = (sizeBytes * 8 / out.durationMs).toInt()
        }
    }

    /**
     * How many bytes from the file's start the metadata blocks need, walking as far as [b] allows.
     * When the walk reaches the end of [b] before the last block, the answer is where the next
     * block header ends: ask again with that much to learn the rest.
     */
    fun bytesNeeded(b: ByteArray, start: Int): Long {
        var p = start + 4L
        var n = 0
        while (n++ < MAX_BLOCKS) {
            if (p + 4 > b.size) return p + 4
            val i = p.toInt()
            val next = p + 4 + b.u24be(i + 1)
            if (b.u8(i) and 0x80 != 0) return next
            p = next
        }
        return p
    }

    /** STREAMINFO: rate, channels and bit depth, and total samples for the duration. */
    fun streamInfo(b: ByteArray, s: Int, e: Int, out: TagBuilder) {
        if (e - s < 18) return
        val rate = (b.u8(s + 10) shl 12) or (b.u8(s + 11) shl 4) or (b.u8(s + 12) shr 4)
        val channels = ((b.u8(s + 12) shr 1) and 7) + 1
        val bits = (((b.u8(s + 12) and 1) shl 4) or (b.u8(s + 13) shr 4)) + 1
        val total = ((b.u8(s + 13) and 0xF).toLong() shl 32) or b.u32be(s + 14)
        if (rate <= 0) return
        out.sampleRate = rate
        out.channels = channels
        out.bitDepth = bits
        if (total > 0) out.durationMs = total * 1000 / rate
    }

    /**
     * A Vorbis comment structure from [s]: vendor string, then "KEY=value" pairs, little-endian
     * lengths. Keys are matched without regard to case.
     */
    fun vorbisComment(b: ByteArray, s: Int, e: Int, out: TagBuilder) {
        var p = s
        if (p + 4 > e) return
        val vendor = b.u32le(p)
        if (vendor > e - p - 4) return
        p += 4 + vendor.toInt()
        if (p + 4 > e) return
        val count = b.u32le(p)
        p += 4
        var i = 0L
        while (i < count && i < MAX_COMMENTS && p + 4 <= e) {
            val len = b.u32le(p)
            p += 4
            if (len > e - p) break
            comment(b, p, len.toInt(), out)
            p += len.toInt()
            i++
        }
    }

    private fun comment(b: ByteArray, s: Int, len: Int, out: TagBuilder) {
        val eq = b.indexOfByte('='.code, s, s + minOf(len, 64))
        if (eq <= s || eq >= s + len) return
        val key = String(b, s, eq - s, Charsets.UTF_8).uppercase()
        val vs = eq + 1
        val vlen = s + len - vs
        if (key == "METADATA_BLOCK_PICTURE") {
            if (vlen > TagReader.MAX_COVER_BYTES / 3 * 4 + 64 * 1024) return
            val raw = Base64.decode(b, vs, vlen) ?: return
            picture(raw, 0, raw.size, out)
            return
        }
        if (vlen > 1024 * 1024) return
        val value = { String(b, vs, vlen, Charsets.UTF_8) }
        when (key) {
            "TITLE" -> out.title(value())
            "ARTIST" -> out.artist(value())
            "ALBUM" -> out.album(value())
            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> out.albumArtist(value())
            "DATE", "YEAR" -> out.year(value())
            "ORIGINALDATE", "ORIGINALYEAR" -> if (out.fallbackYear == 0) out.fallbackYear = TagBuilder.parseYear(value())
            "TRACKNUMBER" -> out.track(value())
            "DISCNUMBER" -> out.disc(value())
            "LYRICS", "UNSYNCEDLYRICS" -> out.lyrics(value())
        }
    }

    /** A FLAC PICTURE structure from [s] (big-endian): type, mime, description, size, then data. */
    fun picture(b: ByteArray, s: Int, e: Int, out: TagBuilder) {
        var p = s
        if (p + 8 > e) return
        val type = b.u32be(p)
        val mimeLen = b.u32be(p + 4)
        p += 8
        if (mimeLen > 256 || mimeLen > e - p) return
        val mime = b.ascii(p, mimeLen.toInt())
        p += mimeLen.toInt()
        if (p + 4 > e) return
        val descLen = b.u32be(p)
        p += 4
        if (descLen > e - p) return
        p += descLen.toInt()
        p += 16 // width, height, depth, colours
        if (p + 4 > e) return
        val dataLen = b.u32be(p)
        p += 4
        if (dataLen > e - p) return
        out.cover(mime, b, p, dataLen, if (type in 0L..255L) type.toInt() else 0)
    }
}

/**
 * Base64 (standard or URL-safe alphabet, padding and whitespace ignored), here because
 * `java.util.Base64` needs Android 8.0 and the floor is 6.0.
 */
internal object Base64 {
    private val values = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62
        t['_'.code] = 63
    }

    /** Decodes the ASCII base64 text at [b][s, s+len), or null when it holds anything else. */
    fun decode(b: ByteArray, s: Int, len: Int): ByteArray? {
        if (!b.fits(s, len.toLong())) return null
        val out = ByteArray(len / 4 * 3 + 3)
        var n = 0
        var acc = 0
        var bits = 0
        for (i in s until s + len) {
            val c = b.u8(i)
            if (c == '='.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) continue
            val v = if (c < 128) values[c] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.copyOf(n)
    }
}
