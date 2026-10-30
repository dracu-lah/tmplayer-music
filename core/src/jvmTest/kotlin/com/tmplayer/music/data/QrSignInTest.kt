// The QR clock cases follow TMPlayer's (GPL-3.0) core/src/jvmTest/kotlin/com/tmplayer/data/SignInStepsTest.kt.
package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrSignInTest {

    // The reducer.

    @Test
    fun `a new link from TDLib replaces the code on screen`() {
        val first = AuthReducer.reduce(AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=One"))
        val second = AuthReducer.reduce(AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=Two"))
        assertEquals(AuthState.Qr("tg://login?token=One"), first.state)
        assertEquals(AuthState.Qr("tg://login?token=Two"), second.state)
        assertEquals(AuthAction.None, second.action)
    }

    @Test
    fun `a blank or absurd link fails with Try again rather than an empty plate`() {
        val blank = AuthReducer.reduce(AuthorizationStateWaitOtherDeviceConfirmation(""))
        val huge = AuthReducer.reduce(AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=" + "A".repeat(5_000)))
        assertEquals(AuthState.Failed(L.errorsUnsupportedSignInStep), blank.state)
        assertEquals(AuthState.Failed(L.errorsUnsupportedSignInStep), huge.state)
    }

    // The clock.

    @Test
    fun `a code is fresh, then about to change, then late, then expired`() {
        val life = QrClock.lifetime(null)
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, life)
        assertEquals(QrPhase.Fresh, QrClock.phase(0, life))
        assertEquals(QrPhase.ExpiringSoon, QrClock.phase(life - QrClock.SOON_MS, life))
        assertEquals(QrPhase.Renewing, QrClock.phase(life + 1, life))
        assertEquals(QrPhase.Expired, QrClock.phase(QrClock.STALE_MS, life))
        assertEquals(1f, QrClock.remaining(0, life))
        assertEquals(0f, QrClock.remaining(life * 2, life))
    }

    @Test
    fun `the lifetime follows the gap TDLib actually renews at, within reason`() {
        assertEquals(25_000L, QrClock.lifetime(25_000L))
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, QrClock.lifetime(1_000L))
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, QrClock.lifetime(120_000L))
    }

    // The refresh policy.

    @Test
    fun `a new link restarts the clock and the same link again keeps its time`() {
        val policy = QrRefreshPolicy()
        assertEquals(1_000L, policy.onLink("a", 1_000L))
        assertEquals(1_000L, policy.onLink("a", 20_000L)) // a rotation, not a new code
        assertEquals(QrPhase.Expired, policy.phase(1_000L + QrClock.STALE_MS))
        assertEquals(30_000L, policy.onLink("b", 30_000L))
        assertEquals("b", policy.link)
        assertEquals(QrPhase.Fresh, policy.phase(31_000L))
        // TDLib renewed it after 29 seconds, which is the lifetime drawn from now on.
        assertEquals(29_000L, policy.lifetime())
    }

    @Test
    fun `an expired code is asked again for once, not on every tick`() {
        val policy = QrRefreshPolicy()
        policy.onLink("a", 0L)
        assertEquals(QrExpiry.Refresh, policy.onExpired("a"))
        assertEquals(QrExpiry.Wait, policy.onExpired("a"))
        assertEquals(QrExpiry.Wait, policy.onExpired("not on screen"))
    }

    @Test
    fun `codes that keep running out stop being asked for after a few`() {
        val policy = QrRefreshPolicy(maxAuto = 3)
        var now = 0L
        for (i in 1..3) {
            policy.onLink("code$i", now)
            now += QrClock.STALE_MS
            assertEquals(QrExpiry.Refresh, policy.onExpired("code$i"))
        }
        policy.onLink("code4", now)
        assertTrue(policy.exhausted)
        assertEquals(QrExpiry.GiveUp, policy.onExpired("code4"))
        assertEquals(QrExpiry.GiveUp, policy.onExpired("code4"))
        // A press always goes, and starts the count again.
        policy.onManualRefresh()
        assertFalse(policy.exhausted)
        policy.onLink("code5", now)
        assertEquals(QrExpiry.Refresh, policy.onExpired("code5"))
    }

    @Test
    fun `a renewal of TDLib's own clears the count, and one of ours does not`() {
        val policy = QrRefreshPolicy(maxAuto = 2)
        policy.onLink("a", 0L)
        assertEquals(QrExpiry.Refresh, policy.onExpired("a"))
        policy.onLink("b", 50_000L) // the code our refresh asked for
        assertEquals(1, policy.stale)
        policy.onLink("c", 80_000L) // TDLib renewed b by itself: the line works
        assertEquals(0, policy.stale)
    }

    @Test
    fun `leaving the QR code forgets everything`() {
        val policy = QrRefreshPolicy(maxAuto = 1)
        policy.onLink("a", 0L)
        policy.onExpired("a")
        policy.reset()
        assertNull(policy.link)
        assertFalse(policy.exhausted)
        assertEquals(QrPhase.Fresh, policy.phase(1_000_000L))
    }

    // The scan hold.

    @Test
    fun `a scan that signs in holds the code up marked scanned`() {
        val hold = QrScanHold()
        hold.observe(AuthState.Qr("a"))
        hold.observe(AuthState.Ready)
        assertEquals(AuthState.Qr("a", scanned = true), hold.shown(AuthState.Ready, endedId = 0))
        // The same state again, as every recomposition passes it, keeps the one hold.
        val id = hold.holdId
        hold.observe(AuthState.Ready)
        assertEquals(id, hold.holdId)
        // Once ended, the real state shows.
        assertEquals(AuthState.Ready, hold.shown(AuthState.Ready, endedId = id))
    }

    @Test
    fun `a scan of an account with a password holds, then the password step shows`() {
        val hold = QrScanHold()
        val password = AuthState.Password(hint = "")
        hold.observe(AuthState.Qr("a"))
        hold.observe(password)
        assertEquals(AuthState.Qr("a", scanned = true), hold.shown(password, endedId = 0))
        assertEquals(password, hold.shown(password, endedId = hold.holdId))
        // A wrong password marks the step again: no second hold.
        hold.observe(password.copy(wrong = true))
        assertEquals(password.copy(wrong = true), hold.shown(password.copy(wrong = true), endedId = hold.holdId))
    }

    @Test
    fun `leaving the code any other way goes through at once`() {
        for (next in listOf(AuthState.Connecting, AuthState.Phone(), AuthState.Failed("x"), AuthState.ChooseMethod)) {
            val hold = QrScanHold()
            hold.observe(AuthState.Qr("a"))
            hold.observe(next)
            assertEquals(next, hold.shown(next, endedId = 0))
            // And a sign in that later completes without a code on screen is not a scan.
            hold.observe(AuthState.Ready)
            assertEquals(AuthState.Ready, hold.shown(AuthState.Ready, endedId = 0))
        }
    }

    @Test
    fun `a new code after a declined scan replaces the old one and is what a later scan holds`() {
        val hold = QrScanHold()
        hold.observe(AuthState.Qr("a"))
        hold.observe(AuthState.Qr("b"))
        hold.observe(AuthState.Ready)
        assertEquals(AuthState.Qr("b", scanned = true), hold.shown(AuthState.Ready, endedId = 0))
    }
}
