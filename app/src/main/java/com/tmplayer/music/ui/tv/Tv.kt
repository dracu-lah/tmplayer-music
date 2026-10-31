package com.tmplayer.music.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.tmplayer.music.platform.FormFactor
import com.tmplayer.music.platform.TvLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The TV side of the one APK: the cached "is this a TV" answer, the safe frame against overscan,
 * the focus ring every clickable draws on a TV, and the small focus memory that puts the D-pad back
 * where it was after a page closes. A phone gets none of it: every piece checks [LocalTv] first.
 */

/** [FormFactor.isTv] with Android's signals: the UI mode type, leanback, and Amazon's Fire TV feature. */
fun isTv(context: Context): Boolean = FormFactor.isTv {
    val app = context.applicationContext
    val pm = app.packageManager
    val ui = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    FormFactor.Signals(
        television = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
        leanback = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false),
        fireTv = runCatching { pm.hasSystemFeature(FIRE_TV_FEATURE) }.getOrDefault(false),
    )
}

private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

/** True on a TV: the larger type, the safe frame, the focus ring, top tabs, no touch-only controls. */
val LocalTv = staticCompositionLocalOf { false }

/**
 * The app inside the TV's safe area ([TvLayout.safeMargins]), on the page's black so the margin
 * reads as part of the screen. On a phone it is [content] unchanged.
 */
@Composable
fun TvFrame(content: @Composable () -> Unit) {
    if (!LocalTv.current) {
        content()
        return
    }
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val (h, v) = with(density) { TvLayout.safeMargins(window.width.toDp().value, window.height.toDp().value) }
    // Focus lost with nothing to take it (the focused row was removed, a list reloaded): after a
    // moment the current screen's [FocusRescue.target] takes it, so the D-pad is never left nowhere.
    var hasFocus by remember { mutableStateOf(false) }
    var lost by remember { mutableIntStateOf(0) }
    LaunchedEffect(lost) {
        if (lost == 0) return@LaunchedEffect
        delay(RESCUE_WAIT_MS)
        if (!hasFocus) FocusRescue.target?.let { requestFocusSoon(it) { hasFocus } }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onFocusChanged {
                if (hasFocus && !it.hasFocus) lost++
                hasFocus = it.hasFocus
            }
            .focusGroup(),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = h.dp, vertical = v.dp)) { content() }
    }
}

/** Where focus goes when it is lost on a TV ([TvFrame]): the shell sets its page or tab here. */
object FocusRescue {
    @Volatile
    var target: FocusRequester? = null
}

private const val RESCUE_WAIT_MS = 250L

/**
 * The default indication on a TV: the theme's ripple (a press still shows) plus, while the item has
 * D-pad focus, a light wash and a ring inside its bounds. Material's own buttons and chips keep the
 * ripple's focus layer, made stronger on a TV in the theme.
 */
class FocusRingIndication(
    private val ripple: IndicationNodeFactory,
    private val color: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, ripple.create(interactionSource), color)

    override fun equals(other: Any?): Boolean =
        other is FocusRingIndication && other.ripple == ripple && other.color == color

    override fun hashCode(): Int = 31 * ripple.hashCode() + color.hashCode()
}

private class FocusRingNode(
    private val source: InteractionSource,
    ripple: DelegatableNode,
    private val color: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init {
        delegate(ripple)
    }

    override fun onAttach() {
        coroutineScope.launch {
            var count = 0
            source.interactions.collect {
                when (it) {
                    is FocusInteraction.Focus -> count++
                    is FocusInteraction.Unfocus -> count = (count - 1).coerceAtLeast(0)
                }
                val now = count > 0
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = RING_WIDTH.toPx()
        val radius = CornerRadius(RING_CORNER.toPx())
        drawRoundRect(color.copy(alpha = WASH_ALPHA), cornerRadius = radius)
        drawRoundRect(
            color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
    }
}

private val RING_WIDTH = 3.dp
private val RING_CORNER = 12.dp
private const val WASH_ALPHA = 0.10f

/**
 * Where the D-pad was in each list, so that closing a page puts it back on the row that opened it
 * and never at the top of a long list. A screen provides a scope name ([LocalFocusScope]); its rows
 * mark themselves with [rememberedFocus]. Holds one key per scope, at most [MAX_SCOPES].
 */
object FocusMemory {
    private val last = LinkedHashMap<String, Any>()

    /** The scope a page close asked to restore, until a row there takes it or the shell gives up. */
    var pending by mutableStateOf<String?>(null)
        private set

    fun remember(scope: String, key: Any) {
        last.remove(scope)
        last[scope] = key
        while (last.size > MAX_SCOPES) last.remove(last.keys.first())
    }

    fun restore(scope: String) {
        pending = scope.takeIf { it in last }
    }

    /** True once, for the row in [scope] keyed [key] that should take focus back. */
    fun take(scope: String, key: Any): Boolean {
        if (pending != scope || last[scope] != key) return false
        pending = null
        return true
    }

    fun giveUp() {
        pending = null
    }

    private const val MAX_SCOPES = 32
}

/** The name of the list the rows below belong to, for [FocusMemory]; null means no memory. */
val LocalFocusScope = compositionLocalOf<String?> { null }

/**
 * Marks a row for [FocusMemory]: its focus is remembered under [key], and it takes focus back when
 * its page is shown again. Place it before the row's clickable. Nothing on a phone.
 */
@Composable
fun Modifier.rememberedFocus(key: Any): Modifier {
    if (!LocalTv.current) return this
    val scope = LocalFocusScope.current ?: return this
    val requester = remember { FocusRequester() }
    val pending = FocusMemory.pending
    LaunchedEffect(scope, key, pending) {
        if (pending == scope && FocusMemory.take(scope, key)) requestFocusSoon(requester)
    }
    return this.focusRequester(requester).onFocusChanged { if (it.hasFocus) FocusMemory.remember(scope, key) }
}

/**
 * Asks [requester] for focus over the next few frames: the target may not be laid out yet (a page
 * whose rows load after its first frame). Gives up by itself after about a second.
 */
suspend fun requestFocusSoon(requester: FocusRequester, isDone: () -> Boolean = { false }): Boolean {
    repeat(FOCUS_TRIES) {
        if (isDone()) return true
        try {
            if (requester.requestFocus()) return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not attached yet: try again on a later frame.
        }
        delay(FOCUS_RETRY_MS)
    }
    return false
}

private const val FOCUS_TRIES = 20
private const val FOCUS_RETRY_MS = 50L

/**
 * A focus ring around a Material component (a button, a chip, an icon button) on a TV, drawn just
 * outside its [shape] while anything in it has focus: their own focus layer is a light tint, too
 * faint from across a room. Put it in the component's `modifier`. Nothing on a phone.
 */
@Composable
fun Modifier.tvFocusRing(shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape): Modifier {
    if (!LocalTv.current) return this
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface
    return this
        .onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val stroke = RING_WIDTH.toPx()
            val gap = RING_GAP.toPx()
            val grown = Size(size.width + 2 * gap, size.height + 2 * gap)
            val outline = shape.createOutline(grown, layoutDirection, this)
            translate(-gap, -gap) { drawOutline(outline, color, style = Stroke(stroke)) }
        }
}

private val RING_GAP = 3.dp
