package com.tmplayer.music.player

import com.tmplayer.music.library.TrackId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The queue as it is kept on disk between runs: every item, which one is current, where in it, and
 * the shuffle and repeat modes. Saved whole and restored whole, and restored paused: nothing autoplays.
 *
 * Items are the version's track id (chat and message) plus the song's key, so a queue survives a
 * version that disappears: the song is looked up by its key and its default version plays.
 */
@Serializable
data class SavedQueue(
    val items: List<Item>,
    val current: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    /** Player.REPEAT_MODE_*: 0 off, 1 one, 2 all. */
    val repeat: Int = 0,
) {
    @Serializable
    data class Item(val chatId: Long, val messageId: Long, val songKey: String = "") {
        val id: TrackId get() = TrackId(chatId, messageId)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The most items a saved queue keeps; a longer queue is cut around the current item. */
        const val MAX_ITEMS = 2_000

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The queue in [text], or null when it is missing, cut short or not a queue. Values out of
         * range are brought back in: a current index past the end points at the first item, a
         * negative position is the start, an unknown repeat mode is off.
         */
        fun decode(text: String?): SavedQueue? {
            if (text.isNullOrBlank() || text.length > MAX_CHARS) return null
            val raw = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            val items = raw.items.filter { it.chatId != 0L && it.messageId != 0L }.take(MAX_ITEMS)
            if (items.isEmpty()) return null
            return SavedQueue(
                items = items,
                current = raw.current.takeIf { it in items.indices } ?: 0,
                positionMs = raw.positionMs.coerceAtLeast(0),
                shuffle = raw.shuffle,
                repeat = raw.repeat.takeIf { it in 0..2 } ?: 0,
            )
        }

        /** [items] cut to [MAX_ITEMS] around [current], with the index moved to match. */
        fun window(items: List<Item>, current: Int): Pair<List<Item>, Int> {
            if (items.size <= MAX_ITEMS) return items to current.coerceIn(0, maxOf(0, items.lastIndex))
            val start = (current - MAX_ITEMS / 4).coerceIn(0, items.size - MAX_ITEMS)
            return items.subList(start, start + MAX_ITEMS) to (current - start)
        }

        private const val MAX_CHARS = 4 * 1024 * 1024
    }
}
