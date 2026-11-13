// From TMPlayer (GPL-3.0), app/src/cast/java/com/tmplayer/cast/CastBuild.kt (GoogleCastSupport), cut down to
// finding devices and connecting; what is played is Media3's CastPlayer (CastBuild.host).
package com.tmplayer.music.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [CastSupport] over the Cast framework: MediaRouter finds the devices while the Cast sheet is open and
 * selecting one starts a [CastSession]; Media3's CastPlayer notices the session and moves the queue (see
 * [CastController]). Looking for devices runs only while the sheet is open, and no device is connected
 * to unless the user picks it: the options say no session is resumed on its own. Main thread only.
 */
internal class GoogleCastSupport(private val context: Context, private val castContext: CastContext) : CastSupport {

    private val main = Handler(Looper.getMainLooper())
    private val router: MediaRouter? = runCatching { MediaRouter.getInstance(context) }.getOrNull()
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    override val availability: CastAvailability = if (router != null) CastAvailability.Yes else CastAvailability.NoPlayServices

    private val _devices = MutableStateFlow<List<CastDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<CastDeviceInfo>> = _devices.asStateFlow()
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    override val state: StateFlow<CastState> = _state.asStateFlow()
    private val _volume = MutableStateFlow(CastVolume())
    override val volume: StateFlow<CastVolume> = _volume.asStateFlow()
    private val _onWifi = MutableStateFlow(WifiNetworks.connected(context))
    override val onWifi: StateFlow<Boolean> = _onWifi.asStateFlow()

    private var discovering = 0
    private var connectingId: String? = null
    private var session: CastSession? = null

