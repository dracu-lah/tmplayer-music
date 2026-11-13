package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastUrlsTest {
    @Test
    fun `tokens are long, url safe and new each time`() {
        val a = CastUrls.newToken()
        val b = CastUrls.newToken()
        assertNotEquals(a, b)
        assertTrue(a.length >= 24)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `a name is stable, plain and hides the key`() {
        val name = CastUrls.nameFor('a', "123:456:song", "flac")
        assertEquals(name, CastUrls.nameFor('a', "123:456:song", "flac"))
        assertNotEquals(name, CastUrls.nameFor('a', "123:457:song", "flac"))
        assertTrue(Regex("a[0-9a-f]{24}\\.flac").matches(name))
        assertFalse("song" in name)
        assertEquals('c', CastUrls.nameFor('c', "k", "jpg")[0])
    }

    @Test
    fun `a hostile extension is made safe`() {
        assertTrue(CastUrls.nameFor('a', "k", "../../etc").endsWith(".etc"))
        assertTrue(CastUrls.nameFor('a', "k", "").endsWith(".bin"))
        assertTrue(CastUrls.nameFor('a', "k", "tool/../x?y").matches(Regex("[A-Za-z0-9._-]+")))
    }

    @Test
    fun `extensions follow the content type`() {
        assertEquals("mp3", CastUrls.extensionFor("audio/mpeg"))
        assertEquals("m4a", CastUrls.extensionFor("audio/mp4; codecs=x"))
        assertEquals("bin", CastUrls.extensionFor("text/html"))
    }

    @Test
    fun `urls bracket an ipv6 host`() {
        assertEquals("http://192.168.1.5:8080/tok/a.mp3", CastUrls.urlFor("192.168.1.5", 8080, "tok", "a.mp3"))
        assertEquals("http://[fe80::1]:1/tok/a.mp3", CastUrls.urlFor("fe80::1", 1, "tok", "a.mp3"))
    }

    @Test
    fun `only the exact token and a plain name get through`() {
        assertEquals("a1.mp3", CastUrls.nameIn("/tok/a1.mp3", "tok"))
        assertNull(CastUrls.nameIn("/other/a1.mp3", "tok"))
        assertNull(CastUrls.nameIn("/tok", "tok"))
        assertNull(CastUrls.nameIn("/tok/", "tok"))
        assertNull(CastUrls.nameIn("/tok/a/b", "tok"))
        assertNull(CastUrls.nameIn("/tok/../a", "tok"))
        assertNull(CastUrls.nameIn("/tok/a b", "tok"))
        assertNull(CastUrls.nameIn("tok/a", "tok"))
        assertNull(CastUrls.nameIn("/" + "t".repeat(5000) + "/a", "tok"))
        assertNull(CastUrls.nameIn("", "tok"))
        assertNull(CastUrls.nameIn("/to\u0000k/a", "tok"))
    }
}
