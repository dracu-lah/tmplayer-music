package com.tmplayer.music.data

import com.tmplayer.music.library.TagCounts
import com.tmplayer.music.library.TagResult
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.tags.Samples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order tags are read in, and when: on screen, about to play, documents, then the trickle on Wi-Fi only. */
class TagPolicyTest {

    private fun t(id: Long, durationMs: Long = 200_000) = Track(
        chatId = 1, messageId = id, fileId = id.toInt(), uniqueId = "u$id", fileName = "f$id.mp3", mime = "audio/mpeg",
        sizeBytes = 100_000, dateSec = id, title = "Track $id", artist = "", durationMs = durationMs,
    )

    @Test
    fun planTakesVisibleThenUpcomingThenDocumentsThenTheRest() {
        val plan = TagPriority.plan(
            visible = listOf(t(1), t(2)),
            upcoming = listOf(t(2), t(3)),
            documents = listOf(t(4, 0), t(5, 0)),
            rest = listOf(t(6), t(1), t(7)),
            policy = ReadPolicy.ALL,
            batch = 6,
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), plan.map { it.messageId })
    }

    @Test
    fun planLeavesOutWhatThePolicyDoesNotAllow() {
        val metered = TagPolicy.policy(NetState.Metered, mobileAllowed = false, active = true, paused = false)
        val plan = TagPriority.plan(listOf(t(1)), listOf(t(2)), listOf(t(3, 0), t(4, 0)), listOf(t(5)), metered, batch = 12, documentsLeft = 1)
        assertEquals("no trickle on mobile data; documents within the cap", listOf(1L, 2L, 3L), plan.map { it.messageId })
        val paused = TagPriority.plan(listOf(t(1)), listOf(t(2)), listOf(t(3, 0)), listOf(t(5)), TagPolicy.policy(NetState.Metered, false, true, paused = true), 12)
        assertEquals("paused: on screen and about to play only", listOf(1L, 2L), paused.map { it.messageId })
        val offline = TagPriority.plan(listOf(t(1)), listOf(t(2)), listOf(t(3, 0)), listOf(t(5)), TagPolicy.policy(NetState.Offline, false, true, paused = false), 12)
        assertTrue(offline.isEmpty())
    }

    @Test
    fun wifiAndMobileData() {
        val wifi = TagPolicy.policy(NetState.Unmetered, mobileAllowed = false, active = true, paused = false)
        assertTrue(wifi.onDemand && wifi.documents && wifi.trickle)
        assertEquals(Int.MAX_VALUE, wifi.documentsCap)

        val mobile = TagPolicy.policy(NetState.Metered, mobileAllowed = false, active = true, paused = false)
        assertTrue("what is on screen or about to play is small and always read", mobile.onDemand)
        assertFalse(mobile.trickle)
        assertEquals(TagPolicy.METERED_DOCUMENTS, mobile.documentsCap)

        val yes = TagPolicy.policy(NetState.Metered, mobileAllowed = true, active = true, paused = false)
        assertTrue(yes.trickle)
        assertEquals(Int.MAX_VALUE, yes.documentsCap)

        val background = TagPolicy.policy(NetState.Unmetered, mobileAllowed = false, active = false, paused = false)
        assertFalse("the trickle runs only while the app is on screen", background.trickle)
        assertFalse("documents are read only while the app is on screen", background.documents)
        assertTrue(background.onDemand)

        val offline = TagPolicy.policy(NetState.Offline, mobileAllowed = true, active = true, paused = false)
        assertFalse(offline.onDemand || offline.documents || offline.trickle)

        val paused = TagPolicy.policy(NetState.Unmetered, mobileAllowed = true, active = true, paused = true)
        assertTrue("Pause stops the background only", paused.onDemand)
        assertFalse(paused.documents || paused.trickle)
        assertEquals(0, paused.documentsCap)

        val pausedOnMobile = TagPolicy.policy(NetState.Metered, mobileAllowed = false, active = true, paused = true)
        assertTrue(pausedOnMobile.onDemand)
        assertFalse(pausedOnMobile.documents || pausedOnMobile.trickle)

        val pausedOffline = TagPolicy.policy(NetState.Offline, mobileAllowed = true, active = true, paused = true)
        assertFalse(pausedOffline.onDemand || pausedOffline.documents || pausedOffline.trickle)
    }

    @Test
    fun theMobileDataQuestionIsAskedOnce() {
        assertTrue(TagPolicy.shouldAsk(NetState.Metered, asked = false, pending = 10))
        assertFalse(TagPolicy.shouldAsk(NetState.Metered, asked = true, pending = 10))
        assertFalse(TagPolicy.shouldAsk(NetState.Unmetered, asked = false, pending = 10))
        assertFalse(TagPolicy.shouldAsk(NetState.Metered, asked = false, pending = 0))
    }

    // The reader with a fake connectivity: the policy flow is what the app's network watch feeds.

    private class Store(ids: List<Track>) : TagStore {
        val tracks = LinkedHashMap<TrackId, Track>().apply { ids.forEach { put(it.id, it) } }
        val order = ArrayList<Long>()

        override suspend fun pendingTags(limit: Int) =
            tracks.values.filter { it.tagState == TagState.TelegramOnly }.sortedByDescending { it.dateSec }.take(limit)

        override suspend fun pendingAmong(ids: Collection<TrackId>) =
            ids.mapNotNull { tracks[it] }.filter { it.tagState == TagState.TelegramOnly }

        override suspend fun pendingDocuments(limit: Int) =
            tracks.values.filter { it.tagState == TagState.TelegramOnly && it.durationMs == 0L }.sortedByDescending { it.dateSec }.take(limit)

        override suspend fun applyTags(results: List<TagResult>) {
            for (r in results) {
                order += r.id.messageId
                tracks[r.id] = tracks.getValue(r.id).copy(tagState = TagState.Read)
            }
        }

        override suspend fun counts() = TagCounts(
            pending = tracks.values.count { it.tagState == TagState.TelegramOnly },
            read = tracks.values.count { it.tagState == TagState.Read },
        )
    }

    private class Bytes : FileBytes {
        val file = Samples.id3Tag(3, Samples.id3Frame23("TIT2", Samples.latin1Text("Song"))) + Samples.mpegFrame()

        override suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray? {
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
    fun onMobileDataOnlyWhatIsAskedForIsReadAndTheDataIsCounted() = runTest {
        val tracks = (1L..40L).map { t(it) } + (41L..45L).map { t(it, durationMs = 0) }
        val store = Store(tracks)
        val policy = MutableStateFlow(TagPolicy.policy(NetState.Metered, false, true, false))
        var fetched = 0L
        val enricher = TagEnricher(store, Bytes(), NoCovers, this, policy = policy, onBytes = { fetched += it })
        enricher.prefer(listOf(TrackId(1, 3), TrackId(1, 4)))
        enricher.preferUpcoming(listOf(TrackId(1, 10)))
        advanceUntilIdle()
        assertEquals("on screen, then about to play, then the documents", listOf(3L, 4L, 10L, 45L, 44L, 43L, 42L, 41L), store.order)
        assertFalse(enricher.status.value.running)
        assertTrue(fetched > 0)

        // Wi-Fi comes: the policy allows the trickle and a kick reads the rest.
        policy.value = TagPolicy.policy(NetState.Unmetered, false, true, false)
        enricher.kick()
        advanceUntilIdle()
        assertTrue(store.tracks.values.all { it.tagState == TagState.Read })
    }

    @Test
    fun documentsOnMobileDataStopAtTheCap() = runTest {
        val store = Store((1L..100L).map { t(it, durationMs = 0) })
        val policy = MutableStateFlow(TagPolicy.policy(NetState.Metered, false, true, false))
        val enricher = TagEnricher(store, Bytes(), NoCovers, this, policy = policy)
        enricher.kick()
        advanceUntilIdle()
        assertEquals(TagPolicy.METERED_DOCUMENTS, store.order.size)
    }

    @Test
    fun onDemandReadsAreQuietInTheBanner() = runTest {
        val store = Store((1L..30L).map { t(it) })
        val policy = MutableStateFlow(TagPolicy.policy(NetState.Metered, false, true, false))
        val enricher = TagEnricher(store, Bytes(), NoCovers, this, policy = policy, gapMs = 1_000)
        enricher.prefer(listOf(TrackId(1, 1)))
        runCurrent()
        assertFalse(enricher.status.value.background)
        val view = LibraryStatus.view(ScanStatus(), enricher.status.value, paused = false, online = true, heldForWifi = true)
        assertEquals(LibraryPhase.WaitingWifi, view.phase)
        assertFalse(view.showBanner)
        advanceUntilIdle()
    }

    @Test
    fun theTrickleRestsAtLeastAsLongAsItWorked() {
        // A batch of 12 that took 20 s rests 20 s: busy at most half of the time.
        assertEquals(20_000L, TricklePacing.restAfter(batchMs = 20_000, files = 12, lowRam = false, minGapMs = 1_000))
        // A quick batch of 12 (3 s) rests until 15 s have passed: 0.8 files a second at most.
        assertEquals(12_000L, TricklePacing.restAfter(batchMs = 3_000, files = 12, lowRam = false, minGapMs = 1_000))
        // Slower on a low memory phone: one file per 2.5 s.
        assertEquals(12_000L, TricklePacing.restAfter(batchMs = 3_000, files = 6, lowRam = true, minGapMs = 1_000))
        // Never less than the usual gap, never more than the cap, and garbage in stays bounded.
        assertEquals(1_000L, TricklePacing.restAfter(batchMs = 0, files = 0, lowRam = false, minGapMs = 1_000))
        assertEquals(TricklePacing.MAX_REST_MS, TricklePacing.restAfter(batchMs = 600_000, files = 12, lowRam = false, minGapMs = 1_000))
        assertEquals(1_000L, TricklePacing.restAfter(batchMs = -5, files = -3, lowRam = false, minGapMs = 1_000))
    }

    @Test
    fun theTrickleAveragesAtMostOneFileASecondAndHalfTheTime() {
        // A fake clock: batches of 12 taking 1 s to 30 s, each followed by its rest.
        for (took in listOf(1_000L, 5_000L, 12_000L, 30_000L)) {
            var now = 0L
            var files = 0
            var busy = 0L
            repeat(10) {
                now += took
                busy += took
                files += 12
                now += TricklePacing.restAfter(took, 12, lowRam = false, minGapMs = 1_000)
            }
            assertTrue("rate for a ${took}ms batch: $files files in $now ms", files * 1_000L <= now)
            assertTrue("duty for a ${took}ms batch: $busy of $now", busy * 2 <= now)
        }
    }
}
