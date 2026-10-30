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
        /** Newest first, a full last page still hands a cursor on, and the page after it is empty. */
        val lazyEnd: Boolean = false,
        /** Messages the search passes over without returning them (the cursor still moves past them). */
        val hollow: List<Long> = emptyList(),
    ) {
        val calls = ArrayList<Triple<ScanPass, Long, Int>>()
        var floodOnce = 0
        var floodAlways = 0
        var fail: String? = null

        /** The next this many pages (not the one message probes) come back empty. */
        var emptyPages = 0

        /** The page calls, without the one message probes for the newest message. */
        val pages = ArrayList<Triple<ScanPass, Long, Int>>()

        fun fetch(pass: ScanPass, from: Long, offset: Int, limit: Int): PageResult {
            val call = Triple(pass, from, offset)
            calls += call
            if (limit > 1) pages += call
            fail?.let { return PageResult.Failed(it) }
            if (floodAlways > 0) return PageResult.FloodWait(floodAlways)
            if (floodOnce > 0) {
                val s = floodOnce
                floodOnce = 0
                return PageResult.FloodWait(s)
            }
            if (limit > 1 && emptyPages > 0) {
                emptyPages--
                return PageResult.Page(emptyList(), emptyList(), if (offset == 0) from else 0L)
            }
            val all = (if (pass == ScanPass.Audio) audio + hollow else documents + other).sortedDescending()
            val n = minOf(page, limit)
            val window = if (offset == 0) {
                all.filter { from == 0L || it < from }.take(n)
            } else {
                all.filter { it >= from }.sorted().take(n).sortedDescending()
            }
            val ids = window.filter { it !in hollow }
            val more = window.isNotEmpty() && all.any { it < window.min() }
            val next = if (offset == 0 && window.size == n && (more || lazyEnd)) window.min() else 0L
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
        assertTrue(chat.pages.all { it.third == -(ChatFeed.PAGE - 1) })
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

    private class FakeStore(var done: Boolean?, indexed: List<Track>, val scanMark: Long? = null) : ChatStore {
        override suspend fun scannedDownTo(chatId: Long) = scanMark

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
        gate: (suspend () -> Unit)? = null,
        wait: suspend (Int) -> Unit = {},
    ) = ChatLoader(7, order, store, { o, onWait ->
        ChatFeed(o, { p, f, off, l ->
            gate?.invoke()
            chat.fetch(p, f, off, l)
        }, onWait, wait = wait)
    }, online, { mask })

    // The rows shown are always the start of the chat's list (the bug: rows older than the first one
    // turned up above it while scrolling, oldest first on an unscanned chat).

    /** Every list the page publishes must be the start of [expected]: nothing above the first, nothing between. */
    private fun ChatLoader.checkPrefix(expected: List<Long>, seen: MutableList<List<Long>>) {
        val rows = state.value.rows.map { it.messageId }
        seen += rows
        assertEquals(expected.take(rows.size), rows, "the rows shown are the start of the chat's list")
    }

    private suspend fun ChatLoader.drainChecked(expected: List<Long>): List<List<Long>> {
        val seen = ArrayList<List<Long>>()
        start()
        checkPrefix(expected, seen)
        var steps = 0
        while (!state.value.end && state.value.problem == null && steps++ < 1_000) {
            more()
            checkPrefix(expected, seen)
        }
        assertTrue(state.value.end, "the page reached the end")
        assertEquals(expected, state.value.rows.map { it.messageId })
        return seen
    }

    /** A large chat: lossless documents are the oldest files, audio messages come later (35,865 track chat). */
    private val oldDocs = (5L..304L).toList()
    private val laterAudio = (1_000L..1_449L).toList()

    @Test
    fun oldestFirstStartsAtTheTrueOldestWhenTheOtherSearchHoldsIt() = runTest {
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val l = loader(chat, FakeStore(done = false, indexed = emptyList()), ChatOrder.OldestFirst)
        val expected = (laterAudio + oldDocs).sorted()
        val seen = l.drainChecked(expected)
        assertEquals(5L, seen.first { it.isNotEmpty() }.first(), "the first row shown is the chat's oldest")
    }

    @Test
    fun newestFirstStartsAtTheTrueNewestWhenTheOtherSearchHoldsIt() = runTest {
        // The symmetric case: the newest files are documents, the audio search answers first.
        val chat = FakeChat((5L..304L).toList(), (1_000L..1_449L).toList(), page = 100)
        val l = loader(chat, FakeStore(done = false, indexed = emptyList()), ChatOrder.NewestFirst)
        val expected = (5L..304L).toList().plus(1_000L..1_449L).sortedDescending()
        val seen = l.drainChecked(expected)
        assertEquals(1_449L, seen.first { it.isNotEmpty() }.first())
        assertEquals(5L, l.state.value.rows.last().messageId, "the bottom end is the chat's oldest")
    }

    @Test
    fun oldestFirstNeverShowsTheIndexOfAnUnscannedChatFirst() = runTest {
        // The scan started at the newest end: the index holds the middle and the top, not the start.
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val store = FakeStore(done = false, indexed = laterAudio.takeLast(200).map { track(it).copy(title = "Tagged $it") }, scanMark = 1_250)
        val l = loader(chat, store, ChatOrder.OldestFirst)
        l.drainChecked((laterAudio + oldDocs).sorted())
        assertEquals("Tagged 1449", l.state.value.rows.last().title, "the index's copy still wins")
    }

    @Test
    fun newestFirstShowsTheIndexAboveTheScanMarkAtOnce() = runTest {
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = FakeStore(done = false, indexed = laterAudio.takeLast(50).map(::track), scanMark = 1_400)
        val l = loader(chat, store, ChatOrder.NewestFirst, gate = { gate.await() })
        val job = launch { l.start() }
        runCurrent()
        assertEquals((1_449L downTo 1_400L).toList(), l.state.value.rows.map { it.messageId }, "before Telegram answers")
        gate.complete(Unit)
        job.join()
        val expected = (laterAudio + oldDocs).sortedDescending()
        assertEquals(expected.take(l.state.value.rows.size), l.state.value.rows.map { it.messageId })
    }

    @Test
    fun newestFirstHidesTheIndexWhenTheScanHasNotReadBothSearches() = runTest {
        // Only the audio pass has run: a document newer than every audio file must not land on top later.
        val docs = oldDocs + 2_000L
        val chat = FakeChat(laterAudio, docs, page = 100)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = FakeStore(done = false, indexed = laterAudio.map(::track), scanMark = null)
        val l = loader(chat, store, ChatOrder.NewestFirst, gate = { gate.await() })
        val job = launch { l.start() }
        runCurrent()
        assertTrue(l.state.value.rows.isEmpty())
        gate.complete(Unit)
        job.join()
        assertEquals(2_000L, l.state.value.rows.first().messageId)
        l.drainChecked((laterAudio + docs).sortedDescending())
    }

    @Test
    fun anEmptyChatAndAChatSmallerThanAPageEndInBothOrders() = runTest {
        for (order in ChatOrder.entries) {
            val empty = loader(FakeChat(emptyList(), emptyList(), page = 100), FakeStore(done = false, indexed = emptyList()), order)
            empty.drainChecked(emptyList())
            val small = listOf(3L, 8, 13)
            val tiny = loader(FakeChat(small, listOf(10L), page = 100), FakeStore(done = false, indexed = emptyList()), order)
            val expected = (small + 10L).let { if (order == ChatOrder.OldestFirst) it.sorted() else it.sortedDescending() }
            tiny.drainChecked(expected)
        }
    }

    @Test
    fun historyEndingExactlyOnAPageBoundary() = runTest {
        for (order in ChatOrder.entries) for (lazy in listOf(false, true)) {
            val a = (1L..10L).toList()
            val d = (11L..30L).toList()
            val chat = FakeChat(a, d, page = 10, lazyEnd = lazy)
            val l = loader(chat, FakeStore(done = false, indexed = emptyList()), order)
            val expected = (a + d).let { if (order == ChatOrder.OldestFirst) it.sorted() else it.sortedDescending() }
            l.drainChecked(expected)
            assertTrue(chat.calls.size < 20, "no extra pages past the end ($order, lazy $lazy): ${chat.calls.size}")
        }
    }

    @Test
    fun aFailedOrLongFloodWaitPageStopsAndTryAgainGoesOnFromTheSamePage() = runTest {
        for (order in ChatOrder.entries) {
            val chat = FakeChat(laterAudio, oldDocs, page = 100)
            val l = loader(chat, FakeStore(done = false, indexed = emptyList()), order)
            l.start()
            val before = l.state.value.rows
            chat.floodAlways = 7
            l.more()
            assertIs<ChatProblem.Failed>(l.state.value.problem)
            assertTrue(chat.calls.size < 20, "flood waits are bounded")
            chat.floodAlways = 0
            chat.fail = "timeout"
            val calls = chat.calls.size
            l.more()
            assertEquals(calls + 1, chat.calls.size, "a failure is not retried by itself")
            assertEquals(before, l.state.value.rows)
            chat.fail = null
            l.retry()
            val expected = (laterAudio + oldDocs).let { if (order == ChatOrder.OldestFirst) it.sorted() else it.sortedDescending() }
            val seen = ArrayList<List<Long>>()
            while (!l.state.value.end && l.state.value.problem == null) {
                l.more()
                l.checkPrefix(expected, seen)
            }
            assertEquals(expected, l.state.value.rows.map { it.messageId })
        }
    }

    @Test
    fun leavingMidLoadOrSwitchingOrderLosesNothingAndClearsTheWait() = runTest {
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val store = FakeStore(done = false, indexed = emptyList())
        val l = loader(chat, store, ChatOrder.OldestFirst, wait = { kotlinx.coroutines.delay(it * 1000L) })
        chat.floodOnce = 30
        val job = launch { l.start() }
        runCurrent()
        assertEquals(ChatProblem.Waiting(30), l.state.value.problem)
        // The user switches to Newest first: this page's work is cancelled mid wait.
        job.cancel()
        job.join()
        assertNull(l.state.value.problem, "the wait note does not stay after the page is left")
        val other = loader(chat, store, ChatOrder.NewestFirst)
        other.drainChecked((laterAudio + oldDocs).sortedDescending())
        // Back to Oldest first: the same loader goes on from where it stopped.
        val expected = (laterAudio + oldDocs).sorted()
        val seen = ArrayList<List<Long>>()
        l.start()
        while (!l.state.value.end && l.state.value.problem == null) {
            l.more()
            l.checkPrefix(expected, seen)
        }
        assertEquals(expected, l.state.value.rows.map { it.messageId })
    }

    @Test
    fun cancelledWhileTheIndexIsAskedKeepsTheRowsTheFeedAlreadyCounted() = runTest {
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : ChatStore by FakeStore(done = false, indexed = emptyList()) {
            override suspend fun byIds(chatId: Long, messageIds: Collection<Long>): Map<Long, Track> {
                gate.await()
                return emptyMap()
            }
        }
        val l = loader(chat, store, ChatOrder.OldestFirst)
        val job = launch { l.start() }
        runCurrent()
        job.cancel()
        job.join()
        gate.complete(Unit)
        val expected = (laterAudio + oldDocs).sorted()
        val seen = ArrayList<List<Long>>()
        while (!l.state.value.end && l.state.value.problem == null) {
            l.more()
            l.checkPrefix(expected, seen)
        }
        assertEquals(expected, l.state.value.rows.map { it.messageId })
    }

    @Test
    fun anEmptyAnswerShortOfTheEndIsAskedAgainNotTakenAsTheEnd() = runTest {
        val chat = FakeChat(laterAudio, oldDocs, page = 100)
        val f = feed(chat, ChatOrder.OldestFirst)
        assertIs<FeedStep.Added>(f.next())
        chat.emptyPages = 1
        assertEquals(emptyList(), (f.next() as FeedStep.Added).tracks)
        assertTrue(!f.ended)
        val rest = drain(f)
        assertEquals((laterAudio + oldDocs).size, rest.size + 100)

        // Empty again and again: the page says it failed, and asks no more by itself.
        val stuck = FakeChat(laterAudio, oldDocs, page = 100)
        val g = feed(stuck, ChatOrder.OldestFirst)
        g.next()
        stuck.emptyPages = 1_000
        var step: FeedStep = g.next()
        var n = 0
        while (step is FeedStep.Added && n++ < 10) step = g.next()
        assertIs<FeedStep.Failed>(step)
        assertTrue(n < ChatFeed.MAX_EMPTY, "bounded retries")
    }

    @Test
    fun newestFirstGoesOnPastAPageWithNothingButACursor() = runTest {
        // A page of messages the search passes over (deleted, say) comes back empty with a cursor.
        val a = (100L..109L).toList() + (1L..10L).toList()
        val chat = FakeChat(a, emptyList(), page = 10, hollow = (50L..59L).toList())
        assertEquals(a.sortedDescending(), drain(feed(chat, ChatOrder.NewestFirst)))
    }

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
        val l = loader(chat, FakeStore(done = false, indexed = listOf(track(130)), scanMark = 130), ChatOrder.NewestFirst, online)
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
