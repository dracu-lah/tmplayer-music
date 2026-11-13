package com.tmplayer.music.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Backup
import com.tmplayer.music.library.ChatPick
import com.tmplayer.music.library.M3u
import com.tmplayer.music.library.Playlist
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.ui.tv.tvFocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Backup"

/** The largest list file read: a million short lines. Bigger is not a playlist. */
private const val MAX_LIST_BYTES = 16 * 1024 * 1024

/** Unmatched entries shown in the preview; the rest are counted. */
private const val MAX_SHOWN = 200

// Files (the system file picker only: no storage permission, no paths)

/** The bytes of [uri], or null when it holds more than [max]. Reads on the IO pool and never keeps more than [max] + 1 bytes. */
private suspend fun readCapped(context: Context, uri: Uri, max: Int): ByteArray? = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
    input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) return@withContext null
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }
}

/** Writes [text] to [uri] as UTF-8; on any failure the half-written document is deleted, so no partial file stays behind. */
private suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    try {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        throw e
    }
}

/** The display name of a picked document without its extension, for naming an imported playlist; blank when unknown. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty().substringBeforeLast('.') else ""
    }.orEmpty()
}.getOrDefault("")

private fun safeFileName(name: String): String =
    name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }.joinToString("").trim().take(60).ifBlank { "playlist" }

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

/** Starts a picker, or says the device has none (some TVs ship without one) instead of crashing. */
private inline fun tryPicker(onMissing: () -> Unit, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        onMissing()
    } catch (e: SecurityException) {
        onMissing()
    }
}

// Playlists: export and import

/** Export as M3U8: asks where to save with the system picker, then writes the playlist's songs. */
@Composable
fun ExportPlaylistButton(playlist: Playlist, name: String, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            guarded({ onError(L.playlistExportFailed) }) {
                try {
                    val songs = Library.index().playlistSongs(playlist.id)
                    writeText(context, uri, M3u.export(name, songs))
                    Toast.makeText(context, L.playlistExportSaved(songs.size), Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "could not export a playlist", e)
                    throw e
                }
            }
        }
    }
    TextButton(
        modifier = Modifier.tvFocusRing(),
        onClick = {
            scope.launch {
                guarded({ onError(L.playlistExportFailed) }) {
                    // Nothing to write: say so now, before a file is made.
                    if (Library.index().playlistSongs(playlist.id).isEmpty()) {
                        onError(L.playlistExportEmpty)
                    } else {
                        tryPicker({ onError(L.backupNoPicker) }) { picker.launch(safeFileName(name) + ".m3u8") }
                    }
                }
            }
        },
    ) { Text(L.playlistExport) }
}

private sealed interface ImportState {
    data object Reading : ImportState

    class Preview(val parsed: M3u.Parsed, val matched: M3u.Matched, val suggestedName: String) : ImportState
}

/**
 * Import a list: pick an M3U, M3U8 or text file, see how many entries match songs in the library and
 * which do not, and only on Create playlist is anything made. Nothing is downloaded, no path is opened.
 */
