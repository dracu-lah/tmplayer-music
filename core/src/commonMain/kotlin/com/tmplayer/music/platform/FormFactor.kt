package com.tmplayer.music.platform

/**
 * One answer, for the whole process, to "is this a television?". The phone and the TV share one
 * APK, and the UI branches on this in several places, so it has to be the same answer everywhere:
 * a screen that decides it is on a TV while the player decides it is on a phone is worse than either.
 *
 * The platform reads the [Signals] once (Android: the UI mode type and two system features) and
 * this decides; hardware cannot change under a running process, so the answer is cached.
 */
object FormFactor {

    /**
     * What the device says about itself. [television]: the UI mode type is television (Android TV,
     * Google TV and Fire TV all set it). [leanback]: the leanback system feature, which a real
     * Android TV declares even on a box that reports another UI mode. [fireTv]: Amazon's own
     * feature (`amazon.hardware.fire_tv`), for a Fire OS build that sets neither.
     */
    data class Signals(val television: Boolean = false, val leanback: Boolean = false, val fireTv: Boolean = false)

    @Volatile
    private var cached: Boolean? = null

    /** True on a TV. Any one signal is enough: none of them is ever set on a phone or a tablet. */
    fun decide(signals: Signals): Boolean = signals.television || signals.leanback || signals.fireTv

    /**
     * The cached answer, reading the signals with [read] the first time. A [read] that fails counts
     * as a phone (the touch layout still works with a remote, the other way round does not hold).
     */
    fun isTv(read: () -> Signals): Boolean =
        cached ?: decide(runCatching(read).getOrDefault(Signals())).also { cached = it }

    /** The answer once known, false before the first [isTv]. For code with no way to read signals. */
    val known: Boolean get() = cached ?: false

    /** Tests and screenshot fixtures only: answers [value] from here on (null forgets the answer). */
    fun override(value: Boolean?) {
        cached = value
    }
}

/**
 * The TV layout's figures, kept here so they are the same on every screen and testable.
 *
 * - The type is [TYPE_SCALE] times the phone's: read from a sofa about 3 m from the screen.
 * - Overscan: a TV may crop the picture's edges, so nothing that matters is drawn in the outer
 *   [SAFE_FRACTION_H] of the width or [SAFE_FRACTION_V] of the height (Android TV's own guide: 48 dp
 *   and 27 dp on a 960 x 540 dp screen).
 */
object TvLayout {
    const val TYPE_SCALE = 1.2f
    const val SAFE_FRACTION_H = 0.05f
    const val SAFE_FRACTION_V = 0.05f

    /** The horizontal and vertical safe margins in dp for a screen of [widthDp] by [heightDp]. */
    fun safeMargins(widthDp: Float, heightDp: Float): Pair<Float, Float> {
        val w = if (widthDp.isFinite() && widthDp > 0f) widthDp else 0f
        val h = if (heightDp.isFinite() && heightDp > 0f) heightDp else 0f
        return (w * SAFE_FRACTION_H) to (h * SAFE_FRACTION_V)
    }
}
