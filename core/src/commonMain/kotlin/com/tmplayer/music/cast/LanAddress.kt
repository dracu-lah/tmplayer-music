// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/cast/LanAddress.kt (trimmed).
package com.tmplayer.music.cast

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Which of the phone's addresses the cast server binds to and puts in the URLs. The caller hands
 * over the addresses of the Wi-Fi network only (never mobile data, a VPN or a hotspot), so the
 * server cannot be reached from anywhere else; this picks the private IPv4 one, preferring the
 * subnet the receiver is on.
 */
object LanAddress {

    /** One address of the Wi-Fi interface. */
    data class Candidate(val interfaceName: String, val address: InetAddress, val prefixLength: Int)

    /** Private, link local, unique local or loopback: anything a receiver on the Wi-Fi can be. */
    fun isLocalNetwork(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC) ||
            // 100.64.0.0/10, the carrier grade range some mesh and travel routers hand out.
            (address.address.size == 4 && (address.address[0].toInt() and 0xFF) == 100 &&
                (address.address[1].toInt() and 0xC0) == 64)

    /** Tunnel and VPN interfaces (tun0, ppp0, wg0, tap0, ipsec0, utun3): never an address a receiver on the Wi-Fi can reach. */
    fun isTunnelInterface(name: String): Boolean {
        val n = name.lowercase()
        return TUNNEL_PREFIXES.any { n.startsWith(it) }
    }

    /** The best of [candidates] for reaching [receiver] (null when not known); null when none is usable. */
    fun pick(candidates: List<Candidate>, receiver: InetAddress?): InetAddress? {
        val usable = candidates.filter { !isTunnelInterface(it.interfaceName) && it.address is Inet4Address && isLocalNetwork(it.address) && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
        if (receiver != null) {
            usable.firstOrNull { sameSubnet(it.address, receiver, it.prefixLength) }?.let { return it.address }
        }
        return usable.firstOrNull()?.address
    }

    fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        var bits = prefix.coerceAtMost(x.size * 8)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    private val TUNNEL_PREFIXES = listOf("tun", "tap", "ppp", "wg", "ipsec", "utun")
}
