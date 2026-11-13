// Adapted from the Android app's Diagnostics (app/.../diagnostics/Diagnostics.kt).
package com.tmplayer.music.desktop.diagnostics

import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.BuildInfo
import com.tmplayer.music.desktop.DesktopPaths
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.About
import com.tmplayer.music.platform.CrashJournal
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.LogReport
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.platform.Logs
import com.tmplayer.music.platform.StandardErrorSink
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log and crash record, for Send logs. [install] runs first thing in `main`: from
 * then on every [Logger] line goes to standard error and to the bounded log under the logs folder,
 * and an uncaught exception is written down there before the thread ends.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val KNOWN_TIMEOUT_MS = 3_000L

    private val journal: CrashJournal by lazy { CrashJournal(DesktopPaths.logsDir) }
    private val startedAt = System.nanoTime()

    /** The version as About and the report show it. */
    fun version(): String = About.version(BuildInfo.VERSION, -1, "desktop")

    fun install() {
        try {
            Logs.install(DesktopPaths.logsDir, StandardErrorSink)
        } catch (e: Exception) {
            // Logging stays on standard error alone; the app carries on.
            Logger.w(TAG, "the log file could not be set up", e)
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written here, on the failing thread: bounded and never throwing. A coroutine failure
            // never gets here (JobFailures contains it); this is for a thread nobody guards.
            try {
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
                Logs.book.add('E', "Crash", "uncaught on ${thread.name}, $seconds s after start", error)
                journal.record(error, version(), Logs.book.recent(CrashJournal.MAX_RECENT_LINES), System.currentTimeMillis())
            } catch (_: Throwable) {
                // Nothing more can be done.
            }
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
        // A failed background job is logged by JobFailures itself; nothing more is sent anywhere.
        JobFailures.report = null
    }

    /** True once, when the last run ended in a crash the user has not been offered Send logs for. */
    fun shouldOfferAfterCrash(): Boolean = runCatching { journal.shouldOffer().also { if (it) journal.markOffered() } }.getOrDefault(false)

    /**
     * The report Send logs writes, built off the main thread. The chat names the library knows are
     * passed to the redactor to be taken out word for word.
     */
    suspend fun report(): String {
        val known = runCatching {
            withTimeoutOrNull(KNOWN_TIMEOUT_MS) { Library.index().pickedChats().map { it.title } }
        }.getOrNull().orEmpty()
        val facts = LogReport.Facts(
            appName = L.appName,
            version = version(),
            android = "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()} (JVM ${System.getProperty("java.version").orEmpty()})",
            device = System.getProperty("os.arch").orEmpty().ifEmpty { "Desktop" },
            containedFailures = JobFailures.count,
        )
        return LogReport.build(facts, Logs.book.lines(), journal.last(), known)
    }

    fun fileName(): String = LogReport.fileName(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))

    /** The folder the report files go in: one at a time, easy to find. */
    fun shareDir(): File = File(DesktopPaths.logsDir, "share")
}
