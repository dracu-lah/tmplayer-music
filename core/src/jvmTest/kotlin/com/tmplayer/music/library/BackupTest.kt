package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The backup file (reading and writing it) and restoring it into a library: merge, replace, pending entries and no partial change. */
class BackupTest {

    private val dir: File = Files.createTempDirectory("backup-test").toFile()
    private val opened = ArrayList<LibraryIndex>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(): LibraryIndex = LibraryIndex.open(":memory:").also { opened += it }

    private fun track(chatId: Long, messageId: Long, title: String, artist: String = "Korvane") = Track(
        chatId = chatId, messageId = messageId, fileId = (chatId * 1000 + messageId).toInt(), uniqueId = "u$chatId-$messageId",
        fileName = "$title.mp3", mime = "audio/mpeg", sizeBytes = 8_000_000, dateSec = 1_700_000_000 + messageId, title = title,
        artist = artist, durationMs = 200_000, format = AudioFormat.MP3, groupKey = GroupKey.of(title, artist, 200_000),
    )

    private suspend fun LibraryIndex.library(chat: Long, vararg titles: String) {
        setPicked(listOf(ChatPick(chat, "Chat $chat")))
        upsert(titles.mapIndexed { i, t -> track(chat, 10L + i, t) })
    }

    private suspend fun LibraryIndex.named(title: String): Song = songs(SongSort.Title, null, 100).first { it.title == title }

    private fun decoded(text: String): Backup.Data = assertIs<Backup.Result.Ok>(Backup.decode(text)).data

    @Test
    fun `a backup survives writing and reading`() = runBlocking {
        val index = open()
        index.library(1, "Velo Marin", "Lumen Tide", "Orin Gale")
        index.setFavourite(index.named("Velo Marin"), true)
        index.setFavourite(index.named("Orin Gale"), true)
        val id = index.createPlaylist("Evening é中")
        index.addToPlaylist(id, listOf(index.named("Orin Gale"), index.named("Velo Marin"), index.named("Lumen Tide")))
        index.recordPlay(TrackId(1, 10), index.named("Velo Marin").groupKey, 1_700_000_100)
        index.recordPlay(TrackId(1, 10), index.named("Velo Marin").groupKey, 1_700_000_200)
        val data = index.backupData()
        assertEquals(2, data.favourites.size)
        assertEquals(listOf("Orin Gale", "Velo Marin", "Lumen Tide"), data.playlists.single().items.map { it.title })
        assertEquals(2, data.plays.single().count)
        assertEquals(1_700_000_200L, data.plays.single().lastSec)
        assertEquals(listOf(1L), data.chats.map { it.chatId })
        assertEquals(data, decoded(Backup.encode(data)))
    }

    @Test
    fun `restoring into an empty library brings back favourites, playlist order and plays`() = runBlocking {
        val source = open()
        source.library(1, "Velo Marin", "Lumen Tide", "Orin Gale")
        source.setFavourite(source.named("Lumen Tide"), true)
        val id = source.createPlaylist("Mix")
        source.addToPlaylist(id, listOf(source.named("Orin Gale"), source.named("Velo Marin")))
        source.recordPlay(TrackId(1, 11), source.named("Lumen Tide").groupKey, 1_700_000_300)
        val data = decoded(Backup.encode(source.backupData()))

        val target = open()
        target.library(1, "Velo Marin", "Lumen Tide", "Orin Gale")
        val summary = target.restoreBackup(data, replace = false, commit = true)
        assertEquals(1, summary.favouritesAdded)
        assertEquals(1, summary.playlistsCreated)
        assertEquals(2, summary.playlistItemsAdded)
        assertEquals(1, summary.playedSongs)
        assertEquals(0, summary.pending)
        assertEquals(listOf("Lumen Tide"), target.favourites(10).map { it.title })
        assertEquals(listOf("Orin Gale", "Velo Marin"), target.playlistSongs(target.playlists().single().id).map { it.title })
        assertEquals(listOf("Lumen Tide"), target.recentlyPlayed(10).map { it.title })
    }

    @Test
    fun `restoring twice changes nothing the second time`() = runBlocking {
        val source = open()
        source.library(1, "Velo Marin", "Lumen Tide")
        source.setFavourite(source.named("Velo Marin"), true)
        val id = source.createPlaylist("Mix")
        source.addToPlaylist(id, listOf(source.named("Velo Marin"), source.named("Lumen Tide")))
        source.recordPlay(TrackId(1, 10), source.named("Velo Marin").groupKey, 1_700_000_300)
        source.recordPlay(TrackId(1, 10), source.named("Velo Marin").groupKey, 1_700_000_400)
        val data = source.backupData()
        val target = open()
        target.library(1, "Velo Marin", "Lumen Tide")
        target.restoreBackup(data, replace = false, commit = true)
        val again = target.restoreBackup(data, replace = false, commit = true)
        assertTrue(again.changesNothing, again.toString())
        assertEquals(1, target.playlists().size)
        assertEquals(2, target.backupData().plays.single().count)
        // Restoring into the library it came from is a no-op too.
        assertTrue(source.restoreBackup(data, replace = false, commit = true).changesNothing)
    }

