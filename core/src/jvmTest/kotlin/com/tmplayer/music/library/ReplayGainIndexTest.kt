package com.tmplayer.music.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** ReplayGain on the track: stored with the tags, kept when a later read has none, carried over by the version 5 step. */
class ReplayGainIndexTest {

    private val dir: File = Files.createTempDirectory("replaygain-index").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:") = LibraryIndex.open(path).also { opened += it }

    private fun track(messageId: Long) = Track(
        chatId = 1, messageId = messageId, fileId = messageId.toInt(), uniqueId = "u$messageId", fileName = "t$messageId.mp3",
        mime = "", sizeBytes = 1_000, dateSec = 1_700_000_000 + messageId, title = "Velo Marin $messageId", artist = "Korvane",
        durationMs = 200_000, format = AudioFormat.MP3, groupKey = SyntheticAccount.groupKeyOf("Korvane", "Velo Marin $messageId"),
    )

    @Test
    fun theGainIsStoredWithTheTagsAndReadBack() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1), track(2)))
        assertEquals(ReplayGain.NONE, index.track(TrackId(1, 1))?.replayGain, "nothing read yet")
        val gain = ReplayGain(-6.5f, -5f, 0.98f, 1f)
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000, replayGain = gain)), TagResult(TrackId(1, 2), Tags(durationMs = 200_000))))
        assertEquals(gain, index.track(TrackId(1, 1))?.replayGain)
        assertEquals(ReplayGain.CHECKED, index.track(TrackId(1, 2))?.replayGain, "read, none found: marked so it is not read again")
    }

    @Test
    fun aLaterReadWithoutGainKeepsWhatTheTrackHad() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1)))
        val gain = ReplayGain(-3f, null, 0.9f, null)
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000, replayGain = gain))))
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000))))
        assertEquals(gain, index.track(TrackId(1, 1))?.replayGain)
    }

    @Test
    fun aVersionFourFileGetsTheColumnAndKeepsItsTracks() = runBlocking {
        val path = File(dir, "v4.db").path
        open(path).also { index ->
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(listOf(track(1)))
            index.close()
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("ALTER TABLE tracks DROP COLUMN replay_gain")
            db.execSQL("PRAGMA user_version = 4")
        }
        val index = open(path)
        assertEquals("Velo Marin 1", index.track(TrackId(1, 1))?.title)
        assertEquals(ReplayGain.NONE, index.track(TrackId(1, 1))?.replayGain)
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000, replayGain = ReplayGain(-2f, null, null, null)))))
        assertEquals(-2f, index.track(TrackId(1, 1))?.replayGain?.trackGainDb)
        val version = BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0 }
        }
        assertEquals(IndexSchema.VERSION.toLong(), version)
    }

    @Test
    fun aBackfilledGainIsStoredOnlyForAReadTrackWithNoneYet() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1), track(2), track(3)))
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000, replayGain = ReplayGain(-2f, null, null, null)))))
        index.applyTags(listOf(TagResult(TrackId(1, 3), Tags(durationMs = 200_000))))
        // 2 has not been read: the normal read takes its gain, so a backfill leaves it alone.
        index.applyGain(TrackId(1, 2), ReplayGain(-9f, null, null, null))
        assertEquals(ReplayGain.NONE, index.track(TrackId(1, 2))?.replayGain)
        // 1 has a gain already: kept.
        index.applyGain(TrackId(1, 1), ReplayGain(-9f, null, null, null))
        assertEquals(-2f, index.track(TrackId(1, 1))?.replayGain?.trackGainDb)
        // 3 was read, found none, and is marked: not overwritten either.
        index.applyGain(TrackId(1, 3), ReplayGain(-9f, null, null, null))
        assertEquals(ReplayGain.CHECKED, index.track(TrackId(1, 3))?.replayGain)
    }

    @Test
    fun aTrackReadBeforeTheColumnGetsItsGainFromTheBackfill() = runBlocking {
        val path = File(dir, "v4b.db").path
        open(path).also { index ->
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(listOf(track(1), track(2)))
            index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 200_000)), TagResult(TrackId(1, 2), Tags(durationMs = 200_000))))
            index.close()
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("UPDATE tracks SET replay_gain = ''")
        }
        val index = open(path)
        assertEquals(ReplayGain.NONE, index.track(TrackId(1, 1))?.replayGain)
        index.applyGain(TrackId(1, 1), ReplayGain(-4f, null, 0.9f, null))
        index.applyGain(TrackId(1, 2), ReplayGain.NONE)
        assertEquals(-4f, index.track(TrackId(1, 1))?.replayGain?.trackGainDb)
        assertEquals(ReplayGain.CHECKED, index.track(TrackId(1, 2))?.replayGain)
    }
}
