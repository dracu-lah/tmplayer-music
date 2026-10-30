// From TMPlayer (GPL-3.0), ui/src/jvmTest/kotlin/com/tmplayer/ui/auth/PhoneEntryTest.kt.
package com.tmplayer.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign in's number rules and the country guess. */
class PhoneEntryTest {
    private val gb = Country("GB", "United Kingdom", listOf("44"))
    private val je = Country("JE", "Jersey", listOf("44"))
    private val us = Country("US", "USA", listOf("1"))
    private val ca = Country("CA", "Canada", listOf("1"))
    private val ru = Country("RU", "Russia", listOf("7"))
    private val kz = Country("KZ", "Kazakhstan", listOf("7"))
    private val it = Country("IT", "Italy", listOf("39"))
    private val va = Country("VA", "Vatican City", listOf("39", "379"))
    private val india = Country("IN", "India", listOf("91"))
    private val bs = Country("BS", "Bahamas", listOf("1242"))
    private val countries = listOf(gb, je, us, ca, ru, kz, it, va, india, bs)

    @Test
    fun `a pasted international number is taken apart whatever its punctuation`() {
        for (raw in listOf("+44 7700 900123", "0044 7700 900123", "+44-7700-900-123", "(+44) 7700 900123", " +44 (7700) 900123")) {
            val entry = PhoneEntry().typeNational(raw, countries)
            assertEquals(raw, "44", entry.dial)
            assertEquals(raw, "7700900123", entry.national)
            assertTrue(raw, entry.touched)
        }
        val dial = PhoneEntry().typeDial("+1 242 555 0100", countries)
        assertEquals("1242", dial.dial)
        assertEquals("5550100", dial.national)
    }

    @Test
    fun `a pasted number switches the country the entry was on`() {
        val entry = PhoneEntry().guess(gb).typeNational("+91 98765 43210", countries)
        assertEquals("91", entry.dial)
        assertEquals("9876543210", entry.national)
        assertEquals(india, PhoneRules.country(countries, entry.dial, null, entry.chosenIso))
    }

    @Test
    fun `a plus and a code alone in the dial field is the code`() {
        assertEquals("44", PhoneEntry().typeDial("+44", countries).dial)
        assertEquals("44", PhoneEntry().typeDial("44", countries).dial)
        assertEquals("1242", PhoneEntry().typeDial("+1242", countries).dial)
        assertEquals("1234", PhoneEntry().typeDial("12345", countries).dial)
    }

    @Test
    fun `the trunk zero goes, except where the zero is part of the number`() {
        val uk = PhoneEntry("44").typeNational("07700 900123", countries)
        assertEquals("7700900123", uk.national)
        assertEquals("7700900123", uk.take("+44 07700 900123", countries)?.national)
        val italy = PhoneEntry("39").typeNational("06 1234 5678", countries)
        assertEquals("0612345678", italy.national)
    }

