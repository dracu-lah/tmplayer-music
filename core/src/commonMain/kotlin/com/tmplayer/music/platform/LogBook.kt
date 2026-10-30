package com.tmplayer.music.platform

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own recent log, for Send logs: the last [ringLines] lines in memory and the same lines
 * on disk in [dir], in `app.log` and the one before it, `app.log.1`, each at most [maxFileBytes].
 * When `app.log` is full it becomes `app.log.1` (the older one goes), so the disk never holds more
 * than twice [maxFileBytes].
 *
 * Writing never throws and never stops the caller for long: a full disk, a folder that went away
 * or a file that cannot be opened only turns the disk half off for a while (it is tried again after
 * [RETRY_AFTER_LINES] lines), and the memory half carries on. Lines are written as they come, one
 * write each, so the last lines before a crash are on disk. Every writer goes through one lock.
 *
 * Lines are kept as written. What is personal is taken out only when they are shared
 * ([LogReport]), since the folder is the app's own and nobody else can read it.
 */
class LogBook(
    private val dir: File?,
    private val maxFileBytes: Long = DEFAULT_FILE_BYTES,
    private val ringLines: Int = DEFAULT_RING_LINES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var out: FileOutputStream? = null
    private var size = 0L
    private var brokenFor = 0

    /** How many lines were written since start, including any the disk did not take. */
    var written: Long = 0
        private set

    /** How many times the disk refused a line; for the report's counts and the tests. */
    var diskFailures: Int = 0
        private set

    val current: File? get() = dir?.let { File(it, CURRENT) }
    val previous: File? get() = dir?.let { File(it, PREVIOUS) }

    /** One log entry: [level] is I or W (or E for a crash), [error]'s trace follows on its own lines. */
    fun add(level: Char, tag: String, message: String, error: Throwable? = null) {
        val lines = try {
            format(level, tag, message, error)
        } catch (e: Exception) {
            listOf("$level/$tag: (a log line could not be formatted: ${e.javaClass.simpleName})")
        }
        synchronized(lock) {
            for (line in lines) {
                ring.addLast(line)
                while (ring.size > ringLines) ring.removeFirst()
                written++
                writeLocked(line)
            }
        }
    }

    /** The last [max] lines from memory, oldest first. */
    fun recent(max: Int = ringLines): List<String> = synchronized(lock) { ring.toList().takeLast(max.coerceAtLeast(0)) }

    /**
     * Every line still kept, oldest first: the older file, then the current one. When neither can
     * be read (missing, unreadable, the disk half off since start) the lines in memory instead.
     */
    fun lines(): List<String> = synchronized(lock) {
        runCatching { out?.flush() }
        val fromDisk = try {
            listOfNotNull(previous, current)
                .filter { it.isFile }
                .flatMap { file -> file.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() } }
        } catch (e: Exception) {
            emptyList()
        }
        if (fromDisk.isEmpty()) ring.toList() else fromDisk
    }

    /** Closes the file; a later line opens it again. */
    fun close() = synchronized(lock) { closeLocked() }

    /** A sink that sends each line to [other] (logcat) and keeps it here as well. */
    fun tee(other: LogSink): LogSink = object : LogSink {
        override fun i(tag: String, message: String) {
            runCatching { other.i(tag, message) }
            add('I', tag, message)
        }

        override fun w(tag: String, message: String, error: Throwable?) {
            runCatching { other.w(tag, message, error) }
            add('W', tag, message, error)
        }
    }

    private fun format(level: Char, tag: String, message: String, error: Throwable?): List<String> {
        val time = stamp.format(Date(clock()))
        val head = "$time $level/$tag: "
        val out = ArrayList<String>()
        message.split('\n').forEachIndexed { i, part -> out += if (i == 0) head + part else "\t$part" }
        if (error != null) {
            error.stackTraceToString().split('\n')
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
                .take(MAX_TRACE_LINES)
                .forEach { out += "\t$it" }
        }
        return out
    }

    private fun writeLocked(line: String) {
        if (dir == null) return
        if (brokenFor > 0) {
            brokenFor--
            return
        }
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            if (out == null) open()
            if (size > 0 && size + bytes.size > maxFileBytes) rotate()
            val stream = out ?: return
            stream.write(bytes)
            size += bytes.size
        } catch (e: Exception) {
            // A full disk or a folder that went away: never a crash from logging.
            diskFailures++
            closeLocked()
            brokenFor = RETRY_AFTER_LINES
        }
    }

    private fun open() {
        val folder = dir ?: return
        folder.mkdirs()
        val file = File(folder, CURRENT)
        size = if (file.isFile) file.length() else 0L
        out = FileOutputStream(file, true)
    }

    private fun rotate() {
        val folder = dir ?: return
        closeLocked()
        val file = File(folder, CURRENT)
        val older = File(folder, PREVIOUS)
        older.delete()
        if (!file.renameTo(older)) file.delete()
        size = 0
        out = FileOutputStream(File(folder, CURRENT), true)
    }

    private fun closeLocked() {
        runCatching { out?.close() }
        out = null
    }

    companion object {
        const val CURRENT = "app.log"
        const val PREVIOUS = "app.log.1"
        const val DEFAULT_FILE_BYTES = 256L * 1024
        const val DEFAULT_RING_LINES = 500
        const val MAX_TRACE_LINES = 40
        const val RETRY_AFTER_LINES = 200
    }
}

/**
 * The app's one [LogBook]. Until [install] it keeps lines in memory only (the JVM tests, and the
 * moments before the app has a files folder).
 */
object Logs {
    @Volatile
    var book: LogBook = LogBook(null)
        private set

    /** Starts the disk half in [dir] and sends [Logger]'s lines through it as well as to [platform]. */
    fun install(dir: File, platform: LogSink) {
        val next = LogBook(dir)
        book = next
        Logger.sink = next.tee(platform)
    }
}
