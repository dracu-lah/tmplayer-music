package com.tmplayer.music.data

import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.ScanCursor
import com.tmplayer.music.library.Track
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** The two searches a chat is read with: audio messages, and documents (where FLAC usually is). */
enum class ScanPass { Audio, Documents }

/** One page of a chat's search, as the scanner needs it. */
sealed interface PageResult {
    /**
     * [messageIds] are every message on the page, music or not, so the cursor moves past them all;
     * [items] are the ones the library keeps, already mapped. [nextFromMessageId] 0 means there is
     * nothing older.
     */
    data class Page(val messageIds: List<Long>, val items: List<Mapped>, val nextFromMessageId: Long) : PageResult
    data class FloodWait(val seconds: Int) : PageResult
    data class Failed(val message: String) : PageResult
}

/** Where the scanner keeps its progress and its finds: the index, behind this seam for the tests. */
interface ScanStore {
    suspend fun cursor(chatId: Long, pass: ScanPass): ScanCursor
    suspend fun saveCursor(chatId: Long, pass: ScanPass, cursor: ScanCursor)
    suspend fun addTracks(tracks: List<Track>)
    suspend fun addLyrics(files: List<LyricsFile>)
}

/** What the status chip shows. */
data class ScanStatus(
    val running: Boolean = false,
    val paused: Boolean = false,
    val chatsTotal: Int = 0,
    val chatsDone: Int = 0,
    /** Tracks found in this run. */
    val found: Int = 0,
    /** Seconds Telegram asked us to wait, while a page waits; 0 otherwise. */
    val waitingSeconds: Int = 0,
    /** Chats that could not be read, with the reason, so each says so and can be tried again. */
    val failed: Map<Long, String> = emptyMap(),
)

/**
 * Reads the chosen chats for audio, page by page, into the index.
 *
 * Every page is stored as it arrives, so the library fills while the scan runs, and its cursor is
 * saved with it, so a scan cut short (the app closed, the phone restarted) carries on where it
 * stopped. A chat already scanned is topped up from the newest end until a message it has seen, which
 * is usually one page. [PARALLEL_CHATS] chats are read at a time; a flood wait is waited out by the
 * page that got it while the other chats go on; a page that keeps failing gives up after
 * [MAX_TRIES] and the chat is marked failed with its reason. Nothing runs once the queue is empty.
 */