    @Test
    fun `merge keeps what is there and joins a playlist of the same name`() = runBlocking {
        val index = open()
        index.library(1, "Velo Marin", "Lumen Tide", "Orin Gale")
        index.setFavourite(index.named("Orin Gale"), true)
        val id = index.createPlaylist("MIX ")
        index.addToPlaylist(id, listOf(index.named("Orin Gale")))
        val data = Backup.Data(
            1_700_000_000,
            favourites = listOf(Backup.Favourite(index.refOf("Velo Marin"), 5), Backup.Favourite(index.refOf("Orin Gale"), 6)),
            playlists = listOf(Backup.PlaylistData("mix", 1, 2, listOf(index.refOf("Orin Gale"), index.refOf("Velo Marin")))),
        )
        val summary = index.restoreBackup(data, replace = false, commit = true)
        assertEquals(1, summary.favouritesAdded)
        assertEquals(1, summary.favouritesKnown)
        assertEquals(0, summary.playlistsCreated)
        assertEquals(1, summary.playlistsMerged)
        assertEquals(1, summary.playlistItemsAdded)
        assertEquals(listOf("Orin Gale", "Velo Marin"), index.playlistSongs(id).map { it.title })
        assertEquals(1, index.playlists().size)
    }

    private suspend fun LibraryIndex.refOf(title: String): Backup.Ref {
        val song = named(title)
        return Backup.Ref(song.defaultTrack.chatId, song.defaultTrack.messageId, song.groupKey, song.title, song.artist)
    }

    @Test
    fun `replace clears what is kept and says how much`() = runBlocking {
        val index = open()
        index.library(1, "Velo Marin", "Lumen Tide")
        index.setFavourite(index.named("Lumen Tide"), true)
        index.addToPlaylist(index.createPlaylist("Old"), listOf(index.named("Lumen Tide")))
        index.recordPlay(TrackId(1, 11), index.named("Lumen Tide").groupKey, 1_700_000_300)
        val data = Backup.Data(1, playlists = listOf(Backup.PlaylistData("New", 1, 1, listOf(index.refOf("Velo Marin")))))

        val preview = index.restoreBackup(data, replace = true, commit = false)
        assertEquals(1, preview.removedFavourites)
        assertEquals(1, preview.removedPlaylists)
        assertEquals(1, preview.removedPlays)
        assertEquals(listOf("Old"), index.playlists().map { it.name }, "a preview changes nothing")
        assertEquals(1, index.favourites(10).size)

        index.restoreBackup(data, replace = true, commit = true)
        assertEquals(listOf("New"), index.playlists().map { it.name })
        assertTrue(index.favourites(10).isEmpty())
        assertTrue(index.recentlyPlayed(10).isEmpty())
    }

    @Test
    fun `entries for songs not in the library wait by key and attach when the chat is indexed`() = runBlocking {
        val source = open()
        source.library(7, "Velo Marin", "Lumen Tide")
        source.setFavourite(source.named("Velo Marin"), true)
        source.addToPlaylist(source.createPlaylist("Mix"), listOf(source.named("Lumen Tide"), source.named("Velo Marin")))
        val data = source.backupData()

        val target = open()
        val summary = target.restoreBackup(data, replace = false, commit = true)
        assertEquals(3, summary.pending)
        assertEquals(listOf(7L), summary.newChats.map { it.chatId })
        assertTrue(target.favourites(10).isEmpty(), "hidden until the chat is indexed")
        assertEquals(0, target.playlists().single().songCount)

        target.library(7, "Velo Marin", "Lumen Tide")
        assertEquals(listOf("Velo Marin"), target.favourites(10).map { it.title })
        assertEquals(listOf("Lumen Tide", "Velo Marin"), target.playlistSongs(target.playlists().single().id).map { it.title })

        // Another account holds the same songs in other chats and messages: the key still finds them.
        val other = open()
        other.restoreBackup(data, replace = false, commit = true)
        other.library(99, "Lumen Tide", "Velo Marin")
        assertEquals(listOf("Velo Marin"), other.favourites(10).map { it.title })
    }

