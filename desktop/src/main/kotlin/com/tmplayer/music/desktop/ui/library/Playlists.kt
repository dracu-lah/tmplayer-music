// Playlists and Favourites follow the Android app's Playlists.kt and Backup.kt (app/.../ui/library),
// laid out for a mouse and a keyboard: the same index calls, the same M3U8 code from :core.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.FileIds
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.os.FilePicker
import com.tmplayer.music.desktop.os.UserFiles
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.DragReorder
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.ErrorPanel
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.common.Loading
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.common.OnSelectAll
import com.tmplayer.music.desktop.ui.common.dragGrip
import com.tmplayer.music.desktop.ui.common.draggedRow
import com.tmplayer.music.desktop.ui.common.autoFocus
import com.tmplayer.music.desktop.ui.common.guarded
import com.tmplayer.music.desktop.ui.common.onEnter
import com.tmplayer.music.desktop.ui.common.typingField
import com.tmplayer.music.desktop.ui.common.moveWithKeys
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.M3u
import com.tmplayer.music.library.Playlist
import com.tmplayer.music.library.Song
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Tells the user a line (a snackbar in the shell); a no-op where there is none (a preview). */
val LocalNotify = compositionLocalOf<(String) -> Unit> { {} }

private const val TAG = "Playlists"

/** The largest list file read: a million short lines. Bigger is not a playlist. */
private const val MAX_LIST_BYTES = 16 * 1024 * 1024

/** Unmatched entries listed in the import preview; the rest are counted. */
private const val MAX_SHOWN = 200

private val ROW_HEIGHT = androidx.compose.ui.unit.Dp(60f)

