package com.tmplayer.music.data

import com.tmplayer.music.library.Track

/** The connection the phone uses now, as far as Android says. Unknown counts as [Metered]. */
enum class NetState { Unmetered, Metered, Offline }

/**
 * Which kinds of tag reads may run now (`plan/MVP-REVISION.md` 7, "tag reading respects mobile
 * data"). [onDemand] covers the rows on screen and the tracks about to play; [documents] the audio
 * sent as documents whose length is still unknown; [trickle] the rest of the library, a little at a
 * time. [documentsCap] bounds the documents read per app session (on mobile data without the
 * user's yes); [Int.MAX_VALUE] is no bound.
 */
data class ReadPolicy(
    val onDemand: Boolean = true,
    val documents: Boolean = true,
    val trickle: Boolean = true,
    val documentsCap: Int = Int.MAX_VALUE,
) {
    companion object {
        /** Everything, as before the policy existed; what tests and a desktop build use. */
        val ALL = ReadPolicy()
    }
}

/**
 * Turns the device's state into a [ReadPolicy]. Plain logic, so the Wi-Fi and mobile data rules are
 * tested without a phone:
 *
 * - The user's Pause stops every read.
 * - On-demand reads (on screen, about to play) are small and run on any connection.
 * - Documents with no known length run on Wi-Fi, and on mobile data up to [METERED_DOCUMENTS] files
 *   per app session unless the user said yes to mobile data.
 * - The background trickle runs only on an unmetered connection (or on mobile data after the user's
 *   yes), and only while the app is on screen or playing.
 * - Offline nothing is read; the reader waits for the connection as it always did, and starts again
 *   when the connection comes back.
 */
object TagPolicy {
    /** Documents read on mobile data per session without the user's yes: their heads are small, but there can be thousands. */
    const val METERED_DOCUMENTS = 60

    fun policy(net: NetState, mobileAllowed: Boolean, active: Boolean, paused: Boolean): ReadPolicy {
        if (paused || net == NetState.Offline) return ReadPolicy(onDemand = false, documents = false, trickle = false, documentsCap = 0)
        val cheap = net == NetState.Unmetered || mobileAllowed
        return ReadPolicy(
            onDemand = true,
            documents = true,
            trickle = cheap && active,
            documentsCap = if (cheap) Int.MAX_VALUE else METERED_DOCUMENTS,
        )
    }

    /**
     * True when the one time "Read track details on mobile data too?" question should be asked: on
     * mobile data, never asked before, with files still waiting that the trickle would read on Wi-Fi.
     */
    fun shouldAsk(net: NetState, asked: Boolean, pending: Int): Boolean = net == NetState.Metered && !asked && pending > 0
}

/**
 * The order the tag reader takes files in: (a) the rows on screen, (b) the playing track and the
 * next few in the queue, (c) audio documents whose length is unknown, (d) the rest. A kind the
 * [ReadPolicy] does not allow is left out; a file is taken once, in its first kind; at most [batch].
 */
object TagPriority {
    fun plan(
        visible: List<Track>,
        upcoming: List<Track>,
        documents: List<Track>,
        rest: List<Track>,
        policy: ReadPolicy,
        batch: Int,
        documentsLeft: Int = policy.documentsCap,
    ): List<Track> {
        val out = LinkedHashMap<Any, Track>()
        fun take(list: List<Track>, cap: Int = Int.MAX_VALUE) {
            var n = 0
            for (t in list) {
                if (out.size >= batch || n >= cap) return
                if (out.putIfAbsent(t.id, t) == null) n++
            }
        }
        if (policy.onDemand) {
            take(visible)
            take(upcoming)
        }
        if (policy.documents && documentsLeft > 0) take(documents, documentsLeft)
        if (policy.trickle) take(rest)
        return out.values.toList()
    }
}