    /** Until when an unselect of the device's route is our own letting go of a stale selection. */
    private var releasingUntilMs = 0L
    private var reselectsLeft = 0

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            // The device being connected to was let go by something other than us (the phone's own route selected
            // under it): selected again, once.
            val ours = SystemClock.elapsedRealtime() < releasingUntilMs
            if (route.id == connectingId && !ours && reselectsLeft > 0 && _state.value is CastState.Connecting) {
                reselectsLeft--
                val id = route.id
                main.postDelayed({ if (connectingId == id) router.routes.firstOrNull { it.id == id }?.let(router::selectRoute) }, RESELECT_DELAY_MS)
            }
            refresh()
        }
    }

    /** The device's volume while a session is on (no discovery asked). */
    private val volumeCallback = object : MediaRouter.Callback() {
        override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishVolume()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishVolume()
    }

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() = publishVolume()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _state.value = CastState.Connecting(session.castDevice?.friendlyName.orEmpty())
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Logger.w(TAG, "Cast session failed to start: $error")
            _state.value = CastState.Failed(session.castDevice?.friendlyName.orEmpty())
            connectingId = null
            refresh()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            connectingId = null
            detach()
            _state.value = CastState.Idle
            refresh()
        }

        // A session is never resumed (the options turn it off); these only keep the state honest if one is.
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    init {
        castContext.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    private fun attach(session: CastSession) {
        detach()
        this.session = session
        connectingId = null
        session.addCastListener(castListener)
        router?.addCallback(selector, volumeCallback, 0)
        _state.value = CastState.Connected(session.castDevice?.friendlyName.orEmpty())
        publishVolume()
        refresh()
    }

    private fun detach() {
        session?.let { runCatching { it.removeCastListener(castListener) } }
        session = null
        runCatching { router?.removeCallback(volumeCallback) }
        _volume.value = CastVolume()
    }

    private fun publishVolume() {
        val s = session ?: return
        val route = router?.selectedRoute?.takeIf { !it.isDefault && it.matchesSelector(selector) }
        val fixed = route?.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED
        val routeLevel = route?.takeIf { it.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && it.volumeMax > 0 }
            ?.let { it.volume.toDouble() / it.volumeMax }
        val level = (routeLevel ?: runCatching { s.volume }.getOrNull() ?: 1.0).coerceIn(0.0, 1.0)
        _volume.value = CastVolume(level, runCatching { s.isMute }.getOrDefault(false), fixed)
    }

    private fun refresh() {
        val r = router ?: return
        val connected = _state.value is CastState.Connected
        _devices.value = r.routes
            .filter { it.isEnabled && !it.isDefault && !it.isBluetooth && it.matchesSelector(selector) }
            .map { route ->
                val device = runCatching { CastDevice.getFromBundle(route.extras) }.getOrNull()
                CastDeviceInfo(
                    id = route.id,
                    name = route.name,
                    model = device?.modelName ?: route.description.orEmpty(),
                    connected = connected && route.id == r.selectedRoute.id,
                    connecting = route.id == connectingId,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private fun watchWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = main.post { _onWifi.value = WifiNetworks.connected(context) }.let { }
            override fun onLost(network: Network) = main.post { _onWifi.value = WifiNetworks.connected(context) }.let { }
        }
        wifiCallback = callback
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        }
    }

    private fun unwatchWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        wifiCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        wifiCallback = null
    }

    override fun startDiscovery() {
        val r = router ?: return
        _onWifi.value = WifiNetworks.connected(context)
        if (discovering++ == 0) {
            r.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            watchWifi()
        }
        refresh()
    }

    override fun stopDiscovery() {
        val r = router ?: return
        if (discovering > 0 && --discovering == 0) {
            // The sheet closes the moment a device is tapped; the route stays looked after until the session is up or failed.
            if (connectingId == null) {
                r.removeCallback(routeCallback)
                unwatchWifi()
            }
        }
    }

    /**
     * Connects to the device [id]. MediaRouter ignores a request to select the route it already has
     * selected, so a route left selected with no session of ours is let go first (the phone's own route selected)
     * and selected afresh a moment later (the same two steps as TMPlayer's).
     */
    override fun connect(id: String) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == id } ?: return
        if (!WifiNetworks.connected(context)) {
            _onWifi.value = false
            _state.value = CastState.Failed(route.name)
            CastNotices.post(context, L.castNoWifi)
            return
        }
        CastNotices.clear()
        connectingId = id
        _state.value = CastState.Connecting(route.name)
        refresh()
        reselectsLeft = 1
        val stale = r.selectedRoute.id == route.id && castContext.sessionManager.currentCastSession?.isConnected != true
        if (stale) {
            releasingUntilMs = SystemClock.elapsedRealtime() + RESELECT_DELAY_MS
            r.selectRoute(r.defaultRoute)
            main.postDelayed({ if (connectingId == id) r.routes.firstOrNull { it.id == id }?.let(r::selectRoute) }, RESELECT_DELAY_MS)
        } else {
            r.selectRoute(route)
        }
        // A connection that never answers is given up, so the sheet does not say Connecting for ever.
        main.postDelayed({
            if (connectingId == id && _state.value is CastState.Connecting) {
                Logger.w(TAG, "No session after ${CONNECT_TIMEOUT_MS} ms")
                connectingId = null
                _state.value = CastState.Failed(route.name)
                // The route is let go, so the framework does not keep trying behind the user's back.
                runCatching { castContext.sessionManager.endCurrentSession(true) }
                if (discovering == 0) {
                    r.removeCallback(routeCallback)
                    unwatchWifi()
                }
                refresh()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    override fun endSession() {
        castContext.sessionManager.endCurrentSession(true)
    }

    override fun setVolume(level: Double) {
        val v = level.coerceIn(0.0, 1.0)
        val route = router?.selectedRoute?.takeIf { !it.isDefault && it.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && it.volumeMax > 0 }
        if (route != null) route.requestSetVolume(Math.round(v * route.volumeMax).toInt()) else runCatching { session?.volume = v }
        if (v > 0 && _volume.value.muted) runCatching { session?.isMute = false }
        // Shown at once; the device's own word follows.
        _volume.value = _volume.value.copy(level = v, muted = if (v > 0) false else _volume.value.muted)
    }

    private companion object {
        const val TAG = "GoogleCastSupport"
        const val RESELECT_DELAY_MS = 300L
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
