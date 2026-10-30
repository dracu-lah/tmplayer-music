package com.tmplayer.music.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import android.content.Context
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.Td
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

// From TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/player/TdDataSource.kt, adapted for
// audio: the container hint is the file's audio mime type, and reads ahead in smaller steps.

/** The URI the player is handed for a Telegram file: `tdfile://<fileId>?type=<mime>`. */
fun tdFileUri(fileId: Int, type: String? = null): Uri {
    val builder = Uri.Builder().scheme(TD_SCHEME).authority(fileId.toString())
    if (!type.isNullOrBlank()) builder.appendQueryParameter(TYPE_PARAM, type)
    return builder.build()
}

const val TD_SCHEME = "tdfile"
private const val TYPE_PARAM = "type"

/**
 * Reads a Telegram file for the player through [TdByteWindow], TDLib's partial download: it asks for
 * the bytes at the read position, waits for them, and reads them from TDLib's file on disk, so a
 * seek far into a FLAC starts fetching there instead of downloading everything before it.
 */
@UnstableApi
class TdDataSource : BaseDataSource(true) {
    private var bytes: TdByteWindow? = null
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    /** Small reads (extractors read a few bytes at a time) come from here, one TDLib read per 64 KB. */
    private val ahead = ByteArray(AHEAD_BYTES)
    private var aheadAt = -1L
    private var aheadLength = 0

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun open(dataSpec: DataSpec): Long {
        val startedAt = SystemClock.elapsedRealtime()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val fileId = dataSpec.uri.authority?.toIntOrNull()
            ?: throw IOException("Not a Telegram file URI: ${dataSpec.uri}")
        headers = dataSpec.uri.getQueryParameter(TYPE_PARAM)?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()
        position = dataSpec.position
        aheadAt = -1L
        val td = blocking(OPEN_TIMEOUT_MS) { Td.awaitAuthorizedSession().client }
        // A window per open, on the client of the moment: a session replaced since (a log out and
        // back in) must not be read through.
        bytes?.close()
        val window = TdByteWindow(td).also { bytes = it }
        window.watch(fileId)
        val size = blocking(OPEN_TIMEOUT_MS) { window.lookUp() }
        if (position > size) throw DataSourceException(C.RESULT_END_OF_INPUT)
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else minOf(dataSpec.length, size - position)
        if (!window.completed) {
            // A file not on the device yet: room is made (the cache trimmed) or the reason it
            // cannot play is given, before a single byte is written (AGENTS.md: space and size).
            blocking(OPEN_TIMEOUT_MS) { Storage.makeRoom(size - position) }?.let { throw IOException(it) }
            blocking(OPEN_TIMEOUT_MS) { window.requestUnlessOnDisk(position) }
        }
        opened = true
        transferStarted(dataSpec)
        val took = SystemClock.elapsedRealtime() - startedAt
        if (took >= SLOW_LOG_MS) Log.i(LOG_TAG, "open of file $fileId at $position took $took ms")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = readOnce(buffer, offset, length)
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    private fun readOnce(buffer: ByteArray, offset: Int, length: Int): Int {
        if (aheadAt >= 0 && position >= aheadAt && position < aheadAt + aheadLength) {
            val from = (position - aheadAt).toInt()
            val count = minOf(length.toLong(), (aheadLength - from).toLong(), bytesRemaining).toInt()
            System.arraycopy(ahead, from, buffer, offset, count)
            return count
        }
        if (length < SMALL_READ_BYTES) {
            val filled = readThrough(ahead, 0, minOf(AHEAD_BYTES.toLong(), bytesRemaining).toInt())
            aheadAt = position
            aheadLength = filled
            val count = minOf(length, filled)
            System.arraycopy(ahead, 0, buffer, offset, count)
            return count
        }
        return readThrough(buffer, offset, length)
    }

    private fun readThrough(buffer: ByteArray, offset: Int, length: Int): Int {
        val window = bytes ?: throw IOException("Not open")
        var attempt = 0
        while (true) {
            val available = window.cachedAvailable(position)
                ?: blocking(READ_TIMEOUT_MS) { window.awaitBytesAt(position) }
            val wanted = minOf(length.toLong(), bytesRemaining, available).toInt()
            try {
                return window.read(position, buffer, offset, wanted)
            } catch (e: EOFException) {
                if (++attempt >= SHORT_READ_RETRIES) throw e
            }
        }
    }

    override fun close() {
        bytes?.close()
        bytes = null
        aheadAt = -1L
        uri = null
        headers = emptyMap()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not respond in time", e)
    }

    /** Telegram files through TDLib, anything else (a cover saved on disk) through the stock source. */
    class Factory(context: Context) : DataSource.Factory {
        private val fallback = DefaultDataSource.Factory(context.applicationContext)

        override fun createDataSource(): DataSource = Router(TdDataSource(), fallback.createDataSource())
    }

    /** Picks the source by the URI's scheme at open. */
    private class Router(private val td: TdDataSource, private val other: DataSource) : DataSource by other {
        private var active: DataSource = other

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            td.addTransferListener(transferListener)
            other.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = if (dataSpec.uri.scheme == TD_SCHEME) td else other
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

        override fun getUri(): Uri? = active.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

        override fun close() = active.close()
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 120_000L
        const val SHORT_READ_RETRIES = 3
        const val AHEAD_BYTES = 64 * 1024
        const val SMALL_READ_BYTES = 4 * 1024
        const val SLOW_LOG_MS = 250L
        const val LOG_TAG = "PlayerStart"
        const val READ_TIMEOUT_MS = TdByteWindow.STALL_TIMEOUT_MS + 10_000L
    }
}
