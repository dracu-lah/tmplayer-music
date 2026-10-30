package com.tmplayer.music.data

import com.tmplayer.music.library.ClipRules
import com.tmplayer.music.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap

/** What the chat page reads the index through; [Library] answers it, the tests fake it. */
interface ChatStore {
    /** Null when the chat is no longer chosen; else whether both searches of its scan reached the end. */
    suspend fun scanDone(chatId: Long): Boolean?

    /** One page of the chat's indexed files in message order after message [after] (null: from the start). */
    suspend fun indexPage(chatId: Long, oldestFirst: Boolean, after: Long?, limit: Int): List<Track>

    /** The chat's indexed files among [messageIds], by message id. */
    suspend fun byIds(chatId: Long, messageIds: Collection<Long>): Map<Long, Track>

    /** Stores files read from Telegram that the index does not hold yet, so they play and get their tags. */
    suspend fun addMissing(tracks: List<Track>)

    /**
     * The message id down to which the chat's scan has read both searches from the newest end (every
     * file at or above it is in the index), or null when one of them has not read a page yet.
     */
    suspend fun scannedDownTo(chatId: Long): Long? = null
}

/** Why a chat page cannot show more right now. */
sealed interface ChatProblem {
    /** Telegram is not connected; the page waits and goes on by itself. */
    data object Offline : ChatProblem

    /** Telegram asked to wait this long; the page goes on by itself after. */
    data class Waiting(val seconds: Int) : ChatProblem

    /** A failure with the sentence to show, and Try again. */
    data class Failed(val message: String) : ChatProblem

    /** The chat is gone or closed to the user (left, deleted, banned): offer to clean it up. */
    data object NotAvailable : ChatProblem

    /** The chat was removed from the library while its page was open. */
    data object Removed : ChatProblem
}

/** What a chat page shows. [rows] are in the page's order, hidden clips left out. */
data class ChatPageState(
    val rows: List<Track> = emptyList(),
    val started: Boolean = false,
    val loading: Boolean = false,
    val end: Boolean = false,
    /** True when the rows come from Telegram page by page (the chat's scan has not finished). */
    val fromTelegram: Boolean = false,
    val problem: ChatProblem? = null,
)

/**
 * One chat as a playlist in message order, loaded page by page
 * and never whole:
 *
 * - A chat whose scan has finished is read from the index alone, by message id (keyset), so its first
 *   rows show at once and a page deep in 20,000 tracks costs what the first does.
 * - Any other chat is read from Telegram through a [ChatFeed] (audio and document searches merged by
 *   message id). Newest first, what the index holds above the scan's mark ([ChatStore.scannedDownTo])
 *   shows at once; oldest first nothing does, as the scan starts at the newest end and the index never
 *   holds the start of an unscanned chat. Each of Telegram's rows is replaced by the index's copy when
 *   that has its tags read. Rows the index lacks are added to it, so they play, get their tags and
 *   show in the library.
 * - Only rows up to the mark where the gathered rows are known to be whole are shown ([ChatFeed.complete],
 *   or the index's mark newest first), so the rows shown are always the start of the chat's list in
 *   the page's order: a later page only adds rows after the last one, never above the first or in
 *   between. Rows past the mark wait until both searches pass them.
 *
 * Offline it says so and waits for the connection; a flood wait is waited out by the feed; a chat
 * that is gone says so (clean up is offered by the screen); a chat removed while open says so. Rows
 * kept are bounded by [MAX_ROWS]. Short clips and voice files follow [hiddenMask], as the lists do.
 * Not thread safe beyond its own lock: one screen drives it.
 */
