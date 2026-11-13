// From TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/player/TdMediaData.kt,
// without the scrub preview's disk only input.
package com.tmplayer.music.desktop.player

import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * A Telegram file (or anything else behind [StreamBytes]) as mediamp's seekable media: the desktop
 * twin of Android's `TdDataSource`. No loopback server and no temp file: mpv's demuxer thread
 * calls [StreamInput.read], which blocks until TDLib has the bytes, and a seek simply moves the
 * read position so the next read points TDLib there. Owns [bytes] and closes them once, however
 * many times mediamp calls [close] (it calls it twice).
 *
 * @param onClose runs once, after the bytes are closed.
 */
class TdMediaData(
    internal val bytes: StreamBytes,
    override val uri: String,
    private val onClose: () -> Unit = {},
) : SeekableInputMediaData {

    override val extraFiles: MediaExtraFiles = MediaExtraFiles()
    override val options: List<String> = emptyList()

    private val closed = AtomicBoolean(false)

    override fun fileLength(): Long = bytes.size

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
        if (closed.get()) throw IOException("Media closed")
        return StreamInput(bytes, coroutineContext[Job])
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { bytes.close() }
        runCatching { onClose() }
    }
}

/**
 * One open read of the file, positioned by [seekTo] and read by mpv's demuxer thread.
 *
 * A read that has to wait blocks that thread, which is what mpv expects of a stream. The wait is a
 * child of the session job mediamp hands [TdMediaData.createInput], and of this input's own job,
 * so stopping the playback or closing the input fails a stuck read at once with an IOException
 * instead of leaving mpv's teardown waiting for TDLib.
 */
class StreamInput(
    private val bytes: StreamBytes,
    sessionJob: Job?,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
) : SeekableInput {

    private val inputJob = SupervisorJob(sessionJob)

    @Volatile
    private var pos = 0L

    @Volatile
    private var closed = false

    override val position: Long get() = pos
    override val size: Long get() = bytes.size
    override val bytesRemaining: Long get() = (bytes.size - pos).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        if (position < 0) throw IOException("Negative seek $position")
        if (closed) throw IOException("Input closed")
        pos = position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Input closed")
        if (length == 0) return 0
        repeat(SHORT_READ_RETRIES) {
            val at = pos
            if (at >= bytes.size) return -1

            // Fast path: no coroutine and no TDLib round trip, which is nearly every read.
            val available = bytes.cachedAvailable(at) ?: blocking { bytes.awaitBytesAt(at) }
            val wanted = minOf(length.toLong(), available, bytes.size - at).toInt()
            try {
                val read = bytes.read(at, buffer, offset, wanted)
                pos = at + read
                return read
            } catch (e: EOFException) {
                // The file on disk was shorter than TDLib said: the window was dropped, so the
                // next turn asks TDLib afresh instead of ending the stream for mpv.
                Logger.w(TAG, "Short read at $at, asking again", e)
            }
        }
        throw IOException("Could not read at $pos")
    }

    override fun close() {
        if (closed) return
        closed = true
        inputJob.cancel()
    }

    /**
     * Runs [block] on this thread until it returns, failing with an IOException when the session
     * or this input is cancelled, or when the wait outlives the read timeout.
     */
    private fun <T> blocking(block: suspend () -> T): T {
        val job = Job(inputJob)
        try {
            return runBlocking(job) { withTimeout(readTimeoutMs) { block() } }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Telegram did not respond in time", e)
        } catch (e: CancellationException) {
            throw IOException("Read cancelled", e)
        } finally {
            job.complete()
        }
    }

    companion object {
        private const val TAG = "StreamInput"

        /** A little above [com.tmplayer.music.player.TdByteWindow.STALL_TIMEOUT_MS], which decides first. */
        const val READ_TIMEOUT_MS = 70_000L

        private const val SHORT_READ_RETRIES = 3
    }
}
