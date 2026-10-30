package com.tmplayer.music.tags

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns tag text of unknown encoding into a string. ID3's "ISO-8859-1" frames and ID3v1 are very
 * often really Windows-1251 (Cyrillic), Shift-JIS, GBK, EUC-KR or UTF-8 written by a tagger that
 * ignored the label, so the label is not trusted: the bytes are decoded every plausible way and
 * the reading that looks most like real text in one script wins.
 */
object TextDecoding {

    /**
     * The candidates in the order ties are broken. Cyrillic comes first because Russian speaking
     * chats are where mislabelled tags are most common; Windows-1252 is last because it decodes
     * any byte, so it should only win when the text really reads as accented Latin.
     */
    private val candidateNames = listOf("windows-1251", "Shift_JIS", "GBK", "EUC-KR", "windows-1252")

    private val candidates: List<Charset> by lazy {
        candidateNames.mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
    }

    private val latin: Charset by lazy {
        runCatching { Charset.forName("windows-1252") }.getOrNull() ?: Charsets.ISO_8859_1
    }

    /** Only this much of a long text is scored; the winner then decodes all of it. */
    private const val SAMPLE_BYTES = 4096

    /**
     * Decodes [bytes] whose real encoding is unknown. Pure ASCII stays as it is; valid UTF-8 with
     * non-ASCII characters is UTF-8 (so text that was already right is never rewritten); anything
     * else goes to whichever legacy encoding reads most plausibly. NULs and whitespace at either
     * end are trimmed. Never throws.
     */
    fun decodeLegacy(bytes: ByteArray): String =
        try {
            clean(pick(bytes))
        } catch (e: Exception) {
            clean(String(bytes, Charsets.ISO_8859_1))
        }

    /** Trims NULs, byte order marks and whitespace from both ends. */
    fun clean(text: String): String = text.trim { it.isWhitespace() || it == '\u0000' || it == '﻿' }

    private fun pick(bytes: ByteArray): String {
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.US_ASCII)
        strictUtf8(bytes)?.let { return it }
        val sample = if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes
        var best: Charset? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (cs in candidates) {
            val score = score(String(sample, cs), cs)
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        val winner = if (best == null || bestScore <= 0.0) latin else best
        return String(bytes, winner)
    }

    /** The bytes as UTF-8 when they are valid UTF-8, else null. */
    internal fun strictUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    private enum class Script { None, Latin, LatinExt, Cyrillic, Cjk, Hangul, Other }

    /**
     * How much [text] looks like real text. Scores are roughly per source byte so a double byte
     * reading and a single byte reading of the same bytes compare fairly. Letters of a common
     * script add, control characters, replacement characters and private use subtract, and a word
     * that switches script mid way (a Cyrillic letter glued to a Latin one) or case (lower then
     * upper) is penalised, since that is what a wrong code page produces.
     */
    private fun score(text: String, cs: Charset): Double {
        var total = 0.0
        var prevScript = Script.None
        var prevLower = false
        for (c in text) {
            val code = c.code
            val script: Script
            when {
                code < 0x80 -> {
                    script = if (c.isLetter()) Script.Latin else Script.None
                }
                code in 0x80..0x9F || code == 0xFFFD || code in 0xE000..0xF8FF -> {
                    total -= 4.0
                    script = Script.None
                }
                code in 0x0400..0x04FF -> {
                    total += 1.0
                    script = Script.Cyrillic
                }
                (code in 0xC0..0xFF && code != 0xD7 && code != 0xF7) || code in 0x0100..0x017F -> {
                    total += 0.6
                    if (prevScript == Script.Latin) total += 0.4
                    script = Script.LatinExt
                }
                code in 0x3040..0x30FF -> {
                    total += 2.0
                    script = Script.Cjk
                }
                code in 0x4E00..0x9FFF -> {
                    total += hanWeight(c, cs)
                    script = Script.Cjk
                }
                code in 0xAC00..0xD7A3 -> {
                    total += 2.0
                    script = Script.Hangul
                }
                code in 0xFF61..0xFF9F -> {
                    total += 0.2
                    script = Script.Cjk
                }
                code in 0xFF01..0xFF5E -> {
                    total += 1.0
                    script = Script.None
                }
                code in 0x3000..0x303F -> {
                    total += 1.5
                    script = Script.None
                }
                code in 0x2013..0x2014 || code in 0x2018..0x201F || code == 0x2026 -> {
                    total += 0.2
                    script = Script.None
                }
                c.isLetter() -> {
                    total += 0.1
                    script = Script.Other
                }
                else -> {
                    total -= 0.5
                    script = Script.None
                }
            }
            if (script != Script.None && prevScript != Script.None && script != prevScript) {
                val latinPair = (script == Script.Latin && prevScript == Script.LatinExt) ||
                    (script == Script.LatinExt && prevScript == Script.Latin)
                if (!latinPair) total -= 1.5
            }
            if (script != Script.None && prevLower && c.isUpperCase()) total -= 0.5
            prevLower = script != Script.None && c.isLowerCase()
            prevScript = script
        }
        return total
    }

    /**
     * A Han character's weight, by how common its code says it is: GB2312 level 1, JIS level 1
     * and the like hold the everyday characters, while a wrong code page lands mostly on rare ones.
     */
    private fun hanWeight(c: Char, cs: Charset): Double {
        val encoded = try {
            c.toString().toByteArray(cs)
        } catch (e: Exception) {
            return 0.2
        }
        if (encoded.size != 2) return 0.2
        val lead = encoded.u8(0)
        return when (cs.name()) {
            "GBK" -> when (lead) {
                in 0xB0..0xD7 -> 2.0
                in 0xD8..0xF7 -> 0.8
                else -> 0.2
            }
            "Shift_JIS" -> when (lead) {
                in 0x88..0x9F -> 2.0
                in 0xE0..0xEA -> 0.8
                else -> 0.2
            }
            else -> 0.4
        }
    }
}
