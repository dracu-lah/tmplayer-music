package com.tmplayer.music.data

import com.tmplayer.music.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat page from Telegram: the audio and document searches merged by message id, oldest or newest
 * first, keyset cursors, flood waits waited out by the call that got them; and the loader that puts
 * the index's rows first.
 */
class ChatFeedTest {

    /**
     * A chat as TDLib's search sees it: [audio] and [documents] are message ids; [other] are document
     * messages that are not audio (a zip), which move the cursor and give no row. Newest first pages
     * down from a cursor (exclusive) with nextFromMessageId; a negative offset pages up from the
     * cursor (inclusive) as TDLib does. Pages are [page] long.
     */
    private class FakeChat(
        val audio: List<Long>,
        val documents: List<Long>,
        val other: List<Long> = emptyList(),
        val page: Int = 10,
    ) {
        val calls = ArrayList<Triple<ScanPass, Long, Int>>()
        var floodOnce = 0
        var fail: String? = null

        fun fetch(pass: ScanPass, from: Long, offset: Int, limit: Int): PageResult {
            calls += Triple(pass, from, offset)
            fail?.let { return PageResult.Failed(it) }
            if (floodOnce > 0) {
                val s = floodOnce
                floodOnce = 0
                return PageResult.FloodWait(s)
            }
            val all = (if (pass == ScanPass.Audio) audio else documents + other).sortedDescending()
            val n = minOf(page, limit)
            val ids = if (offset == 0) {
                all.filter { from == 0L || it < from }.take(n)
            } else {
                all.filter { it >= from }.sorted().take(n).sortedDescending()
            }
            val next = if (offset == 0 && ids.size == n && all.any { it < ids.min() }) ids.min() else 0L
            return PageResult.Page(ids, ids.filter { it !in other }.map { Mapped.Audio(track(it)) }, next)
        }
    }

    private fun feed(chat: FakeChat, order: ChatOrder, waits: MutableList<Int> = ArrayList()) =
        ChatFeed(order, { pass, from, offset, limit -> chat.fetch(pass, from, offset, limit) }, onWait = { waits += it }, wait = {})

    private suspend fun drain(feed: ChatFeed): List<Long> {
        val got = ArrayList<Long>()
        var steps = 0
        while (steps++ < 1_000) {
            when (val step = feed.next()) {
                is FeedStep.Added -> got += step.tracks.map { it.messageId }
                FeedStep.End -> return got
                is FeedStep.Failed -> error(step.message)
            }
        }
        error("did not end")
    }

    private val audio = listOf(5L, 9, 12, 20, 21, 33, 40, 41, 42, 50, 61, 70, 71, 80, 95, 96, 97, 98, 99, 120, 130)
    private val documents = listOf(7L, 25, 26, 60, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111)

    @Test
    fun newestFirstMergesBothSearchesWithEveryMessageOnce() = runTest {
        val chat = FakeChat(audio, documents, other = listOf(1, 2, 3, 115, 116))
        val got = drain(feed(chat, ChatOrder.NewestFirst))
        assertEquals((audio + documents).sorted(), got.sorted())
        assertEquals(got.size, got.toSet().size)
        assertTrue(chat.calls.first().second == 0L, "the first page starts at the newest message")
        assertTrue(chat.calls.all { it.third == 0 })
    }

    @Test
    fun oldestFirstPagesUpFromTheOldestMessage() = runTest {
        val chat = FakeChat(audio, documents)
        val got = drain(feed(chat, ChatOrder.OldestFirst))
        assertEquals((audio + documents).sorted(), got.sorted())
        assertEquals(1L, chat.calls.first().second)
        assertTrue(chat.calls.all { it.third == -(ChatFeed.PAGE - 1) })
    }

