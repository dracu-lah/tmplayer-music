package com.tmplayer.music.data

import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Keep offline, for the rest of the app: the tracks kept ([kept], in [store]), the queue fetching
 * more ([state], [pending]), and the questions streaming asks ([isDownloading]).
 *
 * Kept audio lives outside TDLib's cache, so the cache's Free up space and its ceiling never touch
 * it (and the cache trim is told which chats have a fetch going, [protectedChats]). A kept track
 * plays from its own file with no TDLib at all, which is what makes it work with no network.
 * [start] is called once by the app; before that, and in the demo, nothing is kept.
 */
object OfflineDownloads {
    private const val TAG = "OfflineDownloads"
    private const val KEY_WIFI_ONLY = "offline_wifi_only"

    private val scope = JobFailures.scope("Offline", Dispatchers.IO)

    /** What is kept: the files, the list and the saved queue. */
    val store = OfflineStore(scope)

    @Volatile
    private var engine: KeepEngine? = null

    @Volatile
    private var settings: KeyValues? = null

    private val wifi = MutableStateFlow(true)

    /** Fetch only on an unmetered connection: on by default, so mobile data is never spent unasked. */
    val wifiOnly: StateFlow<Boolean> = wifi.asStateFlow()

    private val none = MutableStateFlow(KeepState())
    private val noPending = MutableStateFlow<Set<TrackId>>(emptySet())

    /** Told when a foreground service is worth having (a fetch is running) and when it is not. */
    @Volatile
    var onForeground: (Boolean) -> Unit = {}

    /** Every track kept, by message. */
    val kept: StateFlow<Map<TrackId, KeptTrack>> get() = store.kept

    /** The queue: what is fetching, what waits, the summary of the last run. */
    val state: StateFlow<KeepState> get() = engine?.state ?: none

    /** Tracks waiting or being fetched. */
    val pending: StateFlow<Set<TrackId>> get() = engine?.pending ?: noPending

    /**
     * Starts keeping in [folder] (its own folder in the app's files), with the switch in [store]
     * and the connection from the library. Picks up the queue an earlier run was in the middle of.
     */
    fun start(folder: File, store: KeyValues, lowRam: Boolean, network: StateFlow<NetState> = Library.network) {
        synchronized(this) {
            if (engine != null) return
            settings = store
            wifi.value = store.getBoolean(KEY_WIFI_ONLY, true)
            this.store.open(folder)
            engine = KeepEngine(
                store = this.store,
                calls = TdKeepCalls,
                freeBytes = { this.store.folder?.let { runCatching { it.usableSpace }.getOrDefault(0L) } ?: 0L },
                network = network,
                wifiOnly = wifi,
                scope = scope,
                concurrency = if (lowRam) 1 else 2,
                onForeground = { on -> onForeground(on) },
            ).also { it.restore() }
        }
    }

    /** True while TDLib fills this file for a kept track, so streaming does not cancel or move that download. */
    fun isDownloading(fileId: Int): Boolean = engine?.isDownloading(fileId) ?: false

    /** The chats with a fetch going, for the cache trim to leave alone. */
    fun protectedChats(): LongArray = engine?.protectedChats() ?: LongArray(0)

    fun isKept(id: TrackId): Boolean = store.isKept(id)

    /** The kept file of [id] to play, or null when it is not kept (or its file is gone). */
    fun pathOf(id: TrackId): File? = store.pathOf(id)

    fun freeBytes(): Long = store.folder?.let { runCatching { it.usableSpace }.getOrDefault(0L) } ?: 0L

    /** What keeping [tracks] would do now: how many are new, what fits in the free space. */
    fun plan(tracks: List<Track>): OfflinePlanner.Plan {
        val kept = store.kept.value
        return OfflinePlanner.plan(
            tracks = tracks,
            kept = kept.keys,
            queued = pending.value,
            keptUniques = kept.values.mapTo(HashSet()) { it.uniqueId },
            freeBytes = freeBytes(),
        )
    }

    /** Queues the tracks of a [plan] (the ones that fit). */
    fun keep(plan: OfflinePlanner.Plan) {
        val running = engine
        if (running == null) {
            Logger.w(TAG, "keep before start: ignored")
            return
        }
        running.enqueue(plan.toKeep)
    }

    fun pause() = engine?.pause()

    fun resume() = engine?.resume()

    /** Stops keeping one track that waits or is being fetched. */
    fun cancel(id: TrackId) = engine?.cancel(id)

    /** Stops everything in the queue; what is kept stays. */
    fun cancelAll() = engine?.cancelAll()

    fun dismissSummary() = engine?.dismissSummary()

