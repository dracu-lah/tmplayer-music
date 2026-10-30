package com.tmplayer.music.data

import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The file ids TDLib hands out belong to the session that handed them out. After a restart the id
 * saved in the index means nothing, or worse, another file: TDLib answered "File not found" to
 * 32,000 of 35,000 saved ids on a real account (2026-10-30), and the tag reader wrote them all off.
 * So the message is the durable identity, and its file id is asked again from it once per session
 * (a `getMessage`, answered from TDLib's own message database without the network when it can).
 *
 * Ids the scanner saw in this session are recorded as it stores them ([prime]), so a fresh library
 * costs no lookups at all. Both maps are bounded and emptied when the TDLib session changes.
 */
object FileIds {
    private const val TAG = "FileIds"
    private const val MAX = 8_000
    private const val LOOKUP_TIMEOUT_MS = 15_000L

    /** The audio (or lyrics) file and the cover thumbnail of one message, as of this session. */
    data class Current(val fileId: Int, val thumbFileId: Int)

    private val byMessage = object : LinkedHashMap<TrackId, Current>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackId, Current>?) = size > MAX
    }
    private val fresh = object : LinkedHashMap<Int, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) = size > MAX * 2
    }
    private var generation = -1L

    /** Empties both maps when TDLib's session is not the one they were filled in. */
    private fun syncGeneration(now: Long) {
        if (now != generation) {
            byMessage.clear()
            fresh.clear()
            generation = now
        }
    }

    private fun put(id: TrackId, current: Current) {
        byMessage[id] = current
        if (current.fileId != 0) fresh[current.fileId] = Unit
        if (current.thumbFileId != 0) fresh[current.thumbFileId] = Unit
    }

    /** Records the ids of tracks just read from Telegram in this session. */
    fun prime(tracks: List<Track>) {
        val now = Td.currentGeneration() ?: return
        synchronized(this) {
            syncGeneration(now)
            tracks.forEach { put(it.id, Current(it.fileId, it.thumbFileId)) }
        }
    }

    /** Records the ids of lyrics files just read from Telegram in this session. */
    fun primeLyrics(files: List<LyricsFile>) {
        val now = Td.currentGeneration() ?: return
        synchronized(this) {
            syncGeneration(now)
            files.forEach { put(TrackId(it.chatId, it.messageId), Current(it.fileId, 0)) }
        }
    }

    /** True when [fileId] was handed out in this session, so it names the file it did then. */
    fun isFresh(fileId: Int): Boolean {
        if (fileId == 0) return false
        val now = Td.currentGeneration() ?: return false
        return synchronized(this) {
            syncGeneration(now)
            fresh[fileId] != null
        }
    }

    /**
     * The current ids of message [id]'s file, or null when the message is gone, not a file the
     * library keeps, or TDLib cannot be reached.
     */
    suspend fun of(id: TrackId): Current? {
        val session = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { Td.awaitAuthorizedSession() } ?: return null
        synchronized(this) {
            syncGeneration(session.generation)
            byMessage[id]?.let { return it }
        }
        val message = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { session.client.getMessage(id.chatId, id.messageId) }
            ?.valueOrNull
        if (message == null) {
            Logger.w(TAG, "no message ${id.chatId}/${id.messageId}")
            return null
        }
        val current = when (val mapped = runCatching { TrackMapper.fromMessage(message) }.getOrNull()) {
            is Mapped.Audio -> Current(mapped.track.fileId, mapped.track.thumbFileId)
            is Mapped.Lyrics -> Current(mapped.file.fileId, 0)
            null -> return null
        }
        synchronized(this) {
            syncGeneration(session.generation)
            put(id, current)
        }
        return current
    }
}
