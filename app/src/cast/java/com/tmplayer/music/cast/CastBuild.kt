package com.tmplayer.music.cast

import android.content.Context
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.tmplayer.music.platform.Logger
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

    /** Notes where the receiver was just before its session ends, while it can still be asked. */
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnding(session: CastSession) = controller.capture(castPlayer)
        override fun onSessionSuspended(session: CastSession, reason: Int) = controller.capture(castPlayer)
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
    }

    init {
        cast.addSessionManagerListener(listener)
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
        runCatching { cast.removeSessionManagerListener(listener) }
        controller.release()
    }

    private companion object {
        const val SETTLE_MS = 3_000
        const val STEP_MS = 150L
    }
}
