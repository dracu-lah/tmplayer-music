package com.tmplayer.music.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Asking for support, as TMPlayer asks (its `SupportReminder`, adapted from videos to tracks): the
 * rules for the Buy Me a Coffee window and the rare notification that points at it. Nothing in the
 * app is ever locked behind any of it.
 *
 * Both wait for real use, never the first day, and both stop for good at "Don't show again":
 *
 * - The window, after [MIN_TRACKS] tracks played to the end, or [MIN_DAYS] days of use with at
 *   least [MIN_TRACKS_LATER] of them, then at most once every [GAP_DAYS] days. Only at a good
 *   moment: in the library, with nothing playing, within [MOMENT_LIFETIME_MS] of a track played to
 *   its end. Never while music plays, never during Welcome, sign in or the chat picker, and never
 *   twice in one run of the app. "Maybe later" and closing it both count as asked.
 * - The notification, at most once every [NUDGE_GAP_DAYS] days, never within
 *   [NUDGE_AFTER_ASK_DAYS] days of the window, never over the app or while music plays, and only
 *   while the "Sponsor reminders" switch is on (off until the user turns it on). Its first rung
 *   asks only for a star on GitHub ([NudgeRung.Star]), which costs nothing; the ones after it are
 *   "Don't forget to sponsor" ([NudgeRung.Coffee]). Once Star on GitHub has been pressed no star is
 *   asked for again. Pressing the notification itself opens the window.
 */
object SupportReminder {

    /** Every donation link, the window and the notification, together: the one gate for a store build. */
    @Volatile
    var enabled: Boolean = true

    /** Shows the window at the next chance whatever the counters say, for checking it by eye (debug builds). */
    @Volatile
    var forced: Boolean = false

    const val MIN_TRACKS = 25
    const val MIN_TRACKS_LATER = 5
    const val MIN_DAYS = 3
    const val GAP_DAYS = 14
    const val NUDGE_GAP_DAYS = 30
    const val NUDGE_AFTER_ASK_DAYS = 7

    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000
    private const val RECENT_LIMIT = 16

    /** A moment older than this is stale: the window would not feel like a thank you any more. */
    const val MOMENT_LIFETIME_MS = 30L * 60 * 1000

    /** What the store remembers. [firstSeenAt] is 0 until the first launch past onboarding is noted. */
    data class Counters(
        val firstSeenAt: Long = 0L,
        val completedTracks: Int = 0,
        /** The next window may not appear before this. */
        val snoozedUntil: Long = 0L,
        /** "Don't show again": for good, the window and the notification both. */
        val neverAgain: Boolean = false,
        /** When the window was last shown, or 0. */
        val askedAt: Long = 0L,
        /** How many windows have been shown. */
        val windows: Int = 0,
        /** When the last notification went out, or 0. */
        val nudgedAt: Long = 0L,
        /** When the notification's star rung went out, or 0. */
        val starAskedAt: Long = 0L,
        /** Star on GitHub was pressed: no star is asked for again. */
        val starred: Boolean = false,
    ) {
        /** Nothing may ask any more. */
        val finished: Boolean get() = neverAgain
    }

    /**
     * Whether the user has really used the app: past its first day, and either [MIN_TRACKS] tracks
     * played to the end, or [MIN_DAYS] days since the first launch with [MIN_TRACKS_LATER] of them.
     * A clock set back (a first launch in the future) is not real use.
     */
    fun realUse(counters: Counters, now: Long): Boolean {
        if (counters.firstSeenAt <= 0L) return false
        val age = now - counters.firstSeenAt
        if (age < DAY_MS) return false
        return counters.completedTracks >= MIN_TRACKS ||
            (age >= MIN_DAYS * DAY_MS && counters.completedTracks >= MIN_TRACKS_LATER)
    }

    /** When the window after the one shown at [now] may first appear. */
    fun snoozedUntil(now: Long): Long = now + GAP_DAYS * DAY_MS

