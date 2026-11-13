package com.tmplayer.music.ui.tv

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.launch

/*
 * Remote and keyboard input for the TV: the second action on a row (a hold of OK, or the Menu or
 * Info key), the seek and scroll keys, the first focus of a dialog or sheet, and the digit pad that
 * stands in for a keyboard. The decisions are plain functions of the key, so they are unit tested
 * without a screen; the modifiers only wire them to Compose. A phone gets none of it.
 */

/** What [HoldKeys] decided about one key event: whether it is used up, and whether a hold fired. */
data class HoldOutcome(val consumed: Boolean, val hold: Boolean)

/**
 * OK held, or the Menu or Info key: "tell me more about this", the TV's long press. Written from key
 * events because a compose long click does not fire for a D-pad hold. OK's first repeat arrives at
 * the system's own long press time, so no timer is needed; the release that follows a hold is used
 * up, so the hold never also counts as a click. Menu and Info fire once, on the way down.
 */
class HoldKeys {
    private var held = false

    fun onKey(key: Key, type: KeyEventType, repeat: Int): HoldOutcome {
        if (key in MORE_KEYS) {
            val fire = type == KeyEventType.KeyDown && repeat == 0
            return HoldOutcome(consumed = type == KeyEventType.KeyDown || type == KeyEventType.KeyUp, hold = fire)
        }
        if (key !in OK_KEYS) return NOTHING
        return when (type) {
            KeyEventType.KeyDown -> {
                if (repeat == 0) {
                    held = false
                    NOTHING
                } else if (!held) {
                    held = true
                    HoldOutcome(consumed = true, hold = true)
                } else {
                    HoldOutcome(consumed = true, hold = false)
                }
            }
            KeyEventType.KeyUp -> if (held) {
                held = false
                HoldOutcome(consumed = true, hold = false)
            } else {
                NOTHING
            }
            else -> NOTHING
        }
    }

    companion object {
        val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
        val MORE_KEYS = setOf(Key.Menu, Key.Info)
        private val NOTHING = HoldOutcome(consumed = false, hold = false)
    }
}

/**
 * The row's second action on a TV: hold OK, or press Menu or Info ([HoldKeys]). Put it before the
 * row's click handler. Nothing on a phone, where the click handler's own long press does it.
 */
@Composable
fun Modifier.tvHold(onHold: (() -> Unit)?): Modifier {
    if (!LocalTv.current || onHold == null) return this
    val keys = remember { HoldKeys() }
    val action = rememberUpdatedState(onHold)
    return this.onPreviewKeyEvent { e ->
        val outcome = keys.onKey(e.key, e.type, e.nativeKeyEvent.repeatCount)
        if (outcome.hold) action.value()
        outcome.consumed
    }
}

/** How far the seek keys move: a tap, and a hold that speeds up the longer it is held. */
object SeekKeys {
    /** Milliseconds of the track per key event: [repeat] is the key's repeat count (0 for the tap). */
    fun stepMs(repeat: Int, durationMs: Long): Long {
        val base = when {
            repeat <= 0 -> TAP_MS
            repeat < SLOW_REPEATS -> SLOW_MS
            repeat < MEDIUM_REPEATS -> MEDIUM_MS
            else -> maxOf(FAST_MS, durationMs.coerceAtLeast(0) / FAST_DIVISOR)
        }
        return base
    }

    /** The fraction (0 to 1) after one key event from [from], backwards when [forward] is false. */
    fun next(from: Float, forward: Boolean, repeat: Int, durationMs: Long): Float {
        if (durationMs <= 0) return from.coerceIn(0f, 1f)
        val delta = stepMs(repeat, durationMs).toDouble() / durationMs
        val signed = if (forward) delta else -delta
        return (from + signed).toFloat().coerceIn(0f, 1f)
    }

    /** True for the keys that move the bar. */
    fun direction(key: Key): Boolean? = when (key) {
        Key.DirectionRight, Key.MediaFastForward, Key.MediaStepForward -> true
        Key.DirectionLeft, Key.MediaRewind, Key.MediaStepBackward -> false
        else -> null
    }

    /** How long the keys are quiet before the seek is made, so a run of taps is one seek. */
    const val SETTLE_MS = 400L