/** Playlists: every playlist with its cover and count, New playlist, and Import a list. */
@Composable
fun PlaylistsScreen(onOpen: (Playlist) -> Unit) {
    val changes by Library.changes.collectAsState()
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    LaunchedEffect(changes, attempt) {
        guarded({ error = it }) {
            playlists = Library.index().playlists()
            error = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = L.tabPlaylists,
            subtitle = null,
            actions = {
                Button(onClick = { naming = true }) { Text(L.playlistNew) }
                ImportPlaylistButton(onError = { notify(it) }, onImported = { Library.notifyChanged() })
            },
        )
        error?.let { ErrorPanel(it, onRetry = { attempt++ }) }
        val list = playlists
        when {
            list == null && error == null -> Loading()
            list != null && list.isEmpty() -> EmptyState(L.playlistEmptyList)
            list != null -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(list, key = { it.id }) { playlist ->
                    ContextMenuArea(items = {
                        listOf(
                            ContextMenuItem(L.playerPlay) { scope.launch { playPlaylist(playlist, shuffle = false, notify) } },
                            ContextMenuItem(L.nowplayingShuffle) { scope.launch { playPlaylist(playlist, shuffle = true, notify) } },
                            ContextMenuItem(L.playlistRename) { renaming = playlist },
                            ContextMenuItem(L.playlistExport) { scope.launch { exportPlaylist(playlist, playlist.name, notify) } },
                            ContextMenuItem(L.playlistDelete) { deleting = playlist },
                        )
                    }) {
                        PlaylistRow(playlist, onClick = { onOpen(playlist) })
                    }
                }
            }
        }
    }
    if (naming) {
        NameDialog(L.playlistNew, "", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                guarded({ notify(it) }) {
                    if (Library.index().createPlaylist(name) < 0) notify(L.playlistTooMany)
                    Library.notifyChanged()
                }
            }
        }
    }
    renaming?.let { playlist ->
        NameDialog(L.playlistRename, playlist.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch {
                guarded({ notify(it) }) {
                    Library.index().renamePlaylist(playlist.id, name)
                    Library.notifyChanged()
                }
            }
        }
    }
    deleting?.let { playlist ->
        ConfirmDeletePlaylist(playlist.name, onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                guarded({ notify(it) }) {
                    Library.index().deletePlaylist(playlist.id)
                    Library.notifyChanged()
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlaylistCover(playlist, 52)
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(L.librarySongs(playlist.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaylistCover(playlist: Playlist, dp: Int) {
    Cover(
        name = playlist.name,
        fileId = playlist.thumbFileId.takeIf { FileIds.isFresh(it) } ?: 0,
        coverPath = Library.coverFile(playlist.coverKey)?.path.orEmpty(),
        size = dp.dp,
        art = CoverArt.keyOfPlaylist(playlist.id),
    )
}

private suspend fun playPlaylist(playlist: Playlist, shuffle: Boolean, notify: (String) -> Unit) {
    guarded({ notify(it) }) {
        val songs = Library.index().playlistSongs(playlist.id)
        if (songs.isEmpty()) return@guarded
        com.tmplayer.music.desktop.DesktopApp.player.play(songs, if (shuffle) songs.indices.random() else 0, shuffle)
    }
}

/** A playlist's songs by [index], for the page and for Play from the list. */
private suspend fun playlistSongs(id: Long): List<Song> = Library.index().playlistSongs(id)

/**
 * One playlist: Play and Shuffle, its songs in order. Each row can be moved (the grip, Alt+Up and
 * Alt+Down, the arrows or the menu) or removed; the playlist can be renamed, exported as M3U8 or
 * deleted (after asking).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistPage(playlist: Playlist, actions: SongActions, onGone: () -> Unit) {
    val changes by Library.changes.collectAsState()
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var songs by remember(playlist.id) { mutableStateOf<List<Song>?>(null) }
    var error by remember(playlist.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val drag = remember { DragReorder() }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.value.dp.toPx() }
    val state: LazyListState = rememberLazyListState()
    LaunchedEffect(changes, attempt) {
        guarded({ error = it }) {
            songs = playlistSongs(playlist.id)
            error = null
        }
    }
    val edit: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            guarded({ notify(it) }) {
                block()
                Library.notifyChanged()
            }
        }
    }
    val move: (Int, Int) -> Unit = { from, to ->
        // The list moves on screen at once; the index follows, and a failure puts the real order back.
        songs = songs?.toMutableList()?.also { if (from in it.indices && to in it.indices) it.add(to, it.removeAt(from)) }
        edit { Library.index().movePlaylistItem(playlist.id, from, to) }
    }
    val list = songs.orEmpty()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = name,
            subtitle = L.librarySongs(list.size),
            actions = {
                Button(onClick = { if (list.isNotEmpty()) actions.play(list, 0) }, enabled = list.isNotEmpty()) { Text(L.playerPlay) }
                FilledTonalButton(onClick = { actions.shuffle(list) }, enabled = list.isNotEmpty()) { Text(L.nowplayingShuffle) }
                TextButton(onClick = { renaming = true }) { Text(L.playlistRename) }
                TextButton(onClick = { scope.launch { exportPlaylist(playlist, name, notify) } }, enabled = list.isNotEmpty()) { Text(L.playlistExport) }
                actions.keepOffline?.let { keep -> TextButton(onClick = { keep(list) }, enabled = list.isNotEmpty()) { Text(L.offlineKeep) } }
                TextButton(onClick = { deleting = true }) { Text(L.playlistDelete) }
            },
        )
        error?.let { ErrorPanel(it, onRetry = { attempt++ }) }
        when {
            songs == null && error == null -> Loading()
            songs != null && list.isEmpty() -> EmptyState(L.desktopPlaylistEmpty)
            songs != null -> LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(list, key = { i, s -> "$i:${s.groupKey}" }) { i, song ->
                    ContextMenuArea(items = {
                        buildList {
                            add(ContextMenuItem(L.playerPlay) { actions.play(list, i) })
                            add(ContextMenuItem(L.menuPlayNext) { actions.playNext(listOf(song)) })
                            add(ContextMenuItem(L.menuAddToQueue) { actions.addToQueue(listOf(song)) })
                            if (i > 0) add(ContextMenuItem(L.playlistMoveUp) { move(i, i - 1) })
                            if (i < list.lastIndex) add(ContextMenuItem(L.playlistMoveDown) { move(i, i + 1) })
                            add(ContextMenuItem(L.playlistRemoveSong) { edit { Library.index().removeFromPlaylist(playlist.id, i) } })
                        }
                    }) {
                        EditableRow(
                            song = song,
                            drag = drag,
                            index = i,
                            count = list.size,
                            rowPx = rowPx,
                            onMove = move,
                            onPlay = { actions.play(list, i) },
                            onRemove = { edit { Library.index().removeFromPlaylist(playlist.id, i) } },
                        )
                    }
                }
            }
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
        ConfirmDeletePlaylist(name, onDismiss = { deleting = false }) {
            deleting = false
            scope.launch {
                guarded({ notify(it) }) {
                    Library.index().deletePlaylist(playlist.id)
                    Library.notifyChanged()
                    onGone()
                }
            }
        }
    }
}

/** A song row that can be moved and removed: the grip, up and down arrows, and a remove button. */
@Composable
private fun EditableRow(
    song: Song,
    drag: DragReorder,
    index: Int,
    count: Int,
    rowPx: Float,
    onMove: (Int, Int) -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .draggedRow(drag, index)
            .moveWithKeys(index, count, onMove)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp)
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            MusicIcons.Drag,
            contentDescription = L.queueMove,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).dragGrip(drag, index, count, rowPx, onMove),
        )
        Cover(
            name = song.title,
            fileId = 0,
            size = 40.dp,
            coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(),
            track = song.defaultTrack.takeIf { song.thumbFileId != 0 },
            art = CoverArt.keyOf(song.albumKey, song.groupKey),
        )
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                com.tmplayer.music.library.ArtistDisplay.of(song.artist).ifBlank { L.libraryUnknownArtist },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
            Icon(MusicIcons.Up, contentDescription = L.playlistMoveUp, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < count - 1, modifier = Modifier.size(32.dp)) {
            Icon(MusicIcons.Down, contentDescription = L.playlistMoveDown, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(MusicIcons.Close, contentDescription = L.playlistRemoveSong, modifier = Modifier.size(18.dp))
        }
    }
}

/** Favourites: the songs hearted, the newest first, with Play and Shuffle. A heart comes off from the menu. */
@Composable
fun FavouritesPage(actions: SongActions) {
    val changes by Library.changes.collectAsState()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val state = rememberLazyListState()
    var selection by remember { mutableStateOf(ListSelection<String>()) }
    LaunchedEffect(changes, attempt) {
        guarded({ error = it }) {
            songs = Library.index().favourites(FAVOURITES_LIMIT).distinctBy { it.groupKey }
            error = null
        }
    }
    val list = songs.orEmpty()
    OnSelectAll { selection = selection.selectAll(list.map { it.groupKey }) }
    LaunchedEffect(list) { selection = selection.prune(list.map { it.groupKey }) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = L.homeFavourites,
            subtitle = if (songs == null) null else L.librarySongs(list.size),
            actions = {
                Button(onClick = { actions.play(list, 0) }, enabled = list.isNotEmpty()) { Text(L.playerPlay) }
                FilledTonalButton(onClick = { actions.shuffle(list) }, enabled = list.isNotEmpty()) { Text(L.nowplayingShuffle) }
            },
        )
        SelectionBar(selection, list, actions) { selection = it }
        error?.let { ErrorPanel(it, onRetry = { attempt++ }) }
        when {
            songs == null && error == null -> Loading()
            songs != null && list.isEmpty() -> EmptyState(L.desktopFavouritesEmpty)
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                SongTable(list, state, actions, selection, wide = maxWidth >= 760.dp) { selection = it }
            }
        }
    }
}

