package com.tmplayer.music.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few extra icons Now Playing needs, as plain 24 dp paths (Material Symbols shapes, Apache 2.0),
 * beside [com.tmplayer.music.ui.common.MusicIcons] and drawn the same way.
 */
internal object PlayerIcons {
    /** Lines of text, for the lyrics toggle. */
    val Lyrics = icon("Lyrics", "M3,18h12v-2H3v2zM3,6v2h18V6H3zm0,7h18v-2H3v2z")

    /** A crescent moon, for the sleep timer. */
    val Sleep = icon(
        "Sleep",
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10 3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.72,8.77 12.34,2.02z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
