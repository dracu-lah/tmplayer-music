package com.tmplayer.music.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
) {
    val items = mutableStateListOf<T>()
    var loaded by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val lock = Mutex()

    suspend fun reload() = guarded {
        val count = items.size.coerceIn(PAGE, MAX_RELOAD)
        val rows = load(null, count)
        items.clear()
        items.addAll(rows)
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
        }
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
        const val PAGE = 200
        const val MAX_RELOAD = 1_000
        const val REFRESH_MS = 1_500L
    }
}

/** Remembers a [Pager] for [key], loads its first page, follows index changes and the scroll. */
@Composable
fun <C, T> rememberPager(
    key: Any,
    state: LazyListState,
    load: suspend (after: C?, limit: Int) -> List<T>,
    cursorOf: (T) -> C,
): Pager<C, T> {
    val pager = remember(key) { Pager(load, cursorOf) }
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
    return pager
}
