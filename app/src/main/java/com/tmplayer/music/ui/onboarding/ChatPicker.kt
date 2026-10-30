package com.tmplayer.music.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.ChatDirectory
import com.tmplayer.music.data.ChatEntry
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.FirstRun
import com.tmplayer.music.ui.theme.Pill
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.tmplayer.music.diagnostics.SendLogsButton
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Cover
import kotlinx.coroutines.CancellationException
import java.text.Normalizer

/** A chat as the picker returns it. */
data class PickedChat(val chatId: Long, val title: String)

/**
 * Pick the chats with music (`plan/RESEARCH.md` 4.1). Saved Messages first, then the user's chats in
 * Telegram's order, then the archive. Each row asks for its audio count only once it is on screen,
 * and remembers it. Nothing is ticked for the user except Saved Messages, and only when it has
 * audio in it. An empty choice is allowed and lands on an empty library.
 *
 * [already] are the chats picked before (Manage chats), shown ticked; [onDone] gets the whole choice.
 *
 * On the first run (nothing picked before) the list is led by "Start with Saved Messages": one tap
 * picks Saved Messages and the few chats with the most audio ([FirstRun]), from the counts of
 * Saved Messages and the top of the list, asked for as the picker opens. The full list stays below.
 */
@Composable
fun ChatPicker(
    directory: ChatDirectory,
    already: Set<Long>,
    onDone: (List<PickedChat>) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var chats by remember { mutableStateOf<List<ChatEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val ticked = remember { mutableStateMapOf<Long, Boolean>().apply { already.forEach { put(it, true) } } }
    val counts = remember { mutableStateMapOf<Long, Int>() }
    var touchedSaved by rememberSaveable { mutableStateOf(already.isNotEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf<List<ChatEntry>?>(null) }
    val firstRun = already.isEmpty()
    var starting by remember { mutableStateOf(false) }
    var startNone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name: (ChatEntry) -> String = { if (it.savedMessages) L.pickerSavedMessages else it.title.ifBlank { L.pickerUntitled } }

    suspend fun countOf(chat: ChatEntry): Int? {
        val count = try {
            directory.audioCount(chat.chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (count != null) counts[chat.chatId] = count
        return count
    }

    // The one tap needs the counts of Saved Messages and the chats at the top before anything is shown.
    LaunchedEffect(chats, firstRun) {
        val all = chats ?: return@LaunchedEffect
        if (!firstRun) return@LaunchedEffect
        FirstRun.toCount(all).forEach { chat -> launch { if (!counts.containsKey(chat.chatId)) countOf(chat) } }
    }

    val startWithSaved: () -> Unit = start@{
        val all = chats ?: return@start
        if (starting) return@start
        starting = true
        startNone = false
        scope.launch {
            // Counts already in come back at once; the rest get a few seconds, then what is known is used.
            withTimeoutOrNull(START_BUDGET_MS) {
                FirstRun.toCount(all).map { chat -> async { counts[chat.chatId] ?: countOf(chat) } }.awaitAll()
            }
            val picked = FirstRun.pick(all, counts.toMap())
            starting = false
            if (picked.isEmpty()) startNone = true else onDone(picked.map { PickedChat(it.chatId, name(it)) })
        }
    }

    LaunchedEffect(attempt) {
        failure = null
        try {
            chats = directory.chats()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failures.humanise(e)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.pickerTitle, style = MaterialTheme.typography.headlineSmall)
                Text(L.pickerBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    placeholder = { Text(L.pickerSearch) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val all = chats
            when {
                failure != null -> Message(failure.orEmpty()) {
                    TextButton(onClick = { attempt++ }) { Text(L.commonRetry) }
                    SendLogsButton()
                }
                all == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                all.isEmpty() -> Message(L.pickerEmpty) {}
                else -> {
                    val needle = fold(query)
                    val shown = if (needle.isEmpty()) all else all.filter { fold(it.title).contains(needle) }
                    val row: @Composable (ChatEntry) -> Unit = { chat ->
                        LaunchedEffect(chat.chatId) {
                            if (counts.containsKey(chat.chatId)) return@LaunchedEffect
                            val count = try {
                                directory.audioCount(chat.chatId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                            if (count != null) {
                                counts[chat.chatId] = count
                                if (chat.savedMessages && !touchedSaved && count > 0) ticked[chat.chatId] = true
                            }
                        }
                        ChatRow(
                            chat = chat,
                            count = counts[chat.chatId],
                            checked = ticked[chat.chatId] == true,
                            onToggle = {
                                if (ticked[chat.chatId] == true) ticked.remove(chat.chatId) else ticked[chat.chatId] = true
                                if (chat.savedMessages) touchedSaved = true
                            },
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        if (firstRun && needle.isEmpty()) {
                            item(key = "startWithSaved") { StartCard(starting, startNone, startWithSaved) }
                        }
                        if (shown.isNotEmpty()) {
                            item(key = "selectAll") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        val untick = shown.all { ticked[it.chatId] == true }
                                        if (untick) {
                                            shown.forEach { ticked.remove(it.chatId) }
                                        } else if (shown.size > LARGE_SELECTION) {
                                            confirmAll = shown
                                        } else {
                                            shown.forEach { ticked[it.chatId] = true }
                                        }
                                        touchedSaved = true
                                    }) {
                                        Text(if (shown.all { ticked[it.chatId] == true }) L.pickerSelectNone else L.pickerSelectAll)
                                    }
                                }
                            }
                        }
                        val (archived, main) = shown.partition { it.archived }
                        items(main, key = { it.chatId }) { chat -> row(chat) }
                        if (archived.isNotEmpty()) {
                            item(key = "archivedHeader") {
                                Text(
                                    L.pickerArchived,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(archived, key = { it.chatId }) { chat -> row(chat) }
                        }
                    }
                }
            }
            val picked = all.orEmpty().filter { ticked[it.chatId] == true }
            val known = picked.sumOf { counts[it.chatId] ?: 0 }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picked.isNotEmpty()) {
                    Text(L.pickerTotal(picked.size, known), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) TextButton(onClick = onBack) { Text(L.commonBack) }
                    Button(
                        onClick = { onDone(picked.map { PickedChat(it.chatId, name(it)) }) },
                        enabled = all != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (picked.isEmpty()) L.pickerLater else L.commonContinue) }
                }
            }
        }
    }

    confirmAll?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmAll = null },
            title = { Text(L.pickerConfirmAllTitle(pending.size)) },
            text = { Text(L.pickerConfirmAllBody) },
            confirmButton = {
                TextButton(onClick = {
                    pending.forEach { ticked[it.chatId] = true }
                    confirmAll = null
                }) { Text(L.pickerConfirmAllYes) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = null }) { Text(L.commonCancel) } },
        )
    }
}

