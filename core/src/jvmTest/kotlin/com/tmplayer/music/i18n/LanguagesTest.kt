package com.tmplayer.music.i18n

import com.tmplayer.music.data.MemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from TMPlayer's LanguagesTest (GPL-3.0), plus the catalog-aware parts. */
class LanguagesTest {

    @Test
    fun `twenty-one languages are known beside english`() {
        assertEquals(22, Languages.tags.size)
        assertEquals(
            listOf(
                "en", "es-419", "pt-BR", "fr", "de", "it", "pl", "cs", "ru", "uk", "tr", "id", "vi", "ar", "fa",
                "hi", "bn", "ml", "ja", "ko", "zh-CN", "zh-TW",
            ),
            Languages.tags,
        )
        assertEquals(Languages.tags.size, Languages.tags.toSet().size)
        assertTrue(Languages.all.all { it.name.isNotBlank() })
    }

    @Test
    fun `arabic and persian are right to left`() {
        assertTrue(Languages.isRtl("ar"))
        assertTrue(Languages.isRtl("fa"))
        assertEquals(listOf("ar", "fa"), Languages.all.filter { it.rtl }.map { it.tag })
        assertFalse(Languages.isRtl("en"))
        assertFalse(Languages.isRtl(""))
        assertFalse(Languages.isRtl("xx"))
    }

    @Test
    fun `the saved setting wins over the system`() {
        assertEquals("de", Languages.resolve("de", listOf("fr-FR")))
        assertEquals("pt-BR", Languages.resolve("pt-BR", listOf("es-CL")))
        assertEquals("pt-BR", Languages.resolve("PT-br", listOf("es-CL")))
    }

    @Test
    fun `an empty or unknown saved value follows the system`() {
        assertEquals("fr", Languages.resolve("", listOf("fr-FR")))
        assertEquals("fr", Languages.resolve(null, listOf("fr-CA")))
        assertEquals("fr", Languages.resolve("xx", listOf("fr")))
        assertEquals("fr", Languages.resolve("   ", listOf("fr")))
    }

    @Test
    fun `system tags map onto the regional catalogs`() {
        assertEquals("es-419", Languages.match("es-CL"))
        assertEquals("es-419", Languages.match("es-MX"))
        assertEquals("es-419", Languages.match("es-419"))
        assertEquals("es-419", Languages.match("es-ES"))
        assertEquals("es-419", Languages.match("es"))
        assertEquals("pt-BR", Languages.match("pt-PT"))
        assertEquals("pt-BR", Languages.match("pt-BR"))
        assertEquals("pt-BR", Languages.match("pt"))
        assertEquals("zh-CN", Languages.match("zh-Hans"))
        assertEquals("zh-CN", Languages.match("zh-CN"))
        assertEquals("zh-CN", Languages.match("zh-SG"))
        assertEquals("zh-CN", Languages.match("zh-Hans-HK"))
        assertEquals("zh-CN", Languages.match("zh"))
        assertEquals("zh-TW", Languages.match("zh-TW"))
        assertEquals("zh-TW", Languages.match("zh-Hant"))
        assertEquals("zh-TW", Languages.match("zh-Hant-CN"))
        assertEquals("zh-TW", Languages.match("zh-HK"))
        assertEquals("zh-TW", Languages.match("zh-MO"))
        assertNull(Languages.match("zh-AU"))
    }

    @Test
    fun `a language matches whatever its region`() {
        assertEquals("de", Languages.match("de-AT"))
        assertEquals("ar", Languages.match("ar-EG"))
        assertEquals("ar", Languages.match("ar_SA"))
        assertEquals("ru", Languages.match("ru-UA"))
        assertEquals("ml", Languages.match("ml-IN"))
        assertEquals("pl", Languages.match("pl-PL"))
        assertEquals("fa", Languages.match("fa-IR"))
        assertEquals("bn", Languages.match("bn-BD"))
        assertEquals("hi", Languages.match("hi_IN"))
        assertEquals("en", Languages.match("en-GB"))
    }

    @Test
    fun `android's legacy indonesian code still matches`() {
        assertEquals("id", Languages.match("in-ID"))
        assertEquals("id", Languages.match("id-ID"))
    }

