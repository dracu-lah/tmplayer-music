// A chat as a playlist follows the Android app's ChatScreen (app/.../ui/library/ChatScreen.kt).
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.ChatLoader
import com.tmplayer.music.data.ChatOrder
import com.tmplayer.music.data.ChatProblem
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.ui.common.Badge
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.ErrorPanel
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.HeldKeys
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.common.OnSelectAll
import com.tmplayer.music.desktop.ui.common.PlayingGlyph
import com.tmplayer.music.desktop.ui.theme.Pill
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * One chat as a playlist: its audio files, read from the index at once and from Telegram as the
 * list is scrolled ([ChatLoader]), oldest or newest first. Double click, or Play, plays from there;
 * the files of the chat go to the queue as they are, a file at a time (no versions folded).
 */
@Composable
fun ChatScreen(chatId: Long, title: String, actions: TrackActions, onGone: () -> Unit) {
    var order by remember(chatId) { mutableStateOf(Library.choices.chatOrder(chatId)) }
    val loader = remember(chatId, order) { Library.chatLoader(chatId, order) }
    androidx.compose.runtime.key(loader) {
        ChatPageBody(chatId, title, loader, actions, order, onOrder = {
            order = it
            Library.choices.setChatOrder(chatId, it)
        }, onGone = onGone)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatPageBody(
    chatId: Long,
    title: String,
    loader: ChatLoader,
    actions: TrackActions,
    order: ChatOrder,
    onOrder: (ChatOrder) -> Unit,
    onGone: () -> Unit,
) {
    val state by loader.state.collectAsState()
    val list = rememberLazyListState(loader.scrollIndex, loader.scrollOffset)
    val scope = rememberCoroutineScope()
    val changes by Library.changes.collectAsState()
    val mark = LocalPlayingMark.current
    var selection by remember(loader) { mutableStateOf(ListSelection<Long>()) }
    val rows = state.rows
    val ids = rows.map { it.messageId }
    LaunchedEffect(ids) { selection = selection.prune(ids) }
    OnSelectAll { selection = selection.selectAll(ids) }

    DisposableEffect(loader, list) {
        onDispose {
            loader.scrollIndex = list.firstVisibleItemIndex
            loader.scrollOffset = list.firstVisibleItemScrollOffset
        }
    }
    // First rows, then the next page as the list nears its end. Leaving the page or switching the
    // order cancels a page that is still loading.
    LaunchedEffect(loader) {
        loader.start()
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= loader.state.value.rows.size - NEAR_END }
            .collect {
                // A page of other documents adds no row and moves no scroll: go on while the end of
                // the list is on screen, until the chat ends, fails or a few pages bring nothing.
                var idle = 0
                while (idle < IDLE_PAGES) {
                    val before = loader.state.value
                    if (before.end || before.problem != null) break
                    loader.more()
                    val after = loader.state.value
                    if (after.rows.size == before.rows.size) idle++ else idle = 0
                    val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (last < after.rows.size - NEAR_END) break
                }
            }
    }
    // A tag read or a switch changed the index: the rows around the screen read again.
    LaunchedEffect(loader) {
        snapshotFlow { changes }.drop(1).collectLatest {
            delay(REFRESH_SETTLE_MS)
            val current = loader.state.value.rows
            val from = (list.firstVisibleItemIndex - REFRESH_AROUND).coerceAtLeast(0)
            loader.refresh(current.drop(from).take(REFRESH_AROUND * 3).map { it.messageId })
        }
    }
    // The rows on screen go to the front of the tag reader's queue once the scroll settles.
    LaunchedEffect(loader) {
        snapshotFlow { list.firstVisibleItemIndex to state.rows.size }.distinctUntilChanged().collectLatest { (first, _) ->
            delay(VISIBLE_SETTLE_MS)
            val shown = loader.state.value.rows.drop(first.coerceAtLeast(0)).take(VISIBLE_ROWS)
            val waiting = shown.filter { it.tagState == TagState.TelegramOnly }.map { it.id }
            if (waiting.isNotEmpty()) runCatching { Library.enricher.prefer(waiting) }
        }
    }
    LaunchedEffect(state.problem) {
        if (state.problem == ChatProblem.Removed) onGone()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = title,
            subtitle = when {
                rows.isNotEmpty() && state.end -> L.chatfirstChatEnd(rows.size)
                rows.isNotEmpty() -> L.librarySongs(rows.size)
                else -> null
            },
            actions = {
                Button(onClick = { actions.play(loader.shown(), 0) }, enabled = rows.isNotEmpty()) { Text(L.homePlayAll) }
                FilledTonalButton(onClick = { actions.shuffle(loader.shown()) }, enabled = rows.isNotEmpty()) { Text(L.homeShuffleAll) }
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = order == ChatOrder.OldestFirst, onClick = { onOrder(ChatOrder.OldestFirst) }, label = { Text(L.chatfirstOrderOldest) }, shape = Pill)
            FilterChip(selected = order == ChatOrder.NewestFirst, onClick = { onOrder(ChatOrder.NewestFirst) }, label = { Text(L.chatfirstOrderNewest) }, shape = Pill)
        }
        if (!selection.isEmpty) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L.desktopSelected(selection.count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val chosen = rows.filter { it.messageId in selection }
                    if (chosen.isNotEmpty()) actions.play(chosen, 0)
                }) { Text(L.desktopPlaySelected) }
                TextButton(onClick = { selection = selection.clear() }) { Text(L.desktopSelectionClear) }
            }
        }
        state.problem?.let { problem -> ProblemLine(problem, loader) { scope.launch { loader.more() } } }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(rows, key = { _, track -> track.messageId }) { index, track ->
                val picked = track.messageId in selection
                ContextMenuArea(items = {
                    val now = if (picked) selection else ListSelection(setOf(track.messageId), track.messageId).also { selection = it }
                    val chosen = rows.filter { it.messageId in now }.ifEmpty { listOf(track) }
                    listOf(ContextMenuItem(L.playerPlay) { actions.play(chosen, 0) })
                }) {
                    TrackRow(
                        track = track,
                        selected = picked,
                        mark = mark?.forTrack(track.id),
                        onClick = {
                            if (DoubleClicks.isDouble(track.messageId) && !HeldKeys.ctrl && !HeldKeys.shift) {
                                actions.play(rows, index)
                            } else {
                                selection = selection.click(track.messageId, ids, ctrl = HeldKeys.ctrl, shift = HeldKeys.shift)
                            }
                        },
                    )
                }
            }
            when {
                state.loading || !state.started -> item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        if (state.fromTelegram) Text(L.chatfirstChatLoading, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                rows.isEmpty() && state.end && state.problem == null -> item(key = "empty") { EmptyState(L.chatfirstChatEmpty) }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, selected: Boolean, mark: Boolean?, onClick: () -> Unit) {
    val title = track.title.ifBlank { track.fileName }
    val artist = ArtistDisplay.of(track.artist).ifBlank { L.libraryUnknownArtist }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            name = title,
            fileId = 0,
            size = 40.dp,
            coverPath = Library.coverFile(track.coverKey)?.path.orEmpty(),
            track = track.id.takeIf { track.thumbFileId != 0 },
            art = CoverArt.keyOf(track),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mark != null) PlayingGlyph(playing = mark, modifier = Modifier.padding(end = 6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mark != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (mark != null) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.format.lossless) Badge(track.format.label)
        Text(Format.duration(track.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun ProblemLine(problem: ChatProblem, loader: ChatLoader, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        when (problem) {
            ChatProblem.Offline -> Text(L.chatfirstChatOffline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ChatProblem.Waiting -> Text(L.chatfirstChatWaiting(problem.seconds), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ChatProblem.Failed -> ErrorPanel(problem.message, onRetry = {
                loader.retry()
                onMore()
            })
            ChatProblem.NotAvailable -> Text(L.chatfirstChatNotAvailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatProblem.Removed -> Text(L.chatfirstChatRemoved, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val NEAR_END = 12
private const val IDLE_PAGES = 3
private const val REFRESH_SETTLE_MS = 600L
private const val REFRESH_AROUND = 20
private const val VISIBLE_SETTLE_MS = 400L
private const val VISIBLE_ROWS = 40
