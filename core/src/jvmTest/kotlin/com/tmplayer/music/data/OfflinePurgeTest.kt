package com.tmplayer.music.data

import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules the rest of the app leans on: kept files are outside TDLib's cache (so Free up space
 * cannot reach them), leaving a chat or logging out purges its kept files and rows, and the
 * streaming code asks [OfflineDownloads.isDownloading] and gets a plain no when nothing is fetched.
 */
class OfflinePurgeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store get() = OfflineDownloads.store

    private fun keep(chat: Long, message: Long) {
        val item = QueuedTrack(chat, message, "T$message", "A", "t$message.flac", 8, "u$chat$message")
        val part = store.partFile(item.id)!!
        part.writeBytes(ByteArray(8))
        assertTrue(store.commit(part, item, 8))
    }

    @Before
    fun open() {
        store.open(File(folder.root, "offline"))
    }

    @After
    fun clean() {
        store.removeAll()
    }

    @Test
    fun `kept files are in their own folder, away from TDLib's cache`() {
        val cache = File(folder.root, "cache").also { it.mkdirs() }
        File(cache, "stream").writeBytes(ByteArray(4))
        keep(1, 1)
        assertEquals(File(folder.root, "offline"), store.folder)
        assertTrue(store.pathOf(TrackId(1, 1))!!.absolutePath.startsWith(File(folder.root, "offline").absolutePath))
        assertFalse(store.pathOf(TrackId(1, 1))!!.absolutePath.startsWith(cache.absolutePath))
    }

    @Test
    fun `leaving a chat purges its kept files and rows and no one else's`() = runBlocking {
        keep(1, 1)
        keep(1, 2)
        keep(2, 3)
        OfflineDownloads.purgeChat(1)
        assertEquals(setOf(TrackId(2, 3)), OfflineDownloads.kept.value.keys)
        assertNull(OfflineDownloads.pathOf(TrackId(1, 1)))
        assertFalse(File(folder.root, "offline/k1_1.flac").exists())
        assertTrue(File(folder.root, "offline/k2_3.flac").exists())
    }

    @Test
    fun `logging out purges everything`() = runBlocking {
        keep(1, 1)
        keep(2, 3)
        OfflineDownloads.purgeAll()
        assertTrue(OfflineDownloads.kept.value.isEmpty())
        assertEquals(listOf<String>(), File(folder.root, "offline").list()!!.filter { it.startsWith("k") })
    }

    @Test
    fun `remove all reports how many were kept`() = runBlocking {
        keep(1, 1)
        keep(1, 2)
        assertEquals(2, OfflineDownloads.removeAll())
        assertEquals(0, OfflineDownloads.removeAll())
    }

    @Test
    fun `nothing fetching means streaming is left alone and no chat is protected`() {
        assertFalse(OfflineDownloads.isDownloading(7))
        assertEquals(0, OfflineDownloads.protectedChats().size)
    }
}
