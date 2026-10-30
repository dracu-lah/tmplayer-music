package com.tmplayer.music.library

/** One artist named in a credit: the name as written there, and the folded key it is listed under. */
data class CreditedArtist(val name: String, val key: String)

/**
 * Splits an artist credit ("A, B feat. C") into the artists it names, for the Artists tab, artist
 * pages and "Go to artist". The song keeps the whole credit as the artist it shows everywhere else.
 *
 * Rules (decided by the user on 2026-10-30, `plan/MVP-REVISION.md` decision k):
 * - Always a separator: a comma (except between two digits, so a number keeps its comma), a pipe,
 *   a NUL (ID3v2.4 writes one between artists), and " feat. ", " ft. ", " featuring " (also after
 *   an opening bracket). " with " is not a separator.
 * - " & " and " and " split only when the credit already has one of the separators above, so a
 *   list "A, B & C" splits fully while a lone "X & Y" stays one artist (duos and bands keep their name).
 * - Never "/" or ";", so names like "AC/DC" stay whole.
 * - Names are trimmed with their spaces collapsed; empty names, separator words and placeholders
 *   ("Unknown", "<unknown>") are dropped. "Various Artists" stays, as one artist.
 * - Two names with the same key are one artist, spelled as the first one.
 * - At most [MAX_ARTISTS] per credit and [MAX_NAME] characters per name, so garbage stays bounded.
 *
 * Pure and total: any input gives a list (maybe empty) and nothing here throws.
 */
internal object ArtistCredits {

    /** The most artists one credit is split into; the rest of a longer list is dropped. */
    const val MAX_ARTISTS = 8

    /** The longest artist name kept; a longer one is cut without splitting a character. */
    const val MAX_NAME = 200

    /** Separators that always split: pipe or NUL, a comma not between two digits, and the feat words. */
    private val ALWAYS = Regex(
        "\\s*[|\u0000]\\s*" +
            "|\\s*(?:(?<!\\d),|,(?!\\d))\\s*" +
            "|(?:\\s+|\\s*[(\\[]\\s*)(?:feat\\.?|ft\\.?|featuring)\\s+",
        RegexOption.IGNORE_CASE,
    )

    /** Separators that split only a credit that is already a list. */
    private val IN_LIST = Regex("\\s+(?:&|and)\\s+", RegexOption.IGNORE_CASE)

    /** Control characters other than NUL, which is read as a separator. */
    private val CONTROLS = Regex("[\\p{Cc}&&[^\u0000]]")

    private val SPACES = Regex("\\s+")

    /** Names that are only a separator word left over, never an artist. */
    private val SEPARATOR_WORDS = setOf("&", "and", "feat", "feat.", "ft", "ft.", "featuring", ",", "|")

    /** The artists [credit] names, in the order written; empty when it names nobody. */
    fun split(credit: String): List<CreditedArtist> {
        val clipped = TextFold.clip(credit)
        val text = if (clipped.any { it.isISOControl() && it != '\u0000' }) CONTROLS.replace(clipped, " ") else clipped
        if (text.isBlank()) return emptyList()
        // Most credits name one artist; they skip the separator regex (the scan and the migration
        // split every track, so this is most of their cost).
        val parts = if (mayHaveSeparator(text)) ALWAYS.split(text) else listOf(text)
        val names = if (parts.size > 1) parts.flatMap { IN_LIST.split(it) } else parts
        val out = ArrayList<CreditedArtist>(minOf(names.size, MAX_ARTISTS))
        val seen = HashSet<String>()
        for (raw in names) {
            if (out.size >= MAX_ARTISTS) break
            val name = clean(raw)
            if (name.isEmpty() || name.lowercase() in SEPARATOR_WORDS) continue
            val key = TextFold.fold(name)
            if (key.isEmpty() || key in GroupKey.UNKNOWN_ARTISTS) continue
            if (seen.add(key)) out += CreditedArtist(name, key)
        }
        return out
    }

    /** The keys of [credit]'s artists, in order. */
    fun keys(credit: String): List<String> = split(credit).map { it.key }

    /** The first artist's key, or blank: what a song is listed under for "Go to artist" when it names one. */
    fun primaryKey(credit: String): String = split(credit).firstOrNull()?.key.orEmpty()

    /** False only when [ALWAYS] cannot match: no comma, pipe or NUL, and no "ft" or "feat" anywhere. */
    private fun mayHaveSeparator(text: String): Boolean =
        text.any { it == ',' || it == '|' || it == '\u0000' } ||
            text.contains("ft", ignoreCase = true) || text.contains("feat", ignoreCase = true)

    /** Spaces collapsed, ends trimmed, a closing bracket left by "(feat. X)" taken off, cut to [MAX_NAME]. */
    private fun clean(raw: String): String {
        val spaced = raw.contains("  ") || raw.any { it.isWhitespace() && it != ' ' }
        var name = (if (spaced) SPACES.replace(raw, " ") else raw).trim()
        while (name.isNotEmpty() && (name.last() == ')' || name.last() == ']') && unbalanced(name)) {
            name = name.dropLast(1).trimEnd()
        }
        if (name.length > MAX_NAME) name = TextFold.clip(name, MAX_NAME).trimEnd()
        return name
    }

    /** Whether [name] closes more brackets than it opens. */
    private fun unbalanced(name: String): Boolean {
        val opens = name.count { it == '(' || it == '[' }
        val closes = name.count { it == ')' || it == ']' }
        return closes > opens
    }
}
