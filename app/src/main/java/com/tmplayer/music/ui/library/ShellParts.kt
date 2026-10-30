package com.tmplayer.music.ui.library

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.R
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Song
import com.tmplayer.music.shell.HeaderOffset
import com.tmplayer.music.shell.LibraryShelf
import com.tmplayer.music.shell.MainTab
import com.tmplayer.music.shell.NavMotion
import com.tmplayer.music.ui.common.MusicIcons
import com.tmplayer.music.ui.player.PlayerSheetController
import com.tmplayer.music.ui.theme.Pill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop

/**
 * What the shell tells the screens inside it: a tap on the tab already shown (scroll to the top),
 * and the way to Manage chats for an empty list's next step.
 */
@Stable
class ShellSignals(val openManage: () -> Unit) {
    var reselect by mutableIntStateOf(0)
        internal set
}

val LocalShellSignals = staticCompositionLocalOf<ShellSignals?> { null }

/**
 * Runs [toTop] each time the tab or chip already shown is tapped again. Idle otherwise: the flow
 * waits on one counter that only a tap moves.
 */
@Composable
fun OnReselect(toTop: suspend () -> Unit) {
    val signals = LocalShellSignals.current ?: return
    LaunchedEffect(signals) {
        snapshotFlow { signals.reselect }.drop(1).collect {
            try {
                toTop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The list stays where it is.
            }
        }
    }
}

/** Scrolls [state] to its first row when the tab is tapped again. */
@Composable
fun ScrollToTopOnReselect(state: LazyListState) = OnReselect { state.animateScrollToItem(0) }

/**
 * A list whose rows load after its first frame loses its saved place: the first layout, with only
 * the header row, clamps it to the top. This takes the place before that layout and goes back to
 * it once [ready] (the rows are there).
 */
@Composable
fun RestoreScroll(state: LazyListState, ready: Boolean) {
    val pending = remember {
        Snapshot.withoutReadObservation { intArrayOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    LaunchedEffect(ready) {
        if (!ready || pending[0] <= 0) return@LaunchedEffect
        val index = pending[0]
        pending[0] = 0
        try {
            state.scrollToItem(index, pending[1])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Stays at the top.
        }
    }
}

/**
 * The header above the lists (top bar, scan banner, chip row) as one offset that the scroll moves:
 * scrolling down slides it away so the list reaches the status bar, scrolling up brings it back
 * (the bottom bar and the mini-player stay). The scroll moves it first, and only what it did not
 * take moves the list. Let go part way, it settles to shown or hidden ([HeaderOffset.settleTarget]),
 * a short animation that ends by itself; at rest nothing runs. No allocation per frame: one float.
 */
@Stable
class CollapsingHeader(private val settleSpec: AnimationSpec<Float>) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    /** The header's measured height in pixels, written while measuring (not state: no feedback loop). */
    internal var height = 0f

    /** Shows the header again (a tab or chip change, a page opened). */
    fun show() {
        offset = 0f
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val taken = HeaderOffset.consumed(offset, available.y, height)
        if (taken == 0f) return Offset.Zero
        offset = HeaderOffset.next(offset, available.y, height)
        return Offset(0f, taken)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val target = HeaderOffset.settleTarget(offset, height) ?: return Velocity.Zero
        animate(offset, target, animationSpec = settleSpec) { value, _ -> offset = value }
        return Velocity.Zero
    }
}

/**
 * The [header] above the [body], clipped to this box: the header moves up by the offset and the
 * body takes the height it leaves. The offset is read while measuring only, so a scroll relayouts
 * these two and recomposes nothing.
 */
@Composable
fun CollapsingLayout(
    state: CollapsingHeader,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(header, body),
        modifier = modifier.clipToBounds().nestedScroll(state),
    ) { (headerParts, bodyParts), constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val heads = headerParts.map { it.measure(loose) }
        val h = heads.sumOf { it.height }
        state.height = h.toFloat()
        val off = state.offset.coerceIn(-h.toFloat(), 0f).toInt()
        val shown = h + off
        val bodyHeight = (constraints.maxHeight - shown).coerceAtLeast(0)
        val bodies = bodyParts.map { it.measure(Constraints(0, constraints.maxWidth, 0, bodyHeight)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            bodies.forEach { it.place(0, shown) }
            var y = off
            heads.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/**
 * The top bar of the tabs: the app's mark (the launcher icon, small) and the word "Music", then
 * search and the account menu. On a page: Back and the page's title instead of the mark.
 */
@Composable
fun ShellTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    menu: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(MusicIcons.Back, contentDescription = L.commonBack) }
            Text(
                title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
        } else {
            Image(
                painterResource(R.drawable.ic_launcher_legacy),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                L.appShortName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() },
            )
        }
        if (onSearch != null) IconButton(onClick = onSearch) { Icon(MusicIcons.Search, contentDescription = L.searchTitle) }
        menu()
    }
}

/**
 * The account menu behind the overflow button: Settings, Manage chats, Send logs, About, Log out.
 * Its own composable so the avatar button (W15) can replace the button and keep the entries.
 */
@Composable
fun AccountMenu(
    onSettings: () -> Unit,
    onManage: () -> Unit,
    onSendLogs: () -> Unit,
    onAbout: () -> Unit,
    onLogOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(MusicIcons.More, contentDescription = L.shellAccountMenu) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item: @Composable (String, () -> Unit) -> Unit = { text, action ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    action()
                })
            }
            item(L.settingsTitle, onSettings)
            item(L.manageTitle, onManage)
            item(L.logsSend, onSendLogs)
            item(L.settingsAbout, onAbout)
            item(L.shellLogOut, onLogOut)
        }
    }
}

