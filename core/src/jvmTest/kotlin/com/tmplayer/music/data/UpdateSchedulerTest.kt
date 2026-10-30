// Adapted from TMPlayer (GPL-3.0), core/src/jvmTest/kotlin/com/tmplayer/data/UpdateSchedulerTest.kt
// and the restart half of UpdateInstallTest.kt.
package com.tmplayer.music.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the update check runs, when the popup opens, and what is said after an update. */
class UpdateSchedulerTest {

    private class FakePrefs : UpdatePrefs {
        var checkOnOpen = true
        var last = 0L
        var skipped = ""
        var snoozed = 0L
        var shown = ""
        override suspend fun checkOnOpen() = checkOnOpen
        override suspend fun lastCheck() = last
        override suspend fun setLastCheck(at: Long) { last = at }
        override suspend fun skippedVersion() = skipped
        override suspend fun setSkippedVersion(version: String) { skipped = version }
        override suspend fun snoozedUntil() = snoozed
        override suspend fun setSnoozedUntil(at: Long) { snoozed = at }
        override suspend fun popupShownFor() = shown
        override suspend fun setPopupShownFor(version: String) { shown = version }
    }

    private val prefs = FakePrefs()
    private var clock = 100 * HOUR
    private var answers = true
    private val checks = mutableListOf<Pair<Boolean, String>>()
    private var skippedState = 0

    private fun scheduler() = UpdateScheduler(
        prefs = prefs,
        now = { clock },
        check = { quiet, skipped ->
            checks += quiet to skipped
            answers
        },
        onSkip = { skippedState++ },
    )

    @Test
    fun `the first opening checks, and another within six hours does not`() = runTest {
        assertTrue(scheduler().checkIfDue())
        assertEquals(clock, prefs.last)
        clock += 5 * HOUR
        assertFalse(scheduler().checkIfDue())
        assertEquals(1, checks.size)
        clock += HOUR
        assertTrue(scheduler().checkIfDue())
        assertEquals(2, checks.size)
    }

    @Test
    fun `a failed check writes nothing, so the next opening tries again`() = runTest {
        answers = false
        assertFalse(scheduler().checkIfDue())
        assertEquals(0L, prefs.last)
        clock += 1
        scheduler().checkIfDue()
        assertEquals(2, checks.size)
    }

    @Test
    fun `the switch off stops the check on opening but not the button`() = runTest {
        prefs.checkOnOpen = false
        scheduler().checkIfDue()
        assertTrue(checks.isEmpty())
        scheduler().checkNow()
        assertEquals(listOf(false to ""), checks)
    }

    @Test
    fun `the button ignores the throttle and passes the skipped version to be re-offered`() = runTest {
        prefs.skipped = "0.2.0"
        scheduler().checkIfDue()
        scheduler().checkNow()
        assertEquals(listOf(true to "0.2.0", false to "0.2.0"), checks)
    }

    @Test
    fun `a clock set back before the last check counts as due`() = runTest {
        prefs.last = clock + 10 * HOUR
        assertTrue(scheduler().checkIfDue())
    }

    @Test
    fun `the popup opens once per version`() = runTest {
        val s = scheduler()
        assertTrue(s.shouldPopUp("0.2.0"))
        s.popupShown("0.2.0")
        assertFalse(s.shouldPopUp("0.2.0"))
        assertTrue(s.shouldPopUp("0.3.0"))
    }

    @Test
    fun `remind me later brings the popup back after a day`() = runTest {
        val s = scheduler()
        s.popupShown("0.2.0")
        s.remindLater()
        assertFalse(s.shouldPopUp("0.2.0"))
        clock += UpdateScheduler.SNOOZE_MS - 1
        assertFalse(s.shouldPopUp("0.2.0"))
        clock += 1
        assertTrue(s.shouldPopUp("0.2.0"))
    }

    @Test
    fun `a skipped version never pops up, a newer one does`() = runTest {
        val s = scheduler()
        s.skip("0.2.0")
        assertEquals(1, skippedState)
        assertFalse(s.shouldPopUp("0.2.0"))
        assertTrue(s.shouldPopUp("0.2.1"))
        s.checkIfDue()
        assertEquals("0.2.0", checks.single().second)
    }

    // ---- after the restart -------------------------------------------------------------------

    private val day = 24 * 60 * 60_000L

    @Test
    fun `the first launch of the new version says so once`() {
        val stored = UpdateRestart.Pending("0.2.0", 1_000L, session = true, foreground = true).encode()
        val launch = UpdateRestart.onLaunch(stored, installed = "0.2.0", now = 2_000L)
        assertEquals("0.2.0", launch.updatedTo)
        assertTrue(launch.clear)
        assertEquals(UpdateRestart.Launch(null, clear = false), UpdateRestart.onLaunch("", "0.2.0", 3_000L))
    }

    @Test
    fun `a newer version than the one installed counts too`() {
        val stored = UpdateRestart.Pending("0.2.0-beta.1", 0L, session = false, foreground = true).encode()
        assertEquals("0.2.0-beta.1", UpdateRestart.onLaunch(stored, installed = "0.2.0", now = 1L).updatedTo)
    }

    @Test
    fun `an install still waiting is kept, a stale one forgotten`() {
        val stored = UpdateRestart.Pending("0.2.0", 10 * day, session = false, foreground = true).encode()
        assertEquals(UpdateRestart.Launch(null, clear = false), UpdateRestart.onLaunch(stored, "0.1.0", 10 * day + 60_000))
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch(stored, "0.1.0", 10 * day + UpdateRestart.STALE_MS + 1))
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch(stored, "0.1.0", day))
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch("garbage", "0.1.0", day))
    }

    @Test
    fun `a pending install is written and read back`() {
        val pending = UpdateRestart.Pending("0.2.0", 123L, session = true, foreground = false)
        assertEquals(pending, UpdateRestart.Pending.decode(pending.encode()))
        assertNull(UpdateRestart.Pending.decode("0.2.0|123|1"))
        assertNull(UpdateRestart.Pending.decode("|123|1|1"))
    }

    @Test
    fun `the notification after the replace is for a session install of this version`() {
        val session = UpdateRestart.Pending("0.2.0", 0L, session = true, foreground = true).encode()
        val dialog = UpdateRestart.Pending("0.2.0", 0L, session = false, foreground = true).encode()
        assertTrue(UpdateRestart.notifyAfterReplace(session, "0.2.0"))
        assertFalse(UpdateRestart.notifyAfterReplace(dialog, "0.2.0"))
        assertFalse(UpdateRestart.notifyAfterReplace(session, "0.1.1"))
        assertFalse(UpdateRestart.notifyAfterReplace("", "0.2.0"))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
