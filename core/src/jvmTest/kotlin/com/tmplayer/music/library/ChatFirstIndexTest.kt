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

/**
 * The index side of chat first: hidden clips filtered at query time in every list, chat pages by
 * message id, rows a chat page adds, and tags already read never undone by a scan seeing the file again.
 */
class ChatFirstIndexTest {

    private val dir: File = Files.createTempDirectory("chatfirst-index").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:") = LibraryIndex.open(path).also { opened += it }

    private fun track(
        chatId: Long,
        messageId: Long,
        title: String,
        artist: String = "Korvane",
        durationMs: Long = 200_000,
        name: String = "$title.mp3",
        format: AudioFormat = AudioFormat.MP3,
        album: String = "",
    ) = Track(
        chatId = chatId, messageId = messageId, fileId = messageId.toInt(), uniqueId = "u$chatId-$messageId", fileName = name,
        mime = "", sizeBytes = 1_000, dateSec = 1_700_000_000 + messageId, title = title, artist = artist, album = album,
        durationMs = durationMs, format = format, groupKey = SyntheticAccount.groupKeyOf(artist, title),
    )

    private fun library(): LibraryIndex = open().also { index ->
        runBlocking {
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(
                listOf(
                    track(1, 1, "Velo Marin", album = "Sand Hours"),
                    track(1, 2, "Jingle", durationMs = 12_000, album = "Sand Hours"),
                    track(1, 3, "Unknown Length", durationMs = 0),
                    track(1, 4, "Voice 001", artist = "", name = "Voice 001.m4a", format = AudioFormat.M4A, durationMs = 90_000),
                    track(1, 5, "Intro", artist = "Nadia Okafor", durationMs = 9_000, album = "Short Things"),
                ),
            )
        }
    }

    @Test
    fun hiddenClipsLeaveEveryList() = runBlocking {
        val index = library()
        assertEquals(5, index.songCount())
        index.hiddenMask = ClipRules.DEFAULT_MASK
        val titles = index.songs(SongSort.Title, null, 100).map { it.title }
        assertEquals(listOf("Unknown Length", "Velo Marin"), titles)
        assertEquals(2, index.songCount())
        assertEquals(5, index.fileCount(), "files are counted as they are")
        assertEquals(2, index.letterIndex(SongSort.Title).size)
        assertEquals(listOf("Unknown Length", "Velo Marin"), index.chatSongs(1, null, 100).map { it.title }.sorted())
        assertEquals(listOf("Unknown Length", "Velo Marin"), index.recentlyAdded(10).map { it.title }.sorted())

        // Albums count what shows; an album of only clips is left out.
        val albums = index.albums(null, 100)
        assertEquals(listOf("Sand Hours"), albums.map { it.title })
        assertEquals(1, albums.single().songCount)
        assertEquals(listOf("Velo Marin"), index.albumSongs(albums.single().key).map { it.title })

        // Artists likewise: one with only a short clip goes.
        val artists = index.artists(null, 100)
        assertEquals(listOf("Korvane"), artists.map { it.name })
        assertEquals(2, artists.single().songCount)
        assertEquals(2, index.artistSongs(artists.single().key).size)

        val found = index.search("intro")
        assertTrue(found.songs.isEmpty() && found.albums.isEmpty() && found.artists.isEmpty())
        assertEquals(1, index.search("velo").songs.size)
        assertEquals(1, index.search("sand").albums.single().songCount)

        // Only short clips hidden: the voice file comes back.
        index.hiddenMask = ClipRules.SHORT
        assertEquals(3, index.songCount())
        index.hiddenMask = 0
        assertEquals(5, index.songCount())
        assertEquals(2, index.albums(null, 100).size)
    }

