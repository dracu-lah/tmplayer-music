package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumKeyTest {

    @Test
    fun `no album is no key`() {
        assertEquals("", AlbumKey.of("Nadia Okafor", "Nadia Okafor", "", 2019))
        assertEquals("", AlbumKey.of("Nadia Okafor", "Nadia Okafor", "   ", 2019))
    }

    @Test
    fun `album artist plus album plus year`() {
        assertEquals("nadia okafor\u0001salt year\u00012019", AlbumKey.of("Nadia Okafor", "Ilse Varga", "Salt Year", 2019))
        assertEquals(AlbumKey.of("Nadia Okafor", "", "Salt Year", 2019), AlbumKey.of("NADIA OKAFOR", "", "Salt  Year!", 2019))
        assertNotEquals(AlbumKey.of("Nadia Okafor", "", "Salt Year", 2019), AlbumKey.of("Nadia Okafor", "", "Salt Year", 2021))
        assertNotEquals(AlbumKey.of("Nadia Okafor", "", "Salt Year", 2019), AlbumKey.of("The Pale Rivers", "", "Salt Year", 2019))
    }

    @Test
    fun `with no album artist the track's primary artist is used`() {
        assertEquals(AlbumKey.of("", "Nadia Okafor feat. Ilse Varga", "Salt Year", 2019), AlbumKey.of("", "Nadia Okafor", "Salt Year", 2019))
        assertEquals(AlbumKey.of("", "Nadia Okafor", "Salt Year", 2019), AlbumKey.of("Nadia Okafor", "Ilse Varga", "Salt Year", 2019))
    }

    @Test
    fun `various artists compilations key on album and year only`() {
        val one = AlbumKey.of("Various Artists", "Nadia Okafor", "Harbour Lights Vol. 2", 2020)
        assertEquals(one, AlbumKey.of("various", "The Pale Rivers", "Harbour Lights Vol. 2", 2020))
        assertEquals(one, AlbumKey.of("VA", "Ilse Varga", "Harbour Lights Vol. 2", 2020))
        assertEquals(one, AlbumKey.of("V.A.", "Ilse Varga", "Harbour Lights Vol. 2", 2020))
        assertEquals(one, AlbumKey.of("", "Various Artists", "Harbour Lights Vol. 2", 2020))
        assertNotEquals(one, AlbumKey.of("Various Artists", "Nadia Okafor", "Harbour Lights Vol. 2", 2021))
        assertNotEquals(one, AlbumKey.of("", "", "Harbour Lights Vol. 2", 2020))
    }

    @Test
    fun `odd years count as unknown`() {
        assertEquals(AlbumKey.of("Nadia Okafor", "", "Salt Year", 0), AlbumKey.of("Nadia Okafor", "", "Salt Year", -4))
        assertEquals(AlbumKey.of("Nadia Okafor", "", "Salt Year", 0), AlbumKey.of("Nadia Okafor", "", "Salt Year", 99_999))
    }

    @Test
    fun `artist key folds the whole name without splitting it`() {
        assertEquals("nadia okafor the pale rivers", AlbumKey.artistKey("Nadia Okafor & The Pale Rivers"))
        assertEquals("zoe lindqvist", AlbumKey.artistKey("Zo\u00EB  Lindqvist"))
        assertEquals("", AlbumKey.artistKey("Unknown Artist"))
        assertEquals("", AlbumKey.artistKey(""))
    }
}
