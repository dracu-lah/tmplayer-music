// From TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/os/NativeFullscreen.kt (windowsHandle only).
package com.tmplayer.music.desktop.os

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Frame

/** The native handle of a window, for the one OS service that wants it: SMTC on Windows. */
internal object WindowHandles {
    private const val GA_ROOT = 2

    /** The frame's top level HWND (the drawing surface inside it is a child); null before the window exists. */
    fun windows(window: Frame): WinDef.HWND? {
        val pointer = Native.getComponentPointer(window) ?: return null
        val hwnd = WinDef.HWND(pointer)
        return User32.INSTANCE.GetAncestor(hwnd, GA_ROOT) ?: hwnd
    }
}
