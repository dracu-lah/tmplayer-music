package com.tmplayer.music.library

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The generated cover's look: a plain function of a stable key, spread out, never throwing. */
class CoverArtTest {

    @Test
    fun sameKeyAlwaysGivesTheSameLook() {
        val key = CoverArt.keyOfAlbum(AlbumKey.of("Night Ferry", "Night Ferry", "Low Tide", 2019))
        val first = CoverArt.seedOf(key)
        repeat(100) { assertEquals(first, CoverArt.seedOf(key)) }
        assertEquals(first, CoverArt.seedOf(String(key.toCharArray())))
    }

    @Test
    fun theLookIsPinnedAcrossVersions() {
        // A change of hash would change every user's covers on update: these must never move.
        assertEquals(
            listOf(CoverSeed(5, 7, true), CoverSeed(3, 11, false), CoverSeed(3, 2, true), CoverSeed(5, 1, false)),
            listOf("", "a:x", "s:y", "a:paper moons\u0001glass hours\u00012021").map { CoverArt.seedOf(it) },
        )
    }

    @Test
    fun everyTrackOfAnAlbumSharesTheCover() {
        fun track(message: Long, title: String) = Track(
            chatId = 1, messageId = message, fileId = 0, uniqueId = "u$message", fileName = "$title.mp3", mime = "audio/mpeg",
            sizeBytes = 1, dateSec = 0, title = title, artist = "Paper Moons", album = "Glass Hours", albumArtist = "Paper Moons",
            year = 2021, groupKey = "g:$title",
        )
        val keys = listOf(track(1, "One"), track(2, "Two"), track(3, "Three")).map { CoverArt.keyOf(it) }.toSet()
        assertEquals(1, keys.size)
        val album = AlbumKey.of("Paper Moons", "Paper Moons", "Glass Hours", 2021)
        assertEquals(CoverArt.keyOfAlbum(album), keys.single())
        assertEquals(keys.single(), CoverArt.keyOf(album, "anything"))
    }

    @Test
    fun aSongWithNoAlbumUsesItsBaseKey() {
        val loose = Track(
            chatId = 1, messageId = 9, fileId = 0, uniqueId = "u9", fileName = "x.mp3", mime = "audio/mpeg",
            sizeBytes = 1, dateSec = 0, title = "Loose", artist = "Someone", groupKey = "base",
        )
        assertEquals(CoverArt.keyOf("", "base"), CoverArt.keyOf(loose))
        // A song split into clusters keeps one cover across its clusters.
        assertEquals(CoverArt.keyOf("", "base"), CoverArt.keyOf("", "base${KEY_SEPARATOR}d2"))
        assertNotEquals(CoverArt.keyOf("", "base"), CoverArt.keyOfAlbum("base"))
    }

    @Test
    fun differentAlbumsSpreadOverStylesAndPalettes() {
        val seeds = (0 until 2_000).map { i ->
            CoverArt.seedOf(CoverArt.keyOfAlbum(AlbumKey.of("Artist $i", "Artist $i", "Album ${i % 37}", 1990 + i % 30)))
        }
        val styles = seeds.groupingBy { it.style }.eachCount()
        val palettes = seeds.groupingBy { it.palette }.eachCount()
        assertEquals((0 until CoverArt.STYLES).toSet(), styles.keys)
        assertEquals(CoverArt.palettes.indices.toSet(), palettes.keys)
        val perStyle = seeds.size / CoverArt.STYLES
        val perPalette = seeds.size / CoverArt.palettes.size
        styles.values.forEach { assertTrue(it in perStyle / 2..perStyle * 3 / 2, "styles $styles") }
        palettes.values.forEach { assertTrue(it in perPalette / 2..perPalette * 3 / 2, "palettes $palettes") }
        val mirrored = seeds.count { it.mirrored }
        assertTrue(mirrored in seeds.size / 3..seeds.size * 2 / 3)
        // Neighbouring keys do not all land on one look.
        val neighbours = (1..10).map { CoverArt.seedOf("a:Album $it") }.toSet()
        assertTrue(neighbours.size >= 7, "$neighbours")
    }

    @Test
    fun emptyOrOddKeysNeverThrow() {
        val random = Random(17)
        val odd = listOf(
            "", " ", "\u0000", "\u0001\u0001", "\uD83C", "\uDFB5\uD83C", "🎵🎶", "a:", "s:", "x".repeat(100_000),
            String(CharArray(64) { (random.nextInt(0, 0xFFFF)).toChar() }),
        )
        for (key in odd) {
            val seed = CoverArt.seedOf(key)
            assertTrue(seed.style in 0 until CoverArt.STYLES)
            assertTrue(seed.palette in CoverArt.palettes.indices)
            CoverArt.keyOf(key, key)
        }
        assertEquals(CoverArt.keyOf("", ""), CoverArt.keyOf("  ", ""))
    }

    @Test
    fun palettesAreThreeOpaqueColours() {
        for (palette in CoverArt.palettes) {
            assertEquals(3, palette.size)
            palette.forEach { assertEquals(0xFF, (it ushr 24) and 0xFF) }
        }
    }
}
