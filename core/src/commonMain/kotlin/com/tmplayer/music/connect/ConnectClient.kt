package com.tmplayer.music.connect

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The phone's identity on the wire: its install id and the name the TV lists it under. */
data class ClientIdentity(val id: String, val name: String)

/** How a try to pair ended. */
sealed interface PairResult {
    /** Paired: store [paired] (it holds the new secret, and where the TV was). */
    data class Success(val paired: Paired) : PairResult

    /** The TV refused: [code] is a [ConnectErrors] value; [left] the tries left, [retrySeconds] the lock. */
    data class Refused(val code: String, val left: Int, val retrySeconds: Int) : PairResult

    /** Could not reach the TV, or what answered was not a TV of this protocol. */
    data class Unreachable(val reason: String) : PairResult
}

/** How a try to connect to a paired TV ended. */
sealed interface ConnectResult {
    data class Connected(val connection: RemoteConnection) : ConnectResult

    /** [code] is a [ConnectErrors] value: [ConnectErrors.UNPAIRED] means the TV forgot this phone, [ConnectErrors.DENIED] that the secret no longer fits. */
    data class Refused(val code: String) : ConnectResult
    data class Unreachable(val reason: String) : ConnectResult
}

/** A live, trusted connection to a TV. Messages arrive on [ConnectClient.connect]'s listener; [send] never blocks. */
class RemoteConnection internal constructor(private val wire: ConnectWire, val tvId: String, val tvName: String) {
    fun send(body: ConnectBody): Boolean = wire.send(body)
    val isOpen: Boolean get() = wire.isOpen
    fun close() = wire.close()
}

/**
 * The phone's side of Connect: pairs with a TV (with the code from its screen) and connects to a
 * paired one. Both calls block (up to [CONNECT_MS] to reach the TV and [HANDSHAKE_MS] for its
 * answers), so run them off the main thread. The connection that comes back pings the TV every
 * [PING_MS] while quiet, and ends when the TV is gone.
 */
object ConnectClient {
    const val CONNECT_MS = 4_000
    const val HANDSHAKE_MS = 10_000
    const val PING_MS = 25_000L
    private const val IDLE_MS = 75_000

    /** Pairs with the TV at [host]:[port] using [code] as typed (digits only). */
    fun pair(host: String, port: Int, me: ClientIdentity, code: String): PairResult {
        val socket = Socket()
        try {
            val (lines, out, hello) = open(socket, host, port) ?: return PairResult.Unreachable("not a TV")
            val cn = ConnectHandshake.newNonce()
            val codeKey = ConnectHandshake.codeKey(code.filter { it.isDigit() }, hello.nonce, cn)
            write(out, ConnectFrame.Pair(ConnectLimits.VERSION, me.id, me.name, cn, ConnectHandshake.pairMac(codeKey, hello.nonce, cn, me.id)))
            return when (val answer = ConnectFrames.parse(lines.next() ?: return PairResult.Unreachable("closed"))) {
                is ConnectFrame.Paired -> {
                    val secret = ConnectHandshake.unwrap(answer.wrapped, codeKey, hello.nonce, cn, me.id)
                        ?: return PairResult.Refused(ConnectErrors.DENIED, 0, 0)
                    // The TV must also know the code: its proof is over the secret it made.
                    if (!ConnectCrypto.same(answer.mac, ConnectHandshake.pairedMac(secret, hello.nonce, cn))) return PairResult.Refused(ConnectErrors.DENIED, 0, 0)
                    PairResult.Success(Paired(hello.id, hello.name.ifBlank { host }, ConnectCrypto.hex(secret), System.currentTimeMillis() / 1000, host, port))
                }
                is ConnectFrame.Err -> PairResult.Refused(answer.code, answer.left, answer.retrySeconds)
                else -> PairResult.Unreachable("unexpected answer")
            }
        } catch (e: IOException) {
            return PairResult.Unreachable(e.message.orEmpty())
        } catch (e: RuntimeException) {
            return PairResult.Unreachable(e.message.orEmpty())
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Connects to [tv] (a paired TV) at [host]:[port] and proves the secret both ways. [onBody] gets
     * every message from the TV, [onClosed] is called once when the connection ends. A TV whose id
     * is not [tv]'s (another TV on that address) is refused.
     */
    fun connect(host: String, port: Int, me: ClientIdentity, tv: Paired, onBody: (ConnectBody) -> Unit, onClosed: () -> Unit): ConnectResult {
        val secret = tv.secretBytes ?: return ConnectResult.Refused(ConnectErrors.DENIED)
        val socket = Socket()
        var handedOver = false
        try {
            val (lines, out, hello) = open(socket, host, port) ?: return ConnectResult.Unreachable("not a TV")
            if (hello.id != tv.id) return ConnectResult.Refused(ConnectErrors.DENIED)
            val cn = ConnectHandshake.newNonce()
            write(out, ConnectFrame.Auth(ConnectLimits.VERSION, me.id, me.name, cn, ConnectHandshake.authMac(secret, hello.nonce, cn, me.id)))
            when (val answer = ConnectFrames.parse(lines.next() ?: return ConnectResult.Unreachable("closed"))) {
                is ConnectFrame.Ready -> {
                    if (!ConnectCrypto.same(answer.mac, ConnectHandshake.readyMac(secret, hello.nonce, cn))) return ConnectResult.Refused(ConnectErrors.DENIED)
                }
                is ConnectFrame.Err -> return ConnectResult.Refused(answer.code)
                else -> return ConnectResult.Unreachable("unexpected answer")
            }
            socket.soTimeout = IDLE_MS
            val wire = ConnectWire(
                socket, lines, out, ConnectHandshake.channel(secret, hello.nonce, cn, host = false),
                pingEveryMs = PING_MS, onBody = onBody, onClosed = onClosed,
            )
            wire.start("connect-tv")
            handedOver = true
            return ConnectResult.Connected(RemoteConnection(wire, hello.id, hello.name))
        } catch (e: IOException) {
            return ConnectResult.Unreachable(e.message.orEmpty())
        } catch (e: RuntimeException) {
            return ConnectResult.Unreachable(e.message.orEmpty())
        } finally {
            if (!handedOver) runCatching { socket.close() }
        }
    }

    private data class Opened(val lines: BoundedLines, val out: java.io.OutputStream, val hello: ConnectFrame.Hello)

    /** Connects and reads the host's hello; null when what answered is not a host of this protocol. */
    private fun open(socket: Socket, host: String, port: Int): Opened? {
        // Only the local network: a typed address that is public is never connected to.
        val address = java.net.InetAddress.getByName(host)
        if (!LocalNet.isLocal(address)) return null
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(address, port), CONNECT_MS)
        socket.soTimeout = HANDSHAKE_MS
        val lines = BoundedLines(socket.getInputStream())
        val hello = ConnectFrames.parse(lines.next() ?: return null) as? ConnectFrame.Hello ?: return null
        if (hello.version != ConnectLimits.VERSION) return null
        return Opened(lines, socket.getOutputStream(), hello)
    }

    private fun write(out: java.io.OutputStream, frame: ConnectFrame) {
        out.write((ConnectFrames.encode(frame) + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
