// Adapted from TMPlayer (GPL-3.0): core/src/pureMain/kotlin/com/tmplayer/data/SignInSteps.kt (QrClock)
// and ui/src/shared/kotlin/com/tmplayer/ui/auth/QrScanned.kt (rememberScanHold), moved out of the
// composables so the rules can be tested.
package com.tmplayer.music.data

/** What a QR code on screen is doing, by how long it has been up. */
enum class QrPhase {
    /** Freshly drawn. */
    Fresh,

    /** In the last part of its life: TDLib is about to send the next one. */
    ExpiringSoon,

    /** Past its lifetime with no new link: TDLib is late, most often for want of a line. */
    Renewing,

    /** So late that TDLib could not renew it: the code no longer signs anybody in. */
    Expired,
}

/**
 * The clock behind the QR pane's life bar and its expiry.
 *
 * TDLib does not say how long a login link lasts. Telegram issues them for about
 * [DEFAULT_LIFETIME_MS] and TDLib asks for the next one before the last runs out, so once two
 * links have arrived the time between them is the lifetime that matters, and until then the usual
 * figure stands in for it.
 */
object QrClock {

    const val DEFAULT_LIFETIME_MS = 30_000L

    /** From this much of the lifetime left the cue says the code is about to change. */
    const val SOON_MS = 8_000L

    /** Well past any renewal TDLib would make: the code is dead and a new one is asked for. */
    const val STALE_MS = 45_000L

    /** The lifetime to draw: the last observed gap between two links, within sane bounds. */
    fun lifetime(observedGapMs: Long?): Long =
        observedGapMs?.takeIf { it in MIN_GAP_MS..STALE_MS } ?: DEFAULT_LIFETIME_MS

    fun phase(ageMs: Long, lifetimeMs: Long): QrPhase = when {
        ageMs >= STALE_MS -> QrPhase.Expired
        ageMs >= lifetimeMs -> QrPhase.Renewing
        lifetimeMs - ageMs <= SOON_MS -> QrPhase.ExpiringSoon
        else -> QrPhase.Fresh
    }

    /** How much of the code's life is left, 1 when drawn and 0 at its end. */
    fun remaining(ageMs: Long, lifetimeMs: Long): Float =
        (1f - ageMs.toFloat() / lifetimeMs.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** A gap shorter than this is TDLib repeating itself, not a renewal. */
    private const val MIN_GAP_MS = 5_000L
}

/** What to do about a code that has run out ([QrRefreshPolicy.onExpired]). */
enum class QrExpiry {
    /** Ask Telegram for a new code. */
    Refresh,

    /** Already asked for this code, or nothing to ask about: do nothing. */
    Wait,

    /** Codes keep running out with no renewal: stop asking and show the way out. */
    GiveUp,
}

/**
 * When the QR pane asks for a new code by itself, and when it stops.
 *
 * TDLib renews the code on its own a little before it runs out, and each new link
 * ([onLink]) restarts the clock. A code still up after [QrClock.STALE_MS] is one TDLib could not
 * renew, so one new code is asked for ([onExpired] answers [QrExpiry.Refresh]), at most once per
 * code, which spaces the requests at least [QrClock.STALE_MS] apart. After [maxAuto] of those in a
 * row with no renewal of TDLib's own in between, it gives up ([exhausted]) and the pane shows the
 * error with Try again and Send logs instead of restarting the sign in for ever. A press of the
 * refresh button ([onManualRefresh]) or a renewal by TDLib starts the count again.
 *
 * Kept for the whole sign in, not one screen, because each refresh starts a new TDLib client and
 * the pane leaves the screen while it does. Times are passed in, so a test needs no clock.
 */
class QrRefreshPolicy(private val maxAuto: Int = MAX_AUTO_REFRESHES) {
    /** The link on screen, null before the first. */
    var link: String? = null
        private set

    /** When [link] first arrived, wall clock milliseconds. A rotation does not move it. */
    var arrivedAt: Long = 0L
        private set

