package com.tmplayer.music.desktop.connect

import com.tmplayer.music.connect.ConnectLimits
import com.tmplayer.music.platform.Logger
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces this computer as `_tmpmusic._tcp` (mDNS through JmDNS), the same service the TV app
 * registers with Android's NsdManager, so the phone's discovery finds both the same way. It carries
 * only the install id, the name and the protocol version: no library, no account. JmDNS listens on
 * the one address the server listens on, not on every interface.
 */
class JmdnsAdvert : ConnectAdvert {
    private var jmdns: JmDNS? = null

    @Synchronized
    override fun register(address: InetAddress, port: Int, id: String, name: String) {
        unregister()
        val j = try {
            JmDNS.create(address, "tmplayer-music")
        } catch (e: Exception) {
            Logger.w(TAG, "mDNS could not start; a phone can still type the address", e)
            return
        }
        val info = ServiceInfo.create(
            SERVICE_TYPE, "TMPlayer Music ${name.take(24)}".trim(), port, 0, 0,
            mapOf("id" to id, "n" to name.take(40), "v" to ConnectLimits.VERSION.toString()),
        )
        try {
            j.registerService(info)
            jmdns = j
        } catch (e: Exception) {
            Logger.w(TAG, "could not advertise", e)
            runCatching { j.close() }
        }
    }

    @Synchronized
    override fun unregister() {
        val j = jmdns ?: return
        jmdns = null
        runCatching {
            j.unregisterAllServices()
            j.close()
        }
    }

    private companion object {
        const val TAG = "JmdnsAdvert"
        const val SERVICE_TYPE = "_tmpmusic._tcp.local."
    }
}