private fun tabLabel(tab: MainTab): String = when (tab) {
    MainTab.Home -> L.tabHome
    MainTab.Chats -> L.tabChats
    MainTab.Library -> L.tabLibrary
}

private fun tabIcon(tab: MainTab, selected: Boolean): ImageVector = when (tab) {
    MainTab.Home -> if (selected) MusicIcons.HomeFilled else MusicIcons.Home
    MainTab.Chats -> if (selected) MusicIcons.ChatsFilled else MusicIcons.Chats
    MainTab.Library -> if (selected) MusicIcons.LibraryFilled else MusicIcons.Library
}

/** One destination: the icon in a pill that fills when selected, the label under it. */
@Composable
private fun NavItem(tab: MainTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .clip(Pill)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainer)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tabIcon(tab, selected),
                contentDescription = null,
                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
        }
        Text(
            tabLabel(tab),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The bottom bar: Home, Chats, Library. It grows with the text (no fixed height, so labels at a
 * large font scale are never clipped) and reports its height to the player sheet, which rests the
 * mini-player on it. As Now Playing opens, the bar slides down out of the window in step with the
 * sheet (read while drawing only).
 */
@Composable
fun ShellBottomBar(selected: MainTab, sheet: PlayerSheetController?, onSelect: (MainTab) -> Unit) {
    if (sheet != null) {
        androidx.compose.runtime.DisposableEffect(sheet) { onDispose { sheet.bottomBarPx = 0 } }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { sheet?.bottomBarPx = it.height }
            .graphicsLayer {
                val p = sheet?.progress() ?: 0f
                translationY = NavMotion.barOffset(p, size.height)
                alpha = if (NavMotion.barGone(p)) 0f else 1f
            },
    ) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .selectableGroup(),
        ) {
            MainTab.entries.forEach { tab ->
                NavItem(tab, tab == selected, onClick = { onSelect(tab) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The same destinations as a rail on the left edge, for a window turned sideways (height is what
 * is short there). It slides out to its edge as Now Playing opens.
 */
@Composable
fun ShellRail(selected: MainTab, sheet: PlayerSheetController?, onSelect: (MainTab) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer {
                val shift = NavMotion.railOffset(sheet?.progress() ?: 0f, size.width)
                translationX = if (rtl) -shift else shift
            },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(88.dp)
                // A short window (split screen, large text) still reaches every item.
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainTab.entries.forEach { tab ->
                NavItem(tab, tab == selected, onClick = { onSelect(tab) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun shelfLabel(shelf: LibraryShelf): String = when (shelf) {
    LibraryShelf.Songs -> L.tabSongs
    LibraryShelf.Albums -> L.tabAlbums
    LibraryShelf.Artists -> L.tabArtists
    LibraryShelf.Playlists -> L.tabPlaylists
    LibraryShelf.Favourites -> L.tabFavourites
}

/**
 * The Library tab's chip row: Songs, Albums, Artists, Playlists, Favourites. It scrolls sideways
 * when the chips do not fit (large text, a narrow phone). On Albums and Artists the grid or list
 * toggle sits at its end.
 */
@Composable
fun LibraryChips(
    selected: LibraryShelf,
    onSelect: (LibraryShelf) -> Unit,
    grid: Boolean?,
    onGrid: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryShelf.entries.forEach { shelf ->
                FilterChip(
                    selected = shelf == selected,
                    onClick = { onSelect(shelf) },
                    label = { Text(shelfLabel(shelf), maxLines = 1) },
                    shape = Pill,
                )
            }
        }
        if (grid != null) {
            // Kept clear of the screen edge; the scrolling chips end before it.
            IconButton(onClick = { onGrid(!grid) }, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    if (grid) MusicIcons.ListRows else MusicIcons.Grid,
                    contentDescription = if (grid) L.libraryShowList else L.libraryShowGrid,
                )
            }
        }
    }
}

/**
 * Favourites: every favourite song, the latest first, to play from. Read whole up to
 * [FAVOURITES_SHOWN] (the index holds up to 20,000); past that a line says only the latest show.
 */
@Composable
fun FavouritesTab(actions: SongActions) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes) {
        try {
            songs = Library.index().favourites(FAVOURITES_SHOWN).distinctBy { it.groupKey }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
            if (songs == null) songs = emptyList()
        }
    }
    val state = rememberLazyListState()
    RestoreScroll(state, ready = songs != null)
    ScrollToTopOnReselect(state)
    val list = songs
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        if (list == null) return@LazyColumn
        if (list.isNotEmpty()) {
            item(key = "buttons") {
                PlayButtons({ list }) { all, shuffle -> if (shuffle) actions.shuffle(all) else actions.play(all, 0) }
            }
        }
        error?.let { message ->
            item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
        }
        if (list.isEmpty() && error == null) {
            item(key = "empty") {
                Text(L.libraryFavouritesEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
        }
        items(list, key = { it.groupKey }) { song ->
            SongRow(song, onClick = { actions.play(list, list.indexOf(song)) }, onLongClick = { actions.menu(song) })
        }
        if (list.size >= FAVOURITES_SHOWN) {
            item(key = "capped") {
                Text(
                    L.libraryFavouritesCapped(list.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

private const val FAVOURITES_SHOWN = 1_000

/** Room for the system bar inset under the content when nothing else (bar, mini-player) takes it. */
@Composable
fun NavigationInsetSpacer() {
    Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
}
