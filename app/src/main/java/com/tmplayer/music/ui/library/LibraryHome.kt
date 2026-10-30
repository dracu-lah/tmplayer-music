package com.tmplayer.music.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.player.HidePlayerSheet
import com.tmplayer.music.ui.player.PlayerSheetSpacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.tmplayer.music.library.Playlist
import com.tmplayer.music.library.Song
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.ui.onboarding.ChatPicker
import kotlinx.coroutines.launch

/** Where the library is: a tab, or a page opened from one. Back pops the page. */
private sealed interface Page {
    data class AlbumPage(val album: Album) : Page
    data class ArtistPage(val artist: Artist) : Page
    data class ChatPage(val chat: ChatRow) : Page
    data class PlaylistPage(val playlist: Playlist) : Page
    data object Manage : Page
    data object Picker : Page
    data object Search : Page
    data object Settings : Page
}

private enum class LibraryTab { Home, Chats, Songs, Albums, Artists, Playlists }

/**
 * The library: tabs, the pages opened from them, the scan banner, and the one time question about
 * reading track details on mobile data. [startOnChats] opens on the Chats tab, for the first run,
 * where the chats just picked can be played before the scan has filled the other tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHome(onLogOut: () -> Unit, startOnChats: Boolean = false) {
    val stack = remember { mutableStateListOf<Page>() }
    var tab by rememberSaveable { mutableStateOf(if (startOnChats) LibraryTab.Chats else LibraryTab.Home) }
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

    var menuFor by remember { mutableStateOf<Song?>(null) }
    var playlistFor by remember { mutableStateOf<Song?>(null) }
    val askNotifications = rememberNotificationAsk()
    val actions = remember {
        SongActions(
            play = { songs, index ->
                askNotifications()
                PlayerConnection.play(songs, index)
            },
            shuffle = { songs ->
                askNotifications()
                PlayerConnection.play(songs, songs.indices.random(), shuffle = true)
            },
            menu = { menuFor = it },
        )
    }

    if (page == Page.Picker) {
        HidePlayerSheet()
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
        bottomBar = { PlayerSheetSpacer() },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            null -> L.appName
                            is Page.AlbumPage -> page.album.title
                            is Page.ArtistPage -> page.artist.name.ifBlank { L.libraryUnknownArtist }
                            is Page.ChatPage -> page.chat.title
                            is Page.PlaylistPage -> page.playlist.name
                            Page.Manage -> L.manageTitle
                            Page.Picker -> ""
                            Page.Search -> L.searchTitle
                            Page.Settings -> L.settingsTitle
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (page != null) TextButton(onClick = { stack.removeAt(stack.lastIndex) }) { Text(L.commonBack) }
                },
                actions = {
                    if (page == null) {
                        IconButton(onClick = { stack += Page.Search }) { Icon(MusicIcons.Search, contentDescription = L.searchTitle) }
                        Overflow(onManage = { stack += Page.Manage }, onSettings = { stack += Page.Settings }, onLogOut = onLogOut)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (page != Page.Settings) ScanBar(onOpen = { stack += Page.Settings })
            when (page) {
                null -> {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tab.ordinal,
                        edgePadding = 8.dp,
                        indicator = {
                            TabRowDefaults.PrimaryIndicator(
                                Modifier.tabIndicatorOffset(tab.ordinal, matchContentSize = true),
                                width = androidx.compose.ui.unit.Dp.Unspecified,
                                shape = com.tmplayer.music.ui.theme.Pill,
                            )
                        },
                    ) {
                        LibraryTab.entries.forEach { t ->
                            Tab(selected = tab == t, onClick = { tab = t }, text = {
                                Text(
                                    when (t) {
                                        LibraryTab.Home -> L.tabHome
                                        LibraryTab.Chats -> L.chatfirstTabChats
                                        LibraryTab.Playlists -> L.tabPlaylists
                                        LibraryTab.Songs -> L.tabSongs
                                        LibraryTab.Albums -> L.tabAlbums
                                        LibraryTab.Artists -> L.tabArtists
                                    },
                                )
                            })
                        }
                    }
                    RefreshBox { when (tab) {
                        LibraryTab.Home -> HomeTab(actions, onShuffleAll = { scope.launch { runCatching { actions.shuffle(allSongs()) } } })
                        LibraryTab.Chats -> ChatsTab(actions, onChat = { stack += Page.ChatPage(it) }, onAdd = { stack += Page.Picker })
                        LibraryTab.Playlists -> PlaylistsTab(onPlaylist = { stack += Page.PlaylistPage(it) })
                        LibraryTab.Songs -> SongsTab(sort, actions) {
                            Column {
                                SongsHeader(sort) { sort = it }
                                PlayButtons({ allSongs() }) { list, shuffle -> if (shuffle) actions.shuffle(list) else actions.play(list, 0) }
                            }
                        }
                        LibraryTab.Albums -> AlbumsTab(onAlbum = { stack += Page.AlbumPage(it) }) {}
                        LibraryTab.Artists -> ArtistsTab(onArtist = { stack += Page.ArtistPage(it) }) {}
                    } }
                }
                is Page.AlbumPage -> AlbumScreen(page.album, actions)
                is Page.ArtistPage -> ArtistScreen(page.artist, actions, onAlbum = { stack += Page.AlbumPage(it) })
                is Page.ChatPage -> ChatScreen(page.chat, actions, onGone = { stack.remove(page) })
                is Page.PlaylistPage -> PlaylistScreen(page.playlist, actions, onDeleted = { stack.removeAt(stack.lastIndex) })
                Page.Settings -> SettingsScreen(onManageChats = { stack += Page.Manage })
                Page.Search -> SearchScreen(actions, onAlbum = { stack += Page.AlbumPage(it) }, onArtist = { stack += Page.ArtistPage(it) })
                Page.Manage -> ManageChats(onAddChats = { stack += Page.Picker }, onChat = { stack += Page.ChatPage(it) })
                Page.Picker -> Unit
            }
        }
    }

    versionsOf?.let { song -> VersionsSheet(song, chatTitles, onDismiss = { versionsOf = null }) }
    menuFor?.let { song ->
        SongMenu(
            song = song,
            onDismiss = { menuFor = null },
            onVersions = {
                menuFor = null
                versionsOf = song
            },
            onAddToPlaylist = {
                menuFor = null
                playlistFor = song
            },
            onAlbum = { album ->
                menuFor = null
                stack += Page.AlbumPage(album)
            },
            onArtist = { artist ->
                menuFor = null
                stack += Page.ArtistPage(artist)
            },
        )
    }
    playlistFor?.let { song -> AddToPlaylistSheet(song, onDismiss = { playlistFor = null }) }
    MobileDataQuestion()
}

/**
 * Asked once, the first time the library has files waiting for their details while the phone is on
 * mobile data: read them on mobile data too, or wait for Wi-Fi. Off by default; dismissing it is a
 * no. The answer is kept and can be changed in Settings.
 */
