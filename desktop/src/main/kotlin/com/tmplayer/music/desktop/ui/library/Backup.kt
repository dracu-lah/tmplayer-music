// Backup and restore follow the Android app's Backup.kt (app/.../ui/library): one JSON file through
// the system file dialog, read and checked whole before anything is touched.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.os.FilePicker
import com.tmplayer.music.desktop.os.UserFiles
import com.tmplayer.music.desktop.ui.common.autoFocus
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Backup
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Backup"

/** What the restore dialog works from: the checked file and what each mode would do. */
private class RestorePlan(val data: Backup.Data, val merge: Backup.Summary, val replace: Backup.Summary)

private fun contents(data: Backup.Data): String =
    L.backupContents(data.favourites.size, data.playlists.size, data.plays.size, data.chats.size)

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

/**
 * Backup and restore: one JSON file through the file dialog. Restore reads and checks the whole file
 * first, shows what it would do, merges by default, and asks again before Replace.
 */
@Composable
fun BackupSection() {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    fun say(text: String, error: Boolean = false) {
        message = text
        failed = error
    }

    fun save() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val file = FilePicker.save(L.backupSave, "tmplayer-music-backup-${today()}.json", "json") ?: return@launch
                val data = Library.index().backupData()
                val text = Backup.encode(data)
                if (text.length > Backup.MAX_FILE_BYTES / 2) throw java.io.IOException("backup too large")
                UserFiles.writeText(file, text)
                say(L.backupSaved + "\n" + contents(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not write a backup", e)
                say(L.backupSaveFailed, error = true)
            } finally {
                busy = false
            }
        }
    }

    fun open() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val file = FilePicker.open(L.backupRestore, "json") ?: return@launch
                val bytes = UserFiles.readCapped(file, Backup.MAX_FILE_BYTES)
                if (bytes == null) {
                    say(L.backupTooBig, error = true)
                    return@launch
                }
                // Read and checked whole before anything is touched; a bad file changes nothing.
                when (val result = withContext(Dispatchers.Default) { Backup.decode(String(bytes, Charsets.UTF_8)) }) {
                    is Backup.Result.Newer -> say(L.backupNewer(result.version), error = true)
                    is Backup.Result.Bad -> {
                        Logger.w(TAG, "backup refused: ${result.problem} ${result.detail}")
                        say(
                            when (result.problem) {
                                Backup.Problem.NotBackup, Backup.Problem.NotJson -> L.backupNotBackup
                                Backup.Problem.TooLarge -> L.backupTooLarge
                                Backup.Problem.Damaged -> L.backupDamaged
                            },
                            error = true,
                        )
                    }
                    is Backup.Result.Ok -> {
                        val index = Library.index()
                        plan = RestorePlan(
                            result.data,
                            index.restoreBackup(result.data, replace = false, commit = false),
                            index.restoreBackup(result.data, replace = true, commit = false),
                        )
                        message = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not read a backup", e)
                say(L.backupReadFailed, error = true)
            } finally {
                busy = false
            }
        }
    }

    SettingsSection(L.backupTitle) {
        SettingsHint(L.backupBody)
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = ::save) { Text(L.backupSave) }
            TextButton(enabled = !busy, onClick = ::open) { Text(L.backupRestore) }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    val current = plan ?: return
    RestoreDialog(
        current,
        onDismiss = { plan = null },
        onApply = { replace, addChats ->
            plan = null
            busy = true
            scope.launch {
                try {
                    val index = Library.index()
                    val summary = index.restoreBackup(current.data, replace = replace, commit = true)
                    val chats = if (addChats) summary.newChats else emptyList()
                    if (chats.isNotEmpty()) {
                        val keep = index.pickedChats().map { ChatPick(it.chatId, it.title) }
                        Library.pick(keep + chats.map { ChatPick(it.chatId, it.title.ifBlank { it.chatId.toString() }) })
                    }
                    Library.notifyChanged()
                    say(if (chats.isEmpty()) L.backupRestored else L.backupRestoredChats(chats.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "could not restore a backup", e)
                    say(L.backupRestoreFailed + " " + Failures.humanise(e), error = true)
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
private fun RestoreDialog(plan: RestorePlan, onDismiss: () -> Unit, onApply: (replace: Boolean, addChats: Boolean) -> Unit) {
    var replace by remember { mutableStateOf(false) }
    var addChats by remember { mutableStateOf(true) }
    var confirmReplace by remember { mutableStateOf(false) }
    val summary = if (replace) plan.replace else plan.merge
    val doesSomething = !summary.changesNothing || (addChats && summary.newChats.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.backupRestoreTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(contents(plan.data), style = MaterialTheme.typography.bodyMedium)
                ModeRow(L.backupMerge, L.backupMergeDetail, selected = !replace) { replace = false }
                ModeRow(L.backupReplace, L.backupReplaceDetail, selected = replace) { replace = true }
                if (replace) Note(L.backupPreviewRemoves(summary.removedFavourites, summary.removedPlaylists, summary.removedPlays))
                Note(L.backupPreviewFavourites(summary.favouritesAdded, summary.favouritesKnown))
                if (summary.playlistsCreated + summary.playlistsMerged > 0) {
                    Note(L.backupPreviewPlaylists(summary.playlistsCreated, summary.playlistsMerged, summary.playlistItemsAdded))
                }
                if (summary.playedSongs > 0) Note(L.backupPreviewPlays(summary.playedSongs))
                if (summary.pending > 0) Note(L.backupPreviewPending(summary.pending))
                if (summary.dropped > 0) Note(L.backupPreviewDropped(summary.dropped))
                if (summary.newChats.isNotEmpty()) {
                    Note(L.backupPreviewChats(summary.newChats.size))
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = addChats, role = Role.Checkbox, onClick = { addChats = !addChats }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = addChats, onCheckedChange = null)
                        Text(L.backupAddChats, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
                if (!doesSomething) Text(L.backupPreviewNothing, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(enabled = doesSomething, onClick = { if (replace) confirmReplace = true else onApply(false, addChats) }) { Text(L.backupRestoreApply) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.commonCancel) } },
    )
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(L.backupReplaceTitle) },
            text = { Text(L.backupReplaceBody(plan.replace.removedFavourites, plan.replace.removedPlaylists, plan.replace.removedPlays)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    onApply(true, addChats)
                }) { Text(L.backupReplaceConfirm) }
            },
            // The safe answer takes the keyboard first: a replace is never one stray Enter away.
            dismissButton = { TextButton(modifier = Modifier.autoFocus(), onClick = { confirmReplace = false }) { Text(L.commonCancel) } },
        )
    }
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
