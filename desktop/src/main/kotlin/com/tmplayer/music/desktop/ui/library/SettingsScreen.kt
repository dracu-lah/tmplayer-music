// Settings follows the Android app's Settings.kt and LyricsSettings.kt (app/.../ui/library): the same
// sections and rules (Library, Storage, Kept offline, Lyrics, Backup, About, Support), plus the
// desktop's own switch (close to the tray). Haptics are not here: a computer has none.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.covers.CoverOnline
import com.tmplayer.music.data.CachePolicy
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.LibraryPhase
import com.tmplayer.music.data.Storage
import com.tmplayer.music.desktop.BuildInfo
import com.tmplayer.music.desktop.DesktopApp
import com.tmplayer.music.desktop.diagnostics.Diagnostics
import com.tmplayer.music.desktop.diagnostics.SendLogsButton
import com.tmplayer.music.desktop.os.OpenExternal
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.autoFocus
import com.tmplayer.music.desktop.ui.common.guarded
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.platform.About
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Settings: Library (what the scan and the tag reader do, with Pause and Check now), Playback,
 * Storage (what the cache holds, what is free, the cache limit, Free up space), Kept offline,
 * Lyrics and covers, Backup, About, Support and the account. Free up space asks first and says what
 * happens: the songs stay and stream again when played, and kept files are never touched.
 */
@Composable
fun SettingsScreen(onManageChats: () -> Unit, onLogOut: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(title = L.settingsTitle, subtitle = null)
        LibrarySection(onManageChats)
        PlaybackSection()
        StorageSection()
        KeptOfflineSection()
        LyricsSection()
        BackupSection()
        AboutSection()
        SupportSection()
        AccountSection(onManageChats, onLogOut)
    }
}

// Pieces

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        content()
    }
}

@Composable
fun SettingsLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}

@Composable
fun SettingsHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
    )
}

/** A setting that is on or off, with a line saying what it does; the whole row toggles it. */
@Composable
fun SwitchLine(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** A row that does one thing on a click, with an optional line under it saying what. */
@Composable
private fun LinkRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// Library

/**
 * What the scan and the tag reader are doing, the counts, when the chats were last checked, Pause or
 * Resume, Check for new tracks now, files whose tags could not be read with Try again, each chat with
 * its state, and the switches for the progress banner and for short clips and voice files.
 */
@Composable
private fun LibrarySection(onManageChats: () -> Unit) {
    val scope = rememberCoroutineScope()
    val view by remember { Library.view() }.collectAsState(null)
    val paused by Library.isPaused.collectAsState()
    val scan by Library.scanner.status.collectAsState()
    val tags by Library.enricher.status.collectAsState()
    val changes by Library.changes.collectAsState()
    val showShort by Library.choices.showShort.collectAsState()
    val showVoice by Library.choices.showVoice.collectAsState()
    val showProgress by Library.choices.showProgress.collectAsState()
    var chats by remember { mutableStateOf<List<ChatRow>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        chats = runCatching { Library.index().pickedChats() }.getOrDefault(chats)
        runCatching { Library.enricher.refreshCounts() }
    }
    // The button stays off from the click until the scan it started has ended, so a double click runs once.
    LaunchedEffect(scan.running) { if (!scan.running) checking = false }

    SettingsSection(L.settingsLibrary) {
        view?.let { current ->
            SettingsLine(current.state)
            if (current.phase == LibraryPhase.Scanning || current.phase == LibraryPhase.Waiting || current.phase == LibraryPhase.WaitingWifi) SettingsHint(current.line)
        }
        val counts = tags.counts
        SettingsLine(L.settingsLibraryCounts(counts.total, chats.size))
        if (counts.total > 0) SettingsHint(L.settingsDetails(counts.finished, counts.total))
        val last = chats.maxOfOrNull { it.scan.lastScanAt } ?: 0L
        SettingsHint(
            when {
                last > 0 && System.currentTimeMillis() / 1000 - last < 60 -> L.settingsLastCheckedNow
                last > 0 -> L.settingsLastChecked(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last * 1000)))
                else -> L.settingsLastNever
            },
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (paused) Library.resume() else Library.pause() }) { Text(if (paused) L.scanResume else L.scanPause) }
            TextButton(
                enabled = !scan.running && !checking,
                onClick = {
                    checking = true
                    scope.launch { runCatching { Library.refresh() }.onFailure { checking = false } }
                },
            ) { Text(L.scanCheckNow) }
        }
        if (counts.failed > 0) {
            Row(Modifier.padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Text(L.settingsChats, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
            for (chat in chats) {
                val failed = scan.failed[chat.chatId]
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    if (failed != null) TextButton(onClick = { Library.scanner.scan(listOf(chat.chatId)) }) { Text(L.commonRetry) }
                }
            }
        }
        SwitchLine(L.settingsShowProgress, L.settingsShowProgressDetail, showProgress) { Library.choices.setShowProgress(it) }
        SwitchLine(L.chatfirstSettingsShort, L.chatfirstSettingsShortDetail, showShort) { Library.setShowClips(showShort = it) }
        SwitchLine(L.chatfirstSettingsVoice, L.chatfirstSettingsVoiceDetail, showVoice) { Library.setShowClips(showVoice = it) }
        // The most common report is a track that does not show up: say why it may not, and where to go.
        Text(L.chatfirstNotFindingTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
        SettingsHint(L.chatfirstNotFindingBody)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onManageChats) { Text(L.manageTitle) }
        }
    }
}

// Playback

