package com.tmplayer.music.ui.library

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.HapticSetting
import com.tmplayer.music.ui.common.rememberHaptics

/**
 * Settings, Playback: "Haptic feedback" (on by default), the light taps and ticks under the player's
 * controls. Applies at once; the system's own touch feedback switch still has the last word.
 */
@Composable
internal fun HapticSettingsSection() {
    val on by HapticSetting.choices.enabled.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(L.settingsPlayback, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = {
                HapticSetting.choices.setEnabled(it)
                // Felt only when turned on, so the switch shows what it does.
                if (it) haptics.toggle(true)
            })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L.settingsHaptics, style = MaterialTheme.typography.bodyLarge)
            Text(L.settingsHapticsDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
