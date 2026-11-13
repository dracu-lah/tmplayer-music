package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanAddressTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)
    private fun candidate(text: String, prefix: Int = 24) = LanAddress.Candidate("wlan0", ip(text), prefix)

    @Test
    fun `private ranges are the local network, public ones are not`() {
        assertTrue(LanAddress.isLocalNetwork(ip("192.168.1.2")))
        assertTrue(LanAddress.isLocalNetwork(ip("10.0.0.2")))
        assertTrue(LanAddress.isLocalNetwork(ip("172.16.5.5")))
        assertTrue(LanAddress.isLocalNetwork(ip("100.64.1.1")))
        assertTrue(LanAddress.isLocalNetwork(ip("fd00::1")))
        assertFalse(LanAddress.isLocalNetwork(ip("8.8.8.8")))
        assertFalse(LanAddress.isLocalNetwork(ip("100.128.0.1")))
    }

    @Test
    fun `the receiver's subnet wins`() {
        val picked = LanAddress.pick(listOf(candidate("10.0.0.5"), candidate("192.168.1.5")), ip("192.168.1.40"))
        assertEquals(ip("192.168.1.5"), picked)
    }

    @Test
    fun `without a receiver the first private ipv4 address is used`() {
        assertEquals(ip("192.168.1.5"), LanAddress.pick(listOf(candidate("fe80::1", 64), candidate("192.168.1.5")), null))
    }

    @Test
    fun `a public or ipv6 only network has no address to serve on`() {
        assertNull(LanAddress.pick(listOf(candidate("8.8.8.8"), candidate("fe80::1", 64)), null))
        assertNull(LanAddress.pick(emptyList(), ip("192.168.1.2")))
    }
}
