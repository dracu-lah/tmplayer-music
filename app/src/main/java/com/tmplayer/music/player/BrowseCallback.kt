package com.tmplayer.music.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.tmplayer.music.auto.BrowseEntry
import com.tmplayer.music.auto.BrowseIds
import com.tmplayer.music.auto.BrowseText
import com.tmplayer.music.auto.BrowseTree
import com.tmplayer.music.auto.BrowserAccess
import com.tmplayer.music.auto.EntryKind
import com.tmplayer.music.auto.Folder
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the media session offers a browser (Android Auto, the assistant, a car display) and how it
 * turns what the browser picks into a queue. The tree itself is [BrowseTree]; this is the Media3
 * side of it: pages by the size the browser asks for, a cover on each row ([BrowseArtwork]),
 * search by voice or text, and who may browse at all ([BrowserAccess]).
 *
 * A browser can only look and play. There is no custom command, nothing starts a scan, a download,
 * a sign in or the settings, and a controller other than the app's own cannot hand the player a
 * URI: only ids the index knows. Playback resumption is refused on purpose, so a car or headset
 * connecting (or a media button with no session) never starts music: nothing autoplays.
 */
@OptIn(UnstableApi::class)
open class BrowseCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val resolve: suspend (com.tmplayer.music.player.SavedQueue.Item) -> MediaItem?,
) : MediaLibrarySession.Callback {

    /** The last page each browser was given, so a tap on one of its songs queues the songs after it. */
    private class Shown(val folder: Folder, val offset: Int, val ids: List<String>)

    private val shown = object : LinkedHashMap<String, Shown>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shown>?) = size > MAX_BROWSERS
    }

    private fun tree() = BrowseTree(
        index = { runCatching { Library.index() }.getOrNull() },
        text = BrowseText(
            recent = L.homeRecentlyPlayed,
            favourites = L.homeFavourites,
            playlists = L.tabPlaylists,
            albums = L.tabAlbums,
            artists = L.tabArtists,
            chats = L.tabChats,
            signedOut = L.autoSignedOut,
            empty = L.autoEmpty,
            unknownArtist = L.libraryUnknownArtist,
            songCount = { L.librarySongs(it) },
            albumCount = { L.libraryAlbums(it) },
        ),
        signedIn = { Td.auth.value is AuthState.Ready },
    )

    private fun may(controller: MediaSession.ControllerInfo): Boolean =
        BrowserAccess.allows(controller.packageName, context.packageName, controller.isTrusted)

    private fun <T : Any> denied(): ListenableFuture<LibraryResult<T>> =
        Futures.immediateFuture(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))

    /** Runs [block] on the service scope; a failure is an error result, never a crash, and a cancelled call cancels the work. */
    private fun <T : Any> result(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        val job = scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "a browse request failed", e)
                future.set(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!may(browser)) return denied()
        // No "recent" or "suggested" root: nothing is picked for the user (no recommendations).
        if (params?.isRecent == true || params?.isSuggested == true) {
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        val root = rootItem()
        // Android Auto's content style hints: folders as a list, songs as a list.
        val extras = Bundle().apply {
            putBoolean(STYLE_SUPPORTED, true)
            putInt(STYLE_BROWSABLE, STYLE_LIST)
            putInt(STYLE_PLAYABLE, STYLE_LIST)
        }
        return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!may(browser)) return denied()
        return result {
            if (mediaId == BrowseIds.ROOT) return@result LibraryResult.ofItem(rootItem(), null)
            val entry = tree().entry(mediaId) ?: return@result LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItem(item(entry, withArt = true), null)
        }
    }

    private fun rootItem() = MediaItem.Builder().setMediaId(BrowseIds.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(L.appName).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!may(browser)) return denied()
        val folder = BrowseIds.parse(parentId) ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        return result {
            val offset = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val rows = tree().children(folder, offset, pageSize)
            synchronized(shown) { shown[browser.packageName] = Shown(folder, offset, rows.map { it.id }) }
            LibraryResult.ofItemList(items(rows), params)
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!may(browser)) return denied()
        return Futures.immediateFuture(
            if (BrowseIds.parse(parentId) != null) LibraryResult.ofVoid() else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
        )
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!may(browser)) return denied()
        // The browser is told when the results are ready, then asks for them page by page.
        scope.launch {
            val count = runCatching { tree().search(query, 0, BrowseTree.MAX_PAGE).size }.getOrDefault(0)
            runCatching { session.notifySearchResultChanged(browser, query, count, params) }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!may(browser)) return denied()
        return result {
            val offset = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val rows = tree().search(query, offset, pageSize)
            LibraryResult.ofItemList(items(rows), params)
        }
    }

    // What the browser picked becomes a queue.

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { expand(controller, mediaItems) }.toMutableList() }
                .onSuccess { future.set(it) }
                .onFailure { future.setException(it) }
        }
        return future
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val siblings = if (mediaItems.size == 1) queueAfter(controller, mediaItems[0]) else null
                    if (siblings != null) {
                        MediaSession.MediaItemsWithStartPosition(siblings, 0, startPositionMs)
                    } else {
                        val items = expand(controller, mediaItems)
                        // The browser's own index only means something while the list is as it sent it.
                        val same = items.size == mediaItems.size
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            if (same && startIndex in items.indices) startIndex else if (items.isEmpty()) C.INDEX_UNSET else 0,
                            if (same) startPositionMs else C.TIME_UNSET,
                        )
                    }
                }
            }.onSuccess { future.set(it) }.onFailure { future.setException(it) }
        }
        return future
    }

    /**
     * A song tapped in a list the browser was just shown plays with the songs after it in that
     * list (up to [QUEUE_AFTER]), as it would on the phone; null when [item] was not in that list.
     */
    private suspend fun queueAfter(controller: MediaSession.ControllerInfo, item: MediaItem): List<MediaItem>? {
        val last = synchronized(shown) { shown[controller.packageName] } ?: return null
        val at = last.ids.indexOf(item.mediaId).takeIf { it >= 0 } ?: return null
        val rows = tree().children(last.folder, last.offset + at, QUEUE_AFTER).filter { it.kind == EntryKind.Song }
        if (rows.isEmpty()) return null
        return rows.mapNotNull { row -> MediaIds.item(row.id)?.let { resolve(it) } }.takeIf { it.isNotEmpty() }
    }

    /**
     * The playable items for what a controller sent: a song id is looked up in the index, a folder
     * id is its songs (at most [FOLDER_SONGS]), a voice or text search is its songs, and an item
     * carrying a URI is kept only from the app's own controller.
     */
    private suspend fun expand(controller: MediaSession.ControllerInfo, items: List<MediaItem>): List<MediaItem> {
        val own = controller.packageName == context.packageName
        val out = ArrayList<MediaItem>(items.size)
        for (item in items) {
            val query = item.requestMetadata.searchQuery
            val folder = BrowseIds.parse(item.mediaId)
            when {
                item.localConfiguration != null -> if (own) out += item
                folder != null && folder != Folder.Root -> out += songsOf(folder)
                item.mediaId.isEmpty() && query != null -> out += songsFor(query)
                else -> MediaIds.item(item.mediaId)?.let { resolve(it) }?.let { out += it }
            }
            if (out.size >= MAX_QUEUE) break
        }
        return out
    }

    private suspend fun songsOf(folder: Folder): List<MediaItem> {
        val rows = tree().children(folder, 0, FOLDER_SONGS).filter { it.kind == EntryKind.Song }
        return rows.mapNotNull { row -> MediaIds.item(row.id)?.let { resolve(it) } }
    }

    /** What a voice or text search asks to play: its songs, else the songs of the first album, else of the first artist. */
    private suspend fun songsFor(query: String): List<MediaItem> {
        val found = tree().search(query, 0, SEARCH_PLAY)
        val songs = found.filter { it.kind == EntryKind.Song }
        if (songs.isNotEmpty()) return songs.mapNotNull { row -> MediaIds.item(row.id)?.let { resolve(it) } }
        val folder = found.firstOrNull { it.kind == EntryKind.Folder }?.let { BrowseIds.parse(it.id) } ?: return emptyList()
        return songsOf(folder)
    }

    /** A saved queue is restored by the app when it starts, never by a controller asking: nothing autoplays. */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException("playback is never resumed by a controller"))

    // Rows as media items.

    private suspend fun items(rows: List<BrowseEntry>): List<MediaItem> = withContext(Dispatchers.IO) {
        rows.map { row -> async { item(row, withArt = true) } }.awaitAll()
    }

    private suspend fun item(row: BrowseEntry, withArt: Boolean): MediaItem {
        val song = row.kind == EntryKind.Song
        val folder = row.kind == EntryKind.Folder
        val metadata = MediaMetadata.Builder()
            .setTitle(row.title)
            .setIsBrowsable(folder)
            .setIsPlayable(song)
            .setMediaType(
                when {
                    song -> MediaMetadata.MEDIA_TYPE_MUSIC
                    row.id.startsWith("tm:album:") -> MediaMetadata.MEDIA_TYPE_ALBUM
                    row.id.startsWith("tm:artist:") -> MediaMetadata.MEDIA_TYPE_ARTIST
                    row.id.startsWith("tm:playlist:") -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    folder -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    else -> null
                },
            )
        if (song) metadata.setArtist(row.subtitle) else metadata.setSubtitle(row.subtitle.ifBlank { null })
        row.album?.let { metadata.setAlbumTitle(it) }
        // An unknown length is left out, never given as 0.
        row.durationMs?.let { metadata.setDurationMs(it) }
        if (song) {
            metadata.setExtras(Bundle().apply { putString(MediaIds.COVER_ART, row.art?.generatedKey) })
        }
        if (withArt) BrowseArtwork.of(row.art)?.let { metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(row.id).setMediaMetadata(metadata.build()).build()
    }

    companion object {
        private const val TAG = "BrowseCallback"
        private const val MAX_BROWSERS = 8

        /** Songs queued after a tapped one, and from a tapped folder or a search. */
        private const val QUEUE_AFTER = 100
        private const val FOLDER_SONGS = 200
        private const val SEARCH_PLAY = 50
        private const val MAX_QUEUE = 500

        private const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1
    }
}
