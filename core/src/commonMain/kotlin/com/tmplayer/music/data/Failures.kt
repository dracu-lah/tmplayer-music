package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import java.io.IOException

/**
 * TDLib's and the platform's error text, turned into a sentence the user can act on. Adapted from
 * TMPlayer's `Failures` (GPL-3.0); the music app grows its own cases as it meets them.
 */
object Failures {

    fun humanise(error: Throwable?): String = when (error) {
        null -> DEFAULT
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: OFFLINE
        else -> humanise(error.message)
    }

    fun humanise(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return DEFAULT

        floodWaitSeconds(raw)?.let { return L.errorsFloodWait(waitPhrase(it)) }

        return when {
            raw.startsWith("Unauthorized", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("SESSION_REVOKED") ||
                raw.contains("AUTH_KEY_UNREGISTERED") ->
                L.errorsSignedOut

            raw.contains("No space left", ignoreCase = true) || // i18n-ok: TDLib text, matched
                raw.contains("ENOSPC") ||
                raw.contains("Not enough disk space", ignoreCase = true) -> // i18n-ok: TDLib text, matched
                L.errorsNoSpace

            raw.contains("NETWORK", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) -> OFFLINE

            else -> raw
        }
    }

    /** The seconds in a `FLOOD_WAIT_42` or "retry after 42", or null when there is no wait. */
    fun floodWaitSeconds(raw: String?): Int? =
        raw?.let { FLOOD.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** A wait in the largest unit that fits it: "42 seconds", "5 minutes", "3 hours", "7 days". */
    fun waitPhrase(seconds: Int): String = when {
        seconds < 60 -> L.unitSeconds(seconds)
        seconds < 3600 -> L.unitMinutes(seconds / 60)
        seconds < 2 * 86_400 -> L.unitHours(seconds / 3600)
        else -> L.unitDays(seconds / 86_400)
    }

    private val FLOOD = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""", RegexOption.IGNORE_CASE)

    val OFFLINE: String get() = L.errorsOffline
    val DEFAULT: String get() = L.errorsNoAnswer
}
