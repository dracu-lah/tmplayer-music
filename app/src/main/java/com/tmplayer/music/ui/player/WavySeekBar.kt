package com.tmplayer.music.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.theme.LocalReducedMotion
import com.tmplayer.music.ui.theme.MusicMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** The wave's shape: one crest every 40 dp, 3 dp high, travelling one wavelength a second in steps. */
private val Wavelength = 40.dp
private val Amplitude = 3.dp
private val StrokeWidth = 4.dp
private val Gap = 6.dp
private val ThumbWidth = 6.dp
private val ThumbHeight = 28.dp

/**
 * The seek bar, in the Material 3 Expressive style: the played part is a wavy line, the rest a flat
 * track, and a tall pill thumb between them. (material3 1.4.0 has no public wavy indicator, and 1.5
 * needs minSdk 24, so it is drawn here.)
 *
 * When it moves ([WaveLogic]): the crest travels only while the track plays, Now Playing is open
 * ([visible]) and the app is in the foreground, about 16 steps a second; motion off ("Remove
 * animations"), a low memory phone and Battery Saver keep it still (still wavy while playing).
 * Paused, scrubbing or with no known length the wave springs flat and the step loop stops, so
 * nothing runs while idle.
 *
 * What a step costs: the wave is drawn once into a bitmap one wavelength wide, laid side by side in
 * its own layer, and a step only moves that layer sideways ([WaveLogic.Draw.Pattern]). Nothing
 * recomposes and nothing is drawn again; the played length clips it and changes only with the
 * position (twice a second). The bar is its own layer too, so its redraws never repaint the sheet.
 * [fraction] is read only while drawing.
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
    val context = LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    // Battery Saver is read again each time the app comes back to the front.
    val powerSave = remember(context, resumed) { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true }
    val still = WaveLogic.stillDevice(lowRam, powerSave)
    val scrubbing = seeking != null
    val travels = WaveLogic.travels(playing, resumed, visible, reduced, known, still) && !scrubbing
    val amplitude = animateFloatAsState(
        WaveLogic.amplitude(playing, known, scrubbing),
        MusicMotion.current.defaultEffects(),
        label = "amplitude",
    )
    // The crest's phase, moved on by a timed step (WaveLogic.STEP_MS) that runs only while the wave
    // travels. It is read only by the pattern layer's translation, so a step moves that layer alone.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(travels) {
        if (!travels) return@LaunchedEffect
        val from = phase.floatValue
        val start = withFrameNanos { it }
        while (true) {
            // Sleeps between steps, so no frame is asked for in between; then waits for the next
            // frame so the step lands on it, timed by the frame clock.
            delay(WaveLogic.SLEEP_MS)
            withFrameNanos { now -> phase.floatValue = WaveLogic.phaseAt(now - start, from) }
        }
    }
    val currentSeek by rememberUpdatedState(onSeek)
    val haptics = com.tmplayer.music.ui.common.rememberHaptics()
    val currentScrub by rememberUpdatedState(onScrub)
    val duration by rememberUpdatedState(durationMs)
    val currentFraction by rememberUpdatedState(fraction)
    val shown = { seeking ?: currentFraction() }
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.secondaryContainer
    val path = remember { Path() }

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            // Its own layer: a redraw of the bar (position, thumb) never repaints the rest of the sheet.
            .graphicsLayer()
            .pointerInput(known) {
                if (!known) return@pointerInput
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    seeking = at(down.position.x)
                    currentScrub(seeking)
                    haptics.gestureStart(); haptics.marks(null); haptics.marks(seeking)
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
                        haptics.marks(seeking)
                    }
                    seeking?.let { currentSeek((it * duration).toLong()) }
                    seeking = null
                    currentScrub(null)
                    haptics.gestureEnd(); haptics.marks(null)
                }
            }
            .semantics {
                contentDescription = L.playerSeek
                if (known) {
                    val now = shown()
                    progressBarRangeInfo = ProgressBarRangeInfo(now, 0f..1f)
                    stateDescription = Format.duration((now * durationMs).toLong().coerceAtLeast(0))
                    setProgress { target ->
                        currentSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
            }
            .drawBehind {
                val bar = BarGeometry(this, known, shown())
                // The rest of the track: flat, after the thumb and its gap.
                if (bar.trackFrom < bar.end) {
                    drawLine(track, Offset(bar.trackFrom, bar.mid), Offset(bar.end, bar.mid), strokeWidth = bar.stroke, cap = StrokeCap.Round)
                }
                if (!known) return@drawBehind
                if (bar.playedTo > bar.start) {
                    val amp = amplitude.value
                    when (WaveLogic.draw(amp)) {
                        WaveLogic.Draw.Flat ->
                            drawLine(active, Offset(bar.start, bar.mid), Offset(bar.playedTo, bar.mid), strokeWidth = bar.stroke, cap = StrokeCap.Round)
                        // Only while the height springs (a few hundred ms after play or pause).
                        WaveLogic.Draw.Path -> drawWavePath(path, bar, Amplitude.toPx() * amp, phase.floatValue, active)
                        // Drawn by the pattern layer below.
                        WaveLogic.Draw.Pattern -> Unit
                    }
                }
                // The thumb: a tall pill.
                val thumbW = ThumbWidth.toPx()
                val thumbH = ThumbHeight.toPx()
                drawRoundRect(
                    active,
                    topLeft = Offset(bar.x - thumbW / 2, bar.mid - thumbH / 2),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2),
                )
            },
    ) {
        if (known) {
            // The played part's window: clipped to the played length, which moves with the position only.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = if (WaveLogic.draw(amplitude.value) == WaveLogic.Draw.Pattern) 1f else 0f }
                    .drawWithContent {
                        val bar = BarGeometry(this, true, shown())
                        if (bar.playedTo > 0f) clipRect(right = bar.playedTo) { this@drawWithContent.drawContent() }
                    },
            ) {
                // The pattern, recorded once per size and colour; a step only changes translationX.
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { translationX = phase.floatValue * WaveLogic.patternWidth(Wavelength.toPx()) }
                        .drawWithCache {
                            val tile = wavePattern(
                                width = WaveLogic.patternWidth(Wavelength.toPx()),
                                amplitude = Amplitude.toPx(),
                                stroke = StrokeWidth.toPx(),
                                color = active,
                            )
                            onDrawBehind {
                                val w = tile.width.toFloat()
                                val top = size.height / 2 - tile.height / 2f
                                // From one wavelength before the start, so a shift of up to one still covers it.
                                var x = -w
                                while (x < size.width) {
                                    drawImage(tile, Offset(x, top))
                                    x += w
                                }
                            }
                        },
                )
            }
        }
    }
}

/** Where the bar's parts are for the shown fraction. */
private class BarGeometry(scope: DrawScope, known: Boolean, fraction: Float) {
    val stroke = with(scope) { StrokeWidth.toPx() }
    val mid = scope.size.height / 2
    val start = stroke / 2
    val end = scope.size.width - stroke / 2
    val x = if (known) start + (end - start) * fraction.coerceIn(0f, 1f) else start
    private val gap = with(scope) { Gap.toPx() }
    private val thumbW = with(scope) { ThumbWidth.toPx() }
    val trackFrom = if (known) x + thumbW / 2 + gap else start
    val playedTo = x - thumbW / 2 - gap
}

