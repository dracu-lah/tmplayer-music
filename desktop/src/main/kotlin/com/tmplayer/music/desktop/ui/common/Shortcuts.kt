package com.tmplayer.music.desktop.ui.common

import androidx.compose.ui.input.key.Key

/** What a key press asks of the app. */
enum class Shortcut {
    PlayPause,
    SeekBack,
    SeekForward,
    Previous,
    Next,
    VolumeUp,
    VolumeDown,
    Search,
    SelectAll,
}

/**
 * The desktop's keyboard: Space plays and pauses, Left and Right seek, Ctrl+Left and Ctrl+Right skip,
 * Ctrl+Up and Ctrl+Down change the volume, Ctrl+F searches, Ctrl+A selects the rows of a list, and
 * a keyboard's media keys do what they say. Ctrl is Cmd on a Mac ([ctrl] is either). Typing in a
 * text field keeps its own keys: Space, the arrows and Ctrl+A are text there; Ctrl+F and the media
 * keys still work.
 */
object Shortcuts {
    /** How far Left and Right seek. */
    const val SEEK_MS = 5_000L

    /** How far Ctrl+Up and Ctrl+Down move the volume. */
    const val VOLUME_STEP = 5

    /** The shortcut for [key] with the modifiers held, or null when it is not one (the key then goes on to whatever has focus). */
    fun of(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, typing: Boolean): Shortcut? = when {
        key == Key.MediaPlayPause -> Shortcut.PlayPause
        key == Key.MediaNext -> Shortcut.Next
        key == Key.MediaPrevious -> Shortcut.Previous
        alt -> null
        ctrl && key == Key.F && !shift -> Shortcut.Search
        typing -> null
        ctrl && key == Key.A && !shift -> Shortcut.SelectAll
        ctrl && key == Key.DirectionLeft -> Shortcut.Previous
        ctrl && key == Key.DirectionRight -> Shortcut.Next
        ctrl && key == Key.DirectionUp -> Shortcut.VolumeUp
        ctrl && key == Key.DirectionDown -> Shortcut.VolumeDown
        ctrl || shift -> null
        key == Key.Spacebar -> Shortcut.PlayPause
        key == Key.DirectionLeft -> Shortcut.SeekBack
        key == Key.DirectionRight -> Shortcut.SeekForward
        else -> null
    }
}
