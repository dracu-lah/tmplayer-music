package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the user keeps about songs (favourites, plays, playlists, recent searches) and search. The
 * thread through all of it: a song's key can change when a new version arrives or a chat goes, and
 * nothing the user kept may be lost or crash because of it.
 */
class LibraryV3Test {

    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
    }

    private fun open(): LibraryIndex = LibraryIndex.open(":memory:").also { opened += it }

    private fun track(
        chatId: Long,
        messageId: Long,
        title: String = "Velo Marin",
        artist: String = "Korvane",
        durationMs: Long = 200_000,
        format: AudioFormat = AudioFormat.MP3,
        size: Long = 8_000_000,
        album: String = "",
        year: Int = 0,
    ) = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = "u$chatId-$messageId",
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = size, dateSec = 1_700_000_000 + messageId, title = title,
        artist = artist, album = album, albumArtist = if (album.isEmpty()) "" else artist, year = year, durationMs = durationMs,
        format = format, groupKey = SyntheticAccount.groupKeyOf(artist, title),
    )

    private suspend fun LibraryIndex.pick(vararg ids: Long) = setPicked(ids.map { ChatPick(it, "Chat $it") })

    private suspend fun LibraryIndex.byTitle(title: String): Song {
        val song = songs(SongSort.Title, null, 1_000).firstOrNull { it.title == title }
        assertNotNull(song, title)
        return song
    }

    // Song shortcuts

    @Test
    fun albumAndArtistKeysResolve() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 1, album = "Sand Hours", year = 2001), track(1, 2, title = "Loose Tide")))
        val song = index.byTitle("Velo Marin")
        assertTrue(song.albumKey.isNotEmpty())
        assertEquals("Sand Hours", index.album(song.albumKey)?.title)
        assertEquals("Korvane", index.artist(song.artistKey)?.name)
        val loose = index.byTitle("Loose Tide")
        assertEquals("", loose.albumKey)
        assertEquals(null, index.album(loose.albumKey))
        assertEquals(song.artistKey, loose.artistKey)
        assertEquals(null, index.artist("nobody"))
    }

    // Favourites

    @Test
    fun aFavouriteSurvivesANewVersionChangingTheSongKey() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10)))
        val before = index.byTitle("Velo Marin")
        assertTrue(index.setFavourite(before, true))
        assertEquals(setOf(before.groupKey), index.favouriteKeys())

        // A shorter edit arrives and takes cluster 0, so the favourite's song is renumbered.
        index.upsert(listOf(track(2, 20, durationMs = 150_000)))
        val after = index.songs(SongSort.Title, null, 10).firstOrNull { it.defaultTrack == TrackId(1, 10) }
        assertNotNull(after)
        assertNotEquals(before.groupKey, after.groupKey)
        assertEquals(setOf(after.groupKey), index.favouriteKeys())
        assertEquals(listOf(after.groupKey), index.favourites(10).map { it.groupKey })

        // Setting it again does not add a second row, and turning it off finds it by the new key.
        assertTrue(index.setFavourite(after, true))
        assertEquals(1, index.favourites(10).size)
        index.setFavourite(after, false)
        assertTrue(index.favouriteKeys().isEmpty())
    }

    @Test
    fun favouritesAreNewestFirstAndHideWhenTheirChatGoes() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 1, title = "Orin Gale"), track(2, 2, title = "Pell Reach")))
        index.setFavourite(index.byTitle("Orin Gale"), true)
        index.setFavourite(index.byTitle("Pell Reach"), true)
        assertEquals(listOf("Pell Reach", "Orin Gale"), index.favourites(10).map { it.title })
        index.removeChat(2)
        assertEquals(listOf("Orin Gale"), index.favourites(10).map { it.title })
        index.pick(1, 2)
        index.upsert(listOf(track(2, 2, title = "Pell Reach")))
        assertEquals(listOf("Pell Reach", "Orin Gale"), index.favourites(10).map { it.title })
    }

    // Playlists

    @Test
    fun aPlaylistEntryFollowsTheSongWhenItsOwnVersionGoes() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10, format = AudioFormat.FLAC, size = 30_000_000), track(2, 20)))
        val song = index.byTitle("Velo Marin")
        assertEquals(TrackId(1, 10), song.defaultTrack)
        val id = index.createPlaylist("Evening")
        assertEquals(1, index.addToPlaylist(id, listOf(song)))
        assertEquals(setOf(id), index.playlistsContaining(song))

        index.removeChat(1)

        val songs = index.playlistSongs(id)
        assertEquals(listOf(TrackId(2, 20)), songs.map { it.defaultTrack })
        assertEquals(setOf(id), index.playlistsContaining(songs.first()))
        assertEquals(1, index.playlists().firstOrNull()?.songCount)
    }

    @Test
    fun anEntryWithNoVersionLeftHidesAndComesBack() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 10, title = "Only Here"), track(2, 20, title = "Other Tide")))
        val id = index.createPlaylist("Mix")
        index.addToPlaylist(id, listOf(index.byTitle("Only Here"), index.byTitle("Other Tide")))

        index.removeChat(1)
        assertEquals(listOf("Other Tide"), index.playlistSongs(id).map { it.title })
        assertEquals(1, index.playlists().firstOrNull()?.songCount)

        index.pick(1, 2)
        index.upsert(listOf(track(1, 10, title = "Only Here")))
        assertEquals(listOf("Only Here", "Other Tide"), index.playlistSongs(id).map { it.title })
        assertEquals(2, index.playlists().firstOrNull()?.songCount)
    }

    @Test
    fun addingSkipsSongsAlreadyThere() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf("Aro", "Bex", "Cal").mapIndexed { i, t -> track(1, i + 1L, title = t) })
        val (a, b, c) = listOf("Aro", "Bex", "Cal").map { index.byTitle(it) }
        val id = index.createPlaylist("  ")
        assertEquals("Playlist", index.playlists().firstOrNull()?.name)
        assertEquals(2, index.addToPlaylist(id, listOf(a, b)))
        assertEquals(1, index.addToPlaylist(id, listOf(b, c, c)))
        assertEquals(0, index.addToPlaylist(id, listOf(a)))
        assertEquals(0, index.addToPlaylist(999, listOf(a)))
        assertEquals(listOf("Aro", "Bex", "Cal"), index.playlistSongs(id).map { it.title })
    }

    @Test
    fun moveAndRemoveKeepTheOrder() = runBlocking {
        val index = open()
        index.pick(1, 2)
        val titles = listOf("Aro", "Bex", "Cal", "Dun", "Eli")
        index.upsert(titles.mapIndexed { i, t -> track(1, i + 1L, title = t) } + track(2, 9, title = "Gone Soon"))
        val id = index.createPlaylist("Order")
        index.addToPlaylist(id, listOf(index.byTitle("Aro"), index.byTitle("Gone Soon")) + titles.drop(1).map { index.byTitle(it) })
        index.removeChat(2)
        // Shown: Aro Bex Cal Dun Eli, with the hidden entry stored between Aro and Bex.
        assertEquals(titles, index.playlistSongs(id).map { it.title })

        index.movePlaylistItem(id, 0, 3)
        assertEquals(listOf("Bex", "Cal", "Dun", "Aro", "Eli"), index.playlistSongs(id).map { it.title })
        index.movePlaylistItem(id, 4, 1)
        assertEquals(listOf("Bex", "Eli", "Cal", "Dun", "Aro"), index.playlistSongs(id).map { it.title })
        index.removeFromPlaylist(id, 2)
        assertEquals(listOf("Bex", "Eli", "Dun", "Aro"), index.playlistSongs(id).map { it.title })
        // Out of range positions change nothing.
        index.movePlaylistItem(id, 0, 9)
        index.removeFromPlaylist(id, -1)
        assertEquals(listOf("Bex", "Eli", "Dun", "Aro"), index.playlistSongs(id).map { it.title })

        // The hidden entry kept its place before Bex and comes back there.
        index.pick(1, 2)
        index.upsert(listOf(track(2, 9, title = "Gone Soon")))
        assertEquals(listOf("Gone Soon", "Bex", "Eli", "Dun", "Aro"), index.playlistSongs(id).map { it.title })
    }

    @Test
    fun renameDeleteAndCover() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 1).copy(thumbFileId = 77)))
        val id = index.createPlaylist(" Road ")
        index.addToPlaylist(id, listOf(index.byTitle("Velo Marin")))
        val other = index.createPlaylist("x".repeat(300))
        assertEquals(200, index.playlists().firstOrNull { it.id == other }?.name?.length)
        index.renamePlaylist(id, "Long Road")
        val playlist = index.playlists().firstOrNull { it.id == id }
        assertEquals("Long Road", playlist?.name)
        assertEquals(77, playlist?.thumbFileId)
        index.deletePlaylist(id)
        assertEquals(listOf(other), index.playlists().map { it.id })
        assertTrue(index.playlistSongs(id).isEmpty())
    }

    // Plays

    @Test
    fun recentlyPlayedIsDistinctAndNewestFirst() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 1, title = "Aro"), track(1, 2, title = "Bex"), track(1, 3, title = "Cal")))
        val aro = index.byTitle("Aro")
        val bex = index.byTitle("Bex")
        index.recordPlay(aro.defaultTrack, aro.groupKey, 100)
        index.recordPlay(bex.defaultTrack, bex.groupKey, 200)
        index.recordPlay(aro.defaultTrack, aro.groupKey, 300)
        assertEquals(listOf("Aro", "Bex"), index.recentlyPlayed(10).map { it.title })
        assertEquals(listOf("Aro"), index.recentlyPlayed(1).map { it.title })
        // A file the index does not know, from a chat that is not chosen, is not recorded.
        index.recordPlay(TrackId(5, 5), "k", 400)
        assertEquals(2, index.recentlyPlayed(10).size)
        assertEquals(listOf("Cal", "Bex", "Aro"), index.recentlyAdded(10).map { it.title })
    }

    @Test
    fun playsOfARemovedChatArePurged() = runBlocking {
        val index = open()
        index.pick(1, 2)
        index.upsert(listOf(track(1, 1, title = "Aro"), track(2, 2, title = "Bex")))
        val aro = index.byTitle("Aro")
        val bex = index.byTitle("Bex")
        index.recordPlay(aro.defaultTrack, aro.groupKey, 100)
        index.recordPlay(bex.defaultTrack, bex.groupKey, 200)
        index.pick(2)
        assertEquals(listOf("Bex"), index.recentlyPlayed(10).map { it.title })
        // Purged, not hidden: adding the chat back does not bring its history back.
        index.pick(1, 2)
        index.upsert(listOf(track(1, 1, title = "Aro")))
        assertEquals(listOf("Bex"), index.recentlyPlayed(10).map { it.title })
    }

    @Test
    fun playHistoryTrimsAtFiveThousand() = runBlocking {
        val index = open()
        index.pick(1)
        index.upsert(listOf(track(1, 1, title = "Aro"), track(1, 2, title = "Bex")))
        val aro = index.byTitle("Aro")
        val bex = index.byTitle("Bex")
        index.recordPlay(aro.defaultTrack, aro.groupKey, 1)
        repeat(LibraryIndex.MAX_PLAYS - 1) { index.recordPlay(bex.defaultTrack, bex.groupKey, 2L + it) }
        assertEquals(listOf("Bex", "Aro"), index.recentlyPlayed(10).map { it.title })
        index.recordPlay(bex.defaultTrack, bex.groupKey, 10_000)
        assertEquals(listOf("Bex"), index.recentlyPlayed(10).map { it.title })
    }

    // Search

    private suspend fun searchLibrary(): LibraryIndex {
        val index = open()
        index.pick(1)
        index.upsert(
            listOf(
                track(1, 1, title = "Émile Fen", artist = "Korvane"),
                track(1, 2, title = "Fen Hollow", artist = "Talvek", album = "Sand Hours", year = 1999),
                track(1, 3, title = "Lofen", artist = "Mirabo"),
                track(1, 4, title = "Rate 100% Pure", artist = "Mirabo"),
                track(1, 5, title = "snake_case", artist = "Mirabo"),
                track(1, 6, title = "Rate 1000 Pure", artist = "Mirabo"),
                track(1, 7, title = "snakeXcase", artist = "Mirabo"),
            ),
        )
        return index
    }

    @Test
    fun searchFoldsCaseAndAccents() = runBlocking {
        val index = searchLibrary()
        assertEquals(listOf("Émile Fen"), index.search("emile").songs.map { it.title })
        assertEquals(listOf("Émile Fen"), index.search("ÉMILE").songs.map { it.title })
        assertEquals(listOf("Émile Fen"), index.search("Émile").songs.map { it.title })
    }

    @Test
    fun searchNeedsEveryWordInAnyOrder() = runBlocking {
        val index = searchLibrary()
        assertEquals(listOf("Émile Fen"), index.search("korvane emile").songs.map { it.title })
        assertEquals(listOf("Émile Fen"), index.search("  fen   KORV ").songs.map { it.title })
        assertTrue(index.search("korvane hollow").songs.isEmpty())
    }

    @Test
    fun searchRanksAStartFirst() = runBlocking {
        val index = searchLibrary()
        assertEquals(listOf("Fen Hollow", "Émile Fen", "Lofen"), index.search("fen").songs.map { it.title })
    }

    @Test
    fun searchEscapesWildcards() = runBlocking {
        val index = searchLibrary()
        assertEquals(listOf("Rate 100% Pure"), index.search("100%").songs.map { it.title })
        assertEquals(listOf("Rate 100% Pure"), index.search("%").songs.map { it.title })
        assertEquals(listOf("snake_case"), index.search("_").songs.map { it.title })
        assertTrue(index.search("\\").songs.isEmpty())
    }

    @Test
    fun searchFindsAlbumsAndArtistsAndRejectsBadQueries() = runBlocking {
        val index = searchLibrary()
        assertEquals(listOf("Sand Hours"), index.search("hours").albums.map { it.title })
        assertEquals(listOf("Sand Hours"), index.search("talvek sand").albums.map { it.title })
        assertEquals(listOf("Talvek"), index.search("TALV").artists.map { it.name })
        assertEquals(listOf("Fen Hollow"), index.search("sand hours").songs.map { it.title })
        assertEquals(SearchResults.EMPTY, index.search(""))
        assertEquals(SearchResults.EMPTY, index.search("   "))
        assertEquals(SearchResults.EMPTY, index.search("f".repeat(201)))
        assertEquals(2, index.search("mirabo", limit = 2).songs.size)
    }

    @Test
    fun recentSearchesDedupeAndCap() = runBlocking {
        val index = open()
        index.addRecentSearch("  Fen ")
        index.addRecentSearch("   ")
        index.addRecentSearch("Hollow")
        index.addRecentSearch("FEN")
        assertEquals(listOf("FEN", "Hollow"), index.recentSearches())
        repeat(12) { index.addRecentSearch("query $it") }
        val recent = index.recentSearches()
        assertEquals(10, recent.size)
        assertEquals("query 11", recent.firstOrNull())
        assertEquals("query 2", recent.lastOrNull())
        index.clearRecentSearches()
        assertTrue(index.recentSearches().isEmpty())
    }

    // Pure helpers

    @Test
    fun helpersHandleOddInput() {
        assertEquals(listOf("emile", "fen"), searchWords(" Émile\tFEN\u0000 "))
        assertTrue(searchWords("x".repeat(201)).isEmpty())
        assertEquals("a\\%b\\_c\\\\", likeEscape("a%b_c\\"))
        assertEquals("k\u0001dorn", baseOfSongKey("k\u0001dorn\u0001d3"))
        assertEquals("plain", baseOfSongKey("plain"))
        assertEquals("Playlist", playlistName("\u0000 "))
        val emoji = "🎵"
        assertEquals(199, playlistName("a".repeat(199) + emoji).length)
    }
}
