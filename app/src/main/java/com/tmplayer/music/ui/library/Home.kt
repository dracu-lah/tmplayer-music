package com.tmplayer.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.ui.common.Cover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Up to this many songs go into a Play all or Shuffle all of the whole library. */
const val PLAY_ALL_LIMIT = 2_000

/** The whole library's songs for Play all and Shuffle all, bounded; the queue takes 2,000 at most anyway. */
suspend fun allSongs(): List<Song> {
    val index = Library.index()
    val out = ArrayList<Song>()
    var after: com.tmplayer.music.library.SongCursor? = null
    while (out.size < PLAY_ALL_LIMIT) {
        val page = index.songs(SongSort.Title, after, 1_000)
        out += page
        if (page.size < 1_000) break
        after = com.tmplayer.music.library.SongCursor.of(page.last(), SongSort.Title)
    }
    return out
}

/**
 * Home: Recently played, Recently added and Favourites as rows of covers, and Shuffle all. A row
 * with nothing in it is left out; with nothing anywhere, Home says how to start. Plain lists of
 * what the user has, never a recommendation (`plan/RESEARCH.md` 11).
 */
@Composable
fun HomeTab(actions: SongActions, onShuffleAll: () -> Unit) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var played by remember { mutableStateOf<List<Song>>(emptyList()) }
    var added by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favourites by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        try {
            val index = Library.index()
            played = index.recentlyPlayed(ROW)
            added = index.recentlyAdded(ROW)
            favourites = index.favourites(ROW)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rows keep what they had; the library tabs say what went wrong.
        }
        loaded = true
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "buttons") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShuffleAll, enabled = added.isNotEmpty()) { Text(L.homeShuffleAll) }
            }
        }
        if (played.isNotEmpty()) item(key = "played") { SongShelf(L.homeRecentlyPlayed, played, actions) }
        if (added.isNotEmpty()) item(key = "added") { SongShelf(L.homeRecentlyAdded, added, actions) }
        if (favourites.isNotEmpty()) item(key = "favourites") { SongShelf(L.homeFavourites, favourites, actions) }
        if (loaded && played.isEmpty() && added.isEmpty() && favourites.isEmpty()) item(key = "empty") { EmptyLibrary() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongShelf(title: String, songs: List<Song>, actions: SongActions) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(songs, key = { it.groupKey }) { song ->
                Column(
                    Modifier.width(128.dp).combinedClickable(
                        onClick = { actions.play(songs, songs.indexOf(song)) },
                        onLongClick = { actions.menu(song) },
                    ),
                ) {
                    Cover(name = song.title, fileId = 0, coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(), size = 128.dp, track = song.defaultTrack.takeIf { song.thumbFileId != 0 })
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist.ifBlank { L.libraryUnknownArtist },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Play all and Shuffle all above a list of songs. [songs] may suspend (the whole library is read
 * from the index when pressed); an empty answer does nothing.
 */
@Composable
fun PlayButtons(songs: suspend () -> List<Song>, onPlay: (List<Song>, Boolean) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val press: (Boolean) -> Unit = { shuffle ->
        scope.launch { runCatching { songs() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onPlay(it, shuffle) } }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { press(false) }) { Text(L.homePlayAll) }
        OutlinedButton(onClick = { press(true) }) { Text(L.homeShuffleAll) }
    }
}

private const val ROW = 20
