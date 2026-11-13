package com.tmplayer.music.data

import com.tmplayer.music.data.OfflinePlanner.Skip
import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Keep offline queue against a scripted TDLib: space, FLOOD_WAIT, cancel, restart, no connection. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val tdDir by lazy { File(folder.root, "td").also { it.mkdirs() } }
    private val offlineDir by lazy { File(folder.root, "offline") }

    /** A TDLib that has files of the sizes asked for and answers as scripted. */
    private open inner class FakeCalls : KeepCalls {
        val sizes = HashMap<Long, Int>()
        val lookups = HashMap<Long, ArrayDeque<KeepLookup>>()
        val starts = HashMap<Long, ArrayDeque<KeepStart>>()

        /** Messages whose first start does not complete by itself. */
        val holdFirst = HashSet<Long>()

        /** Messages whose every start stays incomplete. */
        val holdAlways = HashSet<Long>()
        val bus = MutableSharedFlow<KeepFile>(extraBufferCapacity = 64)
        val cancelled = ArrayList<Int>()
        val forgotten = ArrayList<Int>()
        val started = ArrayList<Int>()
        val asked = HashMap<Int, Int>()

        fun file(message: Long, complete: Boolean, downloaded: Int = 0): KeepFile {
            val fileId = message.toInt()
            val path = File(tdDir, "f$fileId")
            if (complete) path.writeBytes(ByteArray(sizes[message] ?: 100) { 7 })
            return KeepFile(fileId, path.path, complete, downloaded.toLong(), (sizes[message] ?: 100).toLong())
        }

        override suspend fun lookUp(id: TrackId): KeepLookup =
            lookups[id.messageId]?.removeFirstOrNull() ?: KeepLookup.Found(id.messageId.toInt(), (sizes[id.messageId] ?: 100).toLong())

        override suspend fun start(fileId: Int): KeepStart {
            val message = fileId.toLong()
            started += fileId
            asked[fileId] = (asked[fileId] ?: 0) + 1
            starts[message]?.removeFirstOrNull()?.let { return it }
            if (message in holdAlways || message in holdFirst && asked[fileId] == 1) return KeepStart.Started(file(message, complete = false))
            return KeepStart.Started(file(message, complete = true, downloaded = sizes[message] ?: 100))
        }

        override fun updates(fileId: Int): Flow<KeepFile> = bus.filter { it.id == fileId }

        override suspend fun current(fileId: Int): KeepFile? = null

        override suspend fun cancel(fileId: Int) {
            cancelled += fileId
        }

        override suspend fun forget(fileId: Int) {
            forgotten += fileId
            File(tdDir, "f$fileId").delete()
        }
    }


    private val scopes = ArrayList<CoroutineScope>()
    private lateinit var lastScope: CoroutineScope

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
    }

    private class Rig(
        val engine: KeepEngine,
        val store: OfflineStore,
        val calls: Any,
        val network: MutableStateFlow<NetState>,
        val wifi: MutableStateFlow<Boolean>,
    )

    private fun TestScope.rig(
        calls: FakeCalls,
        free: () -> Long = { Long.MAX_VALUE / 2 },
        net: NetState = NetState.Unmetered,
        store: OfflineStore = OfflineStore().also { it.open(offlineDir) },
        stallMs: Long = 120_000,
        concurrency: Int = 2,
        network: MutableStateFlow<NetState> = MutableStateFlow(net),
    ): Rig {
        val wifi = MutableStateFlow(true)
        // Not backgroundScope: advanceUntilIdle leaves a background scope's work alone.
        val engineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        lastScope = engineScope
        val engine = KeepEngine(
            store = store,
            calls = calls,
            freeBytes = free,
            network = network,
            wifiOnly = wifi,
            scope = engineScope,
            concurrency = concurrency,
            reserveBytes = 0,
            clockMs = { currentTime },
            stallMs = stallMs,
        )
        return Rig(engine, store, calls, network, wifi)
    }

    private fun item(message: Long, size: Long = 100, chat: Long = 1) =
        QueuedTrack(chat, message, "Song $message", "Artist", "s$message.mp3", size, "u$message")

    private val Rig.keptIds get() = store.kept.value.keys.map { it.messageId }.sorted()

    @Test
    fun `a track is moved into the offline folder and TDLib's copy is gone`() = runTest {
        val calls = FakeCalls()
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        val path = rig.store.pathOf(TrackId(1, 1))!!
        assertEquals(100L, path.length())
        assertFalse(File(tdDir, "f1").exists())
        assertEquals(listOf(1), calls.forgotten)
        assertEquals(setOf("k1_1.mp3", OfflineStore.INDEX), offlineDir.list()!!.filter { it != OfflineStore.BACKUP }.toSet())
        val state = rig.engine.state.value
        assertEquals(KeepPhase.Idle, state.phase)
        assertEquals(1, state.summary?.kept)
        assertTrue(state.summary!!.skipped.isEmpty())
        assertTrue(rig.engine.pending.value.isEmpty())
    }

    @Test
    fun `the same track asked for twice is kept once`() = runTest {
        val calls = FakeCalls()
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(1)))
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        assertEquals(1, calls.started.size)
    }

    @Test
    fun `a call that gets a flood wait waits it out while the other track goes on`() = runTest {
        val calls = FakeCalls()
        calls.lookups[1] = ArrayDeque(listOf(KeepLookup.Flood(30)))
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceTimeBy(5_000)
        runCurrent()
        // The second went on and finished while the first still waits.
        assertEquals(listOf(2L), rig.keptIds)
        val waiting = rig.engine.state.value.running.single()
        assertEquals(1L, waiting.id.messageId)
        assertTrue(waiting.waitUntilMs > currentTime)
        advanceTimeBy(30_000)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), rig.keptIds)
        assertEquals(2, rig.engine.state.value.summary?.kept)
    }

    @Test
    fun `a download call that gets a flood wait asks again after the wait`() = runTest {
        val calls = FakeCalls()
        calls.starts[1] = ArrayDeque(listOf(KeepStart.Flood(10)))
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceTimeBy(9_000)
        runCurrent()
        assertTrue(rig.keptIds.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        assertEquals(2, calls.asked[1])
    }

    @Test
    fun `too many flood waits in a row skip the track with the reason`() = runTest {
        val calls = FakeCalls()
        calls.lookups[1] = ArrayDeque(List(10) { KeepLookup.Flood(5) })
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceUntilIdle()
        assertEquals(listOf(2L), rig.keptIds)
        val skipped = rig.engine.state.value.summary!!.skipped.single()
        assertEquals(Skip.Failed, skipped.reason)
        assertEquals(1L, skipped.item.messageId)
    }

    @Test
    fun `a track that does not fit is skipped and a smaller one after it is kept`() = runTest {
        val calls = FakeCalls()
        calls.sizes[1] = 500
        calls.sizes[2] = 50
        val rig = rig(calls, free = { 200 })
        rig.engine.enqueue(listOf(item(1, 500), item(2, 50)))
        advanceUntilIdle()
        assertEquals(listOf(2L), rig.keptIds)
        val summary = rig.engine.state.value.summary!!
        assertEquals(listOf(Skip.NoSpace), summary.skipped.map { it.reason })
        // Nothing was asked of TDLib for the track that could not fit.
        assertEquals(listOf(2), calls.started)
    }

    @Test
    fun `the space is looked at before each track, because the disk fills while the queue runs`() = runTest {
        val calls = FakeCalls()
        var free = 1_000L
        val rig = rig(calls, free = { free }, concurrency = 1)
        rig.engine.enqueue(listOf(item(1), item(2), item(3)))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), rig.keptIds)
        val again = rig(calls, free = { free }, concurrency = 1, store = OfflineStore().also { it.open(File(folder.root, "other")) })
        free = 50
        again.engine.enqueue(listOf(item(11), item(12)))
        advanceUntilIdle()
        assertTrue(again.keptIds.isEmpty())
        assertEquals(2, again.engine.state.value.summary!!.skipped.size)
    }

    @Test
    fun `a message that is gone or may not be saved is skipped with its reason`() = runTest {
        val calls = FakeCalls()
        calls.lookups[1] = ArrayDeque(listOf(KeepLookup.Gone))
        calls.lookups[2] = ArrayDeque(listOf(KeepLookup.NotAllowed))
        calls.lookups[3] = ArrayDeque(listOf(KeepLookup.Failed("boom")))
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2), item(3), item(4)))
        advanceUntilIdle()
        assertEquals(listOf(4L), rig.keptIds)
        val reasons = rig.engine.state.value.summary!!.skipped.associate { it.item.messageId to it.reason }
        assertEquals(mapOf(1L to Skip.Gone, 2L to Skip.NotAllowed, 3L to Skip.Failed), reasons)
    }

    @Test
    fun `cancel stops a running fetch and leaves nothing behind`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(2L), rig.keptIds)
        assertTrue(rig.engine.isDownloading(1))
        rig.engine.cancel(TrackId(1, 1))
        advanceUntilIdle()
        assertEquals(listOf(2L), rig.keptIds)
        assertFalse(rig.engine.isDownloading(1))
        assertTrue(1 in calls.cancelled)
        assertTrue(1 in calls.forgotten)
        assertTrue(offlineDir.list()!!.none { it.endsWith(OfflineStore.PART) })
        assertTrue(rig.engine.pending.value.isEmpty())
        // One track stopped: what the rest did is still told (only cancel all is silent).
        assertEquals(1, rig.engine.state.value.summary?.kept)
        assertEquals(KeepPhase.Idle, rig.engine.state.value.phase)
    }

    @Test
    fun `cancel all removes the waiting ones too and what was kept stays`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += setOf(2L, 3L, 4L)
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        rig.engine.enqueue(listOf(item(2), item(3), item(4)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, rig.engine.state.value.running.size)
        assertEquals(1, rig.engine.state.value.queued)
        rig.engine.cancelAll()
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        assertTrue(rig.engine.pending.value.isEmpty())
        assertEquals(KeepPhase.Idle, rig.engine.state.value.phase)
        assertNull(rig.engine.state.value.summary)
        assertTrue(offlineDir.list()!!.none { it.endsWith(OfflineStore.PART) })
    }

    @Test
    fun `without a connection the queue waits and goes on by itself when it is back`() = runTest {
        val calls = FakeCalls()
        val rig = rig(calls, net = NetState.Offline)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(rig.keptIds.isEmpty())
        assertEquals(KeepPhase.WaitingNetwork, rig.engine.state.value.phase)
        assertTrue(calls.started.isEmpty())
        rig.network.value = NetState.Unmetered
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), rig.keptIds)
    }

    @Test
    fun `losing the connection mid fetch keeps the partial bytes and carries on later`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(rig.keptIds.isEmpty())
        rig.network.value = NetState.Offline
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(KeepPhase.WaitingNetwork, rig.engine.state.value.phase)
        assertTrue(1 in calls.cancelled)
        // TDLib's partial bytes are kept for the resume.
        assertFalse(1 in calls.forgotten)
        assertEquals(setOf(TrackId(1, 1)), rig.engine.pending.value)
        rig.network.value = NetState.Unmetered
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        assertEquals(2, calls.asked[1])
    }

    @Test
    fun `wifi only waits on a metered connection until the switch is turned off`() = runTest {
        val calls = FakeCalls()
        val rig = rig(calls, net = NetState.Metered)
        rig.engine.enqueue(listOf(item(1)))
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(rig.keptIds.isEmpty())
        assertEquals(KeepPhase.WaitingWifi, rig.engine.state.value.phase)
        rig.wifi.value = false
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
    }

    @Test
    fun `pause keeps the partial bytes and resume carries on`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceTimeBy(1_000)
        runCurrent()
        rig.engine.pause()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(KeepPhase.Paused, rig.engine.state.value.phase)
        assertFalse(1 in calls.forgotten)
        assertEquals(setOf(TrackId(1, 1)), rig.engine.pending.value)
        advanceTimeBy(300_000)
        runCurrent()
        assertTrue(rig.keptIds.size <= 1)
        rig.engine.resume()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), rig.keptIds)
    }

    @Test
    fun `a fetch that goes quiet twice is given up on and its partial files removed`() = runTest {
        val calls = FakeCalls()
        calls.holdAlways += 1
        val rig = rig(calls, stallMs = 30_000)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceUntilIdle()
        assertEquals(listOf(2L), rig.keptIds)
        assertEquals(Skip.Stalled, rig.engine.state.value.summary!!.skipped.single().reason)
        assertTrue(1 in calls.forgotten)
        assertEquals(2, calls.asked[1])
    }

    @Test
    fun `a fetch that goes quiet once is asked again with what landed kept`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls, stallMs = 30_000)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), rig.keptIds)
        assertTrue(calls.asked[1] == 2)
        // TDLib's partial was never thrown away for the second try.
        assertFalse(1 in calls.forgotten.dropLast(1))
        assertNull(rig.engine.state.value.summary!!.skipped.singleOrNull())
    }

    @Test
    fun `a silence on a full disk is no space, not a stall`() = runTest {
        val calls = FakeCalls()
        calls.holdAlways += 1
        var asks = 0
        val rig = rig(calls, stallMs = 30_000, free = { if (++asks <= 1) 1_000 else 10 })
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertEquals(Skip.NoSpace, rig.engine.state.value.summary!!.skipped.single().reason)
    }

    @Test
    fun `two running fetches cannot both claim the same free space`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls, free = { 150 })
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceTimeBy(1_000)
        runCurrent()
        calls.bus.emit(calls.file(1, complete = true, downloaded = 100))
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
        assertEquals(listOf(Skip.NoSpace), rig.engine.state.value.summary!!.skipped.map { it.reason })
    }

    @Test
    fun `cancelling one track still tells what the rest did`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls, concurrency = 1)
        rig.engine.enqueue(listOf(item(1), item(2), item(3)))
        advanceTimeBy(1_000)
        runCurrent()
        rig.engine.cancel(TrackId(1, 1))
        advanceUntilIdle()
        assertEquals(listOf(2L, 3L), rig.keptIds)
        assertEquals(2, rig.engine.state.value.summary?.kept)
    }

    @Test
    fun `progress updates are followed and a whole file ends the wait`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += 1
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceTimeBy(1_000)
        runCurrent()
        calls.bus.emit(KeepFile(1, File(tdDir, "f1").path, false, 40, 100))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(40L, rig.engine.state.value.running.single().downloaded)
        calls.bus.emit(calls.file(1, complete = true, downloaded = 100))
        advanceUntilIdle()
        assertEquals(listOf(1L), rig.keptIds)
    }

    @Test
    fun `a file that arrives cut short is not kept`() = runTest {
        val calls = FakeCalls()
        calls.starts[1] = ArrayDeque(
            listOf(
                KeepStart.Started(
                    KeepFile(1, File(tdDir, "f1").also { it.writeBytes(ByteArray(40)) }.path, true, 100, 100),
                ),
            ),
        )
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertTrue(rig.keptIds.isEmpty())
        assertEquals(Skip.Stalled, rig.engine.state.value.summary!!.skipped.single().reason)
        assertTrue(offlineDir.list()!!.none { it.endsWith(OfflineStore.PART) })
    }

    @Test
    fun `a track that cannot be downloaded is skipped as not allowed`() = runTest {
        val calls = FakeCalls()
        calls.starts[1] = ArrayDeque(listOf(KeepStart.Started(KeepFile(1, "", false, 0, 100, canDownload = false))))
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertEquals(Skip.NotAllowed, rig.engine.state.value.summary!!.skipped.single().reason)
    }

    @Test
    fun `a refused download for lack of space is a no space skip`() = runTest {
        val calls = FakeCalls()
        calls.starts[1] = ArrayDeque(listOf(KeepStart.Failed("No space left on device")))
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1)))
        advanceUntilIdle()
        assertEquals(Skip.NoSpace, rig.engine.state.value.summary!!.skipped.single().reason)
    }

    @Test
    fun `the queue survives a restart and resumes when the app is back`() = runTest {
        val calls = FakeCalls()
        val first = rig(calls, net = NetState.Offline)
        first.engine.enqueue(listOf(item(1), item(2), item(3)))
        runCurrent()
        first.store.flush()
        // The app was killed; a new one opens the same folder.
        val store2 = OfflineStore().also { it.open(offlineDir) }
        val second = rig(calls, store = store2)
        second.engine.restore()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), second.keptIds)
    }

    @Test
    fun `a queue that was paused comes back paused`() = runTest {
        val calls = FakeCalls()
        val first = rig(calls, net = NetState.Offline)
        first.engine.enqueue(listOf(item(1)))
        first.engine.pause()
        first.store.flush()
        val second = rig(calls, store = OfflineStore().also { it.open(offlineDir) })
        second.engine.restore()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(KeepPhase.Paused, second.engine.state.value.phase)
        assertTrue(second.keptIds.isEmpty())
        second.engine.resume()
        advanceUntilIdle()
        assertEquals(listOf(1L), second.keptIds)
    }

    @Test
    fun `a track kept before the restart is not fetched again`() = runTest {
        val calls = FakeCalls()
        val first = rig(calls, net = NetState.Offline)
        first.engine.enqueue(listOf(item(1), item(2)))
        runCurrent()
        // Track 1 was kept by a run whose queue was not saved after.
        val part = first.store.partFile(TrackId(1, 1))!!
        part.writeBytes(ByteArray(100))
        first.store.commit(part, item(1), 100)
        first.store.flush()
        val second = rig(calls, store = OfflineStore().also { it.open(offlineDir) })
        second.engine.restore()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), second.keptIds)
        assertEquals(listOf(2), calls.started)
    }

    @Test
    fun `a chat that is left stops its tracks and takes its kept files`() = runTest {
        val calls = FakeCalls()
        calls.holdFirst += setOf(3L)
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1, chat = 1), item(2, chat = 2)))
        advanceUntilIdle()
        rig.engine.enqueue(listOf(item(3, chat = 1)))
        advanceTimeBy(1_000)
        runCurrent()
        assertArrayEqualsLongs(longArrayOf(1), rig.engine.protectedChats())
        rig.engine.cancelChat(1)
        advanceUntilIdle()
        rig.store.removeChat(1)
        assertEquals(listOf(2L), rig.keptIds)
        assertTrue(1 in calls.forgotten || 3 in calls.forgotten)
        assertEquals(0, rig.engine.protectedChats().size)
        assertEquals(setOf("k2_2.mp3", OfflineStore.INDEX), offlineDir.list()!!.filter { !it.endsWith(".tmp") && it != OfflineStore.BACKUP }.toSet())
    }

    @Test
    fun `nothing keeps running when the queue is done`() = runTest {
        val calls = FakeCalls()
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2), item(3)))
        advanceUntilIdle()
        // Only the connection watcher is left: no worker, no timer.
        val children = lastScope.coroutineContext[Job]!!.children.toList()
        assertEquals(1, children.size)
        assertNotNull(rig.engine.state.value.summary)
        rig.engine.dismissSummary()
        assertNull(rig.engine.state.value.summary)
    }

    @Test
    fun `a garbled TDLib answer does not take the queue down`() = runTest {
        val calls = object : FakeCalls() {
            override suspend fun lookUp(id: TrackId): KeepLookup =
                if (id.messageId == 1L) error("unexpected") else super.lookUp(id)
        }
        val rig = rig(calls)
        rig.engine.enqueue(listOf(item(1), item(2)))
        advanceUntilIdle()
        val summary = rig.engine.state.value.summary!!
        assertEquals(1, summary.kept)
        assertEquals(Skip.Failed, summary.skipped.single().reason)
    }

    private fun assertArrayEqualsLongs(expected: LongArray, actual: LongArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
