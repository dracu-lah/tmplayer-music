package com.tmplayer.music.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The English catalog, checked the way the app reads it: on the classpath, valid ICU, keys sorted
 * at every level and shaped as the generator expects, no em or en dash anywhere. From TMPlayer's
 * CatalogTest (GPL-3.0), English only until other languages ship.
 */
class CatalogTest {

    // Gradle runs the tests from the module directory.
    private val englishFile = File("src/commonMain/resources/i18n/en.json")
    private val english: Map<String, String> = Translator.parse(englishFile.readText())

    @Test
    fun `the english catalog is on the classpath and matches the source`() {
        assertEquals(english, Translator.parse(Translator.resource("en")!!))
    }

    @Test
    fun `every english message is valid icu`() {
        for ((key, text) in english) {
            runCatching { Icu.parse(text) }.onFailure { fail("en $key: ${it.message}") }
        }
    }

    @Test
    fun `keys are sorted at every level`() {
        // Translator.parse keeps file order, so sorted file order means sorted dotted keys.
        assertEquals(english.keys.sorted(), english.keys.toList())
    }

    @Test
    fun `keys are lower snake case under a namespace`() {
        val shape = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+")
        for (key in english.keys) assertTrue("bad key $key", shape.matches(key))
    }

    @Test
    fun `the generated accessors cover the catalog exactly`() {
        assertEquals(english.keys.sorted(), CATALOG_KEYS)
    }

    @Test
    fun `no em or en dashes`() {
        for ((key, text) in english) {
            assertFalse("dash in $key", text.contains('\u2014') || text.contains('\u2013'))
        }
    }

    @Test
    fun `plurals read in english`() {
        assertEquals("1 second", L.unitSeconds(1))
        assertEquals("5 minutes", L.unitMinutes(5))
        assertEquals("Hint: cat", L.loginPasswordHint("cat"))
    }
}