    /** Whether the window may appear at a good moment at [now]. */
    fun due(
        counters: Counters,
        now: Long,
        enabled: Boolean = this.enabled,
        forced: Boolean = this.forced,
        sessionShown: Boolean = this.sessionShown,
    ): Boolean {
        if (!enabled) return false
        if (forced) return true
        if (counters.finished || sessionShown) return false
        return realUse(counters, now) && now >= counters.snoozedUntil
    }

    /**
     * Whether to show the window now. If so it counts as asked at once: a window the user never
     * answered (the app closed under it) is still one they saw. A forced one leaves the counters alone.
     */
    fun claim(store: SupportStore, now: Long): Boolean {
        if (!due(store.counters(), now)) return false
        if (forced) {
            forced = false
        } else {
            store.recordAsk(now)
            sessionShown = true
        }
        return true
    }

    /** Whether the notification may go out at [now]. [switchOn] is the "Sponsor reminders" setting. */
    fun nudgeDue(counters: Counters, now: Long, switchOn: Boolean, enabled: Boolean = this.enabled): Boolean {
        if (!enabled || !switchOn || counters.finished) return false
        if (!realUse(counters, now)) return false
        if (counters.nudgedAt > 0L && now - counters.nudgedAt < NUDGE_GAP_DAYS * DAY_MS) return false
        if (counters.askedAt > 0L && now - counters.askedAt < NUDGE_AFTER_ASK_DAYS * DAY_MS) return false
        return true
    }

    /** Which notification goes out: a star first, on its own, and money only after it. */
    enum class NudgeRung { Star, Coffee }

    fun nudgeRung(counters: Counters): NudgeRung =
        if (!counters.starred && counters.starAskedAt <= 0L) NudgeRung.Star else NudgeRung.Coffee

    /** Whether a notification of [rung] offers Star on GitHub: never once it has been pressed. */
    fun offersStar(counters: Counters, rung: NudgeRung): Boolean = rung == NudgeRung.Star || !counters.starred

    /** The notification, if due, recorded as sent at once so a second check cannot repeat it. */
    fun claimNudgeRung(store: SupportStore, now: Long): NudgeRung? {
        val counters = store.counters()
        if (!nudgeDue(counters, now, store.remindersOn)) return null
        val rung = nudgeRung(counters)
        store.recordNudge(now, star = rung == NudgeRung.Star)
        return rung
    }

    // ---- the window's moments, in this run of the app ---------------------------------------------

    /** True once the window has appeared in this run of the app. */
    @Volatile
    var sessionShown: Boolean = false

    private val pending = MutableStateFlow(0L)

    /** When the last track played to its end, or 0: the library shows the window from it. */
    val moment: StateFlow<Long> get() = pending

    /** The player played a track to its end. */
    fun trackFinished(now: Long) {
        pending.value = now
    }

    /** Takes the pending moment, true if it is still fresh. */
    fun takeMoment(now: Long): Boolean {
        val at = pending.value
        pending.value = 0L
        return at > 0L && now - at in 0..MOMENT_LIFETIME_MS
    }

    private val openRequest = MutableStateFlow(false)

    /** A press on the notification: the library opens the window as soon as it is free to. */
    val openRequested: StateFlow<Boolean> get() = openRequest

    fun requestOpen() {
        openRequest.value = true
    }

    fun takeOpenRequest(): Boolean {
        val wanted = openRequest.value
        openRequest.value = false
        return wanted
    }

    /** Forgets this run's window, moment and open request, for tests. */
    fun resetSession() {
        sessionShown = false
        forced = false
        pending.value = 0L
        openRequest.value = false
    }

    // ---- counting ----------------------------------------------------------------------------------

    /** A track finished recently: what stops repeat one or a replay within the hour counting twice. */
    data class Recent(val key: String, val at: Long)

    /**
     * A finished track. It counts once per hour: a replay within the hour of the first adds nothing.
     * Returns the new list and whether this one counts.
     */
    fun stepCompleted(recent: List<Recent>, key: String, now: Long): Pair<List<Recent>, Boolean> {
        val before = recent.firstOrNull { it.key == key }?.takeIf { now - it.at in 0 until HOUR_MS }
        if (before != null) return recent to false
        return (listOf(Recent(key, now)) + recent.filter { it.key != key }).take(RECENT_LIMIT) to true
    }

