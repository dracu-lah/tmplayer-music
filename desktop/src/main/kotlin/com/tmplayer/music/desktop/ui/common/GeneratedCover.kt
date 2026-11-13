package com.tmplayer.music.desktop.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tmplayer.music.library.CoverArt

/**
 * The cover drawn for a track or album with no picture of its own: a gradient of the key's palette
 * with one shape of its light colour, laid out by the key's style and flipped by its mirror flag.
 * The same key gives the same cover on every launch ([CoverArt.seedOf]); the layouts are the
 * desktop's own, simpler than the phone's.
 */
@Composable
fun GeneratedCover(art: String, modifier: Modifier = Modifier) {
    val seed = remember(art) { CoverArt.seedOf(art) }
    val colours = remember(seed) { CoverArt.palettes[seed.palette.coerceIn(0, CoverArt.palettes.lastIndex)].map { Color(it) } }
    Canvas(modifier.fillMaxSize()) {
        val deep = colours[0]
        val middle = colours[1]
        val light = colours[2]
        drawRect(Brush.linearGradient(listOf(deep, middle), Offset.Zero, Offset(size.width, size.height)))
        val flip = if (seed.mirrored) -1f else 1f
        fun x(fraction: Float): Float = if (flip > 0) size.width * fraction else size.width * (1f - fraction)
        val w = size.width
        val h = size.height
        when (seed.style % CoverArt.STYLES) {
            0 -> circle(light, Offset(x(0.68f), h * 0.32f), w * 0.22f)
            1 -> {
                circle(light.copy(alpha = 0.9f), Offset(x(0.5f), h * 0.5f), w * 0.3f)
                circle(deep, Offset(x(0.5f), h * 0.5f), w * 0.12f)
            }
            2 -> drawRect(light.copy(alpha = 0.85f), Offset(0f, h * 0.62f), androidx.compose.ui.geometry.Size(w, h * 0.14f))
            3 -> {
                circle(light.copy(alpha = 0.8f), Offset(x(0.3f), h * 0.7f), w * 0.34f)
                circle(middle.copy(alpha = 0.9f), Offset(x(0.72f), h * 0.3f), w * 0.2f)
            }
            4 -> for (i in 0..3) {
                drawRect(
                    light.copy(alpha = 0.25f + 0.2f * i),
                    Offset(x(0.1f + 0.2f * i) - if (flip < 0) w * 0.15f else 0f, h * (0.75f - 0.15f * i)),
                    androidx.compose.ui.geometry.Size(w * 0.15f, h * (0.15f * i + 0.1f)),
                )
            }
            5 -> circle(light.copy(alpha = 0.7f), Offset(x(0.85f), h * 0.85f), w * 0.5f)
            else -> {
                circle(light, Offset(x(0.4f), h * 0.4f), w * 0.1f)
                circle(light.copy(alpha = 0.5f), Offset(x(0.6f), h * 0.6f), w * 0.18f)
            }
        }
    }
}

private fun DrawScope.circle(color: Color, center: Offset, radius: Float) =
    drawCircle(color, radius, center)
