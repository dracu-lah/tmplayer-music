package com.tmplayer.music.desktop.connect

import com.tmplayer.music.connect.ConnectServer
import com.tmplayer.music.connect.LocalNet
import com.tmplayer.music.connect.PairingGate
import com.tmplayer.music.connect.PairingStore
import com.tmplayer.music.connect.SasRequest
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors

/** What this computer's Connect server is doing, for its Settings line. */
sealed interface ConnectHostStatus {
    /** Not listening: the switch is off. */
    data object Off : ConnectHostStatus
    data object NoNetwork : ConnectHostStatus
    data object Failed : ConnectHostStatus
    data class Listening(val address: String, val port: Int) : ConnectHostStatus
}

/** Announces the server to the local network so a phone need not type an address. Takes its time: always called off the UI thread. */
interface ConnectAdvert {
    fun register(address: InetAddress, port: Int, id: String, name: String)
    fun unregister()
}

/**
 * The desktop's Connect target: the server of core ([ConnectServer]) with this computer's player
 * behind it ([handler]). It listens only on this computer's own private address, only while it is
 * started (the Settings switch, off by default), and speaks to nobody until they prove a pairing secret
 * or the code (or the QR token) on the screen. With the switch off nothing listens, nothing is
 * advertised and nothing is watched. [refresh] follows a change of network (the address it listens on).
 */
class DesktopConnectHost(
    private val handler: DesktopConnectHandler,
    val phones: PairingStore,
    private val deviceId: String,
    private val deviceName: String,
    private val advert: ConnectAdvert,
    private val scope: CoroutineScope,
    private val bind: () -> InetAddress? = { LocalNet.bindAddress() },
    private val allowed: (InetAddress) -> Boolean = LocalNet::isLocal,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The pairing door: the code and the QR token on show, and the lock. Shared by the dialog and the server. */
    val gate = PairingGate(clock)

    private val _status = MutableStateFlow<ConnectHostStatus>(ConnectHostStatus.Off)
    val status: StateFlow<ConnectHostStatus> = _status.asStateFlow()

    private var server: ConnectServer? = null
    private var boundTo: InetAddress? = null
    private var pushJob: Job? = null
    private var sasJob: Job? = null

    /**
     * One thread for the advert, so a register and the unregister that follows (a stop, a new address) run in the
     * order they were asked for and never overlap, however the server's own start and stop interleave. It sleeps
     * when idle and does not keep the program alive.
     */
    private val adverts = Executors.newSingleThreadExecutor { task -> Thread(task, "connect-advert").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val _sas = MutableStateFlow<SasRequest?>(null)

    /** A phone gave the right code and waits for this screen's user to say whether it shows the same number. Null when none. */
    val sasRequest: StateFlow<SasRequest?> = _sas.asStateFlow()

    /** The number of phones connected now; 0 while stopped. */
    val clients: StateFlow<Int> get() = server?.clients ?: ZERO

    /** Starts listening (does nothing when it already does on the right address). */
    @Synchronized
    fun start() {
        val address = bind()
        if (server != null && address == boundTo) return
        shutdown()
        if (address == null) {
            _status.value = ConnectHostStatus.NoNetwork
            return
        }
        val s = ConnectServer(address, deviceId, deviceName, phones, gate, handler, allowed)
        val port = try {
            s.start()
        } catch (e: IOException) {
            Logger.w(TAG, "could not listen", e)
            _status.value = ConnectHostStatus.Failed
            return
        }
        server = s
        boundTo = address
        handler.reply = s::sendTo
        pushJob = handler.watch(s.clients, s::broadcast)
        sasJob = scope.launch { s.sasRequest.collect { _sas.value = it } }
        scope.launch(adverts) { runCatching { advert.register(address, port, deviceId, deviceName) }.onFailure { Logger.w(TAG, "could not advertise", it) } }
        _status.value = ConnectHostStatus.Listening(address.hostAddress.orEmpty(), port)
    }

    /** Stops listening, drops every phone and takes the advert down. */
    @Synchronized
    fun stop() {
        shutdown()
        _status.value = ConnectHostStatus.Off
    }

    /** Re-reads the network: a new address (Wi-Fi changed, a cable plugged) restarts the server on it. Only while started. */
    @Synchronized
    fun refresh() {
        if (server != null || _status.value is ConnectHostStatus.NoNetwork || _status.value is ConnectHostStatus.Failed) start()
    }

    private fun shutdown() {
        val s = server ?: return
        server = null
        boundTo = null
        pushJob?.cancel()
        pushJob = null
        sasJob?.cancel()
        sasJob = null
        _sas.value = null
        handler.reply = { _, _ -> }
        gate.close()
        s.stop()
        scope.launch(adverts) { runCatching { advert.unregister() } }
    }

    /** Shows a fresh code. False when the server is not listening. */
    fun openPairing(): Boolean {
        if (server == null) return false
        gate.open()
        return true
    }

    /** The pairing screen is gone: no code on show, and a phone waiting to be confirmed is told no. */
    fun closePairing() {
        gate.close()
        _sas.value?.decline()
    }

    /** Forgets [id]: its pairing is removed and its connections end. */
    fun forget(id: String) {
        val s = server
        if (s != null) s.forget(id) else phones.forget(id)
    }

    private companion object {
        const val TAG = "DesktopConnectHost"
        val ZERO = MutableStateFlow(0).asStateFlow()
    }
}
