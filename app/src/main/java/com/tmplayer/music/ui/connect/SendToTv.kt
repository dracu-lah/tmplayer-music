package com.tmplayer.music.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
