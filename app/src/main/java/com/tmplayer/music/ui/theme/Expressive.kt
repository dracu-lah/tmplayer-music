package com.tmplayer.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Material 3 Expressive's pressable controls, drawn here because material3 1.4.0 keeps its own
// (IconButtonShapes, ToggleButton, ButtonGroup) internal and 1.5.0 needs minSdk 24. Every control is
// a true circle or pill at all times (user, 2026-10-30: no rounded squares or squircles): a press is
// felt as a small scale, and "on" as a colour change, never as a corner change. Each one animates
// only when its state or press changes, with the theme's spring motion, and is still the rest of the time.

/** How far a held control shrinks, so a press is felt before it is let go. */
private const val PRESSED_SCALE = 0.92f

/** Shrinks a little while [pressed], drawn in the graphics layer only (no relayout, no recomposition per frame). */
@Composable
fun Modifier.pressScale(pressed: Boolean): Modifier {
    val scale = animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, MusicMotion.current.fastSpatial(), label = "press")
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A filled button, always a pill ([Pill]; a circle when it is as wide as it is tall), that shrinks a
 * little while pressed. [on] is kept for callers that change the icon (play and pause): the shape
 * never changes with it. Size it with [modifier]; it is never under 48 dp to touch.
 */
@Composable
fun MorphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") on: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize().pressScale(pressed),
        enabled = enabled,
        shape = Pill,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.38f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        interactionSource = source,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A toggle with a label (shuffle, repeat): a pill, tonal when off and filled when on, the colour
 * springing between the two; it shrinks a little while pressed. 48 dp tall.
 */
@Composable
fun MorphToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val effects = MusicMotion.current.defaultEffects<Color>()
    val container by animateColorAsState(if (checked) colors.primary else colors.secondaryContainer, effects, label = "container")
    val content2 by animateColorAsState(if (checked) colors.onPrimary else colors.onSecondaryContainer, effects, label = "content")
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.defaultMinSize(minHeight = 48.dp).pressScale(pressed),
        shape = Pill,
        color = container,
        contentColor = content2,
        interactionSource = source,
    ) {
        Row(
            Modifier.padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * An icon toggle (lyrics, sleep timer, favourite): a 48 dp circle, with no container when off and a
 * tonal one when on; it shrinks a little while pressed.
 */
@Composable
fun MorphIconToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val effects = MusicMotion.current.defaultEffects<Color>()
    val container by animateColorAsState(if (checked) colors.secondaryContainer else Color.Transparent, effects, label = "container")
    val tint by animateColorAsState(if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant, effects, label = "tint")
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.size(48.dp).pressScale(pressed),
        shape = CircleShape,
        color = container,
        contentColor = tint,
        interactionSource = source,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A row of connected buttons where the one being pressed widens and its neighbours give way, the
 * Material 3 Expressive button group (a circle widens into a pill while held, never a squarer shape).
 * Give each child [ButtonGroupScope.groupItem] with its rest
 * width and the interaction source of its button.
 */
@Composable
fun ExpressiveButtonGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable ButtonGroupScope.() -> Unit,
) {
    val scope = remember { ButtonGroupScope() }
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { scope.content() }
}

@Stable
class ButtonGroupScope internal constructor() {
    private var pressed by mutableStateOf<Any?>(null)

    /** This item rests at [width]; pressed it grows by 15%, and the others shrink while it is held. */
    @Composable
    fun Modifier.groupItem(key: Any, source: MutableInteractionSource, width: Dp): Modifier {
        val held by source.collectIsPressedAsState()
        LaunchedEffect(held) {
            if (held) pressed = key else if (pressed == key) pressed = null
        }
        val target = when (pressed) {
            null -> 1f
            key -> 1.15f
            else -> 0.93f
        }
        val factor = animateFloatAsState(target, MusicMotion.current.fastSpatial(), label = "width")
        // Read in the layout step only: a press re-measures this row, it never recomposes it.
        return this.layout { measurable, constraints ->
            val w = (width.toPx() * factor.value).roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w.coerceAtLeast(constraints.minWidth)))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
}
