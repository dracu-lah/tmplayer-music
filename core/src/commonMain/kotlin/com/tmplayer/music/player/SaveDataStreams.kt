// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/player/SaveDataStreams.kt.
package com.tmplayer.music.player

import com.tmplayer.music.data.OfflineDownloads

/**
 * The arithmetic of "Save mobile data": on a metered connection a streamed video is fetched only
 * [WINDOW_BYTES] ahead of the read position instead of to the end of the file, and the request is
 * extended as reading nears the end of what was asked for.
 *
 * Unlike [LowStorageWindow] nothing is ever deleted: the point is to stop paying for bytes nobody
 * watches, not to save disk, so everything already fetched stays, and seeking back over it costs
 * nothing. The ranges asked for are only kept to know where the end of the fetched stretch is.
 *
 * Whether the connection is metered is asked at every decision, so a stream that moves to Wi-Fi
 * asks for the rest of the file at its next read, and one that moves to mobile data is bounded
 * again the same way.
 *
 * Pure, so the plan is tested without TDLib.
 */
object SaveDataWindow {

    /**
     * How far ahead of the read position a request reaches on mobile data: a few minutes of a
     * 1080p film, enough to ride out a patchy signal, little enough that stopping early wastes
     * next to nothing.
     */
    const val WINDOW_BYTES = 100L * 1024 * 1024

    /**
     * How close reading may get to the end of what was asked for before the request is extended.
     * Wider than Low storage mode's roll margin: extending deletes nothing, so it can start early
     * and keep the download running ahead of the player instead of letting it catch up.
     */
    const val EXTEND_MARGIN_BYTES = 16L * 1024 * 1024

    /** An extension starts on a part boundary: Telegram hands files out in parts of this size. */
    const val ALIGN_BYTES = 1024L * 1024

    /**
     * One download request.
     *
     * @property limit for TDLib's `downloadFile`: 0 for the rest of the file, which is what an
     *   unmetered connection gets. A bounded request is never 0.
     * @property ranges what the bounded requests so far have asked for, to be kept for the next
     *   decision. An unbounded request adds nothing: a later bounded one replaces it in TDLib, and
     *   how far it got before that is not known here.
     */
    data class Request(val limit: Long, val ranges: List<LowStorageWindow.Range>) {
        val bounded: Boolean get() = limit > 0
    }

    /**
     * The request for a download from [offset] of a file of [size] bytes, given the [ranges]
     * asked for so far. Bounded to [window] bytes, or the rest of the file when that is closer,
     * only while [metered]; otherwise the rest of the file, as without the setting.
     */
    fun plan(
        ranges: List<LowStorageWindow.Range>,
        offset: Long,
        size: Long,
        metered: Boolean,
        window: Long = WINDOW_BYTES,
    ): Request {
        if (!metered) return Request(0, ranges)
        val end = if (size > 0) minOf(size, offset + window) else offset + window
        val limit = (end - offset).coerceAtLeast(1)
        return Request(limit, LowStorageWindow.merge(ranges + LowStorageWindow.Range(offset, end)))
    }

    /**
     * Whether a read at [position] should send a fresh request before it is served, given what
     * was asked for so far and whether the last request ([lastBounded]) was bounded.
     *
     * On mobile data: when the last request was unbounded (the connection was Wi-Fi then, and
     * TDLib would otherwise go on to the end of the file), or when reading has come within
     * [EXTEND_MARGIN_BYTES] of the end of the bounded stretch it is in, and that stretch stops
     * short of the end of the file. A read outside every stretch is a seek, which the seek path
     * already asks for. On Wi-Fi: when the last request was bounded, so the bound lifts at the
     * next read rather than at the next extension.
     */
    fun extendDue(
        position: Long,
        ranges: List<LowStorageWindow.Range>,
        size: Long,
        metered: Boolean,
        lastBounded: Boolean,
    ): Boolean {
        if (!metered) return lastBounded
        if (!lastBounded) return true
        val here = LowStorageWindow.merge(ranges).firstOrNull { position >= it.start && position < it.end }
            ?: return false
        if (size > 0 && here.end >= size) return false
        return position >= here.end - EXTEND_MARGIN_BYTES
    }

    /** Where an extension at [position] asks TDLib to start: at it, on a part boundary. */
    fun extendStart(position: Long): Long = position.coerceAtLeast(0) / ALIGN_BYTES * ALIGN_BYTES
}

/**
 * The videos streaming with "Save mobile data" right now, and what each has asked TDLib for.
 *
 * The player decides, once per video, whether the setting applies: it is on, the platform has a
 * metered state to report, the video is only being watched (not one the viewer downloaded or is
 * downloading), and Low storage mode does not already bound it, since that mode's own rolling
 * window wins. It says so with [allow], handing over how to ask whether the connection is metered
 * now, and takes it back with [revoke]. [TdByteWindow] asks here before every download request.
 *
 * Held per file id rather than per reader, like [LowStorageStreams]: the player opens a fresh
 * reader at each seek, and what has been asked for belongs to the file.
 */
object SaveDataStreams {

    private class State(
        val metered: () -> Boolean,
        var ranges: List<LowStorageWindow.Range> = emptyList(),
        var lastBounded: Boolean = false,
        var requested: Boolean = false,
    )

    private val files = HashMap<Int, State>()

    /**
     * Lets [fileId] stream bounded while [metered] says the connection is metered.
     *
     * @return this player's hold on the decision, for [revoke] on the way out.
     */
    @Synchronized
    fun allow(fileId: Int, metered: () -> Boolean): Any = State(metered).also { files[fileId] = it }

    /** Back to streaming the whole file, as without the setting. */
    @Synchronized
    fun revoke(fileId: Int) {
        files.remove(fileId)
    }

    /** Takes back only what [allow] returned [hold] for, as [LowStorageStreams.revoke] does. */
    @Synchronized
    fun revoke(fileId: Int, hold: Any) {
        if (files[fileId] === hold) files.remove(fileId)
    }

    /** Whether [fileId] streams with the setting, as of now. */
    @Synchronized
    fun isActive(fileId: Int): Boolean = fileId in files && applies(fileId)

    /**
     * The limit for a download of [fileId] from [offset], or null when the setting does not
     * apply and the caller does what it always has. 0 means the rest of the file. Records the
     * request, so call it only for a request that is about to be made.
     */
    @Synchronized
    fun plan(fileId: Int, offset: Long, size: Long): Long? {
        val state = files[fileId] ?: return null
        if (!applies(fileId)) return null
        val request = SaveDataWindow.plan(state.ranges, offset, size, metered = state.metered())
        state.ranges = request.ranges
        state.lastBounded = request.bounded
        state.requested = true
        return request.limit
    }

    /** Whether a read at [position] should ask TDLib again before it is served. */
    @Synchronized
    fun extendDue(fileId: Int, position: Long, size: Long): Boolean {
        val state = files[fileId] ?: return false
        // Before the first request there is nothing to extend: opening the file makes that one.
        if (!state.requested || !applies(fileId)) return false
        return SaveDataWindow.extendDue(position, state.ranges, size, state.metered(), state.lastBounded)
    }

    /**
     * Not for a file the viewer is downloading (the download queue shares it in TDLib, and it is
     * not ours to cut short), nor for one Low storage mode bounds already.
     */
    private fun applies(fileId: Int): Boolean =
        !OfflineDownloads.isDownloading(fileId) && !LowStorageStreams.isRolling(fileId)
}
