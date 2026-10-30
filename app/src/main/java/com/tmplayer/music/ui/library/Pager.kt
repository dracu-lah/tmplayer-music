package com.tmplayer.music.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A list read from the index a page at a time with a keyset cursor, never all rows at once. The
 * next page is asked for as the list nears its end; when the index changes (the scan adds tracks),
 * the rows already shown are read again, at most every [REFRESH_MS], so a running scan fills the
 * list without reloading it on every page Telegram sends. Nothing runs while the list is idle.
 */
class Pager<C, T>(
    private val load: suspend (after: C?, limit: Int) -> List<T>,
    private val cursorOf: (T) -> C,
    /** Rows from an offset, for a jump far down the list; null when the list has no windows. */
    private val loadAt: (suspend (offset: Int, limit: Int) -> List<T>)? = null,
    /** Rows just before a cursor, nearest last, for scrolling up from a window. */
    private val loadBefore: (suspend (before: C, limit: Int) -> List<T>)? = null,
) {
    val items = mutableStateListOf<T>()
    var loaded by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * The list position of [items]' first row. 0 until a jump ([jumpTo]) reads a window part way down
     * the list; scrolling up from there reads the rows before it ([less]) until it is 0 again.
     */
    var start by mutableIntStateOf(0)
        private set
    private val lock = Mutex()

    suspend fun reload() = guarded {
        val count = items.size.coerceIn(PAGE, MAX_RELOAD)
        val at = loadAt
        val rows = if (start > 0 && at != null) at(start, count) else load(null, count)
        // Nothing moved on screen: leave the list alone instead of rebuilding every row.
        if (rows != items.toList()) {
            items.clear()
            items.addAll(rows)
        }
        end = rows.size < count
        loaded = true
    }

    suspend fun more() {
        if (end || !loaded) return
        guarded {
            val last = items.lastOrNull() ?: return@guarded
            val rows = load(cursorOf(last), PAGE)
            items.addAll(rows)
            end = rows.size < PAGE
            // Far down a long list: let the rows far above go, they are read again on the way up.
            val drop = PagerWindow.dropFromStart(items.size, canReadBack = loadBefore != null)
            if (drop > 0) {
                items.removeRange(0, drop)
                start += drop
            }
        }
    }

    /** Reads the rows before the window's first one, when the window starts part way down. */
    suspend fun less() {
        val before = loadBefore ?: return
        if (start <= 0 || !loaded) return
        guarded {
            val first = items.firstOrNull() ?: return@guarded
            val rows = before(cursorOf(first), PAGE)
            if (rows.isEmpty()) {
                start = 0
                return@guarded
            }
            items.addAll(0, rows)
            start = (start - rows.size).coerceAtLeast(0)
            if (rows.size < PAGE) start = 0
            val drop = PagerWindow.dropFromEnd(items.size)
            if (drop > 0) {
                items.removeRange(items.size - drop, items.size)
                end = false
            }
        }
    }

    /**
     * Makes list position [position] one of [items] and returns its index there, for the A to Z bar.
     * A position already read is not read again; a position outside the window replaces the window
     * with a page around it ([PagerWindow.windowStart]), so a jump to row 90,000 reads one page. Without
     * [loadAt] the pages up to the position are read, as before.
     */
    suspend fun jumpTo(position: Int): Int {
        // A window that could not be scrolled back up from would hide the rows above it.
        val at = loadAt.takeIf { loadBefore != null }
        if (position >= start && position < start + items.size) return position - start
        if (at == null) {
            while (items.size <= position && !end && error == null) {
                val before = items.size
                more()
                if (items.size == before) break
            }
            return (position - start).coerceIn(0, maxOf(0, items.size - 1))
        }
        guarded {
            val from = PagerWindow.windowStart(position, canReadBack = loadBefore != null)
            val rows = at(from, PAGE)
            // Past the end (the list shrank since the bar was read): stay where the list is.
            if (rows.isEmpty() && from > 0) return@guarded
            items.clear()
            items.addAll(rows)
            start = from
            end = rows.size < PAGE
            loaded = true
        }
        return (position - start).coerceIn(0, maxOf(0, items.size - 1))
    }

    private suspend fun guarded(block: suspend () -> Unit) = lock.withLock {
        try {
            block()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
            loaded = true
        }
    }

    companion object {
        const val PAGE = PagerWindow.PAGE
        const val MAX_RELOAD = PagerWindow.MAX_WINDOW
        const val REFRESH_MS = 1_500L
    }
}

/**
 * The window arithmetic of [Pager], kept pure for tests: a list that can read backwards keeps at
 * most [MAX_WINDOW] rows, and a jump opens a window a little above its target so the rows just
 * before it are already there.
 */
object PagerWindow {
    const val PAGE = 200
    const val MAX_WINDOW = 1_000

    /** Rows to let go at the top after a page was added at the bottom. */
    fun dropFromStart(size: Int, canReadBack: Boolean): Int = if (canReadBack && size > MAX_WINDOW) size - MAX_WINDOW else 0

    /** Rows to let go at the bottom after a page was added at the top. */
    fun dropFromEnd(size: Int): Int = if (size > MAX_WINDOW) size - MAX_WINDOW else 0

    /** Where a window opened for [position] starts. */
    fun windowStart(position: Int, canReadBack: Boolean): Int = if (canReadBack) (position - PAGE / 4).coerceAtLeast(0) else 0
}

/** Remembers a [Pager] for [key], loads its first page, follows index changes and the scroll. */
@Composable
fun <C, T> rememberPager(
    key: Any,
    state: LazyListState,
    load: suspend (after: C?, limit: Int) -> List<T>,
    cursorOf: (T) -> C,
    loadAt: (suspend (offset: Int, limit: Int) -> List<T>)? = null,
    loadBefore: (suspend (before: C, limit: Int) -> List<T>)? = null,
): Pager<C, T> {
    val pager = remember(key) { Pager(load, cursorOf, loadAt, loadBefore) }
    LaunchedEffect(pager) {
        pager.reload()
        // conflate, not sample: sample keeps a timer ticking while nothing changes.
        Library.changes.drop(1).conflate().collect {
            pager.reload()
            delay(Pager.REFRESH_MS)
        }
    }
    LaunchedEffect(pager, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= pager.items.size - Pager.PAGE / 4 }
            .collect { pager.more() }
    }
    if (loadBefore != null) {
        // A window that starts part way down (after a letter jump) reads the rows above it as the
        // list nears its top; nothing runs while the window starts at row 0.
        LaunchedEffect(pager, state) {
            snapshotFlow { state.firstVisibleItemIndex }
                .distinctUntilChanged()
                .filter { it <= Pager.PAGE / 4 && pager.start > 0 }
                .collect { pager.less() }
        }
    }
    return pager
}
