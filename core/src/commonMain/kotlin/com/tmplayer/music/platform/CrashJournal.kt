// The idea (a crash written down on the device, asked about at the next start, never sent by
// itself) is from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/CrashRecord.kt.
package com.tmplayer.music.platform

import java.io.File

/**
 * The last crash, written down on the device by the uncaught exception handler so the next open
 * can offer Send logs once.
 *
 * The file is `crash.txt` in [dir]: the moment, the app version, the stack trace and the log lines
 * just before it, as plain text. It goes through a temporary file, so a process killed halfway
 * leaves the old record or none, never half a one. Nothing in it leaves the phone unless the user
 * sends the logs, and then it is redacted like every other line ([LogReport]).
 *
 * The offer is made once per crash: `crash.offered` holds the moment of the crash it was made for,
 * so a dismissed offer stays dismissed, and only a new crash brings it back. Never a loop.
 */
class CrashJournal(private val dir: File) {

    class Crash(val time: Long, val text: String)

    private val file get() = File(dir, CRASH)
    private val offered get() = File(dir, OFFERED)

    /**
     * Writes [error] down. Called on the crashing thread, so it does as little as it can and
     * never throws: a full disk or a missing folder loses the record, not the crash's own report.
     */
    fun record(error: Throwable, version: String, recent: List<String>, now: Long): Boolean = try {
        dir.mkdirs()
        val part = File(dir, "$CRASH.part")
        part.writeText(text(error, version, recent, now), Charsets.UTF_8)
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        } else {
            true
        }
    } catch (e: Throwable) {
        false
    }

    /** The crash on record, or null when there is none or it cannot be read. */
    fun last(): Crash? = try {
        val f = file
        if (!f.isFile || f.length() > MAX_FILE_BYTES) null else parse(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /** True once per crash: there is one on record and the offer was not made for it yet. */
    fun shouldOffer(): Boolean = shouldOffer(last()?.time, offeredTime())

    /** The offer for the crash on record was made; it is not made again. */
    fun markOffered() {
        val crash = last() ?: return
        runCatching {
            dir.mkdirs()
            offered.writeText(crash.time.toString())
        }
    }

    private fun offeredTime(): Long? = try {
        val f = offered
        if (!f.isFile || f.length() > 64) null else f.readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val CRASH = "crash.txt"
        const val OFFERED = "crash.offered"
        const val MAX_TRACE_LINES = 200
        const val MAX_RECENT_LINES = 200
        private const val MAX_LINE = 2_000
        private const val MAX_FILE_BYTES = 1_024L * 1_024
        private const val TIME = "time="

        /** Offer when a crash is on record and the last offer was made for another one (or none). */
        fun shouldOffer(crashTime: Long?, offeredTime: Long?): Boolean = crashTime != null && crashTime != offeredTime

        /** The file's text: the moment, the version, the trace (bounded) and the recent lines (bounded). */
        fun text(error: Throwable, version: String, recent: List<String>, now: Long): String = buildString {
            append(TIME).append(now).append('\n')
            append("version=").append(version.replace('\n', ' ').take(100)).append('\n')
            append("--- crash\n")
            val trace = runCatching { error.stackTraceToString() }.getOrDefault(error.javaClass.name)
            trace.split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }.take(MAX_TRACE_LINES)
                .forEach { append(it.take(MAX_LINE)).append('\n') }
            append("--- recent log\n")
            recent.takeLast(MAX_RECENT_LINES).forEach { append(it.take(MAX_LINE)).append('\n') }
        }

        /** A record back from its text; null when the first line is not a moment. */
        fun parse(text: String): Crash? {
            val first = text.substringBefore('\n')
            if (!first.startsWith(TIME)) return null
            val time = first.removePrefix(TIME).trim().toLongOrNull() ?: return null
            if (time <= 0) return null
            return Crash(time, text.substringAfter('\n', ""))
        }
    }
}
