// The song list and the A to Z bar follow the Android app's Songs.kt (app/.../ui/library/Songs.kt),
// laid out for a mouse and a keyboard.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.TagEnricher
import com.tmplayer.music.desktop.ui.common.Badge
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.ErrorPanel
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.HeldKeys
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.common.OnSelectAll
import com.tmplayer.music.desktop.ui.common.PlayingGlyph
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongCursor
import com.tmplayer.music.library.SongSort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** The song or file that is current, read by every row that draws the playing mark. */
val LocalPlayingMark = compositionLocalOf<PlayingMark?> { null }

/** Songs: every song of every chosen chat in one list, repeats folded into versions. */
@Composable
fun SongsScreen(sort: SongSort, onSort: (SongSort) -> Unit, actions: SongActions, onPlayAll: (SongSort, Boolean) -> Unit, onManage: () -> Unit) {
    val state = rememberLazyListState()
    val pager = rememberPager<SongCursor, Song>(
        key = sort,
        state = state,
        load = { after, limit -> Library.index().songs(sort, after, limit) },
        cursorOf = { SongCursor.of(it, sort) },
        loadAt = { offset, limit -> Library.index().songsAt(sort, offset, limit) },
        loadBefore = { before, limit -> Library.index().songsBefore(sort, before, limit) },
    )
    val songs = pager.items
    var selection by remember(sort) { mutableStateOf(ListSelection<String>()) }
    val keys = songs.map { it.groupKey }
    LaunchedEffect(keys) { selection = selection.prune(keys) }
    OnSelectAll { selection = selection.selectAll(songs.map { it.groupKey }) }
    ReadVisibleFirst(pager, state)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = L.tabSongs,
            subtitle = null,
            actions = {
                Button(onClick = { onPlayAll(sort, false) }, enabled = songs.isNotEmpty()) { Text(L.homePlayAll) }
                FilledTonalButton(onClick = { onPlayAll(sort, true) }, enabled = songs.isNotEmpty()) { Text(L.homeShuffleAll) }
            },
        )
        SortChips(sort, onSort)
        SelectionBar(selection, songs, actions) { selection = it }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= WIDE_AT
                    SongTable(songs, state, actions, selection, wide) { selection = it }
                    if (pager.loaded && songs.isEmpty() && pager.error == null) EmptyState(L.libraryEmpty, Modifier.align(Alignment.TopCenter)) {
                        FilledTonalButton(onClick = onManage) { Text(L.manageTitle) }
                    }
                }
                pager.error?.let { ErrorPanel(it, onRetry = null, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
            // The bar has its own column, so it never covers a row's title or format badge.
            if (sort != SongSort.Added) LetterBar(sort, pager, state, Modifier.fillMaxHeight())
        }
    }
}

/** The page title, its subtitle and the buttons to its right. */
@Composable
fun ScreenHeader(title: String, subtitle: String?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
private fun SortChips(sort: SongSort, onSort: (SongSort) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in SongSort.entries) {
            FilterChip(
                selected = option == sort,
                onClick = { onSort(option) },
                label = { Text(sortLabel(option)) },
                shape = com.tmplayer.music.desktop.ui.theme.Pill,
            )
        }
    }
}

fun sortLabel(sort: SongSort): String = when (sort) {
    SongSort.Title -> L.sortTitle
    SongSort.Artist -> L.sortArtist
    SongSort.Album -> L.sortAlbum
    SongSort.Added -> L.sortAdded
}

/** The bar over the list while rows are selected: how many, and what to do with them. */
@Composable
fun SelectionBar(selection: ListSelection<String>, songs: List<Song>, actions: SongActions, onChange: (ListSelection<String>) -> Unit) {
    if (selection.isEmpty) return
    val chosen = selection.inOrder(songs.map { it.groupKey }).mapNotNull { key -> songs.firstOrNull { it.groupKey == key } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(L.desktopSelected(selection.count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (chosen.isNotEmpty()) actions.play(chosen, 0) }) { Text(L.desktopPlaySelected) }
        TextButton(onClick = { actions.playNext(chosen) }) { Text(L.menuPlayNext) }
        TextButton(onClick = { actions.addToQueue(chosen) }) { Text(L.menuAddToQueue) }
        TextButton(onClick = { onChange(selection.clear()) }) { Text(L.desktopSelectionClear) }
    }
}

