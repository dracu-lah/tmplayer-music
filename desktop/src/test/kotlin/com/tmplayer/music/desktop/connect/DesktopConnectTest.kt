package com.tmplayer.music.desktop.connect

import com.tmplayer.music.connect.ClientIdentity
import com.tmplayer.music.connect.ConnectBody
import com.tmplayer.music.connect.ConnectClient
import com.tmplayer.music.connect.ConnectErrors
import com.tmplayer.music.connect.ConnectHandshake
import com.tmplayer.music.connect.ConnectResult
import com.tmplayer.music.connect.PairLink
import com.tmplayer.music.connect.PairResult
import com.tmplayer.music.connect.PairingStore
import com.tmplayer.music.connect.SendHow
import com.tmplayer.music.connect.TrackRef
import com.tmplayer.music.connect.Transport
import com.tmplayer.music.desktop.DesktopPrefs
import com.tmplayer.music.desktop.player.FakeEngine
import com.tmplayer.music.desktop.player.FakeSource
import com.tmplayer.music.desktop.player.MemoryStore
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.RepeatMode
import com.tmplayer.music.desktop.player.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop as a Connect target: phone commands onto the player, the server's lifecycle, pairing over loopback. */
class DesktopConnectTest {
    private val songs = listOf("a", "b", "c", "d")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val source = FakeSource(songs)
    private val player = PlayerController(
        source = source, store = MemoryStore(), engineFactory = { engine },
        random = Random(3), saveDelayMs = 20, checkpointMs = 60_000, sleepFadeMs = 40, prefetchLeadMs = 30_000,
    )
    private val replies = CopyOnWriteArrayList<Pair<String, ConnectBody>>()
    private var libraryReady = true
    private val handler = DesktopConnectHandler(player, source, { libraryReady }, scope, resolveMs = 5_000).also {
        it.reply = { to, body -> replies += to to body }
    }

    @AfterTest
    fun tearDown() {
        runCatching { host.stop() }
        player.close()
        scope.cancel()
    }

    private fun ref(key: String) = TrackRef(1, songs.indexOf(key) + 1L, key, "Title $key")

    private fun keys() = player.ui.value.queue.entries.map { it.songKey }

    private fun command(body: ConnectBody) = handler.onCommand("phone", body)

    @Test
    fun `a Send plays from the picked track, queues next and adds at the end, in the phone's order`() = runBlocking {
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b"), ref("c")), start = 1))
        eventually("queue of three") { keys() == listOf("a", "b", "c") }
        assertEquals("b", player.ui.value.current?.songKey)
        eventually("answer") { replies.any { it.second is ConnectBody.Sent } }
        assertEquals(ConnectBody.Sent(SendHow.Play, 3, emptyList(), 0), replies.last().second)

        command(ConnectBody.Send(SendHow.Add, listOf(ref("d")), 0))
        eventually("added at the end") { keys() == listOf("a", "b", "c", "d") }
        command(ConnectBody.Send(SendHow.Next, listOf(ref("d")), 0))
        eventually("queued next") { keys() == listOf("a", "b", "d", "c", "d") }
        Unit
    }

