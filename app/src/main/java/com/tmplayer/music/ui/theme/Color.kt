package com.tmplayer.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The app's one look (user, 2026-10-30: "just use amoled black, no other themes"): a pure black
 * page and surfaces, the containers as very dark neutral steps so a card or a sheet still separates
 * from the page, and white accents. There is no light scheme and no wallpaper (dynamic) colour, so
 * every phone shows the same thing and nothing can come out of order on a maker's colour table.
 */
object Palette {
    val Black = Color(0xFF000000)
    val ContainerLow = Color(0xFF0A0A0A)
    val Container = Color(0xFF121212)
    val ContainerHigh = Color(0xFF1A1A1A)
    val ContainerHighest = Color(0xFF222222)
    val Variant = Color(0xFF1E1E1E)
    val Bright = Color(0xFF2A2A2A)
    val Raised = Color(0xFF262626)

    /** The accent and the main text: white. */
    val White = Color(0xFFFFFFFF)

    /** Body text: a hair off white, so long lines are not harsh on black. */
    val Text = Color(0xFFEDEDED)

    /** Secondary text and the tabs not chosen. */
    val Muted = Color(0xFFB3B3B3)

    /** Hairlines: the bottom bar's and the mini-player's top edge, dividers. */
    val Hairline = Color(0xFF2E2E2E)
}

/**
 * The scheme. Every text pair here is checked in `SurfacesTest`: Text and Muted are at least 4.5:1
 * on every step up to ContainerHighest (Text about 15:1 and Muted about 8:1 on #222222), black on
 * the white accent is 21:1, and the accent against the page is far above 3:1 for controls.
 */
val MusicColors: ColorScheme = darkColorScheme(
    primary = Palette.White,
    onPrimary = Palette.Black,
    primaryContainer = Palette.Raised,
    onPrimaryContainer = Palette.Text,
    inversePrimary = Palette.ContainerHighest,
    secondary = Palette.Muted,
    onSecondary = Palette.Black,
    secondaryContainer = Palette.Raised,
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Muted,
    onTertiary = Palette.Black,
    tertiaryContainer = Palette.Raised,
    onTertiaryContainer = Palette.Text,
    background = Palette.Black,
    onBackground = Palette.Text,
    surface = Palette.Black,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Variant,
    onSurfaceVariant = Palette.Muted,
    surfaceTint = Palette.White,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Palette.Hairline,
    scrim = Palette.Black,
    surfaceBright = Palette.Bright,
    surfaceDim = Palette.Black,
    surfaceContainerLowest = Palette.Black,
    surfaceContainerLow = Palette.ContainerLow,
    surfaceContainer = Palette.Container,
    surfaceContainerHigh = Palette.ContainerHigh,
    surfaceContainerHighest = Palette.ContainerHighest,
)
