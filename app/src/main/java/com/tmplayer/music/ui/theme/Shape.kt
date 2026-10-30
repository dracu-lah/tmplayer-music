package com.tmplayer.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One shape rule for the whole app:
 *
 * - Surfaces that hold content (cards, the mini-player, sheets, the big cover) take LARGE rounded
 *   corners: [Shapes.large] (24 dp) for cards and covers, [Shapes.extraLarge] (32 dp) for sheets.
 * - Controls you press (buttons, chips, toggles, tab indicators, the seek thumb) are TRUE PILLS or
 *   CIRCLES, always: [Pill], which is a circle when the control is as wide as it is tall (or
 *   CircleShape). No rounded squares or squircles, and no corner morphing: a press is a small scale
 *   (or a button group's width change), "on" is a colour (user, 2026-10-30).
 * - Small things inside a row (a list cover, a text field) use [Shapes.small] or [Shapes.medium].
 *
 * Nothing else picks its own radius; use these.
 */
val MusicShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Fully rounded ends, for every pressable control. */
val Pill = RoundedCornerShape(percent = 50)

/** Corner radii the player sheet animates between; the same values as [MusicShapes]. */
object ShapeRadius {
    val Card = 24.dp
    val Sheet = 32.dp
    val Cover = 24.dp
    val SmallCover = 12.dp
}