    @Test
    fun theSearchFurthestBehindIsAskedSoGatheredRowsStayInOrder() = runTest {
        val chat = FakeChat(audio, documents)
        val f = feed(chat, ChatOrder.NewestFirst)
        val got = sortedSetOf<Long>(Comparator.reverseOrder())
        repeat(3) { (f.next() as FeedStep.Added).tracks.forEach { got += it.messageId } }
        // Everything above the complete mark is all there is above it.
        val mark = f.complete!!
        val expected = (audio + documents).filter { it >= mark }.toSortedSet(Comparator.reverseOrder())
        assertEquals(expected.toList(), got.filter { it >= mark })
    }

    @Test
    fun aFloodWaitIsWaitedOutByTheCallThatGotIt() = runTest {
        val chat = FakeChat(audio, documents).apply { floodOnce = 7 }
        val waits = ArrayList<Int>()
        val f = feed(chat, ChatOrder.NewestFirst, waits)
        assertIs<FeedStep.Added>(f.next())
        assertEquals(listOf(7, 0), waits)
        assertEquals(2, chat.calls.size, "the same page asked again after the wait")
    }

    @Test
    fun aGoneChatIsToldApartFromAFailure() = runTest {
        val chat = FakeChat(audio, documents).apply { fail = "CHANNEL_PRIVATE" }
        val gone = feed(chat, ChatOrder.NewestFirst).next()
        assertIs<FeedStep.Failed>(gone)
        assertTrue(gone.notAvailable)
        chat.fail = "timeout"
        val passing = feed(chat, ChatOrder.NewestFirst).next()
        assertIs<FeedStep.Failed>(passing)
        assertTrue(!passing.notAvailable)
    }

    @Test
    fun anEmptyChatEnds() = runTest {
        assertEquals(emptyList(), drain(feed(FakeChat(emptyList(), emptyList()), ChatOrder.NewestFirst)))
        assertEquals(emptyList(), drain(feed(FakeChat(emptyList(), emptyList()), ChatOrder.OldestFirst)))
    }

    // The loader

    private class FakeStore(var done: Boolean?, indexed: List<Track>) : ChatStore {
        val rows = java.util.TreeMap<Long, Track>().apply { indexed.forEach { put(it.messageId, it) } }
        val added = ArrayList<Long>()

        override suspend fun scanDone(chatId: Long) = done

        override suspend fun indexPage(chatId: Long, oldestFirst: Boolean, after: Long?, limit: Int): List<Track> {
            val all = if (oldestFirst) rows.values.toList() else rows.descendingMap().values.toList()
            return all.filter { after == null || if (oldestFirst) it.messageId > after else it.messageId < after }.take(limit)
        }

        override suspend fun byIds(chatId: Long, messageIds: Collection<Long>) = messageIds.mapNotNull { rows[it] }.associateBy { it.messageId }

        override suspend fun addMissing(tracks: List<Track>) {
            tracks.forEach {
                rows[it.messageId] = it
                added += it.messageId
            }
        }
    }

    private fun loader(
        chat: FakeChat,
        store: ChatStore,
        order: ChatOrder,
        online: MutableStateFlow<Boolean> = MutableStateFlow(true),
        mask: Int = 0,
    ) = ChatLoader(7, order, store, { o, onWait -> ChatFeed(o, { p, f, off, l -> chat.fetch(p, f, off, l) }, onWait, wait = {}) }, online, { mask })

    @Test
    fun aScannedChatPagesFromTheIndexAloneInBothOrders() = runTest {
        val all = (1L..20_000L).map { track(it) }
        val chat = FakeChat(emptyList(), emptyList())
        val store = FakeStore(done = true, indexed = all)
        val newest = loader(chat, store, ChatOrder.NewestFirst)
        newest.start()
        assertEquals((20_000L downTo 19_901L).toList(), newest.state.value.rows.map { it.messageId })
        repeat(5) { newest.more() }
        assertEquals(600, newest.state.value.rows.size)
        assertEquals(19_401L, newest.state.value.rows.last().messageId)
        assertTrue(chat.calls.isEmpty(), "no Telegram call for a chat the scan finished")

        val oldest = loader(chat, store, ChatOrder.OldestFirst)
        oldest.start()
        oldest.more()
        assertEquals((1L..200L).toList(), oldest.state.value.rows.map { it.messageId })
    }

