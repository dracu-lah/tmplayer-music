// From TMPlayer (GPL-3.0), ui/src/shared/kotlin/com/tmplayer/ui/auth/CountryGuess.kt and
// app/src/main/java/com/tmplayer/data/DeviceCountry.kt.
package com.tmplayer.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The country a sign in's number starts on, filled in before the user types.
 *
 * Telegram's own guess comes first: it reads the address the connection comes from. The device's
 * answer is the fallback (the SIM, then the network, then the locale, [DeviceRegion]). None of
 * these needs a permission: a guess that would have to ask for one is not worth a prompt on the
 * first screen.
 */
object CountryGuess {
    /**
     * How long the number waits for the guess, from the pane appearing: Telegram's country list and
     * its answer together. Past this the field opens with whatever was found, or nothing.
     */
    const val LIMIT_MS = 3_000L

    /**
     * The country to start on, or null when nothing could be found in time.
     *
     * [countries] suspends until a list is in. Telegram's guess shares [limitMs] with the list;
     * when it is late or blank, [localIso] still answers as long as the list arrived.
     */
    suspend fun country(
        countries: suspend () -> List<Country>,
        telegramIso: suspend () -> String,
        localIso: () -> String,
        limitMs: Long = LIMIT_MS,
    ): Country? {
        var list: List<Country> = emptyList()
        val fromTelegram = withTimeoutOrNull(limitMs) {
            list = countries()
            try {
                telegramIso()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }.orEmpty()
        if (list.isEmpty()) return null
        return PhoneCountries.byIso(list, fromTelegram.trim())
            ?: PhoneCountries.byIso(list, runCatching(localIso).getOrDefault("").trim())
    }

    /**
     * The phone's order: the SIM's or the network's country at once when there is one, since the
     * number being typed is most often the one in this phone and nothing then waits on Telegram.
     * Without either (a tablet, no SIM), Telegram's guess and then the locale, as [country] has it.
     */
    suspend fun forPhone(
        countries: List<Country>,
        deviceIso: String,
        telegramIso: suspend () -> String,
        localeIso: () -> String,
        limitMs: Long = LIMIT_MS,
    ): Country? {
        if (countries.isEmpty()) return null
        return PhoneCountries.byIso(countries, deviceIso)
            ?: country({ countries }, telegramIso, localeIso, limitMs)
    }
}

/**
 * Where the device thinks it is, for when Telegram will not say.
 *
 * The SIM is asked before the network: a phone roaming abroad still holds the account registered
 * at home, which is the number being typed. The locale is the last resort and the weakest of the
 * three, since it says what language somebody reads rather than where they are. Each answer is
 * outside data, so only a two letter code counts; anything else ("", "419", junk) is passed over.
 */
object DeviceRegion {
    fun pick(sim: String?, network: String?, locale: String?): String =
        sequenceOf(sim, network, locale).mapNotNull(::region).firstOrNull().orEmpty()

    /**
     * The first region named in a list of language tags: "IN" for "en-IN", "TW" for "zh-Hant-TW".
     * A numeric region ("es-419", Latin America) names no one country and is passed over.
     */
    fun regionOf(tags: List<String>): String = tags.firstNotNullOfOrNull { tag ->
        tag.trim().split('-', '_').drop(1).firstOrNull { it.length == 2 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }?.uppercase()
    }.orEmpty()

    private fun region(raw: String?): String? =
        raw?.trim()?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }?.uppercase()
}
