package com.tmplayer.music.data

import com.tmplayer.music.data.OfflinePlanner.Skip
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** A TDLib file as keeping needs it: where it is, whether it is whole, and how far it has come. */
data class KeepFile(
    val id: Int,
    val path: String,
    val complete: Boolean,
    val downloaded: Long = 0,
    val size: Long = 0,
    val canDownload: Boolean = true,
)

/** The answer to "which file is this message's audio, and may it be saved". */
sealed interface KeepLookup {
    data class Found(val fileId: Int, val size: Long) : KeepLookup
    data object Gone : KeepLookup
    data object NotAllowed : KeepLookup
    data class Flood(val seconds: Int) : KeepLookup
    data class Failed(val message: String) : KeepLookup

    /** TDLib could not be reached (not signed in, not connected): try again later, not a verdict. */
    data object Unavailable : KeepLookup
}

sealed interface KeepStart {
    data class Started(val file: KeepFile) : KeepStart
    data class Flood(val seconds: Int) : KeepStart
    data class Failed(val message: String) : KeepStart
}

/** The TDLib calls keeping makes, behind a seam so the engine's rules are tested on the JVM. */
interface KeepCalls {
    suspend fun lookUp(id: TrackId): KeepLookup

    /** `downloadFile`, not waiting for it. */
    suspend fun start(fileId: Int): KeepStart

    /** Every update of the file; collected before [start] so none is missed. */
    fun updates(fileId: Int): Flow<KeepFile>

    /** `getFile`: what TDLib knows of the file now. */
    suspend fun current(fileId: Int): KeepFile?

    /** `cancelDownloadFile`; the bytes that landed stay for a resume. */
    suspend fun cancel(fileId: Int)

    /** `deleteFile`: TDLib's copy and its record, once the audio is kept (or cancelled for good). */
    suspend fun forget(fileId: Int)
}

enum class KeepPhase { Idle, Running, WaitingNetwork, WaitingWifi, Paused }

/** One track being fetched now; [waitUntilMs] is set while its call waits out a FLOOD_WAIT. */
data class KeepRunning(val id: TrackId, val title: String, val downloaded: Long, val total: Long, val waitUntilMs: Long = 0)

data class KeepSkip(val item: QueuedTrack, val reason: Skip, val detail: String = "")

/** What a finished run did: how many tracks were kept, which were skipped and why. */
data class KeepSummary(val kept: Int, val keptBytes: Long, val skipped: List<KeepSkip>)

data class KeepState(
    val phase: KeepPhase = KeepPhase.Idle,
    val running: List<KeepRunning> = emptyList(),
    /** Tracks waiting their turn, not counting the ones being fetched. */
    val queued: Int = 0,
    /** Tracks asked for in this run, and how many are kept or skipped so far. */
    val total: Int = 0,
    val done: Int = 0,
    val summary: KeepSummary? = null,
) {
    /** True while a foreground service is worth its notification. */
    val working: Boolean get() = phase == KeepPhase.Running
    val hasWork: Boolean get() = queued > 0 || running.isNotEmpty()
}

/**
 * Keeps tracks offline: a queue fetched by TDLib one or two at a time, each moved into the offline
 * folder when whole. The rules (AGENTS.md):
 *
 * - Free space is checked before each track, not once at the start: the disk can fill while the
 *   queue runs. A track that does not fit is skipped, the rest go on.
 * - A call that gets a FLOOD_WAIT waits it out and asks again, on its own; the other worker keeps
 *   going. Past a few waits in a row the track is skipped with the reason.
 * - No connection, or a metered one with Wi-Fi only on: the queue waits, the partial bytes stay,
 *   and it goes on by itself when the connection allows. Nothing polls: waiting is a suspended flow.
 * - A fetch that goes quiet is put at the back of the queue once with what landed kept, and only
 *   a second silence gives up on it; a silence on a full disk is NoSpace, not Stalled.
 * - Each running fetch holds the bytes it still needs, so two workers cannot both pass the space
 *   check for the same room.
 * - Cancel removes what the fetch left behind, Pause keeps TDLib's partial for the resume. A track
 *   is in the offline folder whole or not at all.
 * - The queue is saved ([OfflineStore.saveQueue]) and [restore]d, so a killed app carries on.
 */
