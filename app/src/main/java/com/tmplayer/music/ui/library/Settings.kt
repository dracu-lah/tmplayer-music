package com.tmplayer.music.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.music.BuildConfig
import android.text.format.DateUtils
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.CachePolicy
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.LibraryPhase
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Storage
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Format
import kotlinx.coroutines.launch

/**
 * Settings: Library (what the scan and the tag reader do, with Pause and Check now), Storage (what the cache holds, what is free, the cache limit, Free up space) and About.
 * Free up space asks first and says what happens: the songs stay, they stream again when played.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(-1L) }
    var free by remember { mutableLongStateOf(Storage.freeBytes()) }
    var chosen by remember { mutableLongStateOf(Storage.chosenCeiling) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    LaunchedEffect(bump) {
        used = runCatching { Storage.usedBytes() }.getOrDefault(0)
        free = Storage.freeBytes()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        LibrarySection()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section(L.settingsStorage)
        Line(L.settingsCacheUsed(if (used < 0) "…" else Format.size(used).ifEmpty { L.settingsNothing }))
        Line(L.settingsFree(Format.size(free)))
        Text(L.settingsLimit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        for (choice in CachePolicy.CHOICES) {
            val label = if (choice == CachePolicy.UNBOUNDED) L.settingsLimitNone else Format.size(choice)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                    chosen = choice
                    prefs.setCacheCeiling(choice)
                    Storage.chosenCeiling = choice
                    scope.launch {
                        Storage.trim()
                        bump++
                    }
                }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == choice, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
            }
        }
        Text(
            L.settingsLimitNote(Format.size(Storage.ceiling())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(onClick = { confirm = true }, enabled = used > 0, modifier = Modifier.padding(16.dp)) { Text(L.settingsFreeUp) }
        message?.let { Line(it) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section(L.settingsAbout)
        Line("${L.appName} ${BuildConfig.VERSION_NAME}")
        Line(L.appUnofficial)
        Text(
            L.settingsLegal,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LEGAL_URL))) }
            }.padding(16.dp),
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(L.settingsFreeUpTitle(Format.size(used))) },
            text = { Text(L.settingsFreeUpBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val freed = Storage.freeUp()
                        message = if (freed == null) L.settingsFreeUpFailed else L.settingsFreedUp(Format.size(freed).ifEmpty { L.settingsNothing })
                        bump++
                    }
                }) { Text(L.settingsFreeUp) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L.commonCancel) } },
        )
    }
}

/**
 * The Library section: what the scan and the tag reader are doing, the counts, when the chats were
 * last checked, Pause or Resume, Check for new tracks now, files whose tags could not be read with
 * Try again, and each chat with its state (a failed one with its reason and Try again).
 */
@Composable
private fun LibrarySection() {
    val scope = rememberCoroutineScope()
    val flow = remember { Library.view() }
    val view by flow.collectAsStateWithLifecycle(initialValue = null)
    val paused by Library.isPaused.collectAsStateWithLifecycle()
    val scan by Library.scanner.status.collectAsStateWithLifecycle()
    val tags by Library.enricher.status.collectAsStateWithLifecycle()
    val changes by Library.changes.collectAsStateWithLifecycle()
    var chats by remember { mutableStateOf<List<ChatRow>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }

    LaunchedEffect(changes) {
        chats = runCatching { Library.index().pickedChats() }.getOrDefault(chats)
        runCatching { Library.enricher.refreshCounts() }
    }
    // The button stays off from the tap until the scan it started has ended, so a double tap runs once.
    LaunchedEffect(scan.running) {
        if (!scan.running) checking = false
    }

    Section(L.settingsLibrary)
    val current = view
    if (current != null) {
        Text(
            current.state,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // The reading progress has its own line below; a scan or a wait says more than the state does.
        if (current.phase == LibraryPhase.Scanning || current.phase == LibraryPhase.Waiting) Hint(current.line)
    }
    val counts = tags.counts
    Line(L.settingsLibraryCounts(counts.total, chats.size))
    if (counts.total > 0) Line(L.settingsDetails(counts.finished, counts.total))
    val last = chats.maxOfOrNull { it.scan.lastScanAt } ?: 0L
    Line(
        if (last > 0 && System.currentTimeMillis() / 1000 - last < 60) {
            L.settingsLastCheckedNow
        } else if (last > 0) {
            L.settingsLastChecked(
                DateUtils.getRelativeTimeSpanString(last * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            )
        } else {
            L.settingsLastNever
        },
    )
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (paused) Library.resume() else Library.pause() }) {
            Text(if (paused) L.scanResume else L.scanPause)
        }
        TextButton(
            enabled = !scan.running && !checking,
            onClick = {
                checking = true
                scope.launch { runCatching { Library.refresh() }.onFailure { checking = false } }
            },
        ) { Text(L.scanCheckNow) }
    }
    if (counts.failed > 0) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L.settingsDetailsFailed(counts.failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(
                enabled = !retrying,
                onClick = {
                    retrying = true
                    scope.launch {
                        runCatching { Library.retryFailedTags() }
                        runCatching { Library.enricher.refreshCounts() }
                        retrying = false
                    }
                },
            ) { Text(L.settingsRetryFailed) }
        }
    }
    if (chats.isNotEmpty()) {
        Text(L.settingsChats, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        for (chat in chats) {
            val failed = scan.failed[chat.chatId]
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            failed != null -> failed
                            chat.scan.audio.done && chat.scan.documents.done -> L.manageTracks(chat.trackCount)
                            else -> L.manageScanning(chat.trackCount)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failed != null) {
                    TextButton(onClick = { Library.scanner.scan(listOf(chat.chatId)) }) { Text(L.commonRetry) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Line(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val LEGAL_URL = "https://tmplayer.org/legal"
