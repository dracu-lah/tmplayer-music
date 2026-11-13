// Adapted from TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/DesktopPlatform.kt.
package com.tmplayer.music.desktop

import com.tmplayer.music.platform.Credentials
import com.tmplayer.music.platform.DeviceInfo
import com.tmplayer.music.platform.Paths
import net.harawata.appdirs.AppDirsFactory
import java.io.File

/**
 * Per user directories in each OS's own place (XDG on Linux, AppData on Windows, Library on
 * macOS), under the name TMPlayerMusic so this app never shares a session or a cache with TMPlayer
 * (video). The TDLib session and the library index are data, TDLib's files are cache, the small
 * settings file is config.
 */
object DesktopPaths : Paths {
    private const val APP = "TMPlayerMusic"
    private val dirs = AppDirsFactory.getInstance()

    val dataDir: File = File(dirs.getUserDataDir(APP, null, null)).apply { mkdirs() }
    val cacheDir: File = File(dirs.getUserCacheDir(APP, null, null)).apply { mkdirs() }
    val configDir: File = File(dirs.getUserConfigDir(APP, null, null)).apply { mkdirs() }

    /** Where libmpv and its friends are unpacked once, instead of a fresh temp folder per launch. */
    val nativeDir: File = File(dataDir, "natives").apply { mkdirs() }

    /** The library index (library.db), the saved covers and lyrics. */
    val libraryDir: File = File(dataDir, "library").apply { mkdirs() }

    /** Tracks kept offline ([com.tmplayer.music.data.OfflineStore]): data, never part of the cache. */
    val offlineDir: File = File(dataDir, "offline").apply { mkdirs() }

    /** The bounded log and the crash record, for Send logs. */
    val logsDir: File = File(dataDir, "logs").apply { mkdirs() }

    /** The saved queue ([com.tmplayer.music.player.SavedQueue]), written whole. */
    val queueFile: File get() = File(dataDir, "queue.json")

    /** The few settings the desktop keeps ([DesktopPrefs]). */
    val prefsFile: File get() = File(configDir, "desktop.properties")

    override val databaseDir: File = File(dataDir, "tdlib").apply { mkdirs() }

    override val filesDir: File get() = File(cacheDir, "files").apply { mkdirs() }
}

fun desktopDeviceInfo(): DeviceInfo = DeviceInfo(
    model = "Desktop", // i18n-ok: the device name Telegram lists, not UI
    systemVersion = "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()}".trim(),
    appVersion = BuildInfo.VERSION,
)

fun desktopCredentials(): Credentials = Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH)

/** The OS the app runs on, for the few places that differ (the folder a file manager opens). */
object HostOs {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val windows: Boolean get() = name.contains("win")
    val mac: Boolean get() = name.contains("mac")
    val linux: Boolean get() = !windows && !mac
}
