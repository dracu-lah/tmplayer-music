package com.tmplayer.music.connect

import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** What the TV does with what its phones say. Called on the server's own threads: hand work to the right thread. */
interface ConnectHandler {
    /** What a phone that just connected is told first: the whole picture. */
    fun snapshot(): List<ConnectBody>

    /** A command from a trusted phone. Never called before the phone proved itself. */
    fun onCommand(from: String, body: ConnectBody)
}

/**
 * The TV's side of Connect: a small line based TCP server (one JSON frame per line, see
 * [ConnectFrame]) that listens on one local network address only, and speaks to nobody until they
 * prove a pairing secret or the code on the screen. State goes out only when [broadcast] is
 * called, so an idle server is a thread asleep in accept and, per phone, two asleep in a read and a
 * queue wait: no timer, no polling.
 *
 * The caller (the TV app) decides when it runs ([ConnectPolicy]): [start] when allowed, [stop] when not.
 *
 * Limits: [MAX_CLIENTS] trusted phones at once, [MAX_PENDING] connections still in the handshake,
 * each given [HANDSHAKE_MS]; a trusted connection that says nothing for [IDLE_MS] is dropped (the
 * phone pings between).
 */
class ConnectServer(
    private val bind: InetAddress,
    private val hostId: String,
    private val hostName: String,
    private val phones: PairingStore,
    val gate: PairingGate,
    private val handler: ConnectHandler,
    private val allowed: (InetAddress) -> Boolean = LocalNet::isLocal,
    private val requestedPort: Int = 0,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Peer(val id: String, val wire: ConnectWire)

    private val peers = CopyOnWriteArrayList<Peer>()
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val _clients = MutableStateFlow(0)

    /** How many trusted phones are connected now. */
    val clients: StateFlow<Int> = _clients.asStateFlow()

    @Volatile
    private var server: ServerSocket? = null

    /** The port listened on; 0 while stopped. */
    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = server != null

    /** Starts listening on the address given at the start. Throws [IOException] when it cannot. */
    @Synchronized
    fun start(): Int {
        server?.let { return port }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bind, requestedPort), BACKLOG)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        server = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "connect-accept").apply { isDaemon = true }.start()
        return port
    }

    /** Closes the listener and every connection. */
    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        port = 0
        runCatching { socket.close() }
        for (peer in peers) peer.wire.close()
        peers.clear()
        _clients.value = 0
    }

    /** Sends [body] to every trusted phone. */
    fun broadcast(body: ConnectBody) {
        for (peer in peers) peer.wire.send(body)
    }

    /** Sends [body] to the phone [id] only. */
    fun sendTo(id: String, body: ConnectBody) {
        for (peer in peers) if (peer.id == id) peer.wire.send(body)
    }

    /** Forgets [id]: its pairing is removed and its connections closed. */
    fun forget(id: String) {
        phones.forget(id)
        for (peer in peers) if (peer.id == id) peer.wire.close()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            try {
                if (!allowed(client.inetAddress) || pending.get() >= MAX_PENDING || peers.size >= MAX_CLIENTS) {
                    // A refusal costs nothing: no thread, no work.
                    runCatching { client.close() }
                    continue
                }
                pending.incrementAndGet()
                Thread({ handshake(client) }, "connect-hello").apply { isDaemon = true }.start()
            } catch (e: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handshake(socket: Socket) {
        var counted = true
        var handedOver = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_MS
            val lines = BoundedLines(socket.getInputStream())
            val out = socket.getOutputStream()
            fun write(frame: ConnectFrame) {
                out.write((ConnectFrames.encode(frame) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val sn = ConnectHandshake.newNonce()
            // A new key pair for every connection: nothing recorded fits another one.
            val key = EphemeralKey.generate()
            write(ConnectFrame.Hello(ConnectLimits.VERSION, sn, hostId, hostName, key.publicHex))
            val first = ConnectFrames.parse(lines.next() ?: return)
            val (id, channel) = when (first) {
                is ConnectFrame.Auth -> authenticate(first, sn, key, ::write) ?: return
                is ConnectFrame.Pair -> pair(first, sn, key, ::write) ?: return
                else -> {
                    write(ConnectFrame.Err(ConnectErrors.MALFORMED))
                    return
                }
            }
            socket.soTimeout = IDLE_MS
            pending.decrementAndGet()
            counted = false
            lateinit var self: Peer
            val w = ConnectWire(socket, lines, out, channel, pingEveryMs = 0, onBody = { body -> dispatch(self.id, body) }, onClosed = {
                peers.remove(self)
                _clients.value = peers.size
            })
            self = Peer(id, w)
            if (server == null) return
            // A phone that connects again replaces its earlier connection (which may be half dead).
            for (old in peers) if (old.id == id) old.wire.close()
            peers.add(self)
            handedOver = true
            _clients.value = peers.size
            for (body in runCatching { handler.snapshot() }.getOrDefault(emptyList())) w.send(body)
            w.start("connect-peer")
        } catch (_: IOException) {
        } catch (e: RuntimeException) {
            Logger.w(TAG, "a connection ended on an error", e)
        } finally {
            if (counted) pending.decrementAndGet()
            if (!handedOver) runCatching { socket.close() }
        }
    }

    /** A paired phone: it must prove the secret, and the host proves it back. */
    private fun authenticate(frame: ConnectFrame.Auth, sn: String, key: EphemeralKey, write: (ConnectFrame) -> Unit): Pair<String, SecureChannel>? {
        if (frame.version != ConnectLimits.VERSION) {
            write(ConnectFrame.Err(ConnectErrors.VERSION))
            return null
        }
        val entry = phones.find(frame.id)
        val secret = entry?.secretBytes
        if (entry == null || secret == null) {
            write(ConnectFrame.Err(ConnectErrors.UNPAIRED))
            return null
        }
        val shared = key.agree(frame.key)
        if (shared == null) {
            write(ConnectFrame.Err(ConnectErrors.MALFORMED))
            return null
        }
        val th = ConnectHandshake.transcript("auth", frame.version, sn, frame.nonce, hostId, key.publicHex, frame.id, frame.name, frame.key)
        if (!ConnectCrypto.same(frame.mac, ConnectHandshake.authMac(secret, th))) {
            write(ConnectFrame.Err(ConnectErrors.DENIED))
            return null
        }
        write(ConnectFrame.Ready(ConnectHandshake.readyMac(secret, th)))
        return frame.id to ConnectHandshake.channel(shared, secret, th, host = true)
    }

    /**
     * A new phone: it must prove the code on the screen (or the token of its QR code) inside the key
     * exchange. Wrong tries are counted for the whole TV, so each attempt is one guess.
     */
    private fun pair(frame: ConnectFrame.Pair, sn: String, key: EphemeralKey, write: (ConnectFrame) -> Unit): Pair<String, SecureChannel>? {
        if (frame.version != ConnectLimits.VERSION) {
            write(ConnectFrame.Err(ConnectErrors.VERSION))
            return null
        }
        val shared = key.agree(frame.key)
        if (shared == null) {
            // Not a guess at the code: no point of the curve, no try counted.
            write(ConnectFrame.Err(ConnectErrors.MALFORMED))
            return null
        }
        val th = ConnectHandshake.transcript(frame.via.wire, frame.version, sn, frame.nonce, hostId, key.publicHex, frame.id, frame.name, frame.key)
        var confirmed: ByteArray? = null
        val verdict = gate.attempt { offer ->
            val proof = if (frame.via == ConnectHandshake.Via.Token) offer.token else offer.code
            val candidate = ConnectHandshake.confirmKey(shared, th, frame.via, proof)
            val right = ConnectCrypto.same(frame.mac, ConnectHandshake.pairMac(candidate, th))
            if (right) confirmed = candidate
            right
        }
        val confirmKey = confirmed
        when (verdict) {
            PairingVerdict.Accepted -> {
                if (confirmKey == null) return null
                val secret = ConnectHandshake.newSecret()
                phones.add(Paired(frame.id, frame.name.ifBlank { "Phone" }, ConnectCrypto.hex(secret), clock() / 1000))
                write(ConnectFrame.Paired(ConnectHandshake.wrap(secret, confirmKey, th), ConnectHandshake.pairedMac(secret, th)))
                return frame.id to ConnectHandshake.channel(shared, secret, th, host = true)
            }
            is PairingVerdict.Wrong -> write(ConnectFrame.Err(ConnectErrors.BAD_CODE, left = verdict.left))
            is PairingVerdict.Locked -> write(ConnectFrame.Err(ConnectErrors.LOCKED, retrySeconds = ((verdict.retryMs + 999) / 1000).toInt()))
            PairingVerdict.Closed -> write(ConnectFrame.Err(ConnectErrors.CLOSED))
        }
        return null
    }

    private fun dispatch(id: String, body: ConnectBody) {
        when (body) {
            ConnectBody.Ping -> sendTo(id, ConnectBody.Pong)
            ConnectBody.Resync -> for (b in runCatching { handler.snapshot() }.getOrDefault(emptyList())) sendTo(id, b)
            // Only what a phone may say reaches the handler: the TV's own kinds of message are never commands.
            is ConnectBody.Command, is ConnectBody.Seek, is ConnectBody.Shuffle, is ConnectBody.Repeat, is ConnectBody.Volume,
            is ConnectBody.Jump, is ConnectBody.Remove, is ConnectBody.Send,
            -> runCatching { handler.onCommand(id, body) }.onFailure { Logger.w(TAG, "a command failed", it) }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "ConnectServer"
        const val MAX_CLIENTS = 4
        const val MAX_PENDING = 3
        const val HANDSHAKE_MS = 10_000
        const val IDLE_MS = 75_000
        private const val BACKLOG = 4
    }
}
