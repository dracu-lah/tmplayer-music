package com.tmplayer.music.ui.player

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.tmplayer.music.player.SleepSetting
import com.tmplayer.music.player.SleepTimer
import com.tmplayer.music.ui.common.Artwork
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.PlaybackErrors
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.player.TD_SCHEME
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The playing position, read from the player twice a second while it plays and not at all otherwise. */
@Composable
fun rememberPosition(state: PlayerState): Long = rememberPositionState(state).value

/**
 * The same position as a [State], for callers that read it only where it is shown (a time Text, a
 * progress line drawn from a lambda), so a tick recomposes or redraws that alone, never its parent.
 */
@Composable
fun rememberPositionState(state: PlayerState): State<Long> {
    val position = remember { mutableLongStateOf(PlayerConnection.position()) }
    LaunchedEffect(state.isPlaying, state.item?.mediaId, state.index) {
        position.longValue = PlayerConnection.position()
        while (state.isPlaying) {
            delay(500)
            position.longValue = PlayerConnection.position()
        }
    }
    return position
}

/**
 * The playing item's cover, or its generated cover when it has none (never a blank box). [size] is the size it is
 * decoded at; [fill] stretches it over its slot (the caller clips it to the shape it wants).
 */
@Composable
internal fun ItemCover(item: MediaItem?, size: Dp, name: String, fill: Boolean = false) {
    val uri: Uri? = item?.mediaMetadata?.artworkUri
    Cover(
        name = name,
        fileId = 0,
        modifier = if (fill) Modifier.fillMaxSize() else Modifier,
        coverPath = if (uri?.scheme == "file") uri.path.orEmpty() else "",
        track = if (uri?.scheme == TD_SCHEME) com.tmplayer.music.player.tdTrackOf(uri) else null,
        size = size,
        art = item?.let { com.tmplayer.music.player.MediaIds.coverArt(it) },
    )
}

/** The heart for the playing song: filled when it is a favourite; tap to change. */
@Composable
internal fun FavouriteButton(item: MediaItem?) {
    val key = item?.mediaId?.let { com.tmplayer.music.player.MediaIds.item(it)?.songKey }.orEmpty()
    val changes by com.tmplayer.music.data.Library.changes.collectAsStateWithLifecycle()
    var on by remember(key) { mutableStateOf<Boolean?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(key, changes) {
        if (key.isBlank()) return@LaunchedEffect
        on = runCatching { key in com.tmplayer.music.data.Library.index().favouriteKeys() }.getOrNull()
    }
    // Holds its place while the answer is read, so the title beside it does not jump.
    val now = on ?: return Spacer(Modifier.size(48.dp))
    com.tmplayer.music.ui.theme.MorphIconToggle(checked = now, onCheckedChange = {
        scope.launch {
            runCatching {
                val index = com.tmplayer.music.data.Library.index()
                val song = index.song(key) ?: return@runCatching
                if (index.setFavourite(song, !now)) {
                    on = !now
                } else {
                    PlaybackErrors.report(L.menuFavouritesFull)
                }
                com.tmplayer.music.data.Library.notifyChanged()
            }
        }
    }) {
        Icon(
            if (now) MusicIcons.Heart else MusicIcons.HeartOutline,
            contentDescription = if (now) L.menuUnfavourite else L.menuFavourite,
            tint = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The tint of the playing track's cover, once its picture is in the memory cache. */
@Composable
internal fun rememberCoverTint(item: MediaItem?): Color? {
    val uri = item?.mediaMetadata?.artworkUri ?: return null
    var tint by remember(uri) { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        val image = when (uri.scheme) {
            "file" -> uri.path?.let { Artwork.file(java.io.File(it), 96) }
            TD_SCHEME -> com.tmplayer.music.player.tdTrackOf(uri)?.let { Artwork.thumbnail(0, 96, it) }
            else -> null
        } ?: return@LaunchedEffect
        tint = Artwork.tint(uri.toString(), image)
    }
    return tint
}

/** Pick a sleep timer: minutes, the end of this track, or off. Shows the time left while set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepSheet(onDismiss: () -> Unit) {
    val setting by SleepTimer.setting.collectAsStateWithLifecycle()
    var left by remember { mutableLongStateOf(SleepTimer.remainingMs()) }
    LaunchedEffect(setting) {
        // Ticks only while the sheet is open and a minutes timer runs.
        while (setting is SleepSetting.At) {
            left = SleepTimer.remainingMs()
            delay(1_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(L.sleepTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (setting is SleepSetting.At) {
                Text(L.sleepLeft(Format.duration(left)), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            for (minutes in listOf(15, 30, 45, 60, 90)) {
                SheetLine(L.sleepMinutes(minutes)) {
                    SleepTimer.minutes(minutes)
                    onDismiss()
                }
            }
            SheetLine(L.sleepEndOfTrack) {
                SleepTimer.endOfTrack()
                onDismiss()
            }
            if (setting != SleepSetting.Off) {
                SheetLine(L.sleepOff) {
                    SleepTimer.off()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetLine(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
