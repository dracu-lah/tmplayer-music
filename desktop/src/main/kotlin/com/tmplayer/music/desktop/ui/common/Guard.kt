package com.tmplayer.music.desktop.ui.common

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.tmplayer.music.data.Failures
import com.tmplayer.music.platform.JobFailures
import kotlinx.coroutines.CancellationException

/**
 * Runs [block], and says what went wrong in a line through [onError] instead of letting a failure
 * out of a click. A cancel passes through; every other failure is logged and shown.
 */
suspend fun guarded(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        JobFailures.handle("Desktop", e)
        onError(Failures.humanise(e))
    }
}

/** Takes the keyboard focus once, when it first shows: the safe button of a question, the field of a dialog. */
fun Modifier.autoFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    focusRequester(requester)
}

/** Enter in a field does [action]. */
fun Modifier.onEnter(action: () -> Unit): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        action()
        true
    } else {
        false
    }
}
