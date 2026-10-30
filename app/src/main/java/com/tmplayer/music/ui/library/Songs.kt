package com.tmplayer.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.SongCursor
import com.tmplayer.music.library.SongSort
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackVersions
import com.tmplayer.music.library.PickReason
import com.tmplayer.music.ui.common.Cover
import com.tmplayer.music.ui.common.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.music.data.TagEnricher

/** Songs: every song of every chosen chat in one list, repeats folded into versions. */
@Composable
fun SongsTab(sort: SongSort, actions: SongActions, header: @Composable () -> Unit) {
    val state = rememberLazyListState()
    val pager = rememberPager<SongCursor, Song>(
        key = sort,
        state = state,
        load = { after, limit -> Library.index().songs(sort, after, limit) },
        cursorOf = { SongCursor.of(it, sort) },
    )
    // The bar has its own column, so it never covers a row's title or format badge.
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { SongList(pager, state, actions, header) }
        if (sort != SongSort.Added) LetterBar(sort, pager, state, Modifier.fillMaxHeight())
    }
}

/**
 * The A to Z bar at the edge of Songs: each first letter in the list. Tap a letter, or drag along
 * the bar, to go there. When more letters exist than fit (Latin plus another script), an even
 * sample is shown, the first and last always. The rows up to that letter are read first (keyset
 * pages), so a jump to the end of a long list waits for them; the bar hides itself when the list
 * has fewer than [LETTER_BAR_MIN] rows.
 */
@Composable
private fun LetterBar(sort: SongSort, pager: Pager<SongCursor, Song>, state: LazyListState, modifier: Modifier) {
    val changes by Library.changes.collectAsStateWithLifecycle()
    var letters by remember { mutableStateOf<List<Pair<Char, Int>>>(emptyList()) }
    LaunchedEffect(sort, changes) {
        letters = runCatching { Library.index().letterIndex(sort) }.getOrDefault(letters)
    }
    val total = letters.lastOrNull()?.second ?: 0
    if (letters.size < 2 || total < LETTER_BAR_MIN) return
    // One jump at a time; a drag that moves on cancels the jump it left behind.
    val target = remember { kotlinx.coroutines.flow.MutableStateFlow<Int?>(null) }
    LaunchedEffect(pager, state) {
        target.collectLatest { position ->
            if (position == null) return@collectLatest
            pager.ensure(position)
            // +1 for the header row above the songs.
            state.scrollToItem((position + 1).coerceAtMost(pager.items.size))
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.width(LETTER_BAR_WIDTH)) {
        val fit = (maxHeight / LETTER_HEIGHT).toInt().coerceAtLeast(2)
        val shown = remember(letters, fit) { sampleLetters(letters, fit) }
        val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { LETTER_HEIGHT.toPx() }
        val top = (maxHeight - LETTER_HEIGHT * shown.size) / 2
        val topPx = with(androidx.compose.ui.platform.LocalDensity.current) { top.toPx() }
        fun jump(y: Float) {
            val i = ((y - topPx) / heightPx).toInt().coerceIn(0, shown.lastIndex)
            target.value = shown[i].second
        }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) {
                    detectTapGestures { jump(it.y) }
                }
                .pointerInput(shown) {
                    detectVerticalDragGestures { change, _ -> jump(change.position.y) }
                },
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

private val LETTER_HEIGHT = 18.dp
private val LETTER_BAR_WIDTH = 28.dp
private const val LETTER_BAR_MIN = 40

/** What a tap and a hold on a song do, from any list, and Shuffle all. */
class SongActions(val play: (List<Song>, Int) -> Unit, val shuffle: (List<Song>) -> Unit, val menu: (Song) -> Unit)

@Composable
fun SongList(
    pager: Pager<SongCursor, Song>,
    state: LazyListState,
    actions: SongActions,
    header: @Composable () -> Unit = {},
    empty: @Composable () -> Unit = { EmptyLibrary() },
) {
    ReadVisibleFirst(pager, state)
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        items(pager.items, key = { it.groupKey }) { song ->
            SongRow(song, onClick = { actions.play(pager.items.toList(), pager.items.indexOf(song)) }, onLongClick = { actions.menu(song) })
        }
        if (pager.loaded && pager.items.isEmpty()) item(key = "empty") { empty() }
        pager.error?.let { message ->
            item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
        }
    }
}

/**
 * While the tag reader runs, the rows on screen go to the front of its queue, once the scroll has
 * settled, so what the user looks at gets its real title and cover first. Nothing runs otherwise.
 */
@Composable
private fun ReadVisibleFirst(pager: Pager<SongCursor, Song>, state: LazyListState) {
    val flow = remember { Library.enricher.status.map { it.running && !it.paused }.distinctUntilChanged() }
    val reading by flow.collectAsStateWithLifecycle(initialValue = false)
    if (!reading) return
    LaunchedEffect(pager, state) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collectLatest { first ->
            delay(VISIBLE_SETTLE_MS)
            val from = (first - 1).coerceAtLeast(0)
            Library.enricher.prefer(pager.items.toList().drop(from).take(TagEnricher.BATCH).map { it.defaultTrack })
        }
    }
}

