// Adapted from TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/os/OpenExternal.kt.
package com.tmplayer.music.desktop.os

import com.tmplayer.music.platform.Logger
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Opens a web link in the browser, or a file or folder in the OS's own file manager.
 *
 * `java.awt.Desktop` first, which is the native call on Windows and macOS and goes through GNOME's
 * or KDE's libraries on Linux. It is missing or refuses on plenty of Linux desktops (sway, i3, a
 * JVM without GTK), so each OS then has the command its shell would use. Everything runs off the
 * caller's thread: `Desktop.browse` can block for seconds on Linux while it starts a browser.
 */
object OpenExternal {

    fun browse(url: String) = launch("browse $url") {
        val desktop = desktopFor(Desktop.Action.BROWSE)
        if (desktop != null && runCatching { desktop.browse(URI(url)) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, url))
    }

    /** Opens [file] with its default app, or a folder in the file manager. */
    fun open(file: File) = launch("open $file") {
        val desktop = desktopFor(Desktop.Action.OPEN)
        if (desktop != null && runCatching { desktop.open(file) }.isSuccess) return@launch true
        run(command(OsInfo.osTag, file.absolutePath))
    }

    /** The shell's own opener, for when `java.awt.Desktop` is not there. */
    internal fun command(os: String, target: String): List<String> = when (os) {
        // Not `cmd /c start`: cmd reads a quoted first argument as a window title and splits a
        // URL at every `&`. The URL handler takes the target as one argument.
        "windows" -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
        "macos" -> listOf("open", target)
        else -> listOf("xdg-open", target)
    }

    private fun desktopFor(action: Desktop.Action): Desktop? = runCatching {
        if (!Desktop.isDesktopSupported()) return null
        Desktop.getDesktop().takeIf { it.isSupported(action) }
    }.getOrNull()

    private fun run(command: List<String>): Boolean = runCatching {
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrElse {
        Logger.w(TAG, "could not run ${command.first()}: ${it.message}")
        false
    }

    private fun launch(what: String, block: () -> Boolean) {
        Thread({
            if (!runCatching(block).getOrDefault(false)) Logger.w(TAG, "could not $what")
        }, "tmplayer-open").apply { isDaemon = true }.start()
    }

    private const val TAG = "OpenExternal"
}

/** The OS the app runs on, in the spelling of the native libraries' folders. */
object OsInfo {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = name.contains("win")
    val isMac: Boolean = name.contains("mac")
    val isLinux: Boolean = !isWindows && !isMac

    val osTag: String = when {
        isLinux -> "linux"
        isWindows -> "windows"
        else -> "macos"
    }

    val pid: Long get() = ProcessHandle.current().pid()
}