class KeepEngine(
    private val store: OfflineStore,
    private val calls: KeepCalls,
    private val freeBytes: () -> Long,
    private val network: StateFlow<NetState>,
    private val wifiOnly: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val concurrency: Int = 2,
    private val reserveBytes: Long = CachePolicy.HEADROOM,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val stallMs: Long = STALL_MS,
    private val onForeground: (Boolean) -> Unit = {},
) {
    /** What became of a track's turn. */
    private enum class After { Kept, Skipped, Requeue, RequeueLast, Dropped }

    private class Active(val item: QueuedTrack) {
        @Volatile var job: Job? = null
        var fileId = 0

        /** Whether TDLib was asked for the file: only then is there anything of TDLib's to withdraw. */
        var started = false
        var downloaded = 0L
        var total = item.sizeBytes

        /** Set once the space check passed: the bytes still to arrive count against the free space of the others. */
        var reserved = false
        var waitUntilMs = 0L
        var lastPublishMs = 0L

        /** Set before the job is cancelled: what to do with the track afterwards. */
        @Volatile var disposition: After? = null
        var outcome: After? = null
        var skip: KeepSkip? = null
    }

    private val lock = Any()
    private val waiting = ArrayDeque<QueuedTrack>()
    private val active = LinkedHashMap<TrackId, Active>()
    private val workers = ArrayList<Job>()
    private var paused = false
    private var runTotal = 0
    private var runKept = 0
    private var runBytes = 0L
    private val runSkipped = ArrayList<KeepSkip>()
    private var summary: KeepSummary? = null
    private var lastSavedMs = 0L
    private var foreground = false
    private var silentNext = false

    /** Tracks that already went quiet once: the second silence is the end of them. */
    private val stalledOnce = HashSet<TrackId>()

    private val _state = MutableStateFlow(KeepState())
    val state: StateFlow<KeepState> = _state.asStateFlow()

    private val _pending = MutableStateFlow<Set<TrackId>>(emptySet())

    /** Every track waiting or being fetched, for the menu's "stop keeping" and the row marks. */
    val pending: StateFlow<Set<TrackId>> = _pending.asStateFlow()

    private val gate = MutableStateFlow(true)
    private val activeFileIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    init {
        // The phase follows the connection and the Wi-Fi only switch; nothing runs while they hold still.
        combine(network, wifiOnly) { n, w -> n to w }.onEach { synchronized(lock) { publish() } }.launchIn(scope)
    }

    /** True while TDLib is filling this file for a kept track, so streaming leaves its download alone. */
    fun isDownloading(fileId: Int): Boolean = fileId in activeFileIds

    /** The chats of everything waiting or fetching: TDLib's cache trim leaves these chats' files alone. */
    fun protectedChats(): LongArray = synchronized(lock) {
        (waiting.map { it.chatId } + active.keys.map { it.chatId }).distinct().toLongArray()
    }

    /** Brings back the queue an earlier run of the app was in the middle of. */
    fun restore() {
        val (saved, wasPaused) = store.savedQueue()
        val items = saved.filter { !store.isKept(it.id) }
        if (items.isEmpty()) return
        synchronized(lock) {
            if (waiting.isNotEmpty() || active.isNotEmpty()) return
            paused = wasPaused
            gate.value = !paused
            add(items)
            publish()
        }
        spawn()
    }

    /** Queues [items] (already planned for space and duplicates). Asking for the same track twice does nothing. */
    fun enqueue(items: List<QueuedTrack>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            if (paused) {
                paused = false
                gate.value = true
            }
            add(items)
            saveQueue(force = true)
            publish()
        }
        spawn()
    }

    private fun add(items: List<QueuedTrack>) {
        val have = HashSet<TrackId>(waiting.size + active.size + items.size)
        waiting.forEach { have += it.id }
        have += active.keys
        var added = 0
        for (item in items) {
            if (item.id in have || store.isKept(item.id)) continue
            have += item.id
            waiting.addLast(item)
            added++
        }
        if (added > 0) {
            // A new run starts when nothing else was going: the last run's summary is out of date.
            if (waiting.size == added && active.isEmpty()) summary = null
            runTotal += added
            _pending.value = have
        }
    }

    fun pause() {
        synchronized(lock) {
            if (paused) return
            paused = true
            gate.value = false
            for (a in active.values) {
                a.disposition = After.Requeue
                a.job?.cancel()
            }
            saveQueue(force = true)
            publish()
        }
    }

    fun resume() {
        synchronized(lock) {
            if (!paused) return
            paused = false
            gate.value = true
            saveQueue(force = true)
            publish()
        }
        spawn()
    }

    /** Stops keeping [id]: out of the queue, or its fetch cancelled with its partial files removed. */
    fun cancel(id: TrackId) = cancelWhere(silent = false) { it == id }

    /** Stops everything. Nothing is left half done; what was kept already stays. No summary follows. */
    fun cancelAll() = cancelWhere(silent = true) { true }

    /** A chat was left or removed: its waiting and running tracks stop. */
    fun cancelChat(chatId: Long) = cancelWhere(silent = false) { it.chatId == chatId }

    /**
     * Only [cancelAll] is silent: the user stopped the whole run. Stopping one track or one chat
     * leaves the rest running, and what they kept and skipped is still told when they end.
     */
    private fun cancelWhere(silent: Boolean, match: (TrackId) -> Boolean) {
        synchronized(lock) {
            val before = waiting.size
            waiting.removeAll { match(it.id) }
            val running = active.values.filter { match(it.item.id) }
            if (waiting.size == before && running.isEmpty()) return
            for (a in running) {
                a.disposition = After.Dropped
                a.job?.cancel()
            }
            runTotal = (runTotal - (before - waiting.size)).coerceAtLeast(0)
            if (silent) silentNext = true
            _pending.value = pendingIds()
            saveQueue(force = true)
            maybeFinish()
            publish()
        }
    }

    fun dismissSummary() {
        synchronized(lock) {
            summary = null
            publish()
        }
    }

    private fun spawn() {
        synchronized(lock) {
            workers.removeAll { !it.isActive }
            val want = minOf(concurrency, waiting.size + active.size)
            while (workers.size < want) workers += scope.launch { work() }
        }
    }

    private suspend fun work() {
        while (true) {
            gate.first { it }
            if (!allowedNow()) awaitConnection()
            val taken: Active = synchronized(lock) {
                if (paused) return@synchronized null
                val item = waiting.removeFirstOrNull()
                if (item == null) {
                    maybeFinish()
                    return
                }
                Active(item).also {
                    active[item.id] = it
                    publish()
                }
            } ?: continue
            runItem(taken)
        }
    }

    private fun allowed(n: NetState, wifi: Boolean): Boolean = when (n) {
        NetState.Offline -> false
        NetState.Metered -> !wifi
        NetState.Unmetered -> true
    }

    private fun allowedNow(): Boolean = allowed(network.value, wifiOnly.value)

    private suspend fun awaitConnection() {
        combine(network, wifiOnly, gate) { n, w, g -> g && allowed(n, w) }.first { it }
    }

    private suspend fun runItem(a: Active) {
        coroutineScope {
            val child = launch { fetch(a) }
            a.job = child
            // Cancelled between being taken and getting its job: the job is cancelled now.
            if (a.disposition != null) child.cancel()
            child.join()
        }
        // The track's fate: what the fetch settled, else what whoever cancelled it said.
        val fate = a.outcome ?: a.disposition ?: After.Requeue
        synchronized(lock) {
            active.remove(a.item.id)
            if (fate != After.RequeueLast) stalledOnce.remove(a.item.id)
            when (fate) {
                After.Kept -> {
                    runKept++
                    runBytes += store.kept.value[a.item.id]?.sizeBytes ?: 0
                }
                After.Skipped -> a.skip?.let { runSkipped += it }
                After.Requeue -> waiting.addFirst(a.item)
                After.RequeueLast -> waiting.addLast(a.item)
                After.Dropped -> runTotal = (runTotal - 1).coerceAtLeast(0)
            }
            _pending.value = pendingIds()
            saveQueue(force = false)
            publish()
        }
    }

    private fun pendingIds(): Set<TrackId> {
        val out = HashSet<TrackId>(waiting.size + active.size)
        waiting.forEach { out += it.id }
        out += active.keys
        return out
    }

    private suspend fun fetch(a: Active) {
        try {
            a.outcome = attempt(a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "keeping ${a.item.id} failed", e)
            skip(a, Skip.Failed, Failures.humanise(e))
        } finally {
            // Whatever ended the fetch, TDLib's call is withdrawn. What is left of it depends on the
            // fate: a pause or a lost connection keeps the bytes that landed for the resume; a skip,
            // a cancel or a purge removes them, so no partial file is left behind.
            withContext(NonCancellable) {
                val fate = a.outcome ?: a.disposition ?: After.Requeue
                val fileId = a.fileId
                if (a.started && fileId != 0) {
                    runCatching { calls.cancel(fileId) }
                    if (fate == After.Skipped || fate == After.Dropped) runCatching { calls.forget(fileId) }
                    activeFileIds.remove(fileId)
                }
                if (fate != After.Kept) store.partFile(a.item.id)?.let { runCatching { it.delete() } }
            }
        }
    }

    private suspend fun attempt(a: Active): After {
        val item = a.item
        // 1. which file is it, and may it be saved
        var floods = 0
        var unavailable = 0
        var found: KeepLookup.Found? = null
        while (found == null) {
            when (val look = calls.lookUp(item.id)) {
                is KeepLookup.Found -> found = look
                KeepLookup.Gone -> return skip(a, Skip.Gone)
                KeepLookup.NotAllowed -> return skip(a, Skip.NotAllowed)
                is KeepLookup.Failed -> return skip(a, Skip.Failed, look.message)
                is KeepLookup.Flood -> if (!waitOutFlood(a, look.seconds, ++floods)) return skip(a, Skip.Failed, floodText(look.seconds))
                KeepLookup.Unavailable -> {
                    if (!allowedNow()) return After.Requeue
                    if (++unavailable > MAX_UNAVAILABLE) return skip(a, Skip.Failed, Failures.OFFLINE)
                    delay(UNAVAILABLE_DELAY_MS * unavailable)
                }
            }
        }
        val size = found.size.takeIf { it > 0 } ?: item.sizeBytes
        a.fileId = found.fileId
        a.total = size
        if (store.isKept(item.id)) return After.Kept

        // 2. room for it, now, counting what the other running fetches still have to write
        if (!reserveRoom(a, size)) return skip(a, Skip.NoSpace)

        // 3. fetch it
        a.started = true
        activeFileIds += found.fileId
        val file = fetchFile(a, found.fileId, size) ?: return a.outcome ?: After.Skipped

        // 4. into the offline folder
        return place(a, file, size)
    }

    /** The whole TDLib file, or null when the track was skipped or must wait ([Active.outcome] says which). */
    private suspend fun fetchFile(a: Active, fileId: Int, size: Long): KeepFile? {
        val live = MutableStateFlow<KeepFile?>(null)
        return coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                calls.updates(fileId).collect { live.value = it }
            }
            try {
                var floods = 0
                var started: KeepFile? = null
                while (started == null) {
                    when (val answer = calls.start(fileId)) {
                        is KeepStart.Started -> started = answer.file
                        is KeepStart.Flood -> if (!waitOutFlood(a, answer.seconds, ++floods)) {
                            skip(a, Skip.Failed, floodText(answer.seconds))
                            return@coroutineScope null
                        }
                        is KeepStart.Failed -> {
                            skip(a, if (isNoSpace(answer.message)) Skip.NoSpace else Skip.Failed, answer.message)
                            return@coroutineScope null
                        }
                    }
                }
                if (!started.canDownload && !started.complete) {
                    skip(a, Skip.NotAllowed)
                    return@coroutineScope null
                }
                if (live.value == null || started.complete) live.value = started
                progress(a, live.value ?: started, size)
                var last = (live.value ?: started).downloaded
                while (true) {
                    val seen = live.value
                    if (seen != null && seen.complete) return@coroutineScope seen
                    val moved = withTimeoutOrNull(stallMs) {
                        combine(live, network, wifiOnly) { f, n, w -> Triple(f, n, w) }
                            .first { (f, n, w) -> !allowed(n, w) || f != null && (f.complete || f.downloaded > last) }
                    }
                    if (moved == null) {
                        // Silence: look at the file itself, once, before giving up on it.
                        val now = calls.current(fileId)
                        if (now != null && (now.complete || now.downloaded > last)) {
                            live.value = now
                            last = now.downloaded
                            continue
                        }
                        if (diskFull(size, last)) {
                            skip(a, Skip.NoSpace)
                            return@coroutineScope null
                        }
                        // Once more, later, from what landed; the second silence is the end of it.
                        if (synchronized(lock) { stalledOnce.add(a.item.id) }) {
                            Logger.i(TAG, "${a.item.id} went quiet: back of the queue once")
                            a.outcome = After.RequeueLast
                            return@coroutineScope null
                        }
                        skip(a, Skip.Stalled)
                        return@coroutineScope null
                    }
                    val (f, n, w) = moved
                    if (!allowed(n, w)) {
                        // The connection went: the queue waits for it, the partial bytes stay.
                        a.outcome = After.Requeue
                        return@coroutineScope null
                    }
                    if (f != null) {
                        last = f.downloaded
                        progress(a, f, size)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                return@coroutineScope null
            } finally {
                collector.cancel()
            }
        }
    }

    /**
     * Whether [a] fits in the free space now, with the bytes the other running fetches still have
     * to write held back; if it does, the same bytes are held for it. One step under the lock, so
     * two workers cannot both pass for the same room.
     */
    private fun reserveRoom(a: Active, size: Long): Boolean = synchronized(lock) {
        val others = active.values.filter { it !== a && it.reserved }.sumOf { other ->
            (OfflinePlanner.planned(other.total) - other.downloaded).coerceAtLeast(0)
        }
        val fits = freeBytes() - others >= OfflinePlanner.planned(size) + reserveBytes
        if (fits) a.reserved = true
        fits
    }

    /** True when the disk has no room left for what is still to arrive: a silence then is a full disk. */
    private fun diskFull(size: Long, downloaded: Long): Boolean =
        freeBytes() < (OfflinePlanner.planned(size) - downloaded).coerceAtLeast(0) + reserveBytes

    private fun progress(a: Active, file: KeepFile, size: Long) {
        val now = clockMs()
        synchronized(lock) {
            a.downloaded = file.downloaded
            a.total = size.takeIf { it > 0 } ?: file.size
            a.waitUntilMs = 0
            if (now - a.lastPublishMs >= PROGRESS_EVERY_MS || file.complete) {
                a.lastPublishMs = now
                publish()
            }
        }
    }

    /** Waits out a FLOOD_WAIT of [seconds]; false after too many in a row, or a wait too long to be worth it. */
    private suspend fun waitOutFlood(a: Active, seconds: Int, times: Int): Boolean {
        if (times > MAX_FLOODS || seconds > MAX_FLOOD_SECONDS) return false
        val waitMs = seconds.coerceAtLeast(1) * 1000L + 500
        Logger.i(TAG, "waiting ${seconds}s for Telegram before asking again ($times)")
        synchronized(lock) {
            a.waitUntilMs = clockMs() + waitMs
            publish()
        }
        delay(waitMs)
        synchronized(lock) {
            a.waitUntilMs = 0
            publish()
        }
        return true
    }

    private fun floodText(seconds: Int): String = Failures.humanise("FLOOD_WAIT_$seconds")

    private fun isNoSpace(message: String): Boolean =
        message.contains("No space left", ignoreCase = true) || message.contains("ENOSPC") ||
            message.contains("Not enough disk space", ignoreCase = true)

    private fun skip(a: Active, reason: Skip, detail: String = ""): After {
        a.skip = KeepSkip(a.item, reason, detail)
        a.outcome = After.Skipped
        return After.Skipped
    }

    /** Moves the whole TDLib file into the offline folder (a rename, or a copy when it cannot rename) and lists it. */
    private suspend fun place(a: Active, file: KeepFile, size: Long): After {
        val item = a.item
        val source = File(file.path)
        if (file.path.isEmpty() || !source.isFile) return skip(a, Skip.Failed, "The downloaded file is missing")
        val length = source.length()
        if (size > 0 && length < size) return skip(a, Skip.Stalled, "The file arrived cut short")
        val part = store.partFile(item.id) ?: return skip(a, Skip.Failed, "No place to keep it")
        runCatching { part.delete() }
        if (!source.renameTo(part)) {
            // Another volume: a copy, which needs the room for a second one.
            if (freeBytes() < length + reserveBytes) return skip(a, Skip.NoSpace)
            try {
                copy(source, part)
            } catch (e: CancellationException) {
                runCatching { part.delete() }
                throw e
            } catch (e: IOException) {
                runCatching { part.delete() }
                return skip(a, if (isNoSpace(e.message.orEmpty())) Skip.NoSpace else Skip.Failed, e.message.orEmpty())
            }
        }
        if (part.length() != length) {
            runCatching { part.delete() }
            return skip(a, Skip.Failed, "The copy is not whole")
        }
        if (!store.commit(part, item, length)) return skip(a, Skip.Failed, "Could not keep the file")
        a.outcome = After.Kept
        // TDLib's own record of the file is no longer true; the audio is ours now.
        withContext(NonCancellable) { runCatching { calls.forget(file.id) } }
        return After.Kept
    }

    private suspend fun copy(from: File, to: File) {
        val context = currentCoroutineContext()
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    context.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
        // The source is the TDLib copy: gone once ours is whole.
        runCatching { from.delete() }
    }

    /** Called with the lock held: ends the run when nothing waits or runs. */
    private fun maybeFinish() {
        if (waiting.isNotEmpty() || active.isNotEmpty()) return
        // Parked workers end here; the one calling returns right after, with nothing left to await.
        workers.forEach { it.cancel() }
        workers.clear()
        val silent = silentNext
        silentNext = false
        if (!silent && (runKept > 0 || runSkipped.isNotEmpty())) {
            summary = KeepSummary(runKept, runBytes, runSkipped.toList())
        }
        runTotal = 0
        runKept = 0
        runBytes = 0
        runSkipped.clear()
        saveQueue(force = true)
        store.flush()
        publish()
    }

    private fun saveQueue(force: Boolean) {
        val now = clockMs()
        if (!force && now - lastSavedMs < SAVE_EVERY_MS) return
        lastSavedMs = now
        val all = ArrayList<QueuedTrack>(active.size + waiting.size)
        active.values.forEach { all += it.item }
        all += waiting
        store.saveQueue(all, paused)
    }

    private fun publish() {
        val running = active.values.map { KeepRunning(it.item.id, it.item.title, it.downloaded, it.total, it.waitUntilMs) }
        val busy = waiting.isNotEmpty() || active.isNotEmpty()
        val phase = when {
            !busy -> KeepPhase.Idle
            paused -> KeepPhase.Paused
            network.value == NetState.Offline -> KeepPhase.WaitingNetwork
            !allowedNow() -> KeepPhase.WaitingWifi
            else -> KeepPhase.Running
        }
        _state.value = KeepState(
            phase = phase,
            running = running,
            queued = waiting.size,
            total = runTotal,
            done = (runTotal - waiting.size - active.size).coerceAtLeast(0),
            summary = summary,
        )
        val fg = phase == KeepPhase.Running
        if (fg != foreground) {
            foreground = fg
            runCatching { onForeground(fg) }
        }
    }

    companion object {
        private const val TAG = "KeepEngine"
        const val STALL_MS = 120_000L
        private const val MAX_FLOODS = 6
        private const val MAX_FLOOD_SECONDS = 1_800
        private const val MAX_UNAVAILABLE = 4
        private const val UNAVAILABLE_DELAY_MS = 15_000L
        private const val PROGRESS_EVERY_MS = 400L
        private const val SAVE_EVERY_MS = 10_000L
        private const val COPY_BUFFER = 256 * 1024
    }
}