private const val VISIBLE_SETTLE_MS = 400L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val subtitle = listOf(song.artist.ifBlank { L.libraryUnknownArtist }, Format.duration(song.durationMs)).joinToString("  ·  ")
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            Cover(name = song.title, fileId = 0, coverPath = Library.coverFile(song.coverKey)?.path.orEmpty(), track = song.defaultTrack.takeIf { song.thumbFileId != 0 })
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song.variant.isNotBlank()) Badge(song.variant)
        if (song.bestFormat.lossless) Badge(song.bestFormat.label)
        if (song.versionCount > 1) {
            AssistChip(onClick = onLongClick, label = { Text(L.libraryVersions(song.versionCount)) })
        }
        trailing?.invoke()
    }
}

@Composable
fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun EmptyLibrary() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(L.libraryEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The Versions sheet (`plan/RESEARCH.md` 4.2): one line per file with its quality, size, chat and
 * date, the one that plays by default marked, and why it was chosen. Tap a line to play that version.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VersionsSheet(song: Song, chatTitles: Map<Long, String>, onDismiss: () -> Unit) {
    var versions by remember(song.groupKey) { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song.groupKey) {
        try {
            versions = Library.index().versions(song.groupKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleLarge)
            Text(song.artist.ifBlank { L.libraryUnknownArtist }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = versions.orEmpty()
            val reason = TrackVersions.reason(list, emptySet(), null, false)
            if (list.size > 1) Text(L.versionsTapToPlay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (list.size > 1 && reason != null) {
                Text(reasonText(reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (track in list) {
                val isDefault = track.id == song.defaultTrack
                Column(
                    Modifier.fillMaxWidth().combinedClickable(onClick = {
                        com.tmplayer.music.player.PlayerConnection.playVersion(song, track)
                        onDismiss()
                    }),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            Format.quality(track).ifBlank { track.fileName },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isDefault) Badge(L.versionsDefault)
                        if (!AudioKinds.playable(track.format) && track.format == AudioFormat.OTHER) Badge(L.versionsCantPlay)
                    }
                    Text(
                        listOf(
                            Format.size(track.sizeBytes),
                            chatTitles[track.chatId].orEmpty(),
                            Format.date(track.dateSec),
                            Format.duration(track.durationMs),
                        ).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun reasonText(reason: PickReason): String = when (reason) {
    PickReason.Preferred -> L.versionsWhyPreferred
    PickReason.OnlyPlayable -> L.versionsWhyPlayable
    PickReason.OnDevice -> L.versionsWhyOnDevice
    PickReason.Lossless -> L.versionsWhyLossless
    PickReason.SmallerForData -> L.versionsWhySmaller
    PickReason.HigherQuality -> L.versionsWhyQuality
    PickReason.FullerTags -> L.versionsWhyTags
    PickReason.Oldest -> L.versionsWhyOldest
}
