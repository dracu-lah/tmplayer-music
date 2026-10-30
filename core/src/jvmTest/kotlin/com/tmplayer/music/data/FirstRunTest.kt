package com.tmplayer.music.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Start with Saved Messages": Saved Messages first, only chats with audio, at most five. */
class FirstRunTest {

    private fun chat(id: Long, saved: Boolean = false, archived: Boolean = false) =
        ChatEntry(chatId = id, title = "Chat $id", savedMessages = saved, archived = archived, photoFileId = 0)

    @Test
    fun savedMessagesFirstThenTheMostAudio() {
        val chats = listOf(chat(1, saved = true)) + (2L..10L).map { chat(it) }
        val counts = mapOf(1L to 3, 2L to 50, 3L to 0, 4L to 500, 5L to 50, 6L to 7, 7L to 1, 8L to 900)
        val picked = FirstRun.pick(chats, counts)
        assertEquals(listOf(1L, 8L, 4L, 2L, 5L), picked.map { it.chatId })
    }

    @Test
    fun onlyChatsWithAudioAndNeverMoreThanFive() {
        val chats = listOf(chat(1, saved = true)) + (2L..20L).map { chat(it) }
        val counts = (1L..20L).associateWith { if (it % 2 == 0L) 10 else 0 }
        val picked = FirstRun.pick(chats, counts)
        assertEquals(5, picked.size)
        assertTrue(picked.none { it.savedMessages }, "Saved Messages has no audio here")
        assertTrue(picked.all { (counts[it.chatId] ?: 0) > 0 })
    }

    @Test
    fun unknownCountsAndArchivedChatsAreLeftOut() {
        val chats = listOf(chat(1, saved = true), chat(2), chat(3, archived = true), chat(4))
        val picked = FirstRun.pick(chats, mapOf(1L to 2, 3L to 99, 4L to 1))
        assertEquals(listOf(1L, 4L), picked.map { it.chatId })
        assertTrue(FirstRun.pick(chats, emptyMap()).isEmpty())
        assertTrue(FirstRun.pick(emptyList(), mapOf(1L to 1)).isEmpty())
    }

    @Test
    fun tiesKeepTelegramsOrder() {
        val chats = (1L..4L).map { chat(it) }
        assertEquals(listOf(1L, 2L, 3L, 4L), FirstRun.pick(chats, (1L..4L).associateWith { 5 }).map { it.chatId })
    }

    @Test
    fun countsAreAskedForSavedMessagesAndTheTopOfTheList() {
        val chats = (1L..100L).map { chat(it, archived = it > 90) } + chat(500, saved = true)
        val asked = FirstRun.toCount(chats)
        assertEquals(500L, asked.first().chatId)
        assertEquals(FirstRun.COUNT_AHEAD + 1, asked.size)
        assertTrue(asked.none { it.archived })
    }
}
