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
        val tv = tvAnswers(true)
        val result = pairTyped(code)
        tv.join(5_000)
        return (result as PairResult.Success).paired
    }

    /** The phone's side with a typed code; [phoneSays] is its user's answer to "does the TV show this?", the strings it was shown go to [shown]. */
    private fun pairTyped(code: String, phoneSays: Boolean = true, shown: MutableList<String>? = null): PairResult =
        ConnectClient.pair("127.0.0.1", port, me, code) { sas, _ ->
            shown?.add(sas)
            phoneSays
        }

    /** The TV's user: answers the next request that appears on the TV's screen (gives up after 8 s), after noting the string in [shown]. */
    private fun tvAnswers(ok: Boolean, shown: MutableList<String>? = null): Thread = Thread {
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end) {
            val request = server.sasRequest.value
            if (request != null) {
                shown?.add(request.sas)
                if (ok) request.accept() else request.decline()
                return@Thread
            }
            Thread.sleep(5)
        }
    }.apply { isDaemon = true; start() }

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
            val r = pairTyped(wrong)
            if (left > 0) assertEquals(PairResult.Refused(ConnectErrors.BAD_CODE, left, 0), r) else assertTrue((r as PairResult.Refused).code == ConnectErrors.LOCKED)
        }
        // Locked, so even the right code is refused now (and the code on screen was replaced).
        val refused = pairTyped(gate.current()!!.code) as PairResult.Refused
        assertEquals(ConnectErrors.LOCKED, refused.code)
        assertTrue(refused.retrySeconds in 1..60)
        assertTrue(phones.list.value.isEmpty())
    }

    @Test
    fun pairingIsClosedUntilTheUserOpensIt() {
        val refused = pairTyped("123456") as PairResult.Refused
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
        assertEquals(ConnectResult.Refused(ConnectErrors.WRONG_TV), connect(real.copy(id = ConnectHandshake.newId())))
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
        assertTrue(ConnectClient.pair("8.8.8.8", 9, me, "123456") { _, _ -> true } is PairResult.Unreachable)
    }

    @Test
    fun aPhoneIsPairedWithTheTokenOfTheQrCodeAndNotWithAWrongOne() {
        val offer = gate.open()
        val link = PairLink("127.0.0.1", port, tvId, offer.token, offer.fingerprint, "Living room")
        val wrong = ConnectClient.pair(link.copy(token = "00".repeat(16)), me) as PairResult.Refused
        assertEquals(ConnectErrors.BAD_CODE, wrong.code)
        // Another TV than the one the QR code named is not paired with.
        assertEquals(ConnectErrors.WRONG_TV, (ConnectClient.pair(link.copy(tvId = ConnectHandshake.newId()), me) as PairResult.Refused).code)
        // A device at that address that answers with another key than the QR code named is not the TV, whatever it knows.
        val other = EphemeralKey.generate()
        assertEquals(ConnectErrors.WRONG_TV, (ConnectClient.pair(link.copy(fingerprint = ConnectHandshake.fingerprint(other.publicHex)), me) as PairResult.Refused).code)
        assertTrue(phones.list.value.isEmpty())
        val paired = (ConnectClient.pair(link, me) as PairResult.Success).paired
        assertEquals(tvId, paired.id)
        assertEquals(paired.secret, phones.find(me.id)?.secret)
        assertNull(gate.current())
        assertTrue(connect(paired) is ConnectResult.Connected)
        // The token is spent with the code, and the TV answers with a new key now: the old QR code names none of them.
        assertEquals(ConnectErrors.WRONG_TV, (ConnectClient.pair(link, me) as PairResult.Refused).code)
    }

    @Test
    fun aRecordedPairFrameIsUselessOnAnotherConnection() {
        val offer = gate.open()
        val recorded = ArrayList<String>()
        // A phone pairs by hand with the QR token (speaking the protocol itself), and what it sent is kept.
        val key = EphemeralKey.generate()
        val cn = ConnectHandshake.newNonce()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            val hello = ConnectFrames.parse(input.readLine()) as ConnectFrame.Hello
            assertEquals(ConnectLimits.VERSION, hello.version)
            val th = ConnectHandshake.transcript("t", hello.version, hello.nonce, cn, hello.id, hello.key, me.id, me.name, key.publicHex)
            val confirm = ConnectHandshake.confirmKey(key.agree(hello.key)!!, th, ConnectHandshake.Via.Token, offer.token)
            val frame = ConnectFrame.Pair(hello.version, me.id, me.name, cn, key.publicHex, ConnectHandshake.Via.Token, ConnectHandshake.pairMac(confirm, th))
            recorded += ConnectFrames.encode(frame)
            socket.getOutputStream().write((recorded.last() + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertTrue(ConnectFrames.parse(input.readLine()) is ConnectFrame.Paired)
        }
        assertNotNull(phones.find(me.id))
        phones.forget(me.id)
        // The attacker plays the recording at a new connection with a fresh offer on show: refused, and it cost a try.
        gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            socket.getOutputStream().write((recorded.first() + "\n").toByteArray())
            socket.getOutputStream().flush()
            val answer = ConnectFrames.parse(input.readLine() ?: "") as ConnectFrame.Err
            assertEquals(ConnectErrors.BAD_CODE, answer.code)
            assertEquals(4, answer.left)
        }
        assertNull(phones.find(me.id))
    }

    @Test
    fun aTypedCodeIsOnlyStoredWhenBothUsersSayTheScreensMatch() {
        val phoneSaw = CopyOnWriteArrayList<String>()
        val tvSaw = CopyOnWriteArrayList<String>()
        val code = gate.open().code
        val tv = tvAnswers(true, tvSaw)
        val result = pairTyped(code, phoneSays = true, shown = phoneSaw)
        tv.join(5_000)
        assertTrue(result is PairResult.Success)
        // Both screens showed the same 6 digits (as 123 456).
        assertEquals(1, phoneSaw.size)
        assertEquals(phoneSaw, tvSaw)
        assertTrue(Regex("\\d{3} \\d{3}").matches(phoneSaw.single()))
        assertNotNull(phones.find(me.id))
    }

    @Test
    fun aNoOnThePhoneEndsTheAttemptAndCountsAsAFailedTry() {
        val code = gate.open().code
        val tv = tvAnswers(true)
        val result = pairTyped(code, phoneSays = false)
        tv.join(5_000)
        assertEquals(PairResult.Refused(ConnectErrors.DECLINED, 0, 0), result)
        assertNull(phones.find(me.id))
        await("request cleared") { server.sasRequest.value == null }
        // The code was spent; a new one is on show only when the user opens it again, and four more failures lock the TV (five in all).
        repeat(3) {
            val c = gate.open().code
            val t = tvAnswers(true)
            assertEquals(ConnectErrors.DECLINED, (pairTyped(c, phoneSays = false) as PairResult.Refused).code)
            t.join(5_000)
            await("request cleared") { server.sasRequest.value == null }
        }
        // Four failures so far: the fifth is the last one allowed to count, and the one after is locked out.
        val c = gate.open().code
        val t = tvAnswers(true)
        assertEquals(ConnectErrors.DECLINED, (pairTyped(c, phoneSays = false) as PairResult.Refused).code)
        t.join(5_000)
        await("request cleared") { server.sasRequest.value == null }
        gate.open()
        assertEquals(ConnectErrors.LOCKED, (pairTyped(gate.current()!!.code) as PairResult.Refused).code)
        assertTrue(phones.list.value.isEmpty())
    }

    @Test
    fun aNoOnTheTvEndsTheAttemptAndTheSamePhoneIsToldSo() {
        val code = gate.open().code
        val tv = tvAnswers(false)
        val result = pairTyped(code, phoneSays = true)
        tv.join(5_000)
        assertEquals(ConnectErrors.DECLINED, (result as? PairResult.Refused)?.code ?: result.toString())
        assertNull(phones.find(me.id))
        assertNull(gate.current())
    }

    @Test
    fun aTvUserWhoNeverAnswersEndsTheAttemptWhenTheServerStops() {
        val code = gate.open().code
        val done = CountDownLatch(1)
        var result: PairResult? = null
        Thread {
            result = pairTyped(code)
            done.countDown()
        }.apply { isDaemon = true; start() }
        await("the TV asks its user") { server.sasRequest.value != null }
        server.stop()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result !is PairResult.Success)
        assertTrue(phones.list.value.isEmpty())
    }

    @Test
    fun aPairFrameOfATypedCodeWithoutACommitmentIsRefused() {
        gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            val hello = ConnectFrames.parse(input.readLine()) as ConnectFrame.Hello
            val frame = ConnectFrame.Pair(hello.version, me.id, me.name, "bb".repeat(8), EphemeralKey.generate().publicHex, ConnectHandshake.Via.Code, "cc".repeat(32))
            socket.getOutputStream().write((ConnectFrames.encode(frame) + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertEquals(ConnectErrors.MALFORMED, (ConnectFrames.parse(input.readLine() ?: "") as ConnectFrame.Err).code)
        }
    }

    @Test
    fun aBadRevealOrADecisionWithAWrongMacEndsTheAttemptAndCountsATry() {
        // A phone that skips the commitment (reveals a value it did not commit to) is refused, and it costs a try.
        val offer = gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            val hello = ConnectFrames.parse(input.readLine()) as ConnectFrame.Hello
            val key = EphemeralKey.generate()
            val cn = ConnectHandshake.newNonce()
            val committed = ConnectHandshake.newReveal()
            val commit = ConnectHandshake.commit(committed)
            val th = ConnectHandshake.transcript("c", hello.version, hello.nonce, cn, hello.id, hello.key, me.id, me.name, key.publicHex, commit)
            val confirm = ConnectHandshake.confirmKey(key.agree(hello.key)!!, th, ConnectHandshake.Via.Code, offer.code)
            out.write((ConnectFrames.encode(ConnectFrame.Pair(hello.version, me.id, me.name, cn, key.publicHex, ConnectHandshake.Via.Code, ConnectHandshake.pairMac(confirm, th), commit)) + "\n").toByteArray())
            out.flush()
            assertTrue(ConnectFrames.parse(input.readLine()) is ConnectFrame.SasInit)
            out.write((ConnectFrames.encode(ConnectFrame.SasReveal(ConnectHandshake.newReveal())) + "\n").toByteArray())
            out.flush()
            // No request ever reached the TV's screen; the connection ends.
            assertNull(input.readLine())
        }
        assertNull(server.sasRequest.value)
        assertNull(phones.find(me.id))
        // One try spent in all (the code was right, the pairing was not).
        val wrong = pairTyped(if (gate.open().code == "000000") "111111" else "000000") as PairResult.Refused
        assertEquals(3, wrong.left)
    }

    @Test
    fun aSlowPeerIsCutOffAtTheTotalDeadlineNotPerRead() {
        server.stop()
        val strict = ConnectServer(lo, tvId, "TV", phones, gate, handler, allowed = { true }, handshakeMs = 600)
        val p = strict.start()
        try {
            Socket("127.0.0.1", p).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader()
                input.readLine()
                val started = System.currentTimeMillis()
                // A byte every 150 ms never trips a per-read timeout of 600 ms; the whole handshake must still end by about 600 ms.
                var ended = false
                for (i in 0 until 40) {
                    try {
                        socket.getOutputStream().write('x'.code)
                        socket.getOutputStream().flush()
                    } catch (_: java.io.IOException) {
                        ended = true
                        break
                    }
                    Thread.sleep(150)
                    if (System.currentTimeMillis() - started > 3_000) break
                }
                val took = System.currentTimeMillis() - started
                assertTrue("closed after ${took} ms", ended || runCatching { input.read() }.getOrDefault(-1) == -1)
                assertTrue("took $took ms", took < 3_000)
            }
        } finally {
            strict.stop()
        }
    }

    @Test
    fun carriageReturnsCountTowardTheLineLimit() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            runCatching {
                socket.getOutputStream().write("\r".repeat(ConnectServer.HANDSHAKE_LINE_MAX + 10).toByteArray())
                socket.getOutputStream().flush()
            }
            // The server hangs up (nothing more to read) instead of swallowing returns for ever.
            assertNull(runCatching { input.readLine() }.getOrNull())
        }
    }

    @Test
    fun oneAddressCannotHoldMoreThanTwoPendingConnections() {
        val sockets = (0 until 3).map { Socket("127.0.0.1", port).apply { soTimeout = 3_000 } }
        try {
            val results = sockets.map { runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull() }
            assertEquals(2, results.count { it != null && ConnectFrames.parse(it) is ConnectFrame.Hello })
            assertEquals(1, results.count { it == null })
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
        // The slots come back once those end.
        await("slots free") { runCatching { Socket("127.0.0.1", port).use { s -> s.soTimeout = 1_000; s.getInputStream().bufferedReader().readLine() }.let { it != null } }.getOrDefault(false) }
    }

    @Test
    fun anOldPhoneOfVersionTwoIsToldItsVersionDoesNotFit() {
        gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            val old = """{"t":"pair","v":2,"id":"${me.id}","name":"Pixel","cn":"${"bb".repeat(8)}","ek":"${EphemeralKey.generate().publicHex}","via":"c","m":"${"cc".repeat(32)}"}"""
            socket.getOutputStream().write((old + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertEquals(ConnectErrors.VERSION, (ConnectFrames.parse(input.readLine() ?: "") as ConnectFrame.Err).code)
        }
    }

    @Test
    fun theTvAnswersWithTheOffersKeyWhileACodeIsOnShowSoTheQrCodeCanNameIt() {
        val offer = gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val hello = ConnectFrames.parse(socket.getInputStream().bufferedReader().readLine()) as ConnectFrame.Hello
            assertEquals(offer.fingerprint, ConnectHandshake.fingerprint(hello.key))
        }
        gate.close()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val hello = ConnectFrames.parse(socket.getInputStream().bufferedReader().readLine()) as ConnectFrame.Hello
            assertFalse(offer.fingerprint == ConnectHandshake.fingerprint(hello.key))
        }
    }

    @Test
    fun anotherDeviceAtTheSavedAddressIsNotAForgottenPhone() {
        val paired = pairNow()
        val stranger = paired.copy(id = ConnectHandshake.newId())
        assertEquals(ConnectResult.Refused(ConnectErrors.WRONG_TV), connect(stranger))
    }

    @Test
    fun aTamperedKeyIsRefusedWithoutCountingATry() {
        gate.open()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            val hello = ConnectFrames.parse(input.readLine()) as ConnectFrame.Hello
            // A well formed key that is not on the curve (the host's own key with its last digit changed).
            val bad = hello.key.dropLast(1) + (if (hello.key.last() == '0') "1" else "0")
            val frame = ConnectFrame.Pair(hello.version, me.id, me.name, "bb".repeat(8), bad, ConnectHandshake.Via.Code, "cc".repeat(32))
            socket.getOutputStream().write((ConnectFrames.encode(frame) + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertEquals(ConnectErrors.MALFORMED, (ConnectFrames.parse(input.readLine() ?: "") as ConnectFrame.Err).code)
        }
        // Still five tries: the bad key was not a guess.
        val wrong = pairTyped(if (gate.current()!!.code == "000000") "111111" else "000000") as PairResult.Refused
        assertEquals(4, wrong.left)
    }

    @Test
    fun anOldPeerIsToldItsVersionDoesNotFit() {
        // An old phone (version 1, no key) at this TV.
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val input = socket.getInputStream().bufferedReader()
            input.readLine()
            val old = """{"t":"auth","v":1,"id":"${me.id}","name":"Pixel","cn":"${"bb".repeat(8)}","m":"${"cc".repeat(32)}"}"""
            socket.getOutputStream().write((old + "\n").toByteArray())
            socket.getOutputStream().flush()
            assertEquals(ConnectErrors.VERSION, (ConnectFrames.parse(input.readLine() ?: "") as ConnectFrame.Err).code)
        }
        // An old TV (version 1) at this phone: a clear refusal, not "unreachable".
        java.net.ServerSocket(0, 1, lo).use { old ->
            val thread = Thread {
                runCatching {
                    old.accept().use { s ->
                        s.getOutputStream().write("""{"t":"hello","v":1,"sn":"${"aa".repeat(8)}","id":"$tvId","name":"Old TV"}""".toByteArray())
                        s.getOutputStream().write("\n".toByteArray())
                        s.getOutputStream().flush()
                        Thread.sleep(500)
                    }
                }
            }.apply { isDaemon = true; start() }
            val result = ConnectClient.pair("127.0.0.1", old.localPort, me, "123456") { _, _ -> true }
            assertEquals(PairResult.Refused(ConnectErrors.VERSION, 0, 0), result)
            thread.join(2_000)
        }
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
