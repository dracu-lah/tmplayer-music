package com.tmplayer.music.desktop.player

import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.player.SavedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Repeat as the bar cycles it: off, then all, then one, then off again (the phone's order). */
enum class RepeatMode {
    Off, All, One;

    fun next(): RepeatMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }

    /** The number [SavedQueue] keeps: 0 off, 1 one, 2 all. */
    val saved: Int get() = when (this) {
        Off -> 0
        One -> 1
        All -> 2
    }

    companion object {
        fun fromSaved(value: Int): RepeatMode = when (value) {
            1 -> One
            2 -> All
            else -> Off
        }
    }
}

/**
 * One row of the queue: which file plays ([id], and [songKey] to find a version again if the file
 * has gone) and what the bar shows while it is not opened yet. [uid] tells two copies of the same
 * track in one queue apart, for a list's keys; it is never saved.
 */
data class QueueEntry(
    val id: TrackId,
    val songKey: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    /** A cover saved from the tags ([com.tmplayer.music.data.Library.coverFile]), blank when none. */
    val coverKey: String = "",
    /** Telegram's thumbnail for the file's message, 0 when none. */
    val thumbFileId: Int = 0,
    /** The key of the generated cover ([CoverArt.keyOf]). */
    val artKey: String = "",
    val uid: Long = nextUid(),
) {
    val saved: SavedQueue.Item get() = SavedQueue.Item(id.chatId, id.messageId, songKey)

    companion object {
        private val uids = AtomicLong()
        private fun nextUid(): Long = uids.incrementAndGet()

        fun of(song: Song): QueueEntry = QueueEntry(
            id = song.defaultTrack,
            songKey = song.groupKey,
            title = song.title,
            artist = ArtistDisplay.of(song.artist),
            album = song.album,
            durationMs = song.durationMs,
            coverKey = song.coverKey,
            thumbFileId = song.thumbFileId,
            artKey = CoverArt.keyOf(song.albumKey, song.groupKey),
        )

        /** A file played as that chat's own, with no song key. */
        fun of(track: Track, songKey: String = ""): QueueEntry = QueueEntry(
            id = track.id,
            songKey = songKey,
            title = track.title,
            artist = ArtistDisplay.of(track.artist),
            album = track.album,
            durationMs = track.durationMs,
            coverKey = track.coverKey,
            thumbFileId = track.thumbFileId,
            artKey = CoverArt.keyOf(track),
        )
    }
}

/** Where a skip lands: [index] in the entries, and whether it is the same track played again from the start. */
data class QueueStep(val index: Int, val restart: Boolean = false)

/**
 * The play queue as plain values, so every rule (shuffle, repeat, previous, removing the playing
 * track) is a unit test: a list of [entries], which one is [current], and the play [order] while
 * [shuffle] is on. Every change returns a new queue; nothing here touches the player.
 *
 * With shuffle on, [order] is a permutation of the entry indices with the current one at its place,
 * so a skip is one step in it; turning shuffle off goes on from the current entry in list order.
 * Everything coming in from outside (a saved queue, a count) is brought into range, never trusted.
 */
