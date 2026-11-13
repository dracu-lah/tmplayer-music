package com.tmplayer.music.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/** The cast server over a real loopback socket, with a source that counts what it was asked for. */
class CastServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val body = ByteArray(10_000) { (it % 251).toByte() }
    private val opened = ArrayList<Long>()
    private var failOpen = false
    private lateinit var server: CastServer

    private val source = object : CastSource {
        override val length: Long get() = body.size.toLong()

        override fun open(position: Long): CastReader {
            if (failOpen) throw IOException("gone")
            synchronized(opened) { opened += position }
            return object : CastReader {
                private var at = position.toInt()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (at >= body.size) return -1
                    val n = minOf(length, body.size - at, 1000)
                    System.arraycopy(body, at, buffer, offset, n)
                    at += n
                    return n
                }

                override fun close() = Unit
            }
        }
    }

    @Before
    fun start() {
        server = CastServer(loopback, allowed = { true })
        server.start()
        server.publish("a1.mp3", CastResource(source) { "audio/mpeg" })
    }

    @After
    fun stop() = server.stop()

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(raw: String, path: String? = null): Answer {
        Socket(loopback, server.boundPort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write((path?.let { raw.replace("{path}", it) } ?: raw).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val bytes = socket.getInputStream().readBytes()
            val split = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(bytes, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val headers = head.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Answer(head[0].split(' ')[1].toInt(), headers, bytes.copyOfRange(split + 4, bytes.size))
        }
    }

    private fun get(name: String, range: String? = null, token: String = server.token, method: String = "GET") =
        request("$method /$token/$name HTTP/1.1\r\nHost: x\r\nConnection: close\r\n${range?.let { "Range: $it\r\n" } ?: ""}\r\n")

    @Test
    fun `the whole file with its type, length and range support`() {
        val a = get("a1.mp3")
        assertEquals(200, a.status)
        assertEquals("audio/mpeg", a.headers["content-type"])
        assertEquals("bytes", a.headers["accept-ranges"])
        assertEquals("10000", a.headers["content-length"])
        assertTrue(body.contentEquals(a.body))
    }

    @Test
    fun `a range is answered with exactly those bytes`() {
        val a = get("a1.mp3", "bytes=2000-2999")
        assertEquals(206, a.status)
        assertEquals("bytes 2000-2999/10000", a.headers["content-range"])
        assertTrue(body.copyOfRange(2000, 3000).contentEquals(a.body))
        assertEquals(2000L, synchronized(opened) { opened.last() })
    }

    @Test
    fun `an open ended range and a suffix`() {
        assertTrue(body.copyOfRange(9000, 10000).contentEquals(get("a1.mp3", "bytes=9000-").body))
        assertTrue(body.copyOfRange(9500, 10000).contentEquals(get("a1.mp3", "bytes=-500").body))
    }

    @Test
    fun `a range past the end is a 416 and a garbage range sends the whole file`() {
        val past = get("a1.mp3", "bytes=20000-")
        assertEquals(416, past.status)
        assertEquals("bytes */10000", past.headers["content-range"])
        val garbage = get("a1.mp3", "bytes=banana")
        assertEquals(200, garbage.status)
        assertEquals(body.size, garbage.body.size)
    }

    @Test
    fun `head sends no body`() {
        val a = get("a1.mp3", method = "HEAD")
        assertEquals(200, a.status)
        assertEquals("10000", a.headers["content-length"])
        assertEquals(0, a.body.size)
    }

    @Test
    fun `no token, a wrong token, an unknown name and a path trick are all 404`() {
        assertEquals(404, get("a1.mp3", token = "nope").status)
        assertEquals(404, request("GET /a1.mp3 HTTP/1.1\r\nConnection: close\r\n\r\n").status)
        assertEquals(404, get("missing.mp3").status)
        assertEquals(404, request("GET /${server.token}/../a1.mp3 HTTP/1.1\r\nConnection: close\r\n\r\n").status)
        assertEquals(404, request("GET /${server.token}/a1.mp3/extra HTTP/1.1\r\nConnection: close\r\n\r\n").status)
    }

    @Test
    fun `other methods are refused`() {
        assertEquals(405, request("POST /${server.token}/a1.mp3 HTTP/1.1\r\nConnection: close\r\n\r\n").status)
    }

    @Test
    fun `a source that cannot open is a 502, not a broken answer`() {
        failOpen = true
        assertEquals(502, get("a1.mp3").status)
    }

    @Test
    fun `garbage on the socket does not stop the server`() {
        for (junk in listOf("\u0000\u0001\u0002\r\n\r\n", "GET\r\n\r\n", "X".repeat(20_000) + "\r\n", "GET / HTTP/1.1\r\n" + "A: b\r\n".repeat(500) + "\r\n")) {
            runCatching {
                Socket(loopback, server.boundPort).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(junk.toByteArray(Charsets.ISO_8859_1))
                    socket.shutdownOutput()
                    runCatching { socket.getInputStream().readBytes() }
                }
            }
        }
        assertEquals(200, get("a1.mp3").status)
    }

    @Test
    fun `a stranger outside the allowed addresses is refused`() {
        server.stop()
        server = CastServer(loopback, allowed = { false })
        server.start()
        server.publish("a1.mp3", CastResource(source) { "audio/mpeg" })
        assertEquals(403, get("a1.mp3").status)
    }

    @Test
    fun `stop closes the port and forgets what was published`() {
        server.publish("a1.mp3", CastResource(source) { "audio/mpeg" })
        server.stop()
        assertFalse(server.running)
        // Not a connect to the old port: another test's server can be handed that port at once.
        // A stopped server holds nothing, so starting it again serves nothing published before.
        server.start()
        assertEquals(404, get("a1.mp3").status)
    }

    @Test
    fun `the published url carries the token and the name`() {
        val url = server.urlFor("a1.mp3")
        assertEquals("http://127.0.0.1:${server.boundPort}/${server.token}/a1.mp3", url)
    }

    @Test
    fun `a large range streams in pieces and never asks for more than it was given`() {
        val out = ByteArrayOutputStream()
        out.write(get("a1.mp3", "bytes=1-9999").body)
        assertEquals(9999, out.size())
    }

    @Test
    fun `only a request with the token and a known name counts as served`() {
        get("a1.mp3", token = "nope")
        get("missing.mp3")
        request("GET /${server.token}/../a1.mp3 HTTP/1.1\r\nConnection: close\r\n\r\n")
        assertEquals(0, server.served.get())
        get("a1.mp3")
        assertEquals(1, server.served.get())
    }

    @Test
    fun `an idle socket is dropped soon and never holds a slot for long`() {
        server.stop()
        server = CastServer(loopback, allowed = { true }, firstRequestTimeoutMs = 300)
        server.start()
        Socket(loopback, server.boundPort).use { idle ->
            idle.soTimeout = 3_000
            // The server closes it: a read ends with -1 (or a reset) well inside the test's own timeout.
            val ended = runCatching { idle.getInputStream().read() }.getOrDefault(-1)
            assertEquals(-1, ended)
        }
    }

    @Test
    fun `one address cannot hold more than its share of connections`() {
        server.stop()
        server = CastServer(loopback, allowed = { true }, maxPerAddress = 2)
        server.start()
        server.publish("a1.mp3", CastResource(source) { "audio/mpeg" })
        val held = (1..2).map { Socket(loopback, server.boundPort) }
        try {
            // Let the accept loop admit both before the next one comes.
            Thread.sleep(200)
            assertEquals(403, get("a1.mp3").status)
        } finally {
            held.forEach { runCatching { it.close() } }
        }
        Thread.sleep(300)
        assertEquals(200, get("a1.mp3").status)
    }
}
