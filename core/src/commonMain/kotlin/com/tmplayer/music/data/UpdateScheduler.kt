// Adapted from TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/UpdateScheduler.kt,
// without the repeat timer: a check happens when the app comes to the front, never by itself.
package com.tmplayer.music.data

/** What [UpdateScheduler] remembers between launches. */
interface UpdatePrefs {
    /** "Check for updates": the check when the app opens. On unless the user turns it off. */
    suspend fun checkOnOpen(): Boolean

    /** When a check last got an answer, epoch milliseconds; 0 for never. */
    suspend fun lastCheck(): Long
    suspend fun setLastCheck(at: Long)

    /** The version the user said to skip, or blank. */
    suspend fun skippedVersion(): String
    suspend fun setSkippedVersion(version: String)

    /** "Remind me later": no popup before this, epoch milliseconds. */
    suspend fun snoozedUntil(): Long
    suspend fun setSnoozedUntil(at: Long)

    /** The version the popup was last shown for, so it shows once per version. */
    suspend fun popupShownFor(): String
    suspend fun setPopupShownFor(version: String)

    /** "Get test versions": GitHub pre-releases are offered too. Off unless the user opts in. */
    suspend fun testVersions(): Boolean = false
}

/**
 * When the app asks whether a new version is out.
 *
 * - When the app comes to the front ([checkIfDue]), with "Check for updates" on, and not more often
 *   than every [THROTTLE_MS] by the time of the last check that got an answer. A failed one writes
 *   nothing, so the next time tries again. Nothing runs on a timer: an app left in the background
 *   asks nothing.
 * - The Settings button ([checkNow]) ignores the switch, the throttle and the skipped version.
 *
 * The popup's rules are here too: once per version ([shouldPopUp], [popupShown]), not while
 * snoozed ([remindLater]), never for a skipped version ([skip]).
 */
class UpdateScheduler(
    private val prefs: UpdatePrefs,
    private val now: () -> Long = System::currentTimeMillis,
    private val check: suspend (quiet: Boolean, skipped: String) -> Boolean = Updates::check,
    private val onSkip: () -> Unit = Updates::skip,
) {

    /** The check when the app opens: only with the switch on, and once the throttle has run out. */
    suspend fun checkIfDue(): Boolean {
        if (!prefs.checkOnOpen()) return false
        val last = prefs.lastCheck()
        val at = now()
        // Never checked is due, and so is a clock set back past the last check.
        if (last != 0L && at - last < THROTTLE_MS && at >= last) return false
        val answered = check(true, prefs.skippedVersion())
        if (answered) prefs.setLastCheck(at)
        return answered
    }

    /** The Settings button: now, whatever the throttle says, offering a skipped version too. */
    suspend fun checkNow() {
        val at = now()
        if (check(false, prefs.skippedVersion())) prefs.setLastCheck(at)
    }

    /** Whether the popup should open by itself for [version]. Settings opens it regardless. */
    suspend fun shouldPopUp(version: String): Boolean =
        prefs.skippedVersion() != version &&
            now() >= prefs.snoozedUntil() &&
            prefs.popupShownFor() != version

    /** The popup has been seen for [version] and does not open by itself again. */
    suspend fun popupShown(version: String) {
        prefs.setPopupShownFor(version)
    }

    /** "Remind me later": the popup comes back on the first opening after [SNOOZE_MS]. */
    suspend fun remindLater() {
        prefs.setSnoozedUntil(now() + SNOOZE_MS)
        prefs.setPopupShownFor("")
    }

    /** "Skip this version": the popup goes for [version]; a newer one shows again. */
    suspend fun skip(version: String) {
        prefs.setSkippedVersion(version)
        onSkip()
    }

    companion object {
        /** How long after the first frame the check runs, so a cold start is never slowed by it. */
        const val FIRST_CHECK_DELAY_MS = 10_000L
        const val THROTTLE_MS = 6 * 60 * 60_000L
        const val SNOOZE_MS = 24 * 60 * 60_000L
    }
}

/**
 * What the app says after an update has replaced it: "Updated to 0.2.0" once, on the first launch
 * of the new version, and on Android 12 and later a notification to open it again, since the
 * session install closed it. The install writes a [Pending] before Android takes the APK; the
 * launch reads it with [onLaunch]. From TMPlayer's UpdateInstall.kt (GPL-3.0).
 */
object UpdateRestart {

    /** A pending record older than this, with the old version still running, is dropped. */
    const val STALE_MS = 2L * 24 * 60 * 60_000L

    /**
     * An install handed to Android: [version] at [at]. [session] is Android 12's installer session
     * (which closes the app and has no "Open" button), false for the system installer's screen.
     */
    data class Pending(val version: String, val at: Long, val session: Boolean, val foreground: Boolean) {
        fun encode(): String = listOf(version, at.toString(), flag(session), flag(foreground)).joinToString("|")

        companion object {
            private fun flag(on: Boolean) = if (on) "1" else "0"

            fun decode(text: String): Pending? {
                if (text.length > 200) return null
                val parts = text.split('|')
                if (parts.size != 4 || parts[0].isBlank()) return null
                val at = parts[1].toLongOrNull() ?: return null
                return Pending(parts[0], at, parts[2] == "1", parts[3] == "1")
            }
        }
    }

    /** What a launch does: the version to say it was updated to, if any, and whether to forget the record. */
    data class Launch(val updatedTo: String?, val clear: Boolean)

    /**
     * On a launch of [installed] with the stored record [stored] ("" for none): at or past the
     * record's version, say so once and forget it; older with a record under [STALE_MS] old, keep
     * it (Android may still be asking); older than that, a clock set back, or a record that does
     * not read, forget it.
     */
    fun onLaunch(stored: String, installed: String, now: Long): Launch {
        if (stored.isEmpty()) return Launch(null, clear = false)
        val pending = Pending.decode(stored) ?: return Launch(null, clear = true)
        if (UpdateFeed.compare(installed, pending.version) >= 0) return Launch(pending.version, clear = true)
        val stale = now < pending.at || now - pending.at > STALE_MS
        return Launch(null, clear = stale)
    }

    /**
     * Whether the broadcast after the replace should bring the app back with a notification: only
     * after an installer session, and only for the version that record was about.
     */
    fun notifyAfterReplace(stored: String, installed: String): Boolean {
        val pending = Pending.decode(stored) ?: return false
        return pending.session && UpdateFeed.compare(installed, pending.version) >= 0
    }
}
