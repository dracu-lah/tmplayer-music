package com.tmplayer.music.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
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

    /** Rows the first [reload] reads at least: the window saved before the list was left. */
    private var restoreCount = 0

    /**
     * Before the first load: read the window that was shown when the list was left ([from] rows
     * down, [count] rows), so the saved scroll position lands on the same rows.
     */
    fun restore(from: Int, count: Int) {
        if (loaded) return
        if (loadAt != null && loadBefore != null) start = from.coerceAtLeast(0)
        restoreCount = count.coerceIn(0, MAX_RELOAD)
    }

    suspend fun reload() = guarded {
        val count = maxOf(items.size, restoreCount).coerceIn(PAGE, MAX_RELOAD)
        restoreCount = 0
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

/**
 * What the pager needs from a lazy list or grid: where it is, and a way back there. Read inside
 * snapshotFlow only, so following the scroll costs no recomposition.
 */
@Stable
interface PagedScroll {
    val firstVisible: Int
    val firstOffset: Int
    val lastVisible: Int
    suspend fun scrollTo(index: Int, offset: Int)
}

private class ListScroll(private val state: LazyListState) : PagedScroll {
    override val firstVisible get() = state.firstVisibleItemIndex
    override val firstOffset get() = state.firstVisibleItemScrollOffset
    override val lastVisible get() = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    override suspend fun scrollTo(index: Int, offset: Int) = state.scrollToItem(index, offset)
}

private class GridScroll(private val state: LazyGridState) : PagedScroll {
    override val firstVisible get() = state.firstVisibleItemIndex
    override val firstOffset get() = state.firstVisibleItemScrollOffset
    override val lastVisible get() = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    override suspend fun scrollTo(index: Int, offset: Int) = state.scrollToItem(index, offset)
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
): Pager<C, T> = rememberPagerFor(key, remember(state) { ListScroll(state) }, load, cursorOf, loadAt, loadBefore)

/** The same for a grid of covers. */
@Composable
fun <C, T> rememberGridPager(
    key: Any,
    state: LazyGridState,
    load: suspend (after: C?, limit: Int) -> List<T>,
    cursorOf: (T) -> C,
): Pager<C, T> = rememberPagerFor(key, remember(state) { GridScroll(state) }, load, cursorOf, null, null)

/**
 * The pager's window and the list's place in it are saved (a tab left and come back to, a rotation,
 * process death): the first load reads the same window again, then the list goes back to the row it
 * showed. The list's own saved position cannot do it alone, as it is clamped to the header row
 * before the rows have loaded.
 */
@Composable
private fun <C, T> rememberPagerFor(
    key: Any,
    scroll: PagedScroll,
    load: suspend (after: C?, limit: Int) -> List<T>,
    cursorOf: (T) -> C,
    loadAt: (suspend (offset: Int, limit: Int) -> List<T>)?,
    loadBefore: (suspend (before: C, limit: Int) -> List<T>)?,
): Pager<C, T> {
    val savedStart = rememberSaveable { mutableIntStateOf(0) }
    val savedCount = rememberSaveable { mutableIntStateOf(0) }
    // Read in composition, before the first layout clamps the restored position to the header row;
    // not observed, so a scroll never recomposes the list's parent.
    val pending = remember {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { intArrayOf(scroll.firstVisible, scroll.firstOffset) }
    }
    val first = remember { booleanArrayOf(true) }
    val pager = remember(key) { Pager(load, cursorOf, loadAt, loadBefore) }
    LaunchedEffect(pager) {
        val restoring = first[0]
        first[0] = false
        if (restoring) pager.restore(savedStart.intValue, savedCount.intValue)
        pager.reload()
        if (restoring && pending[0] > 0) {
            try {
                scroll.scrollTo(pending[0], pending[1])
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The list stays at its top: nothing lost but the place.
            }
        }
        // conflate, not sample: sample keeps a timer ticking while nothing changes.
        Library.changes.drop(1).conflate().collect {
            pager.reload()
            delay(Pager.REFRESH_MS)
        }
    }
    LaunchedEffect(pager) {
        // Changes only when a page is read or let go, never per frame.
        snapshotFlow { pager.start to pager.items.size }.collect { (start, count) ->
            savedStart.intValue = start
            savedCount.intValue = count
        }
    }
    LaunchedEffect(pager, scroll) {
        snapshotFlow { scroll.lastVisible }
            .distinctUntilChanged()
            .filter { it >= pager.items.size - Pager.PAGE / 4 }
            .collect { pager.more() }
    }
    if (loadBefore != null) {
        // A window that starts part way down (after a letter jump) reads the rows above it as the
        // list nears its top; nothing runs while the window starts at row 0.
        LaunchedEffect(pager, scroll) {
            snapshotFlow { scroll.firstVisible }
                .distinctUntilChanged()
                .filter { it <= Pager.PAGE / 4 && pager.start > 0 }
                .collect { pager.less() }
        }
    }
    return pager
}
