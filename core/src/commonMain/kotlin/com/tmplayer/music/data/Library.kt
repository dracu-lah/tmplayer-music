package com.tmplayer.music.data

import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.lyrics.Lyrics
import com.tmplayer.music.lyrics.LyricsParser
import com.tmplayer.music.library.LibraryIndex
import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.ScanCursor
import com.tmplayer.music.library.TagCounts
import com.tmplayer.music.library.TagResult
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The music library for the whole process: the index on disk, the scanner that fills it from the
 * chosen chats, and the tag reader that improves it. Started once by the app with its own folder.
 *
 * The library belongs to one Telegram account. When a different account signs in, or the user
 * logs out, it is wiped: rows of chats the user can no longer see must not linger
 * (`plan/RESEARCH.md` 15, AGENTS.md "leaving a chat or logging out purges its rows").
 */
object Library {
    private const val TAG = "Library"

    private val scope = JobFailures.scope("Library", Dispatchers.IO)
    private val gate = Mutex()

    private lateinit var dir: File
    private lateinit var index: LibraryIndex
    lateinit var scanner: LibraryScanner
        private set
    lateinit var enricher: TagEnricher
        private set

    val directory = ChatDirectory { Td.awaitAuthorizedSession().client }

    /** Bumped whenever the index changed, so open lists reload their first page. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** False until the account check has run after sign in, so screens do not show another account's rows. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val paused = MutableStateFlow(false)

    @Volatile
    private var started = false

    fun start(folder: File) {
        synchronized(this) {
            if (started) return
            started = true
        }
        dir = folder.apply { mkdirs() }
        index = LibraryIndex.open(File(dir, "library.db").path)
        scanner = LibraryScanner(IndexScanStore(), ::fetchPage, scope)
        enricher = TagEnricher(
            IndexTagStore(),
            TdFileBytes,
            DiskCovers(File(dir, "covers")),
            scope,
            lyrics = DiskCovers(File(dir, "lyrics")),
            paused = paused,
            online = Td.connected,
            work = lowPriority,
        )
        scope.launch {
            Td.auth.map { it is AuthState.Ready }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) onSignedIn() else _ready.value = false
            }
        }
    }

    /** The index, once the account check is done. */
    suspend fun index(): LibraryIndex = gate.withLock { index }

    /** Tells open lists the index changed outside the scan (a play, a favourite, a playlist edit). */
    fun notifyChanged() {
        _changes.value++
    }

    private val bumpLock = Any()
    private var lastBumpAt = 0L
    private var bumpPending = false

    /**
     * The scan and the tag reader change the index many times a second. Every bump makes each open
     * list, the Home rows and the counts query the index again, so theirs are spread out: the first
     * after a quiet spell goes at once, the rest are folded into one at most every [BUMP_GAP_MS].
     */
    private fun notifyChangedSoon() {
        val wait: Long
        synchronized(bumpLock) {
            if (bumpPending) return
            val now = System.currentTimeMillis()
            wait = (lastBumpAt + BUMP_GAP_MS - now).coerceAtLeast(0)
            if (wait == 0L) lastBumpAt = now else bumpPending = true
        }
        if (wait == 0L) {
            _changes.value++
            return
        }
        scope.launch {
            delay(wait)
            synchronized(bumpLock) {
                bumpPending = false
                lastBumpAt = System.currentTimeMillis()
            }
            _changes.value++
        }
    }

    /** The user's new choice of chats: removed ones are purged, new ones scanned. */
    suspend fun pick(chats: List<ChatPick>) {
        val before = index().pickedChats().map { it.chatId }.toSet()
        val after = chats.map { it.chatId }.toSet()
        (before - after).forEach(scanner::forget)
        index().setPicked(chats)
        _changes.value++
        scanner.scan(after.toList())
    }

    /** Removes one chat and its tracks, for Manage chats. */
    suspend fun removeChat(chatId: Long) {
        scanner.forget(chatId)
        index().removeChat(chatId)
        _changes.value++
    }

    /** Scans every chosen chat again from where it stopped, and tops up the finished ones. */
    suspend fun refresh() {
        scanner.scan(index().pickedChats().map { it.chatId })
    }

    fun pause() {
        paused.value = true
        scanner.pause()
    }

    fun resume() {
        paused.value = false
        scanner.resume()
        enricher.kick()
    }

    /** True while the user paused the scan and the tag reader. */
    val isPaused: StateFlow<Boolean> = paused.asStateFlow()

    /**
     * What the banner and Settings say, at most twice a second: the scanner and the tag reader
     * change their status far more often than anyone can read. Quiet while nothing changes.
     */
    fun view(): Flow<LibraryView> =
        combine(scanner.status, enricher.status, paused, Td.connected) { scan, tags, held, online ->
            LibraryStatus.view(scan, tags, held, online)
        }.distinctUntilChanged().conflate().transform {
            emit(it)
            delay(VIEW_GAP_MS)
        }

