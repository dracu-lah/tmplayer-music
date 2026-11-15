package com.tmplayer.music.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's per-app language list offers exactly the languages the registry knows. From TMPlayer's
 * LocalesConfigTest (GPL-3.0). Gradle runs the tests from the module directory.
 */
class LocalesConfigTest {

    @Test
    fun `locales_config lists every known language in order`() {
        val xml = File("../app/src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""<locale android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(Languages.tags, listed)
    }

    @Test
    fun `the manifest points at the locale config`() {
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:localeConfig="@xml/locales_config""""))
    }
}
