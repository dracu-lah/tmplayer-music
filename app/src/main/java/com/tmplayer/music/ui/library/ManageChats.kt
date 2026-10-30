package com.tmplayer.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.Failures
import com.tmplayer.music.diagnostics.SendLogsButton
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ChatRow
import com.tmplayer.music.ui.common.Cover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The chosen chats with how many tracks each gave and how far its scan got. Remove asks first and
 * says what happens: the tracks leave the library, nothing in Telegram is touched. Tap a chat to see
 * its songs; Add chats opens the picker with the current choice ticked.
 */
@Composable
fun ManageChats(onAddChats: () -> Unit, onChat: (ChatRow) -> Unit) {
    val scope = rememberCoroutineScope()
    val changes by Library.changes.collectAsStateWithLifecycle()
    val status by Library.scanner.status.collectAsStateWithLifecycle()
    var chats by remember { mutableStateOf<List<ChatRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ChatRow?>(null) }

    LaunchedEffect(changes) {
        try {
            chats = Library.index().pickedChats()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = Failures.humanise(e)
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "add") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.manageBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddChats) { Text(L.manageAdd) }
                    TextButton(onClick = { scope.launch { runCatching { Library.refresh() } } }) { Text(L.manageRefresh) }
                }
            }
        }
        // A list that could not be read, or a chat whose scan failed: Send logs once, above the rows.
        if (error != null || status.failed.isNotEmpty()) {
            item(key = "error") {
                Column(Modifier.fillMaxWidth()) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    SendLogsButton(Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        val list = chats.orEmpty()
        if (chats != null && list.isEmpty()) {
            item(key = "empty") { Text(L.manageEmpty, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(list, key = { it.chatId }) { chat ->
            val failed = status.failed[chat.chatId]
            val state = when {
                failed != null -> failed
                chat.scan.audio.done && chat.scan.documents.done -> L.manageTracks(chat.trackCount)
                else -> L.manageScanning(chat.trackCount)
            }
            Row(
                Modifier.fillMaxWidth().clickable { onChat(chat) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(name = chat.title, fileId = 0, round = true, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failed != null) {
                    TextButton(onClick = { Library.scanner.scan(listOf(chat.chatId)) }) { Text(L.commonRetry) }
                }
                TextButton(onClick = { removing = chat }) { Text(L.manageRemove) }
            }
        }
    }

    removing?.let { chat ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(L.manageRemoveTitle(chat.title)) },
            text = { Text(L.manageRemoveBody(chat.trackCount)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        try {
                            Library.removeChat(chat.chatId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = Failures.humanise(e)
                        }
                    }
                }) { Text(L.manageRemove) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(L.commonCancel) } },
        )
    }
}
