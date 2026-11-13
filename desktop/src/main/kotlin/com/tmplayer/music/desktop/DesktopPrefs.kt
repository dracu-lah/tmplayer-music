package com.tmplayer.music.desktop

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.platform.Logger
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * The desktop's small settings, one properties file: plain enough to read by hand when a tester
 * reports something odd, and every write replaces it in one rename so a crash mid write leaves the
 * old file. This is also the store [com.tmplayer.music.data.Library] keeps the user's choices in
 * (the Pause, the clip switches, each chat's order).
 *
 * Outside data: a missing, cut short or unreadable file is an empty set of settings, and a value
 * of the wrong type reads as the default. Nothing here throws.
 */
class DesktopPrefs(private val file: File) : KeyValues {

    private val lock = Any()
    private val values = LinkedHashMap<String, String>().also { map ->
        synchronized(lock) { map.putAll(read()) }
    }

    /** Set once the picker was finished, even with nothing picked, so it is not forced again. */
    var pickerDone: Boolean
        get() = getBoolean(PICKER_DONE, false)
        set(value) = putBoolean(PICKER_DONE, value)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { values[key] }?.let { it.toBooleanStrictOrNull() } ?: default

    override fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    override fun getLong(key: String, default: Long): Long =
        synchronized(lock) { values[key] }?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) = put(key, value.toString())

    override fun getString(key: String): String? = synchronized(lock) { values[key] }

    override fun putString(key: String, value: String) = put(key, value)

    /** Forgets [key]; the next read gives its default. */
    fun remove(key: String) {
        synchronized(lock) {
            if (values.remove(key) != null) write()
        }
    }

    private fun put(key: String, value: String) {
        if (key.isEmpty() || key.length > MAX_KEY || value.length > MAX_VALUE) return
        synchronized(lock) {
            if (values[key] == value) return
            values[key] = value
            write()
        }
    }

    private fun read(): Map<String, String> {
        if (!file.isFile || file.length() > MAX_FILE) return emptyMap()
        return try {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            p.stringPropertyNames().associateWith { p.getProperty(it).orEmpty() }
        } catch (e: Exception) {
            Logger.w(TAG, "the settings file could not be read, starting with defaults", e)
            emptyMap()
        }
    }

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".part")
            val p = Properties()
            values.forEach { (k, v) -> p.setProperty(k, v) }
            // This file holds the pairing secrets of Connect, so it is made readable by its owner only (0600) from the first byte:
            // the temp file is created with those permissions and the rename keeps them. Where there is no POSIX
            // (Windows) nothing is set here and the user profile's own ACL, which already limits the folder to the user, applies.
            Files.deleteIfExists(temp.toPath())
            val owner: Array<FileAttribute<*>> = if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                emptyArray()
            }
            Files.createFile(temp.toPath(), *owner)
            Files.newOutputStream(temp.toPath()).use { p.store(it, null) }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // A file system with no atomic move (some network shares): a plain replace.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // A full disk or a read only folder: the choice holds for this run and is not kept.
            Logger.w(TAG, "the settings file could not be written", e)
        }
    }

    private companion object {
        const val TAG = "DesktopPrefs"
        const val PICKER_DONE = "picker_done"
        const val MAX_KEY = 200
        const val MAX_VALUE = 64 * 1024
        const val MAX_FILE = 4L * 1024 * 1024
    }
}
