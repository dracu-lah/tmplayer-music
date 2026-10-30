package com.tmplayer.music.data

import com.tmplayer.music.data.SupportReminder.Counters
import com.tmplayer.music.data.SupportReminder.DAY_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupportReminderTest {

    private val start = 1_800_000_000_000L

    @Before
    fun setUp() = SupportReminder.resetSession()

    @After
    fun tearDown() {
        SupportReminder.resetSession()
        SupportReminder.enabled = true
    }

    private fun used(tracks: Int = SupportReminder.MIN_TRACKS) = Counters(firstSeenAt = start, completedTracks = tracks)

    @Test
    fun `never on the first day, whatever was played`() {
        assertFalse(SupportReminder.realUse(used(500), start + DAY_MS - 1))
        assertTrue(SupportReminder.realUse(used(), start + DAY_MS))
    }

    @Test
    fun `a few tracks are enough only after some days`() {
        val few = used(SupportReminder.MIN_TRACKS_LATER)
        assertFalse(SupportReminder.realUse(few, start + 2 * DAY_MS))
        assertTrue(SupportReminder.realUse(few, start + SupportReminder.MIN_DAYS * DAY_MS))
        assertFalse(SupportReminder.realUse(used(SupportReminder.MIN_TRACKS_LATER - 1), start + 30 * DAY_MS))
    }

    @Test
    fun `no first launch, or one in the future, is not real use`() {
        assertFalse(SupportReminder.realUse(Counters(completedTracks = 1000), start))
        assertFalse(SupportReminder.realUse(used().copy(firstSeenAt = start + DAY_MS), start))
    }

    @Test
    fun `the window waits two weeks after one, once per run, and never after never`() {
        val now = start + 2 * DAY_MS
        val store = SupportStore(MemoryKeyValues())
        store.noteFirstSeen(start)
        repeat(SupportReminder.MIN_TRACKS) { store.noteCompleted("song$it", start + it) }
        assertTrue(SupportReminder.claim(store, now))
        assertFalse("twice in one run", SupportReminder.claim(store, now + 20 * DAY_MS))
        SupportReminder.resetSession()
        assertFalse("inside the gap", SupportReminder.claim(store, now + SupportReminder.GAP_DAYS * DAY_MS - 1))
        assertTrue(SupportReminder.claim(store, now + SupportReminder.GAP_DAYS * DAY_MS))
        assertEquals(2, store.counters().windows)
        SupportReminder.resetSession()
        store.markNever()
        assertFalse(SupportReminder.claim(store, now + 365 * DAY_MS))
        assertFalse(SupportReminder.nudgeDue(store.counters(), now + 365 * DAY_MS, switchOn = true))
    }

    @Test
    fun `a switched off build never asks, even forced`() {
        SupportReminder.enabled = false
        SupportReminder.forced = true
        assertFalse(SupportReminder.due(used(), start + 30 * DAY_MS))
        assertFalse(SupportReminder.nudgeDue(used(), start + 30 * DAY_MS, switchOn = true))
    }

    @Test
    fun `a forced window leaves the counters alone and happens once`() {
        val store = SupportStore(MemoryKeyValues())
        SupportReminder.forced = true
        assertTrue(SupportReminder.claim(store, start))
        assertEquals(0, store.counters().windows)
        assertFalse(SupportReminder.claim(store, start))
    }

    @Test
    fun `the notification needs the switch, a month between, and a week after the window`() {
        val now = start + 10 * DAY_MS
        assertFalse(SupportReminder.nudgeDue(used(), now, switchOn = false))
        assertTrue(SupportReminder.nudgeDue(used(), now, switchOn = true))
        assertFalse(SupportReminder.nudgeDue(used().copy(nudgedAt = now - 29 * DAY_MS), now, switchOn = true))
        assertTrue(SupportReminder.nudgeDue(used().copy(nudgedAt = now - 30 * DAY_MS), now, switchOn = true))
        assertFalse(SupportReminder.nudgeDue(used().copy(askedAt = now - 6 * DAY_MS), now, switchOn = true))
    }

    @Test
    fun `the star comes first and is not asked again once given`() {
        val store = SupportStore(MemoryKeyValues())
        store.remindersOn = true
        store.noteFirstSeen(start)
        repeat(SupportReminder.MIN_TRACKS) { store.noteCompleted("song$it", start + it) }
        val now = start + 2 * DAY_MS
        assertEquals(SupportReminder.NudgeRung.Star, SupportReminder.claimNudgeRung(store, now))
        assertNull("a second check cannot repeat it", SupportReminder.claimNudgeRung(store, now))
        assertEquals(SupportReminder.NudgeRung.Coffee, SupportReminder.claimNudgeRung(store, now + 30 * DAY_MS))
        store.markStarred()
        assertFalse(SupportReminder.offersStar(store.counters(), SupportReminder.NudgeRung.Coffee))
    }

    @Test
    fun `a replay within the hour counts once`() {
        val store = SupportStore(MemoryKeyValues())
        assertTrue(store.noteCompleted("a", start))
        assertFalse(store.noteCompleted("a", start + 59 * 60 * 1000))
        assertTrue(store.noteCompleted("a", start + 61 * 60 * 1000))
        assertTrue(store.noteCompleted("b", start + 61 * 60 * 1000))
        assertEquals(3, store.counters().completedTracks)
    }

    @Test
    fun `a moment is fresh for half an hour and taken once`() {
        SupportReminder.trackFinished(start)
        assertTrue(SupportReminder.takeMoment(start + SupportReminder.MOMENT_LIFETIME_MS))
        assertFalse(SupportReminder.takeMoment(start + 1))
        SupportReminder.trackFinished(start)
        assertFalse(SupportReminder.takeMoment(start + SupportReminder.MOMENT_LIFETIME_MS + 1))
    }

    @Test
    fun `stored values that do not read count as unset`() {
        val kv = MemoryKeyValues()
        kv.putLong("support_first_seen", -5)
        kv.putLong("support_completed", -1)
        kv.putString("support_recent", "x|y;;|5;a|1|2;b|7")
        val counters = SupportStore(kv).counters()
        assertEquals(0L, counters.firstSeenAt)
        assertEquals(0, counters.completedTracks)
        assertEquals(listOf(SupportReminder.Recent("b", 7)), SupportReminder.decodeRecent(kv.getString("support_recent")))
    }

    @Test
    fun `the recent list round trips and stays bounded`() {
        var recent = emptyList<SupportReminder.Recent>()
        repeat(40) { recent = SupportReminder.stepCompleted(recent, "k$it;|x", start + it).first }
        assertEquals(16, recent.size)
        val sanitised = recent.map { it.copy(key = it.key.replace(Regex("[;|]"), "_")) }
        assertEquals(sanitised, SupportReminder.decodeRecent(SupportReminder.encodeRecent(recent)))
    }
}
