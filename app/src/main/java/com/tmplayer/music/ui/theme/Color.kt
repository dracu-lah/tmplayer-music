package com.tmplayer.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fixed schemes for Android 11 and below, where there is no wallpaper colour to follow. They
 * are built from the launcher icon: the dark tile, the near black and the off-white. There is no
 * coloured accent (user, 2026-10-30: calm, not orange): on the dark scheme the accent is a soft white
 * with a faint tint of the background's blue grey, and on the light scheme it is mirrored into a
 * near black, so filled buttons, progress and selection read as white on dark (or dark on light).
 * Android 12 and up use the device's own (dynamic) surfaces with the same neutral accents, see
 * [neutralAccents].
 *
 * Every text pair here was checked at 4.5:1 or more (onSurface and onSurfaceVariant on every
 * container, primary on the surfaces, onPrimary on primary, onPrimaryContainer on primaryContainer),
 * and primary against every surface container at 3:1 or more for controls.
 */
object IconColors {
    val Tile = Color(0xFF1E1E28)
    val NearBlack = Color(0xFF0E0E12)
    val OffWhite = Color(0xFFEDEDF2)

    /** The dark scheme's accent: a soft white, a hair towards the background's hue. */
    val SoftWhite = Color(0xFFF4F4F8)
}

val FallbackDark: ColorScheme = darkColorScheme(
    primary = IconColors.SoftWhite,
    onPrimary = Color(0xFF15151C),
    primaryContainer = Color(0xFF34343F),
    onPrimaryContainer = IconColors.OffWhite,
    inversePrimary = Color(0xFF3A3A46),
    secondary = Color(0xFFC8C3D6),
    onSecondary = Color(0xFF2E2B3A),
    secondaryContainer = Color(0xFF3A3848),
    onSecondaryContainer = Color(0xFFE4E0F2),
    tertiary = Color(0xFFD4D2DE),
    onTertiary = Color(0xFF2A2933),
    tertiaryContainer = Color(0xFF3C3B48),
    onTertiaryContainer = Color(0xFFE6E4F0),
    background = IconColors.NearBlack,
    onBackground = IconColors.OffWhite,
    surface = IconColors.NearBlack,
    onSurface = IconColors.OffWhite,
    surfaceVariant = Color(0xFF2A2A36),
    onSurfaceVariant = Color(0xFFB4B4C2),
    surfaceTint = IconColors.SoftWhite,
    inverseSurface = IconColors.OffWhite,
    inverseOnSurface = IconColors.Tile,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A8A99),
    outlineVariant = Color(0xFF3A3A48),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E2E3A),
    surfaceDim = IconColors.NearBlack,
    surfaceContainerLowest = Color(0xFF09090C),
    surfaceContainerLow = Color(0xFF15151B),
    surfaceContainer = Color(0xFF1A1A22),
    surfaceContainerHigh = IconColors.Tile,
    surfaceContainerHighest = Color(0xFF282834),
)

val FallbackLight: ColorScheme = lightColorScheme(
    primary = IconColors.Tile,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEDEE6),
    onPrimaryContainer = Color(0xFF15151C),
    inversePrimary = IconColors.SoftWhite,
    secondary = Color(0xFF5C5A6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E0F2),
    onSecondaryContainer = Color(0xFF1A1928),
    tertiary = Color(0xFF4A4858),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E2EE),
    onTertiaryContainer = Color(0xFF1A1928),
    background = Color(0xFFF6F6F9),
    onBackground = IconColors.Tile,
    surface = Color(0xFFF6F6F9),
    onSurface = IconColors.Tile,
    surfaceVariant = Color(0xFFE1E1E8),
    onSurfaceVariant = Color(0xFF4A4A58),
    surfaceTint = IconColors.Tile,
    inverseSurface = IconColors.Tile,
    inverseOnSurface = IconColors.OffWhite,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF77778A),
    outlineVariant = Color(0xFFC8C8D4),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF6F6F9),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1F5),
    surfaceContainer = IconColors.OffWhite,
    surfaceContainerHigh = Color(0xFFE7E7ED),
    surfaceContainerHighest = Color(0xFFE1E1E8),
)

/**
 * The device's (dynamic) scheme with its coloured accent swapped for the scheme's own neutrals, so
 * Android 12 and up keep the wallpaper's tinted surfaces but get the same calm accent as the fixed
 * schemes: the near white (dark) or near black (light) text colour becomes the accent, with the
 * surface on top of it. onSurface on surface is always far above 4.5:1 in Material's tonal palettes.
 */
fun ColorScheme.neutralAccents(): ColorScheme = copy(
    primary = onSurface,
    onPrimary = surface,
    primaryContainer = surfaceContainerHighest,
    onPrimaryContainer = onSurface,
    inversePrimary = inverseOnSurface,
    surfaceTint = onSurface,
)
