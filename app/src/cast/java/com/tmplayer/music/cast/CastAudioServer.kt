package com.tmplayer.music.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.TD_SCHEME
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress

/**
 * The phone's Wi-Fi, and nothing else: casting needs a Wi-Fi network to find a device on and to serve
 * the audio over. Mobile data, a VPN and Ethernet do not count, so the server can never be reached
 * from outside the Wi-Fi it is on.
 */
internal object WifiNetworks {

    @Suppress("DEPRECATION")
    private fun wifi(context: Context): List<Network> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        return runCatching {
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(emptyList())
    }

    fun connected(context: Context): Boolean = wifi(context).isNotEmpty()

    /** The addresses of the Wi-Fi interface, for [LanAddressPick]. */
    fun candidates(context: Context): List<com.tmplayer.music.cast.LanAddress.Candidate> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        return runCatching {
            wifi(context).flatMap { network ->
                val props = cm.getLinkProperties(network) ?: return@flatMap emptyList()
                props.linkAddresses.map { com.tmplayer.music.cast.LanAddress.Candidate(props.interfaceName.orEmpty(), it.address, it.prefixLength) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Calls [onGone] when the Wi-Fi network is lost, or when [address] is no longer one of its
     * addresses (the phone moved to another access point and was given a new one): the server
     * is bound to [address] and cannot go on. Returns what stops the watch.
     */
    fun watchLoss(context: Context, address: InetAddress, onGone: () -> Unit): () -> Unit {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return {}
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                // Another Wi-Fi network may still hold the address (a roam): only the end of all of them counts.
                if (candidates(context).none { it.address == address }) onGone()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                if (linkProperties.linkAddresses.none { it.address == address } && candidates(context).none { it.address == address }) onGone()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }.onFailure { Logger.w("CastWifi", "could not watch the Wi-Fi", it) }
        return { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}

/**
 * The cast server's owner: starts it on the phone's Wi-Fi address while a cast is on, puts each
 * track and its cover on it under a name that says nothing about them, and stops it. The audio is
 * read through the same Media3 data source the local player streams with ([TdCastSource]), so it
 * is TDLib's byte window, or the file kept offline, and never a whole file in memory.
 */
@UnstableApi
internal class CastAudioServer(private val context: Context, private val sources: DataSource.Factory) {

    private var server: CastServer? = null

    /** The address the server is bound to; null while it is off. */
    var address: InetAddress? = null
        private set

    private val published = HashSet<String>()
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    val running: Boolean get() = server?.running == true

    /** How many requests the receiver (or anyone on the Wi-Fi allowed in) has made since the server started; 0 means it was never reached. */
    val requests: Int get() = server?.served?.get() ?: 0

    /** Starts on the Wi-Fi address that reaches [receiver]. False when the phone has no usable Wi-Fi address or the bind fails. */
    fun start(receiver: InetAddress?): Boolean {
        if (running) return true
        val bind = LanAddress.pick(WifiNetworks.candidates(context), receiver) ?: return false
        val made = CastServer(bind)
        return try {
            made.start()
            server = made
            address = bind
            published.clear()
            true
        } catch (e: IOException) {
            Logger.w(TAG, "the cast server could not start", e)
            runCatching { made.stop() }
            false
        }
    }

    /**
     * Keeps the Wi-Fi and the CPU awake while the user wants music playing (the screen may be off and the
     * receiver still fetching from here), and lets them sleep when it is paused or the cast is over. A
     * wake lock that is never let go would drain the battery, so it carries a timeout too.
     */
    @Suppress("DEPRECATION")
    fun hold(on: Boolean) {
        if (!on || !running) {
            runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            return
        }
        runCatching {
            if (wifiLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tmplayer:cast").apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }
        runCatching {
            if (wakeLock == null) {
                val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tmplayer:cast").apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(HOLD_LIMIT_MS)
        }
    }

    fun stop() {
        hold(false)
        server?.stop()
        server = null
        address = null
        published.clear()
    }

    /** The URL of [item]'s audio as [mime], published once; null while the server is off. */
    fun audioUrl(item: MediaItem, mime: String, sizeBytes: Long): String? {
        val running = server ?: return null
        val uri = item.localConfiguration?.uri ?: return null
        val name = CastUrls.nameFor('a', item.mediaId, CastUrls.extensionFor(mime))
        if (published.add(name)) running.publish(name, CastResource(TdCastSource(sources, uri, sizeBytes)) { mime })
        return running.urlFor(name)
    }

    /** The URL of [item]'s cover picture (its saved file or Telegram's thumbnail), or null when it has none. */
    fun coverUrl(item: MediaItem): String? {
        val running = server ?: return null
        val art = item.mediaMetadata.artworkUri ?: return null
        val name = CastUrls.nameFor('c', item.mediaId, "jpg")
        if (name in published) return running.urlFor(name)
        val resource = when (art.scheme) {
            "file" -> art.path?.let(::File)?.takeIf { it.isFile }?.let { file -> CastResource(FileCastSource(file)) { sniffImage(file) } }
            TD_SCHEME -> CastResource(TdCastSource(sources, art, 0)) { "image/jpeg" }
            else -> null
        } ?: return null
        published += name
        running.publish(name, resource)
        return running.urlFor(name)
    }

    private fun sniffImage(file: File): String = runCatching {
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        when {
            n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
            n >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
    }.getOrDefault("image/jpeg")

    private companion object {
        const val TAG = "CastAudioServer"
        const val HOLD_LIMIT_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * A Telegram file (or one kept offline) read through the player's own data source: TDLib's partial
 * download behind TdByteWindow, asked for at the byte the receiver wants. The length is the one the
 * index holds; with none, it is asked of the source once by opening it at the start.
 */
@UnstableApi
internal class TdCastSource(private val factory: DataSource.Factory, private val uri: Uri, knownLength: Long) : CastSource {

    @Volatile
    private var known = knownLength

    override val length: Long
        get() {
            if (known > 0) return known
            val source = factory.createDataSource()
            try {
                val remaining = source.open(DataSpec(uri, 0, C.LENGTH_UNSET.toLong()))
                if (remaining <= 0) throw IOException("The length is not known")
                known = remaining
            } finally {
                runCatching { source.close() }
            }
            return known
        }

    override fun open(position: Long): CastReader {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(uri, position, C.LENGTH_UNSET.toLong()))
        } catch (e: Exception) {
            runCatching { source.close() }
            throw if (e is IOException) e else IOException(e)
        }
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = source.read(buffer, offset, length)
                return if (n == C.RESULT_END_OF_INPUT) -1 else n
            }

            override fun close() {
                runCatching { source.close() }
            }
        }
    }
}

/** A file on the disk (a cover saved from a track's tags). */
internal class FileCastSource(private val file: File) : CastSource {
    override val length: Long get() = file.length()

    override fun open(position: Long): CastReader {
        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(position)
        } catch (e: IOException) {
            runCatching { raf.close() }
            throw e
        }
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun close() = raf.close()
        }
    }
}
