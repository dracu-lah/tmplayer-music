package com.tmplayer.music.desktop.ui.player

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.ui.common.BackHandler
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.DragReorder
import com.tmplayer.music.desktop.ui.common.dragGrip
import com.tmplayer.music.desktop.ui.common.draggedRow
import com.tmplayer.music.desktop.ui.common.moveWithKeys
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.common.PlayingGlyph
import com.tmplayer.music.i18n.L

/** The queue panel's width: wide enough for a title and an artist beside the cover. */
val QUEUE_PANEL_WIDTH = 380.dp

private val ROW_HEIGHT = 56.dp

/**
 * The queue as a panel over the right edge of the page: the playing track first, then what comes
 * next in play order, with the rows to skip to, move and remove, and Clear (after a question: it
 * cannot be undone). Escape closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueuePanel(player: PlayerController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ui by player.ui.collectAsState()
    val queue = ui.queue
    val state = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }
    val drag = remember { DragReorder() }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { ROW_HEIGHT.toPx() }
    BackHandler(onBack = onClose)
    // Opens on the playing track, not the top of a long queue.
    LaunchedEffect(Unit) { runCatching { state.scrollToItem(queue.current.coerceAtLeast(0)) } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = modifier.width(QUEUE_PANEL_WIDTH).fillMaxHeight(),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.playerQueue, style = MaterialTheme.typography.titleLarge)
                    Text(L.nowplayingQueueCount(queue.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmClear = true }, enabled = !queue.isEmpty) { Text(L.queueClear) }
                IconButton(onClick = onClose) { Icon(MusicIcons.Close, contentDescription = L.playerClose) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (queue.isEmpty) {
                EmptyState(L.desktopQueueEmpty)
            } else {
                LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(queue.entries, key = { _, entry -> entry.uid }) { index, entry ->
                        val current = index == queue.current
                        ContextMenuArea(items = {
                            buildList {
                                add(ContextMenuItem(L.playerPlay) { player.skipTo(index) })
                                if (index > 0) add(ContextMenuItem(L.playlistMoveUp) { player.move(index, index - 1) })
                                if (index < queue.size - 1) add(ContextMenuItem(L.playlistMoveDown) { player.move(index, index + 1) })
                                add(ContextMenuItem(L.queueRemove) { player.remove(index) })
                            }
                        }) {
                            Row(
                                Modifier
                                    .draggedRow(drag, index)
                                    .moveWithKeys(index, queue.size) { from, to -> player.move(from, to) }
                                    .fillMaxWidth()
                                    .height(ROW_HEIGHT)
                                    .padding(horizontal = 8.dp)
                                    .background(if (current) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, MaterialTheme.shapes.small)
                                    .clickable { player.skipTo(index) }
                                    .padding(start = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // The grip: drag it to move the row (Alt+Up and Alt+Down, or the menu, do the same).
                                Icon(
                                    MusicIcons.Drag,
                                    contentDescription = L.queueMove,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp).dragGrip(drag, index, queue.size, rowPx) { from, to -> player.move(from, to) },
                                )
                                Cover(
                                    name = entry.title,
                                    fileId = 0,
                                    size = 40.dp,
                                    coverPath = Library.coverFile(entry.coverKey)?.path.orEmpty(),
                                    track = entry.id.takeIf { entry.thumbFileId != 0 },
                                    art = entry.artKey,
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (current) PlayingGlyph(playing = ui.playing, modifier = Modifier.padding(end = 6.dp))
                                        Text(
                                            entry.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                            fontWeight = if (current) FontWeight.SemiBold else null,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        listOf(entry.artist.ifBlank { L.libraryUnknownArtist }, Format.duration(entry.durationMs)).joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { player.remove(index) }, modifier = Modifier.size(32.dp)) {
                                    Icon(MusicIcons.Close, contentDescription = L.queueRemove, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(L.nowplayingClearQueueTitle) },
            text = { Text(L.nowplayingClearQueueBody(queue.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    player.clear()
                }) { Text(L.nowplayingClearQueueConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.commonCancel) } },
        )
    }
}
