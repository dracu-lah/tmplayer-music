// Keep offline on the desktop follows the Android app's Offline.kt (app/.../ui/library): the rules
// live in :core (OfflineDownloads, OfflinePlanner, KeepEngine); this only asks and shows.
package com.tmplayer.music.desktop.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.KeepPhase
import com.tmplayer.music.data.KeepSkip
import com.tmplayer.music.data.KeepState
import com.tmplayer.music.data.KeepSummary
import com.tmplayer.music.data.KeptTrack
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.OfflineDownloads
import com.tmplayer.music.data.OfflinePlanner
import com.tmplayer.music.desktop.ui.common.Format
import com.tmplayer.music.desktop.ui.common.autoFocus
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ClipRules
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep offline, on screen: the menu entries, the questions asked before a big or a too big step,
 * the summary when a run ends. Space is checked first (what is needed, what is free, what fits),
 * a big step is confirmed, and Cancel is the button the keyboard lands on, so Enter never starts a
 * large download by habit.
 */
object OfflineUi {

    sealed interface Ask {
        /** Some of the tracks do not fit: the ones that do can be kept. */
        data class Fit(val plan: OfflinePlanner.Plan) : Ask

        /** None of them fit. */
        data class NoRoom(val plan: OfflinePlanner.Plan) : Ask

        /** A big step: many tracks or many bytes, asked first. */
        data class Confirm(val plan: OfflinePlanner.Plan) : Ask

        /** Removing several kept tracks at once. */
        data class Remove(val ids: List<TrackId>) : Ask
    }

    private val scope = JobFailures.scope("OfflineUi", Dispatchers.Default)
    private val working = AtomicBoolean(false)

    val ask = MutableStateFlow<Ask?>(null)

    /** Says a line to the user (the shell's snackbar). */
    @Volatile
    var notify: (String) -> Unit = {}

    /** Keeps the default version of each of [songs]. */
    fun keepSongs(songs: List<Song>, bulk: Boolean = songs.size > 1) =
        keep(bulk) {
            OfflineDownloads.plan(songs.mapNotNull { runCatching { Library.index().track(it.defaultTrack) }.getOrNull() })
        }

    /** Keeps the files of a chat page as they are (each one its own file, no song folding). */
    fun keepTracks(tracks: List<Track>) = keep(tracks.size > 1) { OfflineDownloads.plan(tracks) }

    /**
     * Keeps every file of a picked chat that the library shows (the clip switches apply). Planned a
     * page at a time: only the small queue entries of the new tracks are held, never 100,000 tracks.
     */
    fun keepChat(chatId: Long) = keep(true) {
        val index = Library.index()
        val mask = Library.choices.hiddenMask
        val kept = OfflineDownloads.kept.value
        val planner = PagedPlanner(
            kept = kept.keys,
            queued = OfflineDownloads.pending.value,
            keptUniques = kept.values.mapTo(HashSet()) { it.uniqueId },
            freeBytes = OfflineDownloads.freeBytes(),
        )
        var seen = 0
        var after: Long? = null
        while (seen < MAX_CHAT_TRACKS) {
            val page = index.chatTracks(chatId, true, after, PAGE)
            if (page.isEmpty()) break
            planner.add(page.filter { !ClipRules.hidden(it, mask) })
            seen += page.size
            after = page.last().messageId
        }
        planner.finish()
    }

    /** [plan] runs off the window's thread; a failure to read or count is said, not shown as an empty list. */
    private fun keep(bulk: Boolean, plan: suspend () -> OfflinePlanner.Plan) {
        // The same click twice (or a second entry while the first is counting) is one run.
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (bulk) notify(L.offlineCounting)
                val counted = try {
                    plan()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "could not count the tracks to keep", e)
                    notify(Failures.humanise(e))
                    return@launch
                }
                when {
                    counted.nothingNew -> notify(L.offlineNothingNew)
                    counted.toKeep.isEmpty() -> ask.value = Ask.NoRoom(counted)
                    !counted.fitsAll -> ask.value = Ask.Fit(counted)
                    counted.needsConfirm -> ask.value = Ask.Confirm(counted)
                    else -> start(counted)
                }
            } finally {
                working.set(false)
            }
        }
    }

    fun start(plan: OfflinePlanner.Plan) {
        ask.value = null
        // The queue looks at the space again before each track, so a disk that filled meanwhile
        // skips what no longer fits.
        OfflineDownloads.keep(plan)
        notify(L.offlineKeeping(plan.toKeep.size + plan.links.size))
    }

    /** Removes kept tracks: one at once, several after asking. */
    fun remove(ids: List<TrackId>) {
        val kept = ids.filter { OfflineDownloads.isKept(it) }
        ids.filter { it in OfflineDownloads.pending.value }.forEach { OfflineDownloads.cancel(it) }
        when {
            kept.isEmpty() -> Unit
            kept.size == 1 -> removeNow(kept)
            else -> ask.value = Ask.Remove(kept)
        }
    }

    fun removeNow(ids: List<TrackId>) {
        ask.value = null
        scope.launch {
            val n = ids.count { OfflineDownloads.remove(it) }
            if (n > 0) notify(L.offlineRemoved(n))
        }
    }

    private const val PAGE = 500
    private const val MAX_CHAT_TRACKS = 100_000
    private const val TAG = "OfflineUi"
}

