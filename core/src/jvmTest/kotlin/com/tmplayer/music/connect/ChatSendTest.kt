package com.tmplayer.music.connect

import com.tmplayer.music.data.ChatFeed
import com.tmplayer.music.data.ChatLoader
import com.tmplayer.music.data.ChatOrder
import com.tmplayer.music.data.ChatProblem
import com.tmplayer.music.data.ChatStore
import com.tmplayer.music.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Send to TV for a whole chat: paged in the background up to the cap, never whole, cancellable. */
class ChatSendTest {

    private class Store(count: Int, private val failAfterPages: Int = Int.MAX_VALUE) : ChatStore {
        val rows = TreeMap<Long, Track>().apply { for (i in 1..count) put(i.toLong(), track(i.toLong())) }
        var pages = 0
        var biggestPage = 0

        override suspend fun scanDone(chatId: Long): Boolean? = true

        override suspend fun indexPage(chatId: Long, oldestFirst: Boolean, after: Long?, limit: Int): List<Track> {
            if (++pages > failAfterPages) throw java.io.IOException("disk gone")
            val all = if (oldestFirst) rows.values.asSequence() else rows.descendingMap().values.asSequence()
            val page = all.filter { after == null || if (oldestFirst) it.messageId > after else it.messageId < after }.take(limit).toList()
            biggestPage = maxOf(biggestPage, page.size)
            return page
        }

        override suspend fun byIds(chatId: Long, messageIds: Collection<Long>) = messageIds.mapNotNull { rows[it] }.associateBy { it.messageId }
        override suspend fun addMissing(tracks: List<Track>) = Unit
    }

    private fun loader(store: Store, order: ChatOrder = ChatOrder.OldestFirst) =
        ChatLoader(7, order, store, { _, _ -> error("the index holds this chat") }, MutableStateFlow(true))

    @Test
    fun aBigChatIsCutAtTheCapInTheChatsOrderAndPagedNotLoadedWhole() = runTest {
        val store = Store(count = 12_000)
        val progress = ArrayList<Int>()
        val out = ChatSend.collect(loader(store), onProgress = { progress += it })
        assertEquals(ChatSend.CAP, out.refs.size)
        assertTrue(out.capped)
        assertEquals(null, out.stopped)
        assertEquals((1L..ChatSend.CAP).toList(), out.refs.map { it.messageId })
        // Read a page at a time, and stopped at the cap instead of reading all 12,000.
        assertTrue(store.biggestPage <= ChatLoader.PAGE)
        assertTrue(store.pages < 12_000 / ChatLoader.PAGE)
        // Progress only goes up and ends at the cap.
        assertEquals(progress.sorted(), progress)
        assertEquals(ChatSend.CAP, progress.last())
    }

    @Test
    fun newestFirstFollowsTheChatsOrder() = runTest {
        val out = ChatSend.collect(loader(Store(count = 300), ChatOrder.NewestFirst))
        assertEquals((300L downTo 1L).toList(), out.refs.map { it.messageId })
        assertFalse(out.capped)
    }

    @Test
    fun aSmallChatAndAnEmptyChatEndWithoutTheCap() = runTest {
        val small = ChatSend.collect(loader(Store(count = 250)))
        assertEquals(250, small.refs.size)
        assertFalse(small.capped)
        val empty = ChatSend.collect(loader(Store(count = 0)))
        assertTrue(empty.refs.isEmpty())
        assertFalse(empty.capped)
    }

    @Test
    fun aChatExactlyAtTheCapIsNotCapped() = runTest {
        val out = ChatSend.collect(loader(Store(count = ChatSend.CAP)), cap = ChatSend.CAP)
        assertEquals(ChatSend.CAP, out.refs.size)
        assertFalse(out.capped)
    }

    @Test
    fun aFailureKeepsWhatWasReadAndSaysSo() = runTest {
        val out = ChatSend.collect(loader(Store(count = 1_000, failAfterPages = 3)))
        assertEquals(300, out.refs.size)
        assertIs<ChatProblem.Failed>(out.stopped)
    }

    @Test
    fun cancellingStopsTheReading() = runTest {
        val store = Store(count = 12_000)
        var cancelled = false
        try {
            ChatSend.collect(loader(store), onProgress = { if (it >= 300) throw CancellationException("cancel") })
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(store.pages < 12_000 / ChatLoader.PAGE)
    }
}

private fun track(id: Long) = Track(
    chatId = 7, messageId = id, fileId = id.toInt(), uniqueId = "u$id", fileName = "Song $id.mp3", mime = "audio/mpeg",
    sizeBytes = 1_000, dateSec = id, title = "Song $id", artist = "", durationMs = 200_000, groupKey = "g$id",
)
