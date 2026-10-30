// From TMPlayer (GPL-3.0), core/src/jvmTest/kotlin/com/tmplayer/data/PhoneCountriesTest.kt, with
// the checks on the list shipped with this app added.
package com.tmplayer.music.data

import com.tmplayer.music.i18n.L
import dev.g000sha256.tdl.dto.CountryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCountriesTest {

    private fun info(iso: String, name: String, vararg codes: String, hidden: Boolean = false) = CountryInfo(
        countryCode = iso,
        name = name,
        englishName = name,
        flagEmoji = "",
        isHidden = hidden,
        callingCodes = arrayOf(*codes),
    )

    private val all = PhoneCountries.from(
        listOf(
            info("GB", "United Kingdom", "44"),
            info("IN", "India", "91"),
            info("US", "United States", "1"),
            info("BS", "Bahamas", "1242"),
            info("XX", "Nowhere", "999", hidden = true),
        ),
    )

    @Test
    fun `the list is sorted by name and drops what Telegram will not accept`() {
        assertEquals(listOf("Bahamas", "India", "United Kingdom", "United States"), all.map { it.name })
    }

    @Test
    fun `a country with no calling code, or only broken ones, has nothing to dial and is left out`() {
        assertTrue(PhoneCountries.from(listOf(info("ZZ", "Codeless"))).isEmpty())
        assertTrue(PhoneCountries.from(listOf(info("ZZ", "Broken", "", "12345", "4a"))).isEmpty())
        assertTrue(PhoneCountries.from(listOf(info("", "No code", "44"), info("GB", " ", "44"))).isEmpty())
    }

    @Test
    fun `search takes a name, an ISO code or a dial code, with or without the plus`() {
        val country = PhoneCountries.byIso(all, "IN")!!
        assertTrue(PhoneCountries.matches(country, "ind"))
        assertTrue(PhoneCountries.matches(country, "INDIA"))
        assertTrue(PhoneCountries.matches(country, "in"))
        assertTrue(PhoneCountries.matches(country, "+91"))
        assertTrue(PhoneCountries.matches(country, "9"))
        assertTrue(PhoneCountries.matches(country, ""))
        assertFalse(PhoneCountries.matches(country, "44"))
    }

    @Test
    fun `search needs no accents`() {
        val ivory = PhoneCountries.byIso(BundledCountries.all, "CI")!!
        assertTrue(PhoneCountries.matches(ivory, "cote"))
        assertTrue(PhoneCountries.matches(ivory, "Côte d'"))
        assertTrue(PhoneCountries.matches(PhoneCountries.byIso(BundledCountries.all, "TR")!!, "turkiye"))
    }

    @Test
    fun `a dial code picks the country that owns exactly it, not one that starts with it`() {
        assertEquals("US", PhoneCountries.byDialCode(all, "1")?.iso)
        assertEquals("BS", PhoneCountries.byDialCode(all, "1242")?.iso)
        assertNull(PhoneCountries.byDialCode(all, ""))
        assertNull(PhoneCountries.byDialCode(all, "12"))
    }

    @Test
    fun `a flag is the country's own letters, and nothing at all for a code that is not one`() {
        assertEquals("🇮🇳", flagEmoji("IN"))
        assertEquals("🇮🇳", flagEmoji("in"))
        assertEquals("", flagEmoji("USA"))
        assertEquals("", flagEmoji("1"))
        assertEquals("", flagEmoji(""))
    }

    @Test
    fun `the shipped list is whole and well formed`() {
        val list = BundledCountries.all
        assertTrue("only ${list.size} countries", list.size >= 200)
        assertEquals("one row per ISO code", list.size, list.map { it.iso }.toSet().size)
        for (country in list) {
            assertTrue(country.iso, country.iso.length == 2 && country.iso.all { it in 'A'..'Z' })
            assertTrue(country.iso, country.name.isNotBlank())
            assertEquals(country.iso, 2, country.flag.codePointCount(0, country.flag.length))
            assertTrue(country.iso, country.dialCodes.isNotEmpty())
            for (code in country.dialCodes) {
                assertTrue("${country.iso} $code", code.length in 1..PhoneCountries.MAX_DIAL_DIGITS && code.all { it in '0'..'9' })
                assertFalse("${country.iso} $code", code.startsWith("0"))
            }
        }
        assertEquals(list.sortedBy { it.name.lowercase() }, list)
        for (iso in listOf("US", "GB", "IN", "DE", "BR", "RU", "CN", "NG", "ID", "AU")) {
            assertNotNull(iso, PhoneCountries.byIso(list, iso))
        }
    }

    @Test
    fun `every shared dial code still means one usual country`() {
        val list = BundledCountries.all
        val shared = list.flatMap { c -> c.dialCodes.map { it to c } }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        assertTrue("the list has shared codes to check", shared.containsKey("1") && shared.containsKey("44") && shared.containsKey("7"))
        for ((code, owners) in shared) {
            val usual = PhoneRules.USUAL[code]
            assertNotNull("shared code +$code has no usual country", usual)
            assertTrue("+$code: usual $usual is not among ${owners.map { it.iso }}", owners.any { it.iso == usual })
            // With nothing to go on, the usual one; with a country chosen that shares it, that one.
            assertEquals(code, usual, PhoneRules.country(list, code, null, null)?.iso)
            for (owner in owners) assertEquals(code, owner.iso, PhoneRules.country(list, code, null, owner.iso)?.iso)
        }
    }

    @Test
    fun `no dial code is shadowed when a whole number is pasted`() {
        // The longest known code wins, so "+1242..." is the Bahamas and "+1 202..." is +1.
        val list = BundledCountries.all
        for (code in list.flatMap { it.dialCodes }.toSet()) {
            val split = PhoneRules.splitInternational("+$code 55 0100", list)
            assertNotNull(code, split)
            assertTrue("+$code split as ${split!!.first}", split.first == code || (code + "550100").startsWith(split.first))
            assertEquals(code, PhoneEntry().typeDial("+$code", list).dial)
        }
        assertEquals("1242" to "5550100", PhoneRules.splitInternational("+1 242 555 0100", list))
        assertEquals("1" to "2025550100", PhoneRules.splitInternational("+1 202 555 0100", list))
        assertEquals("44" to "7700900123", PhoneRules.splitInternational("0044 7700 900123", list))
    }

    @Test
    fun `the device's locale lands on a country in the list`() {
        val list = BundledCountries.all
        for ((tag, iso) in listOf("en-IN" to "IN", "pt-BR" to "BR", "de-DE" to "DE", "en-GB" to "GB", "es-MX" to "MX")) {
            val region = DeviceRegion.pick(null, null, DeviceRegion.regionOf(listOf(tag)))
            assertEquals(tag, iso, PhoneCountries.byIso(list, region)?.iso)
        }
        assertNull(PhoneCountries.byIso(list, DeviceRegion.pick(null, null, DeviceRegion.regionOf(listOf("es-419")))))
    }

    @Test
    fun `the sign in words name every route and problem`() {
        for (delivery in CodeDelivery.entries) {
            assertTrue(delivery.name, SignInWords.codeBlurb(AuthState.Code("+44 7700 900123", delivery = delivery)).isNotBlank())
            assertTrue(delivery.name, SignInWords.resendLabel(delivery).isNotBlank())
        }
        for (problem in PhoneProblem.entries) assertTrue(problem.name, SignInWords.problem(problem).isNotBlank())
        assertEquals("1:05", SignInWords.clock(65))
        assertEquals("0:00", SignInWords.clock(-3))
        assertTrue(SignInWords.offersQr(AuthState.Failed(L.errorsPremiumSignIn)))
        assertFalse(SignInWords.offersQr(AuthState.Failed(L.errorsNoAccount)))
        assertEquals(5, SignInWords.codeLength(AuthState.Code("", length = 0)))
        assertEquals(6, SignInWords.codeLength(AuthState.Code("", length = 6)))
        assertEquals(5, SignInWords.codeLength(AuthState.Code("", length = 99)))
    }

    @Test
    fun `a pasted text message gives its code, sent once`() {
        assertEquals("12345", CodeInput.clean("Telegram code 12345. Do not give this code to anyone", true, 5))
        assertEquals("12345", CodeInput.clean("1 2 3 4 5", true, 5))
        assertEquals("12345", CodeInput.clean("١٢٣٤٥", true, 5))
        assertEquals("word up", CodeInput.clean("word up", false, 0))
        assertTrue(CodeInput.shouldAutoSubmit("12345", 5, true, null))
        assertFalse(CodeInput.shouldAutoSubmit("12345", 5, true, "12345"))
        assertFalse(CodeInput.shouldAutoSubmit("1234", 5, true, null))
        assertFalse(CodeInput.shouldAutoSubmit("hello", 5, false, null))
    }
}