/** The played part as a path, for the short spring between flat and full height. */
private fun DrawScope.drawWavePath(path: Path, bar: BarGeometry, amp: Float, phase: Float, color: Color) {
    val wave = WaveLogic.patternWidth(Wavelength.toPx()).toFloat()
    val shift = phase * wave
    val twoPi = 2f * PI.toFloat()
    path.reset()
    var px = bar.start
    path.moveTo(px, bar.mid + amp * sin(((px - shift) / wave) * twoPi))
    val step = 2.dp.toPx()
    while (px < bar.playedTo) {
        px = (px + step).coerceAtMost(bar.playedTo)
        path.lineTo(px, bar.mid + amp * sin(((px - shift) / wave) * twoPi))
    }
    drawPath(path, color, style = Stroke(width = bar.stroke, cap = StrokeCap.Round))
}

/**
 * One wavelength of the wave, drawn once into a small bitmap (about 110 by 25 pixels): a sine that
 * starts and ends at the same height and slope, so copies side by side join without a seam.
 */
private fun wavePattern(width: Int, amplitude: Float, stroke: Float, color: Color): ImageBitmap {
    val height = ceil(2 * amplitude + stroke + 2f).toInt().coerceIn(1, 512)
    val bitmap = createBitmap(width, height)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        this.color = color.toArgb()
    }
    val mid = height / 2f
    val twoPi = 2f * PI.toFloat()
    val path = android.graphics.Path()
    // A wavelength either side as well, so the stroke's edges at the seams are those of a longer line.
    var x = -width.toFloat()
    path.moveTo(x, mid + amplitude * sin((x / width) * twoPi))
    while (x < 2 * width) {
        x += 1f
        path.lineTo(x, mid + amplitude * sin((x / width) * twoPi))
    }
    canvas.drawPath(path, paint)
    return bitmap.asImageBitmap()
}
