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
import kotlinx.coroutines.withTimeoutOrNull
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
 * One chat as a playlist in message order (`plan/MVP-REVISION.md` decision h), loaded page by page
 * and never whole:
 *
 * - A chat whose scan has finished is read from the index alone, by message id (keyset), so its first
 *   rows show at once and a page deep in 20,000 tracks costs what the first does.
 * - Any other chat is read from Telegram through a [ChatFeed] (audio and document searches merged by
 *   message id). What the index already holds of it shows at once (newest first, where the scan has
 *   been), or after [FIRST_BUDGET_MS] without an answer (oldest first); Telegram's rows then fill in
 *   around it, each replaced by the index's copy when that has its tags read. Rows the index lacks are
 *   added to it, so they play, get their tags and show in the library.
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
    private val firstBudgetMs: Long = FIRST_BUDGET_MS,
) {
    private val _state = MutableStateFlow(ChatPageState())
    val state: StateFlow<ChatPageState> = _state.asStateFlow()

    private val lock = Mutex()
    private val rows = TreeMap<Long, Track>()
    private var feed: ChatFeed? = null
    private var indexAfter: Long? = null
    private var indexEnd = false
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
                val first = withTimeoutOrNull(firstBudgetMs) { feedSteps() }
                if (first == null) {
                    // Telegram is slow: what the index holds shows now, Telegram's rows come in around it.
                    if (oldestFirst) addIndexPreview()
                    feedSteps()
                }
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
        val page = store.indexPage(chatId, oldestFirst, null, PAGE)
        for (t in page) rows.putIfAbsent(t.messageId, t)
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
                        val known = runCatching { store.byIds(chatId, tracks.map { it.messageId }) }.getOrDefault(emptyMap())
                        val missing = tracks.filter { it.messageId !in known }
                        if (missing.isNotEmpty()) runCatching { store.addMissing(missing) }
                        for (t in tracks) rows[t.messageId] = known[t.messageId] ?: t
                        added += tracks.size
                    }
                }
            }
        }
        publish(loading = false, end = source.ended || rows.size >= MAX_ROWS)
        return true
    }

    private fun publish(loading: Boolean = _state.value.loading, end: Boolean = _state.value.end) {
        val mask = hiddenMask()
        shownMask = mask
        val ordered = if (oldestFirst) rows.values else rows.descendingMap().values
        val list = ordered.filter { !ClipRules.hidden(it, mask) }
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

        /** The first rows from Telegram are waited for this long before the index's show (oldest first). */
        const val FIRST_BUDGET_MS = 3_000L

        /** Pages read per [more] at most, so a chat of mostly other documents cannot hold the list. */
        const val MAX_STEPS = 4

        /** New rows a [more] tries to bring. */
        const val MIN_NEW = 30

        /** Rows one page keeps at most; past it the list ends (20,000 is the large case designed for). */
        const val MAX_ROWS = 30_000
    }
}
