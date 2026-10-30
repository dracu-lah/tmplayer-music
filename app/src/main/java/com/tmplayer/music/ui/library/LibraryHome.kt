package com.tmplayer.music.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongCursor
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.ui.onboarding.ChatPicker
import kotlinx.coroutines.launch

/** Where the library is: a tab, or a page opened from one. Back pops the page. */
private sealed interface Page {
    data class AlbumPage(val album: Album) : Page
    data class ArtistPage(val artist: Artist) : Page
    data class ChatPage(val chat: ChatRow) : Page
    data object Manage : Page
    data object Picker : Page
}

private enum class LibraryTab { Songs, Albums, Artists }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHome(onLogOut: () -> Unit) {
    val stack = remember { mutableStateListOf<Page>() }
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }
    var sort by rememberSaveable { mutableStateOf(SongSort.Title) }
    var versionsOf by remember { mutableStateOf<Song?>(null) }
    var chatTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val changes by Library.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val page = stack.lastOrNull()

    LaunchedEffect(changes) {
        chatTitles = runCatching { Library.index().pickedChats().associate { it.chatId to it.title } }.getOrDefault(chatTitles)
    }
    BackHandler(enabled = page != null) { stack.removeAt(stack.lastIndex) }

    val onSong: (Song) -> Unit = { versionsOf = it }

    if (page == Page.Picker) {
        ChatPicker(
            directory = Library.directory,
            already = chatTitles.keys,
            onBack = { stack.removeAt(stack.lastIndex) },
            onDone = { picked ->
                scope.launch { runCatching { Library.pick(picked.map { ChatPick(it.chatId, it.title) }) } }
                stack.removeAt(stack.lastIndex)
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            null -> L.appName
                            is Page.AlbumPage -> page.album.title
                            is Page.ArtistPage -> page.artist.name.ifBlank { L.libraryUnknownArtist }
                            is Page.ChatPage -> page.chat.title
                            Page.Manage -> L.manageTitle
                            Page.Picker -> ""
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (page != null) TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text(L.commonBack) }
                },
                actions = { if (page == null) Overflow(onManage = { stack += Page.Manage }, onLogOut = onLogOut) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanBar()
            when (page) {
                null -> {
                    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                        LibraryTab.entries.forEach { t ->
                            Tab(selected = tab == t, onClick = { tab = t }, text = {
                                Text(
                                    when (t) {
                                        LibraryTab.Songs -> L.tabSongs
                                        LibraryTab.Albums -> L.tabAlbums
                                        LibraryTab.Artists -> L.tabArtists
                                    },
                                )
                            })
                        }
                    }
                    when (tab) {
                        LibraryTab.Songs -> SongsTab(sort, onSong) { SongsHeader(sort) { sort = it } }
                        LibraryTab.Albums -> AlbumsTab(onAlbum = { stack += Page.AlbumPage(it) }) {}
                        LibraryTab.Artists -> ArtistsTab(onArtist = { stack += Page.ArtistPage(it) }) {}
                    }
                }
                is Page.AlbumPage -> AlbumScreen(page.album, onSong)
                is Page.ArtistPage -> ArtistScreen(page.artist, onSong, onAlbum = { stack += Page.AlbumPage(it) })
                is Page.ChatPage -> {
                    val state = rememberLazyListState()
                    val chatId = page.chat.chatId
                    val pager = rememberPager<SongCursor, Song>(
                        key = "chat:$chatId",
                        state = state,
                        load = { after, limit -> Library.index().chatSongs(chatId, after, limit) },
                        cursorOf = { SongCursor.of(it, SongSort.Title) },
                    )
                    SongList(pager, state, onSong)
                }
                Page.Manage -> ManageChats(onAddChats = { stack += Page.Picker }, onChat = { stack += Page.ChatPage(it) })
                Page.Picker -> Unit
            }
        }
    }

    versionsOf?.let { song -> VersionsSheet(song, chatTitles, onDismiss = { versionsOf = null }) }
}

@Composable
private fun Overflow(onManage: () -> Unit, onLogOut: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(L.libraryMenu) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(L.manageTitle) }, onClick = {
                open = false
                onManage()
            })
            DropdownMenuItem(text = { Text(L.shellLogOut) }, onClick = {
                open = false
                onLogOut()
            })
        }
    }
}

/** The count of songs and files, and the sort. */
@Composable
private fun SongsHeader(sort: SongSort, onSort: (SongSort) -> Unit) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var counts by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(changes) {
        counts = runCatching { Library.index().let { it.songCount() to it.fileCount() } }.getOrDefault(counts)
    }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            L.libraryCounts(counts.first, counts.second),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { open = true }) { Text(sortName(sort)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SongSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(sortName(s)) }, onClick = {
                        open = false
                        onSort(s)
                    })
                }
            }
        }
    }
}

private fun sortName(sort: SongSort): String = when (sort) {
    SongSort.Title -> L.sortTitle
    SongSort.Artist -> L.sortArtist
    SongSort.Album -> L.sortAlbum
    SongSort.Added -> L.sortAdded
}

/**
 * The scan's status while it runs: chats done of total, tracks found, a wait Telegram asked for,
 * and Pause. The tag reading shows here too. Gone when nothing runs.
 */
@Composable
private fun ScanBar() {
    val scan by Library.scanner.status.collectAsStateWithLifecycle()
    val tags by Library.enricher.status.collectAsStateWithLifecycle()
    if (!scan.running && !tags.running) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        scan.paused -> L.scanPaused
                        scan.waitingSeconds > 0 -> L.scanWaiting(scan.waitingSeconds)
                        scan.running -> L.scanRunning(minOf(scan.chatsDone + 1, scan.chatsTotal), scan.chatsTotal, scan.found)
                        else -> L.scanTags(tags.done)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (scan.paused) Library.resume() else Library.pause() }) {
                    Text(if (scan.paused) L.scanResume else L.scanPause)
                }
            }
            if (!scan.paused) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
