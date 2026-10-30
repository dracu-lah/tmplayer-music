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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Up to [limit] waiting tracks whose duration is unknown (audio sent as documents), newest first. */
    suspend fun pendingDocuments(limit: Int): List<Track> = emptyList()

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
    /**
     * False while the reader only reads what is on screen or about to play: a few files the banner
     * does not need to announce. True for the documents and the background trickle.
     */
    val background: Boolean = true,
)

/**
 * Reads the real tags of indexed tracks, a few files at a time, after the scan has listed them
 * Only the head of each file is fetched: [TagReader.HEAD_BYTES], or as much
 * as [TagReader.bytesNeeded] says the tag block takes up to [MAX_TAG_BYTES]; the tail too when the
 * head leaves the duration unknown (an MP4 with its index at the end, an Ogg file). A partial copy
 * made for this is deleted afterwards unless the file was already on the device.
 *
 * It is a background job on phones and TV sticks, so it is kept gentle: [PARALLEL] files at a time,
 * a batch of [BATCH] written in one transaction, a pause of [gapMs] between batches, and the
 * parsing on low priority threads. Files the user has on screen ([prefer]) are read first.
 *
 * What it reads, and in which order, follows [policy] and [TagPriority]: (a) the rows on screen
 * ([prefer]), (b) the playing track and the next few ([preferUpcoming]), (c) audio documents whose
 * length is unknown, (d) the rest as a gentle trickle. The app's policy keeps (c) bounded and (d)
 * off on mobile data unless the user said yes, and (d) off while the app is neither on screen nor
 * playing. Every byte fetched is reported to [onBytes], for the data used line in Settings.
 *
 * It stops by itself once nothing allowed is pending (a change of policy or a [prefer] starts it
 * again). While [paused] (the scan's Pause) only the background stops: the rows on screen and the
 * tracks about to play are still read, and with none of those it waits for Resume or a new
 * request. After a background batch it rests as [TricklePacing] says. A file
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
    /** What may be read now; everything unless the app says otherwise. */
    private val policy: StateFlow<ReadPolicy> = MutableStateFlow(ReadPolicy.ALL),
    /** Bytes fetched by one read, for the data used counter. */
    private val onBytes: (Long) -> Unit = {},
    /** Files per batch; smaller on a low memory device. */
    private val batchSize: Int = BATCH,
    /** A low memory device: the background reading is paced slower ([TricklePacing]). */
    private val lowRam: Boolean = false,
    /** Milliseconds from any fixed point, for timing a batch; the tests pass the virtual clock. */
    private val clock: () -> Long = MONOTONIC_MS,
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

    /** The playing track and the next few; kept until the queue moves on. */
    @Volatile
    private var upcoming: List<TrackId> = emptyList()

    /** Documents read under a [ReadPolicy.documentsCap] this session. */
    @Volatile
    private var cappedDocumentsRead = 0

    /** Moves on with each request for rows on screen or tracks about to play: ends a rest or a paused wait. */
    private val wake = MutableStateFlow(0)

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
        preferred = ids.take(batchSize)
        if (ids.isNotEmpty()) {
            wake.update { it + 1 }
            kick()
        }
    }

    /** Reads [ids] (the playing track first, then the next few) before the rest, if they still wait. */
    fun preferUpcoming(ids: List<TrackId>) {
        val next = ids.take(UPCOMING)
        if (next == upcoming) return
        upcoming = next
        if (next.isNotEmpty()) {
            wake.update { it + 1 }
            kick()
        }
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
                if (!online.value) {
                    _status.update { it.copy(waiting = true) }
                    online.first { it }
                    _status.update { it.copy(waiting = false) }
                }
                // Read before the requests below, so one that comes while this round runs is not missed.
                val seen = wake.value
                val held = paused.value
                val planned = policy.value
                // The Pause stops the background only; what is on screen or about to play is still read.
                val allowed = planned.let { if (held) it.copy(documents = false, trickle = false) else it }
                val wanted = preferred
                preferred = emptyList()
                val next = upcoming
                val onScreen = if (!allowed.onDemand || wanted.isEmpty()) emptyList() else runCatching { store.pendingAmong(wanted) }.getOrDefault(emptyList())
                val soon = if (!allowed.onDemand || next.isEmpty()) emptyList() else runCatching { store.pendingAmong(next) }.getOrDefault(emptyList())
                val room = batchSize - onScreen.size - soon.size
                val documentsLeft = if (allowed.documentsCap == Int.MAX_VALUE) Int.MAX_VALUE else allowed.documentsCap - cappedDocumentsRead
                val documents = if (room <= 0 || !allowed.documents || documentsLeft <= 0) emptyList() else store.pendingDocuments(batchSize)
                val rest = if (room <= 0 || !allowed.trickle) emptyList() else store.pendingTags(batchSize)
                val batch = TagPriority.plan(onScreen, soon, documents, rest, allowed, batchSize, documentsLeft)
                if (batch.isEmpty()) {
                    if (!held) break
                    // Paused with nothing on screen to read: wait for Resume or the next request.
                    _status.update { it.copy(paused = true) }
                    combine(paused, wake) { p, w -> !p || w != seen }.first { it }
                    _status.update { it.copy(paused = false) }
                    continue
                }
                val asked = (onScreen + soon).mapTo(HashSet()) { it.id }
                val startedAt = clock()
                if (documentsLeft != Int.MAX_VALUE) {
                    val docIds = documents.mapTo(HashSet()) { it.id }
                    cappedDocumentsRead += batch.count { it.id in docIds && it.id !in asked }
                }
                _status.update { it.copy(background = batch.any { t -> t.id !in asked }) }
                val outcomes = coroutineScope {
                    batch.chunked((batch.size + PARALLEL - 1) / PARALLEL).map { part ->
                        async {
                            part.mapNotNull { track ->
                                // Paused part way: the background files of this batch wait for later.
                                if (paused.value && track.id !in asked) null else track to enrich(track)
                            }
                        }
                    }.awaitAll().flatten()
                }
                // Paused before any of it ran: plan again (on-screen reads only, or wait for Resume).
                if (outcomes.isEmpty()) continue
                val background = outcomes.count { it.first.id !in asked }
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
                val restMs = if (background == 0) gapMs else TricklePacing.restAfter(clock() - startedAt, background, lowRam, gapMs)
                // A request for what is on screen or about to play ends the rest at once, and so does a
                // change of policy or Pause (the app left the screen), so the next plan can stop the reader.
                withTimeoutOrNull(restMs) {
                    combine(wake, policy, paused) { w, p, h -> w != seen || p != planned || h != held }.first { it }
                }
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    worker = null
                    _status.update { it.copy(running = false, waiting = false, paused = false, background = true) }
                }
            }
        }
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
        bytes.read(fileId, offset, length)?.also { got -> runCatching { onBytes(got.size.toLong()) } }
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
        private val ORIGIN = kotlin.time.TimeSource.Monotonic.markNow()

        /** Milliseconds on a monotonic clock, for timing batches. */
        val MONOTONIC_MS: () -> Long = { ORIGIN.elapsedNow().inWholeMilliseconds }
        const val BATCH = 12

        /** The batch on a low memory device. */
        const val SMALL_BATCH = 6
        const val PARALLEL = 2

        /** The playing track and the next tracks read ahead of the rest. */
        const val UPCOMING = 4

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
