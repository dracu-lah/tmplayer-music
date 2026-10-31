package com.tmplayer.music.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat's audio count for the picker: Telegram's count taken when above 0, a 0 or -1 checked with the
 * search itself (a supergroup or channel whose cached count is stale), audio sent as documents counted,
 * flood waits waited out, and an unknown count never shown as 0.
 */
class AudioCounterTest {

    private class FakeCounts : CountCalls {
        val told = HashMap<Long, CountAnswer<Int>>()
        val audio = HashMap<Long, CountAnswer<SearchProbe>>()
        val docs = HashMap<Long, CountAnswer<SearchProbe>>()

        /** Answers given once before the scripted one, per call kind: a flood wait, for one. */
        val firstTold = HashMap<Long, ArrayDeque<CountAnswer<Int>>>()
        val calls = ArrayList<String>()

        override suspend fun audioMessages(chatId: Long): CountAnswer<Int> {
            calls += "count $chatId"
            firstTold[chatId]?.removeFirstOrNull()?.let { return it }
            return told[chatId] ?: CountAnswer.Failed("CHAT_NOT_FOUND")
        }

        override suspend fun probe(chatId: Long, pass: ScanPass, limit: Int): CountAnswer<SearchProbe> {
            calls += "$pass $chatId"
            val map = if (pass == ScanPass.Audio) audio else docs
            return map[chatId] ?: CountAnswer.Value(SearchProbe(0, 0, 0, 0))
        }
    }

    private fun probe(total: Int, messages: Int, audio: Int = messages, next: Long = 0) =
        CountAnswer.Value(SearchProbe(total, messages, audio, next))

    private fun counter(f: FakeCounts, waits: MutableList<Int> = ArrayList(), generation: () -> Long? = { 1L }) =
        AudioCounter(calls = { f }, generation = generation, wait = { waits += it })

    @Test
    fun telegramsCountIsTakenWhenAboveZeroWithNoAudioSearch() = runTest {
        val f = FakeCounts().apply { told[1] = CountAnswer.Value(240) }
        assertEquals(ChatCount.Known(240, 0, more = false), counter(f).count(1))
        assertTrue("Audio 1" !in f.calls)
    }

    @Test
    fun aSupergroupWhoseCountSaysZeroButHoldsAudioIsCountedFromTheSearch() = runTest {
        // TDLib answered its own stale figure for the group: 0, though the search finds audio.
        val f = FakeCounts().apply {
            told[-1001L] = CountAnswer.Value(0)
            audio[-1001L] = probe(total = 1_850, messages = 1)
        }
        assertEquals(ChatCount.Known(1_850, 0, more = false), counter(f).count(-1001))
    }

    @Test
    fun anUnknownCountMinusOneIsCheckedNotShownAsZero() = runTest {
        val f = FakeCounts().apply {
            told[-1002L] = CountAnswer.Value(-1)
            audio[-1002L] = probe(total = -1, messages = 1)
        }
        assertEquals(ChatCount.Known(1, 0, more = false), counter(f).count(-1002))
    }

    @Test
    fun zeroIsZeroOnlyWhenTheSearchesFindNothing() = runTest {
        val f = FakeCounts().apply { told[5] = CountAnswer.Value(0) }
        assertEquals(ChatCount.Known(0, 0, more = false), counter(f).count(5))
    }

    @Test
    fun anEmptyPageWithACursorOnIsNotNoAudio() = runTest {
        val f = FakeCounts().apply {
            told[6] = CountAnswer.Value(0)
            audio[6] = probe(total = 0, messages = 0, next = 500)
        }
        // The audio half is not known and no audio documents were seen: not known, never 0.
        assertEquals(ChatCount.Unknown, counter(f).count(6))
    }

