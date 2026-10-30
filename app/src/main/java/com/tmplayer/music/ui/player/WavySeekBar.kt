package com.tmplayer.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.theme.LocalReducedMotion
import com.tmplayer.music.ui.theme.MusicMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** The wave's shape: one crest every 40 dp, 3 dp high, travelling one wavelength a second. */
private val Wavelength = 40.dp
private val Amplitude = 3.dp
private val StrokeWidth = 4.dp
private val Gap = 6.dp

/** How often the travelling wave is redrawn. */
private const val WAVE_FPS = 30

/**
 * The seek bar, in the Material 3 Expressive style: the played part is a wavy line, the rest a flat
 * track, and a tall pill thumb between them. (material3 1.4.0 has no public wavy indicator, and 1.5
 * needs minSdk 24, so it is drawn here.)
 *
 * When it moves ([WaveLogic]): the crest travels only while the track plays, Now Playing is open
 * ([visible]) and the app is in the foreground; motion off ("Remove animations") keeps it still.
 * Paused, scrubbing or with no known length the wave springs flat and the frame loop stops, so
 * nothing runs while idle.
 *
 * Touch: press anywhere to jump the thumb there, drag to scrub, lift to seek. Every pointer change
 * is consumed, so holding or dragging the bar never moves the player sheet. TalkBack reads it as a
 * slider with the position and can set it.
 */
@Composable
fun WavySeekBar(
    fraction: () -> Float,
    durationMs: Long,
    playing: Boolean,
    visible: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Float?) -> Unit = {},
) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val reduced = LocalReducedMotion.current
    val known = durationMs > 0
    val travels = WaveLogic.travels(playing, resumed, visible, reduced, known) && seeking == null
    val amplitude by animateFloatAsState(
        WaveLogic.amplitude(playing, known, seeking != null),
        MusicMotion.current.defaultEffects(),
        label = "amplitude",
    )
    // The crest's phase, advanced by a frame loop that exists only while the wave travels. Each
    // change redraws the whole window, so it moves at most [WAVE_FPS] times a second: a slow crest
    // looks the same, and a phone pays a third of the cost of 60 or 90 frames a second.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(travels) {
        if (!travels) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            // Sleeps between steps, so no frame is asked for in between.
            delay(1_000L / WAVE_FPS)
            withFrameNanos { now ->
                phase = (phase + (now - last) / 1_000_000_000f) % 1f
                last = now
            }
        }
    }
    val currentSeek by rememberUpdatedState(onSeek)
    val currentScrub by rememberUpdatedState(onScrub)
    val duration by rememberUpdatedState(durationMs)
    val shown = { seeking ?: fraction() }
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.secondaryContainer
    val positionText = Format.duration((shown() * durationMs).toLong().coerceAtLeast(0))
    val path = remember { Path() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(known) {
                if (!known) return@pointerInput
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    seeking = at(down.position.x)
                    currentScrub(seeking)
                    var id = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id } ?: event.changes.firstOrNull() ?: break
                        id = change.id
                        // Consumed so a parent (the sheet) never reads this as its own drag.
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                        seeking = at(change.position.x)
                        currentScrub(seeking)
                    }
                    seeking?.let { currentSeek((it * duration).toLong()) }
                    seeking = null
                    currentScrub(null)
                }
            }
            .semantics {
                contentDescription = L.playerSeek
                if (known) {
                    progressBarRangeInfo = ProgressBarRangeInfo(shown(), 0f..1f)
                    stateDescription = positionText
                    setProgress { target ->
                        currentSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
            },
    ) {
        val stroke = StrokeWidth.toPx()
        val gap = Gap.toPx()
        val thumbW = 6.dp.toPx()
        val thumbH = 28.dp.toPx()
        val mid = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        val x = if (known) start + (end - start) * shown() else start
        // The rest of the track: flat, after the thumb and its gap.
        val trackFrom = if (known) x + thumbW / 2 + gap else start
        if (trackFrom < end) {
            drawLine(track, Offset(trackFrom, mid), Offset(end, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        if (!known) return@Canvas
        // The played part: a sine whose height follows [amplitude] and whose crest moves with [phase].
        val playedTo = x - thumbW / 2 - gap
        if (playedTo > start) {
            val amp = Amplitude.toPx() * amplitude
            if (amp < 0.25f) {
                drawLine(active, Offset(start, mid), Offset(playedTo, mid), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                val wave = Wavelength.toPx()
                val shift = phase * wave
                path.reset()
                var px = start
                path.moveTo(px, mid + amp * sin(((px - shift) / wave) * 2f * PI.toFloat()))
                val step = 2.dp.toPx()
                while (px < playedTo) {
                    px = (px + step).coerceAtMost(playedTo)
                    path.lineTo(px, mid + amp * sin(((px - shift) / wave) * 2f * PI.toFloat()))
                }
                drawPath(path, active, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
        // The thumb: a tall pill.
        drawRoundRect(
            active,
            topLeft = Offset(x - thumbW / 2, mid - thumbH / 2),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbW / 2),
        )
    }
}
