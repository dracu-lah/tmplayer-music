package com.tmplayer.music.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import com.tmplayer.music.connect.ChatSend
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.connect.Connect
import com.tmplayer.music.connect.PhoneRemote
import com.tmplayer.music.connect.SendHow
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Song
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.tvFocusRing

/** True on a phone (not a TV) with a TV paired, in the regular APK: where Play on TV and Add to TV queue are offered. */
@Composable
fun hasPairedTv(): Boolean {
    if (!Connect.available || LocalTv.current) return false
    val tvs by Connect.tvList.collectAsStateWithLifecycle()
    return tvs.isNotEmpty()
}

/**
 * Play on TV and Add to TV queue for [songs] (read when pressed, so a long list is not built until
 * asked), as two buttons in a header. Nothing when no TV is paired.
 */
@Composable
fun SendToTvButtons(songs: () -> List<Song>, modifier: Modifier = Modifier) {
    if (!hasPairedTv()) return
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(modifier = Modifier.tvFocusRing(), onClick = {
            songs().takeIf { it.isNotEmpty() }?.let { PhoneRemote.sendAndTell(context, SendHow.Play, it) }
        }) { Text(L.connectPlayOnTv) }
        TextButton(modifier = Modifier.tvFocusRing(), onClick = {
            songs().takeIf { it.isNotEmpty() }?.let { PhoneRemote.sendAndTell(context, SendHow.Add, it) }
        }) { Text(L.connectAddToTvQueue) }
    }
}

/** The same two as lines of a track's menu; [onDismiss] closes the menu after the tap. */
@Composable
fun TvMenuLines(song: Song, onDismiss: () -> Unit) {
    if (!hasPairedTv()) return
    val context = LocalContext.current
    for ((label, how) in listOf(L.connectPlayOnTv to SendHow.Play, L.connectAddToTvQueue to SendHow.Add)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable {
                PhoneRemote.sendAndTell(context, how, listOf(song))
                onDismiss()
            }.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

/**
 * Play on TV and Add to TV queue for a whole chat. The chat's tracks are read page by page in the
 * background (never all at once), up to [ChatSend.CAP] per send, which a line says. While it runs
 * the buttons give way to a progress line with Cancel. Nothing when no TV is paired.
 */
@Composable
fun SendChatToTvButtons(chatId: Long, modifier: Modifier = Modifier) {
    if (!hasPairedTv()) return
    val context = LocalContext.current
    val state by PhoneRemote.chatSend.collectAsStateWithLifecycle()
    Column(modifier) {
        val running = state
        if (running == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(modifier = Modifier.tvFocusRing(), onClick = { PhoneRemote.sendChatAndTell(context, chatId, SendHow.Play) }) { Text(L.connectPlayOnTv) }
                TextButton(modifier = Modifier.tvFocusRing(), onClick = { PhoneRemote.sendChatAndTell(context, chatId, SendHow.Add) }) { Text(L.connectAddToTvQueue) }
            }
            Text(L.connectSendChatLimit(ChatSend.CAP), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (running) {
                    is PhoneRemote.ChatSendUi.Reading -> {
                        LinearProgressIndicator(progress = { (running.count.toFloat() / ChatSend.CAP).coerceIn(0f, 1f) }, modifier = Modifier.width(96.dp))
                        Text(L.connectSendChatReading(running.count), style = MaterialTheme.typography.bodyMedium)
                    }
                    PhoneRemote.ChatSendUi.Sending -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(L.connectSendChatSending, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TextButton(modifier = Modifier.tvFocusRing(), onClick = { PhoneRemote.cancelChatSend() }) { Text(L.commonCancel) }
            }
        }
    }
}
