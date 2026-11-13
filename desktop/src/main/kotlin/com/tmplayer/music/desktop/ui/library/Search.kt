// Search follows the Android app's Search.kt (app/.../ui/library/Search.kt): the same index
// query and the same recent searches, with the field focused when the page opens (Ctrl+F).
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.common.SectionTitle
import com.tmplayer.music.desktop.ui.common.typingField
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search over the local index: songs, albums and artists, case and accent insensitive, as the user
 * types (a short pause first, so each key does not start a query). With nothing typed, the recent
 * searches; a search is remembered when a result is opened or Enter is pressed.
 *
 * [focusToken] changes each time Ctrl+F is pressed, which puts the keyboard in the field again and
 * selects what is typed.
 */
@Composable
fun SearchScreen(focusToken: Int, actions: SongActions, onAlbum: (Album) -> Unit, onArtist: (Artist) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusToken) { runCatching { focus.requestFocus() } }
    LaunchedEffect(bump) { guarded({ error = it }) { recent = Library.index().recentSearches() } }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        guarded({ error = it }) {
            results = Library.index().search(query.trim())
            error = null
        }
    }
    val remember: () -> Unit = {
        val q = query.trim()
        if (q.isNotEmpty()) scope.launch { guarded({}) { Library.index().addRecentSearch(q); bump++ } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(L.searchTitle, null)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(200) },
            placeholder = { Text(L.searchHint) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(MusicIcons.Close, contentDescription = L.searchClear) } }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { remember() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).focusRequester(focus).typingField(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
        val found = results
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            if (found == null) {
                if (recent.isNotEmpty()) {
                    item(key = "recentHead") {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(L.searchRecent, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { guarded({}) { Library.index().clearRecentSearches(); bump++ } } }) { Text(L.searchClear) }
                        }
                    }
                    items(recent, key = { "r:$it" }) { text ->
                        Text(text, modifier = Modifier.fillMaxWidth().clickable { query = text }.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                }
            } else {
                if (found.songs.isEmpty() && found.albums.isEmpty() && found.artists.isEmpty()) {
                    item(key = "none") { Text(L.searchNothing(query.trim()), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
                if (found.songs.isNotEmpty()) {
                    item(key = "songsHead") { SectionTitle(L.tabSongs) }
                    items(found.songs, key = { "s:${it.groupKey}" }) { song ->
                        Line(
                            title = song.title,
                            subtitle = ArtistDisplay.of(song.artist).ifBlank { L.libraryUnknownArtist },
                            art = CoverArt.keyOf(song.albumKey, song.groupKey),
                            coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(),
                        ) {
                            remember()
                            actions.play(found.songs, found.songs.indexOf(song))
                        }
                    }
                }
                if (found.albums.isNotEmpty()) {
                    item(key = "albumsHead") { SectionTitle(L.tabAlbums) }
                    items(found.albums, key = { "a:${it.key}" }) { album ->
                        Line(album.title, ArtistDisplay.of(album.artist), art = CoverArt.keyOfAlbum(album.key), coverPath = Library.coverFile(album.coverKey)?.path.orEmpty()) {
                            remember()
                            onAlbum(album)
                        }
                    }
                }
                if (found.artists.isNotEmpty()) {
                    item(key = "artistsHead") { SectionTitle(L.tabArtists) }
                    items(found.artists, key = { "r:${it.key}" }) { artist ->
                        Line(artist.name.ifBlank { L.libraryUnknownArtist }, L.librarySongs(artist.songCount), round = true) {
                            remember()
                            onArtist(artist)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(title: String, subtitle: String, art: String? = null, coverPath: String = "", round: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(name = title, fileId = 0, size = 44.dp, coverPath = coverPath, art = art, round = round)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private suspend fun guarded(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(Failures.humanise(e))
    }
}

private const val DEBOUNCE_MS = 200L