    @Test
    fun recentlyPlayedIsFilteredFavouritesAreNot() = runBlocking {
        val index = library()
        val jingle = index.songs(SongSort.Title, null, 100).first { it.title == "Jingle" }
        index.recordPlay(jingle.defaultTrack, jingle.groupKey, 100)
        index.setFavourite(jingle, true)
        index.hiddenMask = ClipRules.DEFAULT_MASK
        assertTrue(index.recentlyPlayed(10).isEmpty())
        assertEquals(listOf("Jingle"), index.favourites(10).map { it.title }, "a favourite is the user's own pick")
    }

    @Test
    fun aDocumentIsJudgedOnceItsTagsGiveALength() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Memo", artist = "", name = "Recording_20231012_101112.m4a", format = AudioFormat.M4A, durationMs = 0)))
        index.hiddenMask = ClipRules.DEFAULT_MASK
        assertEquals(1, index.songCount(), "length unknown: shown")
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(durationMs = 95_000, channels = 1, bitrateKbps = 64, format = AudioFormat.M4A))))
        assertEquals(0, index.songCount(), "a recorder's file of known length: hidden")
        assertEquals(1, index.track(TrackId(1, 1))?.channels)
    }

    @Test
    fun chatPagesAreReadByMessageIdBothWays() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
        index.upsert((1L..50L).map { track(1, it, "Song $it") } + listOf(track(2, 1, "Elsewhere")))
        assertEquals((50L downTo 41L).toList(), index.chatTracks(1, oldestFirst = false, after = null, limit = 10).map { it.messageId })
        assertEquals((40L downTo 31L).toList(), index.chatTracks(1, false, 41, 10).map { it.messageId })
        assertEquals((1L..10L).toList(), index.chatTracks(1, true, null, 10).map { it.messageId })
        assertEquals((11L..20L).toList(), index.chatTracks(1, true, 10, 10).map { it.messageId })
        assertEquals(setOf(3L, 7L), index.chatTracksByIds(1, listOf(3, 7, 999)).keys)
    }

    @Test
    fun aChatPageAddsOnlyWhatIsMissingAndAScanNeverUndoesReadTags() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Lumen Tide")))
        index.applyTags(listOf(TagResult(TrackId(1, 1), Tags(title = "Lumen Tide", album = "Sand Hours", durationMs = 200_000))))
        assertEquals(1, index.upsertMissing(listOf(track(1, 1, "Lumen Tide"), track(1, 2, "New One"))))
        assertEquals("Sand Hours", index.track(TrackId(1, 1))?.album)
        assertEquals(TagState.Read, index.track(TrackId(1, 1))?.tagState)
        // The scan seeing the same file again keeps the tags.
        index.upsert(listOf(track(1, 1, "Lumen Tide")))
        assertEquals("Sand Hours", index.track(TrackId(1, 1))?.album)
        // A different file in the same message (edited) replaces them.
        index.upsert(listOf(track(1, 1, "Lumen Tide").copy(uniqueId = "edited")))
        assertEquals("", index.track(TrackId(1, 1))?.album)
        // A chat that is not chosen gets nothing.
        assertEquals(0, index.upsertMissing(listOf(track(9, 1, "Not Here"))))
    }

    @Test
    fun documentsWithoutALengthAreAskedForFirst() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "A"), track(1, 2, "B", durationMs = 0), track(1, 3, "C", durationMs = 0)))
        assertEquals(listOf(3L, 2L), index.pendingDocuments(10).map { it.messageId })
    }

    @Test
    fun aVersionTwoFileGetsTheClipColumns() = runBlocking {
        val path = File(dir, "v2.db").path
        open(path).also { index ->
            index.setPicked(listOf(ChatPick(1, "One")))
            index.upsert(listOf(track(1, 1, "Velo Marin"), track(1, 2, "Jingle", durationMs = 5_000)))
            index.close()
        }
        // Back to version 2: drop the new columns' meaning by zeroing them and the version.
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("UPDATE songs SET hide = 0")
            db.execSQL("PRAGMA user_version = 2")
        }
        val index = open(path)
        index.hiddenMask = ClipRules.DEFAULT_MASK
        assertEquals(listOf("Velo Marin"), index.songs(SongSort.Title, null, 10).map { it.title })
    }
}
