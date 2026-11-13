package com.tmplayer.music.widget

/** Which layout the home screen widget uses at its current size: 2x1 cells or 4x1 and wider. */
enum class WidgetLayout {
    /** Cover and the three buttons, the title and artist on two short lines. */
    Compact,

    /** Cover, title and artist, and the three buttons in a row. */
    Wide,
    ;

    companion object {
        /** A launcher cell is about 70 dp: 4 cells report at least [WIDE_FROM_DP]; unknown or tiny sizes give [Compact]. */
        const val WIDE_FROM_DP = 250

        fun forWidthDp(minWidthDp: Int): WidgetLayout = if (minWidthDp >= WIDE_FROM_DP) Wide else Compact
    }
}

/**
 * What the widget draws, all of it from the media session: the playing (or paused, restored) item's
 * title and artist, and whether it is playing. [hasItem] false is the empty state: nothing queued.
 * A blank title or artist is shown as nothing, never as a made up name.
 */
data class WidgetState(
    val hasItem: Boolean,
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
) {
    companion object {
        val EMPTY = WidgetState(hasItem = false)

        /** Longest line kept: a RemoteViews text is one more thing that crosses a binder. */
        const val MAX_TEXT = 200

        /** The state for an item with these fields; no item (null or empty media id) is [EMPTY]. */
        fun of(mediaId: String?, title: CharSequence?, artist: CharSequence?, playing: Boolean): WidgetState =
            if (mediaId.isNullOrEmpty()) {
                EMPTY
            } else {
                WidgetState(
                    hasItem = true,
                    title = title?.toString()?.trim().orEmpty().take(MAX_TEXT),
                    artist = artist?.toString()?.trim().orEmpty().take(MAX_TEXT),
                    playing = playing,
                )
            }
    }
}
