package com.tmplayer.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
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
    SongList(pager, state, actions, header)
}

/** What a tap and a hold on a song do, from any list. */
class SongActions(val play: (List<Song>, Int) -> Unit, val menu: (Song) -> Unit)

@Composable
fun SongList(
    pager: Pager<SongCursor, Song>,
    state: LazyListState,
    actions: SongActions,
    header: @Composable () -> Unit = {},
    empty: @Composable () -> Unit = { EmptyLibrary() },
) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(song: Song, onClick: () -> Unit, onLongClick: () -> Unit = onClick, leading: (@Composable () -> Unit)? = null) {
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
            Cover(name = song.title, fileId = song.thumbFileId, coverPath = Library.coverFile(song.coverKey)?.path.orEmpty())
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
    }
}

@Composable
fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
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
