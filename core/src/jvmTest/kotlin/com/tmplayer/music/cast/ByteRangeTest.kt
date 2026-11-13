package com.tmplayer.music.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ByteRangeTest {
    private val total = 1000L

    @Test
    fun `a start and an end`() = assertEquals(ByteRange.Span(10, 19), ByteRange.parse("bytes=10-19", total))

    @Test
    fun `an open end runs to the last byte`() = assertEquals(ByteRange.Span(990, 999), ByteRange.parse("bytes=990-", total))

    @Test
    fun `a suffix is the last bytes`() = assertEquals(ByteRange.Span(900, 999), ByteRange.parse("bytes=-100", total))

    @Test
    fun `a suffix longer than the file is the whole file`() = assertEquals(ByteRange.Span(0, 999), ByteRange.parse("bytes=-5000", total))

    @Test
    fun `an end past the file is cut to it`() = assertEquals(ByteRange.Span(0, 999), ByteRange.parse("bytes=0-99999999999", total))

    @Test
    fun `a start past the end cannot be satisfied`() = assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=1000-", total))

    @Test
    fun `an end before the start cannot be satisfied`() = assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=50-10", total))

    @Test
    fun `an empty file cannot be ranged`() = assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=0-", 0))

    @Test
    fun `a zero suffix cannot be satisfied`() = assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", total))

    @Test
    fun `no header, other units and several ranges send the whole file`() {
        assertNull(ByteRange.parse(null, total))
        assertNull(ByteRange.parse("", total))
        assertNull(ByteRange.parse("items=0-5", total))
        assertNull(ByteRange.parse("bytes=0-5,10-20", total))
    }

    @Test
    fun `garbage is never a range and never throws`() {
        val cases = listOf(
            "bytes=", "bytes=-", "bytes=--", "bytes=a-b", "bytes=1-b", "bytes=+5-9", "bytes=-+5", "bytes=1 0-20", "bytes=99999999999999999999-",
            "bytes=0-99999999999999999999999", "bytes=-99999999999999999999", "bytes=\u0000-\u0000", "bytes==1-2", "BYTES=3-4", "bytes=1-2-3",
            "bytes=0x10-0x20", "bytes=١-٢", "x".repeat(10_000), "bytes=" + "9".repeat(10_000) + "-",
        )
        for (case in cases) {
            val result = ByteRange.parse(case, total)
            if (result is ByteRange.Span) {
                assertTrue("$case gave $result", result.start in 0..result.endInclusive && result.endInclusive < total)
            }
        }
        // Digits that overflow a Long are not understood, so the whole file is sent.
        assertNull(ByteRange.parse("bytes=99999999999999999999-", total))
        // Signs are not digits.
        assertNull(ByteRange.parse("bytes=+5-9", total))
    }

    @Test
    fun `random text stays inside the file`() {
        val random = Random(7)
        val alphabet = "bytes=-0123456789, x"
        repeat(5_000) {
            val text = buildString { repeat(random.nextInt(0, 30)) { append(alphabet.random(random)) } }
            val header = if (random.nextBoolean()) "bytes=$text" else text
            val size = random.nextLong(-5, 5000)
            val result = ByteRange.parse(header, size)
            if (result is ByteRange.Span) {
                assertTrue("$header of $size gave $result", result.start >= 0 && result.start <= result.endInclusive && result.endInclusive < size)
            }
        }
    }
}
