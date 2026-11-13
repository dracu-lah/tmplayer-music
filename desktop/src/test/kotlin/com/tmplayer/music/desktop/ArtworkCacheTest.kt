package com.tmplayer.music.desktop

import com.tmplayer.music.desktop.ui.common.ByteBoundedLru
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkCacheTest {
    private fun cache(cap: Long) = ByteBoundedLru<String, Long>(cap) { it }

    @Test
    fun `one big picture pushes out as many small ones as it needs to`() {
        val c = cache(100)
        repeat(10) { c.put("small$it", 10) }
        assertEquals(100, c.total)
        c.put("big", 70)
        assertTrue(c.total <= 100, "total ${c.total}")
        assertNotNull(c["big"])
        // 70 plus three of 10 fit: the oldest seven went, not just one.
        assertNull(c["small0"])
        assertNull(c["small6"])
        assertNotNull(c["small9"])
    }

    @Test
    fun `reading a picture keeps it from being the oldest`() {
        val c = cache(30)
        c.put("a", 10)
        c.put("b", 10)
        c.put("c", 10)
        c["a"]
        c.put("d", 10)
        assertNotNull(c["a"])
        assertNull(c["b"])
        assertEquals(30, c.total)
    }

    @Test
    fun `replacing a key does not count it twice`() {
        val c = cache(100)
        c.put("a", 40)
        c.put("a", 60)
        assertEquals(60, c.total)
        assertEquals(1, c.size)
    }

    @Test
    fun `a single picture over the cap stays, alone`() {
        val c = cache(50)
        c.put("a", 20)
        c.put("huge", 500)
        assertNotNull(c["huge"])
        assertNull(c["a"])
        assertEquals(1, c.size)
    }
}
