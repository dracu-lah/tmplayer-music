package com.tmplayer.music.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The gateway's answers kept on this device only, so a song is asked about once: found lyrics for
 * [FOUND_TTL_MS], "none" for [NONE_TTL_MS]. One small file per song key (its SHA-256, so no title is in a
 * file name), written whole through a temporary file. Bounded by [maxBytes] and [maxEntries]: the oldest
 * go first. A busy, offline or failed lookup is never kept.
 *
 * Each entry stores the length it was asked with, and a later query matches only within
 * [DURATION_SLACK_SEC]: the versions of one song (one key, lengths within a second or two) share the
 * entry, while a much longer or shorter file under the same key asks again.
 */
class LyricsDiskCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    enum class Kind { Found, Instrumental, None }

    data class Entry(val kind: Kind, val durationSec: Long, val savedAt: Long, val synced: String?, val plain: String?)

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun get(key: String, durationSec: Long): Entry? = runCatching {
        val file = fileOf(key)
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return null
        val entry = decode(file.readText()) ?: run {
            file.delete()
            return null
        }
        val age = now() - entry.savedAt
        val ttl = if (entry.kind == Kind.None) NONE_TTL_MS else FOUND_TTL_MS
        if (age > ttl || age < -DAY_MS) {
            file.delete()
            return null
        }
        if (kotlin.math.abs(entry.durationSec - durationSec) > DURATION_SLACK_SEC) return null
        entry
    }.getOrNull()

    /** Keeps [entry]; false when it could not (no space, a write failure). Never throws. */
    @Synchronized
    fun put(key: String, entry: Entry): Boolean = runCatching {
        val text = encode(entry)
        if (text.length > MAX_ENTRY_BYTES) return false
        dir.mkdirs()
        // Never fill the disk for lyrics: a small margin over the entry itself.
        if (dir.usableSpace in 0 until text.length + MIN_FREE_BYTES) return false
        val target = fileOf(key)
        val part = File(dir, target.name + ".part")
        part.writeText(text)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return false
            }
        }
        trim()
        true
    }.getOrDefault(false)

    @Synchronized
    fun clear() {
        runCatching { dir.deleteRecursively() }
    }

    /** Bytes kept now. */
    @Synchronized
    fun sizeBytes(): Long = runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // A part file left by a process killed mid-write.
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        val kept = files.filter { it.name.endsWith(".json") }.sortedBy { it.lastModified() }.toMutableList()
        var total = kept.sumOf { it.length() }
        while (kept.isNotEmpty() && (total > maxBytes || kept.size > maxEntries)) {
            val oldest = kept.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun fileOf(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) }.take(40) + ".json")
    }

    private fun encode(entry: Entry): String = buildJsonObject {
        put("v", 1)
        put("kind", entry.kind.name)
        put("dur", entry.durationSec)
        put("at", entry.savedAt)
        entry.synced?.let { put("synced", it) }
        entry.plain?.let { put("plain", it) }
    }.toString()

    private fun decode(text: String): Entry? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val kind = Kind.entries.firstOrNull { it.name == root.str("kind") } ?: return null
        val duration = root.long("dur") ?: return null
        val at = root.long("at") ?: return null
        return Entry(kind, duration, at, root.str("synced"), root.str("plain"))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val FOUND_TTL_MS = 90 * DAY_MS
        const val NONE_TTL_MS = 7 * DAY_MS
        const val DURATION_SLACK_SEC = 3L
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_ENTRIES = 5_000
        const val MAX_ENTRY_BYTES = 300 * 1024
        private const val MIN_FREE_BYTES = 1024 * 1024
    }
}
