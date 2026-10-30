// From TMPlayer (GPL-3.0), ui/src/shared/kotlin/com/tmplayer/ui/auth/PhoneEntry.kt, made an
// immutable value so the rules run in a plain JVM test and the screen keeps it in one state.
package com.tmplayer.music.data

/**
 * The number as Telegram's own apps take it: a country row, then the dial code beside the
 * national number.
 *
 * The country is worked out from the code typed, not the other way round, as Telegram does: a code
 * typed by hand switches the country, a code no country has shows as invalid rather than as a
 * wrong flag. Where a code is shared (+1, +7, +44, +39...), TDLib's area codes decide when they
 * can, else the country already chosen or guessed is kept while it still shares the code. A pick
 * from the list or anything typed marks the entry touched, and a guess that lands late never
 * replaces it.
 */
data class PhoneEntry(
    val dial: String = "",
    val national: String = "",
    /** The country picked from the list, or the one guessed, kept while its code is the one typed. */
    val chosenIso: String? = null,
    /** The user has typed, pasted or picked: from here a guess no longer fills anything in. */
    val touched: Boolean = false,
) {
    /** The number Telegram is sent. */
    val full: String get() = "+$dial$national"

    /** The dial code field changed. A whole number pasted into it is taken apart instead. */
    fun typeDial(raw: String, countries: List<Country>): PhoneEntry =
        take(raw, countries) ?: copy(dial = PhoneRules.digits(raw).take(PhoneCountries.MAX_DIAL_DIGITS), touched = true)

    /** The national field changed: digits only, the trunk zero dropped, capped at E.164's length. */
    fun typeNational(raw: String, countries: List<Country>): PhoneEntry =
        take(raw, countries) ?: copy(national = PhoneRules.national(PhoneRules.digits(raw), dial), touched = true)

    /** A country picked from the list: its code goes in front, the national number stays. */
    fun pick(country: Country): PhoneEntry =
        copy(chosenIso = country.iso, dial = country.dialCode, national = PhoneRules.national(national, country.dialCode), touched = true)

    /** The guessed country, only into an entry nobody has touched. */
    fun guess(country: Country): PhoneEntry =
        if (touched || dial.isNotEmpty() || national.isNotEmpty()) this else copy(chosenIso = country.iso, dial = country.dialCode)

    /**
     * A whole international number ("+44 7700 900123", "0044 (7700) 900-123") into its two halves,
     * the country following the code. Null when [raw] is not written as one, so the caller keeps
     * it as typed.
     */
    fun take(raw: String, countries: List<Country>): PhoneEntry? {
        val (d, n) = PhoneRules.splitInternational(raw, countries) ?: return null
        return copy(dial = d, national = PhoneRules.national(n, d), touched = true)
    }

    /** What is wrong with the number for [country], or null when it can be sent. */
    fun problem(country: Country?): PhoneProblem? = when {
        dial.isEmpty() -> PhoneProblem.NoCountry
        country == null -> PhoneProblem.UnknownCode
        national.isEmpty() -> PhoneProblem.Empty
        (dial + national).length < PhoneRules.MIN_DIGITS -> PhoneProblem.TooShort
        else -> null
    }

    /** Long enough to send, as Telegram counts it: seven digits with the code, and a known code. */
    fun ready(country: Country?): Boolean = problem(country) == null

    /** For the screen's saved state: four strings, read back by [restore]. */
    fun save(): List<String> = listOf(dial, national, chosenIso.orEmpty(), if (touched) "1" else "0")

    companion object {
        /** Anything that is not what [save] wrote gives an empty entry rather than a crash. */
        fun restore(saved: List<String>?): PhoneEntry {
            if (saved == null || saved.size != 4) return PhoneEntry()
            return PhoneEntry(
                dial = PhoneRules.digits(saved[0]).take(PhoneCountries.MAX_DIAL_DIGITS),
                national = PhoneRules.digits(saved[1]).take(PhoneRules.MAX_DIGITS),
                chosenIso = saved[2].takeIf { it.length == 2 },
                touched = saved[3] == "1",
            )
        }
    }
}

