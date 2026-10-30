package com.tmplayer.music.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "cannot reach Telegram" hint on the sign in screen, against a clock the test moves by hand. */
class AuthStallTest {

    private val auth = MutableStateFlow<AuthState>(AuthState.Connecting)
    private val attempt = MutableStateFlow(0)
    private val stalled = MutableStateFlow(false)

    @Test
    fun `connecting for longer than the timeout is a stall`() = runTest {
        val watch = launch { AuthStall.watch(auth, stalled, TIMEOUT) }
        advanceTimeBy(TIMEOUT - 1)
        runCurrent()
        assertFalse(stalled.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(stalled.value)
        watch.cancel()
    }

    @Test
    fun `any other state clears the stall and stops the count`() = runTest {
        val watch = launch { AuthStall.watch(auth, stalled, TIMEOUT) }
        advanceTimeBy(TIMEOUT + 1)
        runCurrent()
        assertTrue(stalled.value)
        auth.value = AuthState.Qr("tg://login?token=AbCdEf")
        runCurrent()
        assertFalse(stalled.value)
        advanceTimeBy(TIMEOUT * 3)
        runCurrent()
        assertFalse(stalled.value)
        watch.cancel()
    }

    @Test
    fun `a failure is not a stall, it has its own screen`() = runTest {
        auth.value = AuthState.Failed("no library")
        val watch = launch { AuthStall.watch(auth, stalled, TIMEOUT) }
        advanceTimeBy(TIMEOUT * 2)
        runCurrent()
        assertFalse(stalled.value)
        watch.cancel()
    }

    @Test
    fun `a retry starts the count again though the state is still connecting`() = runTest {
        val watch = launch {
            AuthStall.watch(combine(auth, attempt) { state, _ -> state }, stalled, TIMEOUT)
        }
        advanceTimeBy(TIMEOUT + 1)
        runCurrent()
        assertTrue(stalled.value)
        attempt.value++
        runCurrent()
        assertFalse(stalled.value)
        advanceTimeBy(TIMEOUT - 1)
        runCurrent()
        assertFalse(stalled.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(stalled.value)
        watch.cancel()
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
