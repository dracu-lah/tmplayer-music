package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The A to Z bar's letters: one each, look-alikes folded (the bar showed A L M again after Z). */
class LettersTest {

    @Test
    fun `look-alike letters fold to the plain capital`() {
        assertEquals('A', Letters.of("a"))
        assertEquals('A', Letters.of("ᴀ"))
        assertEquals('L', Letters.of("ʟ"))
        assertEquals('M', Letters.of("ᴍ"))
        assertEquals('A', Letters.of("ａ"))
        assertEquals('A', Letters.of("Ａ"))
        assertEquals('E', Letters.of("é"))
        assertEquals('A', Letters.of("ⓐ"))
        // Mathematical bold capital A, a surrogate pair.
        assertEquals('A', Letters.of("𝐀"))
    }

    @Test
    fun `other scripts stand for themselves and the rest is a hash`() {
        assertEquals('Ж', Letters.of("ж"))
        assertEquals('あ', Letters.of("あ"))
        assertEquals('#', Letters.of("7"))
        assertEquals('#', Letters.of(""))
        assertEquals('#', Letters.of("\uD800"))
        assertEquals('#', Letters.of(SORT_LAST))
        assertEquals('#', Letters.of("\u0000"))
    }

    @Test
    fun `a letter met again after Z keeps its first place`() {
        val groups = listOf(
            "1" to 3L, "a" to 10L, "l" to 5L, "m" to 4L, "z" to 2L,
            "ж" to 6L, "ᴀ" to 1L, "ʟ" to 1L, "ᴍ" to 2L, "ａ" to 1L, SORT_LAST to 3L,
        )
        val bar = Letters.index(groups)
        assertEquals(listOf('#', 'A', 'L', 'M', 'Z', 'Ж'), bar.map { it.first })
        assertEquals(listOf(0, 3, 13, 18, 22, 24), bar.map { it.second })
        assertEquals(bar.size, bar.map { it.first }.toSet().size)
    }

    @Test
    fun `garbage groups never throw`() {
        val random = kotlin.random.Random(5)
        repeat(2_000) {
            val text = String(CharArray(random.nextInt(0, 4)) { random.nextInt(0, 0xFFFF).toChar() })
            Letters.of(text)
        }
        Letters.index(listOf("a" to -5L, "b" to Long.MAX_VALUE, "c" to 1L))
    }
}