/** Shows the questions and the summary of Keep offline; placed once, in the shell. */
@Composable
fun KeepOfflineHost() {
    val ask by OfflineUi.ask.collectAsState()
    val state by OfflineDownloads.state.collectAsState()
    val wifi by OfflineDownloads.wifiOnly.collectAsState()
    val cancel = { OfflineUi.ask.value = null }
    when (val current = ask) {
        is OfflineUi.Ask.Confirm -> AskDialog(
            title = L.offlineConfirmTitle(current.plan.toKeep.size),
            text = L.offlineConfirmBody(Format.size(current.plan.keepBytes), Format.size(current.plan.freeBytes)) +
                " " + if (wifi) L.offlineConfirmWifi else L.offlineConfirmMobile,
            confirm = L.offlineKeep,
            onConfirm = { OfflineUi.start(current.plan) },
            onDismiss = cancel,
        )
        is OfflineUi.Ask.Fit -> AskDialog(
            title = L.offlineSpaceTitle,
            text = L.offlineSpaceBody(Format.size(current.plan.neededBytes), Format.size(current.plan.freeBytes)),
            confirm = L.offlineKeepFits(current.plan.toKeep.size),
            onConfirm = { OfflineUi.start(current.plan) },
            onDismiss = cancel,
        )
        is OfflineUi.Ask.NoRoom -> AskDialog(
            title = L.offlineNoSpaceTitle,
            text = L.offlineNoSpaceBody(Format.size(current.plan.neededBytes), Format.size(current.plan.freeBytes)),
            confirm = null,
            onConfirm = {},
            onDismiss = cancel,
        )
        is OfflineUi.Ask.Remove -> AskDialog(
            title = L.offlineRemoveTitle(current.ids.size),
            text = L.offlineRemoveBody,
            confirm = L.offlineRemove,
            onConfirm = { OfflineUi.removeNow(current.ids) },
            onDismiss = cancel,
        )
        null -> Unit
    }
    state.summary?.let { SummaryDialog(it) }
}

/** A question whose Cancel has the focus: Enter and a stray click never confirm by habit. */
@Composable
internal fun AskDialog(title: String, text: String, confirm: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (confirm != null) TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.autoFocus(), onClick = onDismiss) { Text(if (confirm == null) L.offlineOk else L.commonCancel) }
        },
    )
}

