package com.tmplayer.music.platform

/**
 * Where `:core` sends its log lines. Android installs a sink that writes to logcat; anything that
 * installs nothing (every JVM test) gets standard error. From TMPlayer's `Logger` (GPL-3.0).
 */
interface LogSink {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
}

object Logger {

    @Volatile
    var sink: LogSink = StandardErrorSink

    fun i(tag: String, message: String) = sink.i(tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) = sink.w(tag, message, error)
}

/** The sink nothing has to install. */
object StandardErrorSink : LogSink {
    override fun i(tag: String, message: String) {
        System.err.println("I/$tag: $message")
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        System.err.println("W/$tag: $message")
        error?.printStackTrace()
    }
}
