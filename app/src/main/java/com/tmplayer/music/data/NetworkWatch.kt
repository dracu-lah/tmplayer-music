package com.tmplayer.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.tmplayer.music.platform.Logger

/**
 * Tells the library whether the phone is on an unmetered connection (Wi-Fi, Ethernet), a metered
 * one (mobile data, a hotspot) or none, so tag reading runs in the background on Wi-Fi only unless
 * the user said yes to mobile data. Android's own answer, `NET_CAPABILITY_NOT_METERED` on the active
 * network; when it cannot be had the connection counts as metered, the safe side. Works from
 * Android 6.0 (the callback on a plain request; `registerDefaultNetworkCallback` needs Android 7).
 */
object NetworkWatch {
    private const val TAG = "NetworkWatch"

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            Library.setNetwork(NetState.Metered)
            return
        }
        val update = { Library.setNetwork(stateOf(cm)) }
        update()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
            }
        } catch (e: Exception) {
            // Some builds refuse callbacks (too many registered, a missing permission): the state read now stands.
            Logger.w(TAG, "could not watch the network", e)
        }
    }

    /** The active network's state; metered when Android cannot say. */
    fun stateOf(cm: ConnectivityManager): NetState = try {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        when {
            active == null -> NetState.Offline
            caps == null -> NetState.Metered
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetState.Offline
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetState.Unmetered
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED) -> NetState.Unmetered
            else -> NetState.Metered
        }
    } catch (e: Exception) {
        NetState.Metered
    }
}
