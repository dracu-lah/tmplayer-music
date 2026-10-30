package com.tmplayer.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.common.MusicIcons
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far across a queue row has to be swiped to remove it, as a share of its width. */
private const val SWIPE_REMOVE = 0.35f

/**
 * The queue, as a sheet over Now Playing that drags down to close like the player itself (Back
 * closes it too). Tap a row to jump there; drag a row by its handle to move it; swipe a row
 * sideways or tap its cross to remove it; Clear asks first, then empties the queue and stops.
 * When the queue runs empty the sheet closes by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf(PlayerConnection.queue()) }
    LaunchedEffect(state.count, state.index, state.item?.mediaId) { items = PlayerConnection.queue() }
    LaunchedEffect(state.count) { if (state.count == 0) onDismiss() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Open or closed, no half height: the list is long and the sheet drags down to close.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(L.playerQueue, style = MaterialTheme.typography.headlineSmall)
                Text(
                    L.nowplayingQueueCount(items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty(), modifier = Modifier.height(48.dp)) {
                Text(L.queueClear)
            }
        }
        // A move changes neither the count nor the playing item, so the list is read again after it.
        QueueList(items, state.index, onChanged = { items = PlayerConnection.queue() })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(L.nowplayingClearQueueTitle) },
            text = { Text(L.nowplayingClearQueueBody(items.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    PlayerConnection.clear()
                    onDismiss()
                }) { Text(L.nowplayingClearQueueConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.commonCancel) } },
        )
    }
}

@Composable
private fun QueueList(items: List<MediaItem>, playing: Int, onChanged: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (playing in items.indices) list.scrollToItem((playing - 1).coerceAtLeast(0)) }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // A row and the gap under it: how far a dragged row travels to swap one place.
    val rowHeight = with(LocalDensity.current) { 68.dp.toPx() }
    val shape = MaterialTheme.shapes.medium
    val dragHaptics = com.tmplayer.music.ui.common.rememberHaptics()
    val swipeHaptics = com.tmplayer.music.ui.common.rememberHaptics()

    LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        itemsIndexed(items, key = { i, item -> "$i:${item.mediaId}" }) { i, item ->
            val current = i == playing
            val moving = i == dragging
            var swipe by remember(i, item.mediaId) { mutableFloatStateOf(0f) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .pointerInput(i, item.mediaId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (abs(swipe) > size.width * SWIPE_REMOVE) PlayerConnection.remove(i)
                                swipe = 0f
                            },
                            onDragCancel = { swipe = 0f },
                        ) { change, amount ->
                            change.consume()
                            swipe += amount
                            swipeHaptics.onPass(swipe, size.width * SWIPE_REMOVE)
                        }
                    }
                    .graphicsLayer {
                        if (moving) translationY = dragOffset
                        translationX = swipe
                        alpha = 1f - (abs(swipe) / size.width).coerceIn(0f, 0.6f)
                    }
                    .clip(shape)
                    .background(
                        when {
                            moving -> MaterialTheme.colorScheme.surfaceContainerHighest
                            current -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    )
                    .clickable { PlayerConnection.skipTo(i) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    MusicIcons.Drag,
                    contentDescription = L.queueMove,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp)
                        .pointerInput(i) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = i
                                    dragOffset = 0f
                                    dragHaptics.gestureStart()
                                    dragHaptics.onChange(null)
                                },
                                onDragEnd = {
                                    val to = (dragging + (dragOffset / rowHeight).roundToInt()).coerceIn(0, items.lastIndex)
                                    PlayerConnection.move(dragging, to)
                                    onChanged()
                                    dragHaptics.onChange(null)
                                    dragHaptics.tick()
                                    dragging = -1
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragHaptics.onChange(null)
                                    dragging = -1
                                    dragOffset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                dragHaptics.onChange((dragging + (dragOffset / rowHeight).roundToInt()).coerceIn(0, items.lastIndex))
                            }
                        },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.mediaMetadata.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ArtistDisplay.of(item.mediaMetadata.artist?.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayerConnection.remove(i) }) {
                    Icon(MusicIcons.Close, contentDescription = L.queueRemove)
                }
            }
            if (i < items.lastIndex) androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        }
    }
}
