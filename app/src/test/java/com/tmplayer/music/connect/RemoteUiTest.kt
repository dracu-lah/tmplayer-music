package com.tmplayer.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteUiTest {
    private fun now(playing: Boolean, buffering: Boolean = false, position: Long = 10_000, duration: Long = 200_000) =
        ConnectBody.NowPlaying("t", "a", "b", duration, position, playing, buffering, false, 0, 0, 1, 50, false, "k", true)

    @Test
    fun thePositionMovesOnWhileTheTvPlaysAndStaysWhenItDoesNot() {
        val ui = RemoteUi(now = now(playing = true), receivedAt = 1_000)
        assertEquals(15_000L, ui.positionNow(at = 6_000))
        assertEquals(10_000L, RemoteUi(now = now(playing = false), receivedAt = 1_000).positionNow(at = 6_000))
        // Waiting for data: the bar does not run ahead of the sound.
        assertEquals(10_000L, RemoteUi(now = now(playing = true, buffering = true), receivedAt = 1_000).positionNow(at = 6_000))
    }

    @Test
    fun thePositionNeverPassesTheLengthOrGoesBelowZero() {
        assertEquals(200_000L, RemoteUi(now = now(playing = true, position = 199_000), receivedAt = 0).positionNow(at = 60_000))
        assertEquals(0L, RemoteUi().positionNow(at = 5))
    }

    @Test
    fun aTypedAddressNeedsAHostAndAPort() {
        assertEquals("192.168.1.20" to 41000, PhoneRemote.parseAddress(" 192.168.1.20:41000 "))
        assertEquals("tv.local" to 9, PhoneRemote.parseAddress("http://tv.local:9/"))
        assertNull(PhoneRemote.parseAddress("192.168.1.20"))
        assertNull(PhoneRemote.parseAddress("192.168.1.20:0"))
        assertNull(PhoneRemote.parseAddress("192.168.1.20:99999"))
        assertNull(PhoneRemote.parseAddress(":80"))
        assertNull(PhoneRemote.parseAddress(""))
    }
}
