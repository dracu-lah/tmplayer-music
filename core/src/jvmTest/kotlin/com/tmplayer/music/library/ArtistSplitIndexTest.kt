package com.tmplayer.music.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Artists split on credits in the index: counts, rebuilds, purges, paging and the version 2 migration. */
class ArtistSplitIndexTest {

    private val dir: File = Files.createTempDirectory("artist-split").toFile()
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
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 200_000,
    ) = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = "u$chatId-$messageId",
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = 8_000_000, dateSec = 1_700_000_000 + messageId,
        title = title, artist = artist, album = album, durationMs = durationMs, format = AudioFormat.MP3,
        groupKey = GroupKey.of(title, artist, durationMs),
    )

    private suspend fun LibraryIndex.allArtists(): List<Artist> = artists(null, 1_000)

    @Test
    fun aListCreditMakesEachArtistWithTheSong() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Velo Marin", "Ana Rell, Brio Dane & Cato Venn", album = "Sand Hours")))
        val artists = index.allArtists()
        assertEquals(listOf("Ana Rell", "Brio Dane", "Cato Venn"), artists.map { it.name })
        assertTrue(artists.all { it.songCount == 1 && it.albumCount == 1 })
        for (artist in artists) {
            assertEquals(listOf("Velo Marin"), index.artistSongs(artist.key).map { it.title })
            assertEquals(listOf("Sand Hours"), index.artistAlbums(artist.key).map { it.title })
        }
        // The song still shows the whole credit, and "Go to artist" offers all three in order.
        val song = index.songs(SongSort.Title, null, 10).single()
        assertEquals("Ana Rell, Brio Dane & Cato Venn", song.artist)
        assertEquals("ana rell", song.artistKey)
        assertEquals(listOf("Ana Rell", "Brio Dane", "Cato Venn"), index.songArtists(song).map { it.name })
    }

    @Test
    fun aLoneAmpersandStaysOneArtist() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Velo Marin", "Nadia Okafor & The Pale Rivers")))
        assertEquals(listOf("Nadia Okafor & The Pale Rivers"), index.allArtists().map { it.name })
        val song = index.songs(SongSort.Title, null, 10).single()
        assertEquals(1, index.songArtists(song).size)
    }

    @Test
    fun anArtistOnSeveralCreditsCountsEverySong() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(
            listOf(
                track(1, 1, "First", "Ana Rell", album = "One"),
                track(1, 2, "Second", "Brio Dane feat. Ana Rell", album = "Two"),
                track(1, 3, "Third", "ana rell | Cato Venn"),
            ),
        )
        val ana = index.allArtists().single { it.key == "ana rell" }
        assertEquals("Ana Rell", ana.name)
        assertEquals(3, ana.songCount)
        assertEquals(2, ana.albumCount)
        assertEquals(listOf("First", "Second", "Third"), index.artistSongs("ana rell").map { it.title })
    }

    @Test
    fun applyTagsMovesATrackBetweenArtists() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Velo Marin", "Ana Rell, Brio Dane"), track(1, 2, "Other", "Brio Dane")))
        assertEquals(mapOf("ana rell" to 1, "brio dane" to 2), index.allArtists().associate { it.key to it.songCount })

        index.applyTags(TrackId(1, 1), Tags(title = "Velo Marin", artist = "Cato Venn feat. Dara Moss", durationMs = 200_000), "")
        assertEquals(
            mapOf("brio dane" to 1, "cato venn" to 1, "dara moss" to 1),
            index.allArtists().associate { it.key to it.songCount },
        )
        assertNull(index.artist("ana rell"))
        assertEquals(listOf("Other"), index.artistSongs("brio dane").map { it.title })
    }

    @Test
    fun removingAChatOrATrackPurgesItsArtists() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
        index.upsert(
            listOf(
                track(1, 1, "Velo Marin", "Ana Rell, Brio Dane"),
                track(2, 1, "Other", "Brio Dane, Cato Venn"),
                track(2, 2, "Third", "Dara Moss, Eli Fenn"),
            ),
        )
        index.removeChat(1)
        assertEquals(listOf("Brio Dane", "Cato Venn", "Dara Moss", "Eli Fenn"), index.allArtists().map { it.name })
        assertEquals(1, index.artist("brio dane")?.songCount)

        index.removeTracks(listOf(TrackId(2, 2)))
        assertEquals(listOf("Brio Dane", "Cato Venn"), index.allArtists().map { it.name })

        // Leaving a chat through the picker purges the same way.
        index.setPicked(listOf(ChatPick(1, "One")))
        assertEquals(emptyList(), index.allArtists())
        assertEquals(emptyList(), index.artistSongs("brio dane"))
    }

    @Test
    fun artistsPageByKeyset() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "One")))
        // 30 credits of three artists each, sharing names, so pages cross shared artists.
        index.upsert((1L..30L).map { i -> track(1, i, "Song $i", "Artist ${i % 17}, Artist ${(i + 5) % 23} & Artist ${i + 100}") })
        val all = index.allArtists()
        val paged = ArrayList<Artist>()
        var after: Artist? = null
        while (true) {
            val page = index.artists(after, 7)
            if (page.isEmpty()) break
            paged += page
            after = page.last()
        }
        assertEquals(all, paged)
        assertEquals(paged.size, paged.map { it.key }.toSet().size)
        assertTrue(all.size > 30)
    }

    @Test
    fun theVersion2MigrationSplitsArtistsAndKeepsWhatTheUserKept() = runBlocking {
        val path = File(dir, "library.db").path
        // A library as version 1 left it: built here, then taken back to the version 1 shape.
        LibraryIndex.open(path).use { index ->
            index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
            index.upsert(
                listOf(
                    track(1, 1, "Velo Marin", "Ana Rell, Brio Dane & Cato Venn", album = "Sand Hours"),
                    track(1, 2, "Other", "Brio Dane"),
                    track(2, 1, "Duo Song", "Nadia Okafor & The Pale Rivers"),
                    track(2, 2, "Nobody", ""),
                ),
            )
            val songs = index.songs(SongSort.Title, null, 10).associateBy { it.title }
            index.setFavourite(songs.getValue("Velo Marin"), true)
            val list = index.createPlaylist("Mix")
            index.addToPlaylist(list, listOf(songs.getValue("Duo Song"), songs.getValue("Velo Marin")))
            index.recordPlay(songs.getValue("Other").defaultTrack, songs.getValue("Other").groupKey, 1_700_000_500)
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DROP TABLE track_artists")
            db.execSQL("CREATE INDEX tracks_artist ON tracks(artist_key, song_key, album_key)")
            // Version 1 keyed an artist by the whole credit.
            db.execSQL("DELETE FROM artists")
            db.prepare("SELECT artist FROM tracks").use { s ->
                val credits = ArrayList<String>()
                while (s.step()) credits += s.getText(0)
                for (credit in credits) {
                    val key = AlbumKey.artistKey(credit)
                    db.prepare("UPDATE tracks SET artist_key = ? WHERE artist = ?").use { u ->
                        u.bindText(1, key)
                        u.bindText(2, credit)
                        u.step()
                    }
                    if (key.isNotEmpty()) {
                        db.prepare("INSERT OR REPLACE INTO artists VALUES(?, ?, 1, 0, ?)").use { u ->
                            u.bindText(1, key)
                            u.bindText(2, credit)
                            u.bindText(3, sortOrLast(credit))
                            u.step()
                        }
                    }
                }
            }
            db.execSQL("UPDATE songs SET artist_key = (SELECT t.artist_key FROM tracks t WHERE t.chat_id = songs.default_chat_id AND t.message_id = songs.default_message_id)")
            db.execSQL("PRAGMA user_version = 1")
        }

        val index = open(path)
        assertEquals(
            listOf("Ana Rell", "Brio Dane", "Cato Venn", "Nadia Okafor & The Pale Rivers"),
            index.allArtists().map { it.name },
        )
        assertEquals(2, index.artist("brio dane")?.songCount)
        assertEquals(1, index.artist("cato venn")?.albumCount)
        assertEquals(listOf("Velo Marin"), index.favourites(10).map { it.title })
        val playlist = index.playlists().single()
        assertEquals(listOf("Duo Song", "Velo Marin"), index.playlistSongs(playlist.id).map { it.title })
        assertEquals(listOf("Other"), index.recentlyPlayed(10).map { it.title })
        val velo = index.songs(SongSort.Title, null, 10).single { it.title == "Velo Marin" }
        assertEquals("Ana Rell, Brio Dane & Cato Venn", velo.artist)
        assertEquals("ana rell", velo.artistKey)
        assertEquals(3, index.songArtists(velo).size)
        assertEquals(4, index.songCount())

        // Running it again (as after a kill part way) changes nothing.
        index.close()
        BundledSQLiteDriver().open(path).use { db -> db.execSQL("PRAGMA user_version = 1") }
        val again = open(path)
        assertEquals(4, again.allArtists().size)
        assertEquals(listOf("Velo Marin"), again.favourites(10).map { it.title })
    }

    @Test
    fun anEmptyVersion1FileMigrates() = runBlocking {
        val path = File(dir, "empty.db").path
        BundledSQLiteDriver().open(path).use { db ->
            IndexSchema.createV1(db)
            db.execSQL("PRAGMA user_version = 1")
        }
        val index = open(path)
        assertEquals(emptyList(), index.allArtists())
        index.setPicked(listOf(ChatPick(1, "One")))
        index.upsert(listOf(track(1, 1, "Velo Marin", "Ana, Brio")))
        assertEquals(2, index.allArtists().size)
    }

    @Test
    fun aMigrationBatchBoundaryKeepsEveryTrack() = runBlocking {
        val path = File(dir, "batches.db").path
        val n = IndexSchema.ARTIST_BATCH * 2 + 7
        LibraryIndex.open(path).use { index ->
            index.setPicked(listOf(ChatPick(1, "One"), ChatPick(2, "Two")))
            index.upsert((1L..n.toLong()).map { i -> track(if (i % 2 == 0L) 1 else 2, i, "Song $i", "Solo $i, Shared") })
        }
        BundledSQLiteDriver().open(path).use { db ->
            db.execSQL("DELETE FROM track_artists")
            db.execSQL("DELETE FROM artists")
            db.execSQL("PRAGMA user_version = 1")
        }
        val index = open(path)
        assertEquals(n, index.artist("shared")?.songCount)
        assertEquals(n + 1, countAll(index))
    }

    private suspend fun countAll(index: LibraryIndex): Int {
        var total = 0
        var after: Artist? = null
        while (true) {
            val page = index.artists(after, 1_000)
            if (page.isEmpty()) return total
            total += page.size
            after = page.last()
        }
    }
}
