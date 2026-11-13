package com.tmplayer.music.lyrics

import com.tmplayer.music.tags.TextDecoding

/** One word of a word-timed line: it starts at [timeMs]; [text] keeps its trailing space. */
data class LyricWord(val timeMs: Long, val text: String)

/**
 * One timed line of synced lyrics. [words] is empty for a line with no word times (plain LRC): the
 * line is then lit as a whole. With word times, the words' texts joined are [text]. [endMs] is the
 * line's own end stamp (enhanced LRC's closing `<mm:ss.xx>`), or -1 when it has none.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val endMs: Long = -1,
) {
    val hasWords: Boolean get() = words.isNotEmpty()

    /** The word playing at [at], or -1 before the first word or for a line with no word times. */
    fun wordAt(at: Long): Int {
        var low = 0
        var high = words.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].timeMs <= at) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * How much of word [index] has been sung at [at], 0 to 1. A word ends where the next begins; the
     * last ends at the line's end stamp, else at [nextLineMs] (the next line's start), but never later
     * than [MAX_LAST_WORD_MS] after it began.
     */
    fun wordFraction(index: Int, at: Long, nextLineMs: Long): Float {
        val word = words.getOrNull(index) ?: return 0f
        val end = words.getOrNull(index + 1)?.timeMs
            ?: endMs.takeIf { it > word.timeMs }
            ?: nextLineMs.takeIf { it > word.timeMs }?.coerceAtMost(word.timeMs + MAX_LAST_WORD_MS)
            ?: (word.timeMs + MAX_LAST_WORD_MS)
        val span = end - word.timeMs
        if (span <= 0) return 1f
        return ((at - word.timeMs).toFloat() / span).coerceIn(0f, 1f)
    }

    companion object {
        const val MAX_LAST_WORD_MS = 1500L
    }
}

/**
 * A track's lyrics: [lines] when they are timed (LRC), [plain] text otherwise. Never both empty for
 * a value that exists; no lyrics at all is null, not an empty [Lyrics].
 */
data class Lyrics(val lines: List<LyricLine>, val plain: String) {
    val synced: Boolean get() = lines.isNotEmpty()

    /** True when at least one line has word times. */
    val hasWords: Boolean get() = lines.any { it.hasWords }

