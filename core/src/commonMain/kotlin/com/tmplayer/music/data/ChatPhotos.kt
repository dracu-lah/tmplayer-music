package com.tmplayer.music.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A chat's picture as a row draws it: the small photo's file id, which belongs to the session it was
 * read in, and Telegram's inline preview (a few hundred bytes of blurred JPEG, shown at once while
 * the photo downloads; null when there is none).
 */
class ChatPhoto(val fileId: Int, val mini: ByteArray?)

/** What looking up a chat's picture gave. */
sealed interface PhotoLookup {
    /** Telegram answered: [photo] is the chat's picture, or null when it has none. */
    data class Found(val photo: ChatPhoto?) : PhotoLookup

    /** Telegram did not answer (not signed in, the chat is gone, a failure): asked again next time. */
    data object Unavailable : PhotoLookup
}

/**
 * The pictures of the chats the rows show by id alone (the Chats tab, Manage chats, a chat page):
 * the index keeps no file ids, as they belong to the session that read them, so each is asked of
 * TDLib ([lookup], `getChat`, answered from memory) when its row first shows, and remembered for the
 * session. A chat with no picture is remembered as such; a lookup that failed is not, so the row
 * asks again when it shows again. Bounded to [capacity] chats, emptied when the session changes
 * ([generation]); [changed] drops one chat's picture when Telegram says it changed and tells the
 * rows on screen through [changes].
 */
class ChatPhotos(
    private val lookup: suspend (chatId: Long) -> PhotoLookup,
    private val generation: () -> Long?,
    private val capacity: Int = CAPACITY,
) {
    private class Held(val photo: ChatPhoto?)

    private val held = object : LinkedHashMap<Long, Held>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Held>?): Boolean = size > capacity
    }
    private var heldFor: Long? = null

    /** Bumped by [changed], so an answer asked for before a change is not kept as the new picture. */
    private var epoch = 0L

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Chat ids whose picture changed; a row showing one asks [photo] again. */
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    /** The picture already known for [chatId], for the first frame; null when none or not known. */
    fun cached(chatId: Long): ChatPhoto? = synchronized(held) {
        if (!sameSession()) null else held[chatId]?.photo
    }

    /** [chatId]'s picture, asked of Telegram the first time; null when it has none or would not say. */
    suspend fun photo(chatId: Long): ChatPhoto? {
        val session = generation() ?: return null
        val asked = synchronized(held) {
            if (sameSession()) held[chatId]?.let { return it.photo }
            epoch
        }
        return when (val answer = lookup(chatId)) {
            is PhotoLookup.Found -> {
                synchronized(held) {
                    if (heldFor != session) {
                        held.clear()
                        heldFor = session
                    }
                    if (epoch == asked) held[chatId] = Held(answer.photo)
                }
                answer.photo
            }
            PhotoLookup.Unavailable -> null
        }
    }

    /** Telegram said [chatId]'s picture changed: forget it and tell the rows. */
    fun changed(chatId: Long) {
        synchronized(held) {
            held.remove(chatId)
            epoch++
        }
        _changes.tryEmit(chatId)
    }

    /** Forgets every picture (log out). */
    fun clear() = synchronized(held) {
        held.clear()
        heldFor = null
    }

    /** Called with the lock held. */
    private fun sameSession(): Boolean {
        val now = generation()
        if (now == null || now != heldFor) {
            held.clear()
            heldFor = now
            return false
        }
        return true
    }

    companion object {
        const val CAPACITY = 512
    }
}
