package com.tmplayer.music.desktop.os

import com.tmplayer.music.data.Library
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.AudioEffectsSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.awt.Frame

/**
 * The OS's media controls for the player: the keyboard's media keys, headset buttons and the
 * desktop's now playing widget. MPRIS on Linux (GNOME, KDE and `playerctl` route the media keys
 * through it), the System Media Transport Controls on Windows; macOS has none here, so its media
 * keys do nothing for this app. Nothing here can take the player down: a bus that is not there or a
 * Windows that will not answer is a session that does nothing ([NoMediaSession]).
 *
 * The OS calls arrive on its own threads; [PlayerController] takes every call from any thread.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class MediaControls private constructor(
    private val player: PlayerController,
    private val session: MediaSession,
) : AutoCloseable {

    private val scope = JobFailures.scope("MediaControls", Dispatchers.Default)

    init {
        scope.launch {
            // The clock moves four times a second; the OS extrapolates it, so it is told about once
            // a second, and at once for anything else (a new track, play, pause).
            combine(player.ui, player.position) { ui, position -> ui to position }
                .sample(UPDATE_EVERY_MS)
                .collect { (ui, position) ->
                    val entry = ui.current
                    if (entry == null) {
                        session.clear()
                    } else {
                        val speed = runCatching { AudioEffectsSetting.choices.speed.value.toDouble() }.getOrDefault(1.0)
                        session.update(
                            title = entry.title,
                            durationMs = ui.durationMs.takeIf { it > 0 } ?: entry.durationMs,
                            positionMs = position,
                            playing = ui.playing,
                            artUrl = runCatching { Library.coverFile(entry.coverKey)?.toURI()?.toString() }.getOrNull(),
                            canGoNext = ui.queue.hasNext,
                            canGoPrevious = !ui.queue.isEmpty,
                            artist = entry.artist,
                            album = entry.album,
                            rate = speed,
                        )
                    }
                }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { session.release() }
    }

    companion object {
        private const val UPDATE_EVERY_MS = 500L

        /**
         * Starts the session for this OS. [window] is the app's frame, which Windows ties its
         * controls to (it must have its native window); [raise] brings the window to the front,
         * [quit] ends the app.
         */
        fun start(player: PlayerController, window: Frame?, raise: () -> Unit, quit: () -> Unit): MediaControls {
            val callbacks = object : MediaSessionCallbacks {
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onPlayPause() = player.togglePlay()
                override fun onStop() = player.pause()
                override fun onNext() = player.next()
                override fun onPrevious() = player.previous()
                override fun onSeekBy(offsetMs: Long) = player.seekBy(offsetMs)
                override fun onSeekTo(positionMs: Long) = player.seekTo(positionMs)
                override fun onRaise() = raise()
                override fun onQuit() = quit()
            }
            val session = runCatching { MediaSession.create(callbacks, window) }
                .onFailure { Logger.w("MediaControls", "no media session: ${it.message}") }
                .getOrDefault(NoMediaSession)
            return MediaControls(player, session)
        }
    }
}
