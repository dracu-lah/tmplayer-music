package com.tmplayer.music.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountNamesTest {

    @Test
    fun displayName() {
        assertEquals("Ada Lovelace", AccountNames.display("Ada", "Lovelace"))
        assertEquals("Ada", AccountNames.display("Ada", ""))
        assertEquals("Lovelace", AccountNames.display("  ", "Lovelace "))
        assertEquals("", AccountNames.display(null, null))
    }

    @Test
    fun username() {
        assertEquals("ada", AccountNames.username(arrayOf("ada", "other")))
        assertEquals("ada", AccountNames.username(arrayOf("", "@ada")))
        assertEquals("", AccountNames.username(arrayOf()))
        assertEquals("", AccountNames.username(null))
    }

    @Test
    fun initials() {
        assertEquals("AL", AccountNames.initials("Ada Lovelace"))
        assertEquals("AK", AccountNames.initials("Ada King Lovelace Byron Kingsley"), "first and last word")
        assertEquals("A", AccountNames.initials("ada"))
        assertEquals("", AccountNames.initials(""))
        assertEquals("", AccountNames.initials("   "))
        assertEquals("", AccountNames.initials("🎵 🎶"), "emoji only: no letters")
        assertEquals("R", AccountNames.initials("🎵 Robin"), "the emoji word is skipped")
        assertEquals("ЖБ", AccountNames.initials("жанна борисова"))
        assertEquals("𝐀", AccountNames.initials("𝐀bc"), "a letter outside the BMP stays whole")
        assertEquals("2", AccountNames.initials("2  "))
    }
}
