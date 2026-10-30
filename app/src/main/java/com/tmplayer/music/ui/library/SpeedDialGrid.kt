package com.tmplayer.music.ui.library

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.ChatOrder
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.DialHidden
import com.tmplayer.music.library.DialItem
import com.tmplayer.music.library.SpeedDial
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.player.MediaIds
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.player.WaveLogic
import com.tmplayer.music.ui.theme.MusicTheme
import com.tmplayer.music.ui.theme.Pill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Speed dial, at the top of Home: what the user played lately as small square covers, 3 by 3 a page
 * (`plan/RESEARCH.md` 4 item 2). What the player holds now comes first, marked playing or paused;
 * then the rest by when they were last played or paused ([SpeedDial.pick]). A card that was left part
 * way has a thin bar and resumes there. Tap resumes, long press (or the accessibility action) takes
 * a card off, and playing it again brings it back. Nothing plays until a card is tapped. With nothing
 * to show the section is gone.
 *
 * The rows are read when the library changes (a pause, a skip, a finished track, a chat left), and the
 * current card's position when the song or play and pause change; nothing runs on a timer, the pager
 * never scrolls by itself, and only the page in view is composed.
 */
@Composable
fun SpeedDialSection(modifier: Modifier = Modifier) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    var resumes by remember { mutableStateOf<List<DialItem>>(emptyList()) }
    var hidden by remember { mutableStateOf<List<DialHidden>>(emptyList()) }
    var plays by remember { mutableStateOf<List<DialItem>>(emptyList()) }
    var current by remember { mutableStateOf<DialItem?>(null) }
    LaunchedEffect(changes) {
        try {
            val index = Library.index()
            val (kept, off) = index.resumePoints()
            resumes = kept
            hidden = off
            plays = index.recentPlays(PLAYS_READ)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cards keep what they had; the library tabs say what went wrong.
        }
    }
    val mediaId = state.item?.mediaId.takeIf { state.count > 0 }
    LaunchedEffect(mediaId, changes) {
        current = try {
            mediaId?.let { MediaIds.item(it) }?.let { queued ->
                val index = Library.index()
                index.track(queued.id)?.let { track ->
                    DialItem(
                        key = index.songOf(queued.id)?.groupKey ?: DialItem.fileKey(queued.id),
                        track = queued.id,
                        songKey = queued.songKey,
                        title = track.title.ifBlank { track.fileName },
                        artist = track.artist,
                        coverKey = track.coverKey,
                        thumbFileId = track.thumbFileId,
                        durationMs = track.durationMs,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val position = remember(mediaId, state.index, state.isPlaying, state.durationMs) { PlayerConnection.position() }
    val playingNow = current?.takeIf { mediaId != null && MediaIds.item(mediaId)?.id == it.track }?.let {
        it.copy(positionMs = position.coerceAtLeast(0), durationMs = state.durationMs.takeIf { d -> d > 0 } ?: it.durationMs)
    }
    val items = remember(playingNow, resumes, plays, hidden) { SpeedDial.pick(playingNow, resumes, plays, hidden) }
    if (items.isEmpty()) return

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SpeedDialContent(
        items = items,
        current = playingNow?.track,
        playing = state.isPlaying,
        onTap = { item ->
            if (item.track == playingNow?.track) {
                PlayerConnection.toggle()
            } else {
                scope.launch {
                    val problem = try {
                        resume(item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.homeSpeedDialFailed(Failures.humanise(e))
                    }
                    problem?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            }
        },
        onRemove = { item ->
            scope.launch {
                runCatching { Library.index().hideFromDial(item.track, item.songKey, System.currentTimeMillis()) }
                Library.notifyChanged()
            }
        },
        modifier = modifier,
    )
}

/**
 * Starts [item] where it was left. In the queue already, it is skipped to; a file played from a chat
 * page queues that chat from it on, in the page's order; a song plays alone, at the version that was
 * played. A file that is gone is taken off Speed dial and the answer says so. Null when it started.
 */
private suspend fun resume(item: DialItem): String? {
    if (PlayerConnection.resumeInQueue(item.track, item.positionMs)) return null
    val index = Library.index()
    val track = index.track(item.track)
    if (track == null) {
        index.forgetResume(item.track)
        val song = item.songKey.takeIf { it.isNotEmpty() }?.let { index.song(it) }
        Library.notifyChanged()
        if (song == null) return L.homeSpeedDialGone
        // The file went but the song has another version: play that one from the top.
        PlayerConnection.play(listOf(song), 0)
        return null
    }
    if (item.songKey.isEmpty()) {
        val oldestFirst = Library.choices.chatOrder(track.chatId) == ChatOrder.OldestFirst
        val after = index.chatTracks(track.chatId, oldestFirst, track.messageId, CHAT_QUEUE - 1)
        PlayerConnection.play((listOf(track) + after).map { it.asSong() }, 0, positionMs = item.positionMs)
    } else {
        val song = index.song(item.songKey) ?: index.songOf(track.id)
        val one = song?.copy(defaultTrack = track.id) ?: track.asSong()
        PlayerConnection.play(listOf(one), 0, positionMs = item.positionMs)
    }
    return null
}

@Composable
internal fun SpeedDialContent(
    items: List<DialItem>,
    current: TrackId?,
    playing: Boolean,
    onTap: (DialItem) -> Unit,
    onRemove: (DialItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = SpeedDial.pages(items)
    // Saved with the screen, so a rotation keeps the page in view.
    val pager = rememberPagerState { pages.size }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L.homeSpeedDial, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = SIDE))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Three across the width; on a wide screen (landscape, a tablet) the cards stop growing.
            val cell = min((maxWidth - SIDE * 2 - GAP * (COLUMNS - 1)) / COLUMNS, MAX_CELL)
            // Every page is as tall as the first, the fullest, so a short last page does not move Home.
            val first = pages.firstOrNull()?.size ?: 0
            val rows = ((first + COLUMNS - 1) / COLUMNS).coerceIn(1, ROWS)
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = SIDE),
                pageSpacing = SIDE,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().height(cell * rows + GAP * (rows - 1)),
            ) { page ->
                val cards = pages.getOrNull(page).orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    for (row in cards.chunked(COLUMNS)) {
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
        if (pages.size > 1) PageDots(pager.currentPage, pages.size)
    }
}

/**
 * One square card: the cover, the title over a dark fade at its foot so it reads on any art, a thin
 * bar along the bottom when it was left part way, and a still glyph on the one that is current.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialCard(item: DialItem, size: Dp, playing: Boolean?, onTap: () -> Unit, onRemove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val artist = ArtistDisplay.of(item.artist).ifBlank { L.libraryUnknownArtist }
    val described = L.homeSpeedDialCard(item.title, artist)
    val progress = item.takeIf { it.partlyPlayed && it.durationMs > 0 }?.let {
        L.listeningProgress(Format.duration(it.positionMs.coerceIn(0, it.durationMs)), Format.duration(it.durationMs))
    }
    val removeLabel = L.homeSpeedDialRemove
    Box(
        Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClickLabel = if (playing == true) L.playerPause else L.listeningResume,
                onLongClick = onRemove?.let { { menu = true } },
                onClick = onTap,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = described
                (progress ?: playing?.let { if (it) L.listeningNowPlaying else L.listeningNowPaused })?.let { stateDescription = it }
                if (onRemove != null) {
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                }
            },
    ) {
        Cover(
            name = item.title,
            fileId = 0,
            coverPath = Library.coverFile(item.coverKey)?.path.orEmpty(),
            size = size,
            track = item.track.takeIf { item.thumbFileId != 0 },
            art = item.coverArt.ifBlank { com.tmplayer.music.library.CoverArt.keyOf("", item.songKey.ifEmpty { item.key }) },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
        )
        Text(
            item.title,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 9.dp),
        )
        if (item.partlyPlayed) {
            val fraction = if (item.durationMs > 0) WaveLogic.fraction(item.positionMs, item.durationMs) else 0f
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
        }
        if (playing != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { PlayingGlyph(playing = playing) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(removeLabel) }, onClick = {
                menu = false
                onRemove?.invoke()
            })
        }
    }
}

/** Which page is in view, as small dots; still, so nothing draws while Home is open. */
@Composable
private fun PageDots(page: Int, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = L.homeSpeedDialPage(page + 1, count) },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == page) 8.dp else 6.dp)
                    .align(Alignment.CenterVertically)
                    .clip(Pill)
                    .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

private const val COLUMNS = 3
private const val ROWS = 3
private val SIDE = 16.dp
private val GAP = 8.dp
private val MAX_CELL = 160.dp

/** Plays read for Speed dial: enough to fill three pages after folding and hiding. */
private const val PLAYS_READ = 60

/** Files queued after a chat page's track when it resumes. */
private const val CHAT_QUEUE = 200

// Previews: the fixed (icon colour) scheme, placeholder text only.

private fun previewItems(n: Int) = (1..n).map { i ->
    DialItem(
        key = "k$i",
        track = TrackId(1, i.toLong()),
        songKey = "k$i",
        title = if (i == 2) "A track title that runs long enough for two lines" else "Track $i",
        artist = "Artist name",
        positionMs = if (i % 3 == 1) 80_000 else 0,
        durationMs = 215_000,
    )
}

@Composable
private fun DialSample(dark: Boolean, n: Int) {
    MusicTheme(dark = dark, dynamic = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                SpeedDialContent(previewItems(n), current = TrackId(1, 1), playing = !dark, onTap = {}, onRemove = {})
            }
        }
    }
}

@Preview(name = "Speed dial, light, two pages", widthDp = 400)
@Composable
private fun DialLight() = DialSample(dark = false, n = 14)

@Preview(name = "Speed dial, dark, one page", widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DialDark() = DialSample(dark = true, n = 5)

@Preview(name = "Speed dial, font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun DialLargeText() = DialSample(dark = false, n = 9)
