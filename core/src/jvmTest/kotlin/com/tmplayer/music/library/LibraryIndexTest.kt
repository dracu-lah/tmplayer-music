package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The on-disk library: folding versions, purging chats, paging and surviving a bad file. */
class LibraryIndexTest {

    private val dir: File = Files.createTempDirectory("index-test").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:"): LibraryIndex = LibraryIndex.open(path).also { opened += it }

    private fun track(
        chatId: Long,
        messageId: Long,
        title: String = "Velo Marin",
        artist: String = "Korvane",
        durationMs: Long = 200_000,
        format: AudioFormat = AudioFormat.MP3,
        size: Long = 8_000_000,
        uniqueId: String = "u$chatId-$messageId",
        album: String = "",
        albumArtist: String = "",
        year: Int = 0,
        date: Long = 1_700_000_000 + messageId,
    ) = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = uniqueId,
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = size, dateSec = date, title = title, artist = artist,
        album = album, albumArtist = albumArtist, year = year, durationMs = durationMs, format = format,
        groupKey = SyntheticAccount.groupKeyOf(artist, title),
    )

    private fun LibraryIndex.pick(vararg ids: Long) = runBlocking { setPicked(ids.map { ChatPick(it, "Chat $it") }) }

    @Test
    fun upsertAndReadBack() = runBlocking {
        val index = open()
        index.pick(1)
        val t = track(1, 10, album = "Sand Hours", year = 2001)
        assertEquals(1, index.upsert(listOf(t)))
        assertEquals(t.copy(groupKey = t.groupKey), index.track(t.id))
        val songs = index.songs(SongSort.Title, null, 10)
        assertEquals(1, songs.size)
        assertEquals("Velo Marin", songs.firstOrNull()?.title)
        assertEquals(t.id, songs.firstOrNull()?.defaultTrack)
        assertEquals(1, index.pickedChats().firstOrNull()?.trackCount)
        assertEquals(1, index.albums(null, 10).size)
        assertEquals(1, index.artists(null, 10).size)
    }

    @Test
    fun tracksOfAChatNotChosenAreDropped() = runBlocking {
        val index = open()
        assertEquals(0, index.upsert(listOf(track(9, 1))))
        assertEquals(0, index.fileCount())
    }

    @Test
    fun versionsFoldIntoOneSong() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(
            listOf(
                track(1, 10),
                track(2, 20, durationMs = 201_000, format = AudioFormat.FLAC, size = 30_000_000),
                track(2, 21, durationMs = 199_500),
            ),
        )
        val songs = index.songs(SongSort.Title, null, 10)
        assertEquals(1, songs.size)
        val song = songs.firstOrNull()
        assertNotNull(song)
        assertEquals(3, song.versionCount)
        assertEquals(AudioFormat.FLAC, song.bestFormat)
        assertEquals(TrackId(2, 20), song.defaultTrack)
        assertEquals(3, index.fileCount())
        val versions = index.versions(song.groupKey)
        assertEquals(3, versions.size)
        assertEquals(TrackId(2, 20), versions.firstOrNull()?.id)
    }

    @Test
    fun aForwardCountsOnce() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10, uniqueId = "same"), track(2, 20, uniqueId = "same")))
        val song = index.songs(SongSort.Title, null, 10).firstOrNull()
        assertEquals(1, song?.versionCount)
        assertEquals(2, index.fileCount())
    }

    @Test
    fun durationsFarApartAreTwoSongs() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 10, durationMs = 200_000), track(1, 11, durationMs = 260_000), track(1, 12, durationMs = 0)))
        val songs = index.songs(SongSort.Title, null, 10)
        assertEquals(2, songs.size)
        assertEquals(listOf(2, 1), songs.map { it.versionCount }.sortedDescending())
    }

    @Test
    fun anUnknownArtistFoldsOnlyWithinOneSecond() {
        assertEquals(listOf(0, 0), defaultClusters(listOf(200_000, 202_500), artistKnown = true))
        assertEquals(listOf(0, 1), defaultClusters(listOf(200_000, 202_500), artistKnown = false))
        assertEquals(listOf(0, 0, 1), defaultClusters(listOf(0, 100_000, 400_000), artistKnown = true))
    }

    @Test
    fun removeChatPurgesAndAnotherChatsVersionSurvives() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(
            listOf(
                track(1, 10, format = AudioFormat.FLAC, size = 30_000_000, album = "Glass Rooms"),
                track(2, 20, album = "Glass Rooms"),
                track(1, 11, title = "Only Here", album = "Glass Rooms"),
            ),
        )
        index.upsertLyricsFiles(listOf(LyricsFile(1, 12, 5, "Only Here", 100)))
        assertEquals(2, index.songCount())
        val before = index.songs(SongSort.Title, null, 10).firstOrNull { it.title == "Velo Marin" }
        assertEquals(TrackId(1, 10), before?.defaultTrack)
        assertEquals(2, before?.versionCount)

        index.removeChat(1)

        val after = index.songs(SongSort.Title, null, 10)
        assertEquals(1, after.size)
        assertEquals(1, after.firstOrNull()?.versionCount)
        assertEquals(TrackId(2, 20), after.firstOrNull()?.defaultTrack)
        assertNull(index.track(TrackId(1, 11)))
        assertNull(index.lyricsFor(1, "only here"))
        assertEquals(listOf(2L), index.pickedChats().map { it.chatId })
        assertEquals(1, index.albums(null, 10).firstOrNull()?.songCount)
        // A scan still running for the removed chat cannot bring it back.
        assertEquals(0, index.upsert(listOf(track(1, 13))))
    }

    @Test
    fun unpickingAChatPurgesItsTracks() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10), track(2, 20, title = "Other Tide")))
        index.saveScanState(1, ScanState(audio = ScanCursor(newestMessageId = 10, done = true)))
        index.pick(2)
        assertEquals(1, index.fileCount())
        assertEquals(ScanState(), index.scanState(1))
        assertEquals(listOf(2L), index.pickedChats().map { it.chatId })
    }

    @Test
    fun removeTracksMovesTheDefault() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 10, format = AudioFormat.FLAC), track(1, 11)))
        index.removeTracks(listOf(TrackId(1, 10)))
        val song = index.songs(SongSort.Title, null, 10).firstOrNull()
        assertEquals(TrackId(1, 11), song?.defaultTrack)
        assertEquals(1, song?.versionCount)
    }

    @Test
    fun scanStateRoundTrips() = runBlocking {
        val index = open()
        index.pick(5)
        val state = ScanState(audio = ScanCursor(10, true, 900), documents = ScanCursor(40, false, 800), lastScanAt = 123)
        index.saveScanState(5, state)
        assertEquals(state, index.scanState(5))
        assertNull(index.scanState(6))
    }

    @Test
    fun lyricsMatchByNameWithoutCase() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsertLyricsFiles(listOf(LyricsFile(1, 3, 7, "Velo Marin", 200), LyricsFile(1, 4, 8, "velo marin", 210)))
        assertEquals(4L, index.lyricsFor(1, "VELO MARIN ")?.messageId)
        assertNull(index.lyricsFor(2, "velo marin"))
    }

    @Test
    fun pagingByEverySortReturnsEachSongOnceInOrder() = runBlocking {
        val index = open()
        val account = SyntheticAccount(trackCount = 1_500, chatCount = 12, bigChat = 400, seed = 7)
        index.setPicked(account.chats)
        account.tracks.chunked(500).forEach { index.upsert(it) }
        val total = index.songCount()
        assertTrue(total in 500 until 1_500, "songs: $total")
        for (sort in SongSort.entries) {
            val seen = ArrayList<Song>()
            var cursor: SongCursor? = null
            while (true) {
                val page = index.songs(sort, cursor, 37)
                if (page.isEmpty()) break
                seen += page
                cursor = page.lastOrNull()?.let { SongCursor.of(it, sort) }
            }
            assertEquals(total, seen.size, "$sort")
            assertEquals(total, seen.map { it.groupKey }.toSet().size, "$sort")
            val keys = seen.map { SongCursor.of(it, sort) }
            val ordered = keys.sortedWith(
                if (sort == SongSort.Added) compareBy<SongCursor>({ it.key.toLong() }, { it.groupKey })
                else compareBy({ it.key }, { it.groupKey }),
            )
            assertEquals(ordered, keys, "$sort")
        }
        val letters = index.letterIndex(SongSort.Title)
        assertTrue(letters.isNotEmpty())
        assertEquals(letters.map { it.second }.sorted(), letters.map { it.second })
        assertEquals(0, letters.firstOrNull()?.second)
        assertTrue(index.letterIndex(SongSort.Added).isEmpty())

        val chatSeen = ArrayList<Song>()
        var cursor: SongCursor? = null
        while (true) {
            val page = index.chatSongs(1, cursor, 50)
            if (page.isEmpty()) break
            chatSeen += page
            cursor = page.lastOrNull()?.let { SongCursor.of(it, SongSort.Title) }
        }
        val expected = account.tracks.filter { it.chatId == 1L }.map { it.id }.toSet()
        assertTrue(chatSeen.isNotEmpty())
        assertEquals(chatSeen.size, chatSeen.map { it.groupKey }.toSet().size)
        for (song in chatSeen) assertTrue(index.versions(song.groupKey).any { it.id in expected })
    }

    @Test
    fun albumsKeepSameNamesApart() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(
            listOf(
                track(1, 1, title = "Orin", artist = "Talvek", album = "Low Lanterns", albumArtist = "Talvek", year = 1999),
                track(1, 2, title = "Pell", artist = "Talvek", album = "Low Lanterns", albumArtist = "Talvek", year = 1999),
                track(1, 3, title = "Qest", artist = "Mirabo", album = "Low Lanterns", albumArtist = "Mirabo", year = 1999),
                track(1, 4, title = "Rusk", artist = "Talvek", album = "Low Lanterns", albumArtist = "Talvek", year = 2015),
                track(1, 5, title = "Sorn", artist = "Talvek"),
            ),
        )
        val albums = index.albums(null, 10)
        assertEquals(3, albums.size)
        assertEquals(listOf(1, 1, 2), albums.map { it.songCount }.sorted())
        val talvek = index.artists(null, 10).firstOrNull { it.name == "Talvek" }
        assertNotNull(talvek)
        assertEquals(4, talvek.songCount)
        assertEquals(2, talvek.albumCount)
        assertEquals(listOf(1999, 2015), index.artistAlbums(talvek.key).map { it.year })
        assertEquals(4, index.artistSongs(talvek.key).size)
        val first = albums.firstOrNull { it.songCount == 2 }
        assertNotNull(first)
        assertEquals(listOf("Orin", "Pell"), index.albumSongs(first.key).map { it.title })
        val page2 = index.albums(albums.firstOrNull(), 10)
        assertEquals(2, page2.size)
    }

    @Test
    fun reopeningFromAFileKeepsEverything() = runBlocking {
        val path = File(dir, "library.db").path
        val index = open(path)
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10), track(2, 20), track(1, 11, title = "Fen Hollow")))
        index.saveScanState(1, ScanState(audio = ScanCursor(newestMessageId = 11, done = true)))
        val songs = index.songs(SongSort.Title, null, 10)
        index.close()

        val again = open(path)
        assertEquals(songs, again.songs(SongSort.Title, null, 10))
        assertEquals(3, again.fileCount())
        assertEquals(ScanState(audio = ScanCursor(newestMessageId = 11, done = true)), again.scanState(1))
        assertEquals(listOf(1L, 2L), again.pickedChats().map { it.chatId })
    }

    @Test
    fun aCorruptFileIsMovedAsideAndOpensEmpty() = runBlocking {
        val file = File(dir, "library.db")
        file.writeBytes(ByteArray(8_192) { (it * 31 + 7).toByte() })
        val index = open(file.path)
        assertEquals(0, index.songCount())
        assertTrue(File(dir, "library.db.broken").exists())
        index.pick(1)
        assertEquals(1, index.upsert(listOf(track(1, 1))))
    }

    @Test
    fun aThrowingPickFallsBackToTheDefaultRule() = runBlocking {
        val index = LibraryIndex.open(":memory:", pick = { error("bad pick") }).also { opened += it }
        index.pick(1)
        index.upsert(listOf(track(1, 1, format = AudioFormat.FLAC), track(1, 2)))
        assertEquals(TrackId(1, 1), index.songs(SongSort.Title, null, 1).firstOrNull()?.defaultTrack)
    }
}
