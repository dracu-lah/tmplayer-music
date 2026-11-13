package com.tmplayer.music.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.SleepSetting
import com.tmplayer.music.player.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The regular build's casting: Media3's CastPlayer over Google's Cast framework, plus the audio
 * server on the phone ([CastAudioServer]). A store build has none of this (src/nocast).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object CastBuild {
    /** Why [NoCastSupport] says no here: the Cast framework could not start, which is a phone without Google Play services. */
    val noCastReason: CastAvailability = CastAvailability.NoPlayServices

    private val params: CastParams = CastParams.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setRemoteToLocalEnabled(true)
        .build()

    /** Starts the Cast framework (asynchronously); called once from the application, never on a television. */
    fun initialize(context: Context) {
        runCatching {
            val cast = androidx.media3.cast.Cast.getSingletonInstance(context)
            if (cast.needsInitialization()) cast.initialize(params)
        }.onFailure { Logger.w(TAG, "the Cast framework could not start", it) }
    }

    fun support(context: Context): CastSupport {
        val cast = androidx.media3.cast.Cast.getSingletonInstance(context)
        if (cast.needsInitialization()) cast.initialize(params)
        if (cast.castContextLoadFailure != null) return NoCastSupport
        @Suppress("DEPRECATION")
        val castContext = CastContext.getSharedInstance(context)
        return GoogleCastSupport(context, castContext)
    }

    /**
     * The player the session plays with: the local player wrapped in Media3's CastPlayer, which moves
     * the queue to a Cast device when the user connects and back when the session ends. Any failure
     * here leaves the session with the local player alone.
     */
    fun host(parts: CastHostParts): CastSetup = try {
        val cast = androidx.media3.cast.Cast.getSingletonInstance(parts.context)
        if (cast.needsInitialization()) cast.initialize(params)
        val controller = CastController(parts.context, parts.sources)
        val remote = RemoteCastPlayer.Builder(parts.context).setMediaItemConverter(controller.converter).build()
        val castPlayer = CastPlayer.Builder(parts.context)
            .setLocalPlayer(parts.local)
            .setRemotePlayer(remote)
            .setTransferCallback(controller)
            .build()
        val guard = CastGuardPlayer(parts.context, castPlayer, controller)
        // The speed the user picks goes on to the receiver while casting (the other effects work on this phone only).
        parts.scope.launch { com.tmplayer.music.player.AudioEffectsSetting.choices.speed.collect { guard.syncSpeed() } }
        CastSetup(guard, GoogleCastHost(parts, controller, castPlayer, cast))
    } catch (e: Exception) {
        Logger.w(TAG, "casting is not available: the player works without it", e)
        CastSetup(parts.local, NoCastHost)
    }

    private const val TAG = "CastBuild"
}

/**
 * What the Cast framework reads at start: Google's Default Media Receiver (no receiver app of our own to
 * register), no session picked up again behind the user's back (a device is connected only when they
 * pick it), the receiver stopped when the session ends, and none of the framework's own notification or
 * lock screen controls: the app's media session already shows them.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setResumeSavedSession(false)
        .setEnableReconnectionService(false)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setRemoteToLocalEnabled(true)
        .setCastMediaOptions(CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** The playback service's handle on the cast: ends it when the app is removed from recents, and lets go of the server. */
