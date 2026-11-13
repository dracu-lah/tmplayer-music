// The Versions sheet of the Android app (app/.../ui/library/Songs.kt), as a dialog.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.ui.common.Badge
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.PickReason
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackVersions
import kotlinx.coroutines.CancellationException

/**
 * The Versions dialog: one line per file with its quality, size, chat and date, the one that plays
 * by default marked, and why it was chosen. Click a line to play that version.
 */
@Composable
fun VersionsDialog(song: Song, onPlay: (Track) -> Unit, onDismiss: () -> Unit) {
    var versions by remember(song.groupKey) { mutableStateOf<List<Track>?>(null) }
    var chats by remember(song.groupKey) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song.groupKey) {
        try {
            versions = Library.index().versions(song.groupKey)
            chats = Library.index().pickedChats().associate { it.chatId to it.title }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ArtistDisplay.of(song.artist).ifBlank { L.libraryUnknownArtist }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val list = versions.orEmpty()
                if (list.size > 1) Text(L.versionsTapToPlay, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TrackVersions.reason(list)?.let {
                    Text(reasonText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (track in list) {
                    val isDefault = track.id == song.defaultTrack
                    Column(Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(vertical = 4.dp)) {
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
                            listOf(Format.size(track.sizeBytes), chats[track.chatId].orEmpty(), Format.date(track.dateSec)).filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.commonBack) } },
    )
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
