package com.tmplayer.music.connect

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * When the TV's Connect server may be listening. It runs only while the user has it switched on, the
 * app is on screen or something plays, and (the screen being off) a phone is still connected: with
 * nobody connected and the screen off, nothing listens and nothing is advertised.
 */
object ConnectPolicy {
    fun shouldRun(enabled: Boolean, foreground: Boolean, playing: Boolean, screenOn: Boolean, clients: Int): Boolean =
        enabled && (foreground || playing) && (screenOn || clients > 0)
}

/** Which addresses count as "this local network", and which of the device's to listen on. */
object LocalNet {
    /** A private (10/8, 172.16/12, 192.168/16), link local or loopback address: never a public one. */
    fun isLocal(address: InetAddress): Boolean = address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
        // IPv6 unique local (fc00::/7), which Java does not call site local.
        (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)

    /**
     * The device's own address on the local network to listen on: a private IPv4 of an interface that is
     * up, not loopback and not virtual, wired before wireless. Null with no network (the server then does
     * not start). Never a wildcard address: the server is not reachable from the internet or any other
     * interface.
     */
    fun bindAddress(interfaces: () -> List<NetworkInterface> = { runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty() }): InetAddress? {
        val usable = interfaces().filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
        val candidates = usable.flatMap { nic -> nic.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nic to it } }
        return (candidates.firstOrNull { (nic, _) -> nic.name.startsWith("eth") || nic.name.startsWith("en") } ?: candidates.firstOrNull())?.second
    }
}

/** What a Send to the TV turns into on the wire: bounded messages, in an order that keeps the user's order. */
object SendPlan {
    /** The most tracks one Send carries over (the UI says so); more is cut around the start, as a local play does. */
    const val MAX_TOTAL = 5_000

    /**
     * The messages for [how] with [refs]. Play: a window of up to [ConnectLimits.MAX_SEND_ITEMS]
     * around [start] replaces the queue, the rest after it is added behind it. Next: the chunks go
     * last first, so each lands before the one after it. Add: in order.
     */
    fun messages(how: SendHow, refs: List<TrackRef>, start: Int = 0, shuffle: Boolean = false): List<ConnectBody.Send> {
        if (refs.isEmpty()) return emptyList()
        val size = ConnectLimits.MAX_SEND_ITEMS
        val pivot = start.coerceIn(0, refs.lastIndex)
        return when (how) {
            SendHow.Play -> {
                val from = (pivot - size / 4).coerceIn(0, maxOf(0, refs.size - size))
                val window = refs.subList(from, minOf(refs.size, from + size))
                val rest = refs.drop(from + window.size).take(MAX_TOTAL - window.size)
                listOf(ConnectBody.Send(SendHow.Play, window, pivot - from, shuffle)) + rest.chunked(size).map { ConnectBody.Send(SendHow.Add, it) }
            }
            SendHow.Next -> refs.take(MAX_TOTAL).chunked(size).reversed().map { ConnectBody.Send(SendHow.Next, it) }
            SendHow.Add -> refs.take(MAX_TOTAL).chunked(size).map { ConnectBody.Send(SendHow.Add, it) }
        }
    }
}

/** The TV's queue as the phone sees it: a window of it, and the checks that keep a tap from hitting the wrong row. */
object RemoteQueue {
    /** Up to [ConnectLimits.MAX_QUEUE_ITEMS] around [current] of a queue of [total], as the index it starts at and how many. */
    fun window(total: Int, current: Int): Pair<Int, Int> {
        if (total <= 0) return 0 to 0
        val count = minOf(total, ConnectLimits.MAX_QUEUE_ITEMS)
        val from = (current - count / 4).coerceIn(0, total - count)
        return from to count
    }

    /** True when row [index] of the queue ([idAt] reads a row's id, null when out of range) is still the row [id] the phone tapped. */
    fun stillThere(index: Int, id: String, idAt: (Int) -> String?): Boolean = index >= 0 && idAt(index) == id

    /** Where the current row lands after row [removed] goes. */
    fun currentAfterRemove(current: Int, removed: Int): Int = if (removed < current) current - 1 else current

    /** A name for the sent report: titles that are missing, cut to the wire's cap. */
    fun missingNames(refs: List<TrackRef>): List<String> =
        refs.map { it.title.ifBlank { it.songKey }.take(ConnectLimits.MAX_TEXT) }.take(ConnectLimits.MAX_MISSING_NAMES)
}
