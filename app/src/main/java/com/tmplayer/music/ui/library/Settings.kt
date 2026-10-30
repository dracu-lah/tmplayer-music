package com.tmplayer.music.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tmplayer.music.BuildConfig
import com.tmplayer.music.data.CachePolicy
import com.tmplayer.music.data.Prefs
import com.tmplayer.music.data.Storage
import com.tmplayer.music.i18n.L
import com.tmplayer.music.ui.common.Format
import kotlinx.coroutines.launch

/**
 * Settings: Storage (what the cache holds, what is free, the cache limit, Free up space) and About.
 * Free up space asks first and says what happens: the songs stay, they stream again when played.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(-1L) }
    var free by remember { mutableLongStateOf(Storage.freeBytes()) }
    var chosen by remember { mutableLongStateOf(Storage.chosenCeiling) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    LaunchedEffect(bump) {
        used = runCatching { Storage.usedBytes() }.getOrDefault(0)
        free = Storage.freeBytes()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(L.settingsStorage)
        Line(L.settingsCacheUsed(if (used < 0) "…" else Format.size(used).ifEmpty { L.settingsNothing }))
        Line(L.settingsFree(Format.size(free)))
        Text(L.settingsLimit, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        for (choice in CachePolicy.CHOICES) {
            val label = if (choice == CachePolicy.UNBOUNDED) L.settingsLimitNone else Format.size(choice)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                    chosen = choice
                    prefs.setCacheCeiling(choice)
                    Storage.chosenCeiling = choice
                    scope.launch {
                        Storage.trim()
                        bump++
                    }
                }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == choice, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
            }
        }
        Text(
            L.settingsLimitNote(Format.size(Storage.ceiling())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(onClick = { confirm = true }, enabled = used > 0, modifier = Modifier.padding(16.dp)) { Text(L.settingsFreeUp) }
        message?.let { Line(it) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section(L.settingsAbout)
        Line("${L.appName} ${BuildConfig.VERSION_NAME}")
        Line(L.appUnofficial)
        Text(
            L.settingsLegal,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LEGAL_URL))) }
            }.padding(16.dp),
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(L.settingsFreeUpTitle(Format.size(used))) },
            text = { Text(L.settingsFreeUpBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val freed = Storage.freeUp()
                        message = if (freed == null) L.settingsFreeUpFailed else L.settingsFreedUp(Format.size(freed).ifEmpty { L.settingsNothing })
                        bump++
                    }
                }) { Text(L.settingsFreeUp) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L.commonCancel) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Line(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val LEGAL_URL = "https://tmplayer.org/legal"
