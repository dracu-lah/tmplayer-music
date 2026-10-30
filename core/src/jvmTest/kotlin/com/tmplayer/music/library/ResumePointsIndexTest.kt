package com.tmplayer.music.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where each file was left, in the index: kept, cleared, hidden, bounded, purged with its chat, migrated. */
class ResumePointsIndexTest {

    private val dir: File = Files.createTempDirectory("resume-index").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:") = LibraryIndex.open(path).also { opened += it }

    private fun track(chatId: Long, messageId: Long, title: String, durationMs: Long = 200_000) = Track(
        chatId = chatId, messageId = messageId, fileId = messageId.toInt(), uniqueId = "u$chatId-$messageId", fileName = "$title.mp3",
        mime = "", sizeBytes = 1_000, dateSec = 1_700_000_000 + messageId, title = title, artist = "Korvane",
        durationMs = durationMs, format = AudioFormat.MP3, groupKey = SyntheticAccount.groupKeyOf("Korvane", title),
    )

    private fun library(): LibraryIndex = open().also { index ->
        runBlocking {
            index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
            index.upsert(listOf(track(1, 1, "Velo Marin"), track(1, 2, "Sand Hours"), track(2, 1, "Low Tide")))
        }
    }

    @Test
    fun aPositionIsKeptAndReadBackWithItsSong() = runBlocking {
        val index = library()
        val song = index.songOf(TrackId(1, 1))!!.groupKey
        index.saveResume(TrackId(1, 1), song, 60_000, 200_000, atMs = 10)
        val (items, hidden) = index.resumePoints()
        assertTrue(hidden.isEmpty())
        val one = items.single()
        assertEquals(song, one.key)
        assertEquals(60_000, one.positionMs)
        assertEquals("Velo Marin", one.title)
    }

    @Test
    fun aSpotNearTheEndOrTheStartClearsIt() = runBlocking {
        val index = library()
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 10)
        index.saveResume(TrackId(1, 1), "", 195_000, 200_000, atMs = 20)
        assertTrue(index.resumePoints().first.isEmpty())
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 30)
        index.saveResume(TrackId(1, 1), "", 1_000, 200_000, atMs = 40)
        assertTrue(index.resumePoints().first.isEmpty())
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 50)
        index.clearResume(TrackId(1, 1))
        assertTrue(index.resumePoints().first.isEmpty())
    }

    @Test
    fun aFileTheIndexDoesNotHoldIsNotKept() = runBlocking {
        val index = library()
        index.saveResume(TrackId(9, 9), "x", 60_000, 200_000, atMs = 10)
        assertTrue(index.resumePoints().first.isEmpty())
    }

    @Test
    fun hidingKeepsAHiddenRowThatPlayingAgainReplaces() = runBlocking {
        val index = library()
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 10)
        index.hideFromDial(TrackId(1, 1), "", atMs = 20)
        val (items, hidden) = index.resumePoints()
        assertTrue(items.isEmpty())
        assertEquals(20, hidden.single().atMs)
        // A finished track does not undo a hide.
        index.clearResume(TrackId(1, 1))
        assertEquals(1, index.resumePoints().second.size)
        index.saveResume(TrackId(1, 1), "", 70_000, 200_000, atMs = 30)
        assertEquals(1, index.resumePoints().first.size)
        assertTrue(index.resumePoints().second.isEmpty())
    }

    @Test
    fun leavingAChatOrLosingAFilePurgesItsRows() = runBlocking {
        val index = library()
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 10)
        index.saveResume(TrackId(1, 2), "", 60_000, 200_000, atMs = 11)
        index.saveResume(TrackId(2, 1), "", 60_000, 200_000, atMs = 12)
        index.removeTracks(listOf(TrackId(1, 2)))
        assertEquals(setOf(TrackId(1, 1), TrackId(2, 1)), index.resumePoints().first.map { it.track }.toSet())
        index.setPicked(listOf(ChatPick(2, "Two")))
        assertEquals(listOf(TrackId(2, 1)), index.resumePoints().first.map { it.track })
        // Picking the chat again does not bring the old rows back.
        index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
        index.upsert(listOf(track(1, 1, "Velo Marin")))
        assertEquals(listOf(TrackId(2, 1)), index.resumePoints().first.map { it.track })
        index.removeChat(2)
        assertTrue(index.resumePoints().first.isEmpty())
    }

    @Test
    fun onlyTheNewestAreKept() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        val n = SpeedDial.MAX_KEPT + 20L
        index.upsert((1L..n).map { track(1, it, "Song $it") })
        for (i in 1L..n) index.saveResume(TrackId(1, i), "", 60_000, 200_000, atMs = i)
        val kept = index.resumePoints(limit = 10_000).first
        assertEquals(SpeedDial.MAX_KEPT, kept.size)
        assertEquals(n, kept.first().track.messageId)
        assertTrue(kept.none { it.track.messageId <= 20 })
    }

    @Test
    fun recentPlaysGiveTheNewestPlayOfEachSong() = runBlocking {
        val index = library()
        index.recordPlay(TrackId(1, 1), "", 100)
        index.recordPlay(TrackId(1, 2), "", 200)
        index.recordPlay(TrackId(1, 1), "", 300)
        val plays = index.recentPlays(10)
        assertEquals(listOf("Velo Marin", "Sand Hours"), plays.map { it.title })
        assertEquals(300_000L, plays.first().atMs)
        assertEquals(0, plays.first().positionMs)
    }

    @Test
    fun aVersionThreeFileGetsTheTableAndKeepsItsPlays() = runBlocking {
        val path = File(dir, "v3.db").path
        open(path).also { index ->
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(listOf(track(1, 1, "Velo Marin")))
            index.recordPlay(TrackId(1, 1), "", 100)
            index.close()
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP TABLE resume_points")
            db.execSQL("PRAGMA user_version = 3")
        }
        val index = open(path)
        assertEquals(1, index.recentPlays(10).size)
        index.saveResume(TrackId(1, 1), "", 60_000, 200_000, atMs = 10)
        assertEquals(1, index.resumePoints().first.size)
        val version = BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0 }
        }
        assertEquals(IndexSchema.VERSION.toLong(), version)
    }
}
