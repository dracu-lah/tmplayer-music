package com.tmplayer.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Account
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.LibraryPhase
import com.tmplayer.music.desktop.DesktopApp
import com.tmplayer.music.desktop.player.AudioSurface
import com.tmplayer.music.desktop.ui.common.BackHandler
import com.tmplayer.music.desktop.ui.common.ChatAvatar
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.common.ShellCommands
import com.tmplayer.music.desktop.ui.library.AddToPlaylistDialog
import com.tmplayer.music.desktop.ui.library.AlbumPage
import com.tmplayer.music.desktop.ui.library.Favourites
import com.tmplayer.music.desktop.ui.library.FavouritesPage
import com.tmplayer.music.desktop.ui.library.HomeScreen
import com.tmplayer.music.desktop.ui.library.KeepOfflineHost
import com.tmplayer.music.desktop.ui.library.LocalNotify
import com.tmplayer.music.desktop.ui.library.OfflineUi
import com.tmplayer.music.desktop.ui.library.PlaylistPage
import com.tmplayer.music.desktop.ui.library.PlaylistsScreen
import com.tmplayer.music.desktop.ui.library.SettingsScreen
import com.tmplayer.music.desktop.ui.library.AlbumsScreen
import com.tmplayer.music.desktop.ui.library.ArtistPage
import com.tmplayer.music.desktop.ui.library.ArtistsScreen
import com.tmplayer.music.desktop.ui.library.ChatPicker
import com.tmplayer.music.desktop.ui.library.ChatScreen
import com.tmplayer.music.desktop.ui.library.LocalPlayingMark
import com.tmplayer.music.desktop.ui.library.Navigator
import com.tmplayer.music.desktop.ui.library.PlayingMark
import com.tmplayer.music.desktop.ui.library.Route
import com.tmplayer.music.desktop.ui.library.SearchScreen
import com.tmplayer.music.desktop.ui.library.ShuffleAll
import com.tmplayer.music.desktop.ui.library.SongActions
import com.tmplayer.music.desktop.ui.library.SongsScreen
import com.tmplayer.music.desktop.ui.library.TrackActions
import com.tmplayer.music.desktop.ui.library.VersionsDialog
import com.tmplayer.music.desktop.ui.player.LyricsPanel
import com.tmplayer.music.desktop.ui.player.NowPlayingBar
import com.tmplayer.music.desktop.ui.player.Panel
import com.tmplayer.music.desktop.ui.player.SoundPanel
import com.tmplayer.music.desktop.ui.player.PlayerErrorStrip
import com.tmplayer.music.desktop.ui.player.QueuePanel
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.platform.JobFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The signed in app: a sidebar on the left, the page in the middle, the now playing bar along the
 * bottom, and the queue as a panel over the page's right edge. It works from 900 by 600 up; the
 * page uses the width it is given (an album column appears in the lists when there is room, the
 * grids add columns).
 */
