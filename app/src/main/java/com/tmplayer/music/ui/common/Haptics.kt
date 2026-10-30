package com.tmplayer.music.ui.common

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.tmplayer.music.platform.ChangeTick
import com.tmplayer.music.platform.EnterTick
import com.tmplayer.music.platform.Haptic
import com.tmplayer.music.platform.HapticLimiter
import com.tmplayer.music.platform.HapticRules
import com.tmplayer.music.platform.HapticSetting
import com.tmplayer.music.platform.MarkCrossing

/**
 * Plays [Haptic]s for one control (one source): at most one buzz per 50 ms from it, only while the
 * app's "Haptic feedback" is on (read at each buzz, so Settings applies at once), the device has a
 * vibrator and the screen is on with this window showing. The system's touch feedback switch has
 * the last word. Call it only from a user's touch, never from a playback event or a timer.
 *
 * Played as TMPlayer does: the vibrator's predefined click or tick first from Android 10 (a view's
 * feedback is dropped silently on some phones, HyperOS among them), else the view's constant with
 * the fallbacks in [HapticRules]. Nothing here throws.
 */
class HapticPlayer internal constructor(private val view: View) {
    private val limiter = HapticLimiter()
    private val marks = MarkCrossing()
    private val changes = ChangeTick<Any>()
    private val enter = EnterTick()

    operator fun invoke(event: Haptic) {
        runCatching {
            val context = view.context
            if (!HapticRules.allowed(HapticSetting.choices.enabled.value, HapticDevice.hasVibrator(context), showing(context))) return
            if (!limiter.allow(SystemClock.uptimeMillis())) return
            if (Build.VERSION.SDK_INT >= HapticRules.API_Q && HapticDevice.play(context, event) != null) return
            val constant = HapticRules.constant(event, Build.VERSION.SDK_INT) ?: return
            // No FLAG_IGNORE_GLOBAL_SETTING: with the system's touch feedback off, nothing plays.
            view.performHapticFeedback(constant)
        }
    }

    /** Shorthands, so a call site is one line without importing [Haptic]. */
    fun tap() = invoke(Haptic.Tap)
    fun confirm() = invoke(Haptic.Confirm)
    fun toggle(on: Boolean) = invoke(if (on) Haptic.ToggleOn else Haptic.ToggleOff)
    fun tick() = invoke(Haptic.Tick)
    fun gestureStart() = invoke(Haptic.GestureStart)
    fun gestureEnd() = invoke(Haptic.GestureEnd)

    /** A drag's position on the seek bar: a [Haptic.FrequentTick] at the start, quarters and end; null ends it. */
    fun marks(fraction: Float?) {
        if (marks.move(fraction)) invoke(Haptic.FrequentTick)
    }

    /** A value watched during a drag: [event] when it changes; null ends the drag. */
    fun onChange(value: Any?, event: Haptic = Haptic.FrequentTick) {
        if (changes.next(value)) invoke(event)
    }

    /** A drag of [dx] watched against [threshold]: [event] once each time it is passed. */
    fun onPass(dx: Float, threshold: Float, event: Haptic = Haptic.Tick) {
        if (enter.next(dx, threshold)) invoke(event)
    }

    private fun showing(context: Context): Boolean {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }
}

/** A [HapticPlayer] for the control this is called in; one per control, so each has its own limit. */
@Composable
fun rememberHaptics(): HapticPlayer {
    val view = LocalView.current
    return remember(view) { HapticPlayer(view) }
}

/**
 * A [Haptic.Tick] when [settled] changes right after the user dragged ([drags], the draggable's
 * interaction source): the sheet came to rest on a new snap point. A change from code (a tap that
 * opens it, Back) plays nothing.
 */
@Composable
fun SettleHaptic(settled: Any?, drags: InteractionSource) {
    val haptics = rememberHaptics()
    val drag = remember { DragWatch() }
    LaunchedEffect(drags) {
        drags.interactions.collect {
            when (it) {
                is DragInteraction.Start -> drag.active = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    drag.active = false
                    drag.endedAt = SystemClock.uptimeMillis()
                }
            }
        }
    }
    LaunchedEffect(settled) {
        val previous = drag.settled
        drag.settled = settled
        if (previous != null && previous != settled && drag.recent(SystemClock.uptimeMillis())) haptics(Haptic.Tick)
    }
}

private class DragWatch {
    var active = false
    var endedAt = Long.MIN_VALUE
    var settled: Any? = null

    /** While dragging, or within the settle animation after letting go. */
    fun recent(now: Long): Boolean = active || (endedAt != Long.MIN_VALUE && now - endedAt in 0..SETTLE_MS)

    private companion object {
        const val SETTLE_MS = 1_500L
    }
}

/** Whether this device has a vibrator, asked once: hardware does not change under a process. */
private object HapticDevice {
    @Volatile
    private var cached: Boolean? = null

    fun hasVibrator(context: Context): Boolean = cached ?: ask(context.applicationContext).also { cached = it }

    /**
     * Plays [event] as a predefined effect, honouring the system's touch feedback switch as a view
     * does. True when played, false when that switch is off, null when the vibrator could not be
     * used (then the view's own feedback is tried).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun play(context: Context, event: Haptic): Boolean? = runCatching {
        val app = context.applicationContext
        val touchFeedback = android.provider.Settings.System.getInt(
            app.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) != 0
        if (!touchFeedback) return@runCatching false
        val vibrator = vibrator(app) ?: return@runCatching null
        val wave = VibrationEffect.createPredefined(HapticRules.predefined(event))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(wave, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                wave,
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
            )
        }
        true
    }.getOrNull()

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun ask(context: Context): Boolean = runCatching { vibrator(context)?.hasVibrator() == true }.getOrDefault(false)
}
