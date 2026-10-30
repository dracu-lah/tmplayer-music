package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The library's parsers of outside text (file names, mime types, tags) fed garbage: random text with
 * control characters, lone surrogates, brackets, dashes and 100 KB strings. None may throw, and the
 * results keep their shape (AGENTS.md "Parsers have garbage tests").
 */
class LibraryGarbageTest {

    /** Characters worth mixing in more often than chance would: the ones the rules look at. */
    private val interesting = "()[]{}\u3010\u3011-_.,;&/x \u2013\u2014\u2212\u0000\u0001\u0002\n\t\r\uD800\uDC00\uDBFF\uFFFF\u0301\u3099\u00E9"

    private fun garbage(random: Random): String {
        val length = when (random.nextInt(20)) {
            0 -> 100_000 + random.nextInt(1_000)
            1 -> 0
            else -> random.nextInt(80)
        }
        val out = StringBuilder(length)
        repeat(length) {
            when (random.nextInt(4)) {
                0 -> out.append(interesting[random.nextInt(interesting.length)])
                1 -> out.append(random.nextInt(0x20, 0x7F).toChar())
                else -> out.append(random.nextInt(0, 0x10000).toChar())
            }
        }
        if (random.nextInt(3) == 0) out.append(listOf(".flac", ".mp3", ".LRC", ".", " (Live)", " (feat. X)").random(random))
        return out.toString()
    }

    @Test
    fun `random text never throws and keys keep their shape`() {
        val random = Random(20261030)
        repeat(5_000) { run ->
            val a = garbage(random)
            val b = garbage(random)
            try {
                AudioKinds.classify(a, b)
                AudioKinds.classify(null, a)
                AudioKinds.format(a, b)
                AudioKinds.baseName(a)
                TitleFallback.fromFileName(a)
                TitleFallback.display(a, b, a, b, a)
                GroupKey.normaliseTitle(a)
                GroupKey.variantOf(a)
                GroupKey.primaryArtist(a)
                val key = GroupKey.of(a, b, random.nextLong())
                assertEquals("separators in key $run", 2, key.count { it == GroupKey.SEPARATOR })
                AlbumKey.of(a, b, a, random.nextInt())
                AlbumKey.artistKey(b)
            } catch (e: Throwable) {
                throw AssertionError("run $run threw on input of length ${a.length} and ${b.length}", e)
            }
        }
    }

    @Test
    fun `random durations cluster without throwing`() {
        val random = Random(7)
        repeat(500) {
            val durations = List(random.nextInt(0, 40)) {
                when (random.nextInt(4)) {
                    0 -> 0L
                    1 -> random.nextLong()
                    else -> random.nextLong(1, 1_000_000)
                }
            }
            val known = random.nextBoolean()
            val clusters = GroupKey.clusters(durations, known)
            assertEquals(durations.size, clusters.size)
            assertTrue(clusters.all { it >= 0 })
        }
    }

    @Test
    fun `huge fields are clipped, not processed whole`() {
        val huge = "Glass Harbour ".repeat(10_000)
        assertTrue(GroupKey.normaliseTitle(huge).length <= TextFold.MAX_FIELD)
        assertTrue(GroupKey.of(huge, huge, 0).length <= 3 * TextFold.MAX_FIELD)
        assertTrue(TitleFallback.fromFileName(huge + ".mp3").second.length <= TextFold.MAX_FIELD)
    }
}
