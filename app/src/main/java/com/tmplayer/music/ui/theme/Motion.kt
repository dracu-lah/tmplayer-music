package com.tmplayer.music.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's motion, Material 3 Expressive's spring scheme. material3 1.4.0 (what the Compose BOM
 * gives us) keeps `MotionScheme` and `MaterialTheme.motionScheme` internal, so this is the same
 * scheme written out from the published expressive motion tokens: spatial springs (position, size,
 * shape) bounce a little, effect springs (colour, opacity) never overshoot. Read it as
 * [MusicMotion.current]. When the app can move to material3 1.5 (it needs minSdk 24), hand
 * `MotionScheme.expressive()` to `MaterialExpressiveTheme` and read `MaterialTheme.motionScheme`.
 */
interface MusicMotion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T>
    fun <T> defaultSpatial(): FiniteAnimationSpec<T>
    fun <T> slowSpatial(): FiniteAnimationSpec<T>
    fun <T> fastEffects(): FiniteAnimationSpec<T>
    fun <T> defaultEffects(): FiniteAnimationSpec<T>
    fun <T> slowEffects(): FiniteAnimationSpec<T>

    companion object {
        /** The motion of the theme around this point. */
        val current: MusicMotion
            @Composable @ReadOnlyComposable get() = LocalMusicMotion.current
    }
}

/** The expressive springs (Material's motion tokens, expressive set). */
object ExpressiveMotion : MusicMotion {
    override fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    override fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    override fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    override fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    override fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    override fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}

/** Every animation snaps to its end, for "Remove animations" (system animator scale 0). */
object SnapMotion : MusicMotion {
    override fun <T> fastSpatial(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultSpatial(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatial(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffects(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffects(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffects(): FiniteAnimationSpec<T> = snap()
}

val LocalMusicMotion = staticCompositionLocalOf<MusicMotion> { ExpressiveMotion }
