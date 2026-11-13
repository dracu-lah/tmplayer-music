package com.tmplayer.music.desktop.player

import com.tmplayer.music.data.FileIds
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.OfflineDownloads
import com.tmplayer.music.data.Td
import com.tmplayer.music.desktop.DesktopPaths
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Track
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.SavedQueue
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.source.MediaData
import java.io.File
import java.io.IOException

/**
 * The real [TrackSource]: the index says which file a queue entry is, TDLib streams it as it
 * downloads ([TdStreamBytes], through the same [com.tmplayer.music.player.TdByteWindow] the phone
 * plays through), and plays, spots and read ahead go back to the library, as the phone's
 * PlaybackService does.
 */
object TdTrackSource : TrackSource {
    private const val TAG = "TdTrackSource"
    private const val SESSION_TIMEOUT_MS = 120_000L

    override suspend fun resolve(item: SavedQueue.Item): QueueEntry? {
        val index = Library.index()
        val track = index.track(item.id)
            ?: item.songKey.takeIf { it.isNotBlank() }?.let { key -> index.song(key)?.let { index.track(it.defaultTrack) } }
            ?: return null
        return QueueEntry.of(track, item.songKey)
    }

    /** The version a song's entry plays: the one saved, else the song's default. Null when both are gone. */
    private suspend fun trackOf(entry: QueueEntry): Track? {
        val index = Library.index()
        return index.track(entry.id)
            ?: entry.songKey.takeIf { it.isNotBlank() }?.let { key -> index.song(key)?.let { index.track(it.defaultTrack) } }
    }

    override suspend fun open(entry: QueueEntry): MediaData {
        val track = trackOf(entry) ?: throw IOException(L.desktopTrackGone)
        // Kept offline: the file in the app's own folder plays with no TDLib at all.
        OfflineDownloads.pathOf(track.id)?.let { return LocalFileMediaData(it) }
        // The id saved in the index belongs to the session that scanned it: ask the message again.
        val current = FileIds.of(track.id)?.takeIf { it.fileId != 0 } ?: throw IOException(L.desktopTrackGone)
        val session = withTimeout(SESSION_TIMEOUT_MS) { Td.awaitAuthorizedSession() }
        val bytes = TdStreamBytes(session.client, current.fileId)
        try {
            withTimeout(SESSION_TIMEOUT_MS) { bytes.start() }
        } catch (e: Throwable) {
            bytes.close()
            throw e
        }
        return TdMediaData(bytes, uri = "tdfile://${current.fileId}")
    }

    override suspend fun gainOf(entry: QueueEntry): Float =
        trackOf(entry)?.replayGain?.factor() ?: 1f

    override suspend fun played(entry: QueueEntry, atSec: Long) {
        Library.index().recordPlay(entry.id, entry.songKey, atSec)
        Library.notifyChanged()
    }

    override suspend fun keep(entry: QueueEntry, positionMs: Long, durationMs: Long, atMs: Long) {
        Library.index().saveResume(entry.id, entry.songKey, positionMs.coerceAtLeast(0), durationMs, atMs)
    }

    override suspend fun finished(entry: QueueEntry) {
        Library.index().clearResume(entry.id)
        Library.notifyChanged()
    }

    override fun readAhead(entries: List<QueueEntry>) {
        runCatching { Library.enricher.preferUpcoming(entries.map { it.id }) }
            .onFailure { Logger.w(TAG, "reading ahead failed", it) }
    }
}

/** The queue in one file, replaced in one rename so a crash mid write leaves the old one. */
class FileQueueStore(private val file: File = DesktopPaths.queueFile) : QueueStore {
    override fun read(): String? = runCatching { file.takeIf { it.isFile && it.length() <= MAX_BYTES }?.readText() }.getOrNull()

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".part")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // Windows will not rename over a file that is open: a plain replace.
            file.delete()
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

/** A file on this computer (a kept track) as mpv's own input: no stream, no TDLib. */
fun LocalFileMediaData(file: File): MediaData = org.openani.mediamp.source.UriMediaData(file.absolutePath)
