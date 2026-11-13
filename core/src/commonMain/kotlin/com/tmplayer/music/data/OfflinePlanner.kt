package com.tmplayer.music.data

import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId

/**
 * What a Keep offline asks for before anything is written: which of the tracks are new, which fit
 * in the free space, and whether the step is big enough to ask first. Pure, so the disk-full and
 * duplicate cases are unit tests (AGENTS.md: space and size, several at once).
 *
 * The same file posted in three chats (one unique id) is kept once. A track whose size Telegram
 * does not say is counted as [CachePolicy.UNKNOWN_SIZE], so it is not waved through a full disk.
 */
object OfflinePlanner {

    /** Why a track was not kept; the summary counts each. */
    enum class Skip { AlreadyKept, Duplicate, NoSpace, NotAllowed, Gone, Stalled, Failed }

    data class Plan(
        /** The tracks to keep, in the order asked, that fit in the free space. */
        val toKeep: List<QueuedTrack>,
        /** New tracks left out because they do not fit. */
        val tooBig: List<QueuedTrack>,
        val alreadyKept: Int,
        val duplicates: Int,
        /** The bytes of everything new asked for, fitting or not. */
        val neededBytes: Long,
        /** The bytes of [toKeep]. */
        val keepBytes: Long,
        val freeBytes: Long,
    ) {
        val fitsAll: Boolean get() = tooBig.isEmpty()
        val nothingNew: Boolean get() = toKeep.isEmpty() && tooBig.isEmpty()

        /** A big step is confirmed first: many tracks, or many bytes. */
        val needsConfirm: Boolean get() = toKeep.size >= CONFIRM_TRACKS || keepBytes >= CONFIRM_BYTES
    }

    /**
     * Plans keeping [tracks]. [kept] and [queued] are what is kept or waiting already, [keptUniques]
     * the unique ids of kept files, [freeBytes] the free space of the volume.
     */
    fun plan(
        tracks: List<Track>,
        kept: Set<TrackId>,
        queued: Set<TrackId>,
        keptUniques: Set<String>,
        freeBytes: Long,
        reserveBytes: Long = CachePolicy.HEADROOM,
    ): Plan {
        val seenIds = HashSet<TrackId>()
        val seenUniques = HashSet<String>()
        val fresh = ArrayList<QueuedTrack>()
        var already = 0
        var duplicates = 0
        for (track in tracks) {
            if (!seenIds.add(track.id)) continue
            if (track.id in kept || track.id in queued) {
                already++
                continue
            }
            val unique = track.uniqueId.takeIf { it.isNotEmpty() && !STAND_IN.matches(it) }
            if (unique != null && (unique in keptUniques || !seenUniques.add(unique))) {
                duplicates++
                continue
            }
            fresh += QueuedTrack(
                chatId = track.chatId,
                messageId = track.messageId,
                title = track.title,
                artist = track.artist,
                fileName = track.fileName,
                sizeBytes = track.sizeBytes.coerceAtLeast(0),
                uniqueId = track.uniqueId,
            )
        }
        val free = freeBytes.coerceAtLeast(0)
        var budget = (free - reserveBytes).coerceAtLeast(0)
        val fit = ArrayList<QueuedTrack>()
        val left = ArrayList<QueuedTrack>()
        var needed = 0L
        var keep = 0L
        for (item in fresh) {
            val size = planned(item.sizeBytes)
            needed = saturatingAdd(needed, size)
            if (size <= budget) {
                budget -= size
                keep = saturatingAdd(keep, size)
                fit += item
            } else {
                left += item
            }
        }
        return Plan(fit, left, already, duplicates, needed, keep, free)
    }

    /** The id the mapper makes up when Telegram gives no unique id: it names nothing across chats. */
    private val STAND_IN = Regex("f\\d+")

    /** What a track is counted as when planning: its size, or the stand-in for an unknown one. */
    fun planned(sizeBytes: Long): Long = if (sizeBytes > 0) sizeBytes else CachePolicy.UNKNOWN_SIZE

    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    /** Ask first from this many tracks. */
    const val CONFIRM_TRACKS = 25

    /** Ask first from this many bytes. */
    const val CONFIRM_BYTES = 1024L * 1024 * 1024
}
