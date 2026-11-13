package com.tmplayer.music.auto

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.LibraryIndex
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.library.SyntheticAccount
import com.tmplayer.music.library.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browse tree a car sees: paging, ids, unknown lengths, the empty and signed out lines, search. */
class BrowseTreeTest {

    private val opened = ArrayList<LibraryIndex>()
    private var signedIn = true

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
    }

    private val text = BrowseText(
        recent = "Recently played", favourites = "Favourites", playlists = "Playlists", albums = "Albums",
        artists = "Artists", chats = "Chats", signedOut = "Sign in on the phone", empty = "Add chats on the phone",
        unknownArtist = "Unknown artist", songCount = { "$it songs" }, albumCount = { "$it albums" },
    )

    private fun open(): LibraryIndex = LibraryIndex.open(":memory:").also { opened += it }

    private fun tree(index: LibraryIndex?) = BrowseTree({ index }, text, { signedIn })

    private fun track(chat: Long, message: Long, title: String, artist: String = "Korvane", album: String = "", durationMs: Long = 200_000) = Track(
        chatId = chat, messageId = message, fileId = (chat * 1000 + message).toInt(), uniqueId = "u$chat-$message",
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = 1_000_000, dateSec = 1_700_000_000 + message, title = title,
        artist = artist, album = album, albumArtist = if (album.isEmpty()) "" else artist, durationMs = durationMs,
        format = AudioFormat.MP3, groupKey = SyntheticAccount.groupKeyOf(artist, title),
    )

    private fun filled(songs: Int = 250): LibraryIndex = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "Road mix"), ChatPick(2, "Empty chat")))
        index.upsert(
            (1..songs).map { i ->
                track(1, i.toLong(), "Song %04d".format(i), artist = "Artist ${i % 5}", album = if (i % 2 == 0) "Album ${i % 10}" else "")
            },
        )
        index
    }

    @Test
    fun rootListsTheSixSectionsWhenThereIsMusic() = runBlocking {
        val rows = tree(filled(5)).children(Folder.Root, 0, 50)
        assertEquals(listOf("Recently played", "Favourites", "Playlists", "Albums", "Artists", "Chats"), rows.map { it.title })
        assertTrue(rows.all { it.kind == EntryKind.Folder })
    }

    @Test
    fun anEmptyLibraryIsOneInfoLineSayingWhatToDoOnThePhone() = runBlocking {
        val index = open()
        val signedInRows = tree(index).children(Folder.Root, 0, 50)
        assertEquals(1, signedInRows.size)
        assertEquals(EntryKind.Info, signedInRows[0].kind)
        assertEquals("Add chats on the phone", signedInRows[0].title)
        signedIn = false
        assertEquals("Sign in on the phone", tree(index).children(Folder.Root, 0, 50).single().title)
        // No index at all (nothing started) is the same line, not a crash.
        assertEquals(EntryKind.Info, tree(null).children(Folder.Root, 0, 50).single().kind)
    }

    @Test
    fun songsComePagedAndPagesDoNotOverlap() = runBlocking {
        val tree = tree(filled())
        val chat = Folder.ChatOf(1)
        val first = tree.children(chat, 0, 100)
        val second = tree.children(chat, 100, 100)
        val third = tree.children(chat, 200, 100)
        assertEquals(100, first.size)
        assertEquals(100, second.size)
        assertEquals(50, third.size)
        assertEquals(250, (first + second + third).map { it.id }.toSet().size)
        assertEquals(emptyList(), tree.children(chat, 250, 100))
        assertEquals("Song 0001", first.first().title)
        assertEquals("Song 0101", second.first().title)
    }

    @Test
    fun albumsAndArtistsPageByKeyset() = runBlocking {
        val tree = tree(filled())
        val albums = tree.children(Folder.Albums, 0, 3) + tree.children(Folder.Albums, 3, 3) + tree.children(Folder.Albums, 6, 3)
        assertEquals(5, albums.size)
        assertEquals(albums.size, albums.map { it.id }.toSet().size)
        assertTrue(albums.all { it.id.startsWith("tm:album:") })
        val artists = tree.children(Folder.Artists, 0, 2) + tree.children(Folder.Artists, 2, 2) + tree.children(Folder.Artists, 4, 2)
        assertEquals(5, artists.size)
        assertEquals(emptyList(), tree.children(Folder.Artists, 5, 2))
    }

    @Test
    fun anArtistShowsItsAlbumsThenItsSongsAcrossPages() = runBlocking {
        val index = filled()
        val artist = index.artists(null, 10).first { it.name == "Artist 2" }
        val tree = tree(index)
        val folder = Folder.ArtistOf(artist.key)
        val all = (0 until 100 step 10).flatMap { tree.children(folder, it, 10) }
        val albums = all.count { it.kind == EntryKind.Folder }
        val songs = all.filter { it.kind == EntryKind.Song }
        assertTrue(albums > 0)
        assertEquals(artist.songCount, songs.size)
        // Folders first, songs after.
        assertTrue(all.take(albums).all { it.kind == EntryKind.Folder })
        assertEquals(songs.size, songs.map { it.id }.toSet().size)
    }

    @Test
    fun anAlbumListsItsSongsInOrder() = runBlocking {
        val index = filled()
        val album = index.albums(null, 50).first()
        val rows = tree(index).children(Folder.AlbumOf(album.key), 0, 100)
        assertEquals(album.songCount, rows.size)
        assertTrue(rows.all { it.kind == EntryKind.Song })
    }

    @Test
    fun anUnknownLengthStaysUnknownAndNeverZero() = runBlocking {
        val index = open()
        index.setPicked(listOf(ChatPick(1, "Chat")))
        index.upsert(listOf(track(1, 1, "No length", durationMs = 0), track(1, 2, "Has length", durationMs = 61_000)))
        val rows = tree(index).children(Folder.ChatOf(1), 0, 10)
        assertNull(rows.first { it.title == "No length" }.durationMs)
        assertEquals(61_000L, rows.first { it.title == "Has length" }.durationMs)
    }

    @Test
    fun aSongsIdIsTheQueueItemId() = runBlocking {
        val index = filled(3)
        val song = index.songs(SongSort.Title, null, 1).single()
        val row = tree(index).children(Folder.ChatOf(1), 0, 1).single()
        assertEquals("${song.defaultTrack.chatId}:${song.defaultTrack.messageId}:${song.groupKey}", row.id)
        assertEquals(row, tree(index).entry(row.id))
    }

    @Test
    fun playlistsFavouritesAndRecentlyPlayedShowTheirSongs() = runBlocking {
        val index = filled(10)
        val songs = index.songs(SongSort.Title, null, 10)
        val id = index.createPlaylist("Drive")
        index.addToPlaylist(id, songs.take(4))
        index.setFavourite(songs[0], true)
        index.recordPlay(songs[1].defaultTrack, songs[1].groupKey, 1_700_000_100)
        val tree = tree(index)
        val playlists = tree.children(Folder.Playlists, 0, 10)
        assertEquals("Drive", playlists.single().title)
        assertEquals(4, tree.children(BrowseIds.parse(playlists.single().id)!!, 0, 10).size)
        assertEquals(1, tree.children(Folder.Favourites, 0, 10).size)
        assertEquals(songs[1].title, tree.children(Folder.Recent, 0, 10).single().title)
    }

    @Test
    fun chatsWithNothingInThemAreLeftOut() = runBlocking {
        val rows = tree(filled(3)).children(Folder.Chats, 0, 10)
        assertEquals(listOf("Road mix"), rows.map { it.title })
    }

    @Test
    fun searchFindsSongsAndIsEmptyForABlankQuery() = runBlocking {
        val tree = tree(filled(30))
        assertTrue(tree.search("Song 0007", 0, 10).any { it.title == "Song 0007" && it.kind == EntryKind.Song })
        assertEquals(emptyList(), tree.search("   ", 0, 10))
        assertEquals(emptyList(), tree.search("x".repeat(500), 0, 10))
    }

    @Test
    fun absurdPagesAreHeldInRange() = runBlocking {
        val tree = tree(filled(20))
        assertEquals(emptyList(), tree.children(Folder.ChatOf(1), Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(20, tree.children(Folder.ChatOf(1), -5, 100_000).size)
        assertEquals(1, tree.children(Folder.ChatOf(1), 0, 0).size)
    }

    @Test
    fun idsParseAndGarbageDoesNot() {
        assertEquals(Folder.Playlist(7), BrowseIds.parse(BrowseIds.playlist(7)))
        assertEquals(Folder.AlbumOf("a:b:c"), BrowseIds.parse(BrowseIds.album("a:b:c")))
        assertEquals(Folder.ChatOf(-100123), BrowseIds.parse(BrowseIds.chat(-100123)))
        for (bad in listOf("", "tm:", "tm:playlist:x", "tm:playlist:", "tm:nope:1", "12:34:key", "tm:" + "x".repeat(5_000))) {
            assertNull(BrowseIds.parse(bad), bad)
        }
    }
}