/** What a run did: how many were kept, and each track skipped with the reason. */
@Composable
private fun SummaryDialog(summary: KeepSummary) {
    val dismiss = { OfflineDownloads.dismissSummary(); Unit }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(L.offlineSummaryTitle) },
        text = {
            Column(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.offlineSummaryKept(summary.kept, Format.size(summary.keptBytes).ifEmpty { "0" }))
                if (summary.skipped.isNotEmpty()) {
                    Text(L.offlineSummarySkipped(summary.skipped.size), style = MaterialTheme.typography.titleSmall)
                    LazyColumn {
                        items(summary.skipped.take(MAX_SKIPS_SHOWN)) { skip ->
                            Text(
                                L.offlineSkipLine(skip.item.title.ifBlank { skip.item.fileName }, reasonText(skip)),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        if (summary.skipped.size > MAX_SKIPS_SHOWN) {
                            item { Text(L.offlineSkipMore(summary.skipped.size - MAX_SKIPS_SHOWN), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.autoFocus(), onClick = dismiss) { Text(L.offlineOk) } },
    )
}

private const val MAX_SKIPS_SHOWN = 50

internal fun reasonText(skip: KeepSkip): String = when (skip.reason) {
    OfflinePlanner.Skip.NoSpace -> L.offlineReasonNoSpace
    OfflinePlanner.Skip.NotAllowed -> L.offlineReasonNotAllowed
    OfflinePlanner.Skip.Gone -> L.offlineReasonGone
    OfflinePlanner.Skip.Stalled -> L.offlineReasonStalled
    OfflinePlanner.Skip.Failed -> skip.detail.takeIf { it.isNotBlank() } ?: L.offlineReasonFailed
    OfflinePlanner.Skip.AlreadyKept, OfflinePlanner.Skip.Duplicate -> L.offlineReasonFailed
}

// Settings: the Kept offline section

/**
 * Kept offline in Settings, under Storage: how much is kept, the Wi-Fi only switch, what is being
 * fetched (with Pause and Stop), the list with a Remove on each, and Remove all (asks first).
 * Free up space never touches these files: they live outside TDLib's cache.
 */
@Composable
fun KeptOfflineSection() {
    val kept by OfflineDownloads.kept.collectAsState()
    val state by OfflineDownloads.state.collectAsState()
    val wifi by OfflineDownloads.wifiOnly.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    val bytes = remember(kept) { kept.values.sumOf { it.sizeBytes.coerceAtLeast(0) } }

    SettingsSection(L.offlineSectionTitle) {
        SettingsLine(if (kept.isEmpty()) L.offlineListEmpty else L.offlineSectionTotal(kept.size, Format.size(bytes).ifEmpty { "0" }))
        SettingsHint(L.offlineSectionNote)
        QueueLines(state)
        SwitchLine(L.offlineWifiOnly, L.offlineWifiOnlyDetail, wifi) { OfflineDownloads.setWifiOnly(it) }
        if (kept.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmAll = true }) { Text(L.offlineRemoveAll) }
            }
            KeptList(kept.values.toList())
        }
    }
    if (confirmAll) {
        AskDialog(
            title = L.offlineRemoveAllTitle,
            text = L.offlineRemoveAllBody(Format.size(bytes).ifEmpty { "0" }),
            confirm = L.offlineRemoveAll,
            onConfirm = {
                confirmAll = false
                scope.launch { runCatching { OfflineDownloads.removeAll() } }
            },
            onDismiss = { confirmAll = false },
        )
    }
}

/** What the queue is doing, with Pause or Resume and Stop; nothing while it is empty. */
@Composable
private fun QueueLines(state: KeepState) {
    if (!state.hasWork) return
    val left = state.queued + state.running.size
    val line = when (state.phase) {
        KeepPhase.Running -> L.offlineStateRunning((state.done + 1).coerceAtMost(state.total.coerceAtLeast(1)), state.total)
        KeepPhase.WaitingNetwork -> L.offlineStateWaitNetwork(left)
        KeepPhase.WaitingWifi -> L.offlineStateWaitWifi(left)
        KeepPhase.Paused -> L.offlineStatePaused(left)
        KeepPhase.Idle -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(line, style = MaterialTheme.typography.bodyLarge)
        val one = state.running.firstOrNull()
        if (one != null && state.phase == KeepPhase.Running) {
            Text(
                if (one.waitUntilMs > 0) L.offlineStateFlood else one.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (one.total > 0 && one.waitUntilMs == 0L) {
                LinearProgressIndicator(progress = { (one.downloaded.toFloat() / one.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == KeepPhase.Paused) {
                TextButton(onClick = { OfflineDownloads.resume() }) { Text(L.offlineResume) }
            } else {
                TextButton(onClick = { OfflineDownloads.pause() }) { Text(L.offlinePause) }
            }
            TextButton(onClick = { OfflineDownloads.cancelAll() }) { Text(L.offlineStopAll) }
        }
    }
}

/** Every kept track, newest first, each with Remove. Shown in full under the section: a list of thousands scrolls with the page. */
@Composable
private fun KeptList(entries: List<KeptTrack>) {
    val sorted = remember(entries) { entries.sortedByDescending { it.keptAtSec } }
    Text(L.offlineListTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    // A bounded box with its own scroll, so a very long list does not stretch the settings page.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(sorted, key = { "${it.chatId}:${it.messageId}" }) { entry ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.fileName }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(entry.artist, Format.size(entry.sizeBytes)).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { OfflineDownloads.remove(entry.id) }) { Text(L.offlineListRemove) }
            }
        }
    }
}
