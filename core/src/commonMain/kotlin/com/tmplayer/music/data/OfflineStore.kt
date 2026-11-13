package com.tmplayer.music.data

import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** One track kept offline: the file in the app's own folder and what the Kept offline list shows. */
@Serializable
data class KeptTrack(
    val chatId: Long,
    val messageId: Long,
    val title: String = "",
    val artist: String = "",
    val fileName: String = "",
    /** The file's name inside the offline folder. */
    val file: String,
    val sizeBytes: Long = 0,
    val keptAtSec: Long = 0,
    /** Telegram's remote unique id: the same file forwarded to another chat is the same kept file. */
    val uniqueId: String = "",
) {
    val id: TrackId get() = TrackId(chatId, messageId)
}

/** A track waiting to be kept, saved so a restart carries on with the queue. */
@Serializable
data class QueuedTrack(
    val chatId: Long,
    val messageId: Long,
    val title: String = "",
    val artist: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0,
    val uniqueId: String = "",
) {
    val id: TrackId get() = TrackId(chatId, messageId)
}

/**
 * What is kept offline, on the disk and in a small index beside it.
 *
 * Kept audio lives in its own folder, outside TDLib's cache: the cache's Free up space and its
 * ceiling trim never see these files, and they play with no TDLib at all. A file is written under
 * a `.part` name and renamed to its final name only when whole, so a crash or a cancel leaves
 * nothing half done: [open] sweeps stray `.part` files and files the index does not know. Nothing
 * read from the index is trusted: a bad entry is skipped and the rest kept, a missing or cut short
 * file is dropped from the list, an unreadable index is an empty one.
 *
 * The index is written at once ([scope] null, the tests) or a moment after the last change, so a
 * run of twenty thousand tracks does not rewrite it twenty thousand times; [flush] writes it now.
 */
class OfflineStore(private val scope: CoroutineScope? = null, private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }) {

    private val lock = Any()
    private var dir: File? = null
    private var pendingWrite: Job? = null

    private val _kept = MutableStateFlow<Map<TrackId, KeptTrack>>(emptyMap())

    /** Every kept track, by message. Empty until [open]. */
    val kept: StateFlow<Map<TrackId, KeptTrack>> = _kept.asStateFlow()

    private var queue: List<QueuedTrack> = emptyList()
    private var paused = false

    /** The folder kept files are in, null until [open]. */
    val folder: File? get() = dir

    /** Opens (creating it) the folder, reads the index and sweeps what it does not account for. */
    fun open(folder: File) {
        synchronized(lock) {
            folder.mkdirs()
            dir = folder
            val saved = readIndex(File(folder, INDEX))
            val entries = LinkedHashMap<TrackId, KeptTrack>()
            for (entry in saved.kept) {
                val file = fileOf(entry)
                if (file != null && file.isFile && file.length() > 0) entries[entry.id] = entry
            }
            _kept.value = entries
            queue = saved.queue.filter { it.id !in entries }
            paused = saved.paused
            sweep(folder, entries.values.map { it.file }.toSet())
            // A repair (dropped entries, an unreadable index) is written back at once.
            if (entries.size != saved.kept.size || saved.damaged) writeNow()
        }
    }

    /** The saved queue and whether the user paused it, for a restart to carry on. */
    fun savedQueue(): Pair<List<QueuedTrack>, Boolean> = synchronized(lock) { queue to paused }

    fun saveQueue(items: List<QueuedTrack>, paused: Boolean) {
        synchronized(lock) {
            if (items == queue && paused == this.paused) return
            queue = items.take(MAX_ENTRIES)
            this.paused = paused
            schedule()
        }
    }

    fun isKept(id: TrackId): Boolean = id in _kept.value

    /** True when a file with this unique id is kept already (a forward of it in another chat). */
    fun hasUnique(uniqueId: String): Boolean = uniqueId.isNotEmpty() && _kept.value.values.any { it.uniqueId == uniqueId }

    fun totalBytes(): Long = _kept.value.values.sumOf { it.sizeBytes.coerceAtLeast(0) }

    /**
     * The kept file of [id], or null when it is not kept or the file is gone, in which case the
     * entry is dropped so the badge and the list stop claiming it.
     */
    fun pathOf(id: TrackId): File? {
        val entry = _kept.value[id] ?: return null
        val file = fileOf(entry)
        if (file != null && file.isFile && file.length() > 0) return file
        synchronized(lock) {
            _kept.value = _kept.value - id
            schedule()
        }
        return null
    }

    /** Where a track's file is written while it arrives. */
    fun partFile(id: TrackId): File? = dir?.let { File(it, "k${id.chatId}_${id.messageId}$PART") }

    /** The name a track's file takes when whole. */
    fun finalName(id: TrackId, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(5)
        return "k${id.chatId}_${id.messageId}.${ext.ifEmpty { "bin" }}"
    }