    /** Puts the files whose tags could not be read back in the queue and starts reading. */
    suspend fun retryFailedTags() {
        index().retryFailedTags()
        enricher.kick()
    }

    /**
     * Two low priority threads for parsing tags, so the reader never competes with the screen or
     * the player for a core.
     */
    private val lowPriority = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "TagReader").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    /** Where a cover saved from tags lives, or null. */
    fun coverFile(key: String): File? = if (key.isBlank()) null else File(File(dir, "covers"), key).takeIf { it.isFile }

    private val lyricsCache = LyricsCache()

    /**
     * The lyrics of [track]: a `.lrc` (or `.txt`) with the same name in the same chat first, then
     * what its tags held. Null when there are none. The `.lrc` is fetched from Telegram once (small,
     * capped) and the parsed lyrics are remembered for the session.
     */
    suspend fun lyricsFor(track: Track): Lyrics? {
        val key = "${track.chatId}:${track.messageId}"
        lyricsCache.get(key)?.let { return it.value }
        val found = runCatching { lrcInChat(track) }.getOrNull()
            ?: runCatching {
                File(File(dir, "lyrics"), track.uniqueId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(120))
                    .takeIf { it.isFile && it.length() <= LyricsParser.MAX_BYTES }
                    ?.readBytes()
                    ?.let(LyricsParser::parse)
            }.getOrNull()
        lyricsCache.put(key, Cached(found))
        return found
    }

    private suspend fun lrcInChat(track: Track): Lyrics? {
        val file = index().lyricsFor(track.chatId, AudioKinds.baseName(track.fileName)) ?: return null
        if (file.sizeBytes > LyricsParser.MAX_BYTES) return null
        val fileId = FileIds.of(TrackId(file.chatId, file.messageId))?.fileId ?: return null
        val bytes = TdFileBytes.read(fileId, 0, file.sizeBytes.coerceAtMost(LyricsParser.MAX_BYTES.toLong()).toInt().coerceAtLeast(1))
            ?: return null
        return LyricsParser.parse(bytes)
    }

    /** Wipes the library before a log out, so nothing of this account stays on the device. */
    suspend fun wipe() = gate.withLock {
        Logger.i(TAG, "wiping the library")
        runCatching { index.close() }
        File(dir, "library.db").let { db -> listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() } }
        File(dir, "covers").deleteRecursively()
        File(dir, "lyrics").deleteRecursively()
        lyricsCache.evictAll()
        File(dir, ACCOUNT_FILE).delete()
        index = LibraryIndex.open(File(dir, "library.db").path)
        _changes.value++
    }

    private suspend fun onSignedIn() {
        val me = withTimeoutOrNull(30_000) { Td.awaitAuthorizedSession().client.getMe().valueOrNull?.id }
        if (me != null) {
            val file = File(dir, ACCOUNT_FILE)
            val stored = runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
            if (stored != null && stored != me) wipe()
            runCatching { file.writeText(me.toString()) }
        }
        _ready.value = true
        _changes.value++
        refresh()
        enricher.kick()
    }

    // The seams the scanner and the tag reader work through.

    private class IndexScanStore : ScanStore {
        override suspend fun cursor(chatId: Long, pass: ScanPass): ScanCursor {
            val state = index().scanState(chatId) ?: return ScanCursor()
            return if (pass == ScanPass.Audio) state.audio else state.documents
        }

        override suspend fun saveCursor(chatId: Long, pass: ScanPass, cursor: ScanCursor) {
            val index = index()
            val state = index.scanState(chatId) ?: return
            val now = System.currentTimeMillis() / 1000
            index.saveScanState(
                chatId,
                if (pass == ScanPass.Audio) state.copy(audio = cursor, lastScanAt = now) else state.copy(documents = cursor, lastScanAt = now),
            )
        }

        override suspend fun addTracks(tracks: List<Track>) {
            FileIds.prime(tracks)
            index().upsert(tracks)
            notifyChangedSoon()
            enricher.kick()
        }

        override suspend fun addLyrics(files: List<LyricsFile>) {
            FileIds.primeLyrics(files)
            index().upsertLyricsFiles(files)
        }
    }

    private class IndexTagStore : TagStore {
        override suspend fun pendingTags(limit: Int): List<Track> = index().pendingTags(limit)

        override suspend fun pendingAmong(ids: Collection<TrackId>): List<Track> = index().pendingAmong(ids)

        override suspend fun applyTags(results: List<TagResult>) {
            if (results.isEmpty()) return
            index().applyTags(results)
            notifyChangedSoon()
        }

        override suspend fun counts(): TagCounts = index().tagCounts()
    }

    /** One page of a chat's audio or document search, mapped. */
    private suspend fun fetchPage(chatId: Long, pass: ScanPass, from: Long): PageResult {
        val td: TdlClient = withTimeoutOrNull(60_000) { Td.awaitAuthorizedSession().client }
            ?: return PageResult.Failed(L_NOT_CONNECTED)
        val filter = if (pass == ScanPass.Audio) SearchMessagesFilterAudio() else SearchMessagesFilterDocument()
        val result = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            td.searchChatMessages(chatId, null, "", null, from, 0, LibraryScanner.PAGE_SIZE, filter)
        } ?: return PageResult.Failed("timeout")
        return when (result) {
            is TdlResult.Success -> {
                val messages = result.result.messages.toList()
                PageResult.Page(
                    messageIds = messages.map { it.id },
                    items = messages.mapNotNull { runCatching { TrackMapper.fromMessage(it) }.getOrNull() },
                    nextFromMessageId = result.result.nextFromMessageId,
                )
            }
            is TdlResult.Failure -> Failures.floodWaitSeconds(result.message)?.let { PageResult.FloodWait(it) }
                ?: PageResult.Failed(result.message)
        }
    }

    private const val BUMP_GAP_MS = 2_000L
    private const val VIEW_GAP_MS = 500L
    private const val ACCOUNT_FILE = "account"
    private const val PAGE_TIMEOUT_MS = 30_000L
    private const val L_NOT_CONNECTED = "NETWORK_ERROR"
}

