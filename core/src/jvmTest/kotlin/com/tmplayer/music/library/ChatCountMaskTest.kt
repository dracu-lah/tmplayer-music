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

/** A chat's track count is what its page lists: short clips and voice files left out by the Settings switches. */
class ChatCountMaskTest {

    private val dir: File = Files.createTempDirectory("chat-count-mask").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:") = LibraryIndex.open(path).also { opened += it }

    private fun track(chatId: Long, messageId: Long, name: String, durationMs: Long, format: AudioFormat = AudioFormat.MP3) = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = "u$chatId-$messageId",
        fileName = name, mime = "", sizeBytes = 1_000, dateSec = 1_700_000_000 + messageId, title = "Velo Marin $messageId",
        artist = "Korvane", durationMs = durationMs, format = format,
        groupKey = SyntheticAccount.groupKeyOf("Korvane", "Velo Marin $messageId"),
    )

    /** One normal file, one short clip and one voice file, in chat 1. */
    private fun mixed(chatId: Long = 1) = listOf(
        track(chatId, 1, "song.mp3", 200_000),
        track(chatId, 2, "clip.mp3", 10_000),
        track(chatId, 3, "Voice 001.m4a", 90_000, AudioFormat.M4A),
    )

    private suspend fun LibraryIndex.counts(chatId: Long = 1): Pair<Int?, Int?> =
        pickedChat(chatId)?.trackCount to pickedChats().firstOrNull { it.chatId == chatId }?.trackCount

    @Test
    fun theCountFollowsTheMaskAndTheNextReadSeesAChange() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(mixed())
        index.hiddenMask = 0
        assertEquals(3 to 3, index.counts())
        index.hiddenMask = ClipRules.SHORT
        assertEquals(2 to 2, index.counts())
        index.hiddenMask = ClipRules.VOICE
        assertEquals(2 to 2, index.counts())
        index.hiddenMask = ClipRules.SHORT or ClipRules.VOICE
        assertEquals(1 to 1, index.counts())
        assertEquals(3, index.pickedChat(1)?.indexedCount, "a removal still counts every file")
        index.hiddenMask = 0
        assertEquals(3 to 3, index.counts())
    }

    @Test
    fun theCountMatchesWhatTheChatPageHides() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        val tracks = mixed()
        index.upsert(tracks)
        for (mask in 0..3) {
            index.hiddenMask = mask
            assertEquals(tracks.count { !ClipRules.hidden(it, mask) }, index.pickedChat(1)?.trackCount, "mask $mask")
        }
    }

    @Test
    fun aChatThatHidesNothingAndAnUnknownDurationKeepEveryFile() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
        index.upsert(listOf(track(1, 1, "a.mp3", 200_000), track(2, 1, "b.mp3", 0)) + mixed(2).drop(1))
        index.hiddenMask = ClipRules.DEFAULT_MASK
        assertEquals(1, index.pickedChat(1)?.trackCount)
        assertEquals(1, index.pickedChat(2)?.trackCount, "an unknown duration is never hidden")
        assertEquals(listOf(1, 1), index.pickedChats().map { it.trackCount })
    }

    @Test
    fun tagsThatShowAShortClipHideItFromTheCount() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "a.mp3", 200_000), track(1, 2, "b.mp3", 0)))
        index.hiddenMask = ClipRules.SHORT
        assertEquals(2, index.pickedChat(1)?.trackCount)
        index.applyTags(listOf(TagResult(TrackId(1, 2), Tags(durationMs = 5_000))))
        assertEquals(1, index.pickedChat(1)?.trackCount)
    }

    @Test
    fun theCountIsReadFromTheCoveringIndex() {
        val path = File(dir, "plan.db").path
        open(path).close()
        BundledSQLiteDriver().open(path).use { db ->
            val sql = "SELECT ${LibraryIndex.chatColumns(ClipRules.DEFAULT_MASK)} FROM chats WHERE picked = 1"
            val plan = ArrayList<String>()
            db.prepare("EXPLAIN QUERY PLAN $sql").use { while (it.step()) plan += it.getText(3) }
            assertTrue(plan.any { "COVERING INDEX tracks_chat_hide" in it }, plan.toString())
            assertTrue(plan.none { it.startsWith("SCAN tracks") }, plan.toString())
        }
    }

    @Test
    fun aVersionFiveFileGetsTheBitsAndKeepsItsData() = runBlocking {
        val path = File(dir, "v5.db").path
        open(path).also { index ->
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(mixed())
            index.close()
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP INDEX tracks_chat_hide")
            db.execSQL("ALTER TABLE tracks DROP COLUMN hide")
            db.execSQL("PRAGMA user_version = 5")
        }
        val index = open(path)
        assertEquals(3, index.fileCount())
        assertEquals("Velo Marin 1", index.track(TrackId(1, 1))?.title)
        index.hiddenMask = ClipRules.DEFAULT_MASK
        assertEquals(1, index.pickedChat(1)?.trackCount, "the stored files got their bits")
        index.hiddenMask = 0
        assertEquals(3, index.pickedChat(1)?.trackCount)
        val version = BundledSQLiteDriver().open(path).use { db ->
            db.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0 }
        }
        assertEquals(IndexSchema.VERSION.toLong(), version)
    }
}
