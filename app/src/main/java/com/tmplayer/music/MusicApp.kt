package com.tmplayer.music

import android.app.Application
import com.tmplayer.music.diagnostics.Diagnostics
import com.tmplayer.music.data.AndroidLogSink
import com.tmplayer.music.data.AndroidPaths
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.NetworkWatch
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.Td
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
        Td.start(this)
        // The user's choices (a Pause stays on after a restart) live in Prefs; a weak device reads tags in smaller batches.
        Library.start(File(filesDir, "library"), Prefs(this), lowRam = weakDevice())
        NetworkWatch.start(this)
        Storage.cacheDir = AndroidPaths(this).filesDir
        Storage.chosenCeiling = Prefs(this).cacheCeiling(weakDevice())
    }
}
