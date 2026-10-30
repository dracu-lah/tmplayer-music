package com.tmplayer.music.ui.player

import kotlin.math.abs

/** Where the player sheet rests: the mini-player, Now Playing, or swiped away (paused only). */
enum class SheetValue { Collapsed, Expanded, Dismissed }

/**
 * The player sheet's decisions, kept free of Compose so they can be tested on the JVM. Offsets are
 * the sheet's top edge in pixels from the top of the screen: 0 is Now Playing, [collapsedTop] is the
 * mini-player, and the dismissed anchor is the bottom of the screen.
 */
object SheetLogic {

    /**
     * The anchors. Dismissed is offered only while playback is paused and the sheet rests on the
     * mini-player (`plan/RESEARCH.md` 4 item 5: swipe down dismisses it only when paused), so a fast
     * fling down from Now Playing never throws the queue away.
     */
    fun anchors(containerHeight: Float, collapsedTop: Float, allowDismiss: Boolean): Map<SheetValue, Float> {
        val top = collapsedTop.coerceIn(0f, containerHeight.coerceAtLeast(0f))
        return buildMap {
            put(SheetValue.Expanded, 0f)
            put(SheetValue.Collapsed, top)
            if (allowDismiss && containerHeight > top) put(SheetValue.Dismissed, containerHeight)
        }
    }

    /** How open the sheet is: 0 at the mini-player (or below it), 1 at Now Playing. */
    fun progress(offset: Float, collapsedTop: Float): Float {
        if (offset.isNaN() || collapsedTop <= 0f) return 0f
        return (1f - offset / collapsedTop).coerceIn(0f, 1f)
    }

    /** How far the sheet is pulled below the mini-player toward dismissal, 0 to 1. */
    fun dismissProgress(offset: Float, collapsedTop: Float, containerHeight: Float): Float {
        val span = containerHeight - collapsedTop
        if (offset.isNaN() || span <= 0f) return 0f
        return ((offset - collapsedTop) / span).coerceIn(0f, 1f)
    }

    /** The anchor nearest to [offset], for settling when a drag is cut short (backgrounded, rotated). */
    fun nearest(offset: Float, anchors: Map<SheetValue, Float>, fallback: SheetValue = SheetValue.Collapsed): SheetValue {
        if (offset.isNaN()) return fallback
        return anchors.minByOrNull { abs(it.value - offset) }?.key ?: fallback
    }

    /** The sheet stopped between anchors: something left it there and it needs to settle. */
    fun isBetweenAnchors(offset: Float, anchors: Map<SheetValue, Float>, tolerance: Float = 0.5f): Boolean =
        !offset.isNaN() && anchors.values.none { abs(it - offset) <= tolerance }

    /**
     * Swipe down on the mini-player may dismiss it: paused, and resting on the mini-player. Once it
     * rests dismissed the anchor stays, so the sheet is not pulled back up while the queue clears.
     */
    fun allowDismiss(isPlaying: Boolean, buffering: Boolean, settled: SheetValue): Boolean =
        settled == SheetValue.Dismissed || (!isPlaying && !buffering && settled == SheetValue.Collapsed)

    /**
     * Where a sheet let go below the mini-player (on its way to dismissal) settles, or null when it
     * is not below it (the usual fling decides). Speed is ignored here: a quick flick of a few
     * pixels must not throw the queue away, so it is dismissed only when dragged at least
     * [DISMISS_SHARE] of the way down (and dismissal is allowed at all).
     */
    fun settleBelowMini(offset: Float, collapsedTop: Float, containerHeight: Float, canDismiss: Boolean): SheetValue? {
        if (offset.isNaN() || offset <= collapsedTop + 0.5f) return null
        return if (canDismiss && dismissProgress(offset, collapsedTop, containerHeight) >= DISMISS_SHARE) {
            SheetValue.Dismissed
        } else {
            SheetValue.Collapsed
        }
    }

    const val DISMISS_SHARE = 0.5f

    /** Which way a sideways swipe on the mini-player skips, or 0 when it was too short. */
    fun skipDirection(dragX: Float, threshold: Float): Int = when {
        dragX <= -threshold -> 1
        dragX >= threshold -> -1
        else -> 0
    }
}

/**
 * When the wavy seek bar moves. It waves only while music plays, Now Playing is open and on screen,
 * the app is in the foreground, and motion is allowed. Paused, or with no known length, it is a flat
 * line, so nothing animates while idle.
 */
object WaveLogic {

    /** The wave's crest travels along the bar (an animation runs every frame). */
    fun travels(playing: Boolean, resumed: Boolean, visible: Boolean, reducedMotion: Boolean, lengthKnown: Boolean): Boolean =
        playing && resumed && visible && !reducedMotion && lengthKnown

    /** The wave's height, 0 (flat) to 1. Drawn wavy while playing even when it may not travel. */
    fun amplitude(playing: Boolean, lengthKnown: Boolean, seeking: Boolean): Float =
        if (playing && lengthKnown && !seeking) 1f else 0f

    /** The played share of the track, 0 to 1; 0 when the length is not known. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
