package com.tmplayer.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Chat pictures: a photo already on the disk, one that downloads and arrives, one whose download
 * fails or never comes, a row that leaves mid wait; and the per chat lookup that the Chats tab and
 * Manage chats draw from (no photo, a failed lookup, a photo changed in Telegram, a new session).
 */
class ChatAvatarTest {

    private class FakeFiles : FileCalls {
        val files = HashMap<Int, FileState>()
        val downloads = ArrayList<Int>()
        val cancelled = ArrayList<Int>()
        var refuse = false

        /** What the download answers with: the file as it starts (not whole) unless set. */
        var startsWhole = false
        override val updates = MutableSharedFlow<FileState>(extraBufferCapacity = 16)

        override suspend fun file(fileId: Int): FileState? = files[fileId]

        override suspend fun download(fileId: Int, priority: Int): FileState? {
            downloads += fileId
            if (refuse) return null
            val now = files[fileId] ?: return null
            return if (startsWhole) now.copy(path = "/p/$fileId.jpg", complete = true) else now
        }

        override suspend fun cancel(fileId: Int) {
            cancelled += fileId
        }
    }

    @Test
    fun aPhotoAlreadyOnTheDiskIsNotDownloadedAgain() = runTest {
        val calls = FakeFiles().apply { files[7] = FileState(7, "/p/7.jpg", complete = true) }
        assertEquals("/p/7.jpg", FileFetch.localPath(calls, 7))
        assertTrue(calls.downloads.isEmpty())
    }

    @Test
    fun aPhotoNotDownloadedYetArrivesThroughTheUpdates() = runTest {
        val calls = FakeFiles().apply { files[7] = FileState(7, "", complete = false) }
        val path = async { FileFetch.localPath(calls, 7) }
        runCurrent()
        assertEquals(listOf(7), calls.downloads)
        // Another file's update and a partial one change nothing.
        calls.updates.emit(FileState(8, "/p/8.jpg", complete = true))
        calls.updates.emit(FileState(7, "/p/7.part", complete = false))
        runCurrent()
        assertTrue(path.isActive)
        calls.updates.emit(FileState(7, "/p/7.jpg", complete = true))
        assertEquals("/p/7.jpg", path.await())
        assertTrue(calls.cancelled.isEmpty())
    }

    @Test
    fun aPhotoThatLandsAsTheDownloadIsAskedIsNotMissed() = runTest {
        val calls = FakeFiles().apply {
            files[7] = FileState(7, "", complete = false)
            startsWhole = true
        }
        assertEquals("/p/7.jpg", FileFetch.localPath(calls, 7))
    }

    @Test
    fun aRefusedDownloadLeavesTheInitials() = runTest {
        val calls = FakeFiles().apply {
            files[7] = FileState(7, "", complete = false)
            refuse = true
        }
        assertNull(FileFetch.localPath(calls, 7))
    }

    @Test
    fun aFileThatCannotBeDownloadedIsNotAskedFor() = runTest {
        val calls = FakeFiles().apply { files[7] = FileState(7, "", complete = false, canDownload = false) }
        assertNull(FileFetch.localPath(calls, 7))
        assertTrue(calls.downloads.isEmpty())
    }

    @Test
    fun noPhotoOrAnUnknownFileIsNull() = runTest {
        val calls = FakeFiles()
        assertNull(FileFetch.localPath(calls, 0))
        assertNull(FileFetch.localPath(calls, 99))
        assertTrue(calls.downloads.isEmpty())
    }

    @Test
    fun aDownloadThatNeverComesIsWithdrawnWhenTheTimeRunsOut() = runTest {
        val calls = FakeFiles().apply { files[7] = FileState(7, "", complete = false) }
        val path = async { FileFetch.localPath(calls, 7, timeoutMs = 1_000) }
        advanceTimeBy(1_001)
        assertNull(path.await())
        assertEquals(listOf(7), calls.cancelled)
    }