@Composable
fun Shell(onLogOut: () -> Unit) {
    val player = DesktopApp.player
    val nav = remember { Navigator(Route.Home) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sort by remember { mutableStateOf(sortFromPrefs()) }
    var versionsFor by remember { mutableStateOf<Song?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var addingTo by remember { mutableStateOf<List<Song>?>(null) }
    var managing by remember { mutableStateOf(false) }
    var searchToken by remember { mutableIntStateOf(0) }
    var confirmLogOut by remember { mutableStateOf(false) }

    val failures = remember { JobFailures.handler("Shell") }
    /** Runs [block] off the click, telling the user in a line if it fails. */
    fun guarded(block: suspend () -> Unit) {
        scope.launch(failures) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(Failures.humanise(e))
            }
        }
    }

    // The line Keep offline and the playlists say, in the snackbar; and the hearts, kept fresh.
    val notify: (String) -> Unit = { text -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    LaunchedEffect(Unit) { OfflineUi.notify = notify }
    val changes by Library.changes.collectAsState()
    LaunchedEffect(changes) { Favourites.refresh() }

    // Ctrl+F: the search page, with the keyboard in its field.
    LaunchedEffect(Unit) {
        ShellCommands.search.collect {
            nav.open(Route.Search)
            searchToken++
        }
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    val actions = remember {
        SongActions(
            play = { songs, start -> player.play(songs, start) },
            playNext = { songs -> player.playNext(songs) },
            addToQueue = { songs -> player.addToQueue(songs) },
            shuffle = { songs -> if (songs.isNotEmpty()) player.play(songs, songs.indices.random(), shuffle = true) },
            versions = { versionsFor = it },
            favourite = { songs, on ->
                guarded {
                    if (!Favourites.set(songs, on) && on) snackbar.showSnackbar(L.menuFavouritesFull)
                }
            },
            isFavourite = { Favourites.has(it) },
            addToPlaylist = { songs -> addingTo = songs },
            keepOffline = { songs -> OfflineUi.keepSongs(songs) },
            openAlbum = { song ->
                guarded { Library.index().album(song.albumKey)?.let { nav.push(Route.AlbumPage(it)) } }
            },
            openArtist = { song ->
                guarded { Library.index().artist(song.artistKey)?.let { nav.push(Route.ArtistPage(it)) } }
            },
        )
    }
    val trackActions = remember {
        TrackActions(
            play = { tracks, start -> player.playTracks(tracks, start) },
            shuffle = { tracks -> if (tracks.isNotEmpty()) player.playTracks(tracks, tracks.indices.random(), shuffle = true) },
        )
    }
    val playAll: (SongSort, Boolean) -> Unit = { by, shuffle ->
        guarded {
            val index = Library.index()
            val total = index.songCount()
            val songs = if (shuffle && total > ShuffleAll.QUEUE) {
                ShuffleAll.offsets(total).flatMap { index.songsAt(by, it, ShuffleAll.limit(total)) }.distinctBy { it.groupKey }
            } else {
                index.songs(by, null, ShuffleAll.QUEUE)
            }
            if (songs.isNotEmpty()) player.play(songs, if (shuffle) songs.indices.random() else 0, shuffle)
        }
    }

    val homePlay: (Boolean) -> Unit = { shuffle -> playAll(SongSort.Title, shuffle) }

    val mark by remember {
        player.ui.map { u -> u.current?.let { PlayingMark(it.songKey, it.id, u.playing) } }.distinctUntilChanged()
    }.collectAsState(null)

    val engine by player.engineFlow.collectAsState()
    CompositionLocalProvider(LocalPlayingMark provides mark, LocalNotify provides notify) {
        // libmpv loads nothing without a render surface attached, picture or not.
        Box(Modifier.size(1.dp)) { AudioSurface(engine) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(
                    nav = nav,
                    onManage = { managing = true },
                    onLogOut = { confirmLogOut = true },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val route = nav.current) {
                        Route.Home -> HomeScreen(player, actions = actions, onPlayAll = homePlay, onManage = { managing = true })
                        Route.Playlists -> PlaylistsScreen(onOpen = { nav.push(Route.PlaylistPage(it)) })
                        Route.Favourites -> FavouritesPage(actions)
                        Route.Settings -> SettingsScreen(onManageChats = { managing = true }, onLogOut = onLogOut)
                        is Route.PlaylistPage -> PlaylistPage(route.playlist, actions, onGone = { nav.open(Route.Playlists) })
                        Route.Songs -> SongsScreen(
                            sort = sort,
                            onSort = {
                                sort = it
                                DesktopApp.prefs.putString(SORT_KEY, it.name)
                            },
                            actions = actions,
                            onPlayAll = playAll,
                            onManage = { managing = true },
                        )
                        Route.Albums -> AlbumsScreen(onAlbum = { nav.push(Route.AlbumPage(it)) }, onManage = { managing = true })
                        Route.Artists -> ArtistsScreen(onArtist = { nav.push(Route.ArtistPage(it)) }, onManage = { managing = true })
                        Route.Search -> SearchScreen(
                            focusToken = searchToken,
                            actions = actions,
                            onAlbum = { nav.push(Route.AlbumPage(it)) },
                            onArtist = { nav.push(Route.ArtistPage(it)) },
                        )
                        is Route.Chat -> ChatScreen(route.chatId, route.title, trackActions, onGone = { nav.open(Route.Songs) })
                        is Route.AlbumPage -> AlbumPage(route.album, actions)
                        is Route.ArtistPage -> ArtistPage(route.artist, actions, onAlbum = { nav.push(Route.AlbumPage(it)) })
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    when (panel) {
                        Panel.Queue -> QueuePanel(player, onClose = { panel = null }, modifier = Modifier.align(Alignment.CenterEnd))
                        Panel.Lyrics -> LyricsPanel(player, onClose = { panel = null }, modifier = Modifier.align(Alignment.CenterEnd))
                        Panel.Sound -> SoundPanel(player, onClose = { panel = null }, modifier = Modifier.align(Alignment.CenterEnd))
                        null -> Unit
                    }
                    if (managing) ManageChats(onClose = { managing = false })
                }
            }
            PlayerErrorStrip(player)
            NowPlayingBar(player, open = panel, onToggle = { panel = if (panel == it) null else it })
        }
        versionsFor?.let { song ->
            VersionsDialog(
                song = song,
                onPlay = {
                    player.playVersion(song, it)
                    versionsFor = null
                },
                onDismiss = { versionsFor = null },
            )
        }
        KeepOfflineHost()
        addingTo?.let { songs ->
            AddToPlaylistDialog(songs) { result ->
                addingTo = null
                result?.let(notify)
            }
        }
        if (confirmLogOut) {
            AlertDialog(
                onDismissRequest = { confirmLogOut = false },
                title = { Text(L.shellLogOutTitle) },
                text = { Text(L.shellLogOutBody) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogOut = false
                        onLogOut()
                    }) { Text(L.shellLogOut) }
                },
                dismissButton = { TextButton(onClick = { confirmLogOut = false }) { Text(L.commonCancel) } },
            )
        }
    }
}

