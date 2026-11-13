package com.tmplayer.music.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The code the TV shows and when it stops working. */
data class PairingOffer(val code: String, val expiresAt: Long)

/** What a try at the code came to. */
sealed interface PairingVerdict {
    /** Right: the code is spent. */
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
 * digits, lasts [CODE_MS] and works once. [MAX_TRIES] wrong tries in a row (counted for the whole TV,
 * not per phone, so changing address does not help) lock every try for [LOCK_MS] and replace the
 * code. A paired phone never meets this: it proves its secret instead.
 *
 * [clock] is milliseconds from any steady clock; tests drive it by hand.
 */
class PairingGate(
    private val clock: () -> Long,
    private val digits: () -> String = { (0 until CODE_DIGITS).joinToString("") { ConnectCrypto.randomBelow(10).toString() } },
) {
    private val _offer = MutableStateFlow<PairingOffer?>(null)

    /** The code on show, null when none; the TV screen draws it. */
    val offer: StateFlow<PairingOffer?> = _offer.asStateFlow()

    private val _lockedUntil = MutableStateFlow(0L)

    /** When the lock ends (a [clock] time), 0 when there is none. */
    val lockedUntil: StateFlow<Long> = _lockedUntil.asStateFlow()

    private var failures = 0

    /** Shows a new code (the old one stops working). */
    @Synchronized
    fun open(): PairingOffer {
        failures = 0
        return fresh()
    }

    @Synchronized
    fun close() {
        _offer.value = null
    }

    /** The code that is valid now, or null. */
    @Synchronized
    fun current(): PairingOffer? = _offer.value?.takeIf { clock() < it.expiresAt }

    /** Tries [matches] against the code on show; [matches] says whether a phone's proof fits that code. */
    @Synchronized
    fun attempt(matches: (code: String) -> Boolean): PairingVerdict {
        val now = clock()
        val locked = _lockedUntil.value
        if (locked > now) return PairingVerdict.Locked(locked - now)
        val offer = current() ?: return PairingVerdict.Closed
        if (matches(offer.code)) {
            failures = 0
            _offer.value = null
            return PairingVerdict.Accepted
        }
        failures++
        if (failures < MAX_TRIES) return PairingVerdict.Wrong(MAX_TRIES - failures)
        failures = 0
        _lockedUntil.value = now + LOCK_MS
        fresh()
        return PairingVerdict.Locked(LOCK_MS)
    }

    private fun fresh(): PairingOffer {
        val offer = PairingOffer(digits(), clock() + CODE_MS)
        _offer.value = offer
        return offer
    }

    companion object {
        const val CODE_DIGITS = 6
        const val CODE_MS = 2 * 60_000L
        const val MAX_TRIES = 5
        const val LOCK_MS = 60_000L
    }
}