    @Test
    fun anUnscannedChatShowsTheIndexFirstThenTelegramsRowsInOrder() = runTest {
        val chat = FakeChat(audio, documents)
        // The scan has stored the newest few, one of them with its tags read.
        val store = FakeStore(done = false, indexed = listOf(track(130), track(120).copy(title = "Real title"), track(111)))
        val l = loader(chat, store, ChatOrder.NewestFirst)
        l.start()
        var rows = l.state.value.rows.map { it.messageId }
        assertEquals(rows.sortedDescending(), rows)
        while (!l.state.value.end) l.more()
        rows = l.state.value.rows.map { it.messageId }
        assertEquals((audio + documents).sortedDescending(), rows)
        assertEquals("Real title", l.state.value.rows.first { it.messageId == 120L }.title, "the index's copy wins")
        assertTrue(store.added.isNotEmpty() && 120L !in store.added, "only what the index lacked is added")
        assertTrue(l.state.value.fromTelegram)
    }

    @Test
    fun oldestFirstFromTelegram() = runTest {
        val chat = FakeChat(audio, documents)
        val l = loader(chat, FakeStore(done = false, indexed = emptyList()), ChatOrder.OldestFirst)
        l.start()
        while (!l.state.value.end) l.more()
        assertEquals((audio + documents).sorted(), l.state.value.rows.map { it.messageId })
    }

    @Test
    fun offlineTheIndexRowsShowAndThePageWaits() = runTest {
        val chat = FakeChat(audio, documents)
        val online = MutableStateFlow(false)
        val l = loader(chat, FakeStore(done = false, indexed = listOf(track(130))), ChatOrder.NewestFirst, online)
        val job = launch { l.start() }
        runCurrent()
        assertEquals(listOf(130L), l.state.value.rows.map { it.messageId })
        assertEquals(ChatProblem.Offline, l.state.value.problem)
        assertTrue(chat.calls.isEmpty())
        online.value = true
        advanceUntilIdle()
        job.join()
        assertNull(l.state.value.problem)
        assertTrue(l.state.value.rows.size > 1)
    }

    @Test
    fun aRemovedChatAndAGoneChatSaySo() = runTest {
        val removed = loader(FakeChat(audio, documents), FakeStore(done = null, indexed = emptyList()), ChatOrder.NewestFirst)
        removed.start()
        assertEquals(ChatProblem.Removed, removed.state.value.problem)

        val store = FakeStore(done = false, indexed = emptyList())
        val chat = FakeChat(audio, documents).apply { fail = "Chat not found" }
        val gone = loader(chat, store, ChatOrder.NewestFirst)
        gone.start()
        assertEquals(ChatProblem.NotAvailable, gone.state.value.problem)

        // Removed while open: the next refresh notices.
        val open = loader(FakeChat(audio, documents), store, ChatOrder.NewestFirst)
        open.start()
        store.done = null
        open.refresh(listOf(1L))
        assertEquals(ChatProblem.Removed, open.state.value.problem)
    }

    @Test
    fun hiddenClipsAreLeftOutButUnknownLengthsStay() = runTest {
        val tracks = listOf(track(1, durationMs = 10_000), track(2, durationMs = 0), track(3), track(4, name = "Voice 001.m4a").copy(format = com.tmplayer.music.library.AudioFormat.M4A))
        val l = loader(FakeChat(emptyList(), emptyList()), FakeStore(done = true, indexed = tracks), ChatOrder.OldestFirst, mask = com.tmplayer.music.library.ClipRules.DEFAULT_MASK)
        l.start()
        assertEquals(listOf(2L, 3L), l.state.value.rows.map { it.messageId })
    }
}

private fun track(id: Long, durationMs: Long = 200_000, name: String = "Song $id.mp3") = Track(
    chatId = 7, messageId = id, fileId = id.toInt(), uniqueId = "u$id", fileName = name, mime = "audio/mpeg",
    sizeBytes = 1_000, dateSec = id, title = "Song $id", artist = "", durationMs = durationMs,
)
