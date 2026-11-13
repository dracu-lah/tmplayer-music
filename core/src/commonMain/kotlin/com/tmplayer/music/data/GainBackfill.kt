package com.tmplayer.music.data

import com.tmplayer.music.library.ReplayGain
import com.tmplayer.music.library.TagState
import com.tmplayer.music.library.Track

/**
 * ReplayGain for tracks that were indexed before gains were kept (index version 5): their tags are
 * marked read, but their gain column is empty. Nothing scans for them. When one of them starts
 * playing, [TagEnricher] reads its tag head once and stores what it finds, or the "checked, none"
 * mark ([ReplayGain.CHECKED]), so a track is read for this at most once in its life. Pure decisions
 * here, so the rules are unit tests.
 */
object GainBackfill {
    /** Tracks remembered as tried this session; the oldest is forgotten past this. */
    const val MAX_TRIED = 500

    /** True when [track] was read before gains were kept: tags read, gain never looked at. */
    fun needs(track: Track?): Boolean =
        track != null && track.tagState == TagState.Read && track.replayGain == ReplayGain.NONE

    /**
     * Whether a backfill may fetch now: the policy allows it (not paused, not offline, and on mobile
     * data only after the user's yes to reading there) and the connection is up.
     */
    fun allowed(policy: ReadPolicy, online: Boolean): Boolean = online && policy.onDemand && policy.gainBackfill
}
