package com.tmplayer.music.covers

import java.io.File
import java.security.MessageDigest

/**
 * Covers found online, kept on this device only, so an album is asked about once. A found cover is
 * kept until it is pushed out (oldest written first) by the [maxBytes] cap; "none" is remembered for
 * [NONE_TTL_MS], so a later improvement in the sources can still fill it. One file per key, named by
 * the key's SHA-256 (no title in a file name), written whole through a temporary file. A busy, offline
 * or failed lookup is never kept. Nothing here throws.
 */
class CoverDiskCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
) {
    sealed interface Hit {
        class Image(val file: File) : Hit
        data object None : Hit
    }

    /** Bytes of the image files, counted once on first need and kept up to date after. */
    private var total = -1L

    /** The kept answer for [key], or null when nothing (or nothing current) is kept. */
    @Synchronized
    fun get(key: String): Hit? = runCatching {
        val image = imageOf(key)
        if (image.isFile && image.length() > 0) return Hit.Image(image)
        val none = noneOf(key)
        if (!none.isFile) return null
        val at = if (none.length() in 1..64) none.readText().trim().toLongOrNull() else null
        val age = at?.let { now() - it }
        if (age == null || age > NONE_TTL_MS || age < -DAY_MS) {
            none.delete()
            return null
        }
        Hit.None
    }.getOrNull()

    /** The file an image for [key] is kept in, found or not; no disk access. */
    fun pathOf(key: String): String = imageOf(key).path

    /** Keeps [bytes] as the cover of [key]; null when it could not (no space, a write failure). */
    @Synchronized
    fun putImage(key: String, bytes: ByteArray): File? = runCatching {
        if (bytes.isEmpty() || bytes.size > CoverAnswer.MAX_IMAGE_BYTES) return null
        dir.mkdirs()
        // Never fill the disk for covers: the image and a margin must fit.
        if (dir.usableSpace in 0 until bytes.size + minFreeBytes) return null
        val target = imageOf(key)
        val part = File(dir, target.name + ".part")
        part.writeBytes(bytes)
        val before = if (target.isFile) target.length() else 0L
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
        }
        noneOf(key).delete()
        if (total >= 0) total += bytes.size - before
        trim()
        target.takeIf { it.isFile }
    }.getOrNull()

    /** Remembers that [key] has no cover online, for [NONE_TTL_MS]. */
    @Synchronized
    fun putNone(key: String) {
        runCatching {
            dir.mkdirs()
            if (dir.usableSpace in 0 until minFreeBytes) return
            noneOf(key).writeText(now().toString())
        }
    }

    @Synchronized
    fun clear() {
        runCatching { dir.deleteRecursively() }
        total = -1L
    }

    /** Bytes kept now. */
    @Synchronized
    fun sizeBytes(): Long = runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /** Drops the oldest images until the total is under the cap; old "none" marks go with them. */
    private fun trim() {
        if (total < 0) total = dir.listFiles()?.filter { it.isFile && it.name.endsWith(IMAGE) }?.sumOf { it.length() } ?: 0L
        if (total <= maxBytes) return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val images = files.filter { it.name.endsWith(IMAGE) }.sortedBy { it.lastModified() }.toMutableList()
        var sum = images.sumOf { it.length() }
        while (images.isNotEmpty() && sum > maxBytes) {
            val oldest = images.removeAt(0)
            sum -= oldest.length()
            oldest.delete()
        }
        total = sum
        val cutoff = now() - NONE_TTL_MS
        files.filter { it.name.endsWith(NONE) && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private fun nameOf(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun imageOf(key: String) = File(dir, nameOf(key) + IMAGE)

    private fun noneOf(key: String) = File(dir, nameOf(key) + NONE)

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val NONE_TTL_MS = 7 * DAY_MS
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val IMAGE = ".img"
        private const val NONE = ".none"
    }
}
