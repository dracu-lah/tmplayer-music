package com.tmplayer.music.data

import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.FileType
import dev.g000sha256.tdl.dto.FileTypeAudio
import dev.g000sha256.tdl.dto.FileTypeDocument
import dev.g000sha256.tdl.dto.FileTypeThumbnail
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * TDLib's cache of audio, as [CachePolicy] rules it: how much it holds, trimming it to the ceiling,
 * making room before a download, and Free up space. The app sets [cacheDir] (TDLib's files folder)
 * and [chosenCeiling] (the user's choice) at start and when the choice changes.
 */
object Storage {
    private const val TAG = "Storage"

    @Volatile
    var cacheDir: File? = null

    @Volatile
    var chosenCeiling: Long = CachePolicy.defaultCeiling(weakDevice = false)

    /** Free and total bytes on the cache's volume, 0 when unreadable. */
    fun freeBytes(): Long = runCatching { cacheDir?.usableSpace ?: 0L }.getOrDefault(0L)

    fun totalBytes(): Long = runCatching { cacheDir?.totalSpace ?: 0L }.getOrDefault(0L)

    /** The ceiling in force on this volume. */
    fun ceiling(): Long = CachePolicy.ceiling(chosenCeiling, totalBytes())

    /** What TDLib's cache holds now, from its own quick count; 0 when it will not say. */
    suspend fun usedBytes(): Long {
        val td = withTimeoutOrNull(10_000) { Td.awaitAuthorizedSession().client } ?: return 0
        return td.getStorageStatisticsFast().valueOrNull?.filesSize ?: 0
    }

    /** Trims the cache to the ceiling, the least recently used files first. Never throws. */
    suspend fun trim(maxBytes: Long = ceiling()) {
        optimize(maxBytes.coerceAtLeast(0), IMMUNITY_SECONDS)
    }

    /**
     * Before a file of [neededBytes] downloads: makes room by trimming the cache if that is enough,
     * and returns null to go on; otherwise the sentence to show, saying what is needed and free.
     */
    suspend fun makeRoom(neededBytes: Long): String? {
        val dir = cacheDir ?: return null
        return when (val decision = CachePolicy.beforeDownload(neededBytes, freeBytes(), usedBytes())) {
            CachePolicy.Decision.Proceed -> null
            is CachePolicy.Decision.TrimFirst -> {
                Logger.i(TAG, "low space in ${dir.name}: trimming the cache to ${decision.targetBytes}")
                optimize(decision.targetBytes, 0)
                null
            }
            is CachePolicy.Decision.NotEnough -> com.tmplayer.music.i18n.L.storageNotEnough(
                sizeText(decision.neededBytes),
                sizeText(decision.freeBytes),
            )
        }
    }

    /** Deletes every cached audio file and cover thumbnail; returns the bytes freed, or null on failure. */
    suspend fun freeUp(): Long? = optimize(0, 0)

    private suspend fun optimize(size: Long, immunity: Int): Long? {
        val td = withTimeoutOrNull(10_000) { Td.awaitAuthorizedSession().client } ?: return null
        val result = withTimeoutOrNull(60_000) {
            td.optimizeStorage(
                size,
                Int.MAX_VALUE,
                Int.MAX_VALUE,
                immunity,
                AUDIO_TYPES,
                longArrayOf(),
                longArrayOf(),
                true,
                0,
            )
        }
        return when (result) {
            is TdlResult.Success -> result.result.size
            is TdlResult.Failure -> {
                Logger.w(TAG, "optimizeStorage failed: ${result.message}")
                null
            }
            null -> null
        }
    }

    /** Sizes in the catalog's own words, for the not-enough sentence. */
    private fun sizeText(bytes: Long): String {
        val mb = bytes / CachePolicy.MB
        return if (mb >= 1024) {
            com.tmplayer.music.i18n.L.storageGigabytes(Math.round(mb / 102.4) / 10.0)
        } else {
            com.tmplayer.music.i18n.L.storageMegabytes(mb.coerceAtLeast(1))
        }
    }

    private val AUDIO_TYPES: Array<FileType> = arrayOf(FileTypeAudio(), FileTypeDocument(), FileTypeThumbnail())

    /** A file used in the last minute is left alone by a routine trim (it may be playing). */
    private const val IMMUNITY_SECONDS = 60
}
