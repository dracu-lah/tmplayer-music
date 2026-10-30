package com.tmplayer.music

import android.app.Application
import com.tmplayer.music.data.AndroidLogSink
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Td
import com.tmplayer.music.data.start
import com.tmplayer.music.platform.Logger
import java.io.File

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.sink = AndroidLogSink
        Td.start(this)
        Library.start(File(filesDir, "library"))
    }
}
