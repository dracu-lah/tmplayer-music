package com.tmplayer.music.platform

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * What a touch has just done, as the hand should feel it: named for the moment rather than for the
 * motor, so a screen says "a toggle went on" and [HapticRules] decides what this phone can play for
 * it. The same set and fallbacks as TMPlayer's helper, so both apps feel alike.
 */
enum class Haptic {
    /** A press that does something at once: previous, next, a lyrics line, a sleep timer choice. */
    Tap,

    /** Something asked for is done: play or pause, a favourite changed. */
    Confirm,

    /** A switch turned on: shuffle on, repeat on. */
    ToggleOn,

    /** The same turned off. */
    ToggleOff,

    /** One step crossed: a snap point, a skip threshold, a dropped queue row. */
    Tick,

    /** A step of a drag that crosses many in a second: the seek bar's marks, a queue row's slots. */
    FrequentTick,

    /** A gesture took hold: scrubbing started, a queue row picked up. */
    GestureStart,

    /** That gesture let go: lighter than the start. */
    GestureEnd,
}

/**
 * How a [Haptic] is played, and whether it is played at all, as plain functions so the decision is
 * tested away from a device. The Android half (`ui/common/Haptics.kt`) only asks these.
 */
object HapticRules {
    // android.view.HapticFeedbackConstants, mirrored so the mapping runs in a JVM test. The values
    // are part of the public API and never change.
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val CLOCK_TICK = 4
    const val TEXT_HANDLE_MOVE = 9
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val CONFIRM = 16
    const val TOGGLE_ON = 21
    const val TOGGLE_OFF = 22
    const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27

    /** Build.VERSION_CODES, for the same reason. */
    const val API_O_MR1 = 27
    const val API_Q = 29
    const val API_R = 30
    const val API_U = 34

    /**
     * The view constant to play [event] with on Android [sdk], or null for nothing. A newer constant
     * falls back to the nearest older one of a similar weight; Android 6.0 (23) is the floor.
     */
    fun constant(event: Haptic, sdk: Int): Int? = when (event) {
        Haptic.Tap -> VIRTUAL_KEY
        Haptic.Confirm -> if (sdk >= API_R) CONFIRM else VIRTUAL_KEY
        Haptic.ToggleOn -> if (sdk >= API_U) TOGGLE_ON else VIRTUAL_KEY
        Haptic.ToggleOff -> if (sdk >= API_U) TOGGLE_OFF else CLOCK_TICK
        Haptic.Tick -> if (sdk >= API_U) SEGMENT_TICK else CLOCK_TICK
        Haptic.FrequentTick -> when {
            sdk >= API_U -> SEGMENT_FREQUENT_TICK
            sdk >= API_O_MR1 -> TEXT_HANDLE_MOVE
            else -> CLOCK_TICK
        }
        Haptic.GestureStart -> if (sdk >= API_R) GESTURE_START else CLOCK_TICK
        Haptic.GestureEnd -> when {
            sdk >= API_R -> GESTURE_END
            sdk >= API_O_MR1 -> TEXT_HANDLE_MOVE
            // No lighter release before 8.1: a second full tick on letting go is noise.
            else -> null
        }
    }

    /** VibrationEffect's predefined ids (API 29 and up), as numbers so the rules stay testable. */
    const val EFFECT_CLICK = 0
    const val EFFECT_TICK = 2

    /**
     * The vibrator's predefined effect for [event], played first from Android 10: some phones
     * (HyperOS) drop a view's haptic feedback silently, while every vibrator plays these. A press,
     * a confirm and a switch turned on click; the lighter moments tick.
     */
    fun predefined(event: Haptic): Int = when (event) {
        Haptic.Tap, Haptic.Confirm, Haptic.ToggleOn -> EFFECT_CLICK
        Haptic.ToggleOff, Haptic.Tick, Haptic.FrequentTick, Haptic.GestureStart, Haptic.GestureEnd -> EFFECT_TICK
    }

    /**
     * Whether to play anything: the app's "Haptic feedback" is [enabled], the device has a vibrator,
     * and the screen is on and showing the app ([interactive]). The system's own touch feedback
     * switch is honoured on top of this by the platform.
     */
    fun allowed(enabled: Boolean, hasVibrator: Boolean, interactive: Boolean): Boolean =
        enabled && hasVibrator && interactive
}

/**
 * At most one buzz per [gapMs] from one source, so quick taps or a fast drag never turn into a hum.
 * A clock that goes backwards (a test, a changed uptime) lets the next one through rather than
 * blocking for good.
 */
class HapticLimiter(private val gapMs: Long = GAP_MS) {
    private var last = Long.MIN_VALUE

    fun allow(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs >= last && nowMs - last < gapMs) return false
        last = nowMs
        return true
    }

    companion object {
        const val GAP_MS = 50L
    }
}

/**
 * The seek bar's marks: the start, a quarter, half, three quarters and the end. [move] answers true
 * when the drag reaches or crosses one, so a scrub ticks at most five times across the whole bar,
 * never per frame. Null ends the drag; the first position after it only sets where it started.
 */
class MarkCrossing(private val marks: FloatArray = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
    private var last: Float? = null

    fun move(fraction: Float?): Boolean {
        val from = last
        last = fraction?.takeIf { !it.isNaN() }
        val to = last ?: return false
        if (from == null || from == to) return false
        return marks.any { m -> (from < m && to >= m) || (from > m && to <= m) }
    }
}

/**
 * A value watched during a drag (the slot a queue row would land in): true when it changes from one
 * value to another. Null ends the drag; the first value after it only sets where it started.
 */
class ChangeTick<T : Any> {
    private var last: T? = null

    fun next(value: T?): Boolean {
        val from = last
        last = value
        return from != null && value != null && from != value
    }
}

/** A threshold watched during a drag (the mini-player's skip distance): true when it is first passed. */
class EnterTick {
    private var inside = false

    fun next(past: Boolean): Boolean {
        val entered = past && !inside
        inside = past
        return entered
    }

    /** True when [dx] has gone [threshold] or further either way. */
    fun next(dx: Float, threshold: Float): Boolean = next(threshold > 0f && abs(dx) >= threshold)
}

/** The user's "Haptic feedback" switch, kept across restarts; on until they turn it off. */
class HapticChoices(private val store: KeyValues) {
    private val _enabled = MutableStateFlow(runCatching { store.getBoolean(KEY, true) }.getOrDefault(true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        runCatching { store.putBoolean(KEY, on) }
    }

    private companion object {
        const val KEY = "haptics"
    }
}

/**
 * Where the app keeps its [HapticChoices]: in memory (and on) until [start] gives it the app's
 * store. Read at each buzz, so a change in Settings applies at once without a restart.
 */
object HapticSetting {
    @Volatile
    var choices: HapticChoices = HapticChoices(MemoryKeyValues())
        private set

    fun start(store: KeyValues) {
        choices = HapticChoices(store)
    }
}