    fun encodeRecent(recent: List<Recent>): String = recent.joinToString(";") { "${it.key.replace(Regex("[;|]"), "_")}|${it.at}" }

    /** The stored list, skipping any entry that does not read; at most [RECENT_LIMIT] kept. */
    fun decodeRecent(text: String?): List<Recent> =
        text.orEmpty().take(MAX_RECENT_CHARS).split(';').mapNotNull { part ->
            val f = part.split('|')
            if (f.size != 2 || f[0].isEmpty()) return@mapNotNull null
            Recent(f[0], f[1].toLongOrNull() ?: return@mapNotNull null)
        }.take(RECENT_LIMIT)

    private const val MAX_RECENT_CHARS = 8 * 1024
}

/**
 * Where [SupportReminder]'s counters and the "Sponsor reminders" switch are kept: the app's
 * [KeyValues]. A value that does not read (negative, from an older or broken file) reads as unset.
 */
class SupportStore(private val kv: KeyValues) {

    fun counters(): SupportReminder.Counters = synchronized(LOCK) {
        SupportReminder.Counters(
            firstSeenAt = long(FIRST_SEEN),
            completedTracks = long(COMPLETED).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            snoozedUntil = long(SNOOZED_UNTIL),
            neverAgain = kv.getBoolean(NEVER, false),
            askedAt = long(ASKED_AT),
            windows = long(WINDOWS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            nudgedAt = long(NUDGED_AT),
            starAskedAt = long(STAR_ASKED_AT),
            starred = kv.getBoolean(STARRED, false),
        )
    }

    /** The "Sponsor reminders" switch: off until the user turns it on. */
    var remindersOn: Boolean
        get() = kv.getBoolean(REMINDERS, false)
        set(value) = kv.putBoolean(REMINDERS, value)

    /** The first launch past onboarding, kept once. */
    fun noteFirstSeen(now: Long) = synchronized(LOCK) {
        if (long(FIRST_SEEN) <= 0L) kv.putLong(FIRST_SEEN, now)
    }

    /** A track played to its end; [key] is its song key. Returns whether it counted. */
    fun noteCompleted(key: String, now: Long): Boolean = synchronized(LOCK) {
        val (recent, counts) = SupportReminder.stepCompleted(SupportReminder.decodeRecent(kv.getString(RECENT)), key, now)
        if (counts) {
            kv.putString(RECENT, SupportReminder.encodeRecent(recent))
            kv.putLong(COMPLETED, long(COMPLETED) + 1)
        }
        counts
    }

    fun recordAsk(now: Long) = synchronized(LOCK) {
        kv.putLong(ASKED_AT, now)
        kv.putLong(SNOOZED_UNTIL, SupportReminder.snoozedUntil(now))
        kv.putLong(WINDOWS, long(WINDOWS) + 1)
    }

    fun recordNudge(now: Long, star: Boolean) = synchronized(LOCK) {
        kv.putLong(NUDGED_AT, now)
        if (star) kv.putLong(STAR_ASKED_AT, now)
    }

    /** "Don't show again": the window and the notification end for good. */
    fun markNever() = kv.putBoolean(NEVER, true)

    fun markStarred() = kv.putBoolean(STARRED, true)

    private fun long(key: String): Long = kv.getLong(key, 0L).coerceAtLeast(0L)

    private companion object {
        val LOCK = Any()
        const val FIRST_SEEN = "support_first_seen"
        const val COMPLETED = "support_completed"
        const val RECENT = "support_recent"
        const val SNOOZED_UNTIL = "support_snoozed_until"
        const val NEVER = "support_never"
        const val ASKED_AT = "support_asked_at"
        const val WINDOWS = "support_windows"
        const val NUDGED_AT = "support_nudged_at"
        const val STAR_ASKED_AT = "support_star_asked_at"
        const val STARRED = "support_starred"
        const val REMINDERS = "support_reminders"
    }
}
