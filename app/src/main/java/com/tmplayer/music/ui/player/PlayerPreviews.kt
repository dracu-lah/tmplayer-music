package com.tmplayer.music.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.ui.theme.MusicTheme

// Previews of the player on the black theme, at font scale 1.3 and in landscape. They use the fixed
// (icon colour) scheme, since a preview has no wallpaper. Placeholder text only, no real names.

private fun sample(playing: Boolean = true, durationMs: Long = 215_000): PlayerState = PlayerState(
    connected = true,
    item = MediaItem.Builder()
        .setMediaId("preview")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("Track title that runs long").setArtist("Artist name").setAlbumTitle("Album").build(),
        )
        .build(),
    isPlaying = playing,
    durationMs = durationMs,
    count = 3,
    index = 1,
    hasNext = true,
    hasPrevious = true,
)

@Composable
private fun NowPlayingSample(dark: Boolean, playing: Boolean = true, durationMs: Long = 215_000) {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NowPlayingContent(sample(playing, durationMs), progress = { 1f }, open = false, onCollapse = {}, onQueue = {})
        }
    }
}

@Preview(name = "Now Playing, dark", widthDp = 400, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NowPlayingDark() = NowPlayingSample(dark = true)

@Preview(name = "Now Playing, dark, paused, font 1.3", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
private fun NowPlayingLargeText() = NowPlayingSample(dark = true, playing = false)

@Preview(name = "Now Playing, unknown length", widthDp = 400, heightDp = 860)
@Composable
private fun NowPlayingUnknownLength() = NowPlayingSample(dark = false, durationMs = 0)

@Preview(name = "Now Playing, landscape", widthDp = 860, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NowPlayingLandscape() = NowPlayingSample(dark = true)

@Composable
private fun MiniSample(dark: Boolean, playing: Boolean, durationMs: Long = 215_000) {
    MusicTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            // Flush and full width, directly on the bottom bar, as in the app: the progress line along
            // the mini-player's bottom edge is the one line between the two.
            Column(Modifier.padding(top = 24.dp)) {
                MiniPlayer(sample(playing, durationMs), progress = { 0f }, canDismiss = !playing, onExpand = {}, onDismiss = {}, fraction = { 0.4f })
                com.tmplayer.music.ui.library.ShellBottomBar(com.tmplayer.music.shell.MainTab.Library, sheet = null, onSelect = {}, miniPlayerOn = true)
            }
        }
    }
}

@Preview(name = "Mini-player, dark, paused", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MiniDark() = MiniSample(dark = true, playing = false)

@Preview(name = "Mini-player, font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun MiniLargeText() = MiniSample(dark = false, playing = true)

@Preview(name = "Mini-player, unknown length", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MiniUnknownLength() = MiniSample(dark = true, playing = false, durationMs = 0)

@Preview(name = "Wavy seek bar: playing, paused, unknown", widthDp = 360)
@Composable
private fun SeekBars() {
    MusicTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                WavySeekBar(fraction = { 0.4f }, durationMs = 200_000, playing = true, visible = false, onSeek = {})
                WavySeekBar(fraction = { 0.4f }, durationMs = 200_000, playing = false, visible = false, onSeek = {})
                WavySeekBar(fraction = { 0f }, durationMs = 0, playing = false, visible = false, onSeek = {})
            }
        }
    }
}
