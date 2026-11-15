package com.tmplayer.music

import android.app.Application
import com.tmplayer.music.diagnostics.Diagnostics
import com.tmplayer.music.data.AndroidLogSink
import com.tmplayer.music.data.AndroidPaths
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.NetworkWatch
import com.tmplayer.music.data.OfflineDownloads
import com.tmplayer.music.data.OfflineHost
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.SupportChecks
import com.tmplayer.music.data.Td
import com.tmplayer.music.data.Updates
import com.tmplayer.music.data.configureForAndroid
import com.tmplayer.music.data.start
import com.tmplayer.music.platform.Logger
import java.io.File

class MusicApp : Application() {
    /** A phone Android itself calls low on memory, or one with under 2 GB: a 1 GB cache by default. */
    fun weakDevice(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return am.isLowRamDevice || info.totalMem in 1 until 2L * 1024 * 1024 * 1024
    }

    override fun onCreate() {
        super.onCreate()
        Logger.sink = AndroidLogSink
        // The bounded log file and the crash record for Send logs; first, so later lines are kept.
        Diagnostics.install(this)
        stopContentCapture()
        // The UI language: the Settings choice, else the device's, else English. Before the demo
        // return below, so a demo speaks the device's language too.
        com.tmplayer.music.data.AppLocales.start(this)
        com.tmplayer.music.platform.HapticSetting.start(Prefs(this))
        com.tmplayer.music.player.AudioEffectsSetting.start(Prefs(this))
        // A debug build's demo library starts in place of the app (app/src/debug); never in a release.
        if (BuildHooks.startInstead(this)) return
        // Connect: the phone as a remote for the TV app. A phone only reads its pairings; a TV also listens
        // while the app is on screen or playing (TvConnect). Nothing at all in a store build.
        if (com.tmplayer.music.connect.Connect.available) {
            com.tmplayer.music.connect.Connect.init(this, com.tmplayer.music.ui.tv.isTv(this))
            com.tmplayer.music.connect.TvConnect.start(this)
        }
        Td.start(this)
        // The user's choices (a Pause stays on after a restart) live in Prefs; a weak device reads tags in smaller batches.
        Library.start(File(filesDir, "library"), Prefs(this), lowRam = weakDevice())
        // Keep offline: its own folder (outside TDLib's cache), and the queue an earlier run was in the middle of.
        // A foreground service holds the process only while a fetch runs (OfflineService).
        OfflineDownloads.onForeground = { on -> if (on) OfflineHost.start(this) }
        OfflineDownloads.start(File(filesDir, "offline"), Prefs(this), lowRam = weakDevice())
        NetworkWatch.start(this)
        // Casting (regular APK only; nothing in a store build or on a television): starts Google's Cast framework,
        // which looks for no device until the Cast sheet is opened.
        if (!com.tmplayer.music.ui.tv.isTv(this)) com.tmplayer.music.cast.CastBuild.initialize(this)
        // Covers found online (opt in, the lyrics switch); never started in the demo, which returned above.
        com.tmplayer.music.covers.CoverOnline.start(File(cacheDir, "covers-online"))
        // What this build is, for the update check; off in a store build (BuildConfig.UPDATE_CHECK).
        Updates.configureForAndroid(this)
        Storage.cacheDir = AndroidPaths(this).filesDir
        Storage.chosenCeiling = Prefs(this).cacheCeiling(weakDevice())
        // The once a day sponsor reminder job, only while its switch is on (off by default).
        SupportChecks.schedule(this)
    }

    /**
     * Turns off Compose's content capture (the text on screen handed to the system's on-device
     * intelligence service, where the phone has one). After each change of what is on screen it
     * walks the whole semantics tree, the library under the player included, and the playing time
     * changes it twice a second: on a Pixel (Android 17, 2026-10-30) that was a third of the main
     * thread on Now Playing. It also keeps chat names and titles out of that service.
     * Accessibility (TalkBack) is a separate path and is not touched.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun stopContentCapture() {
        androidx.compose.ui.contentcapture.ContentCaptureManager.isEnabled = false
    }
}
