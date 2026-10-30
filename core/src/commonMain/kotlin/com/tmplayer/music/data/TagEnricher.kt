package com.tmplayer.music.data

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.TagCounts
import com.tmplayer.music.library.TagResult
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the enricher finds tracks to read and leaves what it read: the index, behind a seam. */
interface TagStore {
    /** Up to [limit] tracks whose tags have not been read yet, newest first. */
    suspend fun pendingTags(limit: Int): List<Track>

    /** The tracks among [ids] that still wait for their tags; for reading what is on screen first. */
    suspend fun pendingAmong(ids: Collection<TrackId>): List<Track> = emptyList()

    /** What one batch read, written together. A null [TagResult.tags] marks that file unreadable. */
    suspend fun applyTags(results: List<TagResult>)

    /** How far the library is: files waiting, read and unreadable. */
    suspend fun counts(): TagCounts
}

/** How the enricher reaches a file's bytes: TDLib's partial download, behind a seam. */
interface FileBytes {
    /**
     * The id of [track]'s file in this TDLib session, or null when the message is gone or cannot
     * be reached. The id saved in the index belongs to the session that scanned it.
     */
    suspend fun fileIdOf(track: Track): Int? = track.fileId

    /** [length] bytes from [offset] (fewer at the end of the file), or null when they cannot be had. */
    suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray?

    /**
     * True when the file is already on the device, in full or in part for someone else (the player),
     * so the partial copy is not the reader's to delete afterwards.
     */
    suspend fun isComplete(fileId: Int): Boolean

    /** Drops the partial copy this reading made, so 3,000 tag reads do not fill the cache. */
    suspend fun forget(fileId: Int)
}

/** Where covers (and lyrics) read from tags are kept, by key. */
interface CoverStore {
    /** Saves [data] under [key] and returns the key, or blank when it could not be saved. */
    suspend fun save(key: String, data: ByteArray): String
}

/**
 * What the tag reader is doing, for the banner and Settings. [counts] come from the index, so
 * "N of M" survives a restart; [waiting] is true while it backs off because Telegram gave no bytes.
 */
data class EnrichStatus(
    val running: Boolean = false,
    val paused: Boolean = false,
    val waiting: Boolean = false,
    val counts: TagCounts = TagCounts(),
)

/**
 * Reads the real tags of indexed tracks, a few files at a time, after the scan has listed them
 * (`plan/RESEARCH.md` 6.2). Only the head of each file is fetched: [TagReader.HEAD_BYTES], or as much
 * as [TagReader.bytesNeeded] says the tag block takes up to [MAX_TAG_BYTES]; the tail too when the
 * head leaves the duration unknown (an MP4 with its index at the end, an Ogg file). A partial copy
 * made for this is deleted afterwards unless the file was already on the device.
 *
 * It is a background job on phones and TV sticks, so it is kept gentle: [PARALLEL] files at a time,
 * a batch of [BATCH] written in one transaction, a pause of [gapMs] between batches, and the
 * parsing on low priority threads. Files the user has on screen ([prefer]) are read first.
 *
 * It stops by itself once nothing is pending, and waits while [paused] (the scan's Pause). A file
 * whose tags cannot be parsed keeps Telegram's fields and is marked failed, so it is not tried for
 * ever. A file whose bytes cannot be had is different: when a whole batch gets none, the connection
 * may be the cause, so the reader waits ([backoffMs], doubling) and stops after [MAX_BACKOFFS] rounds
 * until the next kick; while [online] is false it waits for the connection and marks nothing. A
 * file that gets no bytes [MAX_ATTEMPTS] times while online is marked failed.
 */
