package com.tmplayer.music.connect

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads lines with a hard size cap: a peer that never sends a newline cannot make this hold more
 * than [max] bytes, which `readLine` would. A line past the cap or a stream cut mid line is null.
 * A carriage return is dropped from the line but counts toward the cap, so a stream of them is no
 * way around it.
 *
 * A socket's read timeout is per read, so a peer that sends a byte every few seconds never trips it.
 * [limitTo] sets a time the whole of what is read next (every line until the next call) must arrive
 * in; with a [socket] the read timeout follows what is left of it, and a read past it throws.
 */
internal class BoundedLines(input: InputStream, @Volatile var max: Int = ConnectLimits.MAX_LINE, private val socket: Socket? = null) {
    private val input = BufferedInputStream(input, 4096)
    private val buffer = java.io.ByteArrayOutputStream()
    private var deadline = 0L

    /** From now, every read must finish within [ms] (0 lifts the limit). */
    fun limitTo(ms: Long) {
        deadline = if (ms > 0) System.nanoTime() + ms * 1_000_000 else 0L
    }

    private fun leftMs(): Long = (deadline - System.nanoTime()) / 1_000_000

    /** The next line without its newline, or null at the end of the stream; throws on a line past the cap, past the deadline or a socket error. */
    fun next(): String? {
        buffer.reset()
        var raw = 0
        while (true) {
            if (deadline != 0L) {
                val left = leftMs()
                if (left <= 0) throw IOException("too slow")
                runCatching { socket?.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            }
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buffer.toString(Charsets.UTF_8.name())
            if (++raw > max) throw IOException("line too long")
            if (b == '\r'.code) continue
            buffer.write(b)
        }
    }
}

/**
 * A trusted connection after its handshake: a reader thread that hands each opened body to
 * [onBody], and a writer thread fed by a bounded queue, so a slow or stuck peer can block neither
 * the caller of [send] nor the other peers. Both threads sleep in a blocking call, so an idle
 * connection costs no CPU. [pingEveryMs] makes the writer send a [ConnectBody.Ping] after that long
 * with nothing to say (the phone's way of showing it is alive); 0 for none.
 */
internal class ConnectWire(
    private val socket: Socket,
    private val lines: BoundedLines,
    private val output: OutputStream,
    private val channel: SecureChannel,
    private val pingEveryMs: Long,
    private val onBody: (ConnectBody) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val outbox = LinkedBlockingQueue<ConnectBody>(QUEUE_CAP)

    private val shut = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed: Boolean get() = shut.get()

    fun start(name: String) {
        Thread({ readLoop() }, "$name-read").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "$name-write").apply { isDaemon = true }.start()
    }

    /** Queues [body]; false when the connection is closed or the peer is too far behind (it is then dropped). */
    fun send(body: ConnectBody): Boolean {
        if (closed) return false
        if (outbox.offer(body)) return true
        close()
        return false
    }

    val isOpen: Boolean get() = !closed

    fun close() {
        if (!shut.compareAndSet(false, true)) return
        runCatching { socket.close() }
        outbox.clear()
        // Wakes the writer so its thread ends.
        outbox.offer(ConnectBody.Pong)
        onClosed()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val line = lines.next() ?: break
                val frame = ConnectFrames.parse(line) as? ConnectFrame.Secured ?: break
                val body = channel.open(frame) ?: break
                onBody(body)
            }
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        } finally {
            close()
        }
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val body = (if (pingEveryMs > 0) outbox.poll(pingEveryMs, TimeUnit.MILLISECONDS) ?: ConnectBody.Ping else outbox.take())
                if (closed) break
                val line = ConnectFrames.encode(channel.seal(body)) + "\n"
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            close()
        }
    }

    private companion object {
        const val QUEUE_CAP = 64
    }
}