    @Test
    fun `a track the library does not have is named in the answer and never queued`() = runBlocking {
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), TrackRef(9, 9, "ghost", "Ghost song")), 0))
        eventually("answer") { replies.any { it.second is ConnectBody.Sent } }
        assertEquals(ConnectBody.Sent(SendHow.Play, 1, listOf("Ghost song"), 1), replies.last().second)
        assertEquals(listOf("a"), keys())
    }

    @Test
    fun `with the library not open a Send finds nothing and changes nothing`() = runBlocking {
        libraryReady = false
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b")), 0))
        eventually("answer") { replies.any { it.second is ConnectBody.Sent } }
        val sent = replies.last().second as ConnectBody.Sent
        assertEquals(0, sent.ok)
        assertEquals(2, sent.missingCount)
        assertTrue(keys().isEmpty())
    }

    @Test
    fun `transport, shuffle, repeat, volume and seek reach the player`() = runBlocking {
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b"), ref("c")), 0))
        eventually("playing") { player.ui.value.playing }
        command(ConnectBody.Command(Transport.Pause))
        eventually("paused") { !player.ui.value.playing }
        command(ConnectBody.Command(Transport.Toggle))
        eventually("playing again") { player.ui.value.playing }
        command(ConnectBody.Command(Transport.Next))
        eventually("next") { player.ui.value.current?.songKey == "b" }
        command(ConnectBody.Repeat(2))
        eventually("repeat all") { player.ui.value.repeat == RepeatMode.All }
        command(ConnectBody.Repeat(1))
        eventually("repeat one") { player.ui.value.repeat == RepeatMode.One }
        command(ConnectBody.Shuffle(true))
        eventually("shuffle") { player.ui.value.shuffle }
        command(ConnectBody.Volume(35))
        eventually("volume") { player.ui.value.volume == 35 }
        command(ConnectBody.Seek(12_000))
        eventually("seek") { engine.seeks.contains(12_000L) }
        Unit
    }

    @Test
    fun `a tap on a row that moved is not applied to another row and the phone gets the queue again`() = runBlocking {
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b"), ref("c")), 0))
        eventually("queue") { keys().size == 3 }
        val view = handler.queueView()
        val idOfC = view.items[2].id
        // Right row: jumps.
        command(ConnectBody.Jump(2, idOfC))
        eventually("jumped") { player.ui.value.current?.songKey == "c" }
        // The id of row 2 sent for row 1 (the row moved meanwhile): nothing happens, the queue is sent back.
        replies.clear()
        command(ConnectBody.Remove(1, idOfC))
        eventually("queue sent back") { replies.any { it.second is ConnectBody.QueueView } }
        assertEquals(listOf("a", "b", "c"), keys())
        // The right id removes.
        command(ConnectBody.Remove(0, view.items[0].id))
        eventually("removed") { keys() == listOf("b", "c") }
        Unit
    }

    @Test
    fun `the picture the phone gets is the player's, bounded, and empty before anything plays`() = runBlocking {
        val empty = handler.nowPlaying()
        assertFalse(empty.hasTrack)
        assertEquals(0, handler.queueView().total)
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b")), 1))
        eventually("playing b") { player.ui.value.current?.songKey == "b" }
        val now = handler.nowPlaying()
        assertTrue(now.hasTrack)
        assertEquals("Title b", now.title)
        assertEquals(1, now.index)
        assertEquals(2, now.count)
        val queue = handler.queueView()
        assertEquals(2, queue.total)
        assertEquals(listOf("Title a", "Title b"), queue.items.map { it.title })
        assertEquals(2, handler.snapshot().size)
    }

    @Test
    fun `state is pushed only while a phone is connected`() = runBlocking {
        val clients = MutableStateFlow(0)
        val sent = CopyOnWriteArrayList<ConnectBody>()
        val job = handler.watch(clients) { sent += it }
        command(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("b")), 0))
        eventually("playing") { player.ui.value.playing }
        Thread.sleep(200)
        assertTrue(sent.isEmpty(), "nothing goes out with nobody connected")
        clients.value = 1
        Thread.sleep(100)
        command(ConnectBody.Command(Transport.Next))
        eventually("a push") { sent.any { it is ConnectBody.NowPlaying && it.title == "Title b" } }
        eventually("the queue moved on too") { sent.any { it is ConnectBody.QueueView } }
        clients.value = 0
        Thread.sleep(100)
        val before = sent.size
        command(ConnectBody.Command(Transport.Previous))
        Thread.sleep(300)
        assertEquals(before, sent.size)
        job.cancel()
    }

    @Test
    fun `a jump of the position is a seek, ordinary playing is not`() {
        assertFalse(Seeks.jumped(10_000, 250, 10_250, playing = true))
        assertFalse(Seeks.jumped(10_000, 1_000, 10_900, playing = true))
        assertTrue(Seeks.jumped(10_000, 250, 60_000, playing = true))
        assertTrue(Seeks.jumped(10_000, 250, 2_000, playing = true))
        // Paused: the position does not move by itself.
        assertFalse(Seeks.jumped(10_000, 5_000, 10_000, playing = false))
        assertTrue(Seeks.jumped(10_000, 5_000, 15_000, playing = false))
    }

    // ---- The server: lifecycle, pairing over loopback, a command through the real socket.

    private class FakeAdvert : ConnectAdvert {
        val registered = CopyOnWriteArrayList<Pair<Int, String>>()
        val unregistered = java.util.concurrent.atomic.AtomicInteger()
        override fun register(address: InetAddress, port: Int, id: String, name: String) {
            registered += port to name
        }

        override fun unregister() {
            unregistered.incrementAndGet()
        }
    }

    private val lo = InetAddress.getByName("127.0.0.1")
    private val file = File.createTempFile("desktop-connect", ".properties").also { it.deleteOnExit() }
    private val phones = PairingStore(DesktopPrefs(file), PairingStore.PHONES_KEY)
    private val advert = FakeAdvert()
    private val tvId = ConnectHandshake.newId()
    private val me = ClientIdentity(ConnectHandshake.newId(), "Pixel")
    private var address: InetAddress? = lo
    private val host = DesktopConnectHost(handler, phones, tvId, "My computer", advert, scope, bind = { address }, allowed = { true })

    @Test
    fun `the server is off until started, listens on the one address, is advertised and stops cleanly`() = runBlocking {
        assertEquals(ConnectHostStatus.Off, host.status.value)
        assertFalse(host.openPairing(), "no pairing code while nothing listens")
        host.start()
        val listening = assertIs<ConnectHostStatus.Listening>(host.status.value)
        assertEquals("127.0.0.1", listening.address)
        eventually("advertised") { advert.registered.isNotEmpty() }
        assertEquals(listening.port, advert.registered.single().first)
        assertEquals("My computer", advert.registered.single().second)
        // Starting again changes nothing.
        host.start()
        assertEquals(1, advert.registered.size)
        host.stop()
        assertEquals(ConnectHostStatus.Off, host.status.value)
        eventually("advert withdrawn") { advert.unregistered.get() >= 1 }
        assertNull(host.gate.current())
        assertTrue(ConnectClient.pair("127.0.0.1", listening.port, me, "123456") !is PairResult.Success)
    }

    @Test
    fun `no network is said, and a network that comes back is picked up by refresh`() {
        address = null
        host.start()
        assertEquals(ConnectHostStatus.NoNetwork, host.status.value)
        address = lo
        host.refresh()
        assertIs<ConnectHostStatus.Listening>(host.status.value)
        host.stop()
        // Off stays off: refresh never starts a stopped server.
        host.refresh()
        assertEquals(ConnectHostStatus.Off, host.status.value)
    }

    @Test
    fun `a phone pairs with the code or the QR token, connects, and drives the player through the socket`() = runBlocking {
        host.start()
        val port = (host.status.value as ConnectHostStatus.Listening).port
        assertTrue(host.openPairing())
        val offer = host.gate.current()!!
        // The QR token of the offer pairs without typing, only the computer the QR code named.
        val paired = (ConnectClient.pair(PairLink("127.0.0.1", port, tvId, offer.token), me) as PairResult.Success).paired
        assertEquals(tvId, paired.id)
        assertEquals(1, phones.list.value.size)
        // The offer is spent with either way.
        assertEquals(ConnectErrors.CLOSED, (ConnectClient.pair("127.0.0.1", port, me, offer.code) as PairResult.Refused).code)

        val inbox = CopyOnWriteArrayList<ConnectBody>()
        val connected = ConnectClient.connect("127.0.0.1", port, me, paired, { inbox += it }, { }) as ConnectResult.Connected
        // The phone is told the whole picture first.
        eventually("snapshot") { inbox.any { it is ConnectBody.NowPlaying } && inbox.any { it is ConnectBody.QueueView } }
        connected.connection.send(ConnectBody.Send(SendHow.Play, listOf(ref("a"), ref("c")), 0))
        eventually("queued by the phone") { keys() == listOf("a", "c") }
        eventually("the answer reached the phone") { inbox.any { it is ConnectBody.Sent } }
        connected.connection.send(ConnectBody.Command(Transport.Next))
        eventually("next") { player.ui.value.current?.songKey == "c" }
        eventually("state pushed") { inbox.any { it is ConnectBody.NowPlaying && it.title == "Title c" } }

        // Forgetting ends the phone's pairing; it cannot connect again.
        host.forget(me.id)
        eventually("forgotten") { phones.list.value.isEmpty() }
        assertEquals(ConnectResult.Refused(ConnectErrors.UNPAIRED), ConnectClient.connect("127.0.0.1", port, me, paired, { }, { }))
        host.stop()
    }

    @Test
    fun `a wrong code is counted and five lock the pairing`() = runBlocking {
        host.start()
        val port = (host.status.value as ConnectHostStatus.Listening).port
        host.openPairing()
        val wrong = if (host.gate.current()!!.code == "000000") "111111" else "000000"
        repeat(4) { assertEquals(ConnectErrors.BAD_CODE, (ConnectClient.pair("127.0.0.1", port, me, wrong) as PairResult.Refused).code) }
        assertEquals(ConnectErrors.LOCKED, (ConnectClient.pair("127.0.0.1", port, me, wrong) as PairResult.Refused).code)
        assertTrue(phones.list.value.isEmpty())
        host.stop()
    }
}
