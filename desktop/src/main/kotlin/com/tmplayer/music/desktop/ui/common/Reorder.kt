package com.tmplayer.music.desktop.ui.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Drag to reorder for a list whose rows all have the same height (the queue, a playlist): the grip
 * of a row follows the pointer, the row shows where it is, and on release it moves to the row it
 * is over. The same move is on the keyboard, Alt+Up and Alt+Down on a focused row, and in each
 * row's menu (Move up, Move down), so nothing is drag only.
 */
class DragReorder {
    /** The row being dragged, -1 for none. */
    var from by mutableIntStateOf(-1)
        private set

    /** How far the pointer has moved since the grab, in pixels, up negative. */
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(index: Int) {
        from = index
        offset = 0f
    }

    fun drag(dy: Float) {
        offset += dy
    }

    fun cancel() {
        from = -1
        offset = 0f
    }

    /** Ends the drag; the row index it landed on, or null when it did not move. */
    fun finish(rowHeightPx: Float, count: Int): Pair<Int, Int>? {
        val start = from
        val moved = offset
        cancel()
        if (start < 0) return null
        val to = target(start, moved, rowHeightPx, count)
        return if (to != start) start to to else null
    }

    companion object {
        /** The row a row grabbed at [start] lands on after the pointer moved [dy] pixels, held inside the list. */
        fun target(start: Int, dy: Float, rowHeightPx: Float, count: Int): Int {
            if (count <= 0 || rowHeightPx <= 0f || !dy.isFinite()) return start
            return (start + (dy / rowHeightPx).roundToInt()).coerceIn(0, count - 1)
        }
    }
}

/** The grip: dragging it moves row [index] of [count] by [onMove] (from, to). */
fun Modifier.dragGrip(state: DragReorder, index: Int, count: Int, rowHeightPx: Float, onMove: (Int, Int) -> Unit): Modifier =
    pointerInput(index, count, rowHeightPx) {
        detectDragGestures(
            onDragStart = { state.start(index) },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
            onDragEnd = { state.finish(rowHeightPx, count)?.let { (a, b) -> onMove(a, b) } },
            onDragCancel = { state.cancel() },
        )
    }

/** The row follows the pointer while it is the one dragged. */
fun Modifier.draggedRow(state: DragReorder, index: Int): Modifier {
    val dragging = state.from == index
    return this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer { translationY = if (dragging) state.offset else 0f }
        .alpha(if (dragging) 0.85f else 1f)
}

/** Alt+Up and Alt+Down on a focused row move it one place; true when the key was one of them. */
fun Modifier.moveWithKeys(index: Int, count: Int, onMove: (Int, Int) -> Unit): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || !event.isAltPressed) return@onKeyEvent false
    when (event.key) {
        Key.DirectionUp -> if (index > 0) {
            onMove(index, index - 1)
            true
        } else {
            false
        }
        Key.DirectionDown -> if (index < count - 1) {
            onMove(index, index + 1)
            true
        } else {
            false
        }
        else -> false
    }
}
