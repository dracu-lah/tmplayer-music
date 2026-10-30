package com.tmplayer.music.library

/**
 * The key that folds several files of one recording into one song (`plan/RESEARCH.md` 4.2). It is made
 * once at index time and stored on the track, so finding a song's versions is a lookup, never a
 * comparison of every track with every other.
 *
 * A key is `primary artist`, `normalised title` and `variant`, joined by U+0001. A different artist is a
 * different key, always: two songs with one title by two people never fold. A variant (live, remix and
 * the like) is a different recording and so a different key. Duration is not in the key; [clusters]
 * splits one key's members by duration afterwards, and the index adds the cluster as a `d<n>` suffix.
 */
object GroupKey {

    /** Joins the parts of a key; never found in folded text, which keeps only letters, digits and marks. */
    const val SEPARATOR = '\u0001'

    /** Telling two durations apart needs at least this much when the artist is known. */
    private const val KNOWN_ARTIST_SLACK_MS = 3_000L

    /** With no artist to go on, only near-identical lengths fold. */
    private const val UNKNOWN_ARTIST_SLACK_MS = 1_000L

    /**
     * Names a tag reader or a client puts where it has no artist, folded (so `<unknown>` is here too);
     * they count as no artist at all.
     */
    internal val UNKNOWN_ARTISTS = setOf("unknown", "unknown artist")

    /** Splits a credit into names; only the first is used, so the rest may be anything. */
    private val ARTIST_SPLIT = Regex(
        "\\s+(?:feat\\.?|ft\\.?|featuring|vs\\.?|x)\\s+|\\s+&\\s+|\\s*,\\s+|\\s*;\\s*|\\s+/\\s+",
    )

    /** A featured artist outside brackets, up to the next bracket. */
    private val FEAT_INLINE = Regex("\\s(?:feat|ft|featuring)\\.?\\s[^(\\[{\u3010]*")

    /**
     * The variants in the order they win when a title names more than one: the most specific first, and
     * remaster last because a remastered live recording is above all live.
     */
    private val VARIANTS: List<Pair<String, Regex>> = listOf(
        "nightcore" to Regex("\\bnightcore\\b"),
        "sped up" to Regex("\\b(?:sped|speed)[ -]?up\\b"),
        "slowed" to Regex("\\bslowed\\b"),
        "karaoke" to Regex("\\bkaraoke\\b"),
        "instrumental" to Regex("\\binstrumental\\b"),
        "acoustic" to Regex("\\bacoustic\\b"),
        "live" to Regex("\\blive\\b"),
        "remix" to Regex("\\b(?:re-?mix(?:ed)?|rmx)\\b"),
        "radio edit" to Regex("\\bradio (?:edit|version|mix)\\b"),
        "extended" to Regex("\\bextended\\b"),
        "demo" to Regex("\\bdemo\\b"),
        "cover" to Regex("\\bcover\\b"),
        "remaster" to Regex("\\bremaster(?:ed)?\\b"),
    )

    /**
     * The title as compared between files: folded (case, Latin, Greek and Cyrillic accents, punctuation;
     * other scripts kept), with featured artists, bracketed upload noise and the variant's own words
     * removed. The variant is not lost: [variantOf] reads it from the original title into its own part
     * of the key.
     */
    fun normaliseTitle(title: String): String {
        val lower = TextFold.lowerNfkc(title)
        if (lower.isEmpty()) return ""
        var text = TextFold.BRACKET.replace(lower) { match ->
            val content = match.groupValues.getOrNull(1).orEmpty().trim()
            if (TextFold.FEAT_CONTENT.matches(content) || TextFold.isNoise(content) || variantIn(content).isNotEmpty()) {
                " "
            } else {
                match.value
            }
        }
        text = FEAT_INLINE.replace(text, " ")
        val segments = TextFold.DASH_SEPARATOR.split(text)
        text = segments.filterIndexed { index, segment ->
            index == 0 || !(TextFold.isNoise(segment) || variantIn(segment).isNotEmpty())
        }.joinToString(" ")
        val folded = TextFold.fold(text)
        return folded.ifEmpty { TextFold.fold(lower) }
    }

    /**
     * The variant a title names, or blank for the plain recording. Only brackets and the part after a
     * dash are read, so a title that merely contains the word (`Live Forever`, `Cover Me`) is not one;
     * a featured-artist bracket is never read either.
     */
    fun variantOf(title: String): String {
        val lower = TextFold.lowerNfkc(title)
        if (lower.isEmpty()) return ""
        var best = -1
        fun consider(content: String) {
            val found = VARIANTS.indexOfFirst { it.second.containsMatchIn(content) }
            if (found >= 0 && (best < 0 || found < best)) best = found
        }
        for (match in TextFold.BRACKET.findAll(lower)) {
            val content = match.groupValues.getOrNull(1).orEmpty().trim()
            if (!TextFold.FEAT_CONTENT.matches(content)) consider(content)
        }
        TextFold.DASH_SEPARATOR.split(lower).drop(1).forEach(::consider)
        return VARIANTS.getOrNull(best)?.first.orEmpty()
    }