/** A held answer, so "no lyrics" is remembered too. */
private class Cached(val value: Lyrics?)

/** The parsed lyrics of the last tracks asked about; bounded. */
private class LyricsCache {
    private val map = object : java.util.LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 64
    }

    @Synchronized
    fun get(key: String): Cached? = map[key]

    @Synchronized
    fun put(key: String, value: Cached) {
        map[key] = value
    }

    @Synchronized
    fun evictAll() = map.clear()
}

/** A file's bytes through TDLib's partial download. */
private object TdFileBytes : FileBytes {
    private suspend fun td() = withTimeoutOrNull(60_000) { Td.awaitAuthorizedSession().client }

    override suspend fun fileIdOf(track: Track): Int? = FileIds.of(track.id)?.fileId

    override suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray? {
        val td = td() ?: return null
        if (length <= 0 || offset < 0) return null
        // A file the player is fetching: a second request with other bounds would stall both.
        val file = td.getFile(fileId).valueOrNull
        if (file?.local?.isDownloadingActive == true) return null
        val done = withTimeoutOrNull(READ_TIMEOUT_MS) { td.downloadFile(fileId, 1, offset, length.toLong(), true) }
        if (done !is TdlResult.Success) {
            Logger.w("TdFileBytes", "download of $fileId failed: ${(done as? TdlResult.Failure)?.message ?: "timeout"}")
            return null
        }
        val part = td.readFilePart(fileId, offset, length.toLong())
        if (part is TdlResult.Failure) Logger.w("TdFileBytes", "reading $fileId failed: ${part.message}")
        return part.valueOrNull?.data
    }

    /**
     * True when the file is on the device in full, or in part for someone else (the player streams
     * it, or streamed it): its bytes are not the tag reader's to delete.
     */
    override suspend fun isComplete(fileId: Int): Boolean {
        val local = td()?.getFile(fileId)?.valueOrNull?.local ?: return false
        return local.isDownloadingCompleted || local.isDownloadingActive || local.downloadedSize > 0
    }

    override suspend fun forget(fileId: Int) {
        val td = td() ?: return
        runCatching { td.cancelDownloadFile(fileId, false) }
        runCatching { td.deleteFile(fileId) }
    }

    /** A tag read that takes longer is given up and tried later, so one stuck file does not hold a batch. */
    private const val READ_TIMEOUT_MS = 20_000L
}

/**
 * Covers read from tags, one file per key. Written to a temporary name and renamed, so a cut off
 * write never leaves half a picture; skipped when the disk is nearly full.
 */
private class DiskCovers(private val folder: File) : CoverStore {
    override suspend fun save(key: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        val name = key.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(120)
        if (name.isEmpty() || data.isEmpty()) return@withContext ""
        folder.mkdirs()
        if (folder.usableSpace < data.size + MIN_FREE_BYTES) return@withContext ""
        val target = File(folder, name)
        if (target.isFile) return@withContext name
        val temp = File(folder, "$name.part")
        try {
            temp.writeBytes(data)
            if (temp.renameTo(target)) name else ""
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
