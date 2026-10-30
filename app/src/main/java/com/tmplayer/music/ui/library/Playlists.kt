package com.tmplayer.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Playlist
import com.tmplayer.music.library.Song
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.MusicIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Runs an index write from a button, showing its failure instead of throwing it. */
suspend fun guarded(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(Failures.humanise(e))
    }
}

/** Playlists: New playlist at the top, then the user's playlists, most recently changed first. */
@Composable
fun PlaylistsTab(onPlaylist: (Playlist) -> Unit) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var bump by remember { mutableIntStateOf(0) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(changes, bump) { guarded({ error = it }) { playlists = Library.index().playlists() } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    RestoreScroll(listState, ready = playlists != null)
    ScrollToTopOnReselect(listState)
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "new") {
            Button(onClick = { naming = true }, modifier = Modifier.padding(16.dp)) { Text(L.playlistNew) }
        }
        error?.let { item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        val list = playlists.orEmpty()
        if (playlists != null && list.isEmpty()) {
            item(key = "empty") { Text(L.playlistEmptyList, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
        items(list, key = { it.id }) { playlist ->
            Row(
                Modifier.fillMaxWidth().clickable { onPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(name = playlist.name, fileId = playlist.thumbFileId.takeIf { com.tmplayer.music.data.FileIds.isFresh(it) } ?: 0, coverPath = Library.coverFile(playlist.coverKey)?.path.orEmpty(), size = 56.dp, art = com.tmplayer.music.library.CoverArt.keyOfPlaylist(playlist.id))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(L.librarySongs(playlist.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (naming) {
        NameDialog(L.playlistNew, "", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                guarded({ error = it }) {
                    if (Library.index().createPlaylist(name) < 0) error = L.playlistTooMany
                    bump++
                }
            }
        }
    }
}

/**
 * One playlist: Play all and Shuffle all, its songs in order. Each row can be moved up or down or
 * removed; the playlist can be renamed or deleted (after asking).
 */
@Composable
fun PlaylistScreen(playlist: Playlist, actions: SongActions, onDeleted: () -> Unit) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var bump by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(playlist.name) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(changes, bump) { guarded({ error = it }) { songs = Library.index().playlistSongs(playlist.id) } }
    val edit: (suspend () -> Unit) -> Unit = { block -> scope.launch { guarded({ error = it }) { block(); bump++; Library.notifyChanged() } } }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(L.librarySongs(songs?.size ?: 0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { renaming = true }) { Text(L.playlistRename) }
                    TextButton(onClick = { deleting = true }) { Text(L.playlistDelete) }
                }
            }
            PlayButtons({ songs.orEmpty() }) { list, shuffle -> if (shuffle) actions.shuffle(list) else actions.play(list, 0) }
        }
        error?.let { item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        val list = songs.orEmpty()
        if (songs != null && list.isEmpty()) {
            item(key = "empty") { Text(L.playlistEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        }
        itemsIndexed(list, key = { i, s -> "$i:${s.groupKey}" }) { i, song ->
            SongRow(
                song,
                onClick = { actions.play(list, i) },
                onLongClick = { actions.menu(song) },
                trailing = {
                    IconButton(onClick = { edit { Library.index().movePlaylistItem(playlist.id, i, i - 1) } }, enabled = i > 0) {
                        Icon(MusicIcons.Up, contentDescription = L.playlistMoveUp)
                    }
                    IconButton(onClick = { edit { Library.index().movePlaylistItem(playlist.id, i, i + 1) } }, enabled = i < list.lastIndex) {
                        Icon(MusicIcons.Down, contentDescription = L.playlistMoveDown)
                    }
                    IconButton(onClick = { edit { Library.index().removeFromPlaylist(playlist.id, i) } }) {
                        Icon(MusicIcons.Close, contentDescription = L.playlistRemoveSong)
                    }
                },
            )
        }
    }
    if (renaming) {
        NameDialog(L.playlistRename, name, onDismiss = { renaming = false }) { new ->
            renaming = false
            name = new
            edit { Library.index().renamePlaylist(playlist.id, new) }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(L.playlistDeleteTitle(name)) },
            text = { Text(L.playlistDeleteBody) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { guarded({ error = it }) { Library.index().deletePlaylist(playlist.id); onDeleted() } }
                }) { Text(L.playlistDelete) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(L.commonCancel) } },
        )
    }
}

/** Add a song to playlists: New playlist first, then each playlist with a tick if the song is in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: Song, onDismiss: () -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var containing by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var bump by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(bump) {
        guarded({ error = it }) {
            val index = Library.index()
            playlists = index.playlists()
            containing = index.playlistsContaining(song)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrolls: many playlists, or a window turned sideways.
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp)) {
            Text(L.menuAddToPlaylist, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                L.playlistNew,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            for (playlist in playlists) {
                val inIt = playlist.id in containing
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !inIt) {
                        scope.launch {
                            guarded({ error = it }) {
                                if (Library.index().addToPlaylist(playlist.id, listOf(song)) == 0) {
                                    error = L.playlistFull
                                } else {
                                    Library.notifyChanged()
                                    onDismiss()
                                }
                            }
                        }
                    }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Checkbox(checked = inIt, onCheckedChange = null)
                }
            }
        }
    }
    if (naming) {
        NameDialog(L.playlistNew, "", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                guarded({ error = it }) {
                    val index = Library.index()
                    val id = index.createPlaylist(name)
                    if (id < 0) {
                        error = L.playlistTooMany
                    } else {
                        index.addToPlaylist(id, listOf(song))
                        Library.notifyChanged()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(200) },
                label = { Text(L.playlistName) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim().ifBlank { L.playlistDefaultName }) }) { Text(L.commonContinue) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.commonCancel) } },
    )
}
