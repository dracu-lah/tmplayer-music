package com.tmplayer.music.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.platform.Credentials
import com.tmplayer.music.platform.DeviceInfo
import com.tmplayer.music.platform.LogSink
import com.tmplayer.music.platform.Paths
import java.io.File

// The Android answers to what `:core` asks of the platform it runs on. From TMPlayer's
// AndroidPlatform.kt (GPL-3.0).

/** Logcat. */
object AndroidLogSink : LogSink {
    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }
}

/** TDLib's session in `filesDir/tdlib` and its cache in `filesDir/cache`. */
class AndroidPaths(context: Context) : Paths {
    private val app = context.applicationContext
    override val databaseDir: File = File(app.filesDir, "tdlib")
    override val filesDir: File = File(app.filesDir, "cache")
}

fun androidDeviceInfo(): DeviceInfo = DeviceInfo(
    model = Build.MODEL ?: "Android",
    systemVersion = Build.VERSION.RELEASE ?: "",
    appVersion = BuildConfig.VERSION_NAME,
)

fun buildCredentials(): Credentials = Credentials(
    apiId = BuildConfig.TG_API_ID,
    apiHash = BuildConfig.TG_API_HASH,
)

/** [Td.start] with everything Android supplies. Safe to call from every entry point. */
fun Td.start(context: Context) = start(AndroidPaths(context), androidDeviceInfo(), buildCredentials())