@Composable
fun ImportPlaylistButton(onError: (String) -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = ImportState.Reading
        scope.launch {
            try {
                val bytes = readCapped(context, uri, MAX_LIST_BYTES)
                if (bytes == null) {
                    state = null
                    onError(L.playlistImportTooBig)
                    return@launch
                }
                // Parsing and matching are CPU work: off the main thread.
                val parsed = withContext(Dispatchers.Default) { M3u.parse(M3u.decode(bytes)) }
                if (parsed.entries.isEmpty()) {
                    state = null
                    onError(L.playlistImportEmpty)
                    return@launch
                }
                val matched = withContext(Dispatchers.Default) { M3u.match(parsed.entries, Library.index().songSource()) }
                state = ImportState.Preview(parsed, matched, parsed.name ?: displayName(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not read a playlist file", e)
                state = null
                onError(L.playlistImportFailed)
            }
        }
    }
    TextButton(
        modifier = Modifier.tvFocusRing(),
        enabled = state !is ImportState.Reading,
        onClick = { tryPicker({ onError(L.backupNoPicker) }) { picker.launch(arrayOf("*/*")) } },
    ) { Text(L.playlistImport) }

    val preview = state as? ImportState.Preview ?: return
    ImportPreviewDialog(
        preview,
        onDismiss = { state = null },
        onCreate = { name ->
            state = null
            scope.launch {
                guarded({ onError(it) }) {
                    val id = Library.index().createPlaylistWith(name, preview.matched.songs)
                    if (id < 0) onError(L.playlistTooMany) else onImported()
                }
            }
        },
    )
}

@Composable
private fun ImportPreviewDialog(preview: ImportState.Preview, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val matched = preview.matched
    var name by remember { mutableStateOf(preview.suggestedName.take(200).ifBlank { L.playlistDefaultName }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.playlistImportTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text(L.playlistImportName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(L.playlistImportMatched(matched.matched.size, matched.total), style = MaterialTheme.typography.titleSmall)
                if (matched.duplicates > 0) Note(L.playlistImportDuplicates(matched.duplicates))
                if (preview.parsed.skipped > 0) Note(L.playlistImportSkipped(preview.parsed.skipped))
                if (preview.parsed.truncated) Note(L.playlistImportCut)
                if (matched.matched.isEmpty()) {
                    Text(L.playlistImportNone, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Note(L.playlistImportNote)
                }
                if (matched.unmatched.isNotEmpty()) {
                    Text(L.playlistImportUnmatched(matched.unmatched.size), style = MaterialTheme.typography.titleSmall)
                    // Focusable rows, so a remote can scroll the list one row at a time.
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(matched.unmatched.take(MAX_SHOWN)) { miss ->
                            Column(
                                Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp)).focusable().padding(vertical = 4.dp, horizontal = 4.dp),
                            ) {
                                Text(miss.entry.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (miss.reason == M3u.Miss.Ambiguous) L.playlistImportSeveral else L.playlistImportNotFound,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (matched.unmatched.size > MAX_SHOWN) {
                            item { Note(L.playlistImportMore(matched.unmatched.size - MAX_SHOWN)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.tvFocusRing(),
                enabled = matched.matched.isNotEmpty(),
                onClick = { onCreate(name.trim().ifBlank { L.playlistDefaultName }) },
            ) { Text(L.playlistImportCreate) }
        },
        dismissButton = { TextButton(modifier = Modifier.tvFocusRing(), onClick = onDismiss) { Text(L.commonCancel) } },
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// Settings: backup and restore

/** What the restore dialog works from: the checked file and what each mode would do. */
private class RestorePlan(val data: Backup.Data, val merge: Backup.Summary, val replace: Backup.Summary)

private fun contents(data: Backup.Data): String =
    L.backupContents(data.favourites.size, data.playlists.size, data.plays.size, data.chats.size)

/**
 * Backup and restore: one JSON file through the system file picker. Restore reads and checks the whole
 * file first, shows what it would do, merges by default, and asks again before Replace.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    fun say(text: String, error: Boolean = false) {
        message = text
        failed = error
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val data = Library.index().backupData()
                val text = Backup.encode(data)
                if (text.length > Backup.MAX_FILE_BYTES / 2) throw IOException("backup too large")
                writeText(context, uri, text)
                say(L.backupSaved + "\n" + contents(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "could not write a backup", e)
                runCatching { withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                say(L.backupSaveFailed, error = true)
            } finally {
                busy = false
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val bytes = readCapped(context, uri, Backup.MAX_FILE_BYTES)
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

    Text(L.backupTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
    Text(
        L.backupBody,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            modifier = Modifier.tvFocusRing(),
            enabled = !busy,
            onClick = { tryPicker({ say(L.backupNoPicker, error = true) }) { save.launch("tmplayer-music-backup-${today()}.json") } },
        ) { Text(L.backupSave) }
        TextButton(
            modifier = Modifier.tvFocusRing(),
            enabled = !busy,
            onClick = { tryPicker({ say(L.backupNoPicker, error = true) }) { open.launch(arrayOf("*/*")) } },
        ) { Text(L.backupRestore) }
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
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
                        Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { addChats = !addChats },
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
            TextButton(
                modifier = Modifier.tvFocusRing(),
                enabled = doesSomething,
                onClick = { if (replace) confirmReplace = true else onApply(false, addChats) },
            ) { Text(L.backupRestoreApply) }
        },
        dismissButton = { TextButton(modifier = Modifier.tvFocusRing(), onClick = onDismiss) { Text(L.commonCancel) } },
    )
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(L.backupReplaceTitle) },
            text = { Text(L.backupReplaceBody(plan.replace.removedFavourites, plan.replace.removedPlaylists, plan.replace.removedPlays)) },
            confirmButton = {
                TextButton(modifier = Modifier.tvFocusRing(), onClick = {
                    confirmReplace = false
                    onApply(true, addChats)
                }) { Text(L.backupReplaceConfirm) }
            },
            dismissButton = { TextButton(modifier = Modifier.tvFocusRing(), onClick = { confirmReplace = false }) { Text(L.commonCancel) } },
        )
    }
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
