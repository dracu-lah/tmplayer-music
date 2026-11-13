package com.tmplayer.music.data

import com.tmplayer.music.library.ReplayGain
import com.tmplayer.music.library.TagCounts
import com.tmplayer.music.library.TagResult
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.tags.Samples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReplayGain for tracks read before gains were kept: who needs it, when it may fetch, and that it is read once. */
class GainBackfillTest {

    private fun track(id: Long, state: TagState = TagState.Read, gain: ReplayGain = ReplayGain.NONE) = Track(
        chatId = 1, messageId = id, fileId = id.toInt(), uniqueId = "u$id", fileName = "f$id.mp3", mime = "audio/mpeg",
        sizeBytes = 100_000, dateSec = id, title = "T$id", artist = "A", tagState = state, replayGain = gain,
    )

    @Test
    fun `only a track read before gains were kept needs it`() {
        assertTrue(GainBackfill.needs(track(1)))
        assertFalse("tags not read yet: the normal read takes the gain", GainBackfill.needs(track(1, TagState.TelegramOnly)))
        assertFalse(GainBackfill.needs(track(1, TagState.Failed)))
        assertFalse(GainBackfill.needs(track(1, gain = ReplayGain(-6f, null, null, null))))
        assertFalse("looked at once already", GainBackfill.needs(track(1, gain = ReplayGain.CHECKED)))
        assertFalse(GainBackfill.needs(null))
    }

    @Test
    fun `mobile data needs the users yes, offline and paused never`() {
        fun allowed(net: NetState, mobile: Boolean, paused: Boolean = false) =
            GainBackfill.allowed(TagPolicy.policy(net, mobile, active = true, paused = paused), online = net != NetState.Offline)
        assertTrue(allowed(NetState.Unmetered, mobile = false))
        assertFalse(allowed(NetState.Metered, mobile = false))
        assertTrue(allowed(NetState.Metered, mobile = true))
        assertFalse(allowed(NetState.Offline, mobile = true))
        assertFalse(allowed(NetState.Unmetered, mobile = true, paused = true))
        assertFalse(GainBackfill.allowed(ReadPolicy.ALL, online = false))
    }

    @Test
    fun `the checked mark is stored as text and read back, garbage still means unknown`() {
        assertEquals("-", ReplayGain.CHECKED.encode())
        assertEquals(ReplayGain.CHECKED, ReplayGain.decode("-"))
        assertEquals(1f, ReplayGain.CHECKED.factor(), 0f)
        assertEquals("", ReplayGain.NONE.encode())
    }

    private class Store(var current: Track) : TagStore {
        val stored = ArrayList<Pair<TrackId, ReplayGain>>()
        var lookups = 0
        override suspend fun pendingTags(limit: Int) = emptyList<Track>()
        override suspend fun applyTags(results: List<TagResult>) {}
        override suspend fun counts() = TagCounts()
        override suspend fun track(id: TrackId): Track? = current.takeIf { it.id == id }.also { lookups++ }
        override suspend fun applyGain(id: TrackId, gain: ReplayGain) {
            stored += id to gain
        }
    }

    private class Bytes(val gainTag: Boolean) : FileBytes {
        var reads = 0
        private val file = Samples.id3Tag(
            3,
            Samples.id3Frame23("TIT2", Samples.latin1Text("Song")) + Samples.id3Frame23("TPE1", Samples.latin1Text("Nadia Okafor")),
        ) + Samples.mpegFrame()

        override suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray? {
            reads++
            if (offset >= file.size) return ByteArray(0)
            return file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
        }

        override suspend fun isComplete(fileId: Int) = false
        override suspend fun forget(fileId: Int) {}
    }

    private object NoCovers : CoverStore {
        override suspend fun save(key: String, data: ByteArray) = key
    }

    @Test
    fun `a playing track without a gain is read once and marked`() = runTest {
        val store = Store(track(1))
        val bytes = Bytes(false)
        val enricher = TagEnricher(store, bytes, NoCovers, this)
        enricher.preferUpcoming(listOf(TrackId(1, 1)))
        advanceUntilIdle()
        assertEquals(listOf(TrackId(1, 1) to ReplayGain.NONE), store.stored)
        val reads = bytes.reads
        assertTrue(reads > 0)
        // The same track again in this session, or the queue moving back to it: nothing more is read.
        enricher.preferUpcoming(listOf(TrackId(1, 2)))
        enricher.preferUpcoming(listOf(TrackId(1, 1)))
        advanceUntilIdle()
        assertEquals(1, store.stored.size)
        assertEquals(reads, bytes.reads)
    }

    @Test
    fun `a track that has its gain or its mark is not read, nor is one on mobile data without a yes`() = runTest {
        val bytes = Bytes(false)
        val checked = Store(track(1, gain = ReplayGain.CHECKED))
        TagEnricher(checked, bytes, NoCovers, backgroundScope).preferUpcoming(listOf(TrackId(1, 1)))
        val metered = Store(track(2))
        val policy = MutableStateFlow(TagPolicy.policy(NetState.Metered, mobileAllowed = false, active = true, paused = false))
        TagEnricher(metered, bytes, NoCovers, backgroundScope, policy = policy).preferUpcoming(listOf(TrackId(1, 2)))
        val offline = MutableStateFlow(false)
        val away = Store(track(3))
        TagEnricher(away, bytes, NoCovers, backgroundScope, online = offline).preferUpcoming(listOf(TrackId(1, 3)))
        advanceUntilIdle()
        assertEquals(0, bytes.reads)
        assertTrue(checked.stored.isEmpty() && metered.stored.isEmpty() && away.stored.isEmpty())
        assertEquals("the stored track is not even looked up when the policy says no", 0, metered.lookups + away.lookups)
    }
}