private const val SORT_KEY = "songs_sort"

private fun sortFromPrefs(): SongSort =
    DesktopApp.prefs.getString(SORT_KEY)?.let { name -> SongSort.entries.firstOrNull { it.name == name } } ?: SongSort.Title

/** Manage chats: the picker again, over the page, with the chats already chosen ticked. */
@Composable
private fun ManageChats(onClose: () -> Unit) {
    val picked by produceState<Set<Long>?>(null) {
        value = runCatching { Library.index().pickedChats().map { it.chatId }.toSet() }.getOrDefault(emptySet())
    }
    BackHandler(onBack = onClose)
    val already = picked ?: return
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ChatPicker(
            directory = Library.directory,
            already = already,
            onDone = { chosen ->
                scope.launch(JobFailures.handler("ManageChats")) {
                    runCatching { Library.pick(chosen.map { ChatPick(it.chatId, it.title) }) }
                    onClose()
                }
            },
            onBack = onClose,
        )
    }
}

/** The left column: where to go, the chats that were picked, what the library is doing, and the account. */
@Composable
private fun Sidebar(nav: Navigator, onManage: () -> Unit, onLogOut: () -> Unit) {
    val changes by Library.changes.collectAsState()
    val chats by produceState(emptyList(), changes) {
        value = runCatching { Library.index().pickedChats() }.getOrDefault(value)
    }
    val current = nav.current.tab
    Column(
        Modifier.width(SIDEBAR_WIDTH).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(vertical = 12.dp),
    ) {
        Text(L.appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SideItem(MusicIcons.Home, L.tabHome, current == Route.Home) { nav.open(Route.Home) }
        SideItem(MusicIcons.Search, L.searchTitle, current == Route.Search, hint = "Ctrl+F") { nav.open(Route.Search) }
        SideItem(MusicIcons.Library, L.tabSongs, current == Route.Songs) { nav.open(Route.Songs) }
        SideItem(MusicIcons.Grid, L.tabAlbums, current == Route.Albums) { nav.open(Route.Albums) }
        SideItem(MusicIcons.Person, L.tabArtists, current == Route.Artists) { nav.open(Route.Artists) }
        SideItem(MusicIcons.ListRows, L.tabPlaylists, current == Route.Playlists) { nav.open(Route.Playlists) }
        SideItem(MusicIcons.Heart, L.tabFavourites, current == Route.Favourites) { nav.open(Route.Favourites) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L.tabChats, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(chats, key = { it.chatId }) { chat ->
                val selected = (nav.current as? Route.Chat)?.chatId == chat.chatId
                ChatItem(chat.chatId, chat.title.ifBlank { L.pickerUntitled }, chat.trackCount, selected) {
                    nav.open(Route.Chat(chat.chatId, chat.title.ifBlank { L.pickerUntitled }))
                }
            }
        }
        ScanStatus()
        SideItem(MusicIcons.Plus, L.manageTitle, false, onClick = onManage)
        SideItem(MusicIcons.Settings, L.settingsTitle, current == Route.Settings) { nav.open(Route.Settings) }
        AccountItem(onLogOut)
    }
}

private val SIDEBAR_WIDTH = 232.dp

@Composable
private fun SideItem(icon: ImageVector, label: String, selected: Boolean, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(40.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatItem(chatId: Long, title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(44.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatAvatar(chatId = chatId, name = title, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the scan and the tag reader are doing, as a line and a bar; nothing once everything is read. */
@Composable
private fun ScanStatus() {
    val view by remember { Library.view() }.collectAsState(null)
    val shown = view?.takeIf { it.showBanner || it.failedChats > 0 } ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (shown.phase == LibraryPhase.UpToDate) L.scanFailedChats(shown.failedChats) else shown.line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shown.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        if (shown.progress == null && shown.phase != LibraryPhase.Paused && shown.phase != LibraryPhase.Waiting) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AccountItem(onLogOut: () -> Unit) {
    val profile by Account.profile.collectAsState()
    LaunchedEffect(Unit) { runCatching { Account.load() } }
    var open by remember { mutableStateOf(false) }
    Box {
        SideItem(MusicIcons.Person, profile?.name?.ifBlank { null } ?: L.shellAccountUnknown, false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(L.shellLogOut) }, onClick = {
                open = false
                onLogOut()
            })
        }
    }
}
