package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The index at the size AGENTS.md asks for: 100,000 tracks over 3,000 chats with 20,000 in one.
 * First content from disk must come within 3 s and a deep page as fast as the first, or the app
 * misses its start-up target on a large account.
 */
class IndexScaleTest {

    private fun <T> timed(label: String, block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("IndexScaleTest: $label ${ms} ms")
        return result to ms
    }

    @Test
    fun aLargeAccountStaysFast() = runBlocking {
        val dir = Files.createTempDirectory("index-scale").toFile()
        try {
            val path = File(dir, "library.db").path
            val account = SyntheticAccount(trackCount = 100_000, chatCount = 3_000, bigChat = 20_000)
            LibraryIndex.open(path).use { index ->
                index.setPicked(account.chats)
                timed("upsert 100000 tracks in batches of 500") {
                    runBlocking { account.tracks.chunked(500).forEach { index.upsert(it) } }
                }
                assertEquals(100_000, index.fileCount())
            }
            println("IndexScaleTest: file size ${File(path).length() / 1024} KB")

            val (reopened, openMs) = timed("reopen") { LibraryIndex.open(path) }
            reopened.use { index ->
                var firstPageTotal = openMs
                for (sort in SongSort.entries) {
                    val (page, ms) = timed("first page by $sort") { runBlocking { index.songs(sort, null, 100) } }
                    assertEquals(100, page.size)
                    firstPageTotal = maxOf(firstPageTotal, openMs + ms)
                }
                assertTrue(firstPageTotal < 3_000, "reopen and first page took $firstPageTotal ms")

                val songs = index.songCount()
                println("IndexScaleTest: $songs songs from ${index.fileCount()} files")
                timed("songCount and fileCount") { runBlocking { index.songCount() + index.fileCount() } }

                for (sort in SongSort.entries) {
                    var cursor: SongCursor? = null
                    var read = 0
                    while (read < songs - 150) {
                        val page = index.songs(sort, cursor, minOf(1_000, songs - 150 - read))
                        if (page.isEmpty()) break
                        read += page.size
                        cursor = page.lastOrNull()?.let { SongCursor.of(it, sort) }
                    }
                    val (deep, ms) = timed("deep page by $sort after $read rows") { runBlocking { index.songs(sort, cursor, 100) } }
                    assertTrue(deep.isNotEmpty())
                    assertTrue(ms < 200, "deep page by $sort took $ms ms")
                }

                timed("letter index by Title") { runBlocking { index.letterIndex(SongSort.Title) } }
                timed("letter index by Artist") { runBlocking { index.letterIndex(SongSort.Artist) } }
                timed("first page of albums") { runBlocking { index.albums(null, 100) } }
                timed("first page of artists") { runBlocking { index.artists(null, 100) } }
                timed("first page of the 20000 chat") { runBlocking { index.chatSongs(1, null, 100) } }

                // Search: a two-word query taken from a real row, a one-letter query that matches
                // most of the library, and a query that matches nothing (a full pass of the index).
                val sample = index.songs(SongSort.Title, null, 1_000).firstOrNull { it.title.split(' ').size >= 2 }
                val twoWords = sample?.title?.split(' ')?.take(2)?.joinToString(" ").orEmpty()
                for ((label, query) in listOf("two words '$twoWords'" to twoWords, "one letter 'k'" to "k", "no match" to "qqzzx")) {
                    val (results, ms) = timed("search $label") { runBlocking { index.search(query) } }
                    if (query != "qqzzx") assertTrue(results.songs.isNotEmpty(), label)
                    assertTrue(ms < 100, "search $label took $ms ms")
                }
                timed("search two words again") { runBlocking { index.search(twoWords) } }

                // Favourites: 2,000 hearts, all read back for the rows' heart icons.
                val firstThousand = index.songs(SongSort.Title, null, 1_000)
                val toFavourite = firstThousand +
                    index.songs(SongSort.Title, firstThousand.lastOrNull()?.let { SongCursor.of(it, SongSort.Title) }, 1_000)
                timed("setFavourite on ${toFavourite.size} songs") {
                    runBlocking { toFavourite.forEach { index.setFavourite(it, true) } }
                }
                val (keys, keysMs) = timed("favouriteKeys") { runBlocking { index.favouriteKeys() } }
                println("IndexScaleTest: ${keys.size} favourite keys")
                assertEquals(2_000, keys.size, "favourites: ${keys.size}")
                assertTrue(keysMs < 50, "favouriteKeys took $keysMs ms")
                timed("first 50 favourites") { runBlocking { index.favourites(50) } }

                val (_, removeMs) = timed("removeChat of the 20000 chat") { runBlocking { index.removeChat(1) } }
                assertTrue(removeMs < 5_000, "removeChat took $removeMs ms")
                assertEquals(80_000, index.fileCount())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