@Composable
private fun PlaybackSection() {
    val closeToTray by DesktopApp.closeToTray.collectAsState()
    SettingsSection(L.settingsPlayback) {
        val trayOk = remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }
        SwitchLine(L.desktopCloseToTray, L.desktopCloseToTrayDetail, closeToTray && trayOk) { if (trayOk) DesktopApp.setCloseToTray(it) }
        // Without a tray the switch would hide the app for good, so it does nothing and says why.
        if (!trayOk) SettingsHint(L.desktopTrayUnavailable)
        if (!com.tmplayer.music.desktop.HostOs.mac) SettingsHint(L.desktopMediaKeysNote)
    }
}

// Storage

@Composable
private fun StorageSection() {
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(-1L) }
    var free by remember { mutableLongStateOf(Storage.freeBytes()) }
    var chosen by remember { mutableLongStateOf(Storage.chosenCeiling) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    LaunchedEffect(bump) {
        used = runCatching { Storage.usedBytes() }.getOrDefault(0)
        free = withContext(Dispatchers.IO) { Storage.freeBytes() }
    }
    SettingsSection(L.settingsStorage) {
        SettingsLine(L.settingsCacheUsed(if (used < 0) "…" else Format.size(used).ifEmpty { L.settingsNothing }))
        SettingsLine(L.settingsFree(Format.size(free)))
        Text(L.settingsLimit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
        for (choice in CachePolicy.CHOICES) {
            val label = if (choice == CachePolicy.UNBOUNDED) L.settingsLimitNone else Format.size(choice)
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == choice, role = Role.RadioButton) {
                    chosen = choice
                    DesktopApp.setCacheCeiling(choice)
                    scope.launch {
                        Storage.trim()
                        bump++
                    }
                }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == choice, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
            }
        }
        SettingsHint(L.settingsLimitNote(Format.size(Storage.ceiling())))
        message?.let { SettingsLine(it) }
        Button(onClick = { confirm = true }, enabled = used > 0, modifier = Modifier.padding(24.dp, 12.dp)) { Text(L.settingsFreeUp) }
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
            dismissButton = { TextButton(modifier = Modifier.autoFocus(), onClick = { confirm = false }) { Text(L.commonCancel) } },
        )
    }
}

// Lyrics and covers

/**
 * "Look up lyrics and covers online": on by default, with what is sent said, and Clear saved lyrics
 * and Clear saved covers (what was found online is kept on this computer only).
 */
@Composable
private fun LyricsSection() {
    val online by LyricsOnline.choices.online.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    SettingsSection(L.lyricsTitle) {
        SwitchLine(L.lyricsOnlineTitle, L.lyricsOnlineDetail, online) { LyricsOnline.choices.setOnline(it) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    guarded({ message = it }) {
                        withContext(Dispatchers.IO) { LyricsOnline.clear() }
                        message = L.lyricsCleared
                    }
                }
            }) { Text(L.lyricsClear) }
            TextButton(onClick = {
                scope.launch {
                    guarded({ message = it }) {
                        withContext(Dispatchers.IO) { CoverOnline.clear() }
                        message = L.lyricsCoversCleared
                    }
                }
            }) { Text(L.lyricsCoversClear) }
        }
        SettingsHint(L.lyricsClearDetail)
        message?.let { SettingsLine(it) }
    }
}

// About and support

@Composable
private fun AboutSection() {
    SettingsSection(L.settingsAbout) {
        Text(L.appName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
        SettingsHint(L.aboutVersion(Diagnostics.version()))
        SettingsHint(L.aboutWhat)
        SettingsHint(L.appUnofficial)
        LinkRow(L.settingsLegal) { OpenExternal.browse(About.LEGAL_URL) }
        LinkRow(L.aboutFeedback, L.aboutFeedbackDetail) { OpenExternal.browse(About.FEEDBACK_URL) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SendLogsButton()
                Text(L.logsSendDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
        Text(L.aboutLicence, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp))
        LinkRow(L.aboutLicenceOpen) { OpenExternal.browse(About.LICENCE_URL) }
        LinkRow(L.aboutOpensslOpen) { OpenExternal.browse(About.OPENSSL_EXCEPTION_URL) }
        LinkRow(L.aboutThirdPartyOpen) { OpenExternal.browse(About.THIRD_PARTY_URL) }
    }
}

/** Support links: voluntary, they unlock nothing. They open in the browser, bare. */
@Composable
private fun SupportSection() {
    SettingsSection(L.supportTitle) {
        SettingsHint(L.supportNote)
        LinkRow(L.supportBuyCoffee) { OpenExternal.browse(About.COFFEE) }
        LinkRow(L.supportSponsorGithub) { OpenExternal.browse(About.SPONSORS) }
        LinkRow(L.supportStarGithub) { OpenExternal.browse(About.SOURCE) }
    }
}

// Account

/** Manage chats and Log out; a log out is asked first, as it clears this computer's library. */
@Composable
private fun AccountSection(onManageChats: () -> Unit, onLogOut: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SettingsSection(L.shellAccountMenu) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onManageChats) { Text(L.manageTitle) }
            TextButton(onClick = { confirm = true }) { Text(L.shellLogOut) }
        }
        SettingsHint("${L.appName} ${BuildInfo.VERSION}")
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(L.shellLogOutTitle) },
            text = { Text(L.shellLogOutBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onLogOut()
                }) { Text(L.shellLogOut) }
            },
            dismissButton = { TextButton(modifier = Modifier.autoFocus(), onClick = { confirm = false }) { Text(L.commonCancel) } },
        )
    }
}