@UnstableApi
internal class GoogleCastHost(
    private val parts: CastHostParts,
    private val controller: CastController,
    private val castPlayer: CastPlayer,
    private val cast: androidx.media3.cast.Cast,
) : CastHost {

    private val main = Handler(Looper.getMainLooper())
    private var client: RemoteMediaClient? = null

    /** Notes where the receiver was just before its session ends, while it can still be asked, and watches it from the start. */
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnding(session: CastSession) {
            controller.capture(castPlayer)
            stopWatching()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = controller.capture(castPlayer)
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = startWatching(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = stopWatching()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = startWatching(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
    }

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onReceiverStatus()
    }

    /**
     * Until the receiver has asked this phone for anything, looks every [TICK_MS] whether it ever will
     * (client isolation on the Wi-Fi leaves it waiting on an address it cannot open). It stops itself once the
     * receiver has been heard from, when the cast ends, and does not count while the music is paused.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (controller.checkReachable(castPlayer.playWhenReady)) main.postDelayed(this, TICK_MS)
        }
    }

    private fun startWatching(session: CastSession) {
        stopWatching()
        client = session.remoteMediaClient?.also { runCatching { it.registerCallback(clientCallback) } }
        main.postDelayed(tick, TICK_MS)
    }

    private fun stopWatching() {
        main.removeCallbacks(tick)
        client?.let { runCatching { it.unregisterCallback(clientCallback) } }
        client = null
    }

    /**
     * The receiver's own word on what it is doing. A track it could not load or play (the status is idle with an error)
     * is skipped, a little later so a receiver that moves on by itself is not skipped twice, and counted for the sentence at the end.
     */
    private fun onReceiverStatus() {
        val status = client?.mediaStatus ?: return
        if (controller.record == null) return
        when (status.playerState) {
            MediaStatus.PLAYER_STATE_IDLE -> if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) receiverFailed(status.currentItemId, status)
            MediaStatus.PLAYER_STATE_PLAYING -> controller.receiverActive(true)
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING, MediaStatus.PLAYER_STATE_PAUSED -> controller.receiverActive(false)
        }
    }

    private fun receiverFailed(itemId: Int, status: MediaStatus) {
        val queueItem = status.getQueueItemById(itemId)
        val id = CastItemConverter.idOf(queueItem).ifEmpty { "item:$itemId" }
        val title = CastItemConverter.titleOf(queueItem)
        if (controller.receiverFailed(id, title) != CastController.Skip.Next) return
        main.postDelayed({
            val now = client?.mediaStatus
            val stuck = now != null && now.playerState == MediaStatus.PLAYER_STATE_IDLE && now.idleReason == MediaStatus.IDLE_REASON_ERROR && now.currentItemId == itemId
            if (stuck && castPlayer.isCastSessionAvailable && castPlayer.hasNextMediaItem()) {
                castPlayer.seekToNextMediaItem()
                castPlayer.play()
            }
        }, SKIP_DELAY_MS)
    }

    /** The sleep timer's end of track while casting: the receiver has no "pause at the end of the item", so it is seen here. */
    private val sleepWatch = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) endOfTrack()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) endOfTrack()
        }
    }

    init {
        cast.addSessionManagerListener(listener)
        castPlayer.addListener(sleepWatch)
    }

    private fun endOfTrack() {
        if (SleepTimer.setting.value != SleepSetting.EndOfTrack || !castPlayer.isCastSessionAvailable) return
        SleepTimer.off()
        onSleep()
    }

    override fun onSleep() {
        if (!castPlayer.isCastSessionAvailable) return
        // Paused on the receiver first, so it is silent whatever the session end does; then the cast ends and the queue comes back here, paused.
        runCatching { castPlayer.pause() }
        controller.endSessionSoon(EndReason.Sleep)
    }

    override fun onTaskRemoved(done: () -> Unit) {
        if (!castPlayer.isCastSessionAvailable) return done()
        // Decided: removing the app from recents ends the cast. The phone's controls go with the app, and the audio
        // server must not go on serving unattended. The queue comes back to this phone, paused where it was.
        controller.endSessionSoon()
        parts.scope.launch {
            var waited = 0
            while (castPlayer.isCastSessionAvailable && waited < SETTLE_MS) {
                delay(STEP_MS)
                waited += STEP_MS.toInt()
            }
            done()
        }
    }

    override fun release() {
        stopWatching()
        runCatching { cast.removeSessionManagerListener(listener) }
        runCatching { castPlayer.removeListener(sleepWatch) }
        controller.release()
    }

    private companion object {
        const val SETTLE_MS = 3_000
        const val TICK_MS = 2_000L
        const val SKIP_DELAY_MS = 1_500L
        const val STEP_MS = 150L
    }
}
