// From TMPlayer (GPL-3.0), ui/src/shared/kotlin/com/tmplayer/ui/auth/CountryPicker.kt.
package com.tmplayer.music.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tmplayer.music.data.Country
import com.tmplayer.music.data.PhoneCountries
import com.tmplayer.music.i18n.L

/**
 * The full-screen list of countries, the way Telegram's own sign in offers it.
 *
 * A picker rather than a free-text country field, because the thing being chosen is really the
 * dial code and almost nobody knows anybody else's by heart. It covers the step rather than
 * floating over it: two hundred rows behind a keyboard need the whole screen, and a half-height
 * sheet only makes the scrolling longer. The search box has the keyboard at once, since the list
 * is found by typing, not by scrolling; Back closes it.
 */
@Composable
fun CountryPicker(
    countries: List<Country>,
    selected: Country?,
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val shown = remember(countries, query) { countries.filter { PhoneCountries.matches(it, query) } }
    val start = remember(countries, selected) { countries.indexOf(selected).coerceAtLeast(0) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (query.isEmpty()) start else 0)

    BackHandler(onBack = onDismiss)
    // A new query starts the list at its top, where the best match is.
    LaunchedEffect(query) { if (query.isNotEmpty()) runCatching { list.scrollToItem(0) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            Row(column.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.signinCloseCountryList)
                }
                Text(
                    L.signinChooseCountry,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(MAX_QUERY) },
                singleLine = true,
                label = { Text(L.signinCountrySearch) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    // Two hundred rows filtered to none is a dead end; clearing by holding
                    // backspace is a chore nobody should be set on a sign in screen.
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = L.signinClearSearch)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = {
                    // One match left is the answer; otherwise the keyboard steps aside for the list.
                    if (shown.size == 1) onPick(shown.first()) else focus.clearFocus()
                }),
                modifier = column
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(search),
            )

            if (shown.isEmpty()) {
                Box(column.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        L.signinCountryNoMatch,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(column.fillMaxSize(), state = list) {
                items(shown, key = { it.iso }) { country ->
                    val isSelected = country.iso == selected?.iso
                    ListItem(
                        leadingContent = { Text(country.flag, style = MaterialTheme.typography.titleLarge) },
                        headlineContent = { Text(country.name) },
                        trailingContent = {
                            Text("+${country.dialCode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.background
                            },
                        ),
                        modifier = Modifier
                            .semantics { this.selected = isSelected }
                            .clickable(role = Role.Button) { onPick(country) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { search.requestFocus() } }
}

private const val MAX_QUERY = 64