    @Test
    fun `plays past the cap are trimmed to the newest and a preview never writes`() = runBlocking {
        val index = open()
        index.library(1, "Velo Marin", "Lumen Tide")
        repeat(10) { index.recordPlay(TrackId(1, 10), index.named("Velo Marin").groupKey, 1_700_000_000L + it) }
        val data = Backup.Data(1, plays = listOf(Backup.PlayData(index.refOf("Lumen Tide"), LibraryIndex.MAX_PLAYS, 1_700_000_999)))
        val preview = index.restoreBackup(data, replace = false, commit = false)
        assertEquals(1, preview.playedSongs)
        assertEquals(10, index.backupData().plays.sumOf { it.count })
        index.restoreBackup(data, replace = false, commit = true)
        assertEquals(LibraryIndex.MAX_PLAYS, index.backupData().plays.sumOf { it.count })
    }

    @Test
    fun `plays past the cap trim the oldest by time, not the user's own lowest ids`() = runBlocking {
        val index = open()
        index.library(1, "Velo Marin", "Lumen Tide")
        repeat(10) { index.recordPlay(TrackId(1, 10), index.named("Velo Marin").groupKey, 1_700_000_000L + it) }
        val data = Backup.Data(1, plays = listOf(Backup.PlayData(index.refOf("Lumen Tide"), LibraryIndex.MAX_PLAYS, 1_000)))
        index.restoreBackup(data, replace = false, commit = true)
        val plays = index.backupData().plays
        assertEquals(LibraryIndex.MAX_PLAYS, plays.sumOf { it.count })
        assertEquals(listOf(10, LibraryIndex.MAX_PLAYS - 10), plays.map { it.count }.sorted())
    }

    @Test
    fun `a file from a newer version is refused with its number`() {
        val text = "{\"format\":\"${Backup.FORMAT}\",\"version\":${Backup.VERSION + 1},\"favourites\":\"anything\"}"
        assertEquals(Backup.Result.Newer(Backup.VERSION + 1L), Backup.decode(text))
    }

    @Test
    fun `a bad file is refused whole, with the reason`() {
        fun problem(text: String?) = assertIs<Backup.Result.Bad>(Backup.decode(text)).problem
        assertEquals(Backup.Problem.NotJson, problem(""))
        assertEquals(Backup.Problem.NotJson, problem("{\"format\": "))
        assertEquals(Backup.Problem.NotBackup, problem("[1,2]"))
        assertEquals(Backup.Problem.NotBackup, problem("{\"format\":\"other\",\"version\":1}"))
        assertEquals(Backup.Problem.Damaged, problem("{\"format\":\"${Backup.FORMAT}\"}"))
        assertEquals(Backup.Problem.Damaged, problem("{\"format\":\"${Backup.FORMAT}\",\"version\":\"1\"}"))
        assertEquals(Backup.Problem.Damaged, problem("{\"format\":\"${Backup.FORMAT}\",\"version\":0}"))
        val head = "{\"format\":\"${Backup.FORMAT}\",\"version\":1,"
        assertEquals(Backup.Problem.Damaged, problem(head + "\"favourites\":{}}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"favourites\":[{\"chat\":\"1\",\"message\":2,\"key\":\"k\",\"addedSec\":1}]}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"favourites\":[{\"chat\":1,\"message\":2,\"key\":\"\",\"addedSec\":1}]}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"favourites\":[{\"chat\":1,\"message\":2,\"key\":\"k\",\"addedSec\":-1}]}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"favourites\":[{\"chat\":1,\"message\":2,\"key\":\"k\",\"addedSec\":1},5]}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"plays\":[{\"chat\":1,\"message\":2,\"key\":\"k\",\"count\":0,\"lastSec\":1}]}"))
        assertEquals(Backup.Problem.Damaged, problem(head + "\"plays\":[{\"chat\":1,\"message\":2,\"key\":\"k\",\"count\":99999,\"lastSec\":1}]}"))
        val three = "{\"chat\":1,\"message\":2,\"key\":\"k\",\"count\":3000,\"lastSec\":1}"
        assertEquals(Backup.Problem.TooLarge, problem(head + "\"plays\":[$three,$three]}"))
        assertEquals(Backup.Problem.NotJson, problem("[".repeat(10_000)))
    }

    @Test
    fun `unknown extra fields and missing sections are fine`() {
        val data = decoded("{\"format\":\"${Backup.FORMAT}\",\"version\":1,\"somethingNew\":[1,2],\"chats\":[{\"id\":-100,\"title\":\"A\",\"extra\":true}]}")
        assertEquals(listOf(Backup.ChatData(-100, "A")), data.chats)
        assertTrue(data.favourites.isEmpty() && data.playlists.isEmpty() && data.plays.isEmpty())
    }
}