/** The rows of [songs] as a list with the selection, the playing mark and the right click menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongTable(
    songs: List<Song>,
    state: LazyListState,
    actions: SongActions,
    selection: ListSelection<String>,
    wide: Boolean,
    extra: LazyListScope.() -> Unit = {},
    onSelection: (ListSelection<String>) -> Unit,
) {
    val order = songs.map { it.groupKey }
    val mark = LocalPlayingMark.current
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize().onKeyEvent { e ->
            // Enter plays the selected rows, the first of them first.
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !selection.isEmpty) {
                val chosen = selection.inOrder(order).mapNotNull { k -> songs.firstOrNull { it.groupKey == k } }
                if (chosen.isNotEmpty()) actions.play(chosen, 0)
                true
            } else {
                false
            }
        },
    ) {
        itemsIndexed(songs, key = { _, song -> song.groupKey }) { index, song ->
            val picked = song.groupKey in selection
            ContextMenuArea(items = {
                // A right click on a row outside the selection selects it first, as every file manager does.
                val now = if (picked) selection else ListSelection(setOf(song.groupKey), song.groupKey).also(onSelection)
                val chosen = now.inOrder(order).mapNotNull { k -> songs.firstOrNull { it.groupKey == k } }.ifEmpty { listOf(song) }
                songMenu(chosen, actions)
            }) {
                SongRow(
                    song = song,
                    selected = picked,
                    mark = mark?.forSong(song.groupKey, song.defaultTrack),
                    showAlbum = wide,
                    onClick = {
                        val double = DoubleClicks.isDouble(song.groupKey)
                        if (double && !HeldKeys.ctrl && !HeldKeys.shift) {
                            actions.play(songs, index)
                        } else {
                            onSelection(selection.click(song.groupKey, order, ctrl = HeldKeys.ctrl, shift = HeldKeys.shift))
                        }
                    },
                    onVersions = { actions.versions(song) },
                )
            }
        }
        extra()
    }
}

private fun songMenu(chosen: List<Song>, actions: SongActions): List<ContextMenuItem> {
    val one = chosen.singleOrNull()
    return buildList {
        add(ContextMenuItem(L.playerPlay) { actions.play(chosen, 0) })
        add(ContextMenuItem(L.menuPlayNext) { actions.playNext(chosen) })
        add(ContextMenuItem(L.menuAddToQueue) { actions.addToQueue(chosen) })
        if (one != null && one.versionCount > 1) add(ContextMenuItem(L.libraryVersions(one.versionCount)) { actions.versions(one) })
        if (one != null && one.albumKey.isNotBlank()) add(ContextMenuItem(L.menuGoToAlbum) { actions.openAlbum(one) })
        if (one != null && one.artistKey.isNotBlank()) add(ContextMenuItem(L.menuGoToArtist) { actions.openArtist(one) })
    }
}

/** Two clicks on the same row within [WINDOW_MS] make a double click; the first of them already selected the row. */
internal object DoubleClicks {
    private const val WINDOW_MS = 400L
    private var lastKey: Any? = null
    private var lastAt = 0L

    fun isDouble(key: Any, now: Long = System.currentTimeMillis()): Boolean {
        val double = key == lastKey && now - lastAt <= WINDOW_MS
        lastKey = if (double) null else key
        lastAt = now
        return double
    }
}

