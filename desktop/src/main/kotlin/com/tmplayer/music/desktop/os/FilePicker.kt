// Adapted from TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/os/FilePicker.kt and FolderPicker.kt.
package com.tmplayer.music.desktop.os

import com.tmplayer.music.platform.Logger
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Asks the user for one file to open or a place to save one, in the OS's own dialog: FileKit first
 * (the file chooser portal on Linux, the common item dialog on Windows, NSOpenPanel on macOS),
 * Swing's `JFileChooser` where FileKit fails outright (no portal on a bare window manager session).
 * The dialog names the app's window as its parent, so a tiling window manager floats it over the
 * app instead of tiling it beside it.
 */
object FilePicker {

    @Volatile
    private var initialised = false

    private fun parentWindow(): java.awt.Window? = runCatching {
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Window.getWindows().firstOrNull { it.isShowing && it is java.awt.Frame }
    }.getOrNull()

    private fun settings(title: String): FileKitDialogSettings =
        parentWindow()?.let { window ->
            runCatching { FileKitDialogSettings(title = title, parent = FileKitDialogParent.awt(window)) }.getOrNull()
        } ?: FileKitDialogSettings(title = title)

    private fun ensureFileKit() {
        if (!initialised) {
            runCatching { FileKit.init(APP_ID) }.onFailure { Logger.w(TAG, "FileKit would not start", it) }
            initialised = true
        }
    }

    /** The file picked, or null when the user cancelled. With [extensions], only files ending in one of them are offered. */
    suspend fun open(title: String, vararg extensions: String): File? {
        ensureFileKit()
        val viaFileKit = runCatching {
            val type = if (extensions.isEmpty()) FileKitType.File() else FileKitType.File(extensions.toSet())
            FileKit.openFilePicker(type = type, dialogSettings = settings(title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system file picker failed; using Swing's", it) }
        if (viaFileKit.isSuccess) return viaFileKit.getOrNull()?.file
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(extensions.joinToString(), *extensions)
            }
            if (chooser.showOpenDialog(parentWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }

    /**
     * Where to save [suggestedName], or null when the user cancelled. The name keeps [extension]
     * even when it was typed without.
     */
    suspend fun save(title: String, suggestedName: String, extension: String): File? {
        ensureFileKit()
        val base = suggestedName.removeSuffix(".$extension")
        val viaFileKit = runCatching {
            FileKit.openFileSaver(suggestedName = base, extension = extension, dialogSettings = settings(title))
        }
        viaFileKit.exceptionOrNull()?.let { Logger.w(TAG, "The system save dialog failed; using Swing's", it) }
        val picked = if (viaFileKit.isSuccess) {
            viaFileKit.getOrNull()?.file
        } else {
            withContext(Dispatchers.Main) {
                val chooser = JFileChooser().apply {
                    dialogTitle = title
                    selectedFile = File(currentDirectory, "$base.$extension")
                }
                if (chooser.showSaveDialog(parentWindow()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
        }
        return picked?.let { if (it.name.endsWith(".$extension")) it else File(it.parentFile, "${it.name}.$extension") }
    }

    private const val APP_ID = "TMPlayerMusic"
    private const val TAG = "FilePicker"
}

/**
 * Reading and writing the files a picker returned, the way every file here is treated: bounded on
 * the way in (a million line "playlist" is not one), and written whole or not at all.
 */
object UserFiles {

    /** The bytes of [file], or null when it holds more than [max] (or is not a file). Reads on the IO pool, never more than [max] + 1 bytes. */
    suspend fun readCapped(file: File, max: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isFile) throw java.io.FileNotFoundException(file.name)
        file.inputStream().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > max) return@withContext null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    /**
     * Writes [text] to [file] as UTF-8 through a part file and a rename, so a full disk or a crash
     * leaves the old file, or none, never half of a new one. Throws on failure after cleaning up.
     */
    suspend fun writeText(file: File, text: String) = withContext(Dispatchers.IO) {
        val part = File(file.parentFile, file.name + ".part")
        try {
            part.writeText(text, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    part.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: java.io.IOException) {
                // A file system with no atomic move (some network shares): a plain replace.
                java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        }
    }

    /**
     * [name] as a safe file name on every OS: letters, digits, space, dash and underscore only, at
     * most [MAX_NAME] characters, no trailing space or dot, and never a name Windows keeps for a
     * device (CON, PRN, AUX, NUL, COM1 to COM9, LPT1 to LPT9, with or without an extension: the
     * caller adds one). Such a name gets an underscore in front.
     */
    fun safeName(name: String): String {
        // Windows drops a trailing dot or space, so "CON." would still be the device.
        val cleaned = name.trimEnd('.', ' ')
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(MAX_NAME)
            .trim(' ', '.')
            .ifBlank { "playlist" }
        return if (isReservedOnWindows(cleaned)) "_$cleaned" else cleaned
    }

    /** True for a name Windows treats as a device, whatever its extension (`con`, `NUL.txt`, `com1`). */
    fun isReservedOnWindows(name: String): Boolean {
        val stem = name.substringBefore('.').trim(' ').uppercase()
        if (stem in DEVICE_NAMES) return true
        // COM1 to COM9 and LPT1 to LPT9, and the superscript digits Windows reads the same way.
        return stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) && stem[3] in "123456789\u00B9\u00B2\u00B3"
    }

    private const val MAX_NAME = 60
    private val DEVICE_NAMES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$")
}
