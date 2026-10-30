package com.tmplayer.music.data

import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.LibraryIndex
import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.ScanCursor
import com.tmplayer.music.library.Tags
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.SearchMessagesFilterAudio
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        enricher = TagEnricher(IndexTagStore(), TdFileBytes, DiskCovers(File(dir, "covers")), scope, paused)
        scope.launch {
            Td.auth.map { it is AuthState.Ready }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) onSignedIn() else _ready.value = false
            }
        }
    }

    /** The index, once the account check is done. */
    suspend fun index(): LibraryIndex = gate.withLock { index }

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

    /** Where a cover saved from tags lives, or null. */
    fun coverFile(key: String): File? = if (key.isBlank()) null else File(File(dir, "covers"), key).takeIf { it.isFile }

    /** Wipes the library before a log out, so nothing of this account stays on the device. */
    suspend fun wipe() = gate.withLock {
        Logger.i(TAG, "wiping the library")
        runCatching { index.close() }
        File(dir, "library.db").let { db -> listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() } }
        File(dir, "covers").deleteRecursively()
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
            index().upsert(tracks)
            _changes.value++
            enricher.kick()
        }

        override suspend fun addLyrics(files: List<LyricsFile>) {
            index().upsertLyricsFiles(files)
        }
    }

    private class IndexTagStore : TagStore {
        override suspend fun pendingTags(limit: Int): List<Track> = index().pendingTags(limit)

        override suspend fun applyTags(id: TrackId, tags: Tags?, coverKey: String) {
            index().applyTags(id, tags, coverKey)
            _changes.value++
        }
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

    private const val ACCOUNT_FILE = "account"
    private const val PAGE_TIMEOUT_MS = 30_000L
    private const val L_NOT_CONNECTED = "NETWORK_ERROR"
}

/** A file's bytes through TDLib's partial download. */
private object TdFileBytes : FileBytes {
    private suspend fun td() = withTimeoutOrNull(60_000) { Td.awaitAuthorizedSession().client }

    override suspend fun read(fileId: Int, offset: Long, length: Int): ByteArray? {
        val td = td() ?: return null
        if (length <= 0 || offset < 0) return null
        val done = withTimeoutOrNull(READ_TIMEOUT_MS) { td.downloadFile(fileId, 1, offset, length.toLong(), true) }
        if (done !is TdlResult.Success) return null
        return td.readFilePart(fileId, offset, length.toLong()).valueOrNull?.data
    }

    override suspend fun isComplete(fileId: Int): Boolean =
        td()?.getFile(fileId)?.valueOrNull?.local?.isDownloadingCompleted == true

    override suspend fun forget(fileId: Int) {
        val td = td() ?: return
        runCatching { td.cancelDownloadFile(fileId, false) }
        runCatching { td.deleteFile(fileId) }
    }

    private const val READ_TIMEOUT_MS = 60_000L
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