/** Why a number cannot be sent yet, in the order the screen asks about it. */
enum class PhoneProblem {
    /** No dial code at all: the country is still to be chosen. */
    NoCountry,

    /** A dial code no country has. */
    UnknownCode,

    /** A country, but no number after it. */
    Empty,

    /** Fewer digits than any real number has. */
    TooShort,
}

/** The rules every sign in form applies to a number, kept out of Compose so a test can reach them. */
object PhoneRules {
    /** Telegram's own floor (Web A's MIN_NUMBER_LENGTH): seven digits, the dial code counted. */
    const val MIN_DIGITS = 7

    /** E.164 allows fifteen digits, the dial code included. */
    const val MAX_DIGITS = 15

    /**
     * Calling codes whose national numbers keep a leading 0: Italy and the Vatican's landlines,
     * San Marino, Ivory Coast, Gabon and Congo. Everywhere else a 0 in front is the trunk prefix
     * dialled at home ("07700 900123" in the UK), which the international number drops.
     */
    private val KEEPS_ZERO = setOf("39", "378", "225", "241", "242")

    /** Which country a shared code means when nothing else says: the one most numbers belong to. */
    val USUAL = mapOf(
        "1" to "US", "7" to "RU", "44" to "GB", "39" to "IT", "47" to "NO", "61" to "AU",
        "262" to "RE", "290" to "SH", "358" to "FI", "590" to "GP", "599" to "CW", "212" to "MA",
    )

    /**
     * The digits of [raw] in ASCII, everything else dropped. Arabic-Indic, Persian and any other
     * script's digits are numbers too, and Telegram wants them as 0 to 9.
     */
    fun digits(raw: String): String = buildString {
        for (c in raw) {
            val d = c.digitToIntOrNull() ?: continue
            append('0' + d)
        }
    }

    /**
     * [raw] split into dial code and national number when it is written internationally: "+" or
     * "00" in front, after any spaces or brackets. Null otherwise, or when no known code fits.
     */
    fun splitInternational(raw: String, countries: List<Country>): Pair<String, String>? {
        // A paste is outside data: a whole message is fine, an endless one is cut short.
        val clipped = if (raw.length > MAX_PASTE) raw.take(MAX_PASTE) else raw
        val start = clipped.indexOfFirst { it == '+' || it.digitToIntOrNull() != null }
        if (start < 0) return null
        val lead = clipped.substring(start)
        val plus = lead.startsWith('+')
        val all = digits(lead)
        val written = if (plus) "+$all" else all
        return PhoneCountries.splitInternational(written, countries)
    }

    /** National digits for [dial]: the trunk 0 dropped where it is one, capped at E.164's length. */
    fun national(digits: String, dial: String): String {
        val trimmed = if (dial !in KEEPS_ZERO) digits.trimStart('0') else digits
        return trimmed.take((MAX_DIGITS - dial.length).coerceAtLeast(0))
    }

    /** The countries a code reaches. */
    fun candidates(countries: List<Country>, dial: String): List<Country> =
        if (dial.isEmpty()) emptyList() else countries.filter { dial in it.dialCodes }

    /**
     * The country for [dial]: by area code ([areaIso], TDLib's answer) when the code is shared and
     * it knows, else the one already chosen while it shares the code, else the usual one. Null for
     * a code no country has, which the form shows as invalid.
     */
    fun country(countries: List<Country>, dial: String, areaIso: String?, chosenIso: String?): Country? {
        val reach = candidates(countries, dial)
        if (reach.isEmpty()) return null
        if (reach.size == 1) return reach.first()
        reach.firstOrNull { !areaIso.isNullOrEmpty() && it.iso.equals(areaIso, ignoreCase = true) }?.let { return it }
        reach.firstOrNull { !chosenIso.isNullOrEmpty() && it.iso.equals(chosenIso, ignoreCase = true) }?.let { return it }
        reach.firstOrNull { it.iso == USUAL[dial] }?.let { return it }
        return reach.firstOrNull { it.dialCode == dial } ?: reach.first()
    }

    /** The most of a paste that is looked at; a real number with its punctuation is far shorter. */
    private const val MAX_PASTE = 256
}
