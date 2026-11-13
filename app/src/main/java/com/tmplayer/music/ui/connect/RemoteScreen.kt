package com.tmplayer.music.ui.connect

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.connect.Connect
import com.tmplayer.music.connect.ConnectBody
import com.tmplayer.music.connect.Link
import com.tmplayer.music.connect.PhoneRemote
import com.tmplayer.music.connect.Transport
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.GeneratedCover
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.delay

/**
 * The phone's remote for the TV app, full screen over whatever opened it (Settings, or Now Playing
 * when a TV is paired). It connects while it is open and hangs up when it closes. It shows what the TV
 * says: cover, title, artist, a seek bar, play or pause, previous and next, shuffle and repeat, the
 * TV's volume, and the TV's queue, where a tap jumps to a row and the cross removes it. Every control
 * only asks: the TV does it and says what changed.
 */
@Composable
fun RemoteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tvs by Connect.tvs.list.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        PhoneRemote.open(context)
        onDispose { PhoneRemote.close() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ui by PhoneRemote.ui.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(MusicIcons.Close, contentDescription = L.playerClose) }
                    Column(Modifier.weight(1f)) {
                        val name = ui.tv?.name.orEmpty()
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (ui.link) {
                                Link.Connected -> L.connectLinkConnected(name)
                                Link.Connecting -> L.connectLinkConnecting(name)
                                Link.Offline -> L.connectLinkOffline(name)
                                Link.Forgotten -> L.connectLinkForgotten(name)
                                Link.Idle, Link.Searching -> L.connectLinkSearching(name)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.link == Link.Forgotten || ui.link == Link.Offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (tvs.size > 1) {
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tv in tvs) FilterChip(selected = tv.id == ui.tv?.id, onClick = {
                            Connect.setActiveTv(tv.id)
                            PhoneRemote.close()
                            PhoneRemote.open(context, tv)
                        }, label = { Text(tv.name.ifBlank { tv.id.take(8) }, maxLines = 1) })
                    }
                }
                val now = ui.now
                if (now == null || !now.hasTrack) {
                    Text(L.connectNothingPlaying, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
                Controls(ui)
                QueueList(ui)
            }
        }
    }
}

@Composable
private fun Controls(ui: com.tmplayer.music.connect.RemoteUi) {
    val now = ui.now ?: ConnectBody.NowPlaying("", "", "", 0, 0, false, false, false, 0, 0, 0, 0, false, "", false)
    val enabled = ui.link == Link.Connected
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Cover(ui, now.coverKey)
        Text(now.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(now.artist, now.album).filter { it.isNotBlank() }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        SeekBar(ui, enabled)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = enabled, onClick = { PhoneRemote.send(ConnectBody.Shuffle(!now.shuffle)) }) {
                Icon(MusicIcons.Shuffle, contentDescription = if (now.shuffle) L.playerShuffleOn else L.playerShuffleOff,
                    tint = if (now.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = enabled, onClick = { PhoneRemote.send(ConnectBody.Command(Transport.Previous)) }) { Icon(MusicIcons.Previous, contentDescription = L.playerPrevious) }
            FilledIconButton(
                enabled = enabled, modifier = Modifier.size(64.dp),
                onClick = { PhoneRemote.send(ConnectBody.Command(Transport.Toggle)) },
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(if (now.playing) MusicIcons.Pause else MusicIcons.Play, contentDescription = if (now.playing) L.playerPause else L.playerPlay, modifier = Modifier.size(32.dp))
            }
            IconButton(enabled = enabled, onClick = { PhoneRemote.send(ConnectBody.Command(Transport.Next)) }) { Icon(MusicIcons.Next, contentDescription = L.playerNext) }
            IconButton(enabled = enabled, onClick = { PhoneRemote.send(ConnectBody.Repeat((now.repeat + 1) % 3)) }) {
                Icon(
                    if (now.repeat == 1) MusicIcons.RepeatOne else MusicIcons.Repeat,
                    contentDescription = when (now.repeat) { 1 -> L.playerRepeatOne; 2 -> L.playerRepeatAll; else -> L.playerRepeatOff },
                    tint = if (now.repeat != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Volume(now, enabled)
    }
}

/** The TV's cover: the picture it sent, else the same generated cover the TV draws; never blocks. */
@Composable
private fun Cover(ui: com.tmplayer.music.connect.RemoteUi, key: String) {
    val sent = ui.cover?.takeIf { it.key == key }
    val px = with(LocalDensity.current) { 240.dp.roundToPx() }
    val picture by androidx.compose.runtime.produceState<ImageBitmap?>(null, sent?.base64, key) {
        value = sent?.let { c ->
            runCatching {
                val bytes = Base64.decode(c.base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } ?: key.takeIf { it.isNotBlank() }?.let { GeneratedCover.image(it, px) }
    }
    Box(
        Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = picture
        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(MusicIcons.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
    }
}

/** The position moves on by itself while the TV plays (twice a second, only while this is on screen); a seek is sent when the finger lifts. */
@Composable
private fun SeekBar(ui: com.tmplayer.music.connect.RemoteUi, enabled: Boolean) {
    val now = ui.now
    val playing = now?.playing == true && !now.buffering
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing, now?.positionMs, ui.receivedAt) {
        tick = SystemClock.elapsedRealtime()
        while (playing) {
            delay(500)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val duration = now?.durationMs ?: 0
    val position = ui.positionNow(tick)
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Slider(
        value = fraction, enabled = enabled && duration > 0,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { PhoneRemote.send(ConnectBody.Seek((it * duration).toLong())) }
            drag = null
        },
    )
    Row(Modifier.fillMaxWidth()) {
        Text(Format.duration((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f))
        Text(if (duration > 0) Format.duration(duration) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Volume(now: ConnectBody.NowPlaying, enabled: Boolean) {
    if (now.volumeFixed) {
        Text(L.connectVolumeFixed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    Text(L.connectVolume, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = drag ?: (now.volume / 100f), enabled = enabled, valueRange = 0f..1f,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { PhoneRemote.send(ConnectBody.Volume((it * 100).toInt())) }
            drag = null
        },
    )
}

/** The TV's queue (a window of it when it is long): a tap jumps to the row, the cross removes it. */
@Composable
private fun QueueList(ui: com.tmplayer.music.connect.RemoteUi) {
    val queue = ui.queue
    Text(L.connectQueue, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    if (queue == null || queue.items.isEmpty()) {
        Text(L.connectQueueEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val state = rememberLazyListState()
    // Brings the playing row into view when the window changes under it.
    LaunchedEffect(queue.from, queue.current) {
        val at = queue.current - queue.from
        if (at in queue.items.indices) runCatching { state.animateScrollToItem(at) }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(queue.items, key = { i, e -> "${queue.from + i}:${e.id}" }) { i, entry ->
            val index = queue.from + i
            val current = index == queue.current
            Row(
                Modifier.fillMaxWidth().clickable { PhoneRemote.send(ConnectBody.Jump(index, entry.id)) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (entry.artist.isNotBlank()) {
                        Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { PhoneRemote.send(ConnectBody.Remove(index, entry.id)) }) {
                    Icon(MusicIcons.Close, contentDescription = L.connectQueueRemove)
                }
            }
        }
    }
}
