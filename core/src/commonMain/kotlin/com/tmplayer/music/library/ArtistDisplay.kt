package com.tmplayer.music.library

/**
 * How an artist credit reads on screen. Some tags list artists with a pipe or a NUL between them
 * ("A|B|C", ID3v2.4 writes NULs); on screen they read "A, B, C". Display only: the stored credit,
 * its sort key and its grouping keys never go through this ([ArtistCredits] splits the stored one).
 *
 * Pure and total: any input gives a string (maybe empty, which callers show as "Unknown artist"),
 * at most [TextFold.MAX_FIELD] characters long, and nothing here throws.
 */
object ArtistDisplay {

    /** [credit] with each run of pipes and NULs (and the spaces around them) shown as ", ". */
    fun of(credit: String?): String {
        if (credit.isNullOrEmpty()) return ""
        val text = TextFold.clip(credit)
        // Most credits have nothing to change: no copy for them.
        if (text.none { it == '|' || it.isISOControl() }) return text
        val out = StringBuilder(text.length + 8)
        var pendingSeparator = false
        var pendingSpace = false
        for (c in text) {
            when {
                c == '|' || c == '\u0000' -> {
                    pendingSeparator = true
                    pendingSpace = false
                }
                c.isWhitespace() || c.isISOControl() -> pendingSpace = true
                else -> {
                    if (out.isNotEmpty()) {
                        if (pendingSeparator) out.append(", ") else if (pendingSpace) out.append(' ')
                    }
                    pendingSeparator = false
                    pendingSpace = false
                    out.append(c)
                }
            }
        }
        // ", " is longer than "|": clipped again so the bound holds.
        return TextFold.clip(out.toString())
    }
}
