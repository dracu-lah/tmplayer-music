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
 * Limits: [MAX_CLIENTS] trusted phones at once, [MAX_PENDING] connections still in the handshake
 * ([MAX_PENDING_PER_IP] from one address), each given [HANDSHAKE_MS] in all (not per read) to
 * authenticate and [HANDSHAKE_LINE_MAX] bytes a line; a trusted connection that says nothing for
 * [IDLE_MS] is dropped (the phone pings between).
 *
 * A phone that pairs with the typed code is not stored until both screens agree on a short string
 * ([SasRequest], see [ConnectHandshake]): the TV's user answers on [sasRequest].
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
    private val handshakeMs: Int = HANDSHAKE_MS,
) {
    private class Peer(val id: String, val wire: ConnectWire)

    private val peers = CopyOnWriteArrayList<Peer>()
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingByIp = HashMap<String, Int>()
    private val _clients = MutableStateFlow(0)
    private val clientsLock = Any()
    private val _sas = MutableStateFlow<SasRequest?>(null)

    /** How many trusted phones are connected now. */
    val clients: StateFlow<Int> = _clients.asStateFlow()

    /** A phone that gave the right code waits for both screens to show the same string: the TV's screen asks its user. Null when none. */
    val sasRequest: StateFlow<SasRequest?> = _sas.asStateFlow()

    /** Publishes the count of trusted phones. Written from several threads, so under a lock, from the list as it is inside it. */
    private fun publishClients() {
        synchronized(clientsLock) { _clients.value = peers.size }
    }

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
        publishClients()
        // A pairing waiting for the TV's user ends with the server.
        _sas.value?.let { runCatching { it.abort() } }
    }

    /** Sends [body] to every trusted phone. */
    fun broadcast(body: ConnectBody) {
        for (peer in peers) peer.wire.send(body)
    }

    /** Sends [body] to the phone [id] only. */
    fun sendTo(id: String, body: ConnectBody) {
        for (peer in peers) if (peer.id == id) peer.wire.send(body)
    }

    /**
     * Forgets [id]: its pairing is removed and its connections closed. A phone that proved itself a
     * moment ago and is not in the list yet is caught by the check [handshake] makes after it joins.
     */
    fun forget(id: String) {
        phones.forget(id)
        for (peer in peers) if (peer.id == id) peer.wire.close()
    }

    private fun enterAddress(ip: String): Boolean = synchronized(pendingByIp) {
        val n = pendingByIp[ip] ?: 0
        if (n >= MAX_PENDING_PER_IP) {
            false
        } else {
            pendingByIp[ip] = n + 1
            true
        }
    }

    private fun leaveAddress(ip: String) = synchronized(pendingByIp) {
        val n = (pendingByIp[ip] ?: 0) - 1
        if (n <= 0) pendingByIp.remove(ip) else pendingByIp[ip] = n
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            try {
                val ip = client.inetAddress?.hostAddress.orEmpty()
                if (!allowed(client.inetAddress) || pending.get() >= MAX_PENDING || peers.size >= MAX_CLIENTS || !enterAddress(ip)) {
                    // A refusal costs nothing: no thread, no work.
                    runCatching { client.close() }
                    continue
                }
                pending.incrementAndGet()
                try {
                    Thread({ handshake(client, ip) }, "connect-hello").apply { isDaemon = true }.start()
                } catch (e: Throwable) {
                    pending.decrementAndGet()
                    leaveAddress(ip)
                    throw e
                }
            } catch (e: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handshake(socket: Socket, ip: String) {
        var counted = true
        var handedOver = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = handshakeMs
            // The whole authentication gets HANDSHAKE_MS, not each read: a peer that dribbles a byte at a time is cut off.
            val lines = BoundedLines(socket.getInputStream(), HANDSHAKE_LINE_MAX, socket)
            lines.limitTo(handshakeMs.toLong())
            val out = socket.getOutputStream()
            fun write(frame: ConnectFrame) {
                val bytes = (ConnectFrames.encode(frame) + "\n").toByteArray(Charsets.UTF_8)
                // The TV's own user declining writes from another thread while this one waits to read: never two at once.
                synchronized(out) {
                    out.write(bytes)
                    out.flush()
                }
            }
            val sn = ConnectHandshake.newNonce()
            // While a code is on show the TV answers with that offer's key, so the QR code can name it; else a new key pair for every connection.
            val key = gate.current()?.key ?: EphemeralKey.generate()
            write(ConnectFrame.Hello(ConnectLimits.VERSION, sn, hostId, hostName, key.publicHex))
            val first = ConnectFrames.parse(lines.next() ?: return)
            val (id, channel) = when (first) {
                is ConnectFrame.Auth -> authenticate(first, sn, key, ::write) ?: return
                is ConnectFrame.Pair -> pair(first, sn, key, ::write, lines, socket) ?: return
                else -> {
                    write(ConnectFrame.Err(ConnectErrors.MALFORMED))
                    return
                }
            }
            lines.limitTo(0)
            lines.max = ConnectLimits.MAX_LINE
            socket.soTimeout = IDLE_MS
            pending.decrementAndGet()
            leaveAddress(ip)
            counted = false
            lateinit var self: Peer
            val w = ConnectWire(socket, lines, out, channel, pingEveryMs = 0, onBody = { body -> dispatch(self.id, body) }, onClosed = {
                peers.remove(self)
                publishClients()
            })
            self = Peer(id, w)
            if (server == null) return
            // A phone that connects again replaces its earlier connection (which may be half dead).
            for (old in peers) if (old.id == id) old.wire.close()
            peers.add(self)
            handedOver = true
            // forget() and stop() run on other threads: whichever came before the add did not see this phone, so look again now.
            if (server == null || phones.find(id) == null) {
                w.close()
                return
            }
            publishClients()
            for (body in runCatching { handler.snapshot() }.getOrDefault(emptyList())) w.send(body)
            w.start("connect-peer")
        } catch (_: IOException) {
        } catch (e: RuntimeException) {
            Logger.w(TAG, "a connection ended on an error", e)
        } finally {
            if (counted) {
                pending.decrementAndGet()
                leaveAddress(ip)
            }
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
     * exchange. Wrong tries are counted for the whole TV, so each attempt is one guess. A typed code is
     * then not enough on its own (nobody can tell who typed it): both screens must show the same
     * string and both users say so ([confirmOnBothScreens]) before anything is stored.
     */
    private fun pair(
        frame: ConnectFrame.Pair, sn: String, key: EphemeralKey, write: (ConnectFrame) -> Unit, lines: BoundedLines, socket: Socket,
    ): Pair<String, SecureChannel>? {
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
        val withString = frame.via == ConnectHandshake.Via.Code
        // A typed code comes with the phone's commitment for the string; without it nothing could be checked on both screens.
        if (withString && frame.commit.length != 2 * 32) {
            write(ConnectFrame.Err(ConnectErrors.MALFORMED))
            return null
        }
        if (withString && _sas.value != null) {
            // One pairing at a time waits for the TV's user: not a guess either.
            write(ConnectFrame.Err(ConnectErrors.BUSY))
            return null
        }
        val th = ConnectHandshake.transcript(frame.via.wire, frame.version, sn, frame.nonce, hostId, key.publicHex, frame.id, frame.name, frame.key, frame.commit)
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
                if (withString) {
                    if (!confirmOnBothScreens(frame, shared, th, confirmKey, write, lines, socket)) return null
                } else {
                    gate.complete()
                }
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

    /**
     * The right code is in. The TV sends its random value, the phone reveals the one it committed to,
     * and each side derives the same string only if nobody sits between them. The phone's user and the
     * TV's user (through [sasRequest]) must both say the two screens match, within [SAS_MS]. Anything
     * else (a no, a wrong commitment, silence, a dropped line) ends the attempt and counts as a wrong
     * try on the gate. True only when both said yes.
     */
    private fun confirmOnBothScreens(
        frame: ConnectFrame.Pair, shared: ByteArray, th: ByteArray, confirmKey: ByteArray, write: (ConnectFrame) -> Unit,
        lines: BoundedLines, socket: Socket,
    ): Boolean {
        var request: SasRequest? = null
        var confirmed = false
        try {
            val endsAt = System.nanoTime() + SAS_MS * 1_000_000L
            lines.limitTo(SAS_MS.toLong())
            val tvReveal = ConnectHandshake.newReveal()
            write(ConnectFrame.SasInit(tvReveal, ConnectHandshake.sasInitMac(confirmKey, th, tvReveal)))
            val phoneReveal = (ConnectFrames.parse(lines.next() ?: return false) as? ConnectFrame.SasReveal)?.reveal ?: return false
            if (!ConnectCrypto.same(ConnectHandshake.commit(phoneReveal), frame.commit)) return false
            val sas = ConnectHandshake.sas(shared, th, tvReveal, phoneReveal)
            val shown = SasRequest(
                sas, frame.name.ifBlank { "Phone" }, frame.id,
                // The TV's user said no: the phone is told at once; the connection ends when this thread has read the phone's own answer.
                onDecline = { runCatching { write(ConnectFrame.Err(ConnectErrors.DECLINED)) } },
                onAbort = { runCatching { socket.close() } },
            )
            if (!_sas.compareAndSet(null, shown)) {
                runCatching { write(ConnectFrame.Err(ConnectErrors.BUSY)) }
                return false
            }
            request = shown
            val answer = ConnectFrames.parse(lines.next() ?: return false) as? ConnectFrame.Confirm ?: return false
            if (!ConnectCrypto.same(answer.mac, ConnectHandshake.decisionMac(confirmKey, th, sas, answer.ok))) return false
            if (!answer.ok) return false
            val leftMs = ((endsAt - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val tvSaidYes = try {
                shown.answer.get(leftMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                false
            } catch (_: InterruptedException) {
                false
            } catch (_: java.util.concurrent.ExecutionException) {
                false
            }
            if (!tvSaidYes) {
                // Said no: the phone knows already. Ran out of time: tell it now.
                if (!shown.declined) runCatching { write(ConnectFrame.Err(ConnectErrors.DECLINED)) }
                return false
            }
            confirmed = true
            return true
        } finally {
            request?.let { _sas.compareAndSet(it, null) }
            if (confirmed) gate.complete() else gate.reject()
        }
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
        const val MAX_PENDING_PER_IP = 2

        /** The whole time a new connection has to authenticate, not per read. */
        const val HANDSHAKE_MS = 10_000

        /** A handshake line (names are cut to 60 characters, a key is 130): far under a trusted line's cap. */
        const val HANDSHAKE_LINE_MAX = 2 * 1024

        /** How long both users have to answer "do the two screens show the same string?". */
        const val SAS_MS = 60_000
        const val IDLE_MS = 75_000
        private const val BACKLOG = 4
    }
}

/**
 * A pairing waiting for the TV's user: [sas] is what this TV derived, to show beside the question
 * "does [phoneName] show this?". [accept] or [decline] answers it once; declining (or running out of
 * time) tells the phone and ends the attempt, which counts as a wrong try.
 */
class SasRequest internal constructor(
    val sas: String,
    val phoneName: String,
    val phoneId: String,
    private val onDecline: () -> Unit,
    private val onAbort: () -> Unit,
) {
    internal val answer = java.util.concurrent.CompletableFuture<Boolean>()

    @Volatile
    internal var declined = false

    /** The TV's user says the phone shows the same string. */
    fun accept() {
        answer.complete(true)
    }

    /** The TV's user says it does not (or never mind). */
    fun decline() {
        if (answer.isDone) return
        synchronized(this) {
            if (declined) return
            declined = true
        }
        // Called from a screen's thread, which may not touch the network: a short thread tells the phone, and only
        // then lets the waiting one go on (and end the connection).
        Thread({
            onDecline()
            answer.complete(false)
        }, "connect-decline").apply { isDaemon = true }.start()
    }

    /** The server stops (or the dialog is gone for good): the attempt ends now and the connection is closed. */
    internal fun abort() {
        answer.complete(false)
        onAbort()
    }
}