class TagEnricher(
    private val store: TagStore,
    private val bytes: FileBytes,
    private val covers: CoverStore,
    private val scope: CoroutineScope,
    /** Unsynced or LRC lyrics found in a tag, by the file's unique id; null keeps none. */
    private val lyrics: CoverStore? = null,
    private val paused: StateFlow<Boolean> = MutableStateFlow(false),
    /** Whether Telegram is reachable; while it is not, the reader waits and marks nothing. */
    private val online: StateFlow<Boolean> = MutableStateFlow(true),
    private val gapMs: Long = BATCH_GAP_MS,
    private val backoffMs: Long = BACKOFF_MS,
    /** Where the parsing runs; low priority threads in the app. */
    private val work: CoroutineContext = EmptyCoroutineContext,
) {
    private val _status = MutableStateFlow(EnrichStatus())
    val status: StateFlow<EnrichStatus> = _status.asStateFlow()

    private val lock = Mutex()
    private var worker: Job? = null

    /** Files that got no bytes while others did, by id; bounded. */
    private val attempts = HashMap<TrackId, Int>()

    /** Tracks on screen, read before the rest; replaced on every call and bounded. */
    @Volatile
    private var preferred: List<TrackId> = emptyList()

    /** Starts reading if it is not already; cheap to call after every scanned page. */
    fun kick() {
        scope.launch {
            lock.withLock {
                if (worker?.isActive == true) return@withLock
                _status.update { it.copy(running = true, waiting = false) }
                worker = scope.launch { drain() }
            }
        }
    }

    /** Reads [ids] (the rows on screen) next, if they still wait. Only the last call counts. */
    fun prefer(ids: List<TrackId>) {
        preferred = ids.take(BATCH)
    }

    /** Refreshes the counts from the index without starting anything, for a screen that opens. */
    suspend fun refreshCounts() {
        val counts = runCatching { store.counts() }.getOrNull() ?: return
        _status.update { it.copy(counts = counts) }
    }

    private suspend fun drain() {
        var backoffs = 0
        try {
            refreshCounts()
            while (true) {
                waitWhilePaused()
                if (!online.value) {
                    _status.update { it.copy(waiting = true) }
                    online.first { it }
                    _status.update { it.copy(waiting = false) }
                }
                val wanted = preferred
                preferred = emptyList()
                val first = if (wanted.isEmpty()) emptyList() else runCatching { store.pendingAmong(wanted) }.getOrDefault(emptyList())
                val rest = if (first.size >= BATCH) emptyList() else store.pendingTags(BATCH)
                val batch = (first + rest).distinctBy { it.id }.take(BATCH)
                if (batch.isEmpty()) break
                val outcomes = coroutineScope {
                    batch.chunked((batch.size + PARALLEL - 1) / PARALLEL).map { part ->
                        async {
                            part.map { track ->
                                waitWhilePaused()
                                track to enrich(track)
                            }
                        }
                    }.awaitAll().flatten()
                }
                val got = outcomes.count { it.second != null }
                // Without a connection a missing file proves nothing, so only tries made online count.
                val counts = online.value
                val results = outcomes.mapNotNull { (track, result) ->
                    result ?: run {
                        if (!counts) return@run null
                        val tries = (attempts[track.id] ?: 0) + 1
                        if (tries >= MAX_ATTEMPTS) {
                            attempts.remove(track.id)
                            TagResult(track.id, null)
                        } else {
                            if (attempts.size >= MAX_TRACKED) attempts.clear()
                            attempts[track.id] = tries
                            null
                        }
                    }
                }
                if (got == 0) {
                    if (results.isNotEmpty()) {
                        store.applyTags(results)
                        refreshCounts()
                    }
                    // No bytes for any file: Telegram is out of reach or refusing, so wait, longer each time.
                    if (++backoffs > MAX_BACKOFFS) {
                        Logger.w(TAG, "no bytes for $backoffs batches, stopping until the next kick")
                        break
                    }
                    _status.update { it.copy(waiting = true) }
                    delay(backoffMs shl (backoffs - 1).coerceAtMost(5))
                    _status.update { it.copy(waiting = false) }
                    continue
                }
                backoffs = 0
                store.applyTags(results)
                results.forEach { attempts.remove(it.id) }
                refreshCounts()
                delay(gapMs)
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    worker = null
                    _status.update { it.copy(running = false, waiting = false, paused = false) }
                }
            }
        }
    }

    private suspend fun waitWhilePaused() {
        if (!paused.value) return
        _status.update { it.copy(paused = true) }
        paused.first { !it }
        _status.update { it.copy(paused = false) }
    }

    /**
     * Reads one file. A [TagResult] when it is settled (tags read, or the bytes came and could not
     * be parsed, which marks it failed); null when no bytes came at all, so it can be tried again.
     */
    private suspend fun enrich(track: Track): TagResult? {
        val tags = try {
            readTags(track) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "reading the tags of ${track.chatId}/${track.messageId} failed", e)
            return TagResult(track.id, null)
        }
        val coverKey = tags.cover?.let { picture ->
            runCatching { covers.save(track.uniqueId, picture.data) }.getOrDefault("")
        }.orEmpty()
        tags.lyrics.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { lyrics?.save(track.uniqueId, text.toByteArray(Charsets.UTF_8)) }
        }
        return TagResult(track.id, tags, coverKey)
    }

    /**
     * The tags of [track]'s file, or null when its bytes could not be had at all. Throws when the
     * bytes came and could not be parsed. Parsing runs on [work].
     */
    private suspend fun readTags(track: Track): Tags? {
        val fileId: Int
        val wasComplete = try {
            fileId = bytes.fileIdOf(track)?.takeIf { it != 0 } ?: return null
            bytes.isComplete(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        try {
            val first = minOf(TagReader.HEAD_BYTES.toLong(), sizeOrMax(track)).toInt()
            var head = fetch(track, fileId, 0, first) ?: return null
            val needed = withContext(work) { TagReader.bytesNeeded(head) }
            if (needed != null && needed > head.size && needed <= MAX_TAG_BYTES) {
                // A little past the tag, so the first audio frame (an MP3's duration) comes too.
                head = fetch(track, fileId, 0, (needed + FRAME_MARGIN).toInt()) ?: head
            }
            val headBytes = head
            var tags = withContext(work) { TagReader.read(headBytes, track.fileName, track.sizeBytes) }
            if (tags.durationMs == 0L && track.sizeBytes > head.size && wantsTail(tags.format, track)) {
                val length = minOf(TAIL_BYTES.toLong(), track.sizeBytes).toInt()
                val tail = fetch(track, fileId, track.sizeBytes - length, length)
                if (tail != null) tags = withContext(work) { TagReader.read(headBytes, track.fileName, track.sizeBytes, tail) }
            }
            return tags
        } finally {
            if (!wasComplete) runCatching { bytes.forget(fileId) }
        }
    }

    /** Some bytes of [track]'s file; null when they could not be had, a failure included. */
    private suspend fun fetch(track: Track, fileId: Int, offset: Long, length: Int): ByteArray? = try {
        bytes.read(fileId, offset, length)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "fetching ${track.chatId}/${track.messageId} failed", e)
        null
    }

    private fun sizeOrMax(track: Track): Long = if (track.sizeBytes > 0) track.sizeBytes else Long.MAX_VALUE

    /** Formats whose duration (or whole index) may sit at the end of the file. */
    private fun wantsTail(format: AudioFormat, track: Track): Boolean =
        format == AudioFormat.M4A || format == AudioFormat.ALAC || format == AudioFormat.OGG ||
            format == AudioFormat.OPUS || track.format == AudioFormat.M4A || track.format == AudioFormat.OGG ||
            track.format == AudioFormat.OPUS

    companion object {
        private const val TAG = "TagEnricher"
        const val BATCH = 12
        const val PARALLEL = 2

        /** The pause between batches, so the reader leaves the CPU and the connection room. */
        const val BATCH_GAP_MS = 1_000L

        /** The first wait when a whole batch got no bytes; it doubles each round. */
        const val BACKOFF_MS = 15_000L
        const val MAX_BACKOFFS = 6
        const val MAX_ATTEMPTS = 3
        private const val MAX_TRACKED = 2_000

        /** A tag block bigger than this (a huge embedded cover) is read as far as the head goes. */
        const val MAX_TAG_BYTES = 2L * 1024 * 1024
        const val TAIL_BYTES = 128 * 1024
        const val FRAME_MARGIN = 16 * 1024
    }
}