    @Test
    fun aRowThatLeavesMidWaitWithdrawsItsDownload() = runTest {
        val calls = FakeFiles().apply { files[7] = FileState(7, "", complete = false) }
        val job = launch { FileFetch.localPath(calls, 7) }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf(7), calls.cancelled)
    }

    // The per chat lookup.

    private class Lookups {
        val answers = HashMap<Long, PhotoLookup>()
        val asked = ArrayList<Long>()
        var gate: CompletableDeferred<Unit>? = null
        var generation: Long? = 1L

        fun photos(capacity: Int = ChatPhotos.CAPACITY) = ChatPhotos(
            lookup = { id ->
                asked += id
                gate?.await()
                answers[id] ?: PhotoLookup.Unavailable
            },
            generation = { generation },
            capacity = capacity,
        )
    }

    @Test
    fun aChatWithNoPhotoIsRememberedAndNotAskedAgain() = runTest {
        val l = Lookups().apply { answers[1] = PhotoLookup.Found(null) }
        val photos = l.photos()
        assertNull(photos.photo(1))
        assertNull(photos.photo(1))
        assertEquals(listOf(1L), l.asked)
    }

    @Test
    fun aFoundPhotoIsCachedForTheFirstFrame() = runTest {
        val photo = ChatPhoto(42, byteArrayOf(1, 2))
        val l = Lookups().apply { answers[1] = PhotoLookup.Found(photo) }
        val photos = l.photos()
        assertNull(photos.cached(1))
        assertSame(photo, photos.photo(1))
        assertSame(photo, photos.cached(1))
        assertEquals(listOf(1L), l.asked)
    }

    @Test
    fun aFailedLookupIsAskedAgainNextTime() = runTest {
        val l = Lookups()
        val photos = l.photos()
        assertNull(photos.photo(1))
        l.answers[1] = PhotoLookup.Found(ChatPhoto(5, null))
        assertEquals(5, photos.photo(1)?.fileId)
        assertEquals(listOf(1L, 1L), l.asked)
    }

    @Test
    fun aPhotoChangedInTelegramIsAskedAgainAndTheRowsAreTold() = runTest {
        val l = Lookups().apply { answers[1] = PhotoLookup.Found(ChatPhoto(5, null)) }
        val photos = l.photos()
        assertEquals(5, photos.photo(1)?.fileId)
        val told = async { photos.changes.first() }
        runCurrent()
        l.answers[1] = PhotoLookup.Found(ChatPhoto(6, null))
        photos.changed(1)
        assertEquals(1L, told.await())
        assertNull(photos.cached(1))
        assertEquals(6, photos.photo(1)?.fileId)
    }

    @Test
    fun anAnswerAskedForBeforeAChangeIsNotKept() = runTest {
        val l = Lookups().apply {
            answers[1] = PhotoLookup.Found(ChatPhoto(5, null))
            gate = CompletableDeferred()
        }
        val photos = l.photos()
        val first = async { photos.photo(1) }
        runCurrent()
        photos.changed(1)
        l.gate?.complete(Unit)
        assertEquals(5, first.await()?.fileId)
        assertNull(photos.cached(1))
    }

    @Test
    fun aNewSessionForgetsThePhotosOfTheLastOne() = runTest {
        val l = Lookups().apply { answers[1] = PhotoLookup.Found(ChatPhoto(5, null)) }
        val photos = l.photos()
        photos.photo(1)
        l.generation = 2L
        assertNull(photos.cached(1))
        l.answers[1] = PhotoLookup.Found(ChatPhoto(9, null))
        assertEquals(9, photos.photo(1)?.fileId)
        // Signed out: nothing is asked and nothing shown.
        l.generation = null
        assertNull(photos.photo(1))
        assertEquals(listOf(1L, 1L), l.asked)
    }

    @Test
    fun theCacheIsBounded() = runTest {
        val l = Lookups()
        for (id in 1L..10L) l.answers[id] = PhotoLookup.Found(ChatPhoto(id.toInt(), null))
        val photos = l.photos(capacity = 3)
        for (id in 1L..10L) photos.photo(id)
        assertNull(photos.cached(1))
        assertEquals(10, photos.cached(10)?.fileId)
    }
}