    @Test
    fun audioSentAsDocumentsIsCountedAndSaysAtLeastWhenThereAreOlderDocuments() = runTest {
        val f = FakeCounts().apply {
            told[7] = CountAnswer.Value(0)
            docs[7] = probe(total = 400, messages = 50, audio = 12, next = 9_000)
        }
        val count = counter(f).count(7)
        assertEquals(ChatCount.Known(0, 12, more = true), count)
        assertEquals(12, (count as ChatCount.Known).total)
        f.docs[8] = probe(total = 3, messages = 3, audio = 3, next = 0)
        f.told[8] = CountAnswer.Value(10)
        assertEquals(ChatCount.Known(10, 3, more = false), counter(f).count(8))
    }

    @Test
    fun aFailedCountIsUnknownAndAskedAgainNextTime() = runTest {
        val f = FakeCounts().apply {
            audio[9] = CountAnswer.Failed("NETWORK_ERROR")
            docs[9] = CountAnswer.Failed("NETWORK_ERROR")
        }
        val c = counter(f)
        assertEquals(ChatCount.Unknown, c.count(9))
        assertNull(c.cached(9))
        f.told[9] = CountAnswer.Value(3)
        f.docs.remove(9)
        assertEquals(ChatCount.Known(3, 0, more = false), c.count(9))
    }

    @Test
    fun aFoundCountIsRememberedForTheSession() = runTest {
        val f = FakeCounts().apply { told[1] = CountAnswer.Value(4) }
        var session = 1L
        val c = counter(f, generation = { session })
        c.count(1)
        c.count(1)
        assertEquals(1, f.calls.count { it == "count 1" })
        // Another account signed in: the count is asked again.
        session = 2L
        assertNull(c.cached(1))
        c.count(1)
        assertEquals(2, f.calls.count { it == "count 1" })
    }

    @Test
    fun aShortFloodWaitIsWaitedOutByThatCount() = runTest {
        val f = FakeCounts().apply {
            told[1] = CountAnswer.Value(20)
            firstTold[1] = ArrayDeque(listOf(CountAnswer.FloodWait(7)))
        }
        val waits = ArrayList<Int>()
        assertEquals(ChatCount.Known(20, 0, more = false), counter(f, waits).count(1))
        assertEquals(listOf(7), waits)
    }

    @Test
    fun aLongOrEndlessFloodWaitLeavesTheCountUnknownNotZero() = runTest {
        val f = FakeCounts().apply {
            told[1] = CountAnswer.FloodWait(3_600)
            audio[1] = CountAnswer.FloodWait(3_600)
            docs[1] = CountAnswer.FloodWait(5)
        }
        val waits = ArrayList<Int>()
        assertEquals(ChatCount.Unknown, counter(f, waits).count(1))
        // The short one was waited out a bounded number of times, the long ones not at all.
        assertEquals(List(AudioCounter.TRIES) { 5 }, waits)
    }

    @Test
    fun aCallThatThrowsIsUnknown() = runTest {
        val f = object : CountCalls {
            override suspend fun audioMessages(chatId: Long): CountAnswer<Int> = throw IllegalStateException("closed")
            override suspend fun probe(chatId: Long, pass: ScanPass, limit: Int): CountAnswer<SearchProbe> = throw IllegalStateException("closed")
        }
        assertEquals(ChatCount.Unknown, AudioCounter(calls = { f }).count(1))
    }

    @Test
    fun onlyAFewChatsAreCountedAtOnce() = runTest {
        var running = 0
        var most = 0
        val f = object : CountCalls {
            override suspend fun audioMessages(chatId: Long): CountAnswer<Int> {
                running++
                most = maxOf(most, running)
                kotlinx.coroutines.delay(100)
                running--
                return CountAnswer.Value(1)
            }
            override suspend fun probe(chatId: Long, pass: ScanPass, limit: Int): CountAnswer<SearchProbe> =
                CountAnswer.Value(SearchProbe(0, 0, 0, 0))
        }
        val c = AudioCounter(calls = { f })
        val all = (1L..20L).map { id -> async { c.count(id) } }
        runCurrent()
        all.awaitAll()
        assertEquals(AudioCounter.PARALLEL, most)
    }
}
