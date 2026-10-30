package com.tmplayer.music.data

import com.tmplayer.music.library.TagCounts
import com.tmplayer.music.library.TagResult
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.tags.Samples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagEnricherTest {

    private class FakeStore(count: Int) : TagStore {
        val tracks = LinkedHashMap<TrackId, Track>()
        val batches = ArrayList<List<TagResult>>()

        init {
            for (i in 1..count) {
                val t = Track(
                    chatId = 1, messageId = i.toLong(), fileId = i, uniqueId = "u$i", fileName = "f$i.mp3",
                    mime = "audio/mpeg", sizeBytes = 100_000, dateSec = i.toLong(), title = "Track $i", artist = "",
                )
                tracks[t.id] = t
            }
        }

        fun state(id: Long) = tracks.getValue(TrackId(1, id)).tagState

        override suspend fun pendingTags(limit: Int) =
            tracks.values.filter { it.tagState == TagState.TelegramOnly }.sortedByDescending { it.dateSec }.take(limit)

        override suspend fun pendingAmong(ids: Collection<TrackId>) =
            ids.mapNotNull { tracks[it] }.filter { it.tagState == TagState.TelegramOnly }

        override suspend fun applyTags(results: List<TagResult>) {
            batches += results
            for (r in results) {
                val t = tracks.getValue(r.id)
                tracks[r.id] = t.copy(tagState = if (r.tags == null) TagState.Failed else TagState.Read)
            }
        }

        override suspend fun counts() = TagCounts(
            pending = tracks.values.count { it.tagState == TagState.TelegramOnly },
            read = tracks.values.count { it.tagState == TagState.Read },
            failed = tracks.values.count { it.tagState == TagState.Failed },
        )
    }

    /** A file source that serves a small MP3 with an ID3 tag, except for the files it is told to refuse. */
    private class FakeBytes : FileBytes {
        var offline = false
        val refuse = HashSet<Int>()
        val asked = ArrayList<Int>()
        var forgotten = 0
        private val file = Samples.id3Tag(
            3,
            Samples.id3Frame23("TIT2", Samples.latin1Text("Song")) + Samples.id3Frame23("TPE1", Samples.latin1Text("Nadia Okafor")),
        ) + Samples.mpegFrame()

        override suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray? {
            asked += fileId
            if (offline || fileId in refuse) return null
            if (offset >= file.size) return ByteArray(0)
            return file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
        }

        override suspend fun isComplete(fileId: Int) = false
        override suspend fun forget(fileId: Int) {
            forgotten++
        }
    }

    private object NoCovers : CoverStore {
        override suspend fun save(key: String, data: ByteArray) = key
    }

    @Test
    fun `reads every file in batches, one write each, and stops by itself`() = runTest {
        val store = FakeStore(30)
        val bytes = FakeBytes()
        val enricher = TagEnricher(store, bytes, NoCovers, this)
        enricher.kick()
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
        assertEquals(3, store.batches.size)
        assertTrue(store.batches.all { it.size <= TagEnricher.BATCH })
        assertFalse(enricher.status.value.running)
        assertEquals(TagCounts(read = 30), enricher.status.value.counts)
        assertEquals("partial copies are dropped", 30, bytes.forgotten)
    }

    @Test
    fun `waits between batches`() = runTest {
        val store = FakeStore(30)
        val enricher = TagEnricher(store, FakeBytes(), NoCovers, this, gapMs = 10_000)
        enricher.kick()
        runCurrent()
        assertEquals(1, store.batches.size)
        advanceTimeBy(5_000)
        assertEquals(1, store.batches.size)
        // The gap, or longer when the pacing asks it: a batch of 12 rests 15 s (0.8 files a second).
        advanceTimeBy(9_000)
        assertEquals(1, store.batches.size)
        advanceTimeBy(2_000)
        assertEquals(2, store.batches.size)
        advanceUntilIdle()
    }

    @Test
    fun `while Telegram is out of reach it waits and marks nothing`() = runTest {
        val store = FakeStore(30)
        val bytes = FakeBytes()
        val online = MutableStateFlow(false)
        val enricher = TagEnricher(store, bytes, NoCovers, this, online = online)
        enricher.kick()
        advanceUntilIdle()
        assertTrue(enricher.status.value.waiting)
        assertTrue(bytes.asked.isEmpty())
        assertTrue(store.batches.isEmpty())
        online.value = true
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
        assertFalse(enricher.status.value.running)
    }

    @Test
    fun `no bytes at all backs off and stops by itself`() = runTest {
        val store = FakeStore(30)
        val bytes = FakeBytes().apply { offline = true }
        val enricher = TagEnricher(store, bytes, NoCovers, this)
        enricher.kick()
        runCurrent()
        assertTrue(enricher.status.value.waiting)
        advanceUntilIdle()
        assertFalse("the retries stop by themselves", enricher.status.value.running)
        assertTrue(bytes.asked.size <= (TagEnricher.MAX_BACKOFFS + 1) * TagEnricher.BATCH)
        assertTrue("not everything is written off", store.tracks.values.any { it.tagState == TagState.TelegramOnly })

        // Bytes again: the next kick reads the rest, and Try again in Settings would reset the failed.
        bytes.offline = false
        enricher.kick()
        advanceUntilIdle()
        assertTrue(store.tracks.values.none { it.tagState == TagState.TelegramOnly })
    }

    @Test
    fun `one file that never gives bytes is marked failed after a few tries`() = runTest {
        val store = FakeStore(5)
        val bytes = FakeBytes().apply { refuse += 3 }
        val enricher = TagEnricher(store, bytes, NoCovers, this)
        enricher.kick()
        advanceUntilIdle()
        assertEquals(TagState.Failed, store.state(3))
        assertEquals(TagEnricher.MAX_ATTEMPTS, bytes.asked.count { it == 3 })
        assertEquals(4, store.tracks.values.count { it.tagState == TagState.Read })
        assertFalse(enricher.status.value.running)
    }

    @Test
    fun `pause stops reading until resumed`() = runTest {
        val store = FakeStore(30)
        val paused = MutableStateFlow(true)
        val enricher = TagEnricher(store, FakeBytes(), NoCovers, this, paused = paused)
        enricher.kick()
        advanceUntilIdle()
        assertTrue(store.batches.isEmpty())
        assertTrue(enricher.status.value.paused)
        assertTrue(enricher.status.value.running)
        paused.value = false
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
        assertFalse(enricher.status.value.paused)
    }

    @Test
    fun `while paused the rows on screen are still read, and nothing else`() = runTest {
        val store = FakeStore(30)
        val paused = MutableStateFlow(true)
        val enricher = TagEnricher(store, FakeBytes(), NoCovers, this, paused = paused)
        enricher.kick()
        advanceUntilIdle()
        assertTrue(store.batches.isEmpty())
        enricher.prefer(listOf(TrackId(1, 5), TrackId(1, 6)))
        enricher.preferUpcoming(listOf(TrackId(1, 9)))
        advanceUntilIdle()
        assertEquals(setOf(5L, 6L, 9L), store.batches.flatten().map { it.id.messageId }.toSet())
        assertEquals(27, store.tracks.values.count { it.tagState == TagState.TelegramOnly })
        assertTrue("waits for Resume", enricher.status.value.paused && enricher.status.value.running)
        assertFalse("on-demand reads stay out of the banner", enricher.status.value.background)
        paused.value = false
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
    }

    @Test
    fun `the background trickle rests between batches, a request on screen does not wait`() = runTest {
        val store = FakeStore(40)
        val enricher = TagEnricher(store, FakeBytes(), NoCovers, this, clock = { testScheduler.currentTime })
        enricher.kick()
        runCurrent()
        // The first batch of 12 is read at once, then it rests about 15 s (0.8 files a second).
        assertEquals(1, store.batches.size)
        advanceTimeBy(TagEnricher.BATCH * TricklePacing.MS_PER_FILE - 100)
        assertEquals(1, store.batches.size)
        // Rows come on screen during the rest: read now, not after it.
        enricher.prefer(listOf(TrackId(1, 3)))
        runCurrent()
        assertEquals(2, store.batches.size)
        assertEquals(listOf(3L), store.batches[1].map { it.id.messageId }.take(1))
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
    }

    @Test
    fun `tracks on screen are read first`() = runTest {
        val store = FakeStore(40)
        val enricher = TagEnricher(store, FakeBytes(), NoCovers, this, gapMs = 10_000)
        enricher.prefer(listOf(TrackId(1, 2), TrackId(1, 1)))
        enricher.kick()
        runCurrent()
        val first = store.batches.first().map { it.id.messageId }
        assertTrue(first.containsAll(listOf(1L, 2L)))
        advanceUntilIdle()
    }
}
