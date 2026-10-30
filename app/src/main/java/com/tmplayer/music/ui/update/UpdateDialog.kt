// Adapted from TMPlayer (GPL-3.0), app/src/main/java/com/tmplayer/ui/update/UpdateDialog.kt, its
// update section of SettingsScreen.kt and the update wiring of MainActivity.kt: phone only, Material 3.
package com.tmplayer.music.ui.update

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.tmplayer.music.data.AfterUpdate
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.data.Release
import com.tmplayer.music.data.SelfInstaller
import com.tmplayer.music.data.Td
import com.tmplayer.music.data.UpdateRunner
import com.tmplayer.music.data.UpdateScheduler
import com.tmplayer.music.data.UpdateSettings
import com.tmplayer.music.data.UpdateFeed
import com.tmplayer.music.data.UpdateState
import com.tmplayer.music.data.Updates
import com.tmplayer.music.data.canInstall
import com.tmplayer.music.data.release
import com.tmplayer.music.data.unknownSourcesIntent
import com.tmplayer.music.data.updateScheduler
import com.tmplayer.music.data.updatesDir
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.ui.common.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** For writes that must outlive the popup closing (Later, Skip). */
private val updateScope = JobFailures.scope("UpdateDialog", Dispatchers.Main.immediate)

/**
 * The update check and its popup, beside the app's screens (MainActivity puts it next to AppRoot).
 *
 * - Each time the app comes to the front, [UpdateScheduler.FIRST_CHECK_DELAY_MS] later, with
 *   "Check for updates when the app opens" on, under its six hour throttle. Nothing runs on a timer:
 *   the app in the background asks nothing.
 * - The popup opens by itself once per version, only once signed in, never for a skipped version
 *   and not while snoozed.
 * - "Updated to TMPlayer Music 0.2.0" once, on the first launch after the app updated itself.
 *
 * Nothing at all in a build without the updater ([Updates.enabled], a store build).
 */
@Composable
fun UpdateHost() {
    if (!Updates.enabled) return
    val context = LocalContext.current
    val scheduler = remember { updateScheduler(context) }
    val state by Updates.state.collectAsStateWithLifecycle()
    val auth by Td.auth.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }

    // The check on coming to the front; leaving cancels a wait that has not run yet.
    LifecycleResumeEffect(Unit) {
        val job = updateScope.launch {
            delay(UpdateScheduler.FIRST_CHECK_DELAY_MS)
            try {
                scheduler.checkIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("UpdateHost", "update check failed", e)
            }
        }
        onPauseOrDispose { job.cancel() }
    }

    // "Updated to", once, on the first launch after an update.
    LaunchedEffect(Unit) {
        val updated = withContext(Dispatchers.IO) { runCatching { AfterUpdate.onLaunch(context) }.getOrNull() }
        if (updated != null) Toast.makeText(context, L.updateUpdatedTo(updated), Toast.LENGTH_LONG).show()
    }

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val offered = (state as? UpdateState.Available)?.takeUnless { it.skipped }?.release?.version
    val inApp = auth is AuthState.Ready && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(offered, inApp) {
        val version = offered ?: return@LaunchedEffect
        if (!inApp) return@LaunchedEffect
        delay(POPUP_DELAY_MS)
        val due = runCatching { scheduler.shouldPopUp(version) }.getOrDefault(false)
        if (due) {
            runCatching { scheduler.popupShown(version) }
            show = true
        }
    }

    if (show) UpdateDialog(onDismiss = { show = false; Updates.dismiss() })
}

