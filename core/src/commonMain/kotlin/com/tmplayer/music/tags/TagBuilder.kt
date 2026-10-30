package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Picture
import com.tmplayer.music.library.Tags

/**
 * What the readers have found so far. Text fields keep the first non-blank value offered, so the
 * richer tag that is read first (ID3v2 before ID3v1) wins; audio properties are plain fields the
 * container's own header sets. Kept outside the readers' try so a reader that fails part way
 * still hands back what it had.
 */
internal class TagBuilder {
    var title = ""
    var artist = ""
    var album = ""
    var albumArtist = ""
    var year = 0
    var trackNumber = 0
    var discNumber = 0
    var durationMs = 0L
    var sampleRate = 0
    var bitDepth = 0
    var bitrateKbps = 0
    var channels = 0
    var format = AudioFormat.OTHER
    var lyrics = ""

    /** ID3's TLEN, used only when the audio itself gave no duration. */
    var fallbackDurationMs = 0L

    /** An original release year, used only when there is no recording year. */
    var fallbackYear = 0

    private var cover: Picture? = null
    private var coverType = -1

    fun title(s: String) {
        if (title.isEmpty()) title = TextDecoding.clean(s)
    }

    fun artist(s: String) {
        if (artist.isEmpty()) artist = TextDecoding.clean(s)
    }

    fun album(s: String) {
        if (album.isEmpty()) album = TextDecoding.clean(s)
    }

    fun albumArtist(s: String) {
        if (albumArtist.isEmpty()) albumArtist = TextDecoding.clean(s)
    }

    fun lyrics(s: String) {
        if (lyrics.isEmpty()) lyrics = TextDecoding.clean(s)
    }

    fun year(s: String) {
        if (year == 0) year = parseYear(s)
    }

    fun track(s: String) {
        if (trackNumber == 0) trackNumber = parseNumber(s)
    }

    fun disc(s: String) {
        if (discNumber == 0) discNumber = parseNumber(s)
    }

    /**
     * Offers a cover of [len] bytes at [off]. A front cover (picture type 3) replaces any other
     * kind found earlier; otherwise the first one stays. Anything over [TagReader.MAX_COVER_BYTES]
     * or not wholly inside the array is dropped before a byte is copied.
     */
    fun cover(declaredMime: String, arr: ByteArray, off: Int, len: Long, type: Int) {
        if (len <= 0 || len > TagReader.MAX_COVER_BYTES || !arr.fits(off, len)) return
        val current = cover
        if (current != null && (coverType == 3 || type != 3)) return
        val mime = imageMime(declaredMime, arr, off)
        // A JPEG cut short in the file still decodes, as a strip of picture over a blank square: skip it.
        if (arr.u8(off) == 0xFF && arr.u8(off + 1) == 0xD8 && !jpegEnds(arr, off, len.toInt())) return
        cover = Picture(mime, arr.copyOfRange(off, off + len.toInt()))
        coverType = type
    }

    /** The JPEG at [off] has its end marker (FF D9) in its last 4 KB (padding after it is allowed). */
    private fun jpegEnds(arr: ByteArray, off: Int, len: Int): Boolean {
        val end = off + len
        var i = end - 2
        val stop = maxOf(off, end - 4096)
        while (i >= stop) {
            if (arr[i] == 0xFF.toByte() && arr[i + 1] == 0xD9.toByte()) return true
            i--
        }
        return false
    }

    fun build(): Tags =
        Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = if (year > 0) year else fallbackYear,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = if (durationMs > 0) durationMs else maxOf(fallbackDurationMs, 0),
            sampleRate = maxOf(sampleRate, 0),
            bitDepth = maxOf(bitDepth, 0),
            bitrateKbps = maxOf(bitrateKbps, 0),
            channels = maxOf(channels, 0),
            format = format,
            lyrics = lyrics,
            cover = cover,
        )

    companion object {
        private val yearPattern = Regex("[0-9]{4}")

        /** The first four digits in a date such as "2009", "2009-05-01" or "May 2009", else 0. */
        fun parseYear(s: String): Int {
            val y = yearPattern.find(s)?.value?.toIntOrNull() ?: return 0
            return if (y in 1..9999) y else 0
        }

        /** The leading number of "3/12" or " 7 ", else 0. */
        fun parseNumber(s: String): Int {
            val digits = TextDecoding.clean(s).takeWhile { it in '0'..'9' }.take(9)
            return digits.toIntOrNull()?.takeIf { it > 0 } ?: 0
        }

        /** The picture's real type from its first bytes, falling back to what the tag declared. */
        fun imageMime(declared: String, arr: ByteArray, off: Int): String {
            when {
                arr.u8(off) == 0xFF && arr.u8(off + 1) == 0xD8 -> return "image/jpeg"
                arr.at(off, "\u0089PNG") -> return "image/png"
                arr.at(off, "GIF8") -> return "image/gif"
                arr.at(off, "RIFF") && arr.at(off + 8, "WEBP") -> return "image/webp"
                arr.at(off, "BM") -> return "image/bmp"
            }
            val d = TextDecoding.clean(declared).lowercase()
            return when {
                d == "jpg" || d == "jpeg" || d == "image/jpg" -> "image/jpeg"
                d == "png" -> "image/png"
                d.isEmpty() -> "application/octet-stream"
                '/' !in d -> "image/$d"
                else -> d
            }
        }
    }
}
