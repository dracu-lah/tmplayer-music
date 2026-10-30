package com.tmplayer.music.data

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Tags
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.tags.TagReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the enricher finds tracks to read and leaves what it read: the index, behind a seam. */
interface TagStore {
    /** Up to [limit] tracks whose tags have not been read yet, newest first. */
    suspend fun pendingTags(limit: Int): List<Track>

    /** The tags read for [id]; [coverKey] blank when no cover was saved. Null [tags] marks it failed. */
    suspend fun applyTags(id: TrackId, tags: Tags?, coverKey: String)
}

/** How the enricher reaches a file's bytes: TDLib's partial download, behind a seam. */
interface FileBytes {
    /** [length] bytes from [offset] (fewer at the end of the file), or null when they cannot be had. */
    suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray?

    /** True when the file is already on the device in full, so nothing has to be fetched or deleted. */
    suspend fun isComplete(fileId: Int): Boolean

    /** Drops the partial copy this reading made, so 3,000 tag reads do not fill the cache. */
    suspend fun forget(fileId: Int)
}

/** Where covers (and lyrics) read from tags are kept, by key. */
interface CoverStore {
    /** Saves [data] under [key] and returns the key, or blank when it could not be saved. */
    suspend fun save(key: String, data: ByteArray): String
}

data class EnrichStatus(val running: Boolean = false, val done: Int = 0, val failed: Int = 0)

/**
 * Reads the real tags of indexed tracks, a few files at a time, after the scan has listed them
 * (`plan/RESEARCH.md` 6.2). Only the head of each file is fetched: [TagReader.HEAD_BYTES], or as much
 * as [TagReader.bytesNeeded] says the tag block takes up to [MAX_TAG_BYTES]; the tail too when the
 * head leaves the duration unknown (an MP4 with its index at the end, an Ogg file). A partial copy
 * made for this is deleted afterwards unless the file was already on the device.
 *
 * It stops by itself once nothing is pending, and waits while [paused] (the scan's Pause). A track
 * that cannot be read keeps Telegram's fields and is marked failed, so it is not tried for ever.
 */
class TagEnricher(
    private val store: TagStore,
    private val bytes: FileBytes,
    private val covers: CoverStore,
    private val scope: CoroutineScope,
    /** Unsynced or LRC lyrics found in a tag, by the file's unique id; null keeps none. */
    private val lyrics: CoverStore? = null,
    private val paused: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _status = MutableStateFlow(EnrichStatus())
    val status: StateFlow<EnrichStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var worker: Job? = null

    /** Starts reading if it is not already; cheap to call after every scanned page. */
    fun kick() {
        scope.launch {
            lock.withLock {
                if (worker?.isActive == true) return@withLock
                _status.update { it.copy(running = true) }
                worker = scope.launch { drain() }
            }
        }
    }

    private suspend fun drain() {
        try {
            while (true) {
                paused.first { !it }
                val batch = store.pendingTags(BATCH)
                if (batch.isEmpty()) break
                coroutineScope {
                    batch.chunked((batch.size + PARALLEL - 1) / PARALLEL).map { part ->
                        async { part.forEach { enrich(it) } }
                    }.awaitAll()
                }
            }
        } finally {
            lock.withLock {
                worker = null
                _status.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun enrich(track: Track) {
        val tags = try {
            readTags(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "reading the tags of ${track.chatId}/${track.messageId} failed", e)
            null
        }
        val coverKey = tags?.cover?.let { picture ->
            runCatching { covers.save(track.uniqueId, picture.data) }.getOrDefault("")
        }.orEmpty()
        tags?.lyrics?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { lyrics?.save(track.uniqueId, text.toByteArray(Charsets.UTF_8)) }
        }
        store.applyTags(track.id, tags, coverKey)
        _status.update { if (tags == null) it.copy(failed = it.failed + 1) else it.copy(done = it.done + 1) }
    }

    /** The tags of [track]'s file, or null when its bytes could not be had at all. */
    private suspend fun readTags(track: Track): Tags? {
        val wasComplete = bytes.isComplete(track.fileId)
        try {
            val first = minOf(TagReader.HEAD_BYTES.toLong(), sizeOrMax(track)).toInt()
            var head = bytes.read(track.fileId, 0, first) ?: return null
            val needed = TagReader.bytesNeeded(head)
            if (needed != null && needed > head.size && needed <= MAX_TAG_BYTES) {
                // A little past the tag, so the first audio frame (an MP3's duration) comes too.
                head = bytes.read(track.fileId, 0, (needed + FRAME_MARGIN).toInt()) ?: head
            }
            var tags = TagReader.read(head, track.fileName, track.sizeBytes)
            if (tags.durationMs == 0L && track.sizeBytes > head.size && wantsTail(tags.format, track)) {
                val length = minOf(TAIL_BYTES.toLong(), track.sizeBytes).toInt()
                val tail = bytes.read(track.fileId, track.sizeBytes - length, length)
                if (tail != null) tags = TagReader.read(head, track.fileName, track.sizeBytes, tail)
            }
            return tags
        } finally {
            if (!wasComplete) runCatching { bytes.forget(track.fileId) }
        }
    }

    private fun sizeOrMax(track: Track): Long = if (track.sizeBytes > 0) track.sizeBytes else Long.MAX_VALUE

    /** Formats whose duration (or whole index) may sit at the end of the file. */
    private fun wantsTail(format: AudioFormat, track: Track): Boolean =
        format == AudioFormat.M4A || format == AudioFormat.ALAC || format == AudioFormat.OGG ||
            format == AudioFormat.OPUS || track.format == AudioFormat.M4A || track.format == AudioFormat.OGG ||
            track.format == AudioFormat.OPUS

    companion object {
        private const val TAG = "TagEnricher"
        const val BATCH = 24
        const val PARALLEL = 3

        /** A tag block bigger than this (a huge embedded cover) is read as far as the head goes. */
        const val MAX_TAG_BYTES = 2L * 1024 * 1024
        const val TAIL_BYTES = 128 * 1024
        const val FRAME_MARGIN = 16 * 1024
    }
}
