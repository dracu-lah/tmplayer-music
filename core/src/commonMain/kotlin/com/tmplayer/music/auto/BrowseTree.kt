package com.tmplayer.music.auto

import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.LibraryIndex
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongCursor
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.library.TrackId

/**
 * The ids of the browse tree a car (Android Auto) or another media browser is shown. A folder id
 * starts with `tm:`; a song's id is the queue item's own (`chat:message:songKey`, the same as the
 * app's MediaIds), so what the car taps plays through the path the phone uses.
 */
object BrowseIds {
    const val ROOT = "tm:root"
    const val RECENT = "tm:recent"
    const val FAVOURITES = "tm:favourites"
    const val PLAYLISTS = "tm:playlists"
    const val ALBUMS = "tm:albums"
    const val ARTISTS = "tm:artists"
    const val CHATS = "tm:chats"

    /** The single non-playable line shown instead of the tree when there is nothing to browse. */
    const val INFO = "tm:info"

    fun playlist(id: Long) = "tm:playlist:$id"
    fun album(key: String) = "tm:album:$key"
    fun artist(key: String) = "tm:artist:$key"
    fun chat(id: Long) = "tm:chat:$id"

    /** A song's media id: the file that plays by default and the song's key. */
    fun song(track: TrackId, songKey: String) = "${track.chatId}:${track.messageId}:$songKey"

    /** Which folder [id] names, or null for a song, the info line or anything unknown. */
    fun parse(id: String): Folder? {
        if (!id.startsWith("tm:") || id.length > MAX_ID) return null
        return when (id) {
            ROOT -> Folder.Root
            RECENT -> Folder.Recent
            FAVOURITES -> Folder.Favourites
            PLAYLISTS -> Folder.Playlists
            ALBUMS -> Folder.Albums
            ARTISTS -> Folder.Artists
            CHATS -> Folder.Chats
            else -> {
                val parts = id.split(':', limit = 3)
                val value = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return null
                when (parts[1]) {
                    "playlist" -> value.toLongOrNull()?.let { Folder.Playlist(it) }
                    "album" -> Folder.AlbumOf(value)
                    "artist" -> Folder.ArtistOf(value)
                    "chat" -> value.toLongOrNull()?.let { Folder.ChatOf(it) }
                    else -> null
                }
            }
        }
    }

    private const val MAX_ID = 2_000
}

/** A folder of the tree. */
sealed interface Folder {
    data object Root : Folder
    data object Recent : Folder
    data object Favourites : Folder
    data object Playlists : Folder
    data object Albums : Folder
    data object Artists : Folder
    data object Chats : Folder
    data class Playlist(val id: Long) : Folder
    data class AlbumOf(val key: String) : Folder
    data class ArtistOf(val key: String) : Folder
    data class ChatOf(val id: Long) : Folder
}

enum class EntryKind { Folder, Song, Info }

/** What a row's cover is made from: the saved cover, Telegram's thumbnail of [track], or the generated one for [generatedKey]. */
data class BrowseArt(val coverKey: String, val thumbFileId: Int, val track: TrackId?, val generatedKey: String)

/**
 * One row of the tree. [durationMs] is null when the length is unknown, never 0. Songs carry the
 * track that plays by default and the song's key; [kind] tells folders, songs and the info line apart.
 */
data class BrowseEntry(
    val id: String,
    val kind: EntryKind,
    val title: String,
    val subtitle: String = "",
    val album: String? = null,
    val durationMs: Long? = null,
    val art: BrowseArt? = null,
    val track: TrackId? = null,
    val songKey: String = "",
)

/** The words the tree shows, from the app's own strings, so this part needs no resources. */
data class BrowseText(
    val recent: String,
    val favourites: String,
    val playlists: String,
    val albums: String,
    val artists: String,
    val chats: String,
    val signedOut: String,
    val empty: String,
    val unknownArtist: String,
    val songCount: (Int) -> String,
    val albumCount: (Int) -> String,
)

/**
 * The library as a browse tree for a car or another media app: Recently played, Favourites,
 * Playlists, Albums, Artists and the picked chats. Pure reads of the index, every list in pages of
 * the size the browser asked for, so a library of 100,000 tracks is never loaded whole.
 *
 * A car can only browse and play: nothing here starts a scan or a download, signs in or opens
 * settings. With nothing to browse (not signed in, or an empty library) the root is one info line.
 */
