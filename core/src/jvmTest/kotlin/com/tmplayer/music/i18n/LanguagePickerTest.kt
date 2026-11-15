package com.tmplayer.music.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePickerTest {
    private val onlyEnglish = { _: String -> false }

    @Test
    fun `with no catalog shipped only system default and english are listed`() {
        val rows = LanguagePicker.options("", onlyEnglish)
        assertEquals(listOf("", "en"), rows.map { it.tag })
        assertTrue(rows[0].isSystem)
        assertNull(rows[0].name)
        assertEquals("English", rows[1].name)
        assertEquals(listOf(true, false), rows.map { it.selected })
    }

    @Test
    fun `a language shows once its catalog exists`() {
        val has = { tag: String -> tag in setOf("de", "ja", "ar") }
        val rows = LanguagePicker.options("de", has)
        assertEquals(listOf("", "en", "de", "ar", "ja"), rows.map { it.tag })
        assertEquals(listOf("de"), rows.filter { it.selected }.map { it.tag })
        assertEquals("Deutsch", rows.first { it.tag == "de" }.name)
    }

    @Test
    fun `the real catalogs decide on the classpath`() {
        val rows = LanguagePicker.options("", Translator::hasCatalog)
        // Every row beyond system default has a catalog; today that is English alone.
        assertTrue(rows.drop(1).all { Translator.hasCatalog(it.tag) })
        assertEquals("", rows.first { it.selected }.tag)
    }

    @Test
    fun `a saved language whose catalog is gone reads as system default`() {
        val rows = LanguagePicker.options("fr", onlyEnglish)
        assertEquals(listOf("" to true, "en" to false), rows.map { it.tag to it.selected })
        assertEquals("", LanguagePicker.usable("fr", onlyEnglish))
        assertEquals("fr", LanguagePicker.usable("FR", { it == "fr" }))
    }

    @Test
    fun `exactly one row is marked for any saved value`() {
        val has = { tag: String -> tag != "ru" }
        for (saved in listOf(null, "", " ", "en", "EN", "es-419", "ru", "und", "xx", "zh-HK", "\u0000")) {
            assertEquals("saved=$saved", 1, LanguagePicker.options(saved, has).count { it.selected })
        }
    }

    @Test
    fun `a change made on android's language page is adopted once`() {
        // Picked German there while the app held nothing: becomes the in-app choice.
        assertEquals("de", LanguagePicker.adopted(applied = "", saved = "", android = "de-DE"))
        // Cleared there: back to the system's language.
        assertEquals("", LanguagePicker.adopted(applied = "de", saved = "de", android = ""))
        // Nothing changed since the app handed it over.
        assertNull(LanguagePicker.adopted(applied = "de", saved = "de", android = "de"))
        assertNull(LanguagePicker.adopted(applied = "pt-BR", saved = "pt-BR", android = "pt-PT"))
        // A language the app does not know is left alone.
        assertNull(LanguagePicker.adopted(applied = "", saved = "", android = "nl-NL"))
        // Already the in-app choice.
        assertNull(LanguagePicker.adopted(applied = "", saved = "fr", android = "fr"))
    }
}
