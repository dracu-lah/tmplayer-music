// Adapted from TMPlayer (GPL-3.0), ui/src/shared/kotlin/com/tmplayer/data/Thumbnails.kt (downloadThumbnail):
// look first, listen before asking, withdraw on the way out.
package com.tmplayer.music.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A TDLib file as [FileFetch] needs it: where it is on the disk and whether it is whole. */
data class FileState(val id: Int, val path: String, val complete: Boolean, val canDownload: Boolean = true)

/** The TDLib calls a small picture is fetched with, behind a seam for the tests. */
interface FileCalls {
    /** `getFile`: what TDLib knows of the file now; null when it would not say. */
    suspend fun file(fileId: Int): FileState?

    /** `downloadFile`, not waiting for it: the file's state as the download starts; null when refused. */
    suspend fun download(fileId: Int, priority: Int): FileState?

    /** Every file update TDLib sends. */
    val updates: Flow<FileState>

    /** `cancelDownloadFile`, so a picture nobody waits for does not hold the connection. */
    suspend fun cancel(fileId: Int)
}

/**
 * The local path of a small picture (a chat photo, an album cover thumbnail), downloading it first
 * when TDLib does not have it yet:
 *
 * - A file already whole on the disk is answered at once, with no download asked for.
 * - Otherwise the updates are listened to before the download is asked for, and the answer to the
 *   request is looked at first: a small picture often lands in the moment between the two, and an
 *   update missed there would leave the row blank until the time ran out.
 * - A refused download, a file that cannot be downloaded, or one that does not arrive within the
 *   time is null (the row keeps its initials), and an unfinished download is withdrawn, also when the
 *   row leaves the screen mid wait, so a long fling does not leave a queue of downloads behind.
 *
 * Only a finished file is answered: one still being written decodes as a strip of picture.
 */
object FileFetch {
    /** Pictures are small and plentiful; a low priority keeps them behind playback. */
    const val PRIORITY = 8
    const val TIMEOUT_MS = 20_000L

    suspend fun localPath(calls: FileCalls, fileId: Int, timeoutMs: Long = TIMEOUT_MS): String? {
        if (fileId <= 0) return null
        val now = calls.file(fileId) ?: return null
        if (now.complete) return now.path.takeIf { it.isNotEmpty() }
        if (!now.canDownload) return null
        var refused = false
        val done = try {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val completion = async(start = CoroutineStart.UNDISPATCHED) {
                        calls.updates.filter { it.id == fileId && it.complete }.first()
                    }
                    val started = calls.download(fileId, PRIORITY)
                    when {
                        started == null -> {
                            refused = true
                            completion.cancel()
                            null
                        }
                        started.complete -> {
                            completion.cancel()
                            started
                        }
                        else -> completion.await()
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { calls.cancel(fileId) } }
            throw e
        }
        if (refused) return null
        if (done == null) {
            runCatching { calls.cancel(fileId) }
            return null
        }
        return done.path.takeIf { it.isNotEmpty() } ?: calls.file(fileId)?.takeIf { it.complete }?.path?.takeIf { it.isNotEmpty() }
    }
}

/** [FileCalls] over a TDLib client. */
class TdFileCalls(private val td: TdlClient) : FileCalls {
    private fun state(file: dev.g000sha256.tdl.dto.File): FileState = FileState(
        id = file.id,
        path = file.local.path.orEmpty(),
        complete = file.local.isDownloadingCompleted,
        canDownload = file.local.canBeDownloaded,
    )

    override suspend fun file(fileId: Int): FileState? =
        (td.getFile(fileId) as? TdlResult.Success)?.result?.let(::state)

    override suspend fun download(fileId: Int, priority: Int): FileState? =
        (td.downloadFile(fileId, priority, 0, 0, false) as? TdlResult.Success)?.result?.let(::state)

    override val updates: Flow<FileState> get() = td.fileUpdates.map { state(it.file) }

    override suspend fun cancel(fileId: Int) {
        td.cancelDownloadFile(fileId, true)
    }
}
