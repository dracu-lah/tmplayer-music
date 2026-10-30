package com.tmplayer.music.demo

import com.tmplayer.music.data.Library
import com.tmplayer.music.library.ScanCursor
import com.tmplayer.music.library.ScanState
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.library.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fills the demo's own index from [DemoCatalog] the way a finished scan and a few days of listening
 * would: every chat scanned to its end, every file's tags read (so no banner and no reader runs),
 * favourites, playlists, plays and a few tracks left part way for Speed dial. Debug builds only.
 */
internal object DemoLibrary {

    suspend fun fill(covers: File) {
        withContext(Dispatchers.Default) {
            DemoCatalog.albums.forEach { DemoCovers.ensure(covers, it.coverKey, it.style, it.palette) }
        }
        val index = Library.index()
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000
        index.setPicked(DemoCatalog.chats)
        val tracks = DemoCatalog.tracks(now)
        index.upsert(tracks)
        for (chat in DemoCatalog.chats) {
            val newest = tracks.filter { it.chatId == chat.chatId }.maxOfOrNull { it.messageId } ?: 0L
            val done = ScanCursor(fromMessageId = 0, done = true, newestMessageId = newest)
            index.saveScanState(chat.chatId, ScanState(audio = done, documents = done, lastScanAt = now))
        }
        val songs = index.songs(SongSort.Title, null, 1_000)
        fun song(title: String): Song? = songs.firstOrNull { it.title == title && it.variant.isEmpty() }

        DemoCatalog.favourites.forEach { title -> song(title)?.let { index.setFavourite(it, true) } }
        DemoCatalog.playlists.forEach { (name, titles) ->
            val id = index.createPlaylist(name)
            index.addToPlaylist(id, titles.mapNotNull(::song))
        }
        // Oldest first, so the newest play is the last one written.
        DemoCatalog.played.withIndex().reversed().forEach { (i, title) ->
            song(title)?.let { index.recordPlay(it.defaultTrack, it.groupKey, now - 600 - i * 2_700L) }
        }
        DemoCatalog.leftPartWay.withIndex().reversed().forEach { (i, entry) ->
            val (title, share) = entry
            val s = song(title) ?: return@forEach
            index.saveResume(s.defaultTrack, s.groupKey, (s.durationMs * share).toLong(), s.durationMs, nowMs - 300_000 - i * 3_600_000L)
        }
    }

    /** The page [options] opens over its tab, or null for a tab on its own. */
    suspend fun pageFor(options: DemoMode.Options): Page? {
        val index = Library.index()
        return when (options.screen) {
            DemoMode.Screen.Settings -> Page.Settings
            DemoMode.Screen.Album -> {
                val albums = index.albums(null, 100)
                (albums.firstOrNull { it.title.equals(options.album, ignoreCase = true) } ?: albums.firstOrNull())
                    ?.let { Page.AlbumPage(it) }
            }
            DemoMode.Screen.Artist -> {
                val artists = index.artists(null, 100)
                (artists.firstOrNull { it.name == "Kite Harbour" } ?: artists.firstOrNull())?.let { Page.ArtistPage(it) }
            }
            DemoMode.Screen.Playlist -> index.playlists().lastOrNull()?.let { Page.PlaylistPage(it) }
            else -> null
        }
    }

    /** The queue the demo opens with, at its track and [positionMs] in it; paused when [playing] is false. Main thread. */
    suspend fun startQueue(playing: Boolean, positionMs: Long = DemoCatalog.NOW_POSITION_MS) {
        val index = Library.index()
        val album = index.albums(null, 100).firstOrNull { it.title == DemoCatalog.NOW_ALBUM } ?: return
        val songs = index.albumSongs(album.key)
        if (songs.isEmpty()) return
        PlayerConnection.play(songs, DemoCatalog.NOW_TRACK.coerceIn(0, songs.lastIndex), positionMs = positionMs)
        if (!playing) PlayerConnection.pause()
    }
}
