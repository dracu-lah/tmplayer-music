// Home follows the Android app's Home.kt and SpeedDialGrid.kt (app/.../ui/library): the same core
// rules (SpeedDial.pick), laid out for a window: the Continue band, Speed dial in pages of nine,
// and shelves of covers for Recently played, Recently added and Favourites.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.ChatOrder
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.ui.common.Cover
import com.tmplayer.music.desktop.ui.common.EmptyState
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.MusicIcons
import com.tmplayer.music.desktop.ui.common.PlayingGlyph
import com.tmplayer.music.desktop.ui.common.guarded
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.DialHidden
import com.tmplayer.music.library.DialItem
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SpeedDial
import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.launch

/** Songs in each shelf. */
private const val ROW = 30

/** Latest plays read to find Speed dial cards beyond the ones with a kept position. */
private const val PLAYS_READ = 60

/** A chat page's file resumed from Speed dial queues this many files of its chat from there on. */
private const val CHAT_QUEUE = 200

private val SIDE = 24.dp
private val GAP = 12.dp
private val SHELF_CELL = 148.dp

/**
 * Home: Continue (what the queue holds, paused where it was left), Speed dial (what was played
 * lately, with where it was left), then Recently played, Recently added and Favourites as rows of
 * covers, and Play all and Shuffle all. A row with nothing in it is left out; with nothing anywhere,
 * Home says how to start. Plain lists of what the user has, never a recommendation.
 */
@Composable
fun HomeScreen(player: PlayerController, actions: SongActions, onPlayAll: (Boolean) -> Unit, onManage: () -> Unit) {
    val changes by Library.changes.collectAsState()
    var played by remember { mutableStateOf<List<Song>>(emptyList()) }
    var added by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favourites by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        guarded({ }) {
            val index = Library.index()
            played = index.recentlyPlayed(ROW)
            added = index.recentlyAdded(ROW)
            favourites = index.favourites(ROW)
        }
        loaded = true
    }
    val queued by player.ui.collectAsState()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "head") {
            ScreenHeader(
                title = L.tabHome,
                subtitle = null,
                actions = {
                    Button(onClick = { onPlayAll(false) }, enabled = added.isNotEmpty()) { Text(L.homePlayAll) }
                    FilledTonalButton(onClick = { onPlayAll(true) }, enabled = added.isNotEmpty()) { Text(L.homeShuffleAll) }
                },
            )
        }
        if (!queued.queue.isEmpty) item(key = "continue") { ContinueBand(player) }
        item(key = "dial") { SpeedDialSection(player, Modifier.padding(top = 8.dp)) }
        if (played.isNotEmpty()) item(key = "played") { Shelf(L.homeRecentlyPlayed, played, actions) }
        if (added.isNotEmpty()) item(key = "added") { Shelf(L.homeRecentlyAdded, added, actions) }
        if (favourites.isNotEmpty()) item(key = "favourites") { Shelf(L.homeFavourites, favourites, actions) }
        if (loaded && played.isEmpty() && added.isEmpty() && favourites.isEmpty() && queued.queue.isEmpty) {
            item(key = "empty") {
                EmptyState(L.libraryEmpty) { FilledTonalButton(onClick = onManage) { Text(L.manageTitle) } }
            }
        }
    }
}

/** What plays now, or waits paused after a restart: the cover, where it is, and Play or Pause. */
@Composable
private fun ContinueBand(player: PlayerController) {
    val ui by player.ui.collectAsState()
    val position by player.position.collectAsState()
    val entry = ui.current ?: return
    val duration = ui.durationMs.takeIf { it > 0 } ?: entry.durationMs
    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L.commonContinue, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Cover(
                name = entry.title,
                fileId = 0,
                size = 72.dp,
                coverPath = Library.coverFile(entry.coverKey)?.path.orEmpty(),
                track = entry.id.takeIf { entry.thumbFileId != 0 },
                art = entry.artKey,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.artist.ifBlank { L.libraryUnknownArtist },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (duration > 0) {
                    LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        L.listeningProgress(Format.position(position), Format.duration(duration)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = { player.togglePlay() }) { Text(if (ui.playing) L.playerPause else L.listeningResume) }
        }
    }
}