/**
 * The update popup: "TMPlayer Music 0.2.0 is out", what is new, how it installs, and Update now,
 * Remind me later and Skip this version. Then the download with its progress and Cancel, and the
 * install. It also answers Settings' "Check for updates now" while the check runs and when there
 * is nothing new. [Updates] holds the state; this is only a window onto it.
 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsStateWithLifecycle()
    val scheduler = remember { updateScheduler(context) }

    // Read again each time the app comes back: the user may have just allowed installs.
    var allowed by remember { mutableStateOf(Updates.canInstall(context)) }
    var silent by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowed = Updates.canInstall(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(allowed) {
        silent = try {
            SelfInstaller.canUpdateSilently(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    val release = state.release
    val size = release?.let { Updates.apkFor(it)?.size } ?: 0L
    // A part left by an earlier try: the download goes on from there.
    var already by remember(release?.version) { mutableStateOf(0L) }
    LaunchedEffect(release?.version, state is UpdateState.Available) {
        val r = release ?: return@LaunchedEffect
        already = withContext(Dispatchers.IO) { runCatching { Updates.partFile(updatesDir(context), r).length() }.getOrDefault(0L) }
    }
    val downloading = state is UpdateState.Downloading
    val installing = state is UpdateState.Installing

    // Handing over to the system installer's screen is the end of this popup's job.
    LaunchedEffect(state) {
        if (state is UpdateState.Ready) onDismiss()
    }

    val primary: () -> Unit = {
        when {
            downloading || installing -> Unit
            release != null && !allowed -> runCatching { context.startActivity(Updates.unknownSourcesIntent(context)) }
            release != null -> UpdateRunner.start(context, release)
            else -> scope.launch {
                try {
                    scheduler.checkNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Updates.refuse(L.updateUnreachable)
                }
            }
        }
    }

    AlertDialog(
        // While the download runs a tap outside does not close it: it holds the progress and Cancel.
        onDismissRequest = { if (!downloading && !installing) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !downloading && !installing),
        title = { Text(title(state, release)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (release != null) {
                    Text(L.updateYouHave(Updates.installedVersion), style = MaterialTheme.typography.bodyMedium)
                    for (point in release.changes) Text("• $point", style = MaterialTheme.typography.bodyMedium)
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        val fraction = s.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateState.Installing, UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
                Text(
                    status(state, release, size, already, allowed, silent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Where a failed install is finished by hand, and where the whole notes live.
                if (state is UpdateState.Failed || state is UpdateState.Idle) {
                    TextButton(onClick = { openPage(context, release?.pageUrl ?: UpdateFeed.RELEASES_PAGE) }) {
                        Text(L.updateOpenReleasePage)
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloading -> TextButton(onClick = { UpdateRunner.cancel() }) { Text(L.commonCancel) }
                installing -> Unit
                else -> Button(
                    onClick = primary,
                    enabled = state !is UpdateState.Checking,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(primaryLabel(state, release, size, allowed)) }
            }
        },
        dismissButton = {
            // While installing the popup may be closed; the install goes on and says when it is done.
            if (installing) TextButton(onClick = onDismiss) { Text(L.updateClose) }
            if (downloading || installing) return@AlertDialog
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (release != null) {
                    TextButton(onClick = {
                        updateScope.launch { runCatching { scheduler.skip(release.version) } }
                        onDismiss()
                    }) { Text(L.updateSkip) }
                    TextButton(onClick = {
                        updateScope.launch { runCatching { scheduler.remindLater() } }
                        onDismiss()
                    }) { Text(L.updateLater) }
                } else {
                    TextButton(onClick = onDismiss) { Text(L.updateClose) }
                }
            }
        },
    )
}

/**
 * The Settings section: the update on offer, Check for updates now, the switch for the check when
 * the app opens, and Get test versions. Nothing in a build without the updater.
 */
@Composable
fun UpdateSettingsSection() {
    if (!Updates.enabled) return
    val context = LocalContext.current
    val settings = remember { UpdateSettings(context) }
    val state by Updates.state.collectAsStateWithLifecycle()
    var checkOnOpen by remember { mutableStateOf(settings.checkOnOpenNow()) }
    var testVersions by remember { mutableStateOf(settings.testVersionsNow()) }
    var show by remember { mutableStateOf(false) }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        L.settingsUpdates,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp),
    )
    state.release?.let { offered ->
        LinkRow(L.settingsUpdateRow(offered.version), L.settingsUpdateRowDetail(Format.size(Updates.apkFor(offered)?.size ?: 0L))) {
            show = true
        }
    }
    LinkRow(L.settingsCheckUpdates, L.settingsCheckUpdatesDetail(Updates.installedVersion, Updates.RELEASES_PAGE)) {
        show = true
        updateScope.launch { runCatching { updateScheduler(context).checkNow() } }
    }
    Toggle(L.settingsUpdateCheckOnOpen, L.settingsUpdateCheckOnOpenDetail, checkOnOpen) {
        checkOnOpen = it
        settings.setCheckOnOpen(it)
    }
    Toggle(L.settingsTestVersions, if (testVersions) L.settingsTestVersionsOn else L.settingsTestVersionsOff, testVersions) { on ->
        testVersions = on
        settings.setTestVersions(on)
        updateScope.launch {
            runCatching { Updates.testVersionsSwitched(on = on, skipped = settings.skippedVersion()) }
        }
    }

    if (show) UpdateDialog(onDismiss = { show = false; Updates.dismiss() })
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

private fun openPage(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

private fun title(state: UpdateState, release: Release?): String = when {
    release != null && (state as? UpdateState.Available)?.skipped == true -> L.updateTitleSkipped(release.version)
    release != null -> L.updateTitle(release.version)
    state is UpdateState.Idle -> L.updateUpToDate
    else -> L.updateCheckingForUpdates
}

private fun primaryLabel(state: UpdateState, release: Release?, size: Long, allowed: Boolean): String = when {
    state is UpdateState.Checking -> L.updateChecking
    release == null -> L.updateCheckAgain
    !allowed -> L.updateOpenSetting
    state is UpdateState.Failed -> L.commonRetry
    Updates.onMeteredNetwork() && size > 0 -> L.updateNowOnMobileData(action = L.updateUpdateNow, size = Format.size(size))
    else -> L.updateUpdateNow
}

/** The line under What's new: a failure, the answer to a check, or how the update reaches this phone. */
private fun status(state: UpdateState, release: Release?, size: Long, already: Long, allowed: Boolean, silent: Boolean): String {
    if (state is UpdateState.Failed) return state.message
    if (release == null) {
        return if (state is UpdateState.Checking) L.updateAskingGithub else L.updateNewest(Updates.installedVersion, Updates.RELEASES_PAGE)
    }
    val total = Format.size(size).ifEmpty { "?" }
    return when {
        state is UpdateState.Downloading -> L.updateDownloading(total)
        state is UpdateState.Installing -> L.updateInstallingRestart
        !allowed -> L.updateBlockedPhone
        already > 0 && size > 0 -> L.updateResumes(done = Format.size(already), size = total)
        silent -> L.updateSizeAndRestart(total)
        else -> L.updateSizeAndConfirm(total)
    }
}

/** How long after an offer appears the popup follows it. */
private const val POPUP_DELAY_MS = 2_000L
