package com.tmplayer.music.data

import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.ScanCursor
import com.tmplayer.music.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {

    /**
     * A chat of [count] messages, ids 1..count, every third one an audio track, paged newest first.
     * Its documents search finds nothing; [asked] records where each audio page started.
     */
    private class FakeChat(var count: Int, val pageSize: Int = 10) {
        val asked = ArrayList<Long>()
        var failNext = 0
        var floodNext = 0

        /** Audio pages starting at these cursors come back empty, with the cursor moved on as usual. */
        val hollowFrom = HashSet<Long>()

        /** An audio page that is empty and does not move the cursor: Telegram's real end. */
        var stuckFrom: Long? = null

        fun page(chatId: Long, pass: ScanPass, from: Long): PageResult {
            if (pass == ScanPass.Audio) asked += from
            if (floodNext > 0) {
                floodNext--
                return PageResult.FloodWait(30)
            }
            if (failNext > 0) {
                failNext--
                return PageResult.Failed("NETWORK_ERROR")
            }
            if (pass == ScanPass.Documents) return PageResult.Page(emptyList(), emptyList(), 0)
            if (from == stuckFrom) return PageResult.Page(emptyList(), emptyList(), from)
            val top = if (from == 0L) count.toLong() else from - 1
            val ids = (top downTo maxOf(1, top - pageSize + 1)).toList()
            val items = ids.filter { it % 3 == 0L }.map { Mapped.Audio(track(chatId, it)) }
            val next = ids.lastOrNull()?.takeIf { it > 1 } ?: 0L
            if (from in hollowFrom) return PageResult.Page(emptyList(), emptyList(), next)
            return PageResult.Page(ids, items, next)
        }
    }

    private class FakeStore : ScanStore {
        val cursors = HashMap<Pair<Long, ScanPass>, ScanCursor>()
        val tracks = LinkedHashMap<Pair<Long, Long>, Track>()

        override suspend fun cursor(chatId: Long, pass: ScanPass) = cursors[chatId to pass] ?: ScanCursor()
        override suspend fun saveCursor(chatId: Long, pass: ScanPass, cursor: ScanCursor) {
            cursors[chatId to pass] = cursor
        }
        override suspend fun addTracks(tracks: List<Track>) {
            for (t in tracks) this.tracks[t.chatId to t.messageId] = t
        }
        override suspend fun addLyrics(files: List<LyricsFile>) = Unit
    }

    private fun TestScope.scanner(store: ScanStore, chats: Map<Long, FakeChat>, scope: CoroutineScope = this) =
        LibraryScanner(store, { id, pass, from -> chats.getValue(id).page(id, pass, from) }, scope, retryDelayMs = 100)

    @Test
    fun `reads every page to the oldest end and saves the cursor`() = runTest {
        val store = FakeStore()
        val chat = FakeChat(95)
        val s = scanner(store, mapOf(1L to chat))
        s.scan(listOf(1L))
        advanceUntilIdle()
        assertEquals(31, store.tracks.size)
        assertTrue(store.cursors.getValue(1L to ScanPass.Audio).done)
        assertEquals(95L, store.cursors.getValue(1L to ScanPass.Audio).newestMessageId)
        assertFalse(s.status.value.running)
    }

    @Test
    fun `an empty page that moves the cursor on is not the end of the chat`() = runTest {
        // A supergroup whose first page (and one in the middle) Telegram answers with no messages.
        val store = FakeStore()
        val chat = FakeChat(95).apply { hollowFrom += listOf(0L, 56L) }
        scanner(store, mapOf(1L to chat)).scan(listOf(1L))
        advanceUntilIdle()
        // Only the tracks of the two hollow pages are missing: 31 in all, 3 on 95..86 and 3 on 55..46.
        assertEquals(31 - 3 - 3, store.tracks.size)
        assertTrue(store.cursors.getValue(1L to ScanPass.Audio).done)
    }

    @Test
    fun `an empty page that does not move the cursor ends the chat`() = runTest {
        val store = FakeStore()
        val chat = FakeChat(95).apply { stuckFrom = 56L }
        scanner(store, mapOf(1L to chat)).scan(listOf(1L))
        advanceUntilIdle()
        assertTrue(store.cursors.getValue(1L to ScanPass.Audio).done)
        assertEquals(listOf(0L, 86L, 76L, 66L, 56L), chat.asked)
    }

    @Test
    fun `a scan cut short carries on from its cursor`() = runTest {
        val store = FakeStore()
        store.cursors[1L to ScanPass.Audio] = ScanCursor(fromMessageId = 51, done = false, newestMessageId = 95)
        val chat = FakeChat(95)
        scanner(store, mapOf(1L to chat)).scan(listOf(1L))
        advanceUntilIdle()
        assertEquals(51L, chat.asked.first())
        assertEquals(16, store.tracks.size)
    }

    @Test
    fun `a finished chat is topped up from the newest end only`() = runTest {
        val store = FakeStore()
        val chat = FakeChat(95)
        scanner(store, mapOf(1L to chat)).scan(listOf(1L))
        advanceUntilIdle()
        chat.count = 99
        chat.asked.clear()
        scanner(store, mapOf(1L to chat)).scan(listOf(1L))
        advanceUntilIdle()
        assertEquals(listOf(0L), chat.asked)
        assertTrue(store.tracks.containsKey(1L to 96L) && store.tracks.containsKey(1L to 99L))
    }

    @Test
    fun `a flood wait is waited out by that chat while another goes on`() = runTest {
        val store = FakeStore()
        val slow = FakeChat(20).apply { floodNext = 1 }
        val fast = FakeChat(20)
        val s = scanner(store, mapOf(1L to slow, 2L to fast))
        s.scan(listOf(1L, 2L))
        runCurrent()
        assertEquals(30, s.status.value.waitingSeconds)
        assertEquals(6, store.tracks.keys.count { it.first == 2L })
        advanceUntilIdle()
        assertEquals(6, store.tracks.keys.count { it.first == 1L })
    }

    @Test
    fun `a chat that keeps failing gives up with a reason and the rest are read`() = runTest {
        val store = FakeStore()
        val broken = FakeChat(20).apply { failNext = LibraryScanner.MAX_TRIES }
        val good = FakeChat(20)
        val s = scanner(store, mapOf(1L to broken, 2L to good))
        s.scan(listOf(1L, 2L))
        advanceUntilIdle()
        assertTrue(s.status.value.failed.containsKey(1L))
        assertFalse(s.status.value.failed.containsKey(2L))
        assertEquals(6, store.tracks.keys.count { it.first == 2L })
    }

    @Test
    fun `a failure that clears within the tries is not a failure`() = runTest {
        val store = FakeStore()
        val flaky = FakeChat(20).apply { failNext = LibraryScanner.MAX_TRIES - 1 }
        val s = scanner(store, mapOf(1L to flaky))
        s.scan(listOf(1L))
        advanceUntilIdle()
        assertTrue(s.status.value.failed.isEmpty())
        assertEquals(6, store.tracks.size)
    }

    @Test
    fun `the same chat asked for twice is read once`() = runTest {
        val store = FakeStore()
        val chat = FakeChat(20)
        val s = scanner(store, mapOf(1L to chat))
        s.scan(listOf(1L))
        s.scan(listOf(1L, 1L))
        advanceUntilIdle()
        assertEquals(2, chat.asked.size)
    }

    @Test
    fun `pause holds the next page and resume lets it go`() = runTest {
        val store = FakeStore()
        val chat = FakeChat(95)
        val s = scanner(store, mapOf(1L to chat))
        s.pause()
        s.scan(listOf(1L))
        advanceUntilIdle()
        assertTrue(chat.asked.isEmpty())
        assertTrue(s.status.value.paused)
        s.resume()
        advanceUntilIdle()
        assertEquals(31, store.tracks.size)
    }

    @Test
    fun `an empty chat finishes at once`() = runTest {
        val store = FakeStore()
        val s = scanner(store, mapOf(1L to FakeChat(0)))
        s.scan(listOf(1L))
        advanceUntilIdle()
        assertTrue(store.tracks.isEmpty())
        assertTrue(store.cursors.getValue(1L to ScanPass.Audio).done)
    }

    private companion object {
        fun track(chatId: Long, messageId: Long) = Track(
            chatId = chatId,
            messageId = messageId,
            fileId = messageId.toInt(),
            uniqueId = "u$chatId-$messageId",
            fileName = "track$messageId.mp3",
            mime = "audio/mpeg",
            sizeBytes = 1000,
            dateSec = messageId,
            title = "Track $messageId",
            artist = "Nadia Okafor",
        )
    }
}
