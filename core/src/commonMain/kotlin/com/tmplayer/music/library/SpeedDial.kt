package com.tmplayer.music.library

/**
 * One card of Speed dial on Home: a file the user played lately, with where it was left.
 *
 * [key] is what two cards are the same by: the song the file is a version of now, or the file
 * itself when it is in no song, so two versions of one song are one card. [songKey] is how it was
 * played: empty for a file played from a chat page (resuming it queues the chat from there), the
 * song's key otherwise. [positionMs] is 0 when it starts from the top; [durationMs] 0 is unknown.
 */
data class DialItem(
    val key: String,
    val track: TrackId,
    val songKey: String,
    val title: String,
    val artist: String,
    val coverKey: String = "",
    val thumbFileId: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** When it was last played or paused, Unix milliseconds. */
    val atMs: Long = 0,
) {
    /** A card is partly played (shows its thin bar) when it resumes somewhere past the start. */
    val partlyPlayed: Boolean get() = positionMs > 0

    companion object {
        /** The [key] of a file that is in no song. */
        fun fileKey(id: TrackId): String = "file:${id.chatId}:${id.messageId}"
    }
}

/** A card the user took off Speed dial at [atMs]; a play of the same song after that brings it back. */
data class DialHidden(val key: String, val atMs: Long)

/**
 * The rules of Speed dial and of the resume positions behind it, plain so they are tested without a
 * database or a player.
 *
 * A position is kept per file when it is worth coming back to: past the first [MIN_POSITION_MS] and
 * not in the last stretch of the track ([nearEnd]). A track that ended, or was left in its last
 * stretch, has its position cleared, so its card starts from the top again. A missing duration is
 * unknown: such a position is kept (nothing says it is near the end) and only an ended track clears it.
 */
object SpeedDial {
    /** Cards per page: 3 columns by 3 rows. */
    const val PAGE = 9

    /** The most cards shown, three pages. */
    const val MAX_SHOWN = 27

    /** Resume positions kept on disk; older ones are trimmed as new ones come. */
    const val MAX_KEPT = 500

    /** Under this a play is a sample, not something to resume. */
    const val MIN_POSITION_MS = 10_000L

    /** The last stretch counts as finished: 5% of the track, at least 10 s and at most 60 s. */
    fun nearEnd(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val margin = (durationMs / 20).coerceIn(10_000L, 60_000L)
        return positionMs >= durationMs - margin
    }

    /** Whether a position is worth keeping; false means clear what was kept for the file. */
    fun worthKeeping(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= MIN_POSITION_MS && !nearEnd(positionMs, durationMs)

    /**
     * Pages of [items], [PAGE] at a time; the last page may be short. Empty for no items, so the
     * section hides.
     */
    fun pages(items: List<DialItem>): List<List<DialItem>> = items.chunked(PAGE)

    /**
     * The cards, in order: [current] (what the player holds now) first, then the most recent of the
     * rest. [resumes] are the files with a kept position, [plays] the latest play of each song; a song
     * found in both is one card that resumes at its position. A card [hidden] at a time later than
     * its last play or pause is left out; [current] is never hidden, as it is what plays. Each song
     * or file shows once, and at most [limit] cards come back.
     */
    fun pick(
        current: DialItem?,
        resumes: List<DialItem>,
        plays: List<DialItem>,
        hidden: List<DialHidden> = emptyList(),
        limit: Int = MAX_SHOWN,
    ): List<DialItem> {
        val max = limit.coerceIn(0, MAX_SHOWN)
        if (max == 0) return emptyList()
        val hiddenAt = HashMap<String, Long>()
        for (h in hidden) hiddenAt[h.key] = maxOf(hiddenAt[h.key] ?: Long.MIN_VALUE, h.atMs)

        // Per key: the newest moment it was touched, and the position to resume at.
        val best = LinkedHashMap<String, DialItem>()
        val kept = resumes.filter { worthKeeping(it.positionMs, it.durationMs) }
        for (r in kept) {
            val had = best[r.key]
            if (had == null || r.atMs > had.atMs) best[r.key] = r
        }
        for (p in plays) {
            val had = best[p.key]
            when {
                had == null -> best[p.key] = p.copy(positionMs = 0)
                // A newer play of the song keeps the kept position but moves the card up.
                p.atMs > had.atMs -> best[p.key] = had.copy(atMs = p.atMs)
            }
        }
        val out = ArrayList<DialItem>(max)
        val seenKeys = HashSet<String>()
        val seenFiles = HashSet<TrackId>()
        if (current != null) {
            out += current
            seenKeys += current.key
            seenFiles += current.track
        }
        val rest = best.values
            .filter { item -> hiddenAt[item.key]?.let { item.atMs > it } ?: true }
            .sortedWith(compareByDescending<DialItem> { it.atMs }.thenBy { it.track.chatId }.thenBy { it.track.messageId })
        for (item in rest) {
            if (out.size >= max) break
            if (item.key in seenKeys || item.track in seenFiles) continue
            seenKeys += item.key
            seenFiles += item.track
            out += item
        }
        return out.take(max)
    }
}
