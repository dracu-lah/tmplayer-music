package com.tmplayer.music.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.data.AndroidLogSink
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.About
import com.tmplayer.music.platform.CrashJournal
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.LogReport
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.platform.Logs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's own log and crash record, for Send logs. [install] runs first thing in
 * `MusicApp.onCreate`: from then on every [Logger] line goes to logcat and to the bounded log in
 * `files/logs`, and a crash is written down there before the process ends.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val KNOWN_TIMEOUT_MS = 3_000L

    @Volatile
    private var journal: CrashJournal? = null

    fun logsDir(context: Context) = File(context.applicationContext.filesDir, "logs")

    fun journal(context: Context): CrashJournal = journal ?: CrashJournal(logsDir(context)).also { journal = it }

    /** The version as About and the report show it. */
    fun version(): String = About.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE)

    fun install(context: Context) {
        val dir = logsDir(context)
        try {
            Logs.install(dir, AndroidLogSink)
        } catch (e: Exception) {
            // Logging stays on logcat alone; the app carries on.
            Logger.w(TAG, "the log file could not be set up", e)
        }
        val crashes = journal(context)
        val started = SystemClock.elapsedRealtime()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written here, on the crashing thread, since there is no later safe point: the
            // process ends as soon as the handler returns. Bounded and never throwing.
            try {
                Logs.book.add('E', "Crash", "uncaught on ${thread.name}, ${(SystemClock.elapsedRealtime() - started) / 1000} s after start", error)
                crashes.record(error, version(), Logs.book.recent(CrashJournal.MAX_RECENT_LINES), System.currentTimeMillis())
                Logs.book.close()
            } catch (_: Throwable) {
                // Nothing more can be done; Android's own handler still reports the crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * The report Send logs shares, built off the main thread. The chat names the library knows are
     * passed to the redactor to be taken out word for word.
     */
    suspend fun report(context: Context): String {
        val known = runCatching {
            withTimeoutOrNull(KNOWN_TIMEOUT_MS) { Library.index().pickedChats().map { it.title } }
        }.getOrNull().orEmpty()
        val facts = LogReport.Facts(
            appName = L.appName,
            version = version(),
            android = "${Build.VERSION.RELEASE.orEmpty()} (API ${Build.VERSION.SDK_INT})",
            device = listOf(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Android" },
            containedFailures = JobFailures.count,
        )
        return LogReport.build(facts, Logs.book.lines(), journal(context).last(), known)
    }

    fun fileName(): String = LogReport.fileName(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
}
