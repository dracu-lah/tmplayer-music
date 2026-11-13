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
 * nothing half done: [open] sweeps stray `.part` and `.tmp` files and nothing else. A whole file the
 * index does not list but whose name says what it is (`k<chat>_<message>.<ext>`) is listed again,
 * never deleted. Nothing read from the index is trusted: a bad entry is skipped and the rest kept, a
 * missing or cut short file is dropped from the list, an unreadable index is read from its backup
 * (and set aside, never overwritten), and the kept files are listed again from their names.
 *
 * A file kept from one chat and the same file (one unique id) in another chat are one file on the
 * disk with an entry each ([link]); a file is deleted only when no entry uses it any more.
 *
 * A kept track is written to the index at once. The queue is written a moment after its last
 * change ([scope] null, the tests: at once), so a run of twenty thousand tracks does not rewrite it
 * twenty thousand times; [flush] writes it now. The index is written to a temporary file and
 * renamed over the old one, with a backup beside it.
 */
class OfflineStore(private val scope: CoroutineScope? = null, private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }) {

    private val lock = Any()
    private var dir: File? = null
    private var pendingWrite: Job? = null
    private var lastBackupSec = 0L

    private val _kept = MutableStateFlow<Map<TrackId, KeptTrack>>(emptyMap())

    /** Every kept track, by message. Empty until [open]. */
    val kept: StateFlow<Map<TrackId, KeptTrack>> = _kept.asStateFlow()

    private var queue: List<QueuedTrack> = emptyList()
    private var paused = false

    /** The folder kept files are in, null until [open]. */
    val folder: File? get() = dir

    /** Opens (creating it) the folder, reads the index and sweeps the temporary files. */
    fun open(folder: File) {
        synchronized(lock) {
            folder.mkdirs()
            dir = folder
            val index = File(folder, INDEX)
            var saved = readIndex(index)
            if (saved.damaged) {
                // Keep what could not be read for a look, then fall back to the last good copy.
                runCatching { index.renameTo(File(folder, "$INDEX.damaged-${nowSec()}")) }
                val backup = readIndex(File(folder, BACKUP))
                if (!backup.damaged) saved = backup.copy(damaged = true)
            } else if (!index.isFile) {
                // A crash between the two steps of a replace on a disk that cannot rename over a file.
                val backup = readIndex(File(folder, BACKUP))
                if (!backup.damaged && (backup.kept.isNotEmpty() || backup.queue.isNotEmpty())) saved = backup
            }
            val entries = LinkedHashMap<TrackId, KeptTrack>()
            for (entry in saved.kept) {
                val file = fileOf(entry)
                if (file != null && file.isFile && file.length() > 0) entries[entry.id] = entry
            }
            val rebuilt = rebuild(folder, entries)
            _kept.value = entries
            queue = saved.queue.filter { it.id !in entries }
            paused = saved.paused
            sweep(folder)
            // A repair (dropped or found entries, an unreadable index) is written back at once.
            if (rebuilt > 0 || entries.size != saved.kept.size || saved.damaged) writeNow()
        }
    }

    /**
     * Lists again the whole files in [folder] that no entry uses and whose name is the one a kept
     * file gets, so a lost or damaged index never costs the user a kept file. Returns how many.
     */
    private fun rebuild(folder: File, entries: MutableMap<TrackId, KeptTrack>): Int {
        val files = folder.listFiles() ?: return 0
        val used = entries.values.mapTo(HashSet()) { it.file }
        var found = 0
        for (file in files) {
            if (!file.isFile || file.name in used || file.length() <= 0) continue
            val id = idOfName(file.name) ?: continue
            if (id in entries) continue
            entries[id] = KeptTrack(
                chatId = id.chatId,
                messageId = id.messageId,
                fileName = file.name,
                file = file.name,
                sizeBytes = file.length(),
                keptAtSec = (file.lastModified() / 1000).coerceAtLeast(0),
            )
            found++
        }
        if (found > 0) Logger.i(TAG, "listed $found kept files again from their names")
        return found
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
    fun hasUnique(uniqueId: String): Boolean = keptByUnique(uniqueId) != null

    /** True when anything kept has a real unique id, so a lookup by it can find something. */
    fun hasUniques(): Boolean = _kept.value.values.any { OfflinePlanner.isRealUnique(it.uniqueId) }

    private fun keptByUnique(uniqueId: String): KeptTrack? {
        if (!OfflinePlanner.isRealUnique(uniqueId)) return null
        return _kept.value.values.firstOrNull { it.uniqueId == uniqueId && usable(it) }
    }

    /** The bytes on the disk: a file shared by two chats counts once. */
    fun totalBytes(): Long = _kept.value.values.distinctBy { it.file }.sumOf { it.sizeBytes.coerceAtLeast(0) }

    private fun usable(entry: KeptTrack): Boolean = fileOf(entry)?.let { it.isFile && it.length() > 0 } == true

    /**
     * The kept file of [id], or null when it is not kept or the file is gone, in which case the
     * entry is dropped so the badge and the list stop claiming it. With a [uniqueId], the same file
     * kept from another chat is found too, so a forward plays offline wherever it was kept.
     */
    fun pathOf(id: TrackId, uniqueId: String = ""): File? {
        val entry = _kept.value[id] ?: return keptByUnique(uniqueId)?.let(::fileOf)
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
        return "k${id.chatId}_${id.messageId}.${ext.takeIf { it.isNotEmpty() && it != "part" && it != "tmp" } ?: "bin"}"
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
            // Another chat's entry may use this very file name: it moves to a name of its own first.
            synchronized(lock) { detachShared(name, item.id) }
            if (target.exists()) target.delete()
            part.renameTo(target) && target.length() > 0
        }.getOrDefault(false)
        if (!placed) {
            runCatching { part.delete() }
            // A file another entry uses is not ours to delete.
            if (synchronized(lock) { _kept.value.values.none { it.file == name } }) runCatching { target.delete() }
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
            // At once, not after a delay: a kept file the index does not list is a file at risk.
            writeNow()
        }
        return true
    }