class ChatLoader(
    val chatId: Long,
    val order: ChatOrder,
    private val store: ChatStore,
    private val feedOf: (ChatOrder, onWait: (Int) -> Unit) -> ChatFeed,
    private val online: StateFlow<Boolean>,
    private val hiddenMask: () -> Int = { 0 },
) {
    private val _state = MutableStateFlow(ChatPageState())
    val state: StateFlow<ChatPageState> = _state.asStateFlow()

    private val lock = Mutex()
    private val rows = TreeMap<Long, Track>()
    private var feed: ChatFeed? = null
    private var indexAfter: Long? = null
    private var indexEnd = false

    /** Newest first: the message id down to which the index's preview is whole, null when not known. */
    private var previewMark: Long? = null
    private var shownMask = -1

    /** Where the list was scrolled to, so coming back to the page lands there again. */
    var scrollIndex = 0
    var scrollOffset = 0

    private val oldestFirst get() = order == ChatOrder.OldestFirst

    /** Shows the first rows; does nothing once started. Returns when the first page is in or failed. */
    suspend fun start() {
        if (_state.value.started) return
        lock.withLock {
            if (_state.value.started) return
            guard {
                val done = store.scanDone(chatId)
                if (done == null) {
                    _state.update { it.copy(started = true, problem = ChatProblem.Removed, end = true) }
                    return@guard
                }
                if (done) {
                    _state.update { it.copy(started = true, fromTelegram = false) }
                    indexStep()
                    return@guard
                }
                feed = feedOf(order) { seconds ->
                    _state.update { it.copy(problem = if (seconds > 0) ChatProblem.Waiting(seconds) else null) }
                }
                _state.update { it.copy(fromTelegram = true, loading = true) }
                // Newest first, the index holds the top of the chat already (the scan starts there).
                if (!oldestFirst) addIndexPreview()
                _state.update { it.copy(started = true) }
                feedSteps()
            }
        }
    }

    /** The next page, as the list nears its end. */
    suspend fun more() {
        val now = _state.value
        if (!now.started || now.end || now.problem == ChatProblem.Removed || now.problem == ChatProblem.NotAvailable) return
        lock.withLock {
            guard { if (feed == null) indexStep() else feedSteps() }
        }
    }

    /** Clears a failure so [more] tries again. */
    fun retry() {
        _state.update { if (it.problem is ChatProblem.Failed) it.copy(problem = null) else it }
    }

    /**
     * Reads [messageIds] (the rows around the screen) again from the index, after its tags were read
     * or a switch changed, and checks the chat is still chosen.
     */
    suspend fun refresh(messageIds: Collection<Long>) = lock.withLock {
        guard {
            if (store.scanDone(chatId) == null) {
                _state.update { it.copy(problem = ChatProblem.Removed, end = true) }
                return@guard
            }
            val fresh = store.byIds(chatId, messageIds.filter { it in rows })
            var changed = false
            for ((id, track) in fresh) if (rows[id] != track) {
                rows[id] = track
                changed = true
            }
            if (changed || shownMask != hiddenMask()) publish()
        }
    }

    /** The rows as shown, for Play and Shuffle. */
    fun shown(): List<Track> = _state.value.rows

    private suspend fun addIndexPreview() {
        val scanned = store.scannedDownTo(chatId)
        val page = store.indexPage(chatId, false, null, PAGE)
        for (t in page) rows.putIfAbsent(t.messageId, t)
        // A full page may stop above the scan's mark: the preview is whole down to its last row then.
        val last = page.lastOrNull()?.messageId
        previewMark = when {
            scanned == null -> null
            page.size >= PAGE && last != null -> maxOf(scanned, last)
            else -> scanned
        }
        publish()
    }

    private suspend fun indexStep() {
        if (indexEnd) return
        _state.update { it.copy(loading = true) }
        val page = store.indexPage(chatId, oldestFirst, indexAfter, PAGE)
        page.lastOrNull()?.let { indexAfter = it.messageId }
        for (t in page) if (rows.size < MAX_ROWS) rows[t.messageId] = t
        indexEnd = page.size < PAGE || rows.size >= MAX_ROWS
        publish(loading = false, end = indexEnd)
    }

    /**
     * Steps the feed until a few new rows are in, it ends, or [MAX_STEPS] pages were read (a chat of
     * mostly non-audio documents moves the cursor without adding rows; the next scroll goes on).
     */
    private suspend fun feedSteps(): Boolean {
        val source = feed ?: return true
        val visibleBefore = visibleCount()
        var added = 0
        var steps = 0
        while (steps < MAX_STEPS && added < MIN_NEW && rows.size < MAX_ROWS) {
            if (!online.value) {
                _state.update { it.copy(problem = ChatProblem.Offline) }
                online.first { it }
                _state.update { it.copy(problem = null) }
            }
            steps++
            when (val step = source.next()) {
                is FeedStep.End -> {
                    publish(loading = false, end = true)
                    return true
                }
                is FeedStep.Failed -> {
                    _state.update {
                        it.copy(loading = false, problem = if (step.notAvailable) ChatProblem.NotAvailable else ChatProblem.Failed(step.message))
                    }
                    return true
                }
                is FeedStep.Added -> {
                    val tracks = step.tracks
                    if (tracks.isNotEmpty()) {
                        // Kept before anything suspends: leaving the page now must not lose rows the
                        // feed has already counted as seen.
                        for (t in tracks) rows.putIfAbsent(t.messageId, t)
                        val known = try {
                            store.byIds(chatId, tracks.map { it.messageId })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        val missing = tracks.filter { it.messageId !in known }
                        if (missing.isNotEmpty()) {
                            try {
                                store.addMissing(missing)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // They still show and play; the scan stores them later.
                            }
                        }
                        for ((id, t) in known) if (id in rows) rows[id] = t
                    }
                    // Rows past the whole mark do not count: they wait for the other search.
                    added = visibleCount() - visibleBefore
                }
            }
        }
        publish(loading = false, end = source.ended || rows.size >= MAX_ROWS)
        return true
    }

    /**
     * The rows that may show, in the page's order: all of them read from the index alone, else those
     * up to the mark where the gathered rows are whole (none before it is known).
     */
    private fun visibleRows(): Collection<Track> {
        val source = feed ?: return if (oldestFirst) rows.values else rows.descendingMap().values
        val marks = listOfNotNull(source.complete, previewMark.takeIf { !oldestFirst })
        if (marks.isEmpty()) return emptyList()
        return if (oldestFirst) {
            rows.headMap(marks.max(), true).values
        } else {
            rows.tailMap(marks.min(), true).descendingMap().values
        }
    }

    private fun visibleCount(): Int = visibleRows().size

    private fun publish(loading: Boolean = _state.value.loading, end: Boolean = _state.value.end) {
        val mask = hiddenMask()
        shownMask = mask
        val list = visibleRows().filter { !ClipRules.hidden(it, mask) }
        _state.update { it.copy(rows = list, loading = loading, end = end) }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(started = true, loading = false, problem = ChatProblem.Failed(Failures.humanise(e))) }
        }
    }

    companion object {
        const val PAGE = 100

        /** Pages read per [more] at most, so a chat of mostly other documents cannot hold the list. */
        const val MAX_STEPS = 4

        /** New rows a [more] tries to bring. */
        const val MIN_NEW = 30

        /** Rows one page keeps at most; past it the list ends (20,000 is the large case designed for). */
        const val MAX_ROWS = 30_000
    }
}
