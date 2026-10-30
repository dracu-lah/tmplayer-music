package com.tmplayer.music.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.player.ItemCover
import com.tmplayer.music.ui.player.LocalPlayerSheet
import com.tmplayer.music.ui.player.WaveLogic
import com.tmplayer.music.ui.theme.MusicTheme
import com.tmplayer.music.ui.theme.Pill

/**
 * Continue listening, at the top of Home (`plan/RESEARCH.md` 4 item 2): the queue the app restored
 * on open, paused where it was left, as one large card with the cover, title, artist, a thin line
 * of the position and a round play button. Nothing plays until the button is pressed; a tap on the
 * card opens Now Playing. With no queue there is no card.
 *
 * The position is read when the song or play and pause change, never on a timer, so the card is
 * still while Home is open.
 */
@Composable
fun ContinueCard(modifier: Modifier = Modifier) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    if (state.item == null || state.count == 0) return
    val sheet = LocalPlayerSheet.current
    val position = remember(state.item?.mediaId, state.index, state.isPlaying, state.durationMs) { PlayerConnection.position() }
    ContinueCardContent(
        state = state,
        positionMs = position,
        onResume = PlayerConnection::toggle,
        onOpen = { sheet?.expand() },
        modifier = modifier,
    )
}

@Composable
internal fun ContinueCardContent(
    state: PlayerState,
    positionMs: Long,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = state.metadata.title?.toString().orEmpty()
    val artist = state.metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: L.libraryUnknownArtist
    val fraction = WaveLogic.fraction(positionMs, state.durationMs)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L.listeningContinue, style = MaterialTheme.typography.titleMedium)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClickLabel = L.nowplayingExpand, onClick = onOpen),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(88.dp).clip(MaterialTheme.shapes.medium)) {
                        ItemCover(state.item, 88.dp, title, fill = true)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledIconButton(onClick = onResume, shape = CircleShape, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (state.isPlaying) MusicIcons.Pause else MusicIcons.Play,
                            contentDescription = if (state.isPlaying) L.playerPause else L.listeningResume,
                        )
                    }
                }
                if (state.durationMs > 0) {
                    val played = Format.duration(positionMs.coerceIn(0, state.durationMs))
                    val length = Format.duration(state.durationMs)
                    // A still line, not a slider: seeking is in Now Playing.
                    Box(
                        Modifier
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(Pill)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .semantics { contentDescription = L.listeningProgress(played, length) },
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(Pill).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

// Previews: the fixed (icon colour) scheme, placeholder text only.

private fun previewState(playing: Boolean) = PlayerState(
    connected = true,
    item = MediaItem.Builder()
        .setMediaId("preview")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Track title that runs long").setArtist("Artist name").build())
        .build(),
    isPlaying = playing,
    durationMs = 215_000,
    count = 3,
)

@Composable
private fun ContinueSample(dark: Boolean, playing: Boolean) {
    MusicTheme(dark = dark, dynamic = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                ContinueCardContent(previewState(playing), positionMs = 80_000, onResume = {}, onOpen = {})
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayingGlyph(playing = playing, modifier = Modifier.padding(end = 6.dp))
                    Text("Marked track title", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Preview(name = "Continue card and playing mark, light, paused", widthDp = 400)
@Composable
private fun ContinueLight() = ContinueSample(dark = false, playing = false)

@Preview(name = "Continue card and playing mark, dark, playing", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ContinueDark() = ContinueSample(dark = true, playing = true)

@Preview(name = "Continue card, font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun ContinueLargeText() = ContinueSample(dark = false, playing = false)
