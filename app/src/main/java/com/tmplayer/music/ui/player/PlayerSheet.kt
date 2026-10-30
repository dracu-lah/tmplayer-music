package com.tmplayer.music.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.ui.theme.MorphButton
import com.tmplayer.music.ui.theme.MusicMotion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.i18n.L
import com.tmplayer.music.player.PlaybackErrors
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerState
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.theme.Pill
import com.tmplayer.music.ui.theme.ShapeRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The mini-player card's height, and its gap to the screen edges and the navigation bar. */
internal val MiniHeight = 72.dp
internal val MiniMargin = 8.dp
private val MiniSideInset = 12.dp

/** Where the mini-player's cover sits inside the sheet, for the cover's grow-into-Now-Playing motion. */
internal val MiniCoverInset = MiniSideInset + 12.dp
internal val MiniCoverSize = 48.dp

/** Shared between [PlayerSheetHost], [PlayerSheetSpacer] and [HidePlayerSheet]. */
@Stable
class PlayerSheetController internal constructor() {
    internal var hideRequests by mutableIntStateOf(0)

    /** The mini-player is on screen: lists leave room for it at the bottom. */
    var showing by mutableStateOf(false)
        internal set

    /**
     * Now Playing is fully open and opaque: the library under it is not drawn (it would be
     * composited again on every frame of the seek bar's wave for nothing).
     */
    internal var covering by mutableStateOf(false)

    /** Counts requests to open Now Playing from outside the sheet (the Continue card on Home). */
    internal var expandRequests by mutableIntStateOf(0)

    /** Opens Now Playing, as a tap on the mini-player does. Nothing happens without a queue. */
    fun expand() {
        expandRequests++
    }
}

val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetController?> { null }

/**
 * Hosts the library and, over it, the player: one surface that is the mini-player when collapsed
 * and Now Playing when expanded. Mount it once around the library.
 */
@Composable
fun PlayerSheetHost(content: @Composable () -> Unit) {
    val controller = remember { PlayerSheetController() }
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    val hasQueue = state.count > 0 && state.item != null
    val visible = hasQueue && controller.hideRequests == 0
    SideEffect { controller.showing = visible }
    // Saved across rotation and process death: a reopened app shows the player as it was left.
    val sheet = rememberSaveable(saver = AnchoredDraggableState.Saver()) { AnchoredDraggableState(SheetValue.Collapsed) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasQueue) {
        if (!hasQueue) {
            // The queue ran out (or was cleared): the next track played starts as the mini-player.
            queueOpen = false
            runCatching { sheet.snapTo(SheetValue.Collapsed) }
        }
    }
    // The sheet is drawn on its own background, not inside a Surface, so its text and icons take the
    // theme's on-surface colour from here (left unset they default to black, unreadable in the dark).
    CompositionLocalProvider(
        LocalPlayerSheet provides controller,
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Read only while drawing: hiding the library is a draw change, not a recomposition.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (visible && controller.covering) 0f else 1f }) {
                content()
            }
            if (visible) PlayerSheet(state, sheet, controller, onQueue = { queueOpen = true })
        }
    }
    if (visible && queueOpen) QueueSheet(onDismiss = { queueOpen = false })
}

/** Room for the mini-player under a list (a Scaffold's bottom bar). Nothing when there is no player. */
@Composable
fun PlayerSheetSpacer() {
    val controller = LocalPlayerSheet.current ?: return
    if (controller.showing) Spacer(Modifier.navigationBarsPadding().height(MiniHeight + MiniMargin * 2))
}

/** While this is on screen (a full screen picker with its own bottom buttons) the player stays out of the way. */
@Composable
fun HidePlayerSheet() {
    val controller = LocalPlayerSheet.current ?: return
    DisposableEffect(controller) {
        controller.hideRequests++
        onDispose { controller.hideRequests-- }
    }
}