    /** Moves the file [name] to the name of an entry that uses it other than [except], and points them all at it. */
    private fun detachShared(name: String, except: TrackId) {
        val folder = dir ?: return
        val users = _kept.value.values.filter { it.file == name && it.id != except }
        val first = users.firstOrNull() ?: return
        val newName = finalName(first.id, first.fileName)
        if (newName == name) return
        val from = File(folder, name)
        val to = File(folder, newName)
        if (from.isFile && !to.exists() && !from.renameTo(to)) error("could not move $name")
        val moved = users.mapTo(HashSet()) { it.id }
        _kept.value = _kept.value.mapValues { (id, e) -> if (id in moved) e.copy(file = newName) else e }
    }

    /**
     * Keeps [item] as the same file another chat kept already (the same unique id): no copy, no
     * space. Returns false when nothing with that unique id is kept, or [item] is kept already.
     */
    fun link(item: QueuedTrack): Boolean {
        synchronized(lock) {
            if (item.id in _kept.value) return false
            val source = keptByUnique(item.uniqueId) ?: return false
            val entry = KeptTrack(
                chatId = item.chatId,
                messageId = item.messageId,
                title = item.title.ifBlank { source.title },
                artist = item.artist.ifBlank { source.artist },
                fileName = item.fileName.ifBlank { source.fileName },
                file = source.file,
                sizeBytes = source.sizeBytes,
                keptAtSec = nowSec(),
                uniqueId = item.uniqueId,
            )
            _kept.value = _kept.value + (entry.id to entry)
            queue = queue.filter { it.id != entry.id }
            writeNow()
            return true
        }
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
            val goneIds = gone.mapTo(HashSet()) { it.id }
            // A file another chat's entry still uses stays.
            val stillUsed = _kept.value.values.filter { it.id !in goneIds }.mapTo(HashSet()) { it.file }
            for (entry in gone) {
                if (entry.file in stillUsed) continue
                val file = fileOf(entry)
                if (file != null) runCatching { file.delete() }
            }
            _kept.value = _kept.value - goneIds
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

    /** Writes the index to a temporary file, then renames it over the old one (a backup beside it). */
    private fun writeNow() {
        val folder = dir ?: return
        runCatching {
            val body = Json.encodeToString(
                Saved.serializer(),
                Saved(kept = _kept.value.values.toList(), queue = queue, paused = paused),
            )
            val tmp = File(folder, "$INDEX.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            val index = File(folder, INDEX)
            val backup = File(folder, BACKUP)
            // The last good index is copied aside now and then, never on every write.
            val now = nowSec()
            if (index.isFile && (!backup.isFile || now - lastBackupSec >= BACKUP_GAP_SEC)) {
                runCatching { index.copyTo(backup, overwrite = true) }
                lastBackupSec = now
            }
            if (!tmp.renameTo(index)) {
                // A disk that cannot rename over a file: the backup is read on open if this step is cut short.
                if (!backup.isFile && index.isFile) runCatching { index.copyTo(backup, overwrite = true) }
                index.delete()
                if (!tmp.renameTo(index)) error("could not replace the offline index")
            }
        }.onFailure { Logger.w(TAG, "could not write the offline index", it) }
    }

    private fun fileOf(entry: KeptTrack): File? {
        val folder = dir ?: return null
        // A name from the index is a plain file name in the folder, never a path out of it.
        if (entry.file.isBlank() || entry.file.any { it == '/' || it == '\\' } || entry.file.startsWith(".")) return null
        return File(folder, entry.file)
    }

    /** Deletes the temporary and partial files, and only those. */
    private fun sweep(folder: File) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(PART) || file.name.endsWith(TEMP)) {
                Logger.i(TAG, "removing ${file.name}: not a finished file")
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

    private data class Read(val kept: List<KeptTrack>, val queue: List<QueuedTrack>, val paused: Boolean, val damaged: Boolean)

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
        const val BACKUP = "offline.json.bak"
        const val PART = ".part"
        private const val TEMP = ".tmp"
        private const val BACKUP_GAP_SEC = 60L
        private val KEPT_NAME = Regex("k(-?\\d{1,19})_(\\d{1,19})\\.[a-z0-9]{1,5}")

        /** The message a kept file's name says it is, or null for any other file. */
        internal fun idOfName(name: String): TrackId? {
            if (name.endsWith(PART) || name.endsWith(TEMP)) return null
            val m = KEPT_NAME.matchEntire(name) ?: return null
            val chat = m.groupValues[1].toLongOrNull() ?: return null
            val message = m.groupValues[2].toLongOrNull() ?: return null
            return TrackId(chat, message)
        }
        private const val MAX_ENTRIES = 200_000
        private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        private const val WRITE_DELAY_MS = 1_500L
    }
}
