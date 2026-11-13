package com.tmplayer.music.desktop

import com.tmplayer.music.covers.CoverOnline
import com.tmplayer.music.data.CachePolicy
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.NetState
import com.tmplayer.music.data.OfflineDownloads
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.diagnostics.Diagnostics
import com.tmplayer.music.desktop.player.FileQueueStore
import com.tmplayer.music.desktop.player.MpvNatives
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.TdTrackSource
import com.tmplayer.music.desktop.player.SoundSettings
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.AudioEffectsSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * The desktop's process-wide services, each opened exactly once: the settings file, TDLib, the
 * library and the player. [start] runs first thing in `main`, before any window exists, so a crash
 * on the way up is already in the log.
 */
object DesktopApp {
    val prefs: DesktopPrefs by lazy { DesktopPrefs(DesktopPaths.prefsFile) }

    /**
     * The volume is written about half a second after the last change, off the UI thread: a held
     * volume key or a dragged slider would otherwise rewrite the settings file at every step.
     */
    private val volumeSaver = DebouncedVolume(prefs, JobFailures.scope("VolumeSaver", Dispatchers.IO))

    /** The one player: the queue, libmpv, and the saved queue file. */
    val player: PlayerController by lazy {
        newPlayer().also { watchSound(it) }
    }

    private fun newPlayer(): PlayerController =
        PlayerController(
            source = TdTrackSource,
            store = FileQueueStore(),
            engineFactory = { MpvNatives.create() },
            initialVolume = prefs.getLong(VOLUME, 100).toInt(),
            initialMuted = prefs.getBoolean(MUTED, false),
            onVolume = { volume, muted -> volumeSaver.set(volume, muted) },
            onPlayingChanged = { Library.setPlaying(it) },
        )

    /** The sound choices (the same ones the phone keeps) follow the player: now, and whenever one changes. */
    private fun watchSound(player: PlayerController) {
        val fx = AudioEffectsSetting.choices
        JobFailures.scope("Sound", Dispatchers.Default).launch {
            combine(fx.speed, fx.keepPitch, fx.equalizerOn, fx.levels, combine(fx.skipSilence, fx.normalise) { a, b -> a to b }) { speed, keep, eq, levels, rest ->
                SoundSettings(speed, keep, eq, levels, rest.first, rest.second)
            }.collect { player.setSound(it) }
        }
    }

    @Volatile
    private var started = false

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Diagnostics.install()
        // The sound and lyrics choices live in the settings file; they are read before anything plays.
        AudioEffectsSetting.start(prefs)
        Logger.i(TAG, "TMPlayer Music desktop ${BuildInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        Td.start(DesktopPaths, desktopDeviceInfo(), desktopCredentials())
        // The user's choices (a Pause stays on after a restart) live in the settings file.
        Library.start(DesktopPaths.libraryDir, prefs)
        // A computer is on a wired or home line far more often than a metered one, and has no
        // mobile data switch: the tag reader runs as it does on Wi-Fi.
        Library.setNetwork(NetState.Unmetered)
        Storage.cacheDir = DesktopPaths.filesDir
        Storage.chosenCeiling = prefs.getLong(CACHE_CEILING, CachePolicy.defaultCeiling(weakDevice = false))
        // Keep offline: its own folder, outside TDLib's cache, so Free up space never sees it.
        OfflineDownloads.start(DesktopPaths.offlineDir, prefs, lowRam = false)
        // Covers found online, under the lyrics and covers switch (on by default).
        CoverOnline.start(File(DesktopPaths.cacheDir, "covers-online"))
        // A phone as the remote of this computer: only when the Settings switch was left on (off by default).
        runCatching { com.tmplayer.music.desktop.connect.DesktopConnect.start() }.onFailure { Logger.w(TAG, "Connect could not start", it) }
    }

    /** The queue is saved and the player let go; the window is closing. */
    fun stop() {
        runCatching { com.tmplayer.music.desktop.connect.DesktopConnect.stop() }
        runCatching { player.close() }
        runCatching { volumeSaver.flush() }
        runCatching { OfflineDownloads.flush() }
        runCatching { Library.choices.flush() }
    }

    /** Whether closing the window keeps the app in the system tray (off by default). */
    val closeToTray = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean(CLOSE_TO_TRAY, false))

    fun setCloseToTray(on: Boolean) {
        prefs.putBoolean(CLOSE_TO_TRAY, on)
        closeToTray.value = on
    }

    /** Free up space and Keep offline: the ceiling of TDLib's cache, kept for the next run. */
    fun setCacheCeiling(bytes: Long) {
        prefs.putLong(CACHE_CEILING, bytes)
        Storage.chosenCeiling = bytes
    }

    const val VOLUME = "player_volume"
    const val MUTED = "player_muted"
    const val CACHE_CEILING = "cache_ceiling"
    const val CLOSE_TO_TRAY = "close_to_tray"
    private const val TAG = "DesktopApp"
}

/** Writes the latest volume and mute to [prefs] [delayMs] after the last change; [flush] writes at once. */
internal class DebouncedVolume(
    private val prefs: DesktopPrefs,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val delayMs: Long = 500,
) {
    private val lock = Any()
    private var pending: Pair<Int, Boolean>? = null
    private var job: kotlinx.coroutines.Job? = null

    fun set(volume: Int, muted: Boolean) {
        synchronized(lock) {
            pending = volume to muted
            job?.cancel()
            job = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                flush()
            }
        }
    }

    /** Writes what is waiting, if anything; safe to call from any thread and more than once. */
    fun flush() {
        val (volume, muted) = synchronized(lock) {
            job?.cancel()
            job = null
            pending.also { pending = null }
        } ?: return
        prefs.putLong(DesktopApp.VOLUME, volume.toLong())
        prefs.putBoolean(DesktopApp.MUTED, muted)
    }
}
