package com.tmplayer.music.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
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
 * The app's theme: one look, pure black ([MusicColors]), whatever the device's mode or wallpaper.
 * The window behind it is black from the first frame (themes.xml in res/values, values-night and values-v31) and the activity
 * keeps both system bars transparent with light icons ([enableBlackEdgeToEdge]).
 *
 * The layers, top to bottom, all on one surface so the screen reads as one sheet:
 * - status bar and navigation bar: transparent, the page shows through;
 * - top bar, page, bottom bar (or rail): `background` (#000000), with a hairline (`outlineVariant`)
 *   on top of the bottom bar only while no mini-player sits on it;
 * - mini-player: `background` too, its progress line (or a hairline) as its top edge, opening into
 *   Now Playing's `background` plus the cover's tint;
 * - the scan banner and cards: `surfaceContainer` (#121212), inset with round corners; menus,
 *   sheets and dialogs the Material defaults on the steps above it;
 * - the chosen tab: a filled white icon and a bold white label, no pill behind it.
 *
 * Motion is Material's expressive spring scheme ([ExpressiveMotion]), or snaps when the system
 * animator scale is 0 (read again each time the app comes back, as the user may have changed it).
 */
@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var reduced by remember { mutableStateOf(!preview && animationsOff(context)) }
    if (!preview) {
        LifecycleResumeEffect(context) {
            reduced = animationsOff(context)
            onPauseOrDispose { }
        }
    }
    // MaterialExpressiveTheme and MotionScheme are internal in material3 1.4.0, so the expressive
    // motion travels in our own local beside the Material theme (see [MusicMotion]).
    MaterialTheme(colorScheme = MusicColors, shapes = MusicShapes, typography = MusicTypography) {
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

/**
 * Edge to edge with both system bars transparent and light icons, in either device mode: the app is
 * always black behind them. Dark styles also stop Android drawing its own contrast scrim behind
 * three-button navigation.
 */
fun ComponentActivity.enableBlackEdgeToEdge() {
    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
}