    /**
     * Makes [part], whole, the kept file of [item]: renamed to its final name, then listed. Returns
     * false (and deletes [part]) when it cannot be put in place.
     */
    fun commit(part: File, item: QueuedTrack, size: Long): Boolean {
        val folder = dir ?: return false
        val name = finalName(item.id, item.fileName)
        val target = File(folder, name)
        val placed = runCatching {
            if (target.exists()) target.delete()
            part.renameTo(target) && target.length() > 0
        }.getOrDefault(false)
        if (!placed) {
            runCatching { part.delete() }
            runCatching { target.delete() }
            return false
        }
        val entry = KeptTrack(
            chatId = item.chatId,
            messageId = item.messageId,
            title = item.title,
            artist = item.artist,
            fileName = item.fileName,
            file = name,
            sizeBytes = target.length().takeIf { it > 0 } ?: size,
            keptAtSec = nowSec(),
            uniqueId = item.uniqueId,
        )
        synchronized(lock) {
            _kept.value = _kept.value + (entry.id to entry)
            queue = queue.filter { it.id != entry.id }
            schedule()
        }
        return true
    }

    /** Removes one kept track: the file, then the entry. Returns whether it was kept. */
    fun remove(id: TrackId): Boolean = removeWhere { it.id == id } > 0

    /** Removes every track kept from [chatId] (the chat was left or removed). Returns how many. */
    fun removeChat(chatId: Long): Int = removeWhere { it.chatId == chatId }

    /** Removes everything kept and the saved queue. Returns how many tracks. */
    fun removeAll(): Int {
        val n = removeWhere { true }
        synchronized(lock) {
            queue = emptyList()
            paused = false
            schedule()
        }
        return n
    }

    private fun removeWhere(match: (KeptTrack) -> Boolean): Int {
        synchronized(lock) {
            val gone = _kept.value.values.filter(match)
            if (gone.isEmpty()) return 0
            val folder = dir
            for (entry in gone) {
                val file = fileOf(entry)
                if (file != null) runCatching { file.delete() }
            }
            _kept.value = _kept.value - gone.map { it.id }.toSet()
            queue = queue.filter { q -> gone.none { it.id == q.id } }
            if (folder != null) schedule()
            return gone.size
        }
    }

    /** Writes the index now. */
    fun flush() {
        synchronized(lock) {
            pendingWrite?.cancel()
            pendingWrite = null
            writeNow()
        }
    }

    private fun schedule() {
        val s = scope
        if (s == null) {
            writeNow()
            return
        }
        if (pendingWrite?.isActive == true) return
        pendingWrite = s.launch {
            delay(WRITE_DELAY_MS)
            synchronized(lock) {
                pendingWrite = null
                writeNow()
            }
        }
    }

    private fun writeNow() {
        val folder = dir ?: return
        runCatching {
            val body = Json.encodeToString(
                Saved.serializer(),
                Saved(kept = _kept.value.values.toList(), queue = queue, paused = paused),
            )
            val tmp = File(folder, "$INDEX.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(File(folder, INDEX))) {
                File(folder, INDEX).delete()
                tmp.renameTo(File(folder, INDEX))
            }
        }.onFailure { Logger.w(TAG, "could not write the offline index", it) }
    }

    private fun fileOf(entry: KeptTrack): File? {
        val folder = dir ?: return null
        // A name from the index is a plain file name in the folder, never a path out of it.
        if (entry.file.isBlank() || entry.file.any { it == '/' || it == '\\' } || entry.file.startsWith(".")) return null
        return File(folder, entry.file)
    }

    private fun sweep(folder: File, known: Set<String>) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.name == INDEX) continue
            if (file.name !in known) {
                Logger.i(TAG, "removing ${file.name}: not a kept file")
                runCatching { file.delete() }
            }
        }
    }

    @Serializable
    private data class Saved(
        val version: Int = 1,
        val kept: List<KeptTrack> = emptyList(),
        val queue: List<QueuedTrack> = emptyList(),
        val paused: Boolean = false,
    )

    private class Read(val kept: List<KeptTrack>, val queue: List<QueuedTrack>, val paused: Boolean, val damaged: Boolean)

    private fun readIndex(file: File): Read {
        if (!file.isFile) return Read(emptyList(), emptyList(), false, false)
        val root = runCatching {
            if (file.length() > MAX_INDEX_BYTES) return Read(emptyList(), emptyList(), false, true)
            Json.parseToJsonElement(file.readText())
        }.getOrNull() as? JsonObject ?: return Read(emptyList(), emptyList(), false, true)
        return Read(
            kept = entries(root["kept"], KeptTrack.serializer()).filter { it.file.isNotBlank() },
            queue = entries(root["queue"], QueuedTrack.serializer()),
            paused = (root["paused"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            damaged = false,
        )
    }

    private fun <T> entries(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val array = element as? JsonArray ?: return emptyList()
        val out = ArrayList<T>()
        for (item in array.take(MAX_ENTRIES)) {
            if (item !is JsonObject) continue
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(serializer, item.jsonObject) }.getOrNull()?.let(out::add)
        }
        return out
    }

    companion object {
        private const val TAG = "OfflineStore"
        const val INDEX = "offline.json"
        const val PART = ".part"
        private const val MAX_ENTRIES = 200_000
        private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        private const val WRITE_DELAY_MS = 1_500L
    }
}
