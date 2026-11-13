package com.tmplayer.music.desktop.ui.library

import com.tmplayer.music.data.CachePolicy
import com.tmplayer.music.data.OfflinePlanner
import com.tmplayer.music.data.QueuedTrack
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId

/**
 * Plans Keep offline for a chat a page of tracks at a time, so a chat of 100,000 files never has all
 * its [Track] objects in memory at once: only the small [QueuedTrack] entries are kept between
 * pages. [OfflinePlanner] does the per page work (what is already kept, queued or a duplicate); this
 * adds what only the whole run knows: the same file on two pages, and the free space spent as the
 * pages add up.
 */
class PagedPlanner(
    private val kept: Set<TrackId>,
    private val queued: Set<TrackId>,
    private val keptUniques: Set<String>,
    private val freeBytes: Long,
    private val reserveBytes: Long = CachePolicy.HEADROOM,
) {
    private val fresh = ArrayList<QueuedTrack>()
    private val seenUniques = HashSet<String>()
    private var already = 0
    private var duplicates = 0

    /** Adds one page; the page itself can be dropped afterwards. */
    fun add(page: List<Track>) {
        // The space is judged once, at the end: here everything new is let through.
        val part = OfflinePlanner.plan(page, kept, queued, keptUniques, freeBytes = Long.MAX_VALUE, reserveBytes = 0)
        already += part.alreadyKept
        duplicates += part.duplicates
        for (entry in part.toKeep + part.tooBig) {
            val unique = entry.uniqueId.takeIf { it.isNotEmpty() && !STAND_IN.matches(it) }
            if (unique != null && !seenUniques.add(unique)) {
                duplicates++
                continue
            }
            fresh += entry
        }
    }

    /** The plan for everything added: what fits in the free space, in the order asked, and what does not. */
    fun finish(): OfflinePlanner.Plan {
        val free = freeBytes.coerceAtLeast(0)
        var budget = (free - reserveBytes).coerceAtLeast(0)
        val fit = ArrayList<QueuedTrack>()
        val left = ArrayList<QueuedTrack>()
        var needed = 0L
        var keep = 0L
        for (item in fresh) {
            val size = OfflinePlanner.planned(item.sizeBytes)
            needed = saturatingAdd(needed, size)
            if (size <= budget) {
                budget -= size
                keep = saturatingAdd(keep, size)
                fit += item
            } else {
                left += item
            }
        }
        return OfflinePlanner.Plan(fit, left, already, duplicates, needed, keep, free)
    }

    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private companion object {
        /** The id the mapper makes up when Telegram gives no unique id: it names nothing across chats. */
        val STAND_IN = Regex("f\\d+")
    }
}