    /**
     * The first name of a credit, folded: `Nadia Okafor feat. Someone`, `Nadia Okafor & The Pale Rivers`
     * and `Nadia Okafor, Someone` all give `nadia okafor`. Separators count only with spaces around them,
     * so a name written with a bare slash or ampersand stays whole. Blank for no artist, including the
     * placeholders tag readers write in its place.
     */
    fun primaryArtist(artist: String): String {
        // ID3v2.4 separates several artists with NUL; read it as one more separator.
        val lower = TextFold.lowerNfkc(artist.replace('\u0000', ';'))
        if (lower.isEmpty()) return ""
        val first = ARTIST_SPLIT.split(lower).firstOrNull { it.isNotBlank() }.orEmpty()
        val folded = TextFold.fold(first.ifBlank { lower })
        return if (folded in UNKNOWN_ARTISTS) "" else folded
    }

    /**
     * The stored group key. With no artist the title must be the same text (case and spacing aside),
     * not merely fold the same, because the title is then all there is to go on.
     *
     * [durationMs] is not part of the key, so that the key stays a lookup; it is taken so callers pass
     * what they have and a later rule can use it without changing every call.
     */
    @Suppress("UNUSED_PARAMETER")
    fun of(title: String, artist: String, durationMs: Long): String {
        val artistKey = primaryArtist(artist)
        val titleKey = if (artistKey.isEmpty()) {
            TextFold.lowerNfkc(title).replace(SEPARATOR, ' ')
        } else {
            normaliseTitle(title)
        }
        return "$artistKey$SEPARATOR$titleKey$SEPARATOR${variantOf(title)}"
    }

    /** Whether a key carries an artist, which decides how strict [sameRecording] and [clusters] are. */
    fun artistKnown(key: String): Boolean = key.isNotEmpty() && key[0] != SEPARATOR

    /**
     * Whether two durations can be one recording. With a known artist they may differ by 3 seconds or
     * 2 percent of the longer, whichever is more, and an unknown (0) length on either side does not
     * split them. With no artist they must be within a second, and an unknown length never matches.
     */
    fun sameRecording(aMs: Long, bMs: Long, artistKnown: Boolean): Boolean {
        if (aMs <= 0 || bMs <= 0) return artistKnown
        val diff = if (aMs > bMs) aMs - bMs else bMs - aMs
        if (!artistKnown) return diff <= UNKNOWN_ARTIST_SLACK_MS
        val longer = maxOf(aMs, bMs)
        return diff <= maxOf(KNOWN_ARTIST_SLACK_MS, longer / 50)
    }

    /**
     * Splits one key's members by duration and returns a cluster number for each input, in input order.
     * Known lengths are taken shortest first; each starts a new cluster unless it is the [sameRecording]
     * as the current cluster's first member, so a run of small steps cannot drift into one long chain.
     * Clusters are numbered by their shortest length, so the result does not depend on input order.
     * Unknown lengths join cluster 0 when the artist is known; with no artist each is its own cluster,
     * numbered after the known ones, because nothing shows it is the same recording.
     */
    fun clusters(durationsMs: List<Long>, artistKnown: Boolean): List<Int> {
        val result = IntArray(durationsMs.size)
        val known = durationsMs.indices.filter { durationsMs[it] > 0 }
            .sortedWith(compareBy<Int> { durationsMs[it] }.thenBy { it })
        var cluster = -1
        var anchor = 0L
        for (index in known) {
            val duration = durationsMs[index]
            if (cluster < 0 || !sameRecording(anchor, duration, artistKnown)) {
                cluster++
                anchor = duration
            }
            result[index] = cluster
        }
        var next = cluster + 1
        for (index in durationsMs.indices) {
            if (durationsMs[index] > 0) continue
            result[index] = if (artistKnown) 0 else next++
        }
        return result.toList()
    }

    /**
     * One track per Telegram file: a forward of the same file has the same [Track.uniqueId] and is one
     * source seen twice, not a second version. The oldest message of each file is kept, and the kept
     * tracks stay in input order. A track with a blank unique id is never merged with another.
     */
    fun dedupeSources(tracks: List<Track>): List<Track> {
        val oldest = HashMap<String, Track>()
        for (track in tracks) {
            if (track.uniqueId.isBlank()) continue
            val kept = oldest[track.uniqueId]
            if (kept == null || OLDEST_FIRST.compare(track, kept) < 0) oldest[track.uniqueId] = track
        }
        val emitted = HashSet<String>()
        return tracks.filter { track ->
            track.uniqueId.isBlank() || (oldest[track.uniqueId] === track && emitted.add(track.uniqueId))
        }
    }

    /** Oldest message first, with the chat and message ids breaking ties so the order is stable. */
    internal val OLDEST_FIRST: Comparator<Track> =
        compareBy<Track> { it.dateSec }.thenBy { it.chatId }.thenBy { it.messageId }

    private fun variantIn(content: String): String = VARIANTS.firstOrNull { it.second.containsMatchIn(content) }?.first.orEmpty()
}
