package com.tmplayer.music.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The widget's three buttons. Each press is the user's own: a controller connects to the player
 * (starting it from the saved queue when it is not running), does the one thing, and lets go. It
 * is not exported; only the widget's own PendingIntents reach it. With nothing queued a press does
 * nothing (the widget shows its empty state, which opens the app).
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != NowPlayingWidget.ACTION_PREVIOUS && action != NowPlayingWidget.ACTION_PLAY_PAUSE && action != NowPlayingWidget.ACTION_NEXT) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(ACTION_TIMEOUT_MS) { run(app, action) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "a widget button failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context, action: String) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            // A restored queue comes in a moment after the service starts.
            if (controller.mediaItemCount == 0) awaitQueue(controller)
            if (controller.mediaItemCount == 0) return
            when (action) {
                NowPlayingWidget.ACTION_PREVIOUS -> controller.seekToPrevious()
                NowPlayingWidget.ACTION_NEXT -> controller.seekToNext()
                else -> if (controller.playWhenReady && controller.playbackState != Player.STATE_ENDED) {
                    controller.pause()
                } else {
                    if (controller.playbackState == Player.STATE_ENDED) controller.seekToDefaultPosition()
                    if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }
            }
        } finally {
            controller.release()
        }
    }

    private suspend fun awaitQueue(controller: MediaController) {
        withTimeoutOrNull(QUEUE_WAIT_MS) {
            suspendCancellableCoroutine { done ->
                val listener = object : Player.Listener {
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        if (!timeline.isEmpty && done.isActive) {
                            controller.removeListener(this)
                            done.resume(Unit)
                        }
                    }
                }
                controller.addListener(listener)
                done.invokeOnCancellation { controller.removeListener(listener) }
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { done ->
        addListener(
            {
                try {
                    done.resume(get())
                } catch (e: Throwable) {
                    done.resumeWith(Result.failure(e))
                }
            },
            MoreExecutors.directExecutor(),
        )
        done.invokeOnCancellation { cancel(false) }
    }

    private companion object {
        const val TAG = "WidgetAction"
        const val ACTION_TIMEOUT_MS = 8_000L
        const val QUEUE_WAIT_MS = 3_000L
        val scope = JobFailures.scope("WidgetAction", Dispatchers.Main.immediate)
    }
}