// Shelves

@Composable
private fun Shelf(title: String, songs: List<Song>, actions: SongActions) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = SIDE))
        val mark = LocalPlayingMark.current
        LazyRow(contentPadding = PaddingValues(horizontal = SIDE), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            itemsIndexed(songs, key = { _, song -> song.groupKey }) { index, song ->
                ContextMenuArea(items = { songMenuItems(listOf(song), actions) }) {
                    ShelfCard(song, mark?.forSong(song.groupKey, song.defaultTrack), onClick = { actions.play(songs, index) })
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(song: Song, mark: Boolean?, onClick: () -> Unit) {
    Column(
        Modifier.width(SHELF_CELL).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Cover(
            name = song.title,
            fileId = 0,
            size = SHELF_CELL - 8.dp,
            coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(),
            track = song.defaultTrack.takeIf { song.thumbFileId != 0 },
            art = CoverArt.keyOf(song.albumKey, song.groupKey),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) PlayingGlyph(playing = mark, modifier = Modifier.padding(end = 4.dp))
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mark != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
}

// Speed dial

/**
 * Speed dial: what the player holds first, then what was played or paused lately, with where it was
 * left ([SpeedDial.pick]), three cards across and up to nine to a page. A card that was left part
 * way has a thin bar; Remove takes a card off until the song is played again.
 */
@Composable
fun SpeedDialSection(player: PlayerController, modifier: Modifier = Modifier) {
    val changes by Library.changes.collectAsState()
    val ui by player.ui.collectAsState()
    val position by player.position.collectAsState()
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var resumes by remember { mutableStateOf<List<DialItem>>(emptyList()) }
    var hidden by remember { mutableStateOf<List<DialHidden>>(emptyList()) }
    var plays by remember { mutableStateOf<List<DialItem>>(emptyList()) }
    LaunchedEffect(changes) {
        guarded({ }) {
            val index = Library.index()
            val (kept, off) = index.resumePoints()
            resumes = kept
            hidden = off
            plays = index.recentPlays(PLAYS_READ)
        }
    }
    val entry = ui.current
    val playingNow = remember(entry, ui.durationMs) {
        entry?.let {
            DialItem(
                key = it.songKey.ifBlank { DialItem.fileKey(it.id) },
                track = it.id,
                songKey = it.songKey,
                title = it.title,
                artist = it.artist,
                coverKey = it.coverKey,
                thumbFileId = it.thumbFileId,
                durationMs = ui.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                coverArt = it.artKey,
            )
        }
    }
    // The position follows the player only at the moments that matter (a card is drawn, not every tick).
    val shownNow = playingNow?.copy(positionMs = position.coerceAtLeast(0))
    val items = remember(playingNow, resumes, plays, hidden) { SpeedDial.pick(shownNow, resumes, plays, hidden) }
    if (items.isEmpty()) return

    SpeedDialContent(
        items = items,
        current = entry?.id,
        playing = ui.playing,
        modifier = modifier,
        onTap = { item ->
            if (item.track == entry?.id) {
                player.togglePlay()
            } else {
                scope.launch {
                    guarded({ notify(L.homeSpeedDialFailed(it)) }) { resume(player, item)?.let(notify) }
                }
            }
        },
        onRemove = { item ->
            scope.launch {
                runCatching { Library.index().hideFromDial(item.track, item.songKey, System.currentTimeMillis()) }
                Library.notifyChanged()
            }
        },
    )
}

/**
 * Starts [item] where it was left. In the queue already, it is skipped to; a file played from a chat
 * page queues that chat from it on, in the page's order; a song plays alone, at the version that was
 * played. A file that is gone is taken off Speed dial and the answer says so. Null when it started.
 */
private suspend fun resume(player: PlayerController, item: DialItem): String? {
    if (player.resumeInQueue(item.track, item.positionMs)) return null
    val index = Library.index()
    val track = index.track(item.track)
    if (track == null) {
        index.forgetResume(item.track)
        val song = item.songKey.takeIf { it.isNotEmpty() }?.let { index.song(it) }
        Library.notifyChanged()
        if (song == null) return L.homeSpeedDialGone
        // The file went but the song has another version: play that one from the top.
        player.play(listOf(song), 0)
        return null
    }
    if (item.songKey.isEmpty()) {
        val oldestFirst = Library.choices.chatOrder(track.chatId) == ChatOrder.OldestFirst
        val after = index.chatTracks(track.chatId, oldestFirst, track.messageId, CHAT_QUEUE - 1)
        player.playTracks(listOf(track) + after, 0, positionMs = item.positionMs)
    } else {
        val song = index.song(item.songKey) ?: index.songOf(track.id)
        if (song != null) {
            player.play(listOf(song.copy(defaultTrack = track.id)), 0, positionMs = item.positionMs)
        } else {
            player.playTracks(listOf(track), 0, positionMs = item.positionMs)
        }
    }
    return null
}

@Composable
private fun SpeedDialContent(
    items: List<DialItem>,
    current: TrackId?,
    playing: Boolean,
    onTap: (DialItem) -> Unit,
    onRemove: (DialItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = SpeedDial.pages(items)
    var page by remember { mutableStateOf(0) }
    val shown = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = SIDE), verticalAlignment = Alignment.CenterVertically) {
            Text(L.homeSpeedDial, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (pages.size > 1) {
                Text(
                    L.homeSpeedDialPage(shown + 1, pages.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { page = shown - 1 }, enabled = shown > 0) { Icon(MusicIcons.Back, contentDescription = L.commonBack) }
                IconButton(onClick = { page = shown + 1 }, enabled = shown < pages.lastIndex) {
                    Icon(MusicIcons.Back, contentDescription = L.playerNext, modifier = Modifier.size(24.dp).rotate(180f))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = SIDE)) {
            // Three across the width; on a wide window the cards stop growing.
            val cell = minOf((maxWidth - GAP * (COLUMNS - 1)) / COLUMNS, MAX_CELL)
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                for (row in pages.getOrNull(shown).orEmpty().chunked(COLUMNS)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        for (item in row) {
                            DialCard(
                                item = item,
                                size = cell,
                                playing = if (item.track == current) playing else null,
                                onTap = { onTap(item) },
                                onRemove = { onRemove(item) }.takeIf { item.track != current },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val COLUMNS = 3
private val MAX_CELL = 190.dp

/**
 * One square card: the cover, the title over a dark fade at its foot so it reads on any art, a thin
 * bar along the bottom when it was left part way, and a glyph on the one that is current.
 */
@Composable
private fun DialCard(item: DialItem, size: Dp, playing: Boolean?, onTap: () -> Unit, onRemove: (() -> Unit)?) {
    val artist = ArtistDisplay.of(item.artist).ifBlank { L.libraryUnknownArtist }
    val described = L.homeSpeedDialCard(item.title, artist)
    val progress = item.takeIf { it.partlyPlayed && it.durationMs > 0 }?.let {
        L.listeningProgress(Format.duration(it.positionMs.coerceIn(0, it.durationMs)), Format.duration(it.durationMs))
    }
    val card: @Composable () -> Unit = {
        Box(
            Modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onTap)
                // Delete takes a card off Speed dial from the keyboard, as the menu does for the mouse.
                .onKeyEvent { e ->
                    if (onRemove != null && e.type == KeyEventType.KeyDown && (e.key == Key.Delete || e.key == Key.Backspace)) {
                        onRemove()
                        true
                    } else {
                        false
                    }
                }
                .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(described, progress).joinToString(". ") },
        ) {
            Cover(
                name = item.title,
                fileId = 0,
                size = size,
                coverPath = Library.coverFile(item.coverKey)?.path.orEmpty(),
                track = item.track.takeIf { item.thumbFileId != 0 },
                art = item.coverArt.ifBlank { CoverArt.keyOf("", item.key) },
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing != null) PlayingGlyph(playing = playing, modifier = Modifier.padding(end = 4.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(artist, style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.partlyPlayed && item.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0x55FFFFFF),
                    )
                }
            }
        }
    }
    if (onRemove == null) {
        card()
    } else {
        ContextMenuArea(items = { listOf(ContextMenuItem(L.homeSpeedDialRemove) { onRemove() }) }) { card() }
    }
}