    /** Removes one kept track (its file and its entry). */
    fun remove(id: TrackId): Boolean = store.remove(id)

    /** Removes every kept track and stops the queue. Returns how many were kept. */
    suspend fun removeAll(): Int {
        engine?.cancelAll()
        awaitIdle { true }
        return store.removeAll()
    }

    /** A chat was left or removed: its queued tracks stop and its kept files and rows go. */
    suspend fun purgeChat(chatId: Long) {
        engine?.cancelChat(chatId)
        awaitIdle { it.chatId == chatId }
        store.removeChat(chatId)
    }

    /** Logging out or another account: everything goes. */
    suspend fun purgeAll() {
        engine?.cancelAll()
        awaitIdle { true }
        store.removeAll()
    }

    /** Waits (briefly) until nothing matching is fetching, so a file cannot be kept after its removal. */
    private suspend fun awaitIdle(match: (TrackId) -> Boolean) {
        val running = engine ?: return
        withTimeoutOrNull(15_000) { running.pending.first { ids -> ids.none(match) } }
    }

    fun setWifiOnly(on: Boolean) {
        wifi.value = on
        settings?.putBoolean(KEY_WIFI_ONLY, on)
    }

    /** Writes the index now (the app is going away). */
    fun flush() = store.flush()
}

/** [KeepCalls] over the TDLib session. */
internal object TdKeepCalls : KeepCalls {
    private const val SESSION_WAIT_MS = 60_000L

    /** Below playback (which asks at a higher priority), so a queue never starves what is playing. */
    private const val PRIORITY = 4

    private suspend fun client() = withTimeoutOrNull(SESSION_WAIT_MS) { Td.awaitAuthorizedSession().client }

    override suspend fun lookUp(id: TrackId): KeepLookup {
        val td = client() ?: return KeepLookup.Unavailable
        return when (val answer = td.getMessage(id.chatId, id.messageId)) {
            is TdlResult.Failure -> when {
                Failures.floodWaitSeconds(answer.message) != null || answer.code == 429 ->
                    KeepLookup.Flood(Failures.floodWaitSeconds(answer.message) ?: FLOOD_DEFAULT_SECONDS)
                answer.code == 404 || answer.message.contains("not found", ignoreCase = true) -> KeepLookup.Gone
                else -> KeepLookup.Failed(Failures.humanise(answer.message))
            }
            is TdlResult.Success -> {
                val message = answer.result
                // Telegram says whether this message may be saved (a protected chat says no).
                if (!message.canBeSaved) return KeepLookup.NotAllowed
                when (val mapped = runCatching { TrackMapper.fromMessage(message) }.getOrNull()) {
                    is Mapped.Audio -> KeepLookup.Found(mapped.track.fileId, mapped.track.sizeBytes)
                    else -> KeepLookup.Gone
                }
            }
        }
    }

    override suspend fun start(fileId: Int): KeepStart {
        val td = client() ?: return KeepStart.Failed(Failures.OFFLINE)
        return when (val answer = td.downloadFile(fileId, PRIORITY, 0, 0, false)) {
            is TdlResult.Success -> KeepStart.Started(toKeep(answer.result))
            is TdlResult.Failure -> {
                val seconds = Failures.floodWaitSeconds(answer.message)
                when {
                    seconds != null || answer.code == 429 -> KeepStart.Flood(seconds ?: FLOOD_DEFAULT_SECONDS)
                    else -> KeepStart.Failed(answer.message)
                }
            }
        }
    }

    override fun updates(fileId: Int): Flow<KeepFile> = flow {
        val td = client() ?: return@flow
        td.fileUpdates.filter { it.file.id == fileId }.map { toKeep(it.file) }.collect { emit(it) }
    }

    override suspend fun current(fileId: Int): KeepFile? {
        val td = client() ?: return null
        return (td.getFile(fileId) as? TdlResult.Success)?.result?.let(::toKeep)
    }

    override suspend fun cancel(fileId: Int) {
        val td = client() ?: return
        td.cancelDownloadFile(fileId, false)
    }

    override suspend fun forget(fileId: Int) {
        val td = client() ?: return
        td.deleteFile(fileId)
    }

    private fun toKeep(file: dev.g000sha256.tdl.dto.File) = KeepFile(
        id = file.id,
        path = file.local.path.orEmpty(),
        complete = file.local.isDownloadingCompleted,
        downloaded = file.local.downloadedSize,
        size = file.size.takeIf { it > 0 } ?: file.expectedSize.coerceAtLeast(0),
        canDownload = file.local.canBeDownloaded,
    )

    private const val FLOOD_DEFAULT_SECONDS = 30
}