    @Test
    fun `other scripts' digits become ASCII`() {
        assertEquals("0123456789", PhoneRules.digits("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", PhoneRules.digits("۰۱۲۳۴۵۶۷۸۹"))
        val entry = PhoneEntry("91").typeNational("٩٨٧٦٥ ٤٣٢١٠", countries)
        assertEquals("9876543210", entry.national)
        val pasted = PhoneEntry().typeNational("+٩١ ٩٨٧٦٥٤٣٢١٠", countries)
        assertEquals("+919876543210", pasted.full)
    }

    @Test
    fun `letters and symbols never reach the number`() {
        val entry = PhoneEntry("44").typeNational("77a00-9b00.12c3", countries)
        assertEquals("7700900123", entry.national)
        assertEquals("", PhoneEntry().typeDial("abc", countries).dial)
    }

    @Test
    fun `the national number is capped at E164's fifteen digits`() {
        val entry = PhoneEntry("44").typeNational("1234567890123456789", countries)
        assertEquals(13, entry.national.length)
    }

    @Test
    fun `a shared code goes by area code, then the chosen country, then the usual one`() {
        assertEquals(ca, PhoneRules.country(countries, "1", areaIso = "CA", chosenIso = "US"))
        assertEquals(je, PhoneRules.country(countries, "44", areaIso = null, chosenIso = "JE"))
        assertEquals(kz, PhoneRules.country(countries, "7", areaIso = "", chosenIso = "KZ"))
        assertEquals(us, PhoneRules.country(countries, "1", areaIso = null, chosenIso = "IN"))
        assertEquals(gb, PhoneRules.country(countries, "44", areaIso = null, chosenIso = null))
        assertEquals(it, PhoneRules.country(countries, "39", areaIso = null, chosenIso = null))
        assertEquals(va, PhoneRules.country(countries, "379", areaIso = null, chosenIso = null))
    }

    @Test
    fun `a typed code switches the country and an unknown one is no country`() {
        var entry = PhoneEntry().guess(gb)
        entry = entry.typeDial("91", countries)
        assertEquals(india, PhoneRules.country(countries, entry.dial, null, entry.chosenIso))
        entry = entry.typeDial("999", countries)
        assertNull(PhoneRules.country(countries, entry.dial, null, entry.chosenIso))
        entry = entry.typeNational("12345678", countries)
        assertFalse(entry.ready(null))
        assertEquals(PhoneProblem.UnknownCode, entry.problem(null))
    }

    @Test
    fun `a late guess never replaces what the user did`() {
        val picked = PhoneEntry().pick(india).guess(gb)
        assertEquals("91", picked.dial)
        val pasted = PhoneEntry().take("+7 912 345 6789", countries)!!.guess(gb)
        assertEquals("7", pasted.dial)
        val fresh = PhoneEntry().guess(gb)
        assertEquals("44", fresh.dial)
        assertEquals("GB", fresh.chosenIso)
        assertFalse(fresh.touched)
    }

    @Test
    fun `ready at Telegram's seven digits with a known country, and says why not`() {
        assertEquals(PhoneProblem.NoCountry, PhoneEntry().problem(null))
        assertEquals(PhoneProblem.Empty, PhoneEntry("44").problem(gb))
        val short = PhoneEntry("44").typeNational("7700", countries)
        assertFalse(short.ready(gb))
        assertEquals(PhoneProblem.TooShort, short.problem(gb))
        val enough = PhoneEntry("44").typeNational("77009", countries)
        assertTrue(enough.ready(gb))
        assertNull(enough.problem(gb))
    }

    @Test
    fun `picking a country keeps the national number`() {
        val entry = PhoneEntry("44", "7700900123").pick(india)
        assertEquals("+917700900123", entry.full)
        assertEquals("IN", entry.chosenIso)
    }

    @Test
    fun `the saved entry comes back whole, and garbage comes back empty`() {
        val entry = PhoneEntry("44", "7700900123", "GB", touched = true)
        assertEquals(entry, PhoneEntry.restore(entry.save()))
        assertEquals(PhoneEntry(), PhoneEntry.restore(null))
        assertEquals(PhoneEntry(), PhoneEntry.restore(listOf("1", "2")))
        val odd = PhoneEntry.restore(listOf("4x4", "x".repeat(50) + "123", "GBR", "yes"))
        assertEquals("44", odd.dial)
        assertEquals("123", odd.national)
        assertNull(odd.chosenIso)
        assertFalse(odd.touched)
    }

    @Test
    fun `the region of a list of language tags`() {
        assertEquals("IN", DeviceRegion.regionOf(listOf("en-IN", "en")))
        assertEquals("TW", DeviceRegion.regionOf(listOf("zh-Hant-TW")))
        assertEquals("BR", DeviceRegion.regionOf(listOf("es-419", "pt_BR")))
        assertEquals("", DeviceRegion.regionOf(listOf("en", "")))
    }

    @Test
    fun `the device's country is the SIM's, then the network's, then the locale's`() {
        assertEquals("IN", DeviceRegion.pick("in", "gb", "US"))
        assertEquals("GB", DeviceRegion.pick("", "gb", "US"))
        assertEquals("US", DeviceRegion.pick(null, null, "US"))
        // No SIM and a locale with no country ("en" alone gives ""), or a numeric region.
        assertEquals("", DeviceRegion.pick(null, "", ""))
        assertEquals("", DeviceRegion.pick(" ", "419", "usa"))
    }

    @Test
    fun `the guess takes Telegram's answer first and the device's after`() = runTest {
        val found = CountryGuess.country({ countries }, { "CA" }, { "GB" })
        assertEquals(ca, found)
        assertEquals(gb, CountryGuess.country({ countries }, { "" }, { "GB" }))
        assertEquals(gb, CountryGuess.country({ countries }, { error("offline") }, { "gb" }))
        assertNull(CountryGuess.country({ countries }, { "" }, { "" }))
    }

    @Test
    fun `on a phone the SIM answers at once, without waiting for Telegram`() = runTest {
        val found = CountryGuess.forPhone(countries, "IN", { awaitCancellation() }, { "GB" })
        assertEquals(india, found)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `no SIM and no locale country gives no guess, so the list opens`() = runTest {
        assertNull(CountryGuess.forPhone(countries, "", { "" }, { "" }))
        assertEquals(gb, CountryGuess.forPhone(countries, "", { "" }, { "GB" }))
        assertNull(CountryGuess.forPhone(emptyList(), "IN", { "IN" }, { "IN" }))
    }

    @Test
    fun `a slow Telegram leaves the device's answer, inside the limit`() = runTest {
        val found = CountryGuess.country({ countries }, { awaitCancellation() }, { "IN" })
        assertEquals(india, found)
        assertEquals(CountryGuess.LIMIT_MS, currentTime)
    }

    @Test
    fun `no country list in time gives nothing, at the limit`() = runTest {
        val never = CompletableDeferred<List<Country>>()
        assertNull(CountryGuess.country({ never.await() }, { "GB" }, { "GB" }))
        assertEquals(CountryGuess.LIMIT_MS, currentTime)
    }

    @Test
    fun `a quick answer does not wait out the limit`() = runTest {
        val found = CountryGuess.country({ delay(400); countries }, { delay(300); "JE" }, { "" })
        assertEquals(je, found)
        assertEquals(700L, currentTime)
    }
}
