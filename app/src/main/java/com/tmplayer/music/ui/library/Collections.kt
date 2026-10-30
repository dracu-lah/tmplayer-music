package com.tmplayer.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import com.tmplayer.music.shell.NavLayout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.Song
import com.tmplayer.music.ui.common.Cover
import kotlinx.coroutines.CancellationException

/**
 * Albums, A to Z (the only order the index keeps for albums), as rows or as a grid of covers whose
 * columns follow the width ([NavLayout.gridColumns]: two on a phone held upright, more sideways).
 */
@Composable
fun AlbumsTab(grid: Boolean = false, onAlbum: (Album) -> Unit, header: @Composable () -> Unit) {
    if (grid) {
        CoverGrid<Album>(
            key = "albums",
            load = { after, limit -> Library.index().albums(after, limit) },
            itemKey = { it.key },
            header = header,
        ) { album, size ->
            GridCell(album.title, ArtistDisplay.of(album.artist), onClick = { onAlbum(album) }) { AlbumCover(album, size) }
        }
        return
    }
    val state = rememberLazyListState()
    val pager = rememberPager<Album, Album>("albums", state, { after, limit -> Library.index().albums(after, limit) }, { it })
    ScrollToTopOnReselect(state)
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        items(pager.items, key = { it.key }) { album -> AlbumRow(album) { onAlbum(album) } }
        if (pager.loaded && pager.items.isEmpty()) item(key = "empty") { EmptyLibrary() }
    }
}

/**
 * A paged grid of covers, read from the index a page at a time as it nears its end, like the
 * lists. Each cell is a square cover sized to the column, with one or two lines under it.
 */
@Composable
private fun <T : Any> CoverGrid(
    key: String,
    load: suspend (after: T?, limit: Int) -> List<T>,
    itemKey: (T) -> Any,
    header: @Composable () -> Unit,
    cell: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit,
) {
    val state = rememberLazyGridState()
    val pager = rememberGridPager<T, T>(key, state, load, { it })
    OnReselect { state.animateScrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = NavLayout.gridColumns(maxWidth.value)
        val gap = 12.dp
        val side = 16.dp
        val size = ((maxWidth - side * 2 - gap * (columns - 1)) / columns).coerceAtLeast(48.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = side, end = side, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(pager.items, key = itemKey) { cell(it, size) }
            if (pager.loaded && pager.items.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary() }
            pager.error?.let { message ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun GridCell(title: String, subtitle: String, onClick: () -> Unit, round: Boolean = false, cover: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cover()
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AlbumCover(album, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(ArtistDisplay.of(album.artist).ifBlank { null }, album.year.takeIf { it > 0 }?.toString(), L.librarySongs(album.songCount))
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Artists, A to Z (the only order the index keeps for artists), as rows or as a grid of circles. */
@Composable
fun ArtistsTab(grid: Boolean = false, onArtist: (Artist) -> Unit, header: @Composable () -> Unit) {
    if (grid) {
        CoverGrid<Artist>(
            key = "artists",
            load = { after, limit -> Library.index().artists(after, limit) },
            itemKey = { it.key },
            header = header,
        ) { artist, size ->
            GridCell(artist.name.ifBlank { L.libraryUnknownArtist }, L.librarySongs(artist.songCount), onClick = { onArtist(artist) }, round = true) {
                Cover(name = artist.name, fileId = 0, round = true, size = size)
            }
        }
        return
    }
    val state = rememberLazyListState()
    val pager = rememberPager<Artist, Artist>("artists", state, { after, limit -> Library.index().artists(after, limit) }, { it })
    ScrollToTopOnReselect(state)
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        items(pager.items, key = { it.key }) { artist ->
            Row(
                Modifier.fillMaxWidth().clickable { onArtist(artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(name = artist.name, fileId = 0, round = true)
                Column(Modifier.weight(1f)) {
                    Text(artist.name.ifBlank { L.libraryUnknownArtist }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(L.librarySongs(artist.songCount), artist.albumCount.takeIf { it > 0 }?.let(L::libraryAlbums)).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (pager.loaded && pager.items.isEmpty()) item(key = "empty") { EmptyLibrary() }
    }
}

/** One album: its songs by disc and track number. */
@Composable
fun AlbumScreen(album: Album, actions: SongActions) {
    val songs = rememberList(album.key) { Library.index().albumSongs(album.key) }
    DetailList(
        title = album.title,
        subtitle = listOfNotNull(ArtistDisplay.of(album.artist).ifBlank { null }, album.year.takeIf { it > 0 }?.toString()).joinToString("  ·  "),
        cover = { AlbumCover(album, 120.dp) },
        songs = songs,
        actions = actions,
    )
}

/** One artist: their albums in a row, then every song. */
@Composable
fun ArtistScreen(artist: Artist, actions: SongActions, onAlbum: (Album) -> Unit) {
    val songs = rememberList(artist.key) { Library.index().artistSongs(artist.key) }
    val albums = rememberList("albums:" + artist.key) { Library.index().artistAlbums(artist.key) }
    DetailList(
        title = artist.name.ifBlank { L.libraryUnknownArtist },
        subtitle = L.librarySongs(artist.songCount),
        cover = { Cover(name = artist.name, fileId = 0, round = true, size = 120.dp) },
        songs = songs,
        actions = actions,
        extra = {
            val list = albums.value.orEmpty()
            if (list.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.key }) { album ->
                        Column(Modifier.width(120.dp).clickable { onAlbum(album) }) {
                            AlbumCover(album, 120.dp)
                            Text(album.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    LaunchedEffect(key) {
        try {
            value = load()
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
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "head") {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cover()
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "extra") { extra() }
        songs.error?.let { item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) } }
        val list = songs.value
        if (list == null) {
            item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            // Distinct, so a song listed twice by the index cannot crash the list on a repeated key.
            val distinct = list.distinctBy { it.groupKey }
            items(distinct, key = { it.groupKey }) { song ->
                SongRow(song, onClick = { actions.play(distinct, distinct.indexOf(song)) }, onLongClick = { actions.menu(song) })
            }
        }
    }
}

/**
 * An album's cover: the one saved from its tags, else Telegram's thumbnail of one of its tracks,
 * looked up by message because a saved file id belongs to the session that scanned it.
 */
@Composable
fun AlbumCover(album: Album, size: androidx.compose.ui.unit.Dp) {
    val path = Library.coverFile(album.coverKey)?.path.orEmpty()
    val track by androidx.compose.runtime.produceState<com.tmplayer.music.library.TrackId?>(null, album.key, path) {
        if (path.isEmpty() && album.thumbFileId != 0) value = runCatching { Library.index().albumThumbTrack(album.key) }.getOrNull()
    }
    Cover(name = album.title, fileId = 0, coverPath = path, size = size, track = track, art = com.tmplayer.music.library.CoverArt.keyOfAlbum(album.key))
}
