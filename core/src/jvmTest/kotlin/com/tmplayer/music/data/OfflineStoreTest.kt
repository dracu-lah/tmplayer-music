package com.tmplayer.music.data

import com.tmplayer.music.library.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What is kept offline on the disk: whole files only, an index that survives bad input, and purges. */
class OfflineStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dir(): File = File(folder.root, "offline")

    private fun opened(): OfflineStore = OfflineStore(nowSec = { 1_700_000_000 }).also { it.open(dir()) }

    private fun queued(chat: Long, message: Long, name: String = "song.mp3", unique: String = "u$message") =
        QueuedTrack(chat, message, "Song $message", "Artist", name, 10, unique)

    private fun keep(store: OfflineStore, item: QueuedTrack, bytes: Int = 10): Boolean {
        val part = store.partFile(item.id)!!
        part.writeBytes(ByteArray(bytes) { 1 })
        return store.commit(part, item, bytes.toLong())
    }

    @Test
    fun `a file is kept whole under its final name and no part is left`() {
        val store = opened()
        assertTrue(keep(store, queued(1, 5)))
        val path = store.pathOf(TrackId(1, 5))
        assertNotNull(path)
        assertEquals("k1_5.mp3", path!!.name)
        assertEquals(setOf("k1_5.mp3", OfflineStore.INDEX), dir().list()!!.toSet())
        assertEquals(10L, store.totalBytes())
    }

    @Test
    fun `the index survives a restart and only partial and temporary files are swept`() {
        val first = opened()
        keep(first, queued(1, 5))
        keep(first, queued(2, 6, "b.flac"))
        File(dir(), "k9_9.part").writeBytes(ByteArray(40))
        File(dir(), "offline.json.tmp").writeBytes(ByteArray(40))
        File(dir(), "notes.txt").writeBytes(ByteArray(40))
        val again = opened()
        assertEquals(setOf(TrackId(1, 5), TrackId(2, 6)), again.kept.value.keys)
        assertFalse(File(dir(), "k9_9.part").exists())
        assertFalse(File(dir(), "offline.json.tmp").exists())
        assertTrue(File(dir(), "notes.txt").exists())
    }

    @Test
    fun `a kept file the index does not list is listed again, never deleted`() {
        val first = opened()
        keep(first, queued(1, 5))
        keep(first, queued(-1001, 6, "b.flac"))
        File(dir(), OfflineStore.INDEX).delete()
        File(dir(), OfflineStore.BACKUP).delete()
        val again = opened()
        assertEquals(setOf(TrackId(1, 5), TrackId(-1001, 6)), again.kept.value.keys)
        assertTrue(File(dir(), "k1_5.mp3").exists())
        assertTrue(File(dir(), "k-1001_6.flac").exists())
        assertEquals(10L, again.kept.value[TrackId(1, 5)]!!.sizeBytes)
    }

    @Test
    fun `the index is on the disk the moment a track is kept`() {
        val store = OfflineStore(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()), nowSec = { 1_700_000_000 })
        store.open(dir())
        keep(store, queued(1, 5))
        // No flush and no waiting: a crash now still finds the track in the index.
        assertTrue(File(dir(), OfflineStore.INDEX).readText().contains("k1_5.mp3"))
        assertFalse(File(dir(), OfflineStore.INDEX + ".tmp").exists())
    }

    @Test
    fun `a damaged index is set aside, the backup is used and nothing is swept`() {
        val store = opened()
        keep(store, queued(1, 1))
        keep(store, queued(1, 2))
        File(dir(), OfflineStore.INDEX).copyTo(File(dir(), OfflineStore.BACKUP), overwrite = true)
        File(dir(), OfflineStore.INDEX).writeText("{\"kept\": [{\"chatId\": 1")
        File(dir(), "k4_4.mp3").writeBytes(ByteArray(7))
        val again = opened()
        assertEquals(setOf(TrackId(1, 1), TrackId(1, 2), TrackId(4, 4)), again.kept.value.keys)
        assertEquals("Song 1", again.kept.value[TrackId(1, 1)]!!.title)
        assertTrue(File(dir(), "k1_1.mp3").exists() && File(dir(), "k1_2.mp3").exists() && File(dir(), "k4_4.mp3").exists())
        assertTrue(dir().list()!!.any { it.startsWith("offline.json.damaged-") })
    }

    @Test
    fun `the same file kept from another chat is one file with an entry each`() {
        val store = opened()
        keep(store, queued(1, 5, unique = "same"))
        assertTrue(store.link(queued(2, 9, unique = "same")))
        assertFalse(store.link(queued(3, 1, unique = "other")))
        assertEquals(store.pathOf(TrackId(1, 5)), store.pathOf(TrackId(2, 9)))
        assertEquals(10L, store.totalBytes())
        // Not kept in chat 4, but its unique id finds the file.
        assertEquals("k1_5.mp3", store.pathOf(TrackId(4, 1), "same")!!.name)
        assertNull(store.pathOf(TrackId(4, 1), "nothing"))
        // Removing the chat that holds the file name keeps the file for the other chat.
        assertEquals(1, store.removeChat(1))
        assertTrue(File(dir(), "k1_5.mp3").exists())
        assertNotNull(store.pathOf(TrackId(2, 9)))
        assertEquals(1, store.remove(TrackId(2, 9)).let { if (it) 1 else 0 })
        assertFalse(File(dir(), "k1_5.mp3").exists())
    }

    @Test
    fun `keeping a track again does not delete the file another chat shares`() {
        val store = opened()
        keep(store, queued(1, 5, unique = "same"))
        store.link(queued(2, 9, unique = "same"))
        store.remove(TrackId(1, 5))
        // The file is now only chat 2's. Chat 1 keeps its message again under the same name.
        assertTrue(keep(store, queued(1, 5, unique = "same"), bytes = 12))
        assertEquals(10L, store.pathOf(TrackId(2, 9))!!.length())
        assertEquals(12L, store.pathOf(TrackId(1, 5))!!.length())
    }

    @Test
    fun `a file that is gone is dropped from the list when it is asked for`() {
        val store = opened()
        keep(store, queued(1, 5))
        File(dir(), "k1_5.mp3").delete()
        assertNull(store.pathOf(TrackId(1, 5)))
        assertFalse(store.isKept(TrackId(1, 5)))
    }

    @Test
    fun `an entry whose file went missing while the app was away is not listed after a restart`() {
        val store = opened()
        keep(store, queued(1, 5))
        keep(store, queued(1, 6))
        File(dir(), "k1_6.mp3").delete()
        assertEquals(setOf(TrackId(1, 5)), opened().kept.value.keys)
    }

    @Test
    fun `leaving a chat removes only its files`() {
        val store = opened()
        keep(store, queued(1, 1))
        keep(store, queued(1, 2))
        keep(store, queued(2, 3))
        assertEquals(2, store.removeChat(1))
        assertEquals(setOf(TrackId(2, 3)), store.kept.value.keys)
        assertEquals(setOf("k2_3.mp3", OfflineStore.INDEX), dir().list()!!.filter { it != OfflineStore.BACKUP }.toSet())
    }

    @Test
    fun `remove all deletes every file and the saved queue`() {
        val store = opened()
        keep(store, queued(1, 1))
        store.saveQueue(listOf(queued(1, 9)), paused = true)
        assertEquals(1, store.removeAll())
        assertTrue(store.kept.value.isEmpty())
        val again = opened()
        assertTrue(again.savedQueue().first.isEmpty())
        assertEquals(listOf(OfflineStore.INDEX), dir().list()!!.filter { it != OfflineStore.BACKUP }.toList())
    }

    @Test
    fun `the queue and the pause are saved, without what is kept already`() {
        val store = opened()
        keep(store, queued(1, 1))
        store.saveQueue(listOf(queued(1, 1), queued(1, 2), queued(1, 3)), paused = true)
        val (queue, paused) = opened().savedQueue()
        assertTrue(paused)
        assertEquals(listOf(2L, 3L), queue.map { it.messageId })
    }

    @Test
    fun `an unreadable index with no backup is rebuilt from the file names and deletes nothing`() {
        val store = opened()
        keep(store, queued(1, 1))
        File(dir(), OfflineStore.BACKUP).delete()
        File(dir(), OfflineStore.INDEX).writeText("{\"kept\": [{\"chatId\": 1")
        val again = opened()
        assertEquals(setOf(TrackId(1, 1)), again.kept.value.keys)
        assertTrue(File(dir(), "k1_1.mp3").exists())
    }

    @Test
    fun `a bad entry is skipped and the rest are kept`() {
        val store = opened()
        keep(store, queued(1, 1))
        keep(store, queued(1, 2))
        val index = File(dir(), OfflineStore.INDEX)
        val text = index.readText().replace("\"messageId\":2", "\"messageId\":\"two\"")
        index.writeText(text)
        val again = opened()
        assertEquals(setOf(TrackId(1, 1), TrackId(1, 2)), again.kept.value.keys)
        assertEquals("", again.kept.value[TrackId(1, 2)]!!.title)
        assertEquals("Song 1", again.kept.value[TrackId(1, 1)]!!.title)
    }

    @Test
    fun `an entry cannot name a file outside the folder`() {
        val store = opened()
        File(folder.root, "secret.txt").writeText("x")
        File(dir(), OfflineStore.BACKUP).delete()
        File(dir(), OfflineStore.INDEX).writeText(
            """{"kept":[{"chatId":1,"messageId":1,"file":"../secret.txt","sizeBytes":1},{"chatId":1,"messageId":2,"file":"/etc/passwd"}]}""",
        )
        val again = opened()
        assertTrue(again.kept.value.isEmpty())
        assertTrue(File(folder.root, "secret.txt").exists())
    }

    @Test
    fun `an odd file name still gets a plain extension`() {
        val store = opened()
        assertEquals("k1_2.bin", store.finalName(TrackId(1, 2), "no extension"))
        assertEquals("k1_2.x", store.finalName(TrackId(1, 2), "weird.../x"))
        assertEquals("k1_2.flac", store.finalName(TrackId(1, 2), "A B.FLAC"))
        assertEquals("k1_2.bin", store.finalName(TrackId(1, 2), "a.éé"))
    }

    @Test
    fun `an empty part is not kept`() {
        val store = opened()
        val item = queued(1, 1)
        val part = store.partFile(item.id)!!
        part.writeBytes(ByteArray(0))
        assertFalse(store.commit(part, item, 0))
        assertFalse(part.exists())
        assertTrue(store.kept.value.isEmpty())
    }
}
