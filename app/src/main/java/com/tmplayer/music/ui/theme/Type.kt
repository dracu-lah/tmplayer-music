package com.tmplayer.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The expressive type scale: the Material sizes, with heavier display, headline and title weights
 * so a screen's hierarchy reads at a glance, and the body left at its readable regular weight.
 *
 * Floors: anything meaningful (body, a label on a control, times on the seek bar) is 12 sp or more;
 * only labelSmall (11 sp) goes under that, for decoration such as a count beside a heading.
 */
private val base = Typography()

private fun TextStyle.weight(w: FontWeight, tracking: Float? = null): TextStyle =
    copy(fontWeight = w, letterSpacing = tracking?.sp ?: letterSpacing)

val MusicTypography: Typography = Typography(
    displayLarge = base.displayLarge.weight(FontWeight.SemiBold, -0.5f),
    displayMedium = base.displayMedium.weight(FontWeight.SemiBold, -0.25f),
    displaySmall = base.displaySmall.weight(FontWeight.SemiBold, 0f),
    headlineLarge = base.headlineLarge.weight(FontWeight.SemiBold, -0.25f),
    headlineMedium = base.headlineMedium.weight(FontWeight.SemiBold, 0f),
    headlineSmall = base.headlineSmall.weight(FontWeight.SemiBold, 0f),
    titleLarge = base.titleLarge.weight(FontWeight.SemiBold),
    titleMedium = base.titleMedium.weight(FontWeight.SemiBold),
    titleSmall = base.titleSmall.weight(FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall.copy(fontSize = maxOf(base.bodySmall.fontSize.value, 12f).sp),
    labelLarge = base.labelLarge.weight(FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontSize = maxOf(base.labelMedium.fontSize.value, 12f).sp, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontSize = maxOf(base.labelSmall.fontSize.value, 11f).sp, fontWeight = FontWeight.Medium),
)

/** The same scale [com.tmplayer.music.platform.TvLayout.TYPE_SCALE] times larger, for a TV read from across the room. */
val TvTypography: Typography = MusicTypography.scaled(com.tmplayer.music.platform.TvLayout.TYPE_SCALE)

private fun TextStyle.scaled(f: Float): TextStyle = copy(
    fontSize = if (fontSize.isSp) (fontSize.value * f).sp else fontSize,
    lineHeight = if (lineHeight.isSp) (lineHeight.value * f).sp else lineHeight,
)

private fun Typography.scaled(f: Float): Typography = Typography(
    displayLarge = displayLarge.scaled(f),
    displayMedium = displayMedium.scaled(f),
    displaySmall = displaySmall.scaled(f),
    headlineLarge = headlineLarge.scaled(f),
    headlineMedium = headlineMedium.scaled(f),
    headlineSmall = headlineSmall.scaled(f),
    titleLarge = titleLarge.scaled(f),
    titleMedium = titleMedium.scaled(f),
    titleSmall = titleSmall.scaled(f),
    bodyLarge = bodyLarge.scaled(f),
    bodyMedium = bodyMedium.scaled(f),
    bodySmall = bodySmall.scaled(f),
    labelLarge = labelLarge.scaled(f),
    labelMedium = labelMedium.scaled(f),
    labelSmall = labelSmall.scaled(f),
)
