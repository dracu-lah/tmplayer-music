package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Jumps by offset, reading backwards, the cached letter bar and tidying stored names (2026-10-30). */
class LibraryIndexJumpTest {

    private val dir: File = Files.createTempDirectory("index-jump").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun open(path: String = ":memory:"): LibraryIndex = LibraryIndex.open(path).also { opened += it }

    private fun track(chatId: Long, messageId: Long, title: String, artist: String = "Korvane", albumArtist: String = "") = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = "u$chatId-$messageId",
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = 8_000_000, dateSec = 1_700_000_000 + messageId,
        title = title, artist = artist, albumArtist = albumArtist, durationMs = 200_000, format = AudioFormat.MP3,
        groupKey = GroupKey.of(title, artist, 200_000),
    )

    @Test
    fun `songs at an offset and before a cursor match the keyset pages`() = runBlocking {
        val index = open()
        val account = SyntheticAccount(trackCount = 1_200, chatCount = 6, bigChat = 300, seed = 11)
        index.setPicked(account.chats)
        account.tracks.chunked(500).forEach { index.upsert(it) }
        for (sort in SongSort.entries) {
            val all = ArrayList<Song>()
            var cursor: SongCursor? = null
            while (true) {
                val page = index.songs(sort, cursor, 300)
                if (page.isEmpty()) break
                all += page
                cursor = SongCursor.of(page.last(), sort)
            }
            for (offset in listOf(0, 1, 37, all.size / 2, all.size - 1)) {
                val page = index.songsAt(sort, offset, 25)
                assertEquals(all.drop(offset).take(25).map { it.groupKey }, page.map { it.groupKey }, "$sort at $offset")
            }
            assertTrue(index.songsAt(sort, all.size + 10, 25).isEmpty())
            val at = all.size / 2
            val before = index.songsBefore(sort, SongCursor.of(all[at], sort), 40)
            assertEquals(all.subList(at - 40, at).map { it.groupKey }, before.map { it.groupKey }, "$sort before")
            assertTrue(index.songsBefore(sort, SongCursor.of(all.first(), sort), 40).isEmpty())
        }
    }

    @Test
    fun `the letter bar is read again only after a write`() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Apple"), track(1, 2, "Lemon"), track(1, 3, "Mango")))
        val first = index.letterIndex(SongSort.Title)
        assertSame(first, index.letterIndex(SongSort.Title))
        index.upsert(listOf(track(1, 4, "Zest")))
        val second = index.letterIndex(SongSort.Title)
        assertNotSame(first, second)
        assertEquals(listOf('A', 'L', 'M', 'Z'), second.map { it.first })
    }

    @Test
    fun `look-alike titles never repeat a letter after Z`() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        val titles = listOf("Apple", "Lemon", "Mango", "Zest", "ᴀʟᴘʜᴀ", "ʟᴏᴠᴇ", "ᴍᴏᴏɴ", "ａbc")
        index.upsert(titles.mapIndexed { i, t -> track(1, i + 1L, t, artist = "Artist $i") })
        for (sort in listOf(SongSort.Title, SongSort.Artist, SongSort.Album)) {
            val letters = index.letterIndex(sort).map { it.first }
            assertEquals(letters.size, letters.toSet().size, "$sort: $letters")
        }
        assertEquals(listOf('A', 'L', 'M', 'Z'), index.letterIndex(SongSort.Title).map { it.first })
    }

    @Test
    fun `stored names are tidied when the index opens`() = runBlocking {
        val path = File(dir, "library.db").path
        val first = open(path)
        first.setPicked(listOf(ChatPick(1, "One")))
        first.upsert(
            listOf(
                track(1, 1, "#Some_Movie_Video_Songs_-_Title_Words", artist = "<unknown>"),
                track(1, 2, "01 Glass Harbour", artist = "Unknown Artist", albumArtist = "<unknown>"),
                track(1, 3, "7 Rings", artist = "Nadia Okafor"),
            ),
        )
        first.setFavourite(first.songs(SongSort.Title, null, 10).first { it.title.startsWith("#") }, true)
        first.close()
        opened.remove(first)

        val again = open(path)
        assertEquals("Some Movie Video Songs - Title Words", again.track(TrackId(1, 1))?.title)
        assertEquals("", again.track(TrackId(1, 1))?.artist)
        assertEquals("Glass Harbour", again.track(TrackId(1, 2))?.title)
        assertEquals("", again.track(TrackId(1, 2))?.albumArtist)
        assertEquals("7 Rings", again.track(TrackId(1, 3))?.title)
        assertEquals(listOf("Nadia Okafor"), again.artists(null, 10).map { it.name })
        // The favourite followed its song to the new key.
        assertEquals(listOf("Some Movie Video Songs - Title Words"), again.favourites(10).map { it.title })
        // A second pass finds nothing left to change.
        assertEquals(0, again.tidyStoredNames())
    }
}
