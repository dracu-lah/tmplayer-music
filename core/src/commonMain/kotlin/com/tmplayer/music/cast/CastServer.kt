// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/cast/CastServer.kt, cut down to
// ranged files (audio and cover pictures) with a bound on open connections.
package com.tmplayer.music.cast

import com.tmplayer.music.platform.Logger
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bytes of one file as the cast server hands them out: its whole length and a reader opened at
 * any byte. On Android this is wrapped around the player's own streaming path (`TdDataSource`, so
 * TDLib's byte window and a track kept offline), never the whole file in memory. Reads block, the
 * way a socket write does: the server runs each request on a thread of its own.
 */
interface CastSource {

    /** The whole file's length in bytes; may block the first time (a lookup), and fails with an [IOException]. */
    val length: Long

    /** A reader positioned at [position]; it ends at the end of the file. */
    fun open(position: Long): CastReader
}

/** One open read of a [CastSource], from where it was opened to the end of the file. */
interface CastReader : Closeable {

    /** Up to [length] bytes into [buffer], how many were read, or -1 at the end of the file. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** What the server serves: a ranged file with the Content-Type it is sent with (asked when a request comes in, off the main thread). */
class CastResource(val source: CastSource, val mime: () -> String)

/**
 * The small web server a Chromecast or Google Cast device fetches the audio and the cover pictures from.
 *
 * A receiver plays a URL, so the phone has to be one: this listens on the phone's own Wi-Fi address
 * (never every interface), under a path that starts with a token made for this cast alone, and only
 * while something is cast; [stop] closes it and forgets everything it served. A request without the
 * token, with an unknown name, or from an address outside the private ranges gets a 404 or a 403.
 * `Range` is answered with a 206, so a receiver can read the index of a file and seek anywhere.
 */
class CastServer(
    private val bind: InetAddress,
    private val port: Int = 0,
    /** Who may ask; the private and link local ranges by default. Tests on loopback pass their own. */
    private val allowed: (InetAddress) -> Boolean = LanAddress::isLocalNetwork,
    private val maxConnections: Int = MAX_CONNECTIONS,
) {

    /** This cast's secret: the first segment of every path. */
    val token: String = CastUrls.newToken()

    private val resources = ConcurrentHashMap<String, CastResource>()
    private val connections = CopyOnWriteArrayList<Socket>()
    private val streams = ConcurrentHashMap<String, CopyOnWriteArrayList<Socket>>()
    private val threadCount = AtomicInteger()

    @Volatile
    private var server: ServerSocket? = null

    /** Requests answered so far, for the tests and the log. */
    val served = AtomicInteger()

    val running: Boolean get() = server?.isClosed == false

    /** The port it ended up on, once [start]ed. */
    val boundPort: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind, port), BACKLOG)
        server = socket
        Thread({ acceptLoop(socket) }, "cast-server").apply {
            isDaemon = true
            start()
        }
        Logger.i(TAG, "Serving on port ${socket.localPort}")
    }

    /** Closes the listener and every open connection; the resources are forgotten. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        streams.clear()
        resources.clear()
    }

    /** Makes [resource] available as [name] and returns the URL a receiver opens it at. The same name again replaces it. */
    fun publish(name: String, resource: CastResource): String {
        resources[name] = resource
        streams.remove(name)?.forEach { runCatching { it.close() } }
        return urlFor(name)
    }

    fun urlFor(name: String): String = CastUrls.urlFor(bind.hostAddress.orEmpty(), boundPort, token, name)

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) Logger.w(TAG, "Accept failed", e)
                break
            }
            if (!allowed(client.inetAddress) || connections.size >= maxConnections) {
                refuse(client)
                continue
            }
            connections += client
            Thread({ handle(client) }, "cast-request-${threadCount.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Answers a stranger (or a client past the connection bound) with a 403 and closes, on a
     * thread of its own so a slow peer cannot hold up the accept loop.
     */
    private fun refuse(client: Socket) {
        Thread({
            try {
                client.soTimeout = REFUSE_READ_MS
                client.getOutputStream().apply {
                    write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                client.shutdownOutput()
                runCatching { client.getInputStream().read(ByteArray(2048)) }
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
            }
        }, "cast-refuse").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream().buffered(SEND_BUFFER)
            // Keep-alive, so a receiver's run of small range reads does not pay a connect each.
            while (!socket.isClosed) {
                val request = HttpRequest.read(input) ?: break
                served.incrementAndGet()
                val keepOpen = respond(socket, request, output)
                output.flush()
                if (!keepOpen || request.wantsClose) break
            }
        } catch (e: SocketException) {
            // The receiver hung up, or stop() closed it: both ordinary.
        } catch (e: IOException) {
            Logger.i(TAG, "Request ended: ${e.message}")
        } catch (e: Exception) {
            Logger.w(TAG, "Request failed", e)
        } finally {
            connections -= socket
            streams.values.forEach { it.remove(socket) }
            runCatching { socket.close() }
        }
    }

    /** Answers one request; false when the connection has to close after it. */
    private fun respond(socket: Socket, request: HttpRequest, out: OutputStream): Boolean {
        if (request.method == "OPTIONS") {
            writeHead(out, 204, "No Content", emptyMap(), 0)
            return true
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD, OPTIONS"), 0)
            return false
        }
        val name = CastUrls.nameIn(request.path, token) ?: return notFound(out)
        val resource = resources[name] ?: return notFound(out)
        return serveRanged(socket, name, resource, request, out, request.method == "HEAD")
    }

    private fun serveRanged(socket: Socket, name: String, resource: CastResource, request: HttpRequest, out: OutputStream, head: Boolean): Boolean {
        val total = try {
            resource.source.length
        } catch (e: IOException) {
            Logger.i(TAG, "Length not known: ${e.message}")
            writeHead(out, 502, "Bad Gateway", emptyMap(), 0)
            return false
        }
        val range = ByteRange.parse(request.header("range"), total)
        if (range == ByteRange.Unsatisfiable) {
            writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"), 0)
            return true
        }
        val span = range as? ByteRange.Span
        val start = span?.start ?: 0L
        val end = span?.endInclusive ?: (total - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = resource.mime()
        headers["Accept-Ranges"] = "bytes"
        if (span != null) headers["Content-Range"] = "bytes $start-$end/$total"
        if (head || length == 0L) {
            writeHead(out, if (span != null) 206 else 200, if (span != null) "Partial Content" else "OK", headers, length)
            return true
        }
        // The first bytes are read before the head is written: a source that cannot open is a clean 502, not a half answer.
        val reader = try {
            resource.source.open(start)
        } catch (e: IOException) {
            Logger.i(TAG, "Could not open: ${e.message}")
            writeHead(out, 502, "Bad Gateway", emptyMap(), 0)
            return false
        }
        writeHead(out, if (span != null) 206 else 200, if (span != null) "Partial Content" else "OK", headers, length)
        track(name, socket)
        reader.use {
            val buffer = ByteArray(COPY_BUFFER)
            var left = length
            while (left > 0) {
                val n = it.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("Source ended $left bytes early")
                out.write(buffer, 0, n)
                left -= n
            }
        }
        untrack(name, socket)
        return true
    }

    /**
     * Notes [socket] as one reading [name]. A receiver that seeks opens a new request and leaves
     * the old one hanging; past [MAX_STREAMS] the oldest is closed, so two reads of one Telegram
     * file do not pull its download in three directions.
     */
    private fun track(name: String, socket: Socket) {
        val list = streams.getOrPut(name) { CopyOnWriteArrayList() }
        list.remove(socket)
        list += socket
        while (list.size > MAX_STREAMS) runCatching { list.removeAt(0).close() }
    }

    private fun untrack(name: String, socket: Socket) {
        streams[name]?.remove(socket)
    }

    private fun notFound(out: OutputStream): Boolean {
        writeHead(out, 404, "Not Found", emptyMap(), 0)
        return true
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, length: Long?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (length != null) sb.append("Content-Length: ").append(length).append("\r\n")
        // A receiver's player fetches by script, so it asks with CORS.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Range, Content-Type, Accept-Encoding\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        private const val TAG = "CastServer"
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 120_000
        private const val COPY_BUFFER = 64 * 1024
        private const val SEND_BUFFER = 64 * 1024
        private const val REFUSE_READ_MS = 300

        /** Open connections at once; a receiver needs a few (audio, cover, a seek). */
        const val MAX_CONNECTIONS = 12

        /** Reads of one resource at once, beyond which the oldest is closed. */
        const val MAX_STREAMS = 3
    }
}

/** The parts of an HTTP/1.1 request the server reads. */
internal class HttpRequest(
    val method: String,
    val path: String,
    private val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val wantsClose: Boolean get() = header("connection")?.equals("close", ignoreCase = true) == true

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100

        /** The next request on [input], or null once the other side has closed the connection. Bad input is an [IOException]. */
        fun read(input: InputStream): HttpRequest? {
            var line = readLine(input) ?: return null
            // Tolerate a stray blank line between keep-alive requests.
            if (line.isEmpty()) line = readLine(input) ?: return null
            val parts = line.split(' ')
            if (parts.size < 2) throw IOException("Bad request line")
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            var count = 0
            while (true) {
                val h = readLine(input) ?: throw IOException("Headers cut off")
                if (h.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("Too many headers")
                val colon = h.indexOf(':')
                if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
            }
            // The query is never used: the path alone names a resource.
            return HttpRequest(method, target.substringBefore('?'), headers)
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > MAX_LINE) throw IOException("Line too long")
            }
            return sb.toString()
        }
    }
}
