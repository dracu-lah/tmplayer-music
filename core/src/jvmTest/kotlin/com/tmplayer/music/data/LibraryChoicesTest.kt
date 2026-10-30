package com.tmplayer.music.data

import com.tmplayer.music.library.ClipRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the user chose survives a restart: the Pause above all, so a paused reader stays paused. */
class LibraryChoicesTest {

    @Test
    fun pauseSurvivesARestart() {
        val store = MemoryKeyValues()
        val first = LibraryChoices(store)
        assertFalse(first.paused.value)
        first.setPaused(true)
        assertTrue(LibraryChoices(store).paused.value, "a new start reads the Pause back")
        LibraryChoices(store).setPaused(false)
        assertFalse(LibraryChoices(store).paused.value)
    }

    @Test
    fun mobileDataAnswerAndSwitchesSurvive() {
        val store = MemoryKeyValues()
        val choices = LibraryChoices(store)
        assertFalse(choices.mobileAsked.value)
        assertEquals(ClipRules.DEFAULT_MASK, choices.hiddenMask, "short clips and voice files hidden by default")
        choices.setMobileAllowed(false)
        choices.setShowShort(true)
        val again = LibraryChoices(store)
        assertTrue(again.mobileAsked.value, "a no is an answer too: never asked again")
        assertFalse(again.mobileAllowed.value)
        assertEquals(ClipRules.VOICE, again.hiddenMask)
    }

    @Test
    fun dataUsedIsSavedNowAndThenAndOnFlush() {
        val store = MemoryKeyValues()
        var clock = 100_000L
        val choices = LibraryChoices(store) { clock }
        choices.addTagBytes(1_000)
        assertEquals(1_000, LibraryChoices(store).tagBytes.value)
        clock += 1_000
        choices.addTagBytes(500)
        assertEquals(1_500, choices.tagBytes.value)
        assertEquals(1_000, LibraryChoices(store).tagBytes.value, "not written again within the gap")
        choices.flush()
        assertEquals(1_500, LibraryChoices(store).tagBytes.value)
        choices.addTagBytes(-5)
        assertEquals(1_500, choices.tagBytes.value)
    }

    @Test
    fun chatOrderIsRememberedPerChat() {
        val store = MemoryKeyValues()
        val choices = LibraryChoices(store)
        assertEquals(ChatOrder.OldestFirst, choices.chatOrder(7))
        choices.setChatOrder(7, ChatOrder.NewestFirst)
        assertEquals(ChatOrder.NewestFirst, LibraryChoices(store).chatOrder(7))
        assertEquals(ChatOrder.OldestFirst, LibraryChoices(store).chatOrder(8))
        store.putString("chat_order_9", "garbage")
        assertEquals(ChatOrder.OldestFirst, LibraryChoices(store).chatOrder(9))
    }

    @Test
    fun aStoreThatThrowsGivesTheDefaults() {
        val broken = object : KeyValues {
            override fun getBoolean(key: String, default: Boolean): Boolean = error("damaged")
            override fun putBoolean(key: String, value: Boolean) = error("damaged")
            override fun getLong(key: String, default: Long): Long = error("damaged")
            override fun putLong(key: String, value: Long) = error("damaged")
            override fun getString(key: String): String? = error("damaged")
            override fun putString(key: String, value: String) = error("damaged")
        }
        val choices = LibraryChoices(broken)
        assertFalse(choices.paused.value)
        choices.setPaused(true)
        assertTrue(choices.paused.value, "kept for this run")
        assertEquals(ChatOrder.OldestFirst, choices.chatOrder(1))
    }
}
