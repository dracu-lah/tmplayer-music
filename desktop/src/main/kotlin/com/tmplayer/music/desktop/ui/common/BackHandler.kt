package com.tmplayer.music.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Escape is the desktop's Back: the phone's `BackHandler` over a stack of handlers, the newest
 * (the one on top of the screen) first. The window's key handler calls [BackKeys.pressed].
 */
object BackKeys {
    private val handlers = ArrayList<() -> Unit>()

    /** Runs the newest handler; false when none is registered, so the key can mean something else. */
    fun pressed(): Boolean {
        val top = synchronized(handlers) { handlers.lastOrNull() } ?: return false
        top()
        return true
    }

    internal fun push(handler: () -> Unit) = synchronized(handlers) { handlers.add(handler); Unit }

    internal fun remove(handler: () -> Unit) = synchronized(handlers) { handlers.remove(handler); Unit }

    /** For the tests. */
    internal fun clear() = synchronized(handlers) { handlers.clear() }
}

/** Calls [onBack] when Escape is pressed and this is the newest handler. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler: () -> Unit = { current.value() }
        BackKeys.push(handler)
        onDispose { BackKeys.remove(handler) }
    }
}