@Composable
private fun PlayerSheet(
    state: PlayerState,
    sheet: AnchoredDraggableState<SheetValue>,
    controller: PlayerSheetController,
    onQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spatial: AnimationSpec<Float> = MusicMotion.current.defaultSpatial()
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val miniPx = with(density) { MiniHeight.toPx() }
        val marginPx = with(density) { MiniMargin.toPx() }
        val sidePx = with(density) { MiniSideInset.toPx() }
        val collapsedTop = (height - navBottom - marginPx - miniPx).coerceAtLeast(0f)
        val allowDismiss = SheetLogic.allowDismiss(state.isPlaying, state.buffering, sheet.settledValue)
        val anchors = remember(height, collapsedTop, allowDismiss) {
            val map = SheetLogic.anchors(height, collapsedTop, allowDismiss)
            DraggableAnchors { map.forEach { (value, at) -> value at at } }
        }
        SideEffect {
            val target = sheet.targetValue.takeIf { anchors.hasPositionFor(it) } ?: SheetValue.Collapsed
            sheet.updateAnchors(anchors, target)
        }

        val progress = { SheetLogic.progress(sheet.offset, collapsedTop) }
        val showMini by remember(collapsedTop) { derivedStateOf { progress() < 0.5f } }
        val showFull by remember(collapsedTop) { derivedStateOf { progress() > 0f } }
        val fullyOpen by remember(collapsedTop) { derivedStateOf { progress() >= 0.99f } }
        var back by remember { mutableFloatStateOf(0f) }
        val covering by remember(collapsedTop) { derivedStateOf { progress() >= 1f && back == 0f } }
        SideEffect { controller.covering = covering }
        DisposableEffect(controller) { onDispose { controller.covering = false } }
        val expandedOrGoing = sheet.targetValue == SheetValue.Expanded || sheet.settledValue == SheetValue.Expanded
        // A request from outside (the Continue card); the count only moves on a tap, so this is idle.
        var handledExpand by remember { mutableIntStateOf(controller.expandRequests) }
        LaunchedEffect(controller.expandRequests) {
            if (controller.expandRequests != handledExpand) {
                handledExpand = controller.expandRequests
                runCatching { sheet.animateTo(SheetValue.Expanded, spatial) }
            }
        }

        // Swiped away (paused only): stop and clear. The host snaps the sheet back once the queue is gone.
        LaunchedEffect(sheet.settledValue) {
            if (sheet.settledValue == SheetValue.Dismissed) {
                PlayerConnection.clear()
                delay(1_500)
                if (PlayerConnection.state.value.count > 0) runCatching { sheet.animateTo(SheetValue.Collapsed, spatial) }
            }
        }
        // Left mid-drag (app sent to the background, a call): rest on the nearest anchor, no animation.
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            back = 0f
            val map = SheetLogic.anchors(height, collapsedTop, allowDismiss)
            if (SheetLogic.isBetweenAnchors(sheet.offset, map)) {
                scope.launch { runCatching { sheet.snapTo(SheetLogic.nearest(sheet.offset, map)) } }
            }
        }
        // Back collapses Now Playing, following the predictive back gesture; Back again leaves the app.
        PredictiveBackHandler(enabled = expandedOrGoing) { events ->
            try {
                events.collect { back = it.progress }
                back = 0f
                sheet.animateTo(SheetValue.Collapsed, spatial)
            } catch (e: CancellationException) {
                back = 0f
                throw e
            }
        }

        val anchorFling = AnchoredDraggableDefaults.flingBehavior(
            state = sheet,
            positionalThreshold = { distance -> distance * 0.4f },
            animationSpec = spatial,
        )
        // Below the mini-player only the distance counts (SheetLogic.settleBelowMini), never the speed.
        val fling = remember(anchorFling, collapsedTop, height, allowDismiss) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val at = sheet.offset
                    val target = SheetLogic.settleBelowMini(at, collapsedTop, height, allowDismiss)
                        ?.takeIf { sheet.anchors.hasPositionFor(it) }
                        ?: return with(anchorFling) { performFling(initialVelocity) }
                    var last = at
                    animate(at, sheet.anchors.positionOf(target), animationSpec = spatial) { value, _ ->
                        scrollBy(value - last)
                        last = value
                    }
                    return 0f
                }
            }
        }
        val nested = remember(sheet, fling) { SheetNestedScroll(sheet, fling) }
        val collapsedColor = colors.surfaceContainerHigh
        val openColor = colors.background
        val tint = rememberCoverTint(state.item)

        // The library dims as the sheet rises.
        Box(Modifier.fillMaxSize().drawBehind {
            val p = progress()
            if (p > 0f) drawRect(colors.scrim.copy(alpha = 0.5f * p))
        })

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (sheet.offset.takeIf { !it.isNaN() } ?: collapsedTop).roundToInt()) }
                .graphicsLayer {
                    val p = progress()
                    val scale = 1f - 0.08f * back
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.15f)
                    val radius = lerp(ShapeRadius.Card.toPx(), 0f, p) + back * ShapeRadius.Sheet.toPx()
                    shape = CardShape(inset = lerp(sidePx, 0f, p), height = lerp(miniPx, height, p), radius = radius)
                    // Fully open and still, the sheet is the whole window: no clip to pay for on every frame.
                    clip = p < 1f || back > 0f
                    shadowElevation = lerp(3.dp.toPx(), 0f, p)
                }
                .nestedScroll(nested)
                .anchoredDraggable(sheet, reverseDirection = false, orientation = Orientation.Vertical, flingBehavior = fling)
                .drawBehind {
                    val p = progress()
                    drawRect(lerp(collapsedColor, openColor, p))
                    // The cover's tint, only behind Now Playing.
                    if (tint != null && p > 0f) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to tint.copy(alpha = 0.5f * p),
                                0.75f to tint.copy(alpha = 0f),
                            ),
                        )
                    }
                },
        ) {
            if (showFull) {
                NowPlayingContent(
                    state = state,
                    progress = progress,
                    open = fullyOpen,
                    onCollapse = { scope.launch { sheet.animateTo(SheetValue.Collapsed, spatial) } },
                    onQueue = onQueue,
                )
            }
            if (showMini) {
                MiniPlayer(
                    state = state,
                    progress = progress,
                    canDismiss = allowDismiss,
                    onExpand = { scope.launch { sheet.animateTo(SheetValue.Expanded, spatial) } },
                    onDismiss = { scope.launch { sheet.animateTo(SheetValue.Dismissed, spatial) } },
                )
            }
        }
    }
}

