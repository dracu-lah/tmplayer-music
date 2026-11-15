package com.tmplayer.music.ui.library

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.music.data.AppLocales
import com.tmplayer.music.i18n.L
import com.tmplayer.music.i18n.LanguageOption
import com.tmplayer.music.i18n.LanguagePicker
import com.tmplayer.music.i18n.LanguageSetting
import com.tmplayer.music.i18n.Languages
import com.tmplayer.music.i18n.Translator
import com.tmplayer.music.ui.tv.LocalTv
import com.tmplayer.music.ui.tv.requestFocusSoon
import com.tmplayer.music.ui.tv.tvAutoFocus
import com.tmplayer.music.ui.tv.tvFocusRing

/**
 * Settings, Language: one row naming the language in use (in itself), opening a picker with
 * "System default" first and then English and every language whose catalog ships ([LanguagePicker]).
 * Picking applies at once: the screen is recreated in the new language (and mirrored for Arabic and
 * Persian). On a TV the D-pad lands on the marked row of the list and returns to this row on close.
 */
@Composable
internal fun LanguageSettingsRow() {
    val context = LocalContext.current
    val saved by LanguageSetting.saved.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val tv = LocalTv.current

    val current = LanguagePicker.usable(saved, Translator::hasCatalog)
    val detail = if (current.isEmpty()) {
        L.settingsLanguageDetailSystem(Languages.find(Translator.systemDefault())?.name ?: Languages.all.first().name)
    } else {
        Languages.find(current)?.name ?: current
    }

    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(rowFocus)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(L.settingsLanguage, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Back on the row when the picker closes, so the D-pad is never left in nowhere.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open && tv) requestFocusSoon(rowFocus)
        wasOpen = open
    }

    if (open) {
        LanguageDialog(
            options = LanguagePicker.options(saved, Translator::hasCatalog),
            onPick = { tag ->
                open = false
                // The same language again changes nothing.
                if (tag == current) return@LanguageDialog
                val activity = context.findActivity()
                if (activity != null) {
                    AppLocales.change(activity, tag)
                } else {
                    LanguageSetting.set(tag)
                    Translator.select(LanguageSetting.saved.value, AppLocales.system(context))
                }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun LanguageDialog(options: List<LanguageOption>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    // Opens with the marked row in view, not at the top of a long list.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.settingsLanguage) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), state = list) {
                items(options, key = { it.tag }) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (option.selected) Modifier.tvAutoFocus() else Modifier)
                            .clickable(role = Role.RadioButton) { onPick(option.tag) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Text(option.name ?: L.settingsLanguageSystem, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(modifier = Modifier.tvFocusRing(), onClick = onDismiss) { Text(L.commonCancel) } },
    )
}

/** The activity under a (possibly wrapped) context, or null in a preview. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
