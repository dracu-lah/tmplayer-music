package com.tmplayer.music.library

import java.text.Normalizer

/**
 * The letter a name files under in the A to Z bar.
 *
 * Names are often written in look-alike letters: small capitals (`ᴀʟᴍ`), fullwidth (`Ａ`), circled or
 * mathematical bold letters, accented letters. Their code points sort after `z`, so the bar used to
 * show `A L M` a second time after `Z` (2026-10-30). Each of them is folded to the plain capital it
 * looks like here, so one letter appears once; letters of other scripts stand for themselves and
 * everything that is not a letter is `#`.
 */
object Letters {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Latin small capitals and similar look-alikes that have no compatibility decomposition. */
    private val SMALL_CAPITALS: Map<Char, Char> = mapOf(
        'ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E',
        'ꜰ' to 'F', 'ɢ' to 'G', 'ʜ' to 'H', 'ɪ' to 'I', 'ᴊ' to 'J',
        'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O',
        'ᴘ' to 'P', 'ꞯ' to 'Q', 'ʀ' to 'R', 'ꜱ' to 'S', 'ᴛ' to 'T',
        'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z',
    )

    /** The bar letter of a name that starts with [first] (its first character, or more). */
    fun of(first: String): Char {
        if (first.isEmpty()) return '#'
        // One code point: a surrogate pair (mathematical letters) stays whole for the normaliser.
        val end = if (first.length > 1 && first[0].isHighSurrogate() && first[1].isLowSurrogate()) 2 else 1
        val head = first.substring(0, end)
        SMALL_CAPITALS[head[0]]?.let { return it }
        val folded = runCatching { Normalizer.normalize(head, Normalizer.Form.NFKD) }.getOrDefault(head)
        val plain = COMBINING_MARKS.replace(folded, "")
        val c = plain.firstOrNull() ?: return '#'
        SMALL_CAPITALS[c]?.let { return it }
        if (c.isSurrogate() || !c.isLetter()) return '#'
        return c.uppercaseChar()
    }

    /**
     * [groups] of (first character, row count) in the list's order folded into the bar: each letter
     * once, at the position of its first row. A letter met again later (a look-alike sorted after `z`)
     * keeps its first position, so the bar never repeats a letter.
     */
    fun index(groups: List<Pair<String, Long>>): List<Pair<Char, Int>> {
        val out = ArrayList<Pair<Char, Int>>()
        val seen = HashSet<Char>()
        var position = 0L
        for ((first, count) in groups) {
            val letter = of(first)
            if (seen.add(letter)) out += letter to position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            position += count.coerceAtLeast(0)
        }
        return out
    }
}
