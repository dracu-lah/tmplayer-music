package com.tmplayer.music.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sleep timer is set to. */
sealed interface SleepSetting {
    data object Off : SleepSetting

    /** Pause when the clock ([SystemClock.elapsedRealtime]) reaches [endsAt]. */
    data class At(val endsAt: Long) : SleepSetting

    /** Pause when the track playing now ends, also with repeat one. */
    data object EndOfTrack : SleepSetting
}

/**
 * The sleep timer. The screens set it; [PlaybackService] carries it out, so it works with the screen
 * off and the app closed. By minutes, the last [FADE_MS] fade the volume down before the pause, and
 * the volume is put back for the next play. At the end of the track it is Media3's own "pause at
 * the end of the item", which also holds with repeat one. Either way the timer turns itself off
 * once it has paused, and nothing ticks while it waits.
 */
object SleepTimer {
    private val _setting = MutableStateFlow<SleepSetting>(SleepSetting.Off)
    val setting: StateFlow<SleepSetting> = _setting.asStateFlow()

    fun minutes(count: Int) {
        _setting.value = SleepSetting.At(SystemClock.elapsedRealtime() + count.coerceIn(1, 24 * 60) * 60_000L)
    }

    fun endOfTrack() {
        _setting.value = SleepSetting.EndOfTrack
    }

    fun off() {
        _setting.value = SleepSetting.Off
    }

    /** Milliseconds left, or 0 when not set by minutes. */
    fun remainingMs(): Long = (setting.value as? SleepSetting.At)?.let { (it.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) } ?: 0

    /**
     * Runs the timer against [player] for the life of [scope] (the service's). Opted in for
     * `pauseAtEndOfMediaItems`, which Media3 still marks unstable.
     */
    @OptIn(UnstableApi::class)
    fun attach(player: ExoPlayer, scope: CoroutineScope) {
        var job: Job? = null
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Paused at the end of the track because of the timer: the timer is done.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && setting.value == SleepSetting.EndOfTrack) {
                    off()
                }
            }
        })
        scope.launch {
            setting.collect { now ->
                job?.cancel()
                player.pauseAtEndOfMediaItems = now == SleepSetting.EndOfTrack
                if (now is SleepSetting.At) {
                    job = scope.launch {
                        val wait = now.endsAt - SystemClock.elapsedRealtime() - FADE_MS
                        if (wait > 0) delay(wait)
                        val volume = player.volume
                        try {
                            val steps = 20
                            for (i in 1..steps) {
                                player.volume = volume * (steps - i) / steps
                                delay(FADE_MS / steps)
                            }
                            player.pause()
                        } finally {
                            // Also when the timer is turned off part way through the fade.
                            player.volume = volume
                        }
                        off()
                    }
                }
            }
        }
    }

    const val FADE_MS = 5_000L
}
