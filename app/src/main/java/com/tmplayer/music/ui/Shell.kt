package com.tmplayer.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Td
import com.tmplayer.music.i18n.L
import kotlinx.coroutines.launch

/** The signed-in app. Empty for now: the chat picker and the library arrive in step V1. */
@Composable
fun Shell() {
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(L.shellSignedIn, style = MaterialTheme.typography.headlineSmall)
            Text(L.shellNextStep, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { scope.launch { runCatching { Td.logOut() } } }) { Text(L.shellLogOut) }
        }
    }
}
