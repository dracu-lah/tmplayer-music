package com.tmplayer.music.desktop

import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.diagnostics.LogsHost
import com.tmplayer.music.desktop.ui.AppRoot
import com.tmplayer.music.desktop.ui.common.BackKeys
import com.tmplayer.music.desktop.ui.common.HeldKeys
import com.tmplayer.music.desktop.ui.common.ListCommands
import com.tmplayer.music.desktop.ui.common.Shortcut
import com.tmplayer.music.desktop.ui.common.Shortcuts
import com.tmplayer.music.desktop.ui.common.ShellCommands
import com.tmplayer.music.desktop.ui.common.Typing
import com.tmplayer.music.desktop.ui.theme.MusicTheme
import com.tmplayer.music.i18n.L
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** The smallest the window goes: the layout is made for 900 by 600 and up. */
private val MIN_WINDOW = Dimension(900, 600)

fun main() {
    DesktopApp.start()
    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 760.dp))
        Window(
            onCloseRequest = {
                DesktopApp.stop()
                exitApplication()
            },
            state = state,
            title = L.appName,
            onPreviewKeyEvent = ::handleKey,
        ) {
            DisposableEffect(window) {
                window.minimumSize = MIN_WINDOW
                val listener = object : WindowAdapter() {
                    override fun windowActivated(e: WindowEvent?) = Library.setForeground(true)

                    // A key released while another window had the keyboard must not stay "held".
                    override fun windowDeactivated(e: WindowEvent?) {
                        HeldKeys.release()
                        Library.setForeground(false)
                    }
                }
                window.addWindowListener(listener)
                Library.setForeground(window.isActive)
                onDispose { window.removeWindowListener(listener) }
            }
            // Context menus in the app's black, not the toolkit's white.
            CompositionLocalProvider(LocalContextMenuRepresentation provides DarkDefaultContextMenuRepresentation) {
                MusicTheme {
                    AppRoot()
                    LogsHost()
                }
            }
        }
    }
}

/**
 * The window's keyboard, ahead of whatever has focus: Escape is Back, then the player's shortcuts
 * ([Shortcuts]) once signed in. Typing in a field keeps its own keys. Returns true when it used the key.
 */
private fun handleKey(event: KeyEvent): Boolean {
    HeldKeys.update(ctrl = event.isCtrlPressed || event.isMetaPressed, shift = event.isShiftPressed)
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) return BackKeys.pressed()
    // Nothing but sign in before then: a space typed into the password is a space.
    if (Td.auth.value !is AuthState.Ready) return false
    val shortcut = Shortcuts.of(
        key = event.key,
        ctrl = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
        typing = Typing.active,
    ) ?: return false
    val player = DesktopApp.player
    when (shortcut) {
        Shortcut.PlayPause -> player.togglePlay()
        Shortcut.SeekBack -> player.seekBy(-Shortcuts.SEEK_MS)
        Shortcut.SeekForward -> player.seekBy(Shortcuts.SEEK_MS)
        Shortcut.Previous -> player.previous()
        Shortcut.Next -> player.next()
        Shortcut.VolumeUp -> player.setVolume(player.ui.value.volume + Shortcuts.VOLUME_STEP)
        Shortcut.VolumeDown -> player.setVolume(player.ui.value.volume - Shortcuts.VOLUME_STEP)
        Shortcut.Search -> ShellCommands.requestSearch()
        Shortcut.SelectAll -> ListCommands.requestSelectAll()
    }
    return true
}
