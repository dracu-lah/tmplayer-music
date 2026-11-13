package com.tmplayer.music.desktop.player

import com.tmplayer.music.player.SavedQueue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControllerTest {
    private val songs = listOf("a", "b", "c", "d")

    private class Rig(
        val source: FakeSource,
        val store: MemoryStore = MemoryStore(),
        val engine: FakeEngine = FakeEngine(),
    ) {
        val engineMade = AtomicInteger()
        val playingChanges = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val controller = PlayerController(
            source = source,
            store = store,
            engineFactory = {
                engineMade.incrementAndGet()
                engine
            },
            onPlayingChanged = { playingChanges += it },
            random = Random(3),
            saveDelayMs = 20,
            checkpointMs = 60_000,
            sleepFadeMs = 40,
            prefetchLeadMs = 30_000,
        )
    }

    private fun rig(broken: Set<String> = emptySet()) = Rig(FakeSource(songs, broken))

    private fun Rig.entries(vararg keys: String) = keys.map { source.entry(it) }

    private fun current(r: Rig) = r.controller.ui.value.current?.songKey

    @Test
    fun `a restored queue waits paused and nothing is opened until play`() = runBlocking {
        val first = rig()
        first.controller.playEntries(first.entries("a", "b", "c"), 1)
        eventually("b opened") { first.source.opens == listOf("b") }
        eventually("queue saved") { first.store.text?.contains("\"current\":1") == true }

        val second = Rig(FakeSource(songs), first.store)
        second.controller.restore()
        eventually("restored") { !second.controller.ui.value.queue.isEmpty }
        val ui = second.controller.ui.value
        assertEquals("b", ui.current?.songKey)
        assertFalse(ui.playing)
        assertTrue(ui.restored)
        // Nothing autoplays: no engine, no stream, no play recorded.
        assertEquals(0, second.engineMade.get())
        assertTrue(second.source.opens.isEmpty())
        assertTrue(second.source.plays.isEmpty())

        second.controller.togglePlay()
        eventually("opens on play") { second.source.opens == listOf("b") }
        eventually("playing") { second.controller.ui.value.playing }
        assertEquals(1, second.engineMade.get())
        Unit
    }

    @Test
    fun `a restored position is where the first play starts`() = runBlocking {
        val store = MemoryStore(SavedQueue(listOf(SavedQueue.Item(1, 1, "a"), SavedQueue.Item(1, 2, "b")), 1, 42_000, shuffle = false, repeat = 2).encode())
        val r = Rig(FakeSource(songs), store)
        r.controller.restore()
        eventually("restored") { !r.controller.ui.value.queue.isEmpty }
        assertEquals(42_000, r.controller.position.value)
        assertEquals(RepeatMode.All, r.controller.ui.value.repeat)
        r.controller.play()
        eventually("engine opened") { r.engine.opened.isNotEmpty() }
        assertEquals(Triple("test://b", 42_000L, true), r.engine.opened.first())
        Unit
    }

    @Test
    fun `restoring garbage or an empty file restores nothing`() = runBlocking {
        for (text in listOf("", "   ", "not json", "{\"items\":[]}", "{\"items\":[{\"chatId\":0,\"messageId\":0}],\"current\":0,\"positionMs\":0}")) {
            val r = Rig(FakeSource(songs), MemoryStore(text))
            r.controller.restore()
            kotlinx.coroutines.delay(30)
            assertTrue(r.controller.ui.value.queue.isEmpty, "restored from: $text")
            assertEquals(0, r.engineMade.get())
        }
    }

    @Test
    fun `play opens the chosen track at the start and records a play`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b", "c"), 2)
        eventually("playing c") { r.controller.ui.value.playing && current(r) == "c" }
        assertEquals(listOf("test://c"), r.engine.opened.map { it.first })
        assertEquals(0L, r.engine.opened.first().second)
        eventually("play recorded") { r.source.plays == listOf("c") }
        assertEquals(listOf(true), r.playingChanges.distinct())
        Unit
    }

    @Test
    fun `when a track ends the next one opens, and at the end of the queue it stops paused`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.engine.end()
        eventually("b opened") { r.source.opens == listOf("a", "b") }
        eventually("b playing") { current(r) == "b" && r.controller.ui.value.playing }
        assertEquals(listOf("a"), r.source.finished)

        r.engine.end()
        eventually("stopped at the end") { !r.controller.ui.value.playing && r.source.finished.size == 2 }
        // Still on the last track, paused; Play starts it again from the top.
        assertEquals("b", current(r))
        r.controller.togglePlay()
        eventually("b opened again") { r.source.opens == listOf("a", "b", "b") }
        Unit
    }

    @Test
    fun `repeat one plays the same track again when it ends`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.cycleRepeat()
        r.controller.cycleRepeat()
        eventually("repeat one") { r.controller.ui.value.repeat == RepeatMode.One }
        r.engine.end()
        eventually("a opened twice") { r.source.opens == listOf("a", "a") }
        assertEquals("a", current(r))
        Unit
    }

    @Test
    fun `repeat all goes round at the end`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 1)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.cycleRepeat()
        eventually("repeat all") { r.controller.ui.value.repeat == RepeatMode.All }
        r.engine.end()
        eventually("back to a") { r.source.opens == listOf("b", "a") }
        Unit
    }

    @Test
    fun `a track that cannot open is skipped with the reason said, and it plays on`() = runBlocking {
        val r = rig(broken = setOf("b"))
        r.controller.playEntries(r.entries("a", "b", "c"), 1)
        eventually("c playing") { current(r) == "c" && r.controller.ui.value.playing }
        assertEquals(listOf("b", "c"), r.source.opens)
        val message = r.controller.ui.value.error
        assertNotNull(message)
        assertTrue("cannot open b" in message, message)
        Unit
    }

    @Test
    fun `three broken tracks in a row stop with the error and do not loop`() = runBlocking {
        val r = rig(broken = setOf("a", "b", "c", "d"))
        r.controller.playEntries(r.entries("a", "b", "c", "d"), 0)
        eventually("gave up") { r.controller.ui.value.error != null && !r.controller.ui.value.playing && !r.controller.ui.value.buffering && r.source.opens.size >= 4 }
        kotlinx.coroutines.delay(150)
        // The first and three more: no more than that, and nothing keeps trying.
        assertEquals(4, r.source.opens.size)
        assertFalse(r.controller.ui.value.playing)
        Unit
    }

    @Test
    fun `try again opens the current track again`() = runBlocking {
        val r = rig(broken = setOf("a"))
        r.controller.playEntries(r.entries("a"), 0)
        eventually("error shown") { r.controller.ui.value.error != null }
        r.controller.retry()
        eventually("opened again") { r.source.opens.size >= 2 }
        Unit
    }

    @Test
    fun `a failure part way through a track skips to the next`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.engine.fail("mpv_error=-13")
        eventually("b opened") { r.source.opens == listOf("a", "b") }
        Unit
    }

    @Test
    fun `pause and play on a loaded track do not open it again`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.pause()
        eventually("paused") { !r.controller.ui.value.playing }
        r.controller.play()
        eventually("playing again") { r.controller.ui.value.playing }
        assertEquals(listOf("a"), r.source.opens)
        // Pausing keeps the spot for Speed dial.
        eventually("spot kept") { r.source.kept.any { it.first == "a" } }
        Unit
    }

    @Test
    fun `next and previous move through the queue`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b", "c"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.next()
        eventually("b") { current(r) == "b" && r.source.opens.last() == "b" }
        r.controller.next()
        eventually("c") { current(r) == "c" && r.source.opens.last() == "c" }
        // At the end Next does nothing.
        r.controller.next()
        kotlinx.coroutines.delay(60)
        assertEquals("c", current(r))
        r.controller.previous()
        eventually("back to b") { current(r) == "b" }
        Unit
    }

    @Test
    fun `previous after a few seconds restarts the track instead`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 1)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.seekTo(10_000)
        eventually("seeked") { r.engine.seeks.contains(10_000) }
        r.controller.previous()
        eventually("restarted") { r.engine.seeks.contains(0) }
        assertEquals("b", current(r))
        assertEquals(listOf("b"), r.source.opens)
        Unit
    }

    @Test
    fun `seeking with an empty queue does nothing`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.clear()
        eventually("cleared") { r.controller.ui.value.queue.isEmpty }
        // Nothing to seek in an empty queue: no crash, no engine call.
        r.controller.seekTo(5_000)
        kotlinx.coroutines.delay(40)
        assertTrue(r.engine.seeks.isEmpty())
        Unit
    }

    @Test
    fun `removing the playing track opens the next, removing the last empties and stops`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.remove(0)
        eventually("b opened") { r.source.opens == listOf("a", "b") }
        assertEquals(1, r.controller.ui.value.queue.size)
        r.controller.remove(0)
        eventually("emptied and stopped") { r.controller.ui.value.queue.isEmpty && !r.controller.ui.value.playing }
        eventually("saved blank") { r.store.text == "" }
        Unit
    }

    @Test
    fun `play next and add to queue fill an empty queue and start it`() = runBlocking {
        val r = rig()
        val song = com.tmplayer.music.library.Song(
            groupKey = "a", title = "A", artist = "Artist", album = "", durationMs = 1000,
            defaultTrack = com.tmplayer.music.library.TrackId(1, 1), versionCount = 1,
            bestFormat = com.tmplayer.music.library.AudioFormat.FLAC, thumbFileId = 0, coverKey = "", variant = "", addedSec = 0,
        )
        r.controller.addToQueue(listOf(song))
        eventually("started") { r.controller.ui.value.queue.size == 1 && r.source.opens.size == 1 }
        Unit
    }

    @Test
    fun `volume and mute reach the engine and are reported to be kept`() = runBlocking {
        val saved = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val source = FakeSource(songs)
        val engine = FakeEngine()
        val controller = PlayerController(source, MemoryStore(), { engine }, onVolume = { v, m -> saved += v to m }, saveDelayMs = 20)
        controller.playEntries(listOf(source.entry("a")), 0)
        eventually("playing") { controller.ui.value.playing }
        controller.setVolume(40)
        eventually("volume") { controller.ui.value.volume == 40 && engine.state.value.volume == 40 }
        controller.toggleMute()
        eventually("muted") { controller.ui.value.muted && engine.state.value.muted }
        // Raising the volume is a way out of mute.
        controller.setVolume(60)
        eventually("unmuted") { !controller.ui.value.muted }
        assertEquals(listOf(40 to false, 40 to true, 60 to false), saved.toList())
        // Out of range is brought in.
        controller.setVolume(500)
        eventually("capped") { controller.ui.value.volume == 100 }
        Unit
    }

    @Test
    fun `the queue file holds the whole queue with shuffle and repeat`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b", "c"), 1)
        eventually("playing") { r.controller.ui.value.playing }
        r.controller.setShuffle(true)
        r.controller.cycleRepeat()
        eventually("saved") {
            SavedQueue.decode(r.store.text)?.let { it.shuffle && it.repeat == RepeatMode.All.saved } == true
        }
        val saved = SavedQueue.decode(r.store.text)!!
        assertEquals(listOf("a", "b", "c"), saved.items.map { it.songKey })
        assertEquals(1, saved.current)
        Unit
    }

    @Test
    fun `closing saves the queue at once and lets the engine go`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        r.store.text = null
        r.controller.close()
        assertNotNull(SavedQueue.decode(r.store.text))
        assertTrue(r.engine.closed)
        assertNull(null)
        Unit
    }

    @Test
    fun `the sound choices reach the engine, with the track's loudness only while normalise is on`() = runBlocking {
        val r = rig()
        r.source.gains["a"] = 0.5f
        r.controller.setSound(SoundSettings(speed = 1.5f, keepPitch = false))
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("playing") { r.controller.ui.value.playing }
        eventually("sound given before the open") { r.engine.sounds.any { it.first.speed == 1.5f && !it.first.keepPitch && it.second == 1f } }
        r.controller.setSound(SoundSettings(speed = 1.5f, normalise = true))
        eventually("gain follows the switch") { r.engine.sounds.last().first.normalise }
        r.controller.skipTo(0)
        eventually("a opened again with its gain") { r.engine.sounds.any { it.first.normalise && it.second == 0.5f } }
        Unit
    }

    @Test
    fun `the sleep timer at the end of the track pauses on the next one and turns itself off`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.controller.sleepAtEndOfTrack()
        eventually("timer set") { r.controller.sleep.value == SleepSetting.EndOfTrack }
        r.engine.end()
        eventually("paused, timer off") { !r.controller.ui.value.playing && r.controller.sleep.value == SleepSetting.Off }
        // Nothing opened for b: it waits, and Play starts it.
        assertEquals("b", current(r))
        assertEquals(listOf("a"), r.source.opens)
        r.controller.togglePlay()
        eventually("b opened on play") { r.source.opens == listOf("a", "b") }
        Unit
    }

    @Test
    fun `the sleep timer by minutes fades the volume, pauses and puts the volume back`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.controller.sleepAfterMs(150)
        eventually("timer set") { r.controller.sleep.value is SleepSetting.At }
        eventually("paused by the timer") { !r.controller.ui.value.playing && r.controller.sleep.value == SleepSetting.Off }
        eventually("volume back") { r.engine.state.value.volume == 100 }
        // Turning it off before it fires keeps playing.
        r.controller.play()
        eventually("playing again") { r.controller.ui.value.playing }
        r.controller.sleepAfterMs(60_000)
        eventually("set") { r.controller.sleep.value is SleepSetting.At }
        r.controller.sleepOff()
        eventually("off") { r.controller.sleep.value == SleepSetting.Off }
        assertTrue(r.controller.ui.value.playing)
        Unit
    }

    @Test
    fun `near the end the next track is queued in the engine, and the queue follows when the engine moves on`() = runBlocking {
        val r = rig()
        r.engine.queueNextResult = true
        r.controller.playEntries(r.entries("a", "b", "c"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        // Far from the end: nothing is opened ahead.
        assertTrue(r.engine.queuedNext.isEmpty())
        r.engine.at(190_000)
        eventually("b queued") { r.engine.queuedNext == listOf("test://b") }
        r.engine.advance()
        eventually("the queue is on b") { current(r) == "b" }
        eventually("a finished, b played") { r.source.finished == listOf("a") && r.source.plays == listOf("a", "b") }
        // No second open of b: the engine plays the one it was given.
        assertEquals(listOf("test://a"), r.engine.opened.map { it.first })
        assertEquals(listOf("a", "b"), r.source.opens)
        Unit
    }

    @Test
    fun `when the engine cannot queue, the next track is opened ahead and used when its turn comes`() = runBlocking {
        val r = rig()
        r.controller.playEntries(r.entries("a", "b"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.engine.at(190_000)
        eventually("b opened ahead") { r.source.opens == listOf("a", "b") }
        r.engine.end()
        eventually("b playing") { current(r) == "b" && r.controller.ui.value.playing }
        // The stream opened ahead was the one used.
        assertEquals(listOf("a", "b"), r.source.opens)
        assertEquals(listOf("test://a", "test://b"), r.engine.opened.map { it.first })
        Unit
    }

    @Test
    fun `changing the queue drops what was opened ahead`() = runBlocking {
        val r = rig()
        r.engine.queueNextResult = true
        r.controller.playEntries(r.entries("a", "b", "c"), 0)
        eventually("a playing") { r.controller.ui.value.playing }
        r.engine.at(190_000)
        eventually("b queued") { r.engine.queuedNext == listOf("test://b") }
        r.controller.move(1, 2)
        eventually("the queue ahead was cleared") { r.engine.queueCleared >= 1 }
        // The playing track's clock moves on, as it does four times a second.
        r.engine.at(190_500)
        eventually("c queued instead") { r.engine.queuedNext.contains("test://c") }
        Unit
    }
}
