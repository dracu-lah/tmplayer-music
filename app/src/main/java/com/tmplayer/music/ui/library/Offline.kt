package com.tmplayer.music.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.KeepPhase
import com.tmplayer.music.data.KeepSkip
import com.tmplayer.music.data.KeepState
import com.tmplayer.music.data.KeepSummary
import com.tmplayer.music.data.KeptTrack
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.OfflineDownloads
import com.tmplayer.music.data.OfflineHost
import com.tmplayer.music.data.OfflinePlanner
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ClipRules
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.ui.common.Format
import com.tmplayer.music.ui.tv.tvFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep offline, on screen: the entries in the menus (a song's menu, an album's, a playlist's, a
 * chat's header), the mark on a kept row, the questions asked before a big or a too big step, the
 * summary when a run ends, and the Kept offline section of Settings. The rules live in `:core`
 * ([OfflineDownloads], [OfflinePlanner]); this only asks and shows.
 *
 * Every dialog opens with Cancel focused, so a D-pad's OK never starts a large download by habit.
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
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Keeps the default version of each of [songs]. */
    fun keepSongs(songs: List<Song>, bulk: Boolean = songs.size > 1) =
        keep(bulk) { songs.mapNotNull { runCatching { Library.index().track(it.defaultTrack) }.getOrNull() } }

    /** Keeps every file of a picked chat that the library shows (the clip switches apply). */
    fun keepChat(chatId: Long) = keep(true) {
        val index = Library.index()
        val mask = Library.choices.hiddenMask
        val out = ArrayList<Track>()
        var after: Long? = null
        while (out.size < MAX_CHAT_TRACKS) {
            val page = index.chatTracks(chatId, true, after, PAGE)
            if (page.isEmpty()) break
            page.filterTo(out) { !ClipRules.hidden(it, mask) }
            after = page.last().messageId
        }
        out
    }

    private fun keep(bulk: Boolean, load: suspend () -> List<Track>) {
        // The same tap twice (or a second entry while the first is counting) is one run.
        if (!working.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (bulk) toasts.tryEmit(L.offlineCounting)
                val tracks = runCatching { load() }.getOrDefault(emptyList())
                val plan = OfflineDownloads.plan(tracks)
                when {
                    plan.nothingNew -> toasts.tryEmit(L.offlineNothingNew)
                    plan.toKeep.isEmpty() -> ask.value = Ask.NoRoom(plan)
                    !plan.fitsAll -> ask.value = Ask.Fit(plan)
                    plan.needsConfirm -> ask.value = Ask.Confirm(plan)
                    else -> start(plan)
                }
            } finally {
                working.set(false)
            }
        }
    }

    fun start(plan: OfflinePlanner.Plan) {
        ask.value = null
        // The plan was made a moment ago (or before a question was answered): the queue looks at the
        // space again before each track, so a disk that filled meanwhile skips what no longer fits.
        OfflineDownloads.keep(plan)
        toasts.tryEmit(L.offlineKeeping(plan.toKeep.size))
    }

    /** Removes kept tracks: one at once, several after asking. */
    fun remove(ids: List<TrackId>) {
        val kept = ids.filter { OfflineDownloads.isKept(it) }
        val waiting = ids.filter { it in OfflineDownloads.pending.value }
        waiting.forEach { OfflineDownloads.cancel(it) }
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
            if (n > 0) toasts.tryEmit(L.offlineRemoved(n))
        }
    }

    private const val PAGE = 500
    private const val MAX_CHAT_TRACKS = 100_000
}

// ---- the mark on a kept row ------------------------------------------------------------------

/** A small tick in a circle on a row that is kept offline. Nothing for a row that is not. */
@Composable
fun OfflineMark(id: TrackId?, modifier: Modifier = Modifier) {
    if (id == null) return
    val kept = OfflineDownloads.kept.collectAsStateWithLifecycle()
    val on by remember(id) { derivedStateOf { id in kept.value } }
    if (!on) return
    Icon(
        KeptIcon,
        contentDescription = L.offlineBadgeDescription,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(18.dp),
    )
}

private val KeptIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "KeptOffline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M12 3C16.9706 3 21 7.0294 21 12C21 16.9706 16.9706 21 12 21C7.0294 21 3 16.9706 3 12C3 7.0294 7.0294 3 12 3Z" + "M8 12.5L10.8 15.2L16 9.5"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()
}

// ---- the entries -----------------------------------------------------------------------------

