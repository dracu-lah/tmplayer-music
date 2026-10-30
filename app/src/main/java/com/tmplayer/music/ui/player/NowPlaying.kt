package com.tmplayer.music.ui.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.player.PlaybackErrors
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.player.SleepSetting
import com.tmplayer.music.player.SleepTimer
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.theme.ExpressiveButtonGroup
import com.tmplayer.music.ui.theme.MorphButton
import com.tmplayer.music.ui.theme.MorphIconToggle
import com.tmplayer.music.ui.theme.MorphToggle
import com.tmplayer.music.ui.theme.ShapeRadius
import androidx.compose.ui.graphics.vector.ImageVector

/** Where the cover's slot and the content's root are, for the cover's grow-from-the-mini-player motion. */
private class CoverAnchor {
    var root: LayoutCoordinates? = null
    var slot: LayoutCoordinates? = null
}

/**
 * Now Playing, the expanded sheet: a big cover with large rounded corners (or the lyrics in its
 * place), the title and artist, the wavy seek bar with times, previous, play and next as one button
 * group, and shuffle and repeat toggles. At the top: back to the library, lyrics, sleep timer, queue.
 * Landscape puts the cover on the left and the controls on the right.
 *
 * [progress] is how open the sheet is (0 to 1), read only while drawing: the cover grows out of
 * the mini-player's cover and the rest fades in as the finger drags, with no recomposition per frame.
 */
