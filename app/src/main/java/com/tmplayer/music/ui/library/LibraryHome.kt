package com.tmplayer.music.ui.library

import com.tmplayer.music.ui.tv.tvFocusRing
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.tmplayer.music.ui.tv.FocusMemory
import com.tmplayer.music.ui.tv.FocusRescue
import com.tmplayer.music.ui.tv.LocalFocusScope
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.requestFocusSoon
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.LibraryStatus
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.diagnostics.rememberSendLogs
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.library.Playlist
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.shell.LibraryShelf
import com.tmplayer.music.shell.MainTab
import com.tmplayer.music.shell.NavLayout
import com.tmplayer.music.shell.ShellChoices
import com.tmplayer.music.ui.onboarding.ChatPicker
import com.tmplayer.music.ui.player.HidePlayerSheet
import com.tmplayer.music.ui.player.LocalPlayerSheet
import com.tmplayer.music.ui.player.PlayerSheetSpacer
import com.tmplayer.music.ui.theme.LocalReducedMotion
import com.tmplayer.music.ui.theme.MusicMotion
import kotlinx.coroutines.launch

/** A page opened over a tab. Back pops it; a tab tapped again goes back to the tab's top. */
internal sealed interface Page {
    data class AlbumPage(val album: Album) : Page
    data class ArtistPage(val artist: Artist) : Page
    data class ChatPage(val chat: ChatRow) : Page
    data class PlaylistPage(val playlist: Playlist) : Page
    data object About : Page
    data object Manage : Page
    data object Picker : Page
    data object Search : Page
    data object Settings : Page
}

/**
 * The open pages, kept by the activity across a rotation (they hold library rows, not saveable
 * values). Process death drops them and the shell opens on the tab it was on.
 */
internal class ShellModel : ViewModel() {
    val stack = mutableStateListOf<Page>()
}

private tailrec fun Context.owner(): ViewModelStoreOwner? = when (this) {
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.owner()
    else -> null
}

@Composable
private fun rememberShellModel(): ShellModel {
    val context = LocalContext.current
    return remember(context) {
        val owner = context.owner() ?: return@remember ShellModel()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellModel() as T
        }
        runCatching { ViewModelProvider(owner, factory)[ShellModel::class.java] }.getOrElse { ShellModel() }
    }
}

/**
 * The app's shell: Home, Chats and Library in a bottom bar (a rail on the left in a window turned
 * sideways), the top bar with the app's mark, search and the account menu, the pages opened from
 * the tabs, the scan banner, and the one time question about reading track details on mobile data.
 * The top bar, banner and Library chips scroll away with the list and come back on scrolling up.
 * Each tab and chip keeps its own scroll place (switching, rotation, process death); a tap on the
 * tab already shown goes back to its top. [startOnChats] opens on the Chats tab, for the first run,
 * where the chats just picked can be played before the scan has filled the others. [startTab], when
 * given, is the tab it opens on instead (the debug build's demo library).
 */
