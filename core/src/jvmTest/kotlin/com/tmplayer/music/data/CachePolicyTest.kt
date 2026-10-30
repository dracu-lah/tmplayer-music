package com.tmplayer.music.data

import com.tmplayer.music.data.CachePolicy.Decision
import com.tmplayer.music.data.CachePolicy.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {

    private val gb = 1024 * MB

    @Test
    fun `the ceiling is the choice on a roomy phone`() {
        assertEquals(2 * gb, CachePolicy.ceiling(2 * gb, 128 * gb))
    }

    @Test
    fun `the ceiling never takes more than its share of a small disk`() {
        assertEquals((16 * gb * 0.4).toLong(), CachePolicy.ceiling(CachePolicy.UNBOUNDED, 16 * gb))
    }

    @Test
    fun `the ceiling leaves the device room and has a floor`() {
        assertEquals(CachePolicy.MIN_CEILING, CachePolicy.ceiling(2 * gb, 2 * gb))
        assertEquals(CachePolicy.MIN_CEILING, CachePolicy.ceiling(2 * gb, gb / 2))
    }

    @Test
    fun `an unknown disk size keeps the choice`() {
        assertEquals(gb, CachePolicy.ceiling(gb, 0))
    }

    @Test
    fun `enough free space proceeds`() {
        assertEquals(Decision.Proceed, CachePolicy.beforeDownload(40 * MB, 10 * gb, 0))
    }

    @Test
    fun `a full disk with a big cache trims the cache first, by just enough`() {
        val decision = CachePolicy.beforeDownload(40 * MB, 20 * MB, 1 * gb)
        assertTrue(decision is Decision.TrimFirst)
        val shortfall = 40 * MB + CachePolicy.HEADROOM - 20 * MB
        assertEquals(gb - shortfall, (decision as Decision.TrimFirst).targetBytes)
    }

    @Test
    fun `a full disk with an empty cache says how much is needed and free`() {
        assertEquals(Decision.NotEnough(40 * MB + CachePolicy.HEADROOM, 20 * MB), CachePolicy.beforeDownload(40 * MB, 20 * MB, 0))
    }

    @Test
    fun `a cache too small to make room is not enough either`() {
        assertTrue(CachePolicy.beforeDownload(500 * MB, 0, 100 * MB) is Decision.NotEnough)
    }

    @Test
    fun `an unknown size is not waved through on a full disk`() {
        assertTrue(CachePolicy.beforeDownload(0, 10 * MB, 0) is Decision.NotEnough)
        assertEquals(Decision.Proceed, CachePolicy.beforeDownload(-1, 10 * gb, 0))
    }

    @Test
    fun `negative readings from a broken disk query do not crash or proceed`() {
        assertTrue(CachePolicy.beforeDownload(40 * MB, -5, -5) is Decision.NotEnough)
    }

    @Test
    fun `a 1 GB file streams only with room for it`() {
        assertTrue(CachePolicy.beforeDownload(gb, 900 * MB, 0) is Decision.NotEnough)
        assertTrue(CachePolicy.beforeDownload(gb, 900 * MB, 2 * gb) is Decision.TrimFirst)
    }
}