private const val FAVOURITES_LIMIT = 20_000

/** The keys of the favourite songs, kept for the menu's wording, refreshed whenever the library changes. */
object Favourites {
    private val _keys = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val keys: kotlinx.coroutines.flow.StateFlow<Set<String>> = _keys

    suspend fun refresh() {
        _keys.value = runCatching { Library.index().favouriteKeys() }.getOrDefault(_keys.value)
    }

    fun has(song: Song): Boolean = song.groupKey in _keys.value

    /** Hearts or un-hearts [songs]; false when the limit was reached for a heart. */
    suspend fun set(songs: List<Song>, on: Boolean): Boolean {
        var all = true
        for (song in songs) if (!Library.index().setFavourite(song, on)) all = false
        refresh()
        Library.notifyChanged()
        return all
    }
}

// Add to playlist

/** Add songs to a playlist: New playlist first, then each playlist, with a tick on those that hold the song already. */
@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDone: (String?) -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var containing by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(songs) {
        guarded({ error = it }) {
            val index = Library.index()
            playlists = index.playlists()
            // The tick means every one of the songs is in; one song is the usual case.
            containing = songs.map { index.playlistsContaining(it) }.reduceOrNull { a, b -> a intersect b }.orEmpty()
        }
    }
    fun addTo(id: Long, label: String) {
        scope.launch {
            guarded({ onDone(it) }) {
                val added = Library.index().addToPlaylist(id, songs)
                Library.notifyChanged()
                onDone(if (added == 0 && songs.isNotEmpty()) L.playlistFull else label)
            }
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(L.menuAddToPlaylist) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { naming = true }) { Text(L.playlistNew) }
                playlists?.forEach { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { addTo(playlist.id, playlist.name) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(playlist.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playlist.id in containing) Icon(MusicIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                if (playlists?.isEmpty() == true) Text(L.playlistEmptyList, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(L.commonCancel) } },
    )
    if (naming) {
        NameDialog(L.playlistNew, "", onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                guarded({ onDone(it) }) {
                    val id = Library.index().createPlaylist(name)
                    if (id < 0) onDone(L.playlistTooMany) else addTo(id, name)
                }
            }
        }
    }
}

// Dialogs

