package com.tmplayer.music.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.contentDescription
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
import com.tmplayer.music.data.Account
import com.tmplayer.music.data.AccountProfile
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
import com.tmplayer.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
 * The account menu behind the signed-in user's avatar (W15): a header with the name, the username
 * if there is one and "Signed in", then Settings, Manage chats, Send logs, About, Log out (which asks
 * first, in the shell). The profile is read once per session ([Account.load]); until then, or if it
 * cannot be read, the avatar is a neutral tile. TalkBack reads the button as "Account and settings".
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
    val profile by Account.profile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Account.load() }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = L.shellAccountMenu }) {
            AccountAvatar(profile, size = 32.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AccountHeader(profile)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
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

/** Who is signed in, at the top of the account menu: read as one item by TalkBack, not a button. */
@Composable
private fun AccountHeader(profile: AccountProfile?) {
    val name = profile?.name?.takeIf { it.isNotBlank() } ?: L.shellAccountUnknown
    Row(
        Modifier
            .widthIn(min = 200.dp, max = 300.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountAvatar(profile, size = 40.dp)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            profile?.username?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "@$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(L.shellSignedIn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

/**
 * One destination, with nothing behind it (no pill, ripple or pressed tint): the chosen tab is a
 * filled white icon over a bold white label, the others an outline and a medium label in muted grey.
 *
 * Choosing it plays TMPlayer's bar "drop", adapted: the glyph dips to [NavFill.DIP] and springs back
 * a touch past its size, while the filled glyph fades in over the outline on an overlapping ramp
 * (fill in over the first 70%, outline out over the last 70%, [NavFill.IN_MS] in all) and the label
 * turns white and bold in step. Leaving it drains back to the outline quicker ([NavFill.OUT_MS]). The
 * first draw does not animate, nothing runs at rest, and with animations off it snaps. The fill and
 * scale are read while drawing only; the label reads the level, so it recomposes during the 280 ms.
 *
 * Keyboard focus (never touch, which does not focus here) draws a ring around the item.
 */
@Composable
private fun NavItem(tab: MainTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val press = remember { MutableInteractionSource() }
    val focused by press.collectIsFocusedAsState()
    val level = remember { Animatable(if (selected) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(selected, reduced) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        val target = if (selected) 1f else 0f
        if (reduced) {
            level.snapTo(target)
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        if (selected) {
            coroutineScope {
                launch { level.animateTo(1f, tween(NavFill.IN_MS, easing = FastOutSlowInEasing)) }
                launch {
                    scale.animateTo(NavFill.DIP, tween(NavFill.DIP_MS))
                    scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
                }
            }
        } else {
            scale.snapTo(1f)
            level.animateTo(0f, tween(NavFill.OUT_MS))
        }
    }
    val ring = colors.onSurface
    Column(
        modifier
            .selectable(selected = selected, interactionSource = press, indication = null, role = Role.Tab, onClick = onClick)
            .drawWithContent {
                drawContent()
                if (focused) {
                    val inset = 4.dp.toPx()
                    drawRoundRect(
                        ring,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tabIcon(tab, selected = false),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = 1f - ((level.value - 0.3f) / 0.7f).coerceIn(0f, 1f) },
            )
            Icon(
                tabIcon(tab, selected = true),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.graphicsLayer { alpha = (level.value / 0.7f).coerceIn(0f, 1f) },
            )
        }
        val t = level.value
        Text(
            tabLabel(tab),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (t >= 0.5f) FontWeight.Bold else FontWeight.Medium,
            color = androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.primary, t),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** The figures of [NavItem]'s fill, from TMPlayer's SelectableIcon "drop". */
private object NavFill {
    const val IN_MS = 280
    const val OUT_MS = 140
    const val DIP = 0.86f
    const val DIP_MS = 90
}

/**
 * The bottom bar: Home, Chats, Library. It grows with the text (no fixed height, so labels at a
 * large font scale are never clipped) and reports its height to the player sheet, which rests the
 * mini-player on it. As Now Playing opens, the bar slides down out of the window in step with the
 * sheet (read while drawing only).
 *
 * It is the page's own tone (`background`, black), not a container, so it never reads as a separate
 * slab. With no mini-player on it, a hairline marks its top; with one, the mini-player's edge does.
 */
@Composable
fun ShellBottomBar(selected: MainTab, sheet: PlayerSheetController?, onSelect: (MainTab) -> Unit) {
    if (sheet != null) {
        androidx.compose.runtime.DisposableEffect(sheet) { onDispose { sheet.bottomBarPx = 0 } }
    }
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { sheet?.bottomBarPx = it.height }
            .graphicsLayer {
                val p = sheet?.progress() ?: 0f
                translationY = NavMotion.barOffset(p, size.height)
                alpha = if (NavMotion.barGone(p)) 0f else 1f
            }
            .drawWithContent {
                drawContent()
                if (sheet?.showing != true) drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
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
    // The page's tone, like the bottom bar: the rail is part of the page, not a panel beside it.
    Surface(
        color = MaterialTheme.colorScheme.background,
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
