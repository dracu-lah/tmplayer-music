package com.tmplayer.music.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The code (typed) and the one time token (in the QR code) the TV shows, and when they stop working.
 * [key] is the key the TV answers with while this offer is on show, so the fingerprint in the QR
 * code ([fingerprint]) names the key the phone will meet.
 */
class PairingOffer(val code: String, val token: String, val expiresAt: Long, val key: EphemeralKey) {
    val fingerprint: String get() = ConnectHandshake.fingerprint(key.publicHex)

    override fun toString(): String = "PairingOffer(expiresAt=$expiresAt)"
}

/** What a try at the code came to. */
sealed interface PairingVerdict {
    /** Right: the code is spent. A typed code is still to be confirmed by both screens ([PairingGate.reject], [PairingGate.complete]). */
    data object Accepted : PairingVerdict

    /** Wrong, with [left] tries before the lock. */
    data class Wrong(val left: Int) : PairingVerdict

    /** Too many wrong tries: nothing is tried for [retryMs]; the code was changed. */
    data class Locked(val retryMs: Long) : PairingVerdict

    /** The TV is not showing a code (never opened, expired, or used). */
    data object Closed : PairingVerdict
}

/**
 * The one door a new phone goes through. The code is opened by the user on the TV, is 6 random
 * digits (and a 128 bit random token for the QR code, that fits the same offer), lasts [CODE_MS] and
 * works once, by either way. [MAX_TRIES] wrong tries (a wrong code, or a pairing that someone said
 * no to) lock every try for [LOCK_MS] and replace the code. The count is for the whole TV, not per
 * phone, so changing address does not help, and it survives a new code: it starts over only after
 * a pairing that was confirmed ([complete]) or [FAILURE_WINDOW_MS] after its first try. A paired phone
 * never meets this: it proves its secret instead.
 *
 * The lock is a denial of service by design: anyone on the network who can reach the TV can burn the
 * five tries and keep the user from pairing for a minute. That is the price of not letting them guess
 * a code; it costs the user a minute, never the TV's security.
 *
 * [clock] is milliseconds from any steady clock; tests drive it by hand.
 */
class PairingGate(
    private val clock: () -> Long,
    private val digits: () -> String = { (0 until CODE_DIGITS).joinToString("") { ConnectCrypto.randomBelow(10).toString() } },
    private val tokens: () -> String = { ConnectCrypto.randomHex(TOKEN_BYTES) },
    private val keys: () -> EphemeralKey = { EphemeralKey.generate() },
) {
    private val _offer = MutableStateFlow<PairingOffer?>(null)

    /** The code on show, null when none; the TV screen draws it. */
    val offer: StateFlow<PairingOffer?> = _offer.asStateFlow()

    private val _lockedUntil = MutableStateFlow(0L)

    /** When the lock ends (a [clock] time), 0 when there is none. */
    val lockedUntil: StateFlow<Long> = _lockedUntil.asStateFlow()

    private var failures = 0
    private var firstFailureAt = 0L

    /** Shows a new code (the old one stops working). The count of wrong tries is kept: a new code is not a new start. */
    @Synchronized
    fun open(): PairingOffer = fresh()

    @Synchronized
    fun close() {
        _offer.value = null
    }

    /** The code that is valid now, or null. */
    @Synchronized
    fun current(): PairingOffer? = _offer.value?.takeIf { clock() < it.expiresAt }

    /** Tries [matches] against the offer on show (its code and its token); [matches] says whether a phone's proof fits it. */
    @Synchronized
    fun attempt(matches: (offer: PairingOffer) -> Boolean): PairingVerdict {
        val now = clock()
        val locked = _lockedUntil.value
        if (locked > now) return PairingVerdict.Locked(locked - now)
        val offer = current() ?: return PairingVerdict.Closed
        if (matches(offer)) {
            _offer.value = null
            return PairingVerdict.Accepted
        }
        return countFailure(now)
    }

    /** A pairing whose code was right was not confirmed (a no on either screen, a timeout, a dropped line): it counts as a wrong try. */
    @Synchronized
    fun reject() {
        countFailure(clock())
    }

    /** A pairing was confirmed and stored: the count of wrong tries starts over. */
    @Synchronized
    fun complete() {
        failures = 0
    }

    private fun countFailure(now: Long): PairingVerdict {
        if (failures > 0 && now - firstFailureAt > FAILURE_WINDOW_MS) failures = 0
        if (failures == 0) firstFailureAt = now
        failures++
        if (failures < MAX_TRIES) return PairingVerdict.Wrong(MAX_TRIES - failures)
        failures = 0
        _lockedUntil.value = now + LOCK_MS
        // A code that was on show is replaced; none is made for a gate that was closed.
        if (_offer.value != null) fresh()
        return PairingVerdict.Locked(LOCK_MS)
    }

    private fun fresh(): PairingOffer {
        val offer = PairingOffer(digits(), tokens(), clock() + CODE_MS, keys())
        _offer.value = offer
        return offer
    }

    companion object {
        const val CODE_DIGITS = 6
        const val TOKEN_BYTES = 16
        const val CODE_MS = 2 * 60_000L
        const val MAX_TRIES = 5
        const val LOCK_MS = 60_000L
        const val FAILURE_WINDOW_MS = 10 * 60_000L
    }
}
