package com.tmplayer.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fixed schemes for Android 11 and below, where there is no wallpaper colour to follow. They
 * are built from the launcher icon: the dark tile, the near black, the off-white and the warm coral
 * accent. Android 12 and up use the device's own (dynamic) colours instead, see [MusicTheme].
 *
 * Every text pair here was checked at 4.5:1 or more (onSurface and onSurfaceVariant on every
 * container, primary on the surfaces, onPrimary on primary). The coral itself is too light to carry
 * text on a light surface, so the light scheme's primary is a deeper coral from the same hue.
 */
object IconColors {
    val Tile = Color(0xFF1E1E28)
    val NearBlack = Color(0xFF0E0E12)
    val OffWhite = Color(0xFFEDEDF2)
    val Coral = Color(0xFFFF6B57)
}

val FallbackDark: ColorScheme = darkColorScheme(
    primary = IconColors.Coral,
    onPrimary = Color(0xFF3A0A03),
    primaryContainer = Color(0xFF7A2418),
    onPrimaryContainer = Color(0xFFFFDAD3),
    inversePrimary = Color(0xFFA8341F),
    secondary = Color(0xFFC8C3D6),
    onSecondary = Color(0xFF2E2B3A),
    secondaryContainer = Color(0xFF3A3848),
    onSecondaryContainer = Color(0xFFE4E0F2),
    tertiary = Color(0xFFF2B880),
    onTertiary = Color(0xFF3D2400),
    tertiaryContainer = Color(0xFF5A3A12),
    onTertiaryContainer = Color(0xFFFFDDBA),
    background = IconColors.NearBlack,
    onBackground = IconColors.OffWhite,
    surface = IconColors.NearBlack,
    onSurface = IconColors.OffWhite,
    surfaceVariant = Color(0xFF2A2A36),
    onSurfaceVariant = Color(0xFFB4B4C2),
    surfaceTint = IconColors.Coral,
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
    primary = Color(0xFFB23A26),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD3),
    onPrimaryContainer = Color(0xFF3E0600),
    inversePrimary = IconColors.Coral,
    secondary = Color(0xFF5C5A6E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E0F2),
    onSecondaryContainer = Color(0xFF1A1928),
    tertiary = Color(0xFF7A4F12),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDBA),
    onTertiaryContainer = Color(0xFF2A1700),
    background = Color(0xFFF6F6F9),
    onBackground = IconColors.Tile,
    surface = Color(0xFFF6F6F9),
    onSurface = IconColors.Tile,
    surfaceVariant = Color(0xFFE1E1E8),
    onSurfaceVariant = Color(0xFF4A4A58),
    surfaceTint = Color(0xFFB23A26),
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
