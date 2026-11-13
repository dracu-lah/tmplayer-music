package com.tmplayer.music.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app's one look, pure black ([MusicColors]), the same as the phone's: no light scheme, no
 * system colours. The layers on the desktop, all on one black sheet: the sidebar and the page on
 * `background`, the now playing bar on `surfaceContainerLow` with a hairline (`outlineVariant`)
 * as its top edge, cards, menus and dialogs on the Material steps above it.
 */
@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MusicColors, shapes = MusicShapes, typography = MusicTypography, content = content)
}
