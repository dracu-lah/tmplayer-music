package com.tmplayer.music.connect

import com.tmplayer.music.data.MemoryKeyValues
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real server and client over loopback: pairing, a paired connect, refusals and the limits. */
class ConnectSocketTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val commands = CopyOnWriteArrayList<ConnectBody>()
    private val handler = object : ConnectHandler {
        override fun snapshot(): List<ConnectBody> = listOf(ConnectBody.QueueView(0, 1, 0, listOf(QueueEntry("a", "T", "A", 1))))
        override fun onCommand(from: String, body: ConnectBody) {
            commands += body
        }
    }
    private val phones = PairingStore(MemoryKeyValues(), PairingStore.PHONES_KEY)
    private val gate = PairingGate({ System.currentTimeMillis() })
    private val tvId = ConnectHandshake.newId()
    private val server = ConnectServer(lo, tvId, "Living room", phones, gate, handler, allowed = { true })
    private val me = ClientIdentity(ConnectHandshake.newId(), "Pixel")
    private val port: Int = server.start()

    @After
    fun stop() = server.stop()

    private fun pairNow(): Paired {
        val code = gate.open().code
        val result = ConnectClient.pair("127.0.0.1", port, me, code)
        return (result as PairResult.Success).paired
    }

    private fun connect(tv: Paired, inbox: MutableList<ConnectBody> = CopyOnWriteArrayList(), closed: CountDownLatch = CountDownLatch(1)): ConnectResult =
        ConnectClient.connect("127.0.0.1", port, me, tv, { inbox += it }, { closed.countDown() })

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }

    @Test
    fun aPhoneIsPairedOnceWithTheCodeAndConnectsWithoutIt() {
        val paired = pairNow()
        assertEquals(tvId, paired.id)
        assertEquals("Living room", paired.name)
        assertEquals(paired.secret, phones.find(me.id)?.secret)
        // The code is spent.
        assertNull(gate.current())

        val inbox = CopyOnWriteArrayList<ConnectBody>()
        val connected = connect(paired, inbox) as ConnectResult.Connected
        // The TV tells the new phone the whole picture first.
        await("snapshot") { inbox.any { it is ConnectBody.QueueView } }
        assertEquals(1, server.clients.value)

        connected.connection.send(ConnectBody.Command(Transport.Next))
        connected.connection.send(ConnectBody.Seek(5_000))
        await("commands") { commands.size == 2 }
        assertEquals(listOf(ConnectBody.Command(Transport.Next), ConnectBody.Seek(5_000)), commands.toList())

        // State is pushed on change, to every phone.
        server.broadcast(ConnectBody.Problem("hello"))
        await("push") { inbox.contains(ConnectBody.Problem("hello")) }

        // Ping is answered by the server itself.
        connected.connection.send(ConnectBody.Ping)
        await("pong") { inbox.contains(ConnectBody.Pong) }
        connected.connection.close()
        await("gone") { server.clients.value == 0 }
    }

    @Test
    fun aWrongCodeIsCountedAndFiveLockPairingForAMinute() {
        val code = gate.open().code
        val wrong = if (code == "000000") "111111" else "000000"
        for (left in 4 downTo 0) {
            val r = ConnectClient.pair("127.0.0.1", port, me, wrong)
            if (left > 0) assertEquals(PairResult.Refused(ConnectErrors.BAD_CODE, left, 0), r) else assertTrue((r as PairResult.Refused).code == ConnectErrors.LOCKED)
        }
        // Locked, so even the right code is refused now (and the code on screen was replaced).
        val refused = ConnectClient.pair("127.0.0.1", port, me, gate.current()!!.code) as PairResult.Refused
        assertEquals(ConnectErrors.LOCKED, refused.code)
        assertTrue(refused.retrySeconds in 1..60)
        assertTrue(phones.list.value.isEmpty())
    }

    @Test
    fun pairingIsClosedUntilTheUserOpensIt() {
        val refused = ConnectClient.pair("127.0.0.1", port, me, "123456") as PairResult.Refused
        assertEquals(ConnectErrors.CLOSED, refused.code)
        assertTrue(phones.list.value.isEmpty())
    }

    @Test
    fun anUnpairedPhoneAndAWrongSecretGetNoCommandThrough() {
        val stranger = Paired(tvId, "TV", ConnectCrypto.hex(ConnectHandshake.newSecret()), 0)
        assertEquals(ConnectResult.Refused(ConnectErrors.UNPAIRED), connect(stranger))
        val real = pairNow()
        val wrongSecret = real.copy(secret = ConnectCrypto.hex(ConnectHandshake.newSecret()))
        assertEquals(ConnectResult.Refused(ConnectErrors.DENIED), connect(wrongSecret))
        // Another TV on that address is not accepted either.
        assertEquals(ConnectResult.Refused(ConnectErrors.DENIED), connect(real.copy(id = ConnectHandshake.newId())))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aRawPeerThatSendsACommandBeforePairingIsDropped() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            assertTrue(ConnectFrames.parse(input.readLine()) is ConnectFrame.Hello)
            val line = ConnectFrames.encode(ConnectFrame.Secured(1, ConnectBodies.encode(ConnectBody.Command(Transport.Play)), "ab".repeat(32)))
            socket.getOutputStream().write((line + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertTrue(ConnectFrames.parse(input.readLine() ?: "") is ConnectFrame.Err)
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun garbageAndHugeLinesAreDroppedWithoutHarm() {
        for (payload in listOf("garbage\n", "{\"t\":\"auth\"}\n", "x".repeat(ConnectLimits.MAX_LINE + 10) + "\n", "\u0000\u0001\n")) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val input = socket.getInputStream().bufferedReader()
                input.readLine()
                runCatching {
                    socket.getOutputStream().write(payload.toByteArray())
                    socket.getOutputStream().flush()
                    // The server answers or closes; either way it is not stuck.
                    while (input.readLine() != null) Unit
                }
            }
        }
        // Still serving.
        assertTrue(connect(pairNow()) is ConnectResult.Connected)
    }

    @Test
    fun forgettingAPhoneEndsItsConnectionAndItsPairing() {
        val paired = pairNow()
        val closed = CountDownLatch(1)
        val connected = connect(paired, closed = closed) as ConnectResult.Connected
        await("connected") { server.clients.value == 1 }
        server.forget(me.id)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertFalse(connected.connection.isOpen)
        assertNull(phones.find(me.id))
        assertEquals(ConnectResult.Refused(ConnectErrors.UNPAIRED), connect(paired))
    }

    @Test
    fun stoppingTheServerClosesEveryone() {
        val closed = CountDownLatch(1)
        assertNotNull(connect(pairNow(), closed = closed) as ConnectResult.Connected)
        await("connected") { server.clients.value == 1 }
        server.stop()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertFalse(server.isRunning)
        assertTrue(connect(phones.list.value.first()) is ConnectResult.Unreachable)
    }

    @Test
    fun aPublicAddressIsNeverConnectedTo() {
        assertTrue(ConnectClient.pair("8.8.8.8", 9, me, "123456") is PairResult.Unreachable)
    }

    @Test
    fun aPeerOutsideTheLocalNetworkIsDroppedAtOnce() {
        server.stop()
        val strict = ConnectServer(lo, tvId, "TV", phones, gate, handler, allowed = { false })
        val p = strict.start()
        try {
            Socket("127.0.0.1", p).use { socket ->
                socket.soTimeout = 3_000
                assertNull(runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull())
            }
        } finally {
            strict.stop()
        }
    }
}
