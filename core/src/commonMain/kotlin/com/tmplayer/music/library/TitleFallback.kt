package com.tmplayer.music.library

/**
 * The title and artist a track shows when its tags say nothing, in the order of `plan/RESEARCH.md` 6.2:
 * the file's tags, then Telegram's own fields, then `Artist - Title` read from the file name, then the
 * file name itself. A name is only tidied (track numbers, underscores and bracketed upload noise go);
 * its letters are never rewritten, so a title in any script shows as the sender wrote it.
 */
object TitleFallback {

    /**
     * A leading track number with its separator: `01 `, `01. `, `1-02 `, `(03) `, `07 - `. Four digits are
     * left alone because they are more often a year or part of the title.
     */
    private val TRACK_NUMBER = Regex(
        "^\\(?\\d{1,3}(?:[-.]\\d{1,3})?\\)?(?:\\s*[.):\\-\u2012\u2013\u2014\u2015\u2212]\\s*|\\s+)",
    )

    private val SPACES = Regex("\\s+")

    /** What may be trimmed from the ends of a parsed part: spaces, separators and stray underscores. */
    private fun isEdge(c: Char): Boolean =
        c.isWhitespace() || c.isISOControl() || c == '-' || c == '_' || c == ',' || c == ';' ||
            c in "\u2012\u2013\u2014\u2015\u2212"

    /**
     * Reads `(artist, title)` from a file name; the artist is blank when the name does not say. Handles
     * `Artist - Title.mp3`, `01 - Artist - Title.flac`, `01. Title.mp3`, `03_Title_Name.mp3`,
     * `Title (Official Audio).mp3` and the unicode dashes as separators. A name that is only a number
     * or only noise comes back as the name without its extension, so the title is never blank unless
     * the name is.
     */
    fun fromFileName(fileName: String): Pair<String, String> {
        val whole = TextFold.trimField(fileName.trim().takeLast(TextFold.MAX_FIELD))
        val base = AudioKinds.baseName(whole).ifBlank { whole }
        var text = base.replace('_', ' ').replace(SPACES, " ").trim()
        text = TextFold.BRACKET.replace(text) { match ->
            val content = match.groupValues.getOrNull(1).orEmpty()
            if (TextFold.isNoise(content)) " " else match.value
        }.replace(SPACES, " ").trim()
        text = stripTrackNumber(text)

        val parts = TextFold.DASH_SEPARATOR.split(text, limit = 2)
        val (artist, title) = if (parts.size == 2) {
            stripTrackNumber(tidy(parts.getOrElse(0) { "" })) to tidy(parts.getOrElse(1) { "" })
        } else {
            "" to tidy(text)
        }
        if (title.isBlank() || title.all { !it.isLetter() }) {
            // Only a number, only noise, or a separator with nothing after it: show the name as it is.
            return "" to base
        }
        return artist to title
    }

    /**
     * The `(artist, title)` a track shows. Each comes from the first source that has it: the tags, then
     * Telegram's title and performer, then the file name. The file name's artist is taken only when the
     * title came from the file name too, or when the name's title is the one already known, so a name like
     * `Title - Live at the Hall.mp3` never turns into an artist. The title is blank only when every input is.
     */
    fun display(
        tagTitle: String?,
        tagArtist: String?,
        tgTitle: String?,
        tgPerformer: String?,
        fileName: String?,
    ): Pair<String, String> {
        var title = TextFold.trimField(tagTitle).ifEmpty { TextFold.trimField(tgTitle) }
        var artist = realArtist(tagArtist).ifEmpty { realArtist(tgPerformer) }
        val name = TextFold.trimField(fileName)
        if ((title.isEmpty() || artist.isEmpty()) && name.isNotEmpty()) {
            val (nameArtist, nameTitle) = fromFileName(name)
            if (title.isEmpty()) {
                title = nameTitle
                if (artist.isEmpty()) artist = nameArtist
            } else if (artist.isEmpty() && TextFold.fold(nameTitle) == TextFold.fold(title)) {
                artist = nameArtist
            }
        }
        if (title.isEmpty()) title = name
        return TextFold.trimField(artist) to TextFold.trimField(title)
    }

    /**
     * Words taggers and phones write when they do not know the artist ("<unknown>" is Android's own).
     * They mean nothing, so they count as blank and the next source is asked.
     */
    private val PLACEHOLDER_ARTISTS = setOf("<unknown>", "unknown", "unknown artist", "[unknown]", "artist", "n/a")

    private fun realArtist(value: String?): String =
        TextFold.trimField(value).takeUnless { it.lowercase() in PLACEHOLDER_ARTISTS }.orEmpty()

    private fun stripTrackNumber(text: String): String {
        val match = TRACK_NUMBER.find(text) ?: return text
        val rest = text.substring(match.range.last + 1)
        return if (rest.any { it.isLetter() }) rest.trim() else text
    }

    private fun tidy(part: String): String = part.trim(::isEdge).replace(SPACES, " ")
}
