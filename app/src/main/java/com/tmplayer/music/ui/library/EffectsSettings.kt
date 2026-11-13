package com.tmplayer.music.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.player.EffectsSheet

/**
 * Settings, Playback: one row, "Sound effects", that opens the same sheet as Now Playing (equalizer,
 * playback speed, skip silence, normalise loudness), so the choices can be made without a track playing.
 */
@Composable
internal fun EffectsSettingsRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(L.effectsTitle, style = MaterialTheme.typography.bodyLarge)
        Text(L.effectsOpenDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) EffectsSheet(onDismiss = { open = false })
}
