package com.tmplayer.music.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * True when the user turned animations off (Developer options or Accessibility, "Remove
 * animations"): every animation in the app then snaps, and nothing loops.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The app's theme. It follows the device: dark or light as the system is, and on Android 12 and up
 * the wallpaper's (dynamic) surfaces, with neutral accents in place of the wallpaper's accent
 * ([neutralAccents]). Android 11 and below get [FallbackDark] or [FallbackLight], made
 * from the icon's colours. Motion is Material's expressive spring scheme ([ExpressiveMotion]), or snaps when the system
 * animator scale is 0 (read again each time the app comes back, as the user may have changed it).
 */
@Composable
fun MusicTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val colors = when {
        dynamic && !preview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).neutralAccents()
        dark -> FallbackDark
        else -> FallbackLight
    }
    var reduced by remember { mutableStateOf(!preview && animationsOff(context)) }
    if (!preview) {
        LifecycleResumeEffect(context) {
            reduced = animationsOff(context)
            onPauseOrDispose { }
        }
    }
    // MaterialExpressiveTheme and MotionScheme are internal in material3 1.4.0, so the expressive
    // motion travels in our own local beside the Material theme (see [MusicMotion]).
    MaterialTheme(colorScheme = colors, shapes = MusicShapes, typography = MusicTypography) {
        CompositionLocalProvider(
            LocalReducedMotion provides reduced,
            LocalMusicMotion provides if (reduced) SnapMotion else ExpressiveMotion,
            content = content,
        )
    }
}

/** The system's animator duration scale is 0: the user does not want motion. */
private fun animationsOff(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

