package com.tmplayer.music.library

import java.text.Normalizer
import java.util.Locale

/**
 * Text helpers shared by the title fallback and the group and album keys. Every input is outside data
 * (tags, Telegram's fields, file names), so the work is bounded by [clip] and nothing here throws.
 */
internal object TextFold {

    /** The most characters of one field that any rule looks at. A real title or artist is far shorter. */
    const val MAX_FIELD = 1024

    /** Hyphen-minus with a space on each side, or any of the unicode dashes with or without spaces. */
    val DASH_SEPARATOR = Regex("\\s-\\s|\\s*[\u2012\u2013\u2014\u2015\u2212\uFE58\uFE63]\\s*")

    /** One bracketed group with no bracket inside it; group 1 is the content. */
    val BRACKET = Regex("[(\\[{\u3010]([^()\\[\\]{}\u3010\u3011]*)[)\\]}\u3011]")

    /**
     * A bracket's content that is only upload noise, matched against the lower-case content with its
     * spaces collapsed. Years and edition words are not here: a remaster is a variant, not noise.
     */
    private val NOISE = Regex(
        "^(?:" +
            "official(?: (?:music|lyric|lyrics))? (?:audio|video|visuali[sz]er)" +
            "|(?:music|lyric|lyrics) video" +
            "|lyrics?|audio|video|official|visuali[sz]er|mv" +
            "|(?:hq|hd)(?: (?:audio|video))?" +
            "|\\d{2,4} ?kbps" +
            "|free download" +
            ")$",
    )

    /** A bracket's content that names a featured artist. */
    val FEAT_CONTENT = Regex("^(?:feat|ft|featuring)\\b.*")

    private val SPACES = Regex("\\s+")

    /** Control characters, which no name should carry and which must never reach a key's separator. */
    private val CONTROLS = Regex("\\p{Cc}")

    /** Characters dropped without leaving a space, so `don't` and `dont` fold the same. */
    private val APOSTROPHES = setOf('\'', '\u2019', '\u2018', '`', '\u00B4', '\u02BC')

    /** The letters NFKD leaves whole, spelled out the way a person without them would type them. */
    private val SPECIAL_LETTERS = mapOf(
        '\u00DF' to "ss", '\u00F8' to "o", '\u0142' to "l", '\u0111' to "d", '\u00E6' to "ae",
        '\u0153' to "oe", '\u00FE' to "th", '\u0131' to "i", '\u03C2' to "\u03C3",
    )

    /** At most [max] characters, never cutting a surrogate pair in half. */
    fun clip(text: String, max: Int = MAX_FIELD): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }

    /** Trims whitespace and control characters (tag readers leave NULs and stray line breaks). */
    fun trimField(text: String?): String = text?.trim { it.isWhitespace() || it.isISOControl() }.orEmpty()

    /**
     * NFKC, lower case in the root locale, control characters and whitespace collapsed to one space:
     * the strictest form still worth comparing.
     */
    fun lowerNfkc(text: String): String =
        Normalizer.normalize(clip(text), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(CONTROLS, " ").replace(SPACES, " ").trim()

    /** Whether a bracket's (or a dash segment's) content is upload noise. */
    fun isNoise(content: String): Boolean = NOISE.matches(content.lowercase(Locale.ROOT).replace(SPACES, " ").trim())

    /**
     * The comparison form of a name: lower case, accents removed from Latin, Greek and Cyrillic letters
     * only, every other script kept as written (NFKC), apostrophes dropped and any other punctuation or
     * space collapsed to one space. When that leaves nothing (a title of only symbols or emoji), the
     * lower-case text is kept instead, so such names do not all fold into one.
     */
    fun fold(text: String): String {
        val lower = lowerNfkc(text)
        if (lower.isEmpty()) return ""
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val stripped = StringBuilder(decomposed.length)
        var stripMarks = false
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                if (!stripMarks) stripped.appendCodePoint(cp)
                continue
            }
            val script = Character.UnicodeScript.of(cp)
            stripMarks = script == Character.UnicodeScript.LATIN ||
                script == Character.UnicodeScript.GREEK ||
                script == Character.UnicodeScript.CYRILLIC
            val special = if (cp < 0x10000) SPECIAL_LETTERS[cp.toChar()] else null
            if (special != null) stripped.append(special) else stripped.appendCodePoint(cp)
        }
        // Recompose what was kept, so a kana with its voicing mark is one character again.
        val composed = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
        val out = StringBuilder(composed.length)
        var pendingSpace = false
        i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x10000 && cp.toChar() in APOSTROPHES) continue
            if (Character.isLetterOrDigit(cp) || isMark(cp)) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            } else {
                pendingSpace = true
            }
        }
        return if (out.isEmpty()) lower else out.toString()
    }

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