    /** Expired codes asked again for in a row, with no renewal of TDLib's own between them. */
    var stale: Int = 0
        private set

    private var gap: Long? = null

    /** A new code of ours (a restart) is on its way, so the next link is not TDLib's renewal. */
    private var asked = false

    /** The link the last automatic request was made for. */
    private var askedFor: String? = null

    /** True once the automatic requests have stopped; only a press starts them again. */
    val exhausted: Boolean get() = stale >= maxAuto

    /** Notes [newLink] on screen and answers when it arrived. The same link again keeps its time. */
    fun onLink(newLink: String, nowMs: Long): Long {
        if (newLink == link) return arrivedAt
        val previous = link
        if (previous != null && !asked) {
            // TDLib renewed the code by itself: the line is fine, and its spacing is the lifetime.
            gap = nowMs - arrivedAt
            stale = 0
        }
        asked = false
        link = newLink
        arrivedAt = nowMs
        return nowMs
    }

    fun lifetime(): Long = QrClock.lifetime(gap)

    fun phase(nowMs: Long): QrPhase =
        if (link == null) QrPhase.Fresh else QrClock.phase((nowMs - arrivedAt).coerceAtLeast(0), lifetime())

    /** The code [expiredLink] has run out on screen. */
    fun onExpired(expiredLink: String): QrExpiry = when {
        expiredLink != link || expiredLink == askedFor -> QrExpiry.Wait
        exhausted -> QrExpiry.GiveUp
        else -> {
            stale++
            asked = true
            askedFor = expiredLink
            QrExpiry.Refresh
        }
    }

    /** The user pressed for a new code: always sent, and the automatic count starts again. */
    fun onManualRefresh() {
        stale = 0
        asked = true
        askedFor = null
    }

    /** The sign in left the QR code (the phone number chosen, back, signed in). */
    fun reset() {
        link = null
        arrivedAt = 0L
        stale = 0
        gap = null
        asked = false
        askedFor = null
    }

    companion object {
        const val MAX_AUTO_REFRESHES = 3
    }
}

/**
 * Holds a QR code on screen for a moment after it is scanned, marked [AuthState.Qr.scanned].
 *
 * TDLib has no "scanned" state: the phone's confirmation moves it straight from
 * `authorizationStateWaitOtherDeviceConfirmation` ([AuthState.Qr]) to Ready, or to the password
 * for an account with two-step verification. Without a pause the code vanishes into a loader and
 * the user never learns the scan worked, so that move is held on the same code with a tick. Any
 * other way out of the QR code (the phone number chosen, a restart, an error) goes through at once.
 *
 * [observe] is called with every state the app is in, the same one again included; [shown] is
 * what to draw. The caller ends a hold by its [holdId] after [SCANNED_HOLD_MS].
 */
class QrScanHold {
    private var lastLink: String? = null

    /** The scanned code being held, null when nothing is. */
    var held: AuthState.Qr? = null
        private set

    /** Counts holds, so the end of one is never taken for the end of the next. */
    var holdId: Int = 0
        private set

    fun observe(auth: AuthState) {
        val afterScan = auth is AuthState.Ready || auth is AuthState.Password
        val last = lastLink
        when {
            auth is AuthState.Qr -> {
                lastLink = auth.link.takeIf { it.isNotBlank() }
                held = null
            }
            afterScan && last != null -> {
                held = AuthState.Qr(last, scanned = true)
                holdId++
                lastLink = null
            }
            // A wrong password marks the same step again; a hold already under way is kept.
            afterScan -> Unit
            else -> {
                lastLink = null
                held = null
            }
        }
    }

    /** [auth], or the held scanned code while hold [holdId] has not been ended ([endedId]). */
    fun shown(auth: AuthState, endedId: Int): AuthState = held?.takeIf { holdId != endedId } ?: auth

    companion object {
        /** Long enough to read "Scanned", short enough not to hold up the sign in. */
        const val SCANNED_HOLD_MS = 1_400L
    }
}
