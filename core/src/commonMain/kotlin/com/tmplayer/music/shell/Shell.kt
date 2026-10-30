package com.tmplayer.music.shell

import com.tmplayer.music.data.KeyValues

/** The three destinations of the bottom bar (or the rail in landscape). */
enum class MainTab { Home, Chats, Library }

/** The sections of the Library tab, shown as a chip row at its top. */
enum class LibraryShelf { Songs, Albums, Artists, Playlists, Favourites }

/**
 * What the shell remembers between starts: the Library chip last chosen, and whether Albums and
 * Artists show as a grid. A value the app no longer knows (an older or newer build) falls back to
 * the default instead of failing.
 */
class ShellChoices(private val store: KeyValues) {

    var shelf: LibraryShelf
        get() = shelfOf(store.getString(SHELF))
        set(value) = store.putString(SHELF, value.name)

    var albumsGrid: Boolean
        get() = store.getBoolean(ALBUMS_GRID, true)
        set(value) = store.putBoolean(ALBUMS_GRID, value)

    var artistsGrid: Boolean
        get() = store.getBoolean(ARTISTS_GRID, false)
        set(value) = store.putBoolean(ARTISTS_GRID, value)

    companion object {
        const val SHELF = "shell_library_shelf"
        const val ALBUMS_GRID = "shell_albums_grid"
        const val ARTISTS_GRID = "shell_artists_grid"

        /** The shelf named [name], or Songs for nothing or a name not known. */
        fun shelfOf(name: String?): LibraryShelf =
            LibraryShelf.entries.firstOrNull { it.name == name } ?: LibraryShelf.Songs
    }
}

/**
 * The collapsing header (top bar, scan banner, chip row) as one offset in pixels: 0 is fully shown,
 * minus its height is fully hidden. A scroll moves it first; only what it could not take goes on
 * to the list.
 */
object HeaderOffset {

    /** The offset after a scroll of [delta] pixels (negative: the content moves up). */
    fun next(offset: Float, delta: Float, height: Float): Float {
        if (!height.isFinite() || height <= 0f) return 0f
        val from = if (offset.isFinite()) offset else 0f
        val d = if (delta.isFinite()) delta else 0f
        return (from + d).coerceIn(-height, 0f)
    }

    /** The part of [delta] the header took moving from [offset]: what the list must not scroll. */
    fun consumed(offset: Float, delta: Float, height: Float): Float =
        next(offset, delta, height) - (if (offset.isFinite()) offset.coerceIn(-height.coerceAtLeast(0f), 0f) else 0f)

    /**
     * Where a header left part way settles once the list stops: back to shown when more than half
     * of it shows, else hidden. Null when it rests already, so nothing animates while idle.
     */
    fun settleTarget(offset: Float, height: Float): Float? {
        if (!height.isFinite() || height <= 0f || !offset.isFinite()) return null
        val at = offset.coerceIn(-height, 0f)
        if (at >= -0.5f || at <= -height + 0.5f) return null
        return if (at > -height / 2f) 0f else -height
    }

    /** A header taller or shorter than before keeps the same share hidden, never more than it has. */
    fun resize(offset: Float, oldHeight: Float, newHeight: Float): Float {
        if (!newHeight.isFinite() || newHeight <= 0f) return 0f
        if (!oldHeight.isFinite() || oldHeight <= 0f || !offset.isFinite()) return 0f
        val share = (-offset / oldHeight).coerceIn(0f, 1f)
        return -share * newHeight
    }
}

/**
 * How the navigation moves with the player sheet: as the sheet opens ([progress] 0 to 1) the bottom
 * bar slides down out of the window and the rail slides out to the side, in step with the finger.
 */
object NavMotion {

    private fun clamp(progress: Float): Float = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)

    /** The bottom bar's downward shift in pixels for a bar [barHeight] tall. */
    fun barOffset(progress: Float, barHeight: Float): Float =
        if (!barHeight.isFinite() || barHeight <= 0f) 0f else clamp(progress) * barHeight

    /** The rail's shift toward its edge (negative, to the left; the caller mirrors it in RTL). */
    fun railOffset(progress: Float, railWidth: Float): Float =
        if (!railWidth.isFinite() || railWidth <= 0f) 0f else -clamp(progress) * railWidth

    /** The bar is fully out of sight: it can skip drawing and take no touches. */
    fun barGone(progress: Float): Boolean = clamp(progress) >= 0.999f
}

/** Layout choices made from the window's size in dp, kept here so they can be tested. */
object NavLayout {

    /** Material's compact width limit: narrower windows always get the bottom bar. */
    const val COMPACT_WIDTH = 600f

    /**
     * The rail on the left edge instead of the bottom bar: a window at least [COMPACT_WIDTH] wide
     * that is wider than tall (a phone or a tablet turned sideways), where height is what is short.
     * A tablet held upright keeps the bottom bar. Nonsense sizes keep the bar.
     */
    fun useRail(widthDp: Float, heightDp: Float): Boolean {
        if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) return false
        return widthDp >= COMPACT_WIDTH && widthDp > heightDp
    }

    /** The smallest a grid cover may be, so two fit on the narrowest phone. */
    const val MIN_CELL = 150f

    /** Columns of a cover grid [widthDp] wide: at least 2, at most 8, each at least [MIN_CELL]. */
    fun gridColumns(widthDp: Float): Int {
        if (!widthDp.isFinite() || widthDp <= 0f) return 2
        return (widthDp / MIN_CELL).toInt().coerceIn(2, 8)
    }

    /**
     * The mini-player's height in dp for the user's font scale: one slim row, a little taller once
     * the text is large, so the title and artist lines are never clipped.
     */
    fun miniHeight(fontScale: Float): Float = when {
        !fontScale.isFinite() || fontScale <= 1.15f -> 64f
        fontScale <= 1.5f -> 72f
        else -> 84f
    }
}
