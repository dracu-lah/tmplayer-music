package com.tmplayer.music.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress

/** A TV found on the network: its install id (from its advert), its name and where to connect. */
data class FoundTv(val id: String, val name: String, val host: String, val port: Int)

/** The service type the TV advertises and the phone looks for. */
private const val SERVICE_TYPE = "_tmpmusic._tcp."
private const val TAG = "ConnectNsd"

/**
 * The TV announcing itself on the local network (Android's NsdManager, which is mDNS), so a phone
 * need not type an address. It carries only the TV's install id, its name and the protocol version:
 * no library, no account. Registered while the server listens, taken down when it stops.
 */
class NsdAdvert(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(port: Int, id: String, name: String) {
        val nsd = manager ?: return
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "TMPlayer Music ${name.take(24)}".trim()
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", id)
            setAttribute("n", name.take(40))
            setAttribute("v", ConnectLimits.VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Logger.w(TAG, "advert failed: $errorCode")
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Logger.w(TAG, "unadvert failed: $errorCode")
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure {
            Logger.w(TAG, "could not advertise", it)
            listener = null
        }
    }

    @Synchronized
    fun unregister() {
        val l = listener ?: return
        listener = null
        runCatching { manager?.unregisterService(l) }
    }
}

/**
 * Looks for TVs while a screen asks (the pairing dialog, the remote): [scan] starts discovery when
 * its flow is collected and stops it when the collector goes, so nothing looks in the background.
 * Android resolves one service at a time, so found services queue up and are resolved in turn.
 */
object TvDiscovery {
    fun scan(context: Context): Flow<List<FoundTv>> = callbackFlow {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val lock = Any()
        val found = LinkedHashMap<String, FoundTv>()
        val byService = HashMap<String, String>()
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var stopped = false

        fun publish() = trySend(synchronized(lock) { found.values.toList() })

        fun next() {
            val info = synchronized(lock) {
                if (resolving || stopped) return
                waiting.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            val done = {
                synchronized(lock) { resolving = false }
                next()
            }
            @Suppress("DEPRECATION")
            val started = runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = done()
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        val tv = serviceInfo?.let(::foundOf)
                        if (tv != null) {
                            synchronized(lock) {
                                found[tv.id] = tv
                                byService[serviceInfo.serviceName.orEmpty()] = tv.id
                            }
                            publish()
                        }
                        done()
                    }
                })
            }.isSuccess
            if (!started) done()
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Logger.w(TAG, "discovery failed: $errorCode")
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                synchronized(lock) { waiting.addLast(serviceInfo) }
                next()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val gone = synchronized(lock) { byService.remove(serviceInfo?.serviceName.orEmpty())?.let { found.remove(it) } }
                if (gone != null) publish()
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.onFailure {
            Logger.w(TAG, "could not look for TVs", it)
        }
        trySend(emptyList())
        awaitClose {
            synchronized(lock) { stopped = true }
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private fun foundOf(info: NsdServiceInfo): FoundTv? {
        val id = info.attributes?.get("id")?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
            ?.takeIf { it.length in 16..64 && it.all { c -> Character.digit(c, 16) >= 0 } } ?: return null
        val name = info.attributes?.get("n")?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }?.take(40)
            ?: info.serviceName.orEmpty()
        val address: InetAddress = info.host ?: return null
        if (!LocalNet.isLocal(address)) return null
        val port = info.port.takeIf { it in 1..65535 } ?: return null
        return FoundTv(id.lowercase(), name, address.hostAddress ?: return null, port)
    }
}
