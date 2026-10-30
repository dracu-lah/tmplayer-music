package com.tmplayer.music.lyrics

import com.tmplayer.music.tags.TextDecoding

/** One timed line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * A track's lyrics: [lines] when they are timed (LRC), [plain] text otherwise. Never both empty for
 * a value that exists; no lyrics at all is null, not an empty [Lyrics].
 */
data class Lyrics(val lines: List<LyricLine>, val plain: String) {
    val synced: Boolean get() = lines.isNotEmpty()

    /**
     * The line playing at [positionMs], or -1 before the first. [latencyMs] is added to the clock,
     * so the highlight is not late on a Bluetooth headset (`plan/RESEARCH.md` 15).
     */
    fun lineAt(positionMs: Long, latencyMs: Long = 0): Int {
        val at = positionMs + latencyMs
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= at) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/**
 * Reads lyrics from a `.lrc` file or a tag (`plan/RESEARCH.md` 8 and 15). LRC lines are
 * `[mm:ss.xx]text`, with several stamps on one line allowed; `[offset:+250]` moves every line;
 * the header tags (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]` and the like) are dropped, and
 * enhanced LRC's word stamps (`<mm:ss.xx>`) are taken out of the text for now. Text with no stamps
 * at all is plain lyrics. Never throws: outside data, with its line in GarbageInputTest.
 */
object LyricsParser {

    /** Bytes of a `.lrc` or `.txt` file: the encoding is detected, as for tags. */
    fun parse(bytes: ByteArray): Lyrics? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val text = runCatching { decode(bytes) }.getOrNull() ?: return null
        return parse(text)
    }

    fun parse(text: String): Lyrics? = runCatching { parseText(text) }.getOrNull()

    private fun parseText(raw: String): Lyrics? {
        if (raw.isBlank() || raw.length > MAX_CHARS) return null
        val text = raw.removePrefix("﻿")
        var offset = 0L
        val timed = ArrayList<LyricLine>()
        val plain = StringBuilder()
        var lineCount = 0
        for (rawLine in text.lineSequence()) {
            if (++lineCount > MAX_LINES) break
            val line = rawLine.trimEnd('\r')
            var rest = line
            val stamps = ArrayList<Long>()
            var header = false
            while (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) break
                val inside = rest.substring(1, close)
                val stamp = stamp(inside)
                if (stamp != null) {
                    stamps += stamp
                } else {
                    val tag = HEADER.matchEntire(inside)
                    if (tag == null) break
                    header = true
                    if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        offset = tag.groupValues[2].trim().toLongOrNull()?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: offset
                    }
                }
                rest = rest.substring(close + 1)
            }
            val words = WORD_STAMP.replace(rest, "").trim()
            when {
                stamps.isNotEmpty() -> for (s in stamps) timed += LyricLine(s, words)
                header && words.isEmpty() -> Unit
                else -> {
                    if (plain.isNotEmpty()) plain.append('\n')
                    plain.append(line.trim())
                }
            }
        }
        if (timed.isNotEmpty()) {
            // LRC's offset is how much earlier the lyrics should show: a positive one moves them up.
            val lines = timed.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) }.sortedBy { it.timeMs }
            return Lyrics(lines, "")
        }
        val body = plain.toString().trim()
        return if (body.isEmpty()) null else Lyrics(emptyList(), body)
    }

    /** `mm:ss`, `mm:ss.x`, `mm:ss.xx`, `mm:ss.xxx` or `mm:ss:xx`, to milliseconds; null otherwise. */
    private fun stamp(text: String): Long? {
        val match = STAMP.matchEntire(text.trim()) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        if (seconds >= 60 || minutes > 24 * 60) return null
        val fraction = match.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0) * 100
            2 -> (fraction.toLongOrNull() ?: 0) * 10
            else -> fraction.take(3).toLongOrNull() ?: 0
        }
        return minutes * 60_000 + seconds * 1000 + ms
    }

    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> TextDecoding.decodeLegacy(bytes)
    }

    private val STAMP = Regex("""(\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?""")
    private val HEADER = Regex("""([A-Za-z#]+)\s*:(.*)""")
    private val WORD_STAMP = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")

    const val MAX_BYTES = 1024 * 1024
    private const val MAX_CHARS = 1024 * 1024
    private const val MAX_LINES = 20_000
    private const val MAX_OFFSET = 60L * 60 * 1000
}
