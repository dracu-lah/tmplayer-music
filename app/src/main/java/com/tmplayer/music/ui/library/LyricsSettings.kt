package com.tmplayer.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.i18n.L
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.platform.JobFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Lyrics: "Look up lyrics online" (off by default, with what is sent said plainly) and
 * Clear saved lyrics. Only this switch and the lyrics page's question turn the lookup on.
 */
@Composable
internal fun LyricsSettingsSection() {
    val online by LyricsOnline.choices.online.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableStateOf(false) }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(L.lyricsTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = online, role = Role.Switch, onValueChange = { LyricsOnline.choices.setOnline(it) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L.lyricsOnlineTitle, style = MaterialTheme.typography.bodyLarge)
            Text(L.lyricsOnlineDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = online, onCheckedChange = null)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { LyricsOnline.clear() }
                        cleared = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        JobFailures.handle("LyricsSettings", e)
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(L.lyricsClear, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (cleared) L.lyricsCleared else L.lyricsClearDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