    private const val TAP_MS = 10_000L
    private const val SLOW_MS = 2_000L
    private const val MEDIUM_MS = 5_000L
    private const val FAST_MS = 15_000L
    private const val FAST_DIVISOR = 150
    private const val SLOW_REPEATS = 10
    private const val MEDIUM_REPEATS = 30
}

/**
 * Up and Down scroll [state] while it can, a page's worth at a time, and let the D-pad leave once
 * its end is reached. For text that is read, not picked from: a long lyrics page. The caller makes
 * the same node focusable. Nothing on a phone.
 */
@Composable
fun Modifier.tvScrollKeys(state: ScrollableState): Modifier {
    if (!LocalTv.current) return this
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { SCROLL_STEP.toPx() }
    return this.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val down = when (e.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return@onKeyEvent false
        }
        val can = if (down) state.canScrollForward else state.canScrollBackward
        if (can) scope.launch { state.animateScrollBy(if (down) step else -step) }
        can
    }
}

private val SCROLL_STEP = 160.dp

/**
 * Takes the D-pad when it appears, on a TV: for the safe button of a dialog (Cancel), so a stray OK
 * never confirms. Gives up by itself after about a second. Nothing on a phone.
 */
@Composable
fun Modifier.tvAutoFocus(): Modifier {
    if (!LocalTv.current) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusSoon(requester) }
    return this.focusRequester(requester)
}

/**
 * A modal bottom sheet that is reachable with the D-pad: on a TV it opens at full height (no half
 * state whose lower rows nothing can bring into view) and the first control in it takes focus. Back
 * closes it. A phone gets the plain sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val tv = LocalTv.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = tv),
    ) {
        if (!tv) {
            content()
            return@ModalBottomSheet
        }
        val entry = remember { FocusRequester() }
        // A focus group: asking it for focus lands on its first control, whatever that turns out to be.
        Box(Modifier.focusRequester(entry).focusGroup()) { content() }
        LaunchedEffect(Unit) { requestFocusSoon(entry) }
    }
}

/**
 * Hands the D-pad to the row keyed [key] in the list [scope], once its page shows it: for the
 * neighbour of a row that was just removed, which nothing can ask for focus by itself.
 */
fun FocusMemory.handOver(scope: String?, key: Any) {
    if (scope == null) return
    remember(scope, key)
    restore(scope)
}

/**
 * The row to put the D-pad on after the row at [removed] left a list that now holds [newSize]: the
 * one that took its place, else the one before it, else none (the list is empty).
 */
fun neighbourAfterRemoval(removed: Int, newSize: Int): Int? = when {
    newSize <= 0 -> null
    removed < 0 -> 0
    removed >= newSize -> newSize - 1
    else -> removed
}

/**
 * The digit pad: 1 to 9, then Delete, 0 and Done, in a grid the D-pad crosses. It stands in for the
 * system keyboard on a TV, where a box may have none, for the phone number and the sign in code.
 * [onDone] is the pad's own Done (send the number); [doneLabel] names it.
 */
@Composable
fun TvKeypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    doneLabel: String,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    Column(modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 3) {
                    val digit = ('1' + row * 3 + col)
                    val first = if (row == 0 && col == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                    PadKey(first, onClick = { onDigit(digit) }) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey(Modifier, onClick = onDelete, description = L.commonBack) {
                Icon(MusicIcons.Back, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            PadKey(Modifier, onClick = { onDigit('0') }) { Text("0", style = MaterialTheme.typography.titleLarge) }
            PadKey(Modifier, onClick = onDone, description = doneLabel) {
                Icon(MusicIcons.Check, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PadKey(
    modifier: Modifier,
    onClick: () -> Unit,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .size(width = 72.dp, height = 52.dp)
            .tvFocusRing(androidx.compose.foundation.shape.CircleShape)
            .semantics { if (description != null) contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) { content() }
}

/** The digits left after one pad press: [typed] with [digit] added, at most [max] long. */
object PadEntry {
    fun add(typed: String, digit: Char, max: Int): String =
        if (digit in '0'..'9' && typed.length < max) typed + digit else typed

    fun delete(typed: String): String = typed.dropLast(1)
}
