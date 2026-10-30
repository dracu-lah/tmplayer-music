package com.tmplayer.music.platform

import java.io.File

/**
 * Where TDLib keeps its session and its cache. From TMPlayer's `Paths` (GPL-3.0), without the
 * downloads folder: this app keeps nothing outside TDLib's own cache until Keep offline exists.
 */
interface Paths {
    /** TDLib's database: the signed-in session. Losing it signs the user out. */
    val databaseDir: File

    /** TDLib's files directory: the cache. Anything in here may be deleted by rule. */
    val filesDir: File
}

/** What TDLib tells Telegram about the device, shown in the user's list of active sessions. */
data class DeviceInfo(
    val model: String,
    val systemVersion: String,
    val appVersion: String,
)

/**
 * The Telegram API id and hash. The app injects its own from its build configuration; a build
 * without them compiles and says so at sign in, which is what a fork or a CI build gets.
 */
data class Credentials(
    val apiId: Int,
    val apiHash: String,
) {
    val present: Boolean get() = apiId != 0 && apiHash.isNotBlank()
}