@Composable
fun LibraryHome(onLogOut: () -> Unit, startOnChats: Boolean = false, startTab: MainTab? = null) {
    val context = LocalContext.current
    val choices = remember { ShellChoices(Prefs(context)) }
    val model = rememberShellModel()
    val stack = model.stack
    val sheet = LocalPlayerSheet.current
    var tab by rememberSaveable { mutableStateOf(startTab ?: if (startOnChats) MainTab.Chats else MainTab.Home) }
    var shelf by rememberSaveable { mutableStateOf(choices.shelf) }
    var albumsGrid by rememberSaveable { mutableStateOf(choices.albumsGrid) }
    var artistsGrid by rememberSaveable { mutableStateOf(choices.artistsGrid) }
    var sort by rememberSaveable { mutableStateOf(SongSort.Title) }
    // Home is not ours to scroll (its list lives in Home.kt): a tap on Home again opens it afresh.
    var homeRound by rememberSaveable { mutableIntStateOf(0) }
    var askLogOut by remember { mutableStateOf(false) }
    var versionsOf by remember { mutableStateOf<Song?>(null) }
    var chatTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val changes by Library.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    // The settle animation follows the motion setting: a snap with animations removed.
    val motion = MusicMotion.current
    val header = remember(motion) { CollapsingHeader(motion.defaultEffects()) }
    val signals = remember {
        ShellSignals(openManage = {
            header.show()
            stack += Page.Manage
        })
    }
    val sendLogs = rememberSendLogs()
    val page = stack.lastOrNull()
    // A TV: tabs along the top, a header that stays, and the D-pad focus kept somewhere sensible.
    val tv = LocalTv.current
    val tabFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    var bodyHasFocus by remember { mutableStateOf(false) }
    val tabKey: (MainTab) -> String = { t ->
        when (t) {
            MainTab.Home -> "home:$homeRound"
            MainTab.Chats -> "chats"
            MainTab.Library -> when (shelf) {
                LibraryShelf.Albums -> "albums:$albumsGrid"
                LibraryShelf.Artists -> "artists:$artistsGrid"
                else -> "shelf:${shelf.name}"
            }
        }
    }
    val focusScopeOf: (Int) -> String = { depth ->
        stack.getOrNull(depth - 1)?.let { "page:$depth:${it.hashCode()}" } ?: tabKey(tab)
    }
    // Back one level: the page below (or the tab) shows again, with the D-pad on the row that opened it.
    val pop: () -> Unit = {
        header.show()
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            if (tv) FocusMemory.restore(focusScopeOf(stack.size))
        }
    }

    LaunchedEffect(changes) {
        chatTitles = runCatching { Library.index().pickedChats().associate { it.chatId to it.title } }.getOrDefault(chatTitles)
    }
    // Composed first and always, so a sheet opened later wins Back; it also steps aside while the
    // sheet is open, so Back collapses Now Playing before it leaves a page or a tab.
    BackHandler(enabled = (page != null || tab != MainTab.Home) && sheet?.expanded != true) {
        if (stack.isNotEmpty()) {
            pop()
        } else {
            header.show()
            tab = MainTab.Home
        }
    }
    if (tv) {
        DisposableEffect(bodyFocus) {
            FocusRescue.target = bodyFocus
            onDispose { if (FocusRescue.target === bodyFocus) FocusRescue.target = null }
        }
        // The first focus of each screen: a page opened, its first item; a tab, its tab in the top
        // bar (so Back to Home lands there too). A page closed puts the D-pad back on the row that
        // opened it ([FocusMemory]); if that row is gone, the first item in view, never the list's top.
        LaunchedEffect(page, tab) {
            val pending = FocusMemory.pending
            when {
                pending != null -> {
                    delay(RESTORE_WAIT_MS)
                    if (FocusMemory.pending == pending) {
                        FocusMemory.giveUp()
                        if (!bodyHasFocus) requestFocusSoon(bodyFocus) { bodyHasFocus }
                    }
                }
                page != null -> requestFocusSoon(bodyFocus) { bodyHasFocus }
                !bodyHasFocus -> requestFocusSoon(tabFocus)
            }
        }
    }
    var lastHomeRound by remember { mutableIntStateOf(homeRound) }
    LaunchedEffect(homeRound) {
        if (homeRound != lastHomeRound) {
            holder.removeState("home:$lastHomeRound")
            lastHomeRound = homeRound
        }
    }

    val select: (MainTab) -> Unit = { target ->
        header.show()
        when {
            target != tab -> {
                stack.clear()
                tab = target
            }
            stack.isNotEmpty() -> stack.clear()
            target == MainTab.Home -> homeRound++
            else -> signals.reselect++
        }
    }
    val pickShelf: (LibraryShelf) -> Unit = { target ->
        header.show()
        if (target == shelf) {
            signals.reselect++
        } else {
            shelf = target
            choices.shelf = target
        }
    }

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
                if (songs.isNotEmpty()) {
                    askNotifications()
                    PlayerConnection.play(songs, songs.indices.random(), shuffle = true)
                }
            },
            menu = { menuFor = it },
        )
    }
    val open: (Page) -> Unit = {
        header.show()
        stack += it
    }

    if (page == Page.Picker) {
        HidePlayerSheet()
        ChatPicker(
            directory = Library.directory,
            already = chatTitles.keys,
            onBack = pop,
            onDone = { picked ->
                scope.launch { runCatching { Library.pick(picked.map { ChatPick(it.chatId, it.title) }) } }
                pop()
            },
        )
        return
    }

    val topBar: @Composable () -> Unit = {
        ShellTopBar(
            title = when (page) {
                null -> null
                is Page.AlbumPage -> page.album.title
                is Page.ArtistPage -> page.artist.name.ifBlank { L.libraryUnknownArtist }
                is Page.ChatPage -> page.chat.title
                is Page.PlaylistPage -> page.playlist.name
                Page.About -> L.settingsAbout
                Page.Manage -> L.manageTitle
                Page.Picker -> ""
                Page.Search -> L.searchTitle
                Page.Settings -> L.settingsTitle
            },
            onBack = if (page != null) pop else null,
            onSearch = if (page != Page.Search) ({ open(Page.Search) }) else null,
            tabs = if (tv) ({ TvTabs(tab, select, tabFocus) }) else null,
        ) {
            AccountMenu(
                onSettings = { open(Page.Settings) },
                onManage = { open(Page.Manage) },
                onSendLogs = sendLogs,
                onAbout = { open(Page.About) },
                onLogOut = { askLogOut = true },
            )
        }
    }

    // A tab change fades through: the old tab fades out quickly, the new one fades in rising a few
    // dp. Each side keeps its own saved state (scroll place), so nothing jumps. Chips and pages
    // change in place. Snaps when animations are off.
    val reduced = LocalReducedMotion.current
    val riseByPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    val body: @Composable () -> Unit = {
        when (page) {
            null -> AnimatedContent(
                targetState = tab,
                transitionSpec = { tabFadeThrough(reduced, riseByPx) },
                label = "tabFadeThrough",
            ) { shownTab ->
                val key = tabKey(shownTab)
                // Each tab and chip keeps its own saved state (scroll place included), here and
                // after a rotation or process death.
                holder.SaveableStateProvider(key) {
                    RefreshBox(enabled = !tv) {
                        when (shownTab) {
                            MainTab.Home -> HomeTab(actions, onShuffleAll = { scope.launch { runCatching { actions.shuffle(allSongs()) } } })
                            MainTab.Chats -> ChatsTab(actions, onChat = { open(Page.ChatPage(it)) }, onAdd = { open(Page.Picker) })
                            MainTab.Library -> when (shelf) {
                                LibraryShelf.Songs -> SongsTab(sort, actions) {
                                    Column {
                                        SongsHeader(sort) { sort = it }
                                        PlayButtons({ allSongs() }) { list, shuffle -> if (shuffle) actions.shuffle(list) else actions.play(list, 0) }
                                    }
                                }
                                LibraryShelf.Albums -> AlbumsTab(grid = albumsGrid, onAlbum = { open(Page.AlbumPage(it)) }) {}
                                LibraryShelf.Artists -> ArtistsTab(grid = artistsGrid, onArtist = { open(Page.ArtistPage(it)) }) {}
                                LibraryShelf.Playlists -> PlaylistsTab(onPlaylist = { open(Page.PlaylistPage(it)) })
                                LibraryShelf.Favourites -> FavouritesTab(actions)
                            }
                        }
                    }
                }
            }
            is Page.AlbumPage -> AlbumScreen(page.album, actions)
            is Page.ArtistPage -> ArtistScreen(page.artist, actions, onAlbum = { open(Page.AlbumPage(it)) })
            is Page.ChatPage -> ChatScreen(page.chat, actions, onGone = { stack.remove(page) })
            is Page.PlaylistPage -> PlaylistScreen(page.playlist, actions, onDeleted = pop)
            Page.About -> AboutScreen()
            Page.Settings -> SettingsScreen(onManageChats = { open(Page.Manage) })
            Page.Search -> SearchScreen(actions, onAlbum = { open(Page.AlbumPage(it)) }, onArtist = { open(Page.ArtistPage(it)) })
            Page.Manage -> ManageChats(onAddChats = { open(Page.Picker) }, onChat = { open(Page.ChatPage(it)) })
            Page.Picker -> Unit
        }
    }

    CompositionLocalProvider(LocalShellSignals provides signals) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val rail = NavLayout.useRail(maxWidth.value, maxHeight.value)
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    if (rail && !tv) ShellRail(tab, sheet, select)
                    CollapsingLayout(
                        state = header,
                        modifier = Modifier.weight(1f).fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                        header = {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.fillMaxWidth()) {
                                    topBar()
                                    if (page != Page.Settings) ScanBar(onOpen = { open(Page.Settings) })
                                    if (page == null && tab == MainTab.Library) {
                                        LibraryChips(
                                            selected = shelf,
                                            onSelect = pickShelf,
                                            grid = when (shelf) {
                                                LibraryShelf.Albums -> albumsGrid
                                                LibraryShelf.Artists -> artistsGrid
                                                else -> null
                                            },
                                            onGrid = { on ->
                                                if (shelf == LibraryShelf.Albums) {
                                                    albumsGrid = on
                                                    choices.albumsGrid = on
                                                } else {
                                                    artistsGrid = on
                                                    choices.artistsGrid = on
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        body = {
                            // One focus group for the page or tab under the header: a page opened
                            // asks it for focus (its first item), and its rows remember their place.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .focusRequester(bodyFocus)
                                    .onFocusChanged { bodyHasFocus = it.hasFocus }
                                    .focusGroup(),
                            ) {
                                CompositionLocalProvider(LocalFocusScope provides focusScopeOf(stack.size)) { body() }
                            }
                        },
                        collapse = !tv,
                    )
                }
                PlayerSheetSpacer(aboveBar = !rail && !tv)
                if (!rail && !tv) {
                    ShellBottomBar(tab, sheet, select)
                } else if (sheet?.showing != true) {
                    NavigationInsetSpacer()
                }
            }
        }
    }

    if (askLogOut) {
        AlertDialog(
            onDismissRequest = { askLogOut = false },
            title = { Text(L.shellLogOutTitle) },
            text = { Text(L.shellLogOutBody) },
            confirmButton = {
                TextButton(onClick = {
                    askLogOut = false
                    stack.clear()
                    onLogOut()
                }) { Text(L.shellLogOut) }
            },
            dismissButton = { TextButton(onClick = { askLogOut = false }) { Text(L.commonCancel) } },
        )
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
                open(Page.AlbumPage(album))
            },
            onArtist = { artist ->
                menuFor = null
                open(Page.ArtistPage(artist))
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
private fun RefreshBox(enabled: Boolean = true, content: @Composable () -> Unit) {
    // Pull to refresh is touch only: a TV checks for new tracks from Settings and Manage chats.
    if (!enabled) {
        Box(Modifier.fillMaxSize()) { content() }
        return
    }
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
        // Scrolls in a window turned sideways, where the lines do not all fit.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
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
            TextButton(onClick = { open = true }, modifier = Modifier.tvFocusRing()) { Text(sortName(sort)) }
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
 * when everything is up to date. Hide turns the progress off for good (Settings brings it back);
 * chats that could not be read still show, as a line in the error colour ([LibraryStatus.banner]).
 */
@Composable
private fun ScanBar(onOpen: () -> Unit) {
    val flow = remember { Library.view() }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    val paused by Library.isPaused.collectAsStateWithLifecycle()
    val showProgress by Library.choices.showProgress.collectAsStateWithLifecycle()
    val mode = LibraryStatus.banner(view, showProgress)
    val shown = view ?: return
    if (mode == LibraryStatus.Banner.None) return
    // An inset card in surfaceContainer, one small step from the page, not a full-width slab in an
    // accent container: the progress and the buttons carry the accent. Errors keep their own colour.
    val card = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    if (mode == LibraryStatus.Banner.Attention) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = card.clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(L.scanFailedChats(shown.failedChats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(L.scanFailedChatsSub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = card.clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shown.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    shown.sub?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { if (paused) Library.resume() else Library.pause() }) {
                    Text(if (paused) L.scanResume else L.scanPause)
                }
                TextButton(
                    onClick = { Library.choices.setShowProgress(false) },
                    modifier = Modifier.semantics { contentDescription = L.scanHideLabel },
                ) { Text(L.scanHide) }
            }
            shown.progress?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }
    }
}

/** How long a closed page's row has to take focus back before the shell gives the page its first item. */
private const val RESTORE_WAIT_MS = 600L

/** The tab change: out in 90 ms, in over 200 ms from [riseByPx] lower; none with animations off. */
private fun tabFadeThrough(reduced: Boolean, riseByPx: Int): ContentTransform =
    if (reduced) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        (fadeIn(tween(200, delayMillis = 40)) + slideInVertically(tween(200)) { riseByPx }) togetherWith fadeOut(tween(90))
    }
