package com.tmplayer.music.ui.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.PlaybackErrors
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.player.TD_SCHEME
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The playing position, read from the player twice a second while it plays and not at all otherwise. */
@Composable
fun rememberPosition(state: PlayerState): Long {
    var position by remember { mutableLongStateOf(PlayerConnection.position()) }
    LaunchedEffect(state.isPlaying, state.item?.mediaId, state.index) {
        position = PlayerConnection.position()
        while (state.isPlaying) {
            delay(500)
            position = PlayerConnection.position()
        }
    }
    return position
}

@Composable
private fun ItemCover(item: MediaItem?, size: Dp, name: String) {
    val uri: Uri? = item?.mediaMetadata?.artworkUri
    Cover(
        name = name,
        fileId = if (uri?.scheme == TD_SCHEME) uri.authority?.toIntOrNull() ?: 0 else 0,
        coverPath = if (uri?.scheme == "file") uri.path.orEmpty() else "",
        size = size,
    )
}

/**
 * The bar above the bottom edge on every library screen while something is in the queue. Tap opens
 * Now Playing; a swipe left or right skips. A track that could not play says so here.
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    val error by PlaybackErrors.last.collectAsStateWithLifecycle()
    if (state.count == 0 || state.item == null) return
    val position = rememberPosition(state)
    val title = state.metadata.title?.toString().orEmpty()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (drag < -threshold) PlayerConnection.next() else if (drag > threshold) PlayerConnection.previous()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, amount -> drag += amount }
                    }
                    .graphicsLayer { translationX = drag / 3 }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ItemCover(state.item, 44.dp, title)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.metadata.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayPause(state, 40.dp)
                IconButton(onClick = PlayerConnection::next, enabled = state.hasNext) {
                    Icon(MusicIcons.Next, contentDescription = L.playerNext)
                }
            }
        }
    }
}

@Composable
private fun PlayPause(state: PlayerState, size: Dp, filled: Boolean = false) {
    val icon = if (state.isPlaying) MusicIcons.Pause else MusicIcons.Play
    val label = if (state.isPlaying) L.playerPause else L.playerPlay
    if (filled) {
        FilledIconButton(onClick = PlayerConnection::toggle, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size / 2))
        }
    } else {
        IconButton(onClick = PlayerConnection::toggle, modifier = Modifier.size(size)) { Icon(icon, contentDescription = label) }
    }
}

/**
 * Now Playing: the big cover, the title and artist, a seek bar with times (an unknown length shows
 * no bar to seek in), previous, play, next, shuffle and repeat, and the queue.
 */
@Composable
fun NowPlaying(onClose: () -> Unit, onQueue: () -> Unit) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    val error by PlaybackErrors.last.collectAsStateWithLifecycle()
    val position = rememberPosition(state)
    var seeking by remember { mutableStateOf<Float?>(null) }
    val title = state.metadata.title?.toString().orEmpty()
    LaunchedEffect(state.count) { if (state.count == 0) onClose() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(MusicIcons.Down, contentDescription = L.playerClose) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onQueue) { Icon(MusicIcons.Queue, contentDescription = L.playerQueue) }
            }
            Spacer(Modifier.weight(0.5f))
            Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)) {
                ItemCover(state.item, 420.dp, title)
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(
                listOfNotNull(state.metadata.artist?.toString(), state.metadata.albumTitle?.toString()).joinToString("  ·  "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(16.dp))
            if (state.durationMs > 0) {
                val shown = seeking ?: (position.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Slider(
                    value = shown,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let { PlayerConnection.seekTo((it * state.durationMs).toLong()) }
                        seeking = null
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = L.playerSeek },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(Format.duration(((seeking ?: shown) * state.durationMs).toLong().coerceAtLeast(1)), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(Format.duration(state.durationMs), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // No length to seek in. Static text: an animated bar here would run for as long as
                // the screen is open, paused or not.
                Text(L.playerUnknownLength, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlayerConnection.setShuffle(!state.shuffle) }) {
                    Icon(
                        MusicIcons.Shuffle,
                        contentDescription = if (state.shuffle) L.playerShuffleOn else L.playerShuffleOff,
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = PlayerConnection::previous, enabled = state.hasPrevious) {
                    Icon(MusicIcons.Previous, contentDescription = L.playerPrevious, modifier = Modifier.size(32.dp))
                }
                PlayPause(state, 72.dp, filled = true)
                IconButton(onClick = PlayerConnection::next, enabled = state.hasNext) {
                    Icon(MusicIcons.Next, contentDescription = L.playerNext, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = PlayerConnection::cycleRepeat) {
                    Icon(
                        if (state.repeat == Player.REPEAT_MODE_ONE) MusicIcons.RepeatOne else MusicIcons.Repeat,
                        contentDescription = when (state.repeat) {
                            Player.REPEAT_MODE_ONE -> L.playerRepeatOne
                            Player.REPEAT_MODE_ALL -> L.playerRepeatAll
                            else -> L.playerRepeatOff
                        },
                        tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The queue: what plays next. Tap jumps to a track; hold a row and drag to move it; the cross
 * removes it; Clear empties the queue and stops.
 */
@Composable
fun QueueScreen(onClose: () -> Unit) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf(PlayerConnection.queue()) }
    LaunchedEffect(state.count, state.index, state.item?.mediaId) { items = PlayerConnection.queue() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (state.index in items.indices) list.scrollToItem(state.index) }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(MusicIcons.Down, contentDescription = L.playerClose) }
                Text(L.playerQueue, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    PlayerConnection.clear()
                    onClose()
                }) { Text(L.queueClear) }
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, item -> "$i:${item.mediaId}" }) { i, item ->
                    val current = i == state.index
                    val moving = i == dragging
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .graphicsLayer { if (moving) translationY = dragOffset }
                            .background(if (moving) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.background)
                            .clickable { PlayerConnection.skipTo(i) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            MusicIcons.Drag,
                            contentDescription = L.queueMove,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                                .pointerInput(i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = i
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val to = (dragging + (dragOffset / rowHeight).roundToInt()).coerceIn(0, items.lastIndex)
                                            PlayerConnection.move(dragging, to)
                                            dragging = -1
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragging = -1
                                            dragOffset = 0f
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    }
                                },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.mediaMetadata.title?.toString().orEmpty(),
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.mediaMetadata.artist?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { PlayerConnection.remove(i) }, colors = IconButtonDefaults.iconButtonColors()) {
                            Icon(MusicIcons.Close, contentDescription = L.queueRemove)
                        }
                    }
                }
            }
        }
    }
}