/**
 * The one tap first run: a large card with "Start with Saved Messages", what it picks, and the way
 * to the full list below. While the counts come in the button says so; with no audio found it says
 * to pick below.
 */
@Composable
private fun StartCard(starting: Boolean, none: Boolean, onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, enabled = !starting, shape = Pill, modifier = Modifier.fillMaxWidth()) {
                    if (starting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(L.chatfirstPickerStart)
                }
                Text(
                    when {
                        starting -> L.chatfirstPickerStartCounting
                        none -> L.chatfirstPickerStartNone
                        else -> L.chatfirstPickerStartDetail
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            L.chatfirstPickerOrPick,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ChatRow(chat: ChatEntry, count: Int?, checked: Boolean, onToggle: () -> Unit) {
    val title = if (chat.savedMessages) L.pickerSavedMessages else chat.title.ifBlank { L.pickerUntitled }
    val line = when {
        count == null -> ""
        count == 0 -> L.pickerNoAudio
        else -> L.pickerCount(count)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $line" }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(name = title, fileId = chat.photoFileId, round = true, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count == null) {
                // A quiet bar where the count will be, so the row does not jump when it arrives.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).width(84.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                ) {}
            } else {
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** Case and accent folded, for a search that finds "Cafe" when typed "cafe". */
private fun fold(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"), "")

private const val LARGE_SELECTION = 50

/** How long the one tap waits for the counts it lacks before it picks from what is known. */
private const val START_BUDGET_MS = 4_000L