    /**
     * The line playing at [positionMs], or -1 before the first. [latencyMs] is added to the clock,
     * so the highlight is not late on a Bluetooth headset.
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
 * Reads lyrics from a `.lrc` file or a tag. LRC lines are
 * `[mm:ss.xx]text`, with several stamps on one line allowed; `[offset:+250]` moves every line;
 * the header tags (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]` and the like) are dropped, and
 * enhanced LRC's word stamps (`<mm:ss.xx>`) become the line's [LyricWord]s (a line whose word
 * times go backwards, or has too many words, keeps only its line time). Text with no stamps at all
 * is plain lyrics. Never throws: outside data, with its line in GarbageInputTest.
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
            // Walks the line by position: cutting the front off a line of thousands of stamps again and
            // again would be quadratic.
            var pos = 0
            val stamps = ArrayList<Long>()
            var header = false
            while (pos < line.length && line[pos] == '[') {
                val close = line.indexOf(']', pos)
                if (close < 0 || close - pos > MAX_TAG) break
                val inside = line.substring(pos + 1, close)
                val stamp = stamp(inside)
                if (stamp != null) {
                    // Stamps past the cap are read over and dropped, so a line of them cannot grow the list.
                    if (stamps.size < MAX_STAMPS) stamps += stamp
                } else {
                    val tag = HEADER.matchEntire(inside)
                    if (tag == null) break
                    header = true
                    if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        offset = tag.groupValues[2].trim().toLongOrNull()?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: offset
                    }
                }
                pos = close + 1
            }
            val rest = line.substring(pos)
            val enhanced = if (stamps.isNotEmpty()) wordsOf(rest, stamps[0]) else null
            val words = enhanced?.text ?: WORD_STAMP.replace(rest, "").trim()
            when {
                stamps.isNotEmpty() -> for (s in stamps) {
                    if (timed.size >= MAX_LINES) break
                    // Word times are absolute; a repeated line (several stamps) moves them with its stamp.
                    val delta = s - stamps[0]
                    timed += LyricLine(
                        s,
                        words,
                        enhanced?.words?.map { LyricWord(it.timeMs + delta, it.text) }.orEmpty(),
                        enhanced?.endMs?.takeIf { it >= 0 }?.plus(delta) ?: -1,
                    )
                }
                header && words.isEmpty() -> Unit
                else -> {
                    if (plain.isNotEmpty()) plain.append('\n')
                    plain.append(line.trim())
                }
            }
        }
        if (timed.isNotEmpty()) {
            // LRC's offset is how much earlier the lyrics should show: a positive one moves them up.
            val lines = timed.map { line ->
                line.copy(
                    timeMs = (line.timeMs - offset).coerceAtLeast(0),
                    words = line.words.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) },
                    endMs = if (line.endMs >= 0) (line.endMs - offset).coerceAtLeast(0) else -1,
                )
            }.sortedBy { it.timeMs }
            return Lyrics(lines, "")
        }
        val body = plain.toString().trim()
        return if (body.isEmpty()) null else Lyrics(emptyList(), body)
    }

    private class Enhanced(val text: String, val words: List<LyricWord>, val endMs: Long)

    /**
     * The words of one line body with enhanced LRC stamps (`<mm:ss.xx>word <mm:ss.xx>next`). Text
     * before the first stamp starts with the line. Null when there are no stamps, or they cannot be
     * trusted (times going backwards, more than [MAX_WORDS] words): the line is then lit whole.
     */
    private fun wordsOf(body: String, lineMs: Long): Enhanced? {
        if (body.indexOf('<') < 0) return null
        // More stamps than a line may have words: not trusted, and not worth walking to the end.
        val matches = WORD_STAMP.findAll(body).take(MAX_WORDS + 2).toList()
        if (matches.isEmpty() || matches.size > MAX_WORDS + 1) return null
        val times = ArrayList<Long>()
        val texts = ArrayList<StringBuilder>()
        var endMs = -1L
        var cursor = 0
        var pendingTime = lineMs // before the first stamp, the leading text starts with the line
        for (m in matches) {
            val chunk = body.substring(cursor, m.range.first)
            addWord(times, texts, pendingTime, chunk)
            pendingTime = stamp(m.value.substring(1, m.value.length - 1)) ?: return null
            cursor = m.range.last + 1
        }
        val tail = body.substring(cursor)
        if (tail.isBlank()) endMs = pendingTime else addWord(times, texts, pendingTime, tail)
        if (times.isEmpty() || times.size > MAX_WORDS) return null
        if (times.zipWithNext().any { (a, b) -> b < a } || (endMs >= 0 && endMs < times.last())) return null
        val words = times.indices.map { LyricWord(times[it], texts[it].toString()) }.toMutableList()
        // The line's own edges are not part of a word.
        words[0] = words[0].copy(text = words[0].text.trimStart())
        words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
        val text = words.joinToString("") { it.text }
        if (text.isBlank()) return null
        return Enhanced(text, words, endMs)
    }

    private fun addWord(times: ArrayList<Long>, texts: ArrayList<StringBuilder>, time: Long, chunk: String) {
        if (chunk.isEmpty()) return
        if (chunk.isBlank()) {
            // Only spaces: they belong to the word before.
            texts.lastOrNull()?.append(chunk)
            return
        }
        times += time
        texts += StringBuilder(chunk)
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

    private const val MAX_WORDS = 400

    /** Time stamps kept on one line (a repeated chorus); more are read over and dropped. */
    private const val MAX_STAMPS = 50

    /** The longest `[...]` tag read: a stamp or a header is far shorter. */
    private const val MAX_TAG = 64

    const val MAX_BYTES = 1024 * 1024
    private const val MAX_CHARS = 1024 * 1024
    private const val MAX_LINES = 20_000
    private const val MAX_OFFSET = 60L * 60 * 1000
}