@Composable
private fun MobileDataQuestion() {
    val flow = remember { Library.askMobile() }
    val ask by flow.collectAsStateWithLifecycle(initialValue = false)
    if (!ask) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Library.setMobileAllowed(false) },
        title = { Text(L.chatfirstMobileTitle) },
        text = { Text(L.chatfirstMobileBody) },
        confirmButton = { TextButton(onClick = { Library.setMobileAllowed(true) }) { Text(L.chatfirstMobileYes) } },
        dismissButton = { TextButton(onClick = { Library.setMobileAllowed(false) }) { Text(L.chatfirstMobileNo) } },
    )
}

/**
 * Pull down on a library tab to check the chosen chats for new tracks, as other players refresh.
 * The spinner shows from the pull until the scan it started has ended; nothing runs otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshBox(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val scan by Library.scanner.status.collectAsStateWithLifecycle()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(pulled, scan.running) {
        if (pulled && !scan.running) {
            // The scan starts a moment after the call; give it that moment before calling it done.
            kotlinx.coroutines.delay(800)
            if (!Library.scanner.status.value.running) pulled = false
        }
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            scope.launch { runCatching { Library.refresh() }.onFailure { pulled = false } }
        },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

/** What can be done with one song: play it next, add it to the queue, see its versions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongMenu(
    song: Song,
    onDismiss: () -> Unit,
    onVersions: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var favourite by remember { mutableStateOf<Boolean?>(null) }
    var album by remember { mutableStateOf<Album?>(null) }
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    LaunchedEffect(song.groupKey) {
        runCatching {
            val index = Library.index()
            favourite = song.groupKey in index.favouriteKeys()
            album = song.albumKey.takeIf { it.isNotBlank() }?.let { index.album(it) }
            // A credit naming several artists ("A, B feat. C") offers each one; the song keeps the whole credit.
            artists = index.songArtists(song)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MenuLine(L.menuPlayNext) {
                PlayerConnection.playNext(song)
                onDismiss()
            }
            MenuLine(L.menuAddToQueue) {
                PlayerConnection.addToQueue(song)
                onDismiss()
            }
            favourite?.let { on ->
                MenuLine(if (on) L.menuUnfavourite else L.menuFavourite) {
                    scope.launch {
                        val done = runCatching { Library.index().setFavourite(song, !on) }.getOrDefault(true)
                        if (!done) android.widget.Toast.makeText(context, L.menuFavouritesFull, android.widget.Toast.LENGTH_LONG).show()
                        Library.notifyChanged()
                    }
                    onDismiss()
                }
            }
            MenuLine(L.menuAddToPlaylist, onAddToPlaylist)
            album?.let { MenuLine(L.menuGoToAlbum) { onAlbum(it) } }
            val only = artists.singleOrNull()
            if (only != null) {
                MenuLine(L.menuGoToArtist) { onArtist(only) }
            } else {
                for (artist in artists) MenuLine(L.menuGoToArtistNamed(artist.name.ifBlank { L.libraryUnknownArtist })) { onArtist(artist) }
            }
            MenuLine(if (song.versionCount > 1) L.libraryVersions(song.versionCount) else L.menuFileDetails, onVersions)
        }
    }
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/**
 * Asks for the notification permission once, the first time something plays (Android 13 and later).
 * Refused, playback still works; only the notification and the lock screen controls are missing.
 */
@Composable
private fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.notificationsAsked &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                prefs.notificationsAsked = true
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}

@Composable
private fun Overflow(onManage: () -> Unit, onSettings: () -> Unit, onLogOut: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(L.libraryMenu) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(L.manageTitle) }, onClick = {
                open = false
                onManage()
            })
            DropdownMenuItem(text = { Text(L.settingsTitle) }, onClick = {
                open = false
                onSettings()
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
 * What the library is doing while the scan or the tag reader runs: what and how far ("N of M"),
 * that listening goes on, and Pause or Resume. The bar is still (determinate), so nothing animates,
 * and the words change at most twice a second. Tap it for the Library section of Settings. Gone
 * when everything is up to date.
 */
@Composable
private fun ScanBar(onOpen: () -> Unit) {
    val flow = remember { Library.view() }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    val paused by Library.isPaused.collectAsStateWithLifecycle()
    val shown = view?.takeIf { it.showBanner } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shown.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    shown.sub?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                TextButton(onClick = { if (paused) Library.resume() else Library.pause() }) {
                    Text(if (paused) L.scanResume else L.scanPause)
                }
            }
            shown.progress?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }
    }
}