data class PlayQueue(
    val entries: List<QueueEntry> = emptyList(),
    val current: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val order: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val size: Int get() = entries.size
    val currentEntry: QueueEntry? get() = entries.getOrNull(current)

    /** Where [current] is in the play order: its own index without shuffle. */
    private val position: Int get() = if (shuffle) order.indexOf(current).coerceAtLeast(0) else current

    private fun at(position: Int): Int = if (shuffle) order[position] else position

    /** The entry that follows the playing one, for "Next up" and the next button's state; null at the end. */
    fun upNext(): QueueEntry? = nextStep(auto = false)?.takeIf { !it.restart }?.let { entries.getOrNull(it.index) }

    /**
     * Where Next goes. [auto] is the track ending by itself: repeat one then plays it again. At the
     * end the queue stops with repeat off (null) and goes round with repeat all.
     */
    fun nextStep(auto: Boolean): QueueStep? {
        if (isEmpty) return null
        if (auto && repeat == RepeatMode.One) return QueueStep(current, restart = true)
        val p = position
        return when {
            p + 1 < size -> QueueStep(at(p + 1))
            repeat == RepeatMode.All -> QueueStep(at(0), restart = size == 1)
            else -> null
        }
    }

    /**
     * Where Previous goes: back to the start of the playing track when it has played [RESTART_AFTER_MS]
     * or more, else the one before it. On the first track it starts again from the beginning.
     */
    fun previousStep(positionMs: Long): QueueStep? {
        if (isEmpty) return null
        if (positionMs >= RESTART_AFTER_MS) return QueueStep(current, restart = true)
        val p = position
        return when {
            p > 0 -> QueueStep(at(p - 1))
            repeat == RepeatMode.All && size > 1 -> QueueStep(at(size - 1))
            else -> QueueStep(current, restart = true)
        }
    }

    /** Whether Next does anything. */
    val hasNext: Boolean get() = !isEmpty && (repeat == RepeatMode.All || position + 1 < size)

    /** [index] made the playing one (a tap on a row). Out of range changes nothing. */
    fun jumpTo(index: Int): PlayQueue {
        if (index !in entries.indices) return this
        return copy(current = index)
    }

    fun withRepeat(mode: RepeatMode): PlayQueue = copy(repeat = mode)

    /**
     * Shuffle on or off. On: the play order is the other entries shuffled, behind the playing one,
     * and the ones before it in list order are shuffled in too, so every track still comes up.
     */
    fun withShuffle(on: Boolean, random: Random = Random.Default): PlayQueue {
        if (on == shuffle) return this
        if (!on) return copy(shuffle = false, order = emptyList())
        return copy(shuffle = true, order = shuffled(current, size, random))
    }

    /** Puts [added] right after the playing track. */
    fun playNext(added: List<QueueEntry>): PlayQueue {
        if (added.isEmpty()) return this
        if (isEmpty) return of(added, 0, shuffle, repeat)
        val at = (current + 1).coerceAtMost(size)
        val inserted = entries.toMutableList().apply { addAll(at, added) }
        return copy(entries = inserted, order = if (shuffle) insertIntoOrder(at, added.size, afterPosition = position) else emptyList())
            .capped()
    }

    /** Puts [added] at the end. */
    fun append(added: List<QueueEntry>): PlayQueue {
        if (added.isEmpty()) return this
        if (isEmpty) return of(added, 0, shuffle, repeat)
        val at = size
        return copy(entries = entries + added, order = if (shuffle) insertIntoOrder(at, added.size, afterPosition = size - 1) else emptyList())
            .capped()
    }

    /**
     * Takes entry [index] out. When it is the playing one the next in play order takes its place
     * (or the last one, when it was last); [QueueRemoval.currentChanged] says the player must open
     * what is current now, and [QueueRemoval.queue] is empty when it was the only one.
     */
    fun remove(index: Int): QueueRemoval {
        if (index !in entries.indices) return QueueRemoval(this, false)
        if (size == 1) return QueueRemoval(PlayQueue(repeat = repeat, shuffle = shuffle), true)
        val removedCurrent = index == current
        val p = position
        val after = when {
            !removedCurrent -> current
            p + 1 < size -> at(p + 1)
            p - 1 >= 0 -> at(p - 1)
            else -> 0
        }
        val shift: (Int) -> Int? = { i -> if (i == index) null else if (i > index) i - 1 else i }
        val next = copy(
            entries = entries.filterIndexed { i, _ -> i != index },
            current = shift(after) ?: 0,
            order = if (shuffle) order.mapNotNull(shift) else emptyList(),
        )
        return QueueRemoval(next, removedCurrent)
    }

    /** Moves entry [from] to [to]; the playing track stays the playing track. */
    fun move(from: Int, to: Int): PlayQueue {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        val list = entries.toMutableList()
        list.add(to, list.removeAt(from))
        val map: (Int) -> Int = { i ->
            when {
                i == from -> to
                from < to && i in (from + 1)..to -> i - 1
                from > to && i in to until from -> i + 1
                else -> i
            }
        }
        return copy(entries = list, current = map(current), order = if (shuffle) order.map(map) else emptyList())
    }

    /** The queue as [SavedQueue] keeps it, cut around the playing track when it is longer than it holds. */
    fun toSaved(positionMs: Long): SavedQueue? {
        if (isEmpty) return null
        val (window, index) = SavedQueue.window(entries.map { it.saved }, current)
        return SavedQueue(window, index, positionMs.coerceAtLeast(0), shuffle, repeat.saved)
    }

    private fun capped(): PlayQueue {
        if (size <= SavedQueue.MAX_ITEMS) return this
        // Past what a saved queue keeps: the far end goes, never the playing track.
        var result = this
        while (result.size > SavedQueue.MAX_ITEMS) {
            val last = result.size - 1
            result = result.remove(if (result.current == last) last - 1 else last).queue
        }
        return result
    }

    private fun insertIntoOrder(at: Int, count: Int, afterPosition: Int): List<Int> {
        val shifted = order.map { if (it >= at) it + count else it }.toMutableList()
        val where = (afterPosition + 1).coerceIn(0, shifted.size)
        shifted.addAll(where, (at until at + count).toList())
        return shifted
    }

    companion object {
        /** Previous starts the track again when it has played at least this long (as the phone does). */
        const val RESTART_AFTER_MS = 3_000L

        /** A queue of [entries] with [start] playing, cut to what a saved queue holds, around it. */
        fun of(
            entries: List<QueueEntry>,
            start: Int,
            shuffle: Boolean = false,
            repeat: RepeatMode = RepeatMode.Off,
            random: Random = Random.Default,
        ): PlayQueue {
            if (entries.isEmpty()) return PlayQueue(shuffle = shuffle, repeat = repeat)
            val first = start.coerceIn(0, entries.lastIndex)
            val begin = (first - SavedQueue.MAX_ITEMS / 4).coerceIn(0, maxOf(0, entries.size - SavedQueue.MAX_ITEMS))
            val window = entries.subList(begin, minOf(entries.size, begin + SavedQueue.MAX_ITEMS))
            val index = first - begin
            val order = if (shuffle) shuffled(index, window.size, random) else emptyList()
            return PlayQueue(window, index, shuffle, repeat, order)
        }

        /** The queue [saved] describes, over [entries] resolved from it (some may have gone): paused at the same place. */
        fun restored(saved: SavedQueue, entries: List<QueueEntry?>, random: Random = Random.Default): PlayQueue {
            val kept = entries.filterNotNull()
            if (kept.isEmpty()) return PlayQueue()
            // The playing entry's place among the ones that survived.
            val playing = entries.getOrNull(saved.current)
            val start = if (playing != null) kept.indexOf(playing) else {
                // It is gone: the next one that survived, else the last.
                entries.drop(saved.current).filterNotNull().firstOrNull()?.let { kept.indexOf(it) } ?: kept.lastIndex
            }
            return of(kept, start.coerceAtLeast(0), saved.shuffle, RepeatMode.fromSaved(saved.repeat), random)
        }

        private fun shuffled(current: Int, size: Int, random: Random): List<Int> {
            val others = (0 until size).filter { it != current }.shuffled(random)
            return listOf(current) + others
        }
    }
}

/** What taking an entry out did: the new queue, and whether the player must now open another track. */
data class QueueRemoval(val queue: PlayQueue, val currentChanged: Boolean)
