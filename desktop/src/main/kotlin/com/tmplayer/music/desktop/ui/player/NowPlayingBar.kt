package com.tmplayer.music.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.diagnostics.SendLogsButton
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.QueueEntry
import com.tmplayer.music.desktop.player.RepeatMode
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.guarded
import com.tmplayer.music.desktop.ui.library.Favourites
import kotlinx.coroutines.launch
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.i18n.L

/** The bar's height: cover and two lines of text on the left, the controls and the seek bar in the middle, the volume on the right. */
val NOW_PLAYING_HEIGHT = 88.dp

/**
 * The bottom bar: the playing track's cover, title and artist; shuffle, previous, play or pause,
 * next and repeat over a seek bar with the time; the queue button and the volume. Nothing is
 * playing on open: with a restored queue the bar shows its track paused, and Play starts it.
 */
@Composable
fun NowPlayingBar(player: PlayerController, open: Panel?, onToggle: (Panel) -> Unit, modifier: Modifier = Modifier) {
    val ui by player.ui.collectAsState()
    val entry = ui.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().height(NOW_PLAYING_HEIGHT)) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) { TrackInfo(entry) }
                    if (entry != null) FavouriteToggle(entry)
                }
                Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Transport(player)
                    SeekRow(player)
                }
                Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    PanelButton(MusicIcons.Lyrics, L.nowplayingLyricsShow, open == Panel.Lyrics) { onToggle(Panel.Lyrics) }
                    PanelButton(MusicIcons.Sleep, L.effectsTitle, open == Panel.Sound) { onToggle(Panel.Sound) }
                    PanelButton(MusicIcons.Queue, L.playerQueue, open == Panel.Queue) { onToggle(Panel.Queue) }
                    VolumeControl(player)
                }
            }
        }
    }
}

/** The error strip over the bar: what went wrong, Try again, and Send logs. */
@Composable
fun PlayerErrorStrip(player: PlayerController) {
    val ui by player.ui.collectAsState()
    val error = ui.error ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = player::retry) { Text(L.commonRetry) }
            SendLogsButton()
        }
    }
}

@Composable
private fun TrackInfo(entry: QueueEntry?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entry == null) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Icon(MusicIcons.Equalizer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(L.desktopNothingPlaying, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Cover(
                name = entry.title,
                fileId = 0,
                size = 56.dp,
                coverPath = Library.coverFile(entry.coverKey)?.path.orEmpty(),
                track = entry.id.takeIf { entry.thumbFileId != 0 },
                art = entry.artKey,
            )
            Column(Modifier.weight(1f, fill = false)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.artist.ifBlank { L.libraryUnknownArtist },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Transport(player: PlayerController) {
    val ui by player.ui.collectAsState()
    val has = !ui.queue.isEmpty
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BarButton(
            MusicIcons.Shuffle,
            if (ui.shuffle) L.playerShuffleOn else L.playerShuffleOff,
            enabled = has,
            active = ui.shuffle,
        ) { player.setShuffle(!ui.shuffle) }
        BarButton(MusicIcons.Previous, L.playerPrevious, enabled = has) { player.previous() }
        // Play or pause: the one filled control.
        IconButton(
            onClick = { player.togglePlay() },
            enabled = has,
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.size(40.dp),
        ) {
            if (ui.buffering && !ui.playing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(if (ui.playing) MusicIcons.Pause else MusicIcons.Play, contentDescription = if (ui.playing) L.playerPause else L.playerPlay)
            }
        }
        BarButton(MusicIcons.Next, L.playerNext, enabled = has && (ui.queue.hasNext)) { player.next() }
        BarButton(
            if (ui.repeat == RepeatMode.One) MusicIcons.RepeatOne else MusicIcons.Repeat,
            when (ui.repeat) {
                RepeatMode.Off -> L.playerRepeatOff
                RepeatMode.All -> L.playerRepeatAll
                RepeatMode.One -> L.playerRepeatOne
            },
            enabled = has,
            active = ui.repeat != RepeatMode.Off,
        ) { player.cycleRepeat() }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The time, the seek bar and the length. A track whose length is not known says so and cannot be seeked. */
@Composable
private fun SeekRow(player: PlayerController) {
    val ui by player.ui.collectAsState()
    val position by player.position.collectAsState()
    val duration = ui.durationMs.takeIf { it > 0 } ?: ui.current?.durationMs ?: 0L
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seekable = duration > 0 && !ui.queue.isEmpty
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = dragging?.let { (it * duration).toLong() } ?: position
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (ui.queue.isEmpty) "" else Format.position(shownMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = seekable,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f).height(24.dp).semantics { contentDescription = L.playerSeek },
        )
        // A length that is not known is shown as such, never hidden.
        Text(
            if (ui.queue.isEmpty) "" else Format.duration(duration),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
    }
}

/** The side panels the bar opens over the page's right edge, one at a time. */
enum class Panel { Queue, Lyrics, Sound }

@Composable
private fun PanelButton(icon: ImageVector, description: String, open: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VolumeControl(player: PlayerController) {
    val ui by player.ui.collectAsState()
    val silent = ui.muted || ui.volume == 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { player.toggleMute() }, modifier = Modifier.size(36.dp)) {
            Icon(
                if (silent) MusicIcons.VolumeMuted else MusicIcons.Volume,
                contentDescription = if (silent) L.desktopUnmute else L.desktopMute,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Slider(
            value = if (ui.muted) 0f else ui.volume / 100f,
            onValueChange = { player.setVolume((it * 100).toInt()) },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.width(88.dp).height(24.dp).semantics { contentDescription = L.desktopVolume },
        )
    }
}

/** The heart of the playing song: on when it is a favourite, a click turns it on or off. */
@Composable
private fun FavouriteToggle(entry: QueueEntry) {
    val keys by Favourites.keys.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // A file played from a chat page has no song key of its own: the index knows which song it is.
    val key by androidx.compose.runtime.produceState(entry.songKey, entry.id, entry.songKey) {
        value = entry.songKey.ifBlank { runCatching { Library.index().songOf(entry.id)?.groupKey }.getOrNull().orEmpty() }
    }
    val on = key.isNotBlank() && key in keys
    IconButton(
        onClick = {
            scope.launch {
                guarded({ }) {
                    val index = Library.index()
                    val song = (if (entry.songKey.isNotBlank()) index.song(entry.songKey) else null) ?: index.songOf(entry.id) ?: return@guarded
                    Favourites.set(listOf(song), !on)
                }
            }
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (on) MusicIcons.Heart else MusicIcons.HeartOutline,
            contentDescription = if (on) L.menuUnfavourite else L.menuFavourite,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