class LibraryScanner(
    private val store: ScanStore,
    private val fetchPage: suspend (chatId: Long, pass: ScanPass, fromMessageId: Long) -> PageResult,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val paused = MutableStateFlow(false)
    private val lock = Mutex()
    private val queued = LinkedHashSet<Long>()
    private val cancelled = HashSet<Long>()
    private var worker: Job? = null

    /** Adds [chatIds] to the scan. A chat already queued or running is not added twice. */
    fun scan(chatIds: Collection<Long>) {
        scope.launch {
            lock.withLock {
                val fresh = chatIds.filter { queued.add(it) }
                cancelled.removeAll(fresh.toSet())
                if (fresh.isEmpty() && worker?.isActive == true) return@withLock
                _status.update { it.copy(chatsTotal = it.chatsTotal + fresh.size, running = true) }
                if (worker?.isActive != true) worker = scope.launch { drain() }
            }
        }
    }

    /** Stops [chatId]'s scan at its next page, for a chat the user just removed. */
    fun forget(chatId: Long) {
        scope.launch { lock.withLock { cancelled.add(chatId) } }
    }

    fun pause() {
        paused.value = true
        _status.update { it.copy(paused = true) }
    }

    fun resume() {
        paused.value = false
        _status.update { it.copy(paused = false) }
    }

    /** Waits for the current run to finish; for the tests. */
    suspend fun idle() {
        status.first { !it.running }
    }

    private suspend fun drain() {
        val gate = Semaphore(PARALLEL_CHATS)
        while (true) {
            // Checked and cleared under the lock that scan() takes, so a chat queued as the last
            // batch ends either joins this loop or starts a new worker, never neither.
            val batch = lock.withLock {
                if (queued.isEmpty()) {
                    _status.update { it.copy(running = false, waitingSeconds = 0, chatsTotal = 0, chatsDone = 0) }
                    worker = null
                    return
                }
                queued.toList()
            }
            coroutineScope {
                batch.map { chatId ->
                    async {
                        gate.withPermit {
                            val failure = try {
                                scanChat(chatId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.w(TAG, "scanning $chatId failed", e)
                                Failures.humanise(e)
                            }
                            lock.withLock { queued.remove(chatId) }
                            _status.update { s ->
                                s.copy(
                                    chatsDone = s.chatsDone + 1,
                                    failed = if (failure == null) s.failed - chatId else s.failed + (chatId to failure),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** Both passes of one chat. Null when it was read, the reason when it could not be. */
    private suspend fun scanChat(chatId: Long): String? {
        for (pass in ScanPass.entries) {
            val reason = scanPass(chatId, pass)
            if (reason != null) return reason
        }
        return null
    }

    private suspend fun scanPass(chatId: Long, pass: ScanPass): String? {
        var cursor = store.cursor(chatId, pass)
        // Already read to the end: only what was posted since needs reading, from the newest end.
        val topUp = cursor.done
        var from = if (topUp) 0L else cursor.fromMessageId
        var newest = cursor.newestMessageId
        var pages = 0
        while (pages < MAX_PAGES) {
            paused.first { !it }
            if (lock.withLock { chatId in cancelled }) return null
            val page = when (val result = fetchWithRetries(chatId, pass, from)) {
                is PageResult.Page -> result
                is PageResult.Failed -> return result.message
                is PageResult.FloodWait -> return Failures.DEFAULT
            }
            pages++
            val ids = page.messageIds
            ids.maxOrNull()?.let { newest = maxOf(newest, it) }
            val fresh = if (topUp) page.items.filter { itemId(it) > cursor.newestMessageId } else page.items
            store(fresh)
            val reachedSeen = topUp && ids.any { it <= cursor.newestMessageId }
            // Telegram can answer a page with nothing but a cursor further down (a supergroup or channel
            // whose page held only messages TDLib leaves out): that is not the end, the next page goes
            // on from there. The cursor only ever moves down, so this cannot loop.
            val next = page.nextFromMessageId
            val movedOn = next != 0L && (from == 0L || next < from)
            val end = next == 0L || (ids.isEmpty() && !movedOn) || reachedSeen
            cursor = if (topUp) {
                cursor.copy(newestMessageId = newest)
            } else {
                ScanCursor(fromMessageId = page.nextFromMessageId, done = end, newestMessageId = newest)
            }
            store.saveCursor(chatId, pass, cursor)
            if (end) return null
            from = page.nextFromMessageId
        }
        return null
    }

    /**
     * One page, waiting out flood waits and retrying failures a bounded number of times. A
     * [PageResult.Failed] it returns carries the sentence to show for the chat.
     */
    private suspend fun fetchWithRetries(chatId: Long, pass: ScanPass, from: Long): PageResult {
        var failures = 0
        var waits = 0
        while (true) {
            when (val result = fetchPage(chatId, pass, from)) {
                is PageResult.Page -> return result
                is PageResult.FloodWait -> {
                    if (++waits > MAX_WAITS || result.seconds > MAX_WAIT_S) {
                        return PageResult.Failed(Failures.humanise("FLOOD_WAIT_${result.seconds}"))
                    }
                    _status.update { it.copy(waitingSeconds = result.seconds) }
                    delay(result.seconds * 1000L)
                    _status.update { it.copy(waitingSeconds = 0) }
                }
                is PageResult.Failed -> {
                    if (++failures >= MAX_TRIES) return PageResult.Failed(Failures.humanise(result.message))
                    delay(retryDelayMs shl (failures - 1))
                }
            }
        }
    }

    private fun itemId(item: Mapped): Long = when (item) {
        is Mapped.Audio -> item.track.messageId
        is Mapped.Lyrics -> item.file.messageId
    }

    private suspend fun store(items: List<Mapped>) {
        if (items.isEmpty()) return
        val tracks = items.mapNotNull { (it as? Mapped.Audio)?.track }
        val lyrics = items.mapNotNull { (it as? Mapped.Lyrics)?.file }
        if (tracks.isNotEmpty()) store.addTracks(tracks)
        if (lyrics.isNotEmpty()) store.addLyrics(lyrics)
        _status.update { it.copy(found = it.found + tracks.size) }
    }

    companion object {
        private const val TAG = "LibraryScanner"
        const val PAGE_SIZE = 100
        const val PARALLEL_CHATS = 2
        const val MAX_TRIES = 4
        const val MAX_WAITS = 5
        const val MAX_WAIT_S = 600

        /** 20,000 files at 100 a page is 200 pages; this bounds a chat that never ends. */
        const val MAX_PAGES = 2_000
        private const val RETRY_DELAY_MS = 2_000L
    }
}
