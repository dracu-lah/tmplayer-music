package com.tmplayer.music.desktop

import com.tmplayer.music.data.Library
import com.tmplayer.music.data.NetState
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.diagnostics.Diagnostics
import com.tmplayer.music.desktop.player.FileQueueStore
import com.tmplayer.music.desktop.player.MpvNatives
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.TdTrackSource
import com.tmplayer.music.platform.Logger

/**
 * The desktop's process-wide services, each opened exactly once: the settings file, TDLib, the
 * library and the player. [start] runs first thing in `main`, before any window exists, so a crash
 * on the way up is already in the log.
 */
object DesktopApp {
    val prefs: DesktopPrefs by lazy { DesktopPrefs(DesktopPaths.prefsFile) }

    /** The one player: the queue, libmpv, and the saved queue file. */
    val player: PlayerController by lazy {
        PlayerController(
            source = TdTrackSource,
            store = FileQueueStore(),
            engineFactory = { MpvNatives.create() },
            initialVolume = prefs.getLong(VOLUME, 100).toInt(),
            initialMuted = prefs.getBoolean(MUTED, false),
            onVolume = { volume, muted ->
                prefs.putLong(VOLUME, volume.toLong())
                prefs.putBoolean(MUTED, muted)
            },
            onPlayingChanged = { Library.setPlaying(it) },
        )
    }

    @Volatile
    private var started = false

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Diagnostics.install()
        Logger.i(TAG, "TMPlayer Music desktop ${BuildInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
        // The user's choices (a Pause stays on after a restart) live in the settings file.
        Library.start(DesktopPaths.libraryDir, prefs)
        // A computer is on a wired or home line far more often than a metered one, and has no
        // mobile data switch: the tag reader runs as it does on Wi-Fi.
        Library.setNetwork(NetState.Unmetered)
        Storage.cacheDir = DesktopPaths.filesDir
    }

    /** The queue is saved and the player let go; the window is closing. */
    fun stop() {
        runCatching { player.close() }
        runCatching { Library.choices.flush() }
    }

    const val VOLUME = "player_volume"
    const val MUTED = "player_muted"
    private const val TAG = "DesktopApp"
}
