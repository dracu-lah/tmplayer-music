package com.tmplayer.music.desktop

import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.diagnostics.LogsHost
import com.tmplayer.music.desktop.os.MediaControls
import com.tmplayer.music.desktop.os.WindowMemory
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
import java.awt.SystemTray
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** The smallest the window goes: the layout is made for 900 by 600 and up. */
private val MIN_WINDOW = Dimension(900, 600)

fun main() {
    DesktopApp.start()
    application {
        // The size, place and maximised state of the last run, kept sane for the screens attached now.
        val state = remember { WindowMemory.load() }
        LaunchedEffect(state) { WindowMemory.follow(state) }
        var visible by remember { mutableStateOf(true) }
        val closeToTray by DesktopApp.closeToTray.collectAsState()
        // A tray the desktop has no place for would hide the app for good, so close to tray needs one.
        val trayOk = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val player = DesktopApp.player
        val playerUi by player.ui.collectAsState()
        val quit = {
            WindowMemory.save(state)
            DesktopApp.stop()
            exitApplication()
        }
        val trayIcon = remember { iconPainter("tray.png") }
        val windowIcon = remember { iconPainter("window.png") }
        if (trayOk) {
            Tray(
                icon = trayIcon,
                tooltip = playerUi.current?.let { "${it.title}, ${it.artist}".trimEnd(',', ' ') } ?: L.appName,
                onAction = { visible = true },
            ) {
                Item(L.desktopTrayShow, onClick = { visible = true })
                Separator()
                Item(if (playerUi.playing) L.playerPause else L.playerPlay, enabled = !playerUi.queue.isEmpty, onClick = player::togglePlay)
                Item(L.playerPrevious, enabled = !playerUi.queue.isEmpty, onClick = player::previous)
                Item(L.playerNext, enabled = playerUi.queue.hasNext, onClick = player::next)
                Separator()
                Item(L.desktopTrayQuit, onClick = quit)
            }
        }
        Window(
            visible = visible,
            onCloseRequest = { if (closeToTray && trayOk) visible = false else quit() },
            state = state,
            title = L.appName,
            icon = windowIcon,
            onPreviewKeyEvent = ::handleKey,
        ) {
            DisposableEffect(window) {
                // The media keys, headset buttons and the desktop's now playing widget.
                val controls = MediaControls.start(
                    player = player,
                    window = window,
                    raise = {
                        visible = true
                        javax.swing.SwingUtilities.invokeLater { runCatching { window.toFront() } }
                    },
                    quit = quit,
                )
                onDispose { controls.close() }
            }
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

/** An image the app carries, as a painter for the window and the tray; a blank one when it cannot be read. */
private fun iconPainter(name: String): Painter {
    val image = runCatching {
        DesktopApp::class.java.getResourceAsStream("/com/tmplayer/music/desktop/$name")?.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()) }
    }.getOrNull()
    return if (image != null) BitmapPainter(image.toComposeImageBitmap()) else androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Transparent)
}
