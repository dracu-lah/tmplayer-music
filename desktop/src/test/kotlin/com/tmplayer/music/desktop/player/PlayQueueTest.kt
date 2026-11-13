package com.tmplayer.music.desktop.player

import com.tmplayer.music.library.TrackId
import com.tmplayer.music.player.SavedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayQueueTest {
    private fun entry(n: Int) = QueueEntry(TrackId(1, n.toLong()), "song$n", "Title $n", "Artist")

    private fun queue(size: Int, start: Int = 0, shuffle: Boolean = false, repeat: RepeatMode = RepeatMode.Off) =
        PlayQueue.of((0 until size).map(::entry), start, shuffle, repeat, Random(7))

    private fun PlayQueue.keys() = entries.map { it.songKey }

    @Test
    fun `an empty queue goes nowhere`() {
        val q = PlayQueue()
        assertNull(q.nextStep(auto = false))
        assertNull(q.previousStep(0))
        assertNull(q.currentEntry)
        assertFalse(q.hasNext)
    }

    @Test
    fun `next walks the list and stops at the end with repeat off`() {
        var q = queue(3)
        assertEquals(1, q.nextStep(false)?.index)
        q = q.jumpTo(2)
        assertNull(q.nextStep(false))
        assertNull(q.nextStep(true))
        assertFalse(q.hasNext)
    }

    @Test
    fun `repeat all goes round and repeat one replays when the track ends by itself`() {
        val all = queue(3, start = 2, repeat = RepeatMode.All)
        assertEquals(QueueStep(0), all.nextStep(false))
        assertEquals(QueueStep(0), all.nextStep(true))
        val one = queue(3, start = 1, repeat = RepeatMode.One)
        assertEquals(QueueStep(1, restart = true), one.nextStep(auto = true))
        // The Next button still moves on with repeat one.
        assertEquals(QueueStep(2), one.nextStep(auto = false))
    }

    @Test
    fun `a queue of one with repeat all replays it`() {
        val q = queue(1, repeat = RepeatMode.All)
        assertEquals(QueueStep(0, restart = true), q.nextStep(true))
        assertNull(q.upNext())
    }

    @Test
    fun `previous restarts a track that has played a while, else goes back`() {
        val q = queue(3, start = 1)
        assertEquals(QueueStep(1, restart = true), q.previousStep(PlayQueue.RESTART_AFTER_MS))
        assertEquals(QueueStep(0), q.previousStep(PlayQueue.RESTART_AFTER_MS - 1))
        // On the first track there is nothing before it: it starts again.
        assertEquals(QueueStep(0, restart = true), q.jumpTo(0).previousStep(0))
        // With repeat all the one before the first is the last.
        assertEquals(QueueStep(2), queue(3, repeat = RepeatMode.All).previousStep(0))
    }

    @Test
    fun `repeat cycles off all one off`() {
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertEquals(RepeatMode.One, RepeatMode.All.next())
        assertEquals(RepeatMode.Off, RepeatMode.One.next())
        assertEquals(RepeatMode.One, RepeatMode.fromSaved(RepeatMode.One.saved))
        assertEquals(RepeatMode.All, RepeatMode.fromSaved(RepeatMode.All.saved))
        assertEquals(RepeatMode.Off, RepeatMode.fromSaved(99))
    }

    @Test
    fun `shuffle keeps the playing track first and plays every track once`() {
        val q = queue(8, start = 3, shuffle = true)
        assertEquals(3, q.order.first())
        assertEquals((0 until 8).toSet(), q.order.toSet())
        val seen = mutableListOf(q.current)
        var at = q
        while (true) {
            val step = at.nextStep(false) ?: break
            seen += step.index
            at = at.jumpTo(step.index)
        }
        assertEquals(8, seen.size)
        assertEquals(8, seen.toSet().size)
    }

    @Test
    fun `turning shuffle off goes on in list order from the current track`() {
        val q = queue(6, start = 2, shuffle = true).withShuffle(false)
        assertFalse(q.shuffle)
        assertTrue(q.order.isEmpty())
        assertEquals(QueueStep(3), q.nextStep(false))
    }

    @Test
    fun `play next puts tracks right behind the playing one, with shuffle too`() {
        val added = listOf(entry(90), entry(91))
        val plain = queue(4, start = 1).playNext(added)
        assertEquals(listOf("song0", "song1", "song90", "song91", "song2", "song3"), plain.keys())
        assertEquals(QueueStep(2), plain.nextStep(false))

        val shuffled = queue(4, start = 1, shuffle = true).playNext(added)
        assertEquals(6, shuffled.order.size)
        assertEquals(6, shuffled.order.toSet().size)
        val first = shuffled.nextStep(false)!!.index
        assertEquals("song90", shuffled.entries[first].songKey)
    }

    @Test
    fun `append puts tracks last and starts an empty queue`() {
        val q = queue(2).append(listOf(entry(5)))
        assertEquals(listOf("song0", "song1", "song5"), q.keys())
        val started = PlayQueue().append(listOf(entry(5)))
        assertEquals(listOf("song5"), started.keys())
        assertEquals(0, started.current)
    }

    @Test
    fun `removing a track before the playing one keeps the same track playing`() {
        val q = queue(5, start = 3)
        val removal = q.remove(1)
        assertFalse(removal.currentChanged)
        assertEquals("song3", removal.queue.currentEntry?.songKey)
        assertEquals(4, removal.queue.size)
    }

    @Test
    fun `removing the playing track moves to the next, or the last when it was last`() {
        val q = queue(4, start = 1)
        val removal = q.remove(1)
        assertTrue(removal.currentChanged)
        assertEquals("song2", removal.queue.currentEntry?.songKey)
        val last = queue(4, start = 3).remove(3)
        assertEquals("song2", last.queue.currentEntry?.songKey)
    }

    @Test
    fun `removing the only track empties the queue and keeps shuffle and repeat`() {
        val removal = queue(1, shuffle = true, repeat = RepeatMode.All).remove(0)
        assertTrue(removal.queue.isEmpty)
        assertTrue(removal.currentChanged)
        assertTrue(removal.queue.shuffle)
        assertEquals(RepeatMode.All, removal.queue.repeat)
    }

    @Test
    fun `removing with shuffle on keeps a valid order`() {
        val q = queue(6, start = 2, shuffle = true)
        val removal = q.remove(4)
        assertEquals(5, removal.queue.order.size)
        assertEquals((0 until 5).toSet(), removal.queue.order.toSet())
        assertEquals("song2", removal.queue.currentEntry?.songKey)
    }

    @Test
    fun `removing an index out of range changes nothing`() {
        val q = queue(3)
        assertTrue(q.remove(7).queue === q)
        assertTrue(q.remove(-1).queue === q)
    }

    @Test
    fun `moving a track keeps the playing track playing`() {
        val q = queue(5, start = 2)
        val moved = q.move(0, 4)
        assertEquals(listOf("song1", "song2", "song3", "song4", "song0"), moved.keys())
        assertEquals("song2", moved.currentEntry?.songKey)
        val movedPlaying = q.move(2, 0)
        assertEquals("song2", movedPlaying.currentEntry?.songKey)
        assertEquals(0, movedPlaying.current)
        assertTrue(q.move(2, 2) === q)
        assertTrue(q.move(0, 99) === q)
    }

    @Test
    fun `moving with shuffle on keeps the order a permutation`() {
        val q = queue(6, start = 1, shuffle = true).move(5, 0)
        assertEquals((0 until 6).toSet(), q.order.toSet())
        assertEquals("song1", q.currentEntry?.songKey)
    }

    @Test
    fun `a huge list is cut around the start`() {
        val q = PlayQueue.of((0 until 5_000).map(::entry), start = 4_000, random = Random(1))
        assertEquals(SavedQueue.MAX_ITEMS, q.size)
        assertEquals("song4000", q.currentEntry?.songKey)
    }

    @Test
    fun `appending past what a saved queue holds drops the far end, never the playing track`() {
        val q = PlayQueue.of((0 until SavedQueue.MAX_ITEMS).map(::entry), start = SavedQueue.MAX_ITEMS - 1, random = Random(1))
            .append(listOf(entry(9_001), entry(9_002)))
        assertEquals(SavedQueue.MAX_ITEMS, q.size)
        assertEquals("song${SavedQueue.MAX_ITEMS - 1}", q.currentEntry?.songKey)
    }

    @Test
    fun `an out of range start and an empty list are brought into range`() {
        assertEquals(2, PlayQueue.of((0 until 3).map(::entry), start = 99).current)
        assertEquals(0, PlayQueue.of((0 until 3).map(::entry), start = -4).current)
        assertTrue(PlayQueue.of(emptyList(), 0).isEmpty)
    }

    @Test
    fun `the saved form keeps everything and restoring it round trips`() {
        val q = queue(5, start = 3, shuffle = true, repeat = RepeatMode.One)
        val saved = q.toSaved(12_345)!!
        assertEquals(3, saved.current)
        assertEquals(12_345, saved.positionMs)
        assertTrue(saved.shuffle)
        assertEquals(RepeatMode.One.saved, saved.repeat)
        val decoded = SavedQueue.decode(saved.encode())!!
        val back = PlayQueue.restored(decoded, q.entries, Random(2))
        assertEquals(q.keys(), back.keys())
        assertEquals(3, back.current)
        assertTrue(back.shuffle)
        assertEquals(RepeatMode.One, back.repeat)
    }

    @Test
    fun `restoring over items that have gone keeps the rest and lands on the nearest track`() {
        val q = queue(5, start = 2)
        val saved = q.toSaved(0)!!
        // The playing track and the first are gone.
        val resolved: List<QueueEntry?> = listOf(null, q.entries[1], null, q.entries[3], q.entries[4])
        val back = PlayQueue.restored(saved, resolved)
        assertEquals(listOf("song1", "song3", "song4"), back.keys())
        assertEquals("song3", back.currentEntry?.songKey)
        assertTrue(PlayQueue.restored(saved, listOf(null, null)).isEmpty)
    }

    @Test
    fun `an empty queue has nothing to save`() {
        assertNull(PlayQueue().toSaved(0))
    }
}
