// From TMPlayer (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/player/StreamBytes.kt and
// TdStreamBytes.kt, without the scrub preview's disk only reads (audio has none).
package com.tmplayer.music.desktop.player

import com.tmplayer.music.player.TdByteWindow
import com.tmplayer.music.data.Storage
import dev.g000sha256.tdl.TdlClient
import java.io.IOException

/**
 * The bytes of one file that is still arriving, as the desktop's media source sees them.
 *
 * In the app this is TDLib's partial file behind [TdByteWindow] ([TdStreamBytes]); in the tests it
 * is a file being copied at a set rate or a plain array. [StreamInput] blocks on it and never
 * needs to know which.
 */
interface StreamBytes {

    /** The whole file's length, known before the first read. */
    val size: Long

    /** How many bytes at [position] are on disk right now, or null if that takes a wait. */
    fun cachedAvailable(position: Long): Long?

    /**
     * Suspends until at least one byte at [position] is on disk, aiming the download there if it
     * is somewhere else, and returns how many are. Throws an IOException when it gives up.
     */
    suspend fun awaitBytesAt(position: Long): Long

    /** Reads bytes that [cachedAvailable] or [awaitBytesAt] has already vouched for. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Stops listening. Reads after this are not expected. */
    fun close()
}

/**
 * [StreamBytes] over TDLib, through the same [TdByteWindow] Android's `TdDataSource` wraps.
 *
 * Opened once per playback with [start], which subscribes before the first `getFile` (so no update
 * is missed while it is in flight) and learns the size. The download is asked for where the first
 * read lands, not at byte 0: a resumed track's first request goes to where it resumes, and a seek
 * far into a FLAC starts fetching there instead of downloading everything before it.
 */
class TdStreamBytes(td: TdlClient, val fileId: Int) : StreamBytes {

    private val window = TdByteWindow(td)

    override var size: Long = 0L
        private set

    /** Whether the whole file was already on disk when last looked at. */
    val completed: Boolean get() = window.completed

    /**
     * Watches the file and learns its size. A file not on the device yet gets its room first (the
     * cache trimmed, or the reason it cannot play: AGENTS.md, space and size) before a single byte
     * is written, then its download is asked for from the start.
     */
    suspend fun start() {
        window.watch(fileId)
        size = window.lookUp()
        if (!window.completed) {
            Storage.makeRoom(size)?.let { throw IOException(it) }
            window.requestUnlessOnDisk(0)
        }
    }

    override fun cachedAvailable(position: Long): Long? = window.cachedAvailable(position)

    override suspend fun awaitBytesAt(position: Long): Long = window.awaitBytesAt(position)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        window.read(position, buffer, offset, length)

    override fun close() = window.close()
}
