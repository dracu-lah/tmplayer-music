package com.tmplayer.music.cast

import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.AudioFormat

/** One queue item as the cast plan sees it. [id] is the queue item's media id. */
data class CastEntry(
    val id: String,
    val title: String,
    val format: AudioFormat,
    val mime: String,
    val fileName: String,
)

/** A playable entry: where it is in the queue the plan was made from, and the Content-Type to send it with. */
data class CastItem(val sourceIndex: Int, val entry: CastEntry, val mime: String)

/** An entry left out, and why. */
data class CastSkip(val sourceIndex: Int, val entry: CastEntry, val reason: CastRefusal)

/**
 * What goes to the Cast device from a queue. A receiver takes a bounded queue (its messages are
 * small), so [window] is a few items around where playback is and [upcoming] is the rest, handed
 * over in chunks as playback gets near the end of what is loaded. [skipped] are the tracks it
 * cannot play. [startIndex] is where playback starts in [window], or -1 when nothing can play.
 */
data class CastPlan(
    val window: List<CastItem>,
    val startIndex: Int,
    val upcoming: List<CastItem>,
    val skipped: List<CastSkip>,
)

/** The queue rules of casting, pure: what is sent, what is left out, and how a finished cast folds back into the local queue. */
object CastQueue {
    /** How many items go to the receiver at once. */
    const val WINDOW = 20

    /** How many items before the current one are loaded, so Previous works. */
    const val BEFORE = 3

    /** How many items are added at a time as playback nears the end of what is loaded. */
    const val CHUNK = 10

    /** More is added when this few items are left after the current one. */
    const val LOW_WATER = 5

    /**
     * Plans the cast of [entries] (in the order they will play) with [current] playing. A current
     * item that cannot play starts the next one that can, or the nearest one before it.
     */
    fun plan(entries: List<CastEntry>, current: Int, window: Int = WINDOW, before: Int = BEFORE): CastPlan {
        val playable = ArrayList<CastItem>()
        val skipped = ArrayList<CastSkip>()
        entries.forEachIndexed { index, entry ->
            when (val verdict = CastFormats.check(entry.format, entry.mime, entry.fileName)) {
                is CastVerdict.Playable -> playable += CastItem(index, entry, verdict.mime)
                is CastVerdict.Refused -> skipped += CastSkip(index, entry, verdict.reason)
            }
        }
        if (playable.isEmpty()) return CastPlan(emptyList(), -1, emptyList(), skipped)
        val at = current.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        val startPos = playable.indexOfFirst { it.sourceIndex >= at }.takeIf { it >= 0 } ?: playable.lastIndex
        val from = (startPos - before.coerceAtLeast(0)).coerceAtLeast(0)
        val to = minOf(playable.size, from + window.coerceAtLeast(1))
        return CastPlan(playable.subList(from, to).toList(), startPos - from, playable.subList(to, playable.size).toList(), skipped)
    }

    /** Whether [entry] can be added to a running cast. */
    fun verdict(entry: CastEntry): CastVerdict = CastFormats.check(entry.format, entry.mime, entry.fileName)

    /**
     * The local queue after a cast: the receiver's queue as it ended ([finalIds]) with every item of
     * the [original] queue that was never sent ([sent] holds the ids that were) put back after the
     * nearest earlier item of the original that is in the result, or at the front when none is.
     * Something removed from the cast queue stays removed; something added there stays added.
     */
    fun mergeBack(original: List<String>, sent: Set<String>, finalIds: List<String>): List<String> {
        val result = ArrayList(finalIds)
        var front = 0
        original.forEachIndexed { index, id ->
            if (id in sent) return@forEachIndexed
            var anchor = -1
            var back = index - 1
            while (back >= 0 && anchor < 0) {
                anchor = result.indexOf(original[back])
                back--
            }
            if (anchor >= 0) result.add(anchor + 1, id) else result.add(front++, id)
        }
        return result
    }

    /**
     * The sentence for the tracks left out ([skipped]), or null when none were. One track names
     * itself; several are counted, with the formats that were refused.
     */
    fun summary(skipped: List<CastSkip>): String? {
        if (skipped.isEmpty()) return null
        val formats = skipped.map { it.reason }.distinct().joinToString(", ") { label(it) }
        return if (skipped.size == 1) {
            L.castSkippedOne(skipped[0].entry.title.ifBlank { L.castUntitled }, formats)
        } else {
            L.castSkippedMany(skipped.size, formats)
        }
    }

    private fun label(reason: CastRefusal): String = when (reason) {
        CastRefusal.Alac -> L.castFormatAlac
        CastRefusal.Aiff -> L.castFormatAiff
        CastRefusal.Unknown -> L.castFormatUnknown
    }
}
