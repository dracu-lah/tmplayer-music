// Albums, artists and their pages follow the Android app's Collections.kt (app/.../ui/library/Collections.kt).
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.ErrorPanel
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.common.Loading
import com.tmplayer.music.desktop.ui.common.OnSelectAll
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.CancellationException

/** Albums, A to Z (the only order the index keeps for albums), as a grid of covers that fills the width. */
@Composable
fun AlbumsScreen(onAlbum: (Album) -> Unit, onManage: () -> Unit) {
    CoverGrid<Album>(
        key = "albums",
        title = L.tabAlbums,
        load = { after, limit -> Library.index().albums(after, limit) },
        itemKey = { it.key },
        onManage = onManage,
    ) { album, size ->
        GridCell(album.title, ArtistDisplay.of(album.artist), onClick = { onAlbum(album) }) { AlbumCover(album, size) }
    }
}

/** Artists, A to Z, as a grid of circles. */
@Composable
fun ArtistsScreen(onArtist: (Artist) -> Unit, onManage: () -> Unit) {
    CoverGrid<Artist>(
        key = "artists",
        title = L.tabArtists,
        load = { after, limit -> Library.index().artists(after, limit) },
        itemKey = { it.key },
        onManage = onManage,
    ) { artist, size ->
        GridCell(artist.name.ifBlank { L.libraryUnknownArtist }, L.librarySongs(artist.songCount), onClick = { onArtist(artist) }, round = true) {
            Cover(name = artist.name, fileId = 0, round = true, size = size)
        }
    }
}

/**
 * A paged grid of covers, read from the index a page at a time as it nears its end, like the
 * lists. Each cell is a square cover sized to the column, with one or two lines under it.
 */
@Composable
private fun <T : Any> CoverGrid(
    key: String,
    title: String,
    load: suspend (after: T?, limit: Int) -> List<T>,
    itemKey: (T) -> Any,
    onManage: () -> Unit,
    cell: @Composable (T, Dp) -> Unit,
) {
    val state = rememberLazyGridState()
    val pager = rememberGridPager<T, T>(key, state, load, { it })
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, null)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gap = 16.dp
            val side = 24.dp
            val columns = ((maxWidth - side * 2 + gap) / (MIN_CELL + gap)).toInt().coerceIn(2, 10)
            val size = ((maxWidth - side * 2 - gap * (columns - 1)) / columns).coerceAtLeast(64.dp)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = state,
                contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pager.items, key = itemKey) { cell(it, size) }
                if (pager.loaded && pager.items.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(L.libraryEmpty) { FilledTonalButton(onClick = onManage) { Text(L.manageTitle) } }
                    }
                }
                pager.error?.let { message ->
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(message, onRetry = null) }
                }
            }
        }
    }
}

private val MIN_CELL = 150.dp

@Composable
private fun GridCell(title: String, subtitle: String, onClick: () -> Unit, round: Boolean = false, cover: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cover()
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One album: its songs by disc and track number. */
@Composable
fun AlbumPage(album: Album, actions: SongActions) {
    val songs = rememberList(album.key) { Library.index().albumSongs(album.key) }
    DetailList(
        title = album.title,
        subtitle = listOfNotNull(ArtistDisplay.of(album.artist).ifBlank { null }, album.year.takeIf { it > 0 }?.toString(), L.librarySongs(album.songCount))
            .joinToString("  ·  "),
        cover = { AlbumCover(album, 160.dp) },
        songs = songs,
        actions = actions,
    )
}

/** One artist: their albums in a row, then every song. */
@Composable
fun ArtistPage(artist: Artist, actions: SongActions, onAlbum: (Album) -> Unit) {
    val songs = rememberList(artist.key) { Library.index().artistSongs(artist.key) }
    val albums = rememberList("albums:" + artist.key) { Library.index().artistAlbums(artist.key) }
    DetailList(
        title = artist.name.ifBlank { L.libraryUnknownArtist },
        subtitle = listOfNotNull(L.librarySongs(artist.songCount), artist.albumCount.takeIf { it > 0 }?.let(L::libraryAlbums)).joinToString("  ·  "),
        cover = { Cover(name = artist.name, fileId = 0, round = true, size = 160.dp) },
        songs = songs,
        actions = actions,
        extra = {
            val list = albums.value.orEmpty()
            if (list.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(list, key = { it.key }) { album ->
                        Column(Modifier.width(140.dp).clickable { onAlbum(album) }) {
                            AlbumCover(album, 140.dp)
                            Text(album.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
    )
}

/** A short list read whole (an album, an artist; capped by the index), with its failure shown. */
class Loaded<T>(val value: List<T>?, val error: String?)

@Composable
fun <T> rememberList(key: Any, load: suspend () -> List<T>): Loaded<T> {
    var value by remember(key) { mutableStateOf<List<T>?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, attempt) {
        try {
            value = load()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
            value = emptyList()
        }
    }
    return Loaded(value, error)
}

@Composable
private fun DetailList(
    title: String,
    subtitle: String,
    cover: @Composable () -> Unit,
    songs: Loaded<Song>,
    actions: SongActions,
    extra: @Composable () -> Unit = {},
) {
    val list = songs.value?.distinctBy { it.groupKey }
    val state: LazyListState = rememberLazyListState()
    var selection by remember { mutableStateOf(ListSelection<String>()) }
    OnSelectAll { selection = selection.selectAll(list.orEmpty().map { it.groupKey }) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            cover()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { list?.let { actions.play(it, 0) } }, enabled = !list.isNullOrEmpty()) { Text(L.playerPlay) }
                    FilledTonalButton(onClick = { list?.let(actions.shuffle) }, enabled = !list.isNullOrEmpty()) { Text(L.nowplayingShuffle) }
                }
            }
        }
        extra()
        SelectionBar(selection, list.orEmpty(), actions) { selection = it }
        songs.error?.let { ErrorPanel(it, onRetry = null) }
        when {
            list == null -> Loading()
            list.isEmpty() && songs.error == null -> EmptyState(L.libraryEmpty)
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                SongTable(list, state, actions, selection, wide = maxWidth >= 760.dp) { selection = it }
            }
        }
    }
}

/**
 * An album's cover: the one saved from its tags, else Telegram's thumbnail of one of its tracks,
 * looked up by message because a saved file id belongs to the session that scanned it.
 */
@Composable
fun AlbumCover(album: Album, size: Dp) {
    val path = Library.coverFile(album.coverKey)?.path.orEmpty()
    val track by produceState<TrackId?>(null, album.key, path) {
        if (path.isEmpty() && album.thumbFileId != 0) value = runCatching { Library.index().albumThumbTrack(album.key) }.getOrNull()
    }
    Cover(name = album.title, fileId = 0, coverPath = path, size = size, track = track, art = CoverArt.keyOfAlbum(album.key))
}