class BrowseTree(
    private val index: suspend () -> LibraryIndex?,
    private val text: BrowseText,
    private val signedIn: () -> Boolean,
) {

    /**
     * The rows [offset] up to [offset] + [size] of [folder]; empty past the end. [size] is held to
     * [MAX_PAGE] and [offset] to [MAX_OFFSET], so an absurd request costs what a big one does.
     */
    suspend fun children(folder: Folder, offset: Int, size: Int): List<BrowseEntry> {
        val from = offset.coerceIn(0, MAX_OFFSET)
        val n = size.coerceIn(1, MAX_PAGE)
        val library = index()
        if (folder == Folder.Root) return root(library).drop(from).take(n)
        library ?: return emptyList()
        return when (folder) {
            Folder.Root -> emptyList()
            Folder.Recent -> library.recentlyPlayed(from + n).drop(from).map(::song)
            Folder.Favourites -> library.favourites(from + n).drop(from).map(::song)
            Folder.Playlists -> library.playlists().drop(from).take(n).map { playlist ->
                BrowseEntry(
                    id = BrowseIds.playlist(playlist.id),
                    kind = EntryKind.Folder,
                    title = playlist.name,
                    subtitle = text.songCount(playlist.songCount),
                    art = BrowseArt(playlist.coverKey, playlist.thumbFileId, null, CoverArt.keyOfPlaylist(playlist.id)),
                )
            }
            Folder.Albums -> walk<Album>(from, n) { after, limit -> library.albums(after, limit) }.map { album(library, it) }
            Folder.Artists -> walk<Artist>(from, n) { after, limit -> library.artists(after, limit) }.map(::artist)
            Folder.Chats -> library.pickedChats().filter { it.picked && it.trackCount > 0 }.drop(from).take(n).map {
                BrowseEntry(
                    id = BrowseIds.chat(it.chatId),
                    kind = EntryKind.Folder,
                    title = it.title,
                    subtitle = text.songCount(it.trackCount),
                )
            }
            is Folder.Playlist -> library.playlistSongs(folder.id).drop(from).take(n).map(::song)
            is Folder.AlbumOf -> library.albumSongs(folder.key, from + n).drop(from).map(::song)
            is Folder.ArtistOf -> {
                // The artist's albums first, then every song of the artist.
                val albums = library.artistAlbums(folder.key, MAX_ARTIST_ALBUMS)
                val rows = ArrayList<BrowseEntry>()
                if (from < albums.size) albums.drop(from).take(n).mapTo(rows) { album(library, it) }
                val room = n - rows.size
                if (room > 0) {
                    val songFrom = (from - albums.size).coerceAtLeast(0)
                    library.artistSongs(folder.key, songFrom + room).drop(songFrom).take(room).mapTo(rows) { song(it) }
                }
                rows
            }
            is Folder.ChatOf -> walk<Song>(from, n) { after, limit ->
                library.chatSongs(folder.id, after?.let { SongCursor.of(it, SongSort.Title) }, limit)
            }.map(::song)
        }
    }

    /** One row by its id (a folder or a song), for a browser that asks for an item on its own; null when unknown or gone. */
    suspend fun entry(id: String): BrowseEntry? {
        if (id == BrowseIds.INFO) return info(index())
        val folder = BrowseIds.parse(id)
        if (folder != null) return folderEntry(folder)
        val key = songKeyOf(id) ?: return null
        return index()?.song(key)?.let(::song)
    }

    /** Songs, then albums, then artists matching [query] with the library's own search; empty for a blank query. */
    suspend fun search(query: String, offset: Int, size: Int): List<BrowseEntry> {
        val library = index() ?: return emptyList()
        val from = offset.coerceIn(0, MAX_SEARCH)
        val n = size.coerceIn(1, MAX_PAGE)
        val found = library.search(query.trim(), (from + n).coerceAtMost(MAX_SEARCH))
        val rows = ArrayList<BrowseEntry>(found.songs.size + found.albums.size + found.artists.size)
        found.songs.mapTo(rows, ::song)
        found.albums.mapTo(rows) { album(library, it) }
        found.artists.mapTo(rows, ::artist)
        return rows.drop(from).take(n)
    }

    /** The song key in a song's media id, or null when [id] is not one. */
    private fun songKeyOf(id: String): String? {
        val parts = id.split(':', limit = 3)
        if (parts.size < 3 || parts[0].toLongOrNull() == null || parts[1].toLongOrNull() == null) return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    private suspend fun root(library: LibraryIndex?): List<BrowseEntry> {
        val info = info(library)
        if (info != null) return listOf(info)
        return listOf(
            folder(BrowseIds.RECENT, text.recent),
            folder(BrowseIds.FAVOURITES, text.favourites),
            folder(BrowseIds.PLAYLISTS, text.playlists),
            folder(BrowseIds.ALBUMS, text.albums),
            folder(BrowseIds.ARTISTS, text.artists),
            folder(BrowseIds.CHATS, text.chats),
        )
    }

    /** The one info line when there is nothing to browse, else null. A failing index counts as nothing. */
    private suspend fun info(library: LibraryIndex?): BrowseEntry? {
        val songs = if (library == null) 0 else runCatching { library.songCount() }.getOrDefault(0)
        if (songs > 0) return null
        val say = if (signedIn()) text.empty else text.signedOut
        return BrowseEntry(BrowseIds.INFO, EntryKind.Info, say)
    }

    private fun folderEntry(folder: Folder): BrowseEntry? = when (folder) {
        Folder.Root -> null
        Folder.Recent -> folder(BrowseIds.RECENT, text.recent)
        Folder.Favourites -> folder(BrowseIds.FAVOURITES, text.favourites)
        Folder.Playlists -> folder(BrowseIds.PLAYLISTS, text.playlists)
        Folder.Albums -> folder(BrowseIds.ALBUMS, text.albums)
        Folder.Artists -> folder(BrowseIds.ARTISTS, text.artists)
        Folder.Chats -> folder(BrowseIds.CHATS, text.chats)
        // A deeper folder is only known once its parent listed it: the browser holds that row already.
        else -> null
    }

    private fun folder(id: String, title: String) = BrowseEntry(id, EntryKind.Folder, title)

    private fun song(song: Song): BrowseEntry = BrowseEntry(
        id = BrowseIds.song(song.defaultTrack, song.groupKey),
        kind = EntryKind.Song,
        title = song.title,
        subtitle = ArtistDisplay.of(song.artist).ifBlank { text.unknownArtist },
        album = song.album.ifBlank { null },
        // An unknown length stays unknown, never 0.
        durationMs = song.durationMs.takeIf { it > 0 },
        art = BrowseArt(song.coverKey, song.thumbFileId, song.defaultTrack, CoverArt.keyOf(song.albumKey, song.groupKey)),
        track = song.defaultTrack,
        songKey = song.groupKey,
    )

    private suspend fun album(library: LibraryIndex, album: Album): BrowseEntry {
        // Telegram's thumbnail is looked up by message (a saved file id belongs to the session that scanned it).
        val thumb = if (album.coverKey.isBlank() && album.thumbFileId != 0) {
            runCatching { library.albumThumbTrack(album.key) }.getOrNull()
        } else {
            null
        }
        return BrowseEntry(
            id = BrowseIds.album(album.key),
            kind = EntryKind.Folder,
            title = album.title,
            subtitle = ArtistDisplay.of(album.artist).ifBlank { text.unknownArtist },
            art = BrowseArt(album.coverKey, if (thumb != null) album.thumbFileId else 0, thumb, CoverArt.keyOfAlbum(album.key)),
        )
    }

    private fun artist(artist: Artist): BrowseEntry = BrowseEntry(
        id = BrowseIds.artist(artist.key),
        kind = EntryKind.Folder,
        title = artist.name,
        subtitle = text.songCount(artist.songCount),
        art = BrowseArt("", 0, null, "r:" + artist.key),
    )

    companion object {
        /** The most rows one page may hold, whatever the browser asks for. */
        const val MAX_PAGE = 200

        /** The deepest row a page may start at (the index keeps far fewer plays, favourites and search hits). */
        const val MAX_OFFSET = 200_000

        private const val MAX_SEARCH = 200
        private const val MAX_ARTIST_ALBUMS = 200
        private const val WALK_BATCH = 500

        /**
         * Rows [offset] up to [offset] + [size] of a keyset paged list: walks the pages before [offset]
         * (each a cheap indexed read, [WALK_BATCH] rows) and then reads the page itself. Empty past the end.
         */
        internal suspend fun <T : Any> walk(offset: Int, size: Int, fetch: suspend (after: T?, limit: Int) -> List<T>): List<T> {
            var after: T? = null
            var skipped = 0
            while (skipped < offset) {
                val want = minOf(WALK_BATCH, offset - skipped)
                val batch = fetch(after, want)
                if (batch.size < want) return emptyList()
                skipped += batch.size
                after = batch.last()
            }
            return fetch(after, size)
        }
    }
}
