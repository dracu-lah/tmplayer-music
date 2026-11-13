package com.tmplayer.music.connect

import com.tmplayer.music.data.MemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLogicTest {

    private fun roundTrip(body: ConnectBody) = assertEquals(body, ConnectBodies.parse(ConnectBodies.encode(body)))

    @Test
    fun everyMessageSurvivesTheWire() {
        val ref = TrackRef(-1001234567890L, 77L, "song:key", "Title: \"quoted\" | bar")
        listOf(
            ConnectBody.Command(Transport.Toggle), ConnectBody.Seek(61_000), ConnectBody.Shuffle(true), ConnectBody.Repeat(2),
            ConnectBody.Volume(40), ConnectBody.Jump(3, "1:2:k"), ConnectBody.Remove(0, "1:2:k"),
            ConnectBody.Send(SendHow.Add, listOf(ref, ref.copy(messageId = 78)), start = 1, shuffle = true),
            ConnectBody.Ping, ConnectBody.Resync, ConnectBody.Pong, ConnectBody.Problem("x"),
            ConnectBody.NowPlaying("T", "A", "B", 200_000, 5_000, true, false, true, 1, 2, 9, 30, false, "ck", true),
            ConnectBody.QueueView(2, 50, 0, listOf(QueueEntry("1:2:k", "T", "A", 1_000))),
            ConnectBody.CoverPicture("k", "AAAA"),
            ConnectBody.Sent(SendHow.Play, 3, listOf("Gone song"), 1),
        ).forEach(::roundTrip)
    }

    @Test
    fun wrongTypesAndUnknownMessagesAreNull() {
        listOf(
            """{"t":"seek","ms":"abc"}""", """{"t":"seek"}""", """{"t":"repeat","mode":7}""", """{"t":"cmd","a":"explode"}""",
            """{"t":"send","how":"play","items":[]}""", """{"t":"send","how":"play","items":[{"c":0,"m":1}]}""",
            """{"t":"jump","i":-1,"id":"a"}""", """{"t":"nope"}""", """[]""", """"text"""", "", "{", """{"t":5}""",
        ).forEach { assertNull(it, ConnectBodies.parse(it)) }
    }

    @Test
    fun valuesOutOfRangeAreBroughtBack() {
        assertEquals(ConnectBody.Volume(100), ConnectBodies.parse("""{"t":"vol","v":9999}"""))
        assertEquals(ConnectBody.Seek(0), ConnectBodies.parse("""{"t":"seek","ms":-5}"""))
        val send = ConnectBodies.parse("""{"t":"send","how":"add","start":99,"items":[{"c":1,"m":2,"k":"a","n":"b"}]}""") as ConnectBody.Send
        assertEquals(0, send.start)
    }

    @Test
    fun aSendKeepsAtMostTheCapOfItems() {
        val refs = List(ConnectLimits.MAX_SEND_ITEMS + 50) { TrackRef(1, it + 1L, "k$it", "t$it") }
        val text = ConnectBodies.encode(ConnectBody.Send(SendHow.Add, refs))
        assertEquals(ConnectLimits.MAX_SEND_ITEMS, (ConnectBodies.parse(text) as ConnectBody.Send).items.size)
    }

    @Test
    fun theSecureChannelRefusesReplaysGapsAndForgeries() {
        val secret = ConnectHandshake.newSecret()
        val shared = ConnectCrypto.randomBytes(32)
        val th = ConnectCrypto.sha256("transcript")
        val host = ConnectHandshake.channel(shared, secret, th, host = true)
        val phone = ConnectHandshake.channel(shared, secret, th, host = false)
        val first = phone.seal(ConnectBody.Ping)
        val second = phone.seal(ConnectBody.Resync)
        assertEquals(ConnectBody.Ping, host.open(first))
        // Replayed.
        assertNull(host.open(first))
        // A gap (a frame lost or reordered).
        val third = phone.seal(ConnectBody.Ping)
        assertNull(host.open(third))
        assertEquals(ConnectBody.Resync, host.open(second))
        // Altered payload under the old MAC.
        val forged = phone.seal(ConnectBody.Ping).copy(payload = ConnectBodies.encode(ConnectBody.Command(Transport.Next)))
        assertNull(host.open(forged))
        // A frame the TV sent cannot be played back at the TV (the keys differ by direction).
        val reflected = host.seal(ConnectBody.Pong)
        assertNull(ConnectHandshake.channel(shared, secret, th, host = true).open(reflected))
    }

    @Test
    fun theCodeLocksAfterFiveWrongTriesForAMinuteAndChangesCode() {
        var now = 0L
        var n = 0
        val gate = PairingGate({ now }, { "00000${n++}" })
        val first = gate.open().code
        repeat(4) { assertEquals(PairingVerdict.Wrong(4 - it), gate.attempt { false }) }
        assertEquals(PairingVerdict.Locked(PairingGate.LOCK_MS), gate.attempt { false })
        // Locked: even the right code is not tried.
        now += 30_000
        var tried = false
        assertTrue(gate.attempt { tried = true; true } is PairingVerdict.Locked)
        assertFalse(tried)
        assertFalse(gate.current()!!.code == first)
        now += 31_000
        assertEquals(PairingVerdict.Accepted, gate.attempt { it.code == gate.current()!!.code })
        // Spent: a second use is closed.
        assertEquals(PairingVerdict.Closed, gate.attempt { true })
    }

    @Test
    fun aCodeExpiresAndAClosedGateRefuses() {
        var now = 0L
        val gate = PairingGate({ now })
        assertEquals(PairingVerdict.Closed, gate.attempt { true })
        gate.open()
        now += PairingGate.CODE_MS + 1
        assertNull(gate.current())
        assertEquals(PairingVerdict.Closed, gate.attempt { true })
        assertEquals(PairingGate.CODE_DIGITS, gate.open().code.length)
    }

    @Test
    fun theBookKeepsPairingsAndSurvivesGarbage() {
        val kv = MemoryKeyValues()
        val store = PairingStore(kv, PairingStore.PHONES_KEY)
        val entry = Paired(ConnectHandshake.newId(), "Pixel", ConnectCrypto.hex(ConnectHandshake.newSecret()), 5)
        store.add(entry)
        store.add(entry.copy(name = "Pixel 2"))
        assertEquals(listOf("Pixel 2"), store.list.value.map { it.name })
        assertEquals(1, PairingStore(kv, PairingStore.PHONES_KEY).list.value.size)
        store.forget(entry.id)
        assertTrue(PairingStore(kv, PairingStore.PHONES_KEY).list.value.isEmpty())
        kv.putString(PairingStore.PHONES_KEY, "{not json")
        assertTrue(PairingStore(kv, PairingStore.PHONES_KEY).list.value.isEmpty())
        kv.putString(PairingStore.PHONES_KEY, """[{"id":"short","secret":"zz"},5,null]""")
        assertTrue(PairingStore(kv, PairingStore.PHONES_KEY).list.value.isEmpty())
    }

    @Test
    fun theDeviceIdIsMadeOnceAndRepaired() {
        val kv = MemoryKeyValues()
        val id = PairingStore.deviceId(kv)
        assertEquals(id, PairingStore.deviceId(kv))
        kv.putString(PairingStore.DEVICE_ID_KEY, "x")
        assertNotNull(PairingStore.deviceId(kv).takeIf { it.length == 32 })
    }

    @Test
    fun theServerRunsOnlyWhenAllowed() {
        assertTrue(ConnectPolicy.shouldRun(true, true, false, true, 0))
        assertTrue(ConnectPolicy.shouldRun(true, false, true, true, 0))
        // Switched off, or neither on screen nor playing.
        assertFalse(ConnectPolicy.shouldRun(false, true, true, true, 3))
        assertFalse(ConnectPolicy.shouldRun(true, false, false, true, 3))
        // Screen off: only while a phone is still connected.
        assertFalse(ConnectPolicy.shouldRun(true, true, true, false, 0))
        assertTrue(ConnectPolicy.shouldRun(true, true, true, false, 1))
    }

    @Test
    fun localAddressesOnly() {
        fun local(text: String) = LocalNet.isLocal(java.net.InetAddress.getByName(text))
        assertTrue(local("192.168.1.20"))
        assertTrue(local("10.0.0.5"))
        assertTrue(local("172.16.4.4"))
        assertTrue(local("169.254.1.1"))
        assertTrue(local("fd12:3456::1"))
        assertFalse(local("8.8.8.8"))
        assertFalse(local("172.32.0.1"))
        assertFalse(local("2001:4860:4860::8888"))
    }

    @Test
    fun aPlaySendIsCutAroundTheStartAndKeepsTheRestInOrder() {
        val refs = List(1_000) { TrackRef(1, it + 1L, "k$it", "t$it") }
        val plan = SendPlan.messages(SendHow.Play, refs, start = 500)
        assertEquals(SendHow.Play, plan.first().how)
        assertEquals(ConnectLimits.MAX_SEND_ITEMS, plan.first().items.size)
        assertEquals(refs[500], plan.first().items[plan.first().start])
        assertTrue(plan.drop(1).all { it.how == SendHow.Add })
        // What comes after the window follows it, in order.
        val flat = plan.flatMap { it.items }
        assertEquals(flat.sortedBy { it.messageId }, flat)
        assertEquals(refs.last(), flat.last())
        assertTrue(SendPlan.messages(SendHow.Add, emptyList()).isEmpty())
    }

    @Test
    fun aNextSendKeepsTheUsersOrderAndATotalCap() {
        val refs = List(900) { TrackRef(1, it + 1L, "k$it", "t$it") }
        val plan = SendPlan.messages(SendHow.Next, refs)
        // Chunks go last first, so landing each right after the current track gives the original order.
        assertEquals(refs[800], plan.first().items.first())
        assertEquals(refs[0], plan.last().items.first())
        assertEquals((SendPlan.MAX_TOTAL + ConnectLimits.MAX_SEND_ITEMS - 1) / ConnectLimits.MAX_SEND_ITEMS, SendPlan.messages(SendHow.Add, List(10_000) { refs[0] }).size)
    }

    @Test
    fun theQueueWindowAndTheTapGuards() {
        assertEquals(0 to 0, RemoteQueue.window(0, 0))
        assertEquals(0 to 10, RemoteQueue.window(10, 5))
        val (from, count) = RemoteQueue.window(5_000, 4_990)
        assertEquals(ConnectLimits.MAX_QUEUE_ITEMS, count)
        assertTrue(from + count <= 5_000 && 4_990 in from until from + count)
        val ids = listOf("a", "b", "c")
        assertTrue(RemoteQueue.stillThere(1, "b", ids::getOrNull))
        // The row moved or was removed meanwhile: the tap is not applied to another row.
        assertFalse(RemoteQueue.stillThere(1, "c", ids::getOrNull))
        assertFalse(RemoteQueue.stillThere(9, "b", ids::getOrNull))
        assertFalse(RemoteQueue.stillThere(-1, "b", ids::getOrNull))
        assertEquals(2, RemoteQueue.currentAfterRemove(3, 0))
        assertEquals(3, RemoteQueue.currentAfterRemove(3, 5))
    }
}