/** A name for a playlist; Enter accepts it, Escape closes. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(200) },
                label = { Text(L.playlistName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).typingField().onEnter { onDone(name.trim().ifBlank { L.playlistDefaultName }) },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim().ifBlank { L.playlistDefaultName }) }) { Text(title) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.commonCancel) } },
    )
}

@Composable
private fun ConfirmDeletePlaylist(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.playlistDeleteTitle(name)) },
        text = { Text(L.playlistDeleteBody) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(L.playlistDelete) } },
        // Cancel is the one the keyboard lands on first: a destructive step is off by default.
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.autoFocus()) { Text(L.commonCancel) } },
    )
}

// Export and import

/** Export as M3U8: asks where to save, then writes the playlist's songs. */
private suspend fun exportPlaylist(playlist: Playlist, name: String, notify: (String) -> Unit) {
    guarded({ notify(L.playlistExportFailed) }) {
        val songs = Library.index().playlistSongs(playlist.id)
        if (songs.isEmpty()) {
            notify(L.playlistExportEmpty)
            return@guarded
        }
        val file = FilePicker.save(L.playlistExport, UserFiles.safeName(name) + ".m3u8", "m3u8") ?: return@guarded
        try {
            UserFiles.writeText(file, M3u.export(name, songs))
            notify(L.playlistExportSaved(songs.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "could not export a playlist", e)
            notify(L.playlistExportFailed)
        }
    }
}

private sealed interface ImportState {
    data object Reading : ImportState

    class Preview(val parsed: M3u.Parsed, val matched: M3u.Matched, val suggestedName: String) : ImportState
}

/**
 * Import a list: pick an M3U, M3U8 or text file, see how many entries match songs in the library and
 * which do not, and only on Create playlist is anything made. Nothing is downloaded, no path is opened.
 */
@Composable
fun ImportPlaylistButton(onError: (String) -> Unit, onImported: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState?>(null) }
    TextButton(
        enabled = state !is ImportState.Reading,
        onClick = {
            scope.launch {
                guarded({ onError(it) }) {
                    val file = FilePicker.open(L.playlistImport, "m3u", "m3u8", "txt") ?: return@guarded
                    state = ImportState.Reading
                    try {
                        val bytes = UserFiles.readCapped(file, MAX_LIST_BYTES)
                        if (bytes == null) {
                            state = null
                            onError(L.playlistImportTooBig)
                            return@guarded
                        }
                        val parsed = M3u.parse(M3u.decode(bytes))
                        if (parsed.entries.isEmpty()) {
                            state = null
                            onError(L.playlistImportEmpty)
                            return@guarded
                        }
                        val matched = M3u.match(parsed.entries, Library.index().songSource())
                        state = ImportState.Preview(parsed, matched, parsed.name ?: file.nameWithoutExtension)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "could not read a playlist file", e)
                        state = null
                        onError(L.playlistImportFailed)
                    }
                }
            }
        },
    ) { Text(L.playlistImport) }

    val preview = state as? ImportState.Preview ?: return
    ImportPreviewDialog(
        preview,
        onDismiss = { state = null },
        onCreate = { name ->
            state = null
            scope.launch {
                guarded({ onError(it) }) {
                    val id = Library.index().createPlaylistWith(name, preview.matched.songs)
                    if (id < 0) onError(L.playlistTooMany) else onImported()
                }
            }
        },
    )
}

@Composable
private fun ImportPreviewDialog(preview: ImportState.Preview, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val matched = preview.matched
    var name by remember { mutableStateOf(preview.suggestedName.take(200).ifBlank { L.playlistDefaultName }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.playlistImportTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text(L.playlistImportName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().typingField(),
                )
                Text(L.playlistImportMatched(matched.matched.size, matched.total), style = MaterialTheme.typography.titleSmall)
                if (matched.duplicates > 0) Note(L.playlistImportDuplicates(matched.duplicates))
                if (preview.parsed.skipped > 0) Note(L.playlistImportSkipped(preview.parsed.skipped))
                if (preview.parsed.truncated) Note(L.playlistImportCut)
                if (matched.matched.isEmpty()) {
                    Text(L.playlistImportNone, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Note(L.playlistImportNote)
                }
                if (matched.unmatched.isNotEmpty()) {
                    Text(L.playlistImportUnmatched(matched.unmatched.size), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(matched.unmatched.take(MAX_SHOWN)) { miss ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)) {
                                Text(miss.entry.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (miss.reason == M3u.Miss.Ambiguous) L.playlistImportSeveral else L.playlistImportNotFound,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (matched.unmatched.size > MAX_SHOWN) {
                            item { Note(L.playlistImportMore(matched.unmatched.size - MAX_SHOWN)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = matched.matched.isNotEmpty(),
                onClick = { onCreate(name.trim().ifBlank { L.playlistDefaultName }) },
            ) { Text(L.playlistImportCreate) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.commonCancel) } },
    )
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