/**
 * The card's visible part: inset from the sides and as tall as the sheet has grown, so the same
 * surface is a floating card when collapsed and the whole screen when open.
 */
private class CardShape(private val inset: Float, private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rect = Rect(inset, 0f, size.width - inset, height.coerceAtMost(size.height))
        return Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
    }
}

/**
 * Lets a list inside Now Playing (the lyrics) scroll first: the sheet only starts closing once the
 * list is at its top, and a fling that the list did not use settles the sheet.
 */
private class SheetNestedScroll(
    private val sheet: AnchoredDraggableState<SheetValue>,
    private val fling: FlingBehavior,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        // Finger moving up while the sheet is not fully open: open it before the list scrolls.
        return if (delta < 0 && source == NestedScrollSource.UserInput && !sheet.offset.isNaN() && sheet.offset > 0f) {
            Offset(0f, sheet.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (source == NestedScrollSource.UserInput && delta != 0f && !sheet.offset.isNaN()) {
            Offset(0f, sheet.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val v = available.y
        return if (v < 0 && !sheet.offset.isNaN() && sheet.offset > sheet.anchors.minPosition()) {
            settle(v)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // Only when the scroll moved the sheet: a lyrics fling that just hits the top leaves it open.
        if (!movedOffAnchor()) return Velocity.Zero
        settle(available.y)
        return available
    }

    private fun movedOffAnchor(): Boolean {
        val at = sheet.offset
        if (at.isNaN()) return false
        val nearest = sheet.anchors.closestAnchor(at) ?: return false
        return kotlin.math.abs(sheet.anchors.positionOf(nearest) - at) > 0.5f
    }

    private suspend fun settle(velocity: Float) {
        sheet.anchoredDrag {
            val scroll = object : ScrollScope {
                override fun scrollBy(pixels: Float): Float {
                    val from = sheet.offset
                    val to = (from + pixels).coerceIn(sheet.anchors.minPosition(), sheet.anchors.maxPosition())
                    dragTo(to)
                    return to - from
                }
            }
            with(fling) { scroll.performFling(velocity) }
        }
    }
}

/**
 * The collapsed sheet: a floating card with the cover, title and artist (or the last error), play or
 * pause and next, over a thin progress line that only moves when the position is read (twice a
 * second while playing). Tap or swipe up opens Now Playing; swipe sideways skips; swipe down while
 * paused stops and closes it (TalkBack has the same as an action).
 */
@Composable
internal fun MiniPlayer(
    state: PlayerState,
    progress: () -> Float,
    canDismiss: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
) {
    val error by PlaybackErrors.last.collectAsStateWithLifecycle()
    // Read only by the progress line while it draws: a tick redraws the line, not the card.
    val position = rememberPositionState(state)
    val title = state.metadata.title?.toString().orEmpty()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val dismissAction = L.nowplayingDismiss
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MiniSideInset)
            .height(MiniHeight)
            .graphicsLayer { alpha = (1f - progress() / 0.25f).coerceIn(0f, 1f) }
            .clickable(onClickLabel = L.nowplayingExpand, onClick = onExpand)
            .semantics {
                if (canDismiss) customActions = listOf(CustomAccessibilityAction(dismissAction) { onDismiss(); true })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when (SheetLogic.skipDirection(drag, threshold)) {
                            1 -> PlayerConnection.next()
                            -1 -> PlayerConnection.previous()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            },
    ) {
        Row(
            Modifier.fillMaxSize().graphicsLayer { translationX = drag / 3 }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(MiniCoverSize).clip(RoundedCornerShape(ShapeRadius.SmallCover))) {
                ItemCover(state.item, MiniCoverSize, title)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = error
                Text(
                    line ?: ArtistDisplay.of(state.metadata.artist?.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPauseButton(state, Modifier.size(48.dp), iconSize = 24.dp)
            IconButton(onClick = PlayerConnection::next, enabled = state.hasNext) {
                Icon(MusicIcons.Next, contentDescription = L.playerNext)
            }
        }
        if (state.durationMs > 0) {
            LinearProgressIndicator(
                progress = { WaveLogic.fraction(position.value, state.durationMs) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).fillMaxWidth().height(3.dp).clip(Pill),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/**
 * Play or pause, a filled circle ([MorphButton]) whose icon changes; its shape never does. While
 * the track waits for Telegram the icon gives way to a small spinner, which runs only while buffering.
 */
@Composable
internal fun PlayPauseButton(
    state: PlayerState,
    modifier: Modifier,
    iconSize: Dp,
    interactionSource: MutableInteractionSource? = null,
) {
    val playing = state.isPlaying
    val label = if (playing) L.playerPause else L.playerPlay
    MorphButton(
        onClick = PlayerConnection::toggle,
        modifier = modifier.semantics { contentDescription = label },
        on = playing,
        interactionSource = interactionSource,
    ) {
        if (state.buffering && iconSize >= 32.dp) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 3.dp,
            )
        } else {
            Icon(if (playing) MusicIcons.Pause else MusicIcons.Play, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}