@Composable
internal fun NowPlayingContent(
    state: PlayerState,
    progress: () -> Float,
    open: Boolean,
    onCollapse: () -> Unit,
    onQueue: () -> Unit,
) {
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val anchor = remember { CoverAnchor() }
    val density = LocalDensity.current
    // The mini-player's row keeps clear of a side cutout or side navigation buttons in landscape.
    val sideInset = androidx.compose.foundation.layout.WindowInsets.safeDrawing
        .getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val miniX = with(density) { MiniCoverInset.toPx() } + sideInset
    val miniY = with(density) { ((miniHeight() - MiniCoverSize) / 2).toPx() }
    val miniSize = with(density) { MiniCoverSize.toPx() }
    val fade = Modifier.graphicsLayer { alpha = ((progress() - 0.35f) / 0.65f).coerceIn(0f, 1f) }

    // The cover follows the sheet: at the mini-player it sits on the mini cover, at Now Playing in its slot.
    val coverMotion = Modifier
        .onPlaced { anchor.slot = it }
        .graphicsLayer {
            val p = progress()
            val root = anchor.root
            val slot = anchor.slot
            if (showLyrics || root == null || slot == null || !root.isAttached || !slot.isAttached || slot.size.width == 0) {
                alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                return@graphicsLayer
            }
            val at = root.localPositionOf(slot, Offset.Zero)
            val t = (p / 0.85f).coerceIn(0f, 1f)
            val small = miniSize / slot.size.width
            val scale = lerp(small, 1f, t)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = (miniX - at.x) * (1f - t)
            translationY = (miniY - at.y) * (1f - t)
            scaleX = scale
            scaleY = scale
            alpha = (p / 0.25f).coerceIn(0f, 1f)
            val radius = lerp(ShapeRadius.SmallCover.toPx(), ShapeRadius.Cover.toPx(), t) / scale
            shape = RoundedCornerShape(radius)
            clip = true
        }

    val slotContent: @Composable (Modifier) -> Unit = { slotModifier ->
        if (showLyrics) {
            Box(slotModifier.then(fade)) { LyricsPanel(state, visible = open) }
        } else {
            Box(slotModifier.then(coverMotion)) {
                ItemCover(state.item, 420.dp, state.metadata.title?.toString().orEmpty(), fill = true)
            }
        }
    }
    val topBar: @Composable () -> Unit = {
        TopBar(
            showLyrics = showLyrics,
            onLyrics = { showLyrics = it },
            onCollapse = onCollapse,
            onSleep = { sleepOpen = true },
            onQueue = onQueue,
            modifier = fade,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPlaced { anchor.root = it }) {
        if (maxWidth > maxHeight) {
            Row(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.45f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    slotContent(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
                BoxWithConstraints(Modifier.weight(0.55f).fillMaxHeight()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        topBar()
                        Controls(state, open, fade, compact = true)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                topBar()
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    slotContent(Modifier.widthIn(max = 480.dp).aspectRatio(1f, matchHeightConstraintsFirst = true))
                }
                Controls(state, open, fade)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (sleepOpen) SleepSheet(onDismiss = { sleepOpen = false })
}

@Composable
private fun TopBar(
    showLyrics: Boolean,
    onLyrics: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    onSleep: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier,
) {
    val sleep by SleepTimer.setting.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) { Icon(MusicIcons.Down, contentDescription = L.nowplayingCollapse) }
        Spacer(Modifier.weight(1f))
        MorphIconToggle(checked = showLyrics, onCheckedChange = onLyrics) {
            Icon(MusicIcons.Lyrics, contentDescription = if (showLyrics) L.nowplayingLyricsHide else L.nowplayingLyricsShow)
        }
        Spacer(Modifier.width(4.dp))
        MorphIconToggle(checked = sleep != SleepSetting.Off, onCheckedChange = { onSleep() }) {
            Icon(
                MusicIcons.Sleep,
                contentDescription = when (sleep) {
                    SleepSetting.Off -> L.sleepTitle
                    SleepSetting.EndOfTrack -> "${L.sleepOn}, ${L.sleepEndOfTrackShort}"
                    is SleepSetting.At -> L.sleepOn
                },
            )
        }
        IconButton(onClick = onQueue) { Icon(MusicIcons.Queue, contentDescription = L.playerQueue) }
    }
}

/** Title and artist, the seek bar, the transport group and the shuffle and repeat toggles. */
@Composable
private fun Controls(state: PlayerState, open: Boolean, fade: Modifier, compact: Boolean = false) {
    val error by PlaybackErrors.last.collectAsStateWithLifecycle()
    // Read only by the bar (while drawing) and by [Times]: a tick of the position never recomposes these controls.
    val position = rememberPositionState(state)
    val scrub = remember { mutableStateOf<Float?>(null) }
    val title = state.metadata.title?.toString().orEmpty()
    val subtitle = listOfNotNull(
        ArtistDisplay.of(state.metadata.artist?.toString()).takeIf { it.isNotBlank() },
        state.metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
    Column(fade.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FavouriteButton(state.item)
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
        if (state.durationMs > 0) {
            WavySeekBar(
                fraction = { WaveLogic.fraction(position.value, state.durationMs) },
                durationMs = state.durationMs,
                playing = state.isPlaying,
                visible = open,
                onSeek = PlayerConnection::seekTo,
                onScrub = { scrub.value = it },
            )
            Times(position, scrub, state.durationMs)
        } else {
            // No length to seek in: a still line and a plain sentence, nothing animated.
            WavySeekBar(fraction = { 0f }, durationMs = 0, playing = false, visible = false, onSeek = {})
            Text(
                L.playerUnknownLength,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Landscape (compact) has about 360 dp of height: smaller circles and gaps keep everything in view.
        val gap = if (compact) 8.dp else 16.dp
        Spacer(Modifier.height(gap))
        Transport(state, compact)
        Spacer(Modifier.height(gap))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            ShuffleToggle(state.shuffle)
            RepeatToggle(state.repeat)
        }
    }
}

/**
 * The elapsed and total times under the bar. The only part of Now Playing that recomposes while a
 * track plays: twice a second with the position, and on each move while scrubbing.
 */
@Composable
private fun Times(position: State<Long>, scrub: State<Float?>, durationMs: Long) {
    val shown = scrub.value?.let { (it * durationMs).toLong() } ?: position.value
    val times = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth()) {
        Text(Format.duration(shown.coerceAtLeast(0)), style = times, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(Format.duration(durationMs), style = times, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Previous, play or pause, next: one connected group where the pressed button widens and its
 * neighbours give way (the Material 3 Expressive button group). At rest all three are circles, play
 * or pause the largest one in the middle.
 */
@Composable
private fun Transport(state: PlayerState, compact: Boolean) {
    val previous = remember { MutableInteractionSource() }
    val play = remember { MutableInteractionSource() }
    val next = remember { MutableInteractionSource() }
    val skipSize = if (compact) 56.dp else SkipSize
    val playSize = if (compact) 72.dp else PlaySize
    ExpressiveButtonGroup(Modifier.fillMaxWidth()) {
        SkipButton(MusicIcons.Previous, L.playerPrevious, state.hasPrevious, PlayerConnection::previous, previous, Modifier.groupItem("previous", previous, skipSize).height(skipSize))
        PlayPauseButton(state, Modifier.groupItem("play", play, playSize).height(playSize), iconSize = if (compact) 32.dp else 40.dp, interactionSource = play)
        SkipButton(MusicIcons.Next, L.playerNext, state.hasNext, PlayerConnection::next, next, Modifier.groupItem("next", next, skipSize).height(skipSize))
    }
}

/** The transport's circles: skip buttons and the larger play or pause. */
private val SkipSize = 64.dp
private val PlaySize = 88.dp

@Composable
private fun SkipButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    source: MutableInteractionSource,
    modifier: Modifier,
) {
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    MorphButton(
        onClick = { haptics.tap(); onClick() },
        modifier = modifier,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        interactionSource = source,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ShuffleToggle(on: Boolean) {
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    MorphToggle(
        checked = on,
        onCheckedChange = { haptics.toggle(it); PlayerConnection.setShuffle(it) },
        modifier = Modifier.semantics { contentDescription = if (on) L.playerShuffleOn else L.playerShuffleOff },
    ) {
        Icon(MusicIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(L.nowplayingShuffle, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RepeatToggle(mode: Int) {
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    MorphToggle(
        checked = mode != Player.REPEAT_MODE_OFF,
        // Off, all, one, off: on until it leaves repeat one.
        onCheckedChange = { haptics.toggle(mode != Player.REPEAT_MODE_ONE); PlayerConnection.cycleRepeat() },
        modifier = Modifier.semantics {
            contentDescription = when (mode) {
                Player.REPEAT_MODE_ONE -> L.playerRepeatOne
                Player.REPEAT_MODE_ALL -> L.playerRepeatAll
                else -> L.playerRepeatOff
            }
        },
    ) {
        Icon(if (mode == Player.REPEAT_MODE_ONE) MusicIcons.RepeatOne else MusicIcons.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (mode == Player.REPEAT_MODE_ONE) L.nowplayingRepeatOne else L.nowplayingRepeat, style = MaterialTheme.typography.labelLarge)
    }
}
