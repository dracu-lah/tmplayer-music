package com.tmplayer.music.data

/**
 * How much audio the app keeps in TDLib's cache, and whether a file may start downloading
 * Pure, so the disk-full cases are unit tests.
 *
 * The cache is bounded by a ceiling the user picks (2 GB by default, 1 GB on a weak device) that
 * never takes more than a share of the disk and always leaves the device room of its own. Before a
 * track downloads, the free space is checked: enough, go on; not enough but the cache could make
 * room, trim it first; not enough even then, say how much is needed and how much is free.
 */
object CachePolicy {

    /** What the user can choose, in bytes; [UNBOUNDED] is "no limit" (still never past the disk's share). */
    val CHOICES = listOf(512L * MB, 1024L * MB, 2048L * MB, 4096L * MB, UNBOUNDED)

    fun defaultCeiling(weakDevice: Boolean): Long = if (weakDevice) 1024L * MB else 2048L * MB

    /**
     * The ceiling actually used: the user's [chosen] one, but never more than [DISK_SHARE] of the
     * volume or what leaves [RESERVED] free on it, and never under [MIN_CEILING].
     */
    fun ceiling(chosen: Long, totalBytes: Long): Long {
        if (totalBytes <= 0) return chosen.coerceAtLeast(MIN_CEILING)
        val share = (totalBytes * DISK_SHARE).toLong()
        val leaving = totalBytes - RESERVED
        return minOf(chosen, share, leaving).coerceAtLeast(MIN_CEILING)
    }

    sealed interface Decision {
        data object Proceed : Decision

        /** Trim the cache down to [targetBytes] first, then go on. */
        data class TrimFirst(val targetBytes: Long) : Decision

        /** Even an empty cache would not make room: [neededBytes] are needed, [freeBytes] are free. */
        data class NotEnough(val neededBytes: Long, val freeBytes: Long) : Decision
    }

    /**
     * Whether a file of [neededBytes] still to download may start, with [freeBytes] free on the
     * volume and [cacheBytes] in the cache. An unknown size (0 or less) is treated as [UNKNOWN_SIZE],
     * so a file Telegram does not size is not waved through on a full disk.
     */
    fun beforeDownload(neededBytes: Long, freeBytes: Long, cacheBytes: Long): Decision {
        val need = (if (neededBytes > 0) neededBytes else UNKNOWN_SIZE) + HEADROOM
        val free = freeBytes.coerceAtLeast(0)
        if (free >= need) return Decision.Proceed
        val cache = cacheBytes.coerceAtLeast(0)
        val shortfall = need - free
        if (cache >= shortfall) return Decision.TrimFirst((cache - shortfall).coerceAtLeast(0))
        return Decision.NotEnough(need, free)
    }

    const val MB = 1024L * 1024
    const val UNBOUNDED = Long.MAX_VALUE
    const val MIN_CEILING = 256L * MB
    const val RESERVED = 2048L * MB
    const val DISK_SHARE = 0.4
    const val HEADROOM = 100L * MB
    const val UNKNOWN_SIZE = 64L * MB
}
