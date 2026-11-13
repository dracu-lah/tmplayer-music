package com.tmplayer.music.cast

import com.tmplayer.music.i18n.L

/** What to do about a track the receiver could not play. */
enum class CastFailAction {
    /** Already counted (the receiver repeats its status): nothing. */
    Ignore,

    /** Skip to the next track and say so at the end. */
    Skip,

    /** The receiver never asked this phone for anything: it cannot reach the phone's server. */
    Unreachable,

    /** Too many tracks in a row failed: stop casting instead of walking through the whole queue. */
    GiveUp,
}

/**
 * What the phone knows about how the receiver is doing with what it was sent, pure and clocked by
 * the caller. Two questions:
 *
 *  - Can the receiver reach the phone's server at all? Some Wi-Fi networks stop devices talking to
 *    each other (client isolation): the receiver then waits on an address it can never open, and
 *    nothing says so. The server counts the requests it gets; if the user wants playback and none
 *    has come within [unreachableAfterMs], the receiver cannot reach it ([reachable]).
 *  - A track the receiver cannot play is skipped, counted, and told at the end ([summary]); a run of
 *    [giveUpAfter] failures in a row ends the cast ([failed]).
 */
class CastHealth(
    private val unreachableAfterMs: Long = UNREACHABLE_AFTER_MS,
    private val giveUpAfter: Int = GIVE_UP_AFTER,
) {
    private var wantedAt = -1L
    private var reached = false
    private var inARow = 0
    private var lastFailed: String? = null
    private val failedTitles = LinkedHashMap<String, String>()

    /** True once the server has been asked for anything, which proves the receiver reaches it. */
    val isReached: Boolean get() = reached

    /**
     * Called each time the phone looks, with [served] (the requests the server has had in all) and
     * whether the user wants [playing] now. True while it is still fine or not yet known; false when the
     * receiver has had [unreachableAfterMs] of wanted playback and asked for nothing. Paused time does
     * not count: a paused receiver may not have fetched anything yet.
     */
    fun reachable(nowMs: Long, served: Int, playing: Boolean): Boolean {
        if (served > 0) reached = true
        if (reached) return true
        if (!playing) {
            wantedAt = -1L
            return true
        }
        if (wantedAt < 0) wantedAt = nowMs
        return nowMs - wantedAt < unreachableAfterMs
    }

    /** The receiver is loading or playing something: a later failure is a new one. [playing] clears the run of failures. */
    fun active(playing: Boolean) {
        lastFailed = null
        if (playing) inARow = 0
    }

    /** The receiver reports that it could not play [id] ([title] for the summary). */
    fun failed(id: String, title: String, served: Int): CastFailAction {
        if (served > 0) reached = true
        if (id == lastFailed) return CastFailAction.Ignore
        lastFailed = id
        if (!reached) return CastFailAction.Unreachable
        failedTitles.putIfAbsent(id, title)
        inARow++
        return if (inARow >= giveUpAfter) CastFailAction.GiveUp else CastFailAction.Skip
    }

    /** How many different tracks failed. */
    val failedCount: Int get() = failedTitles.size

    /** The sentence for the tracks that failed, or null when none did. */
    fun summary(): String? = when (failedTitles.size) {
        0 -> null
        1 -> L.castFailedOne(failedTitles.values.first().ifBlank { L.castUntitled })
        else -> L.castFailedMany(failedTitles.size)
    }

    companion object {
        /** How long the user may want playback with no request from the receiver before it is called unreachable. */
        const val UNREACHABLE_AFTER_MS = 20_000L

        /** This many failures in a row end the cast. */
        const val GIVE_UP_AFTER = 3
    }
}