@Composable
fun SongRow(
    song: Song,
    selected: Boolean,
    mark: Boolean?,
    showAlbum: Boolean,
    onClick: () -> Unit,
    onVersions: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 12.dp)
            .background(container, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            name = song.title,
            fileId = 0,
            size = 40.dp,
            coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(),
            track = song.defaultTrack.takeIf { song.thumbFileId != 0 },
            art = CoverArt.keyOf(song.albumKey, song.groupKey),
        )
        Column(Modifier.weight(if (showAlbum) 1.4f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mark != null) PlayingGlyph(playing = mark, modifier = Modifier.padding(end = 6.dp))
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mark != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (mark != null) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                ArtistDisplay.of(song.artist).ifBlank { L.libraryUnknownArtist },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAlbum) {
            Text(
                song.album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // A column of fixed width, so the album column above lines up from row to row.
        Row(
            if (showAlbum) Modifier.width(BADGES_WIDTH) else Modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (song.variant.isNotBlank()) Badge(song.variant)
            if (song.bestFormat.lossless) Badge(song.bestFormat.label)
            if (song.versionCount > 1) {
                TextButton(onClick = onVersions) { Text(L.libraryVersions(song.versionCount), style = MaterialTheme.typography.labelMedium) }
            }
        }
        Text(
            Format.duration(song.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
    }
}

/** The rows on screen go to the front of the tag reader's queue once the scroll has settled. */
@Composable
private fun ReadVisibleFirst(pager: Pager<SongCursor, Song>, state: LazyListState) {
    LaunchedEffect(pager, state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collectLatest { first ->
            delay(VISIBLE_SETTLE_MS)
            val from = first.coerceAtLeast(0)
            runCatching { Library.enricher.prefer(pager.items.toList().drop(from).take(TagEnricher.BATCH).map { it.defaultTrack }) }
        }
    }
}

/**
 * The A to Z bar at the edge of Songs: each first letter in the list. Click a letter, or drag along
 * the bar, to go there. A jump reads one page around the letter's row ([Pager.jumpTo]), however far
 * down it is; the bar hides itself when the list has fewer than [LETTER_BAR_MIN] rows.
 */
@Composable
private fun LetterBar(sort: SongSort, pager: Pager<SongCursor, Song>, state: LazyListState, modifier: Modifier) {
    val changes by Library.changes.collectAsState()
    var letters by remember { mutableStateOf<List<Pair<Char, Int>>>(emptyList()) }
    LaunchedEffect(sort, changes) {
        letters = runCatching { Library.index().letterIndex(sort) }.getOrDefault(letters)
    }
    val total = letters.lastOrNull()?.second ?: 0
    if (letters.size < 2 || total < LETTER_BAR_MIN) return
    // One jump at a time; a drag that moves on cancels the jump it left behind.
    val target = remember { MutableStateFlow<Int?>(null) }
    LaunchedEffect(pager, state) {
        target.collectLatest { position ->
            if (position == null) return@collectLatest
            val index = pager.jumpTo(position)
            state.scrollToItem(index.coerceAtMost(pager.items.size))
        }
    }
    BoxWithConstraints(modifier.width(LETTER_BAR_WIDTH)) {
        val fit = (maxHeight / LETTER_HEIGHT).toInt().coerceAtLeast(2)
        val shown = remember(letters, fit) { sampleLetters(letters, fit) }
        val heightPx = with(LocalDensity.current) { LETTER_HEIGHT.toPx() }
        val top = (maxHeight - LETTER_HEIGHT * shown.size) / 2
        val topPx = with(LocalDensity.current) { top.toPx() }
        fun jump(y: Float) {
            val i = ((y - topPx) / heightPx).toInt().coerceIn(0, shown.lastIndex)
            target.value = shown[i].second
        }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) { detectTapGestures { jump(it.y) } }
                .pointerInput(shown) { detectVerticalDragGestures { change, _ -> jump(change.position.y) } },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for ((letter, _) in shown) {
                Box(Modifier.height(LETTER_HEIGHT), contentAlignment = Alignment.Center) {
                    Text(letter.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * At most [fit] of [letters] in their order: '#' and A to Z first, as they are what most lists are
 * jumped by, and the letters of other scripts evenly spread over the slots left.
 */
internal fun sampleLetters(letters: List<Pair<Char, Int>>, fit: Int): List<Pair<Char, Int>> {
    if (letters.size <= fit) return letters
    val latin = letters.filter { it.first == '#' || it.first in 'A'..'Z' }
    val others = letters - latin.toSet()
    if (latin.size >= fit) {
        val step = (latin.size - 1).toDouble() / (fit - 1)
        return (0 until fit).map { latin[Math.round(it * step).toInt().coerceIn(0, latin.lastIndex)] }.distinct()
    }
    val room = fit - latin.size
    val picked = if (room <= 0 || others.isEmpty()) {
        emptyList()
    } else if (room == 1) {
        listOf(others.first())
    } else {
        val step = (others.size - 1).toDouble() / (room - 1)
        (0 until room).map { others[Math.round(it * step).toInt().coerceIn(0, others.lastIndex)] }.distinct()
    }
    return (latin + picked).sortedBy { it.second }
}

private val ROW_HEIGHT = 56.dp
private val BADGES_WIDTH = 190.dp
private val LETTER_HEIGHT = 18.dp
private val LETTER_BAR_WIDTH = 28.dp
private const val LETTER_BAR_MIN = 40
private const val VISIBLE_SETTLE_MS = 400L

/** The width from which a row has room for its album column. */
private val WIDE_AT = 760.dp