/** The song menu's line: Keep offline, Remove from offline, or Stop keeping while it waits its turn. */
@Composable
fun OfflineMenuLine(song: Song, onDismiss: () -> Unit) {
    val id = song.defaultTrack
    val kept by OfflineDownloads.kept.collectAsStateWithLifecycle()
    val pending by OfflineDownloads.pending.collectAsStateWithLifecycle()
    val label = when {
        id in kept -> L.offlineRemove
        id in pending -> L.offlineStop
        else -> L.offlineKeep
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (id in kept || id in pending) OfflineUi.remove(listOf(id)) else OfflineUi.keepSongs(listOf(song))
                onDismiss()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/** Keep offline (or Remove from offline once all of them are kept) for an album or a playlist. */
@Composable
fun KeepOfflineButton(songs: List<Song>?, modifier: Modifier = Modifier) {
    val list = songs.orEmpty()
    if (list.isEmpty()) return
    val kept by OfflineDownloads.kept.collectAsStateWithLifecycle()
    val allKept by remember(list) { derivedStateOf { list.all { it.defaultTrack in kept } } }
    TextButton(
        modifier = modifier.tvFocusRing(),
        onClick = { if (allKept) OfflineUi.remove(list.map { it.defaultTrack }) else OfflineUi.keepSongs(list, bulk = true) },
    ) {
        Icon(KeptIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(if (allKept) L.offlineRemove else L.offlineKeep, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A chat page's Keep chat offline, and Remove from offline when some of it is kept. */
@Composable
fun KeepChatOfflineButton(chatId: Long) {
    val kept by OfflineDownloads.kept.collectAsStateWithLifecycle()
    val mine by remember(chatId) { derivedStateOf { kept.keys.filter { it.chatId == chatId } } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineUi.keepChat(chatId) }) {
            Icon(KeptIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(L.offlineKeepChat, modifier = Modifier.padding(start = 6.dp))
        }
        if (mine.isNotEmpty()) {
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineUi.remove(mine) }) { Text(L.offlineRemove) }
        }
    }
}

// ---- the questions and the summary -----------------------------------------------------------

/** Shows the questions, the toasts and the summary of Keep offline; placed once, in the library. */
@Composable
fun KeepOfflineHost() {
    val context = LocalContext.current
    val ask by OfflineUi.ask.collectAsStateWithLifecycle()
    val state by OfflineDownloads.state.collectAsStateWithLifecycle()
    val wifi by OfflineDownloads.wifiOnly.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        OfflineUi.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
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

/** A question whose Cancel has the focus: the D-pad's OK and a stray tap never confirm by habit. */
@Composable
private fun AskDialog(title: String, text: String, confirm: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (confirm != null) TextButton(modifier = Modifier.tvFocusRing(), onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.focusRequester(focus).tvFocusRing(), onClick = onDismiss) {
                Text(if (confirm == null) L.offlineOk else L.commonCancel)
            }
        },
    )
}

/** What a run did: how many were kept, and each track skipped with the reason. */
@Composable
private fun SummaryDialog(summary: KeepSummary) {
    val dismiss = { OfflineDownloads.dismissSummary(); Unit }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
        confirmButton = {
            TextButton(modifier = Modifier.focusRequester(focus).tvFocusRing(), onClick = dismiss) { Text(L.offlineOk) }
        },
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

// ---- Settings: the Kept offline section ------------------------------------------------------

/**
 * Kept offline in Settings, under Storage: how much is kept, the Wi-Fi only switch, what is being
 * fetched (with Pause and Stop), the list with a Remove on each, and Remove all (asks first).
 */
@Composable
fun KeptOfflineSection() {
    val kept by OfflineDownloads.kept.collectAsStateWithLifecycle()
    val state by OfflineDownloads.state.collectAsStateWithLifecycle()
    val wifi by OfflineDownloads.wifiOnly.collectAsStateWithLifecycle()
    val refused by OfflineHost.refused.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var listing by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val bytes = remember(kept) { kept.values.sumOf { it.sizeBytes.coerceAtLeast(0) } }

    Text(
        L.offlineSectionTitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    Text(
        if (kept.isEmpty()) L.offlineListEmpty else L.offlineSectionTotal(kept.size, Format.size(bytes).ifEmpty { "0" }),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    Text(
        L.offlineSectionNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    QueueLines(state, refused)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = wifi, role = Role.Switch, onValueChange = { OfflineDownloads.setWifiOnly(it) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L.offlineWifiOnly, style = MaterialTheme.typography.bodyLarge)
            Text(L.offlineWifiOnlyDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = wifi, onCheckedChange = null)
    }
    if (kept.isNotEmpty()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { listing = true }) { Text(L.offlineListShow) }
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { confirmAll = true }) { Text(L.offlineRemoveAll) }
        }
    }
    if (listing) KeptListDialog(kept.values.toList()) { listing = false }
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
private fun QueueLines(state: KeepState, refused: Boolean) {
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
        if (refused) Text(L.offlineRefused, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == KeepPhase.Paused) {
                TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineDownloads.resume() }) { Text(L.offlineResume) }
            } else {
                TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineDownloads.pause() }) { Text(L.offlinePause) }
            }
            TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineDownloads.cancelAll() }) { Text(L.offlineStopAll) }
        }
    }
}

/** Every kept track, newest first, each with Remove. A list as long as the library scrolls without cost. */
@Composable
private fun KeptListDialog(entries: List<KeptTrack>, onClose: () -> Unit) {
    val sorted = remember(entries) { entries.sortedByDescending { it.keptAtSec } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(L.offlineListTitle) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sorted, key = { "${it.chatId}:${it.messageId}" }) { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { entry.fileName },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(entry.artist, Format.size(entry.sizeBytes)).filter { it.isNotBlank() }.joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(modifier = Modifier.tvFocusRing(), onClick = { OfflineDownloads.remove(entry.id) }) { Text(L.offlineListRemove) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.focusRequester(focus).tvFocusRing(), onClick = onClose) { Text(L.offlineClose) }
        },
    )
}