    @Test
    fun `the system list is walked in order and english is the last resort`() {
        assertEquals("ja", Languages.resolve("", listOf("nl-NL", "ja-JP", "de-DE")))
        assertEquals("zh-TW", Languages.resolve("", listOf("nl-NL", "zh-TW", "ko-KR")))
        assertEquals("ko", Languages.resolve("", listOf("zh-AU", "ko-KR")))
        assertEquals("en", Languages.resolve("", listOf("nl-NL", "sv-SE")))
        assertEquals("en", Languages.resolve("", emptyList()))
        assertEquals("en", Languages.resolve("", listOf("")))
    }

    @Test
    fun `a language without a catalog is passed over`() {
        val only = setOf("fr", "ja")
        val has = { tag: String -> tag in only }
        // The saved choice has no catalog: back to the system list.
        assertEquals("fr", Languages.resolve("de", listOf("fr-FR"), has))
        // The device's first language has none: the next one that does is used.
        assertEquals("ja", Languages.resolve("", listOf("de-DE", "ja-JP"), has))
        // Nothing the device speaks is shipped: English.
        assertEquals("en", Languages.resolve("", listOf("de-DE", "ru"), has))
        assertEquals("en", Languages.resolve("de", emptyList(), has))
        // English never needs a catalog check.
        assertEquals("en", Languages.resolve("en", listOf("fr"), has))
    }

    @Test
    fun `garbage tags never throw and never match`() {
        for (bad in listOf("", " ", "-", "_", "--", "und", "und-US", "xx", "***", "zh-", "-zh", "e", "x-private", "\u0000", "😀", "a".repeat(5000))) {
            val matched = Languages.match(bad)
            // Only a bare Chinese tag with stray hyphens ("zh-", "-zh") may match: Simplified.
            assertTrue("match($bad) = $matched", matched == null || bad in setOf("zh-", "-zh"))
            assertEquals(matched ?: "en", Languages.resolve(bad, listOf(bad)))
        }
        assertNull(Languages.match("und"))
        assertNull(Languages.match(""))
        assertNull(Languages.match("xx-YY"))
        assertNull(Languages.find("und"))
        assertNull(Languages.find(""))
    }

    @Test
    fun `random input does not throw`() {
        val random = java.util.Random(7)
        val alphabet = "abcxyzABC-_ 0123456789é中"
        repeat(2000) {
            val tag = String(CharArray(random.nextInt(24)) { alphabet[random.nextInt(alphabet.length)] })
            val matched = Languages.match(tag)
            assertTrue(matched == null || matched in Languages.tags)
            assertTrue(Languages.resolve(tag, listOf(tag, tag)) in Languages.tags)
        }
    }

    @Test
    fun `the translator only picks a language whose catalog ships`() {
        // Only English ships today, so every other language reads as English and nothing is claimed.
        assertTrue(Translator.hasCatalog("en"))
        assertFalse(Translator.hasCatalog("xx"))
        assertEquals("en", Translator.select("de", listOf("fr-FR")))
        assertEquals("en", Translator.messages.tag)
        assertEquals(listOf("fr-FR"), Translator.system)
        assertEquals("en", Translator.systemDefault())
        Translator.select("", emptyList())
    }

    @Test
    fun `the saved language is kept as a known tag and cleans garbage`() {
        val store = MemoryKeyValues()
        LanguageSetting.start(store)
        assertEquals("", LanguageSetting.saved.value)
        LanguageSetting.set("pt-br")
        assertEquals("pt-BR", LanguageSetting.saved.value)
        assertEquals("pt-BR", store.getString("ui_language"))
        LanguageSetting.set("klingon")
        assertEquals("", LanguageSetting.saved.value)
        // A new process reads what the last one stored, and a damaged value reads as the system's.
        store.putString("ui_language", "de")
        LanguageSetting.start(store)
        assertEquals("de", LanguageSetting.saved.value)
        store.putString("ui_language", "\u0000\u0000")
        LanguageSetting.start(store)
        assertEquals("", LanguageSetting.saved.value)
        LanguageSetting.start(MemoryKeyValues())
    }
}
