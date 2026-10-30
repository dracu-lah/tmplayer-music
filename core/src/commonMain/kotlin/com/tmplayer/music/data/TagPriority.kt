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
 * - The user's Pause stops the background: the documents and the trickle. On-demand reads (the rows
 *   on screen, the playing track and the next few) go on while paused: they are few, small and
 *   bounded, and without them a paused library shows initials instead of covers.
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
        if (net == NetState.Offline) return ReadPolicy(onDemand = false, documents = false, trickle = false, documentsCap = 0)
        if (paused) return ReadPolicy(onDemand = true, documents = false, trickle = false, documentsCap = 0)
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

/**
 * How the background reading (the documents and the trickle) is paced, so it stays a gentle
 * trickle: on a phone it read about one file a second yet held about 40% of a core (TDLib's receive
 * thread, the index writes, the parsing). After each background batch the reader rests:
 *
 * - at least as long as the batch took, so it works at most about half of the time;
 * - long enough that it averages at most one file per [MS_PER_FILE] (under one a second; per
 *   [LOW_RAM_MS_PER_FILE] on a low memory device);
 * - never less than the usual gap between batches, never more than [MAX_REST_MS].
 *
 * Reads for the rows on screen and the tracks about to play are not paced: a request for them ends
 * the rest at once.
 */
object TricklePacing {
    /**
     * At most 0.8 files a second (1.25 s each). Measured on the POCO M2 Pro (2026-10-30): about 1.07
     * files a second cost 17 to 21% of a core, nearly all TDLib's sender and receiver threads.
     */
    const val MS_PER_FILE = 1_250L
    const val LOW_RAM_MS_PER_FILE = 2_500L
    const val MAX_REST_MS = 60_000L

    /** The rest after a background batch of [files] that took [batchMs]. */
    fun restAfter(batchMs: Long, files: Int, lowRam: Boolean, minGapMs: Long): Long {
        val took = batchMs.coerceAtLeast(0L)
        val perFile = if (lowRam) LOW_RAM_MS_PER_FILE else MS_PER_FILE
        val forRate = files.coerceAtLeast(0) * perFile - took
        return maxOf(minGapMs, took, forRate).coerceIn(0L, MAX_REST_MS)
    }
}
