package com.tmplayer.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3U8 export, reading a list in its common shapes, and matching entries to songs without guessing. */
class M3uTest {

    private fun song(title: String, artist: String, durationMs: Long = 200_000, key: String = GroupKey.of(title, artist, 0) + "\u0001d0") = Song(
        groupKey = key, title = title, artist = artist, album = "", durationMs = durationMs,
        defaultTrack = TrackId(1, key.hashCode().toLong().coerceAtLeast(1)), versionCount = 1, bestFormat = AudioFormat.MP3,
        thumbFileId = 0, coverKey = "", variant = GroupKey.variantOf(title), addedSec = 0,
    )

    private class Fake(val songs: List<Song>) : M3u.SongSource {
        override suspend fun byBase(bases: Collection<String>): Map<String, List<Song>> =
            songs.groupBy { baseOfSongKey(it.groupKey) }.filterKeys { it in bases }

        override suspend fun byTitle(title: String): List<Song> {
            val want = GroupKey.normaliseTitle(title)
            return songs.filter { GroupKey.normaliseTitle(it.title).contains(want) }
        }
    }

    @Test
    fun `export writes the header, a duration or minus one, and artist dash title`() {
        val text = M3u.export("Evening", listOf(song("Velo Marin", "Korvane", 200_400), song("No Length", "", 0)))
        assertEquals(
            "#EXTM3U\n#PLAYLIST:Evening\n#EXTINF:200,Korvane - Velo Marin\nKorvane - Velo Marin\n#EXTINF:-1,No Length\nNo Length\n",
            text,
        )
    }

    @Test
    fun `export keeps every entry on two lines whatever the names hold`() {
        val text = M3u.export("a\nb", listOf(song("Line\r\nBreak\u0000", "Ar\ntist")))
        val lines = text.trimEnd().split('\n')
        assertEquals(4, lines.size)
        assertEquals("#PLAYLIST:a b", lines[1])
        assertEquals("#EXTINF:200,Ar tist - Line Break", lines[2])
    }

    @Test
    fun `an exported list reads back to the same entries`() {
        val songs = listOf(song("Velo Marin", "Korvane", 200_000), song("Lumen Tide", "Pell", 181_000))
        val parsed = M3u.parse(M3u.export("Mix", songs))
        assertEquals("Mix", parsed.name)
        assertEquals(listOf("Velo Marin" to "Korvane", "Lumen Tide" to "Pell"), parsed.entries.map { it.title to it.artist })
        assertEquals(listOf(200_000L, 181_000L), parsed.entries.map { it.durationMs })
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `extinf, paths, addresses and plain names all give a title and an artist`() {
        val text = "﻿#EXTM3U\r\n#EXTINF:123,Korvane - Velo Marin\r\n/music/whatever/01.mp3\r\n" +
            "#EXTINF:-1,Only Title\r\nC:\\Music\\x.flac\r\n" +
            "/storage/Pell - Lumen%20Tide.mp3\r\n" +
            "https://example.com/a/03 - Odd Name.ogg?token=1\r\n" +
            "Plain Artist - Plain Title\r\n" +
            "# a comment\r\n\r\n   \r\n" +
            "#EXTINF:abc\r\nLonely Title\r\n"
        val parsed = M3u.parse(text)
        assertEquals(
            listOf(
                "Velo Marin" to "Korvane",
                "Only Title" to "",
                "Lumen Tide" to "Pell",
                "Odd Name" to "",
                "Plain Title" to "Plain Artist",
                "Lonely Title" to "",
            ),
            parsed.entries.map { it.title to it.artist },
        )
        assertEquals(123_000L, parsed.entries.first().durationMs)
        assertEquals(0L, parsed.entries[1].durationMs)
    }

    @Test
    fun `an absurd or missing duration counts as unknown, a bare newline list works`() {
        val parsed = M3u.parse("#EXTINF:99999999999,A - B\nx\n#EXTINF:1.5e3,C - D\ny\n")
        assertEquals(listOf(0L, 1_000L), parsed.entries.map { it.durationMs })
        assertEquals(2, M3u.parse("One\nTwo").entries.size)
    }

    @Test
    fun `too long, blank and too many lines are skipped or cut, never thrown`() {
        val long = "x".repeat(M3u.MAX_LINE + 1)
        val parsed = M3u.parse("#EXTM3U\n$long\nGood - Song\n")
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.skipped)
        val many = M3u.parse((1..M3u.MAX_ENTRIES + 50).joinToString("\n") { "Artist - Song $it" })
        assertEquals(M3u.MAX_ENTRIES, many.entries.size)
        assertTrue(many.truncated)
        val huge = M3u.parse("a".repeat(M3u.MAX_CHARS + 10))
        assertTrue(huge.truncated)
        assertTrue(M3u.parse(null).entries.isEmpty())
        assertTrue(M3u.parse("").entries.isEmpty())
    }

    @Test
    fun `matching by artist and title ignores case, accents, noise and feat`() = runBlocking {
        val library = Fake(listOf(song("Velo Marin", "Korvane"), song("Café Nuit (Official Audio)", "Émile feat. Pell")))
        val entries = M3u.parse("korvane - VELO MARIN\nEmile - Cafe Nuit\nNobody - Missing\n").entries
        val result = M3u.match(entries, library)
        assertEquals(listOf("Velo Marin", "Café Nuit (Official Audio)"), result.songs.map { it.title })
        assertEquals(listOf("Nobody - Missing"), result.unmatched.map { it.entry.label })
        assertEquals(M3u.Miss.NotFound, result.unmatched.single().reason)
        assertEquals(3, result.total)
    }

    @Test
    fun `a live version never matches the studio song`() = runBlocking {
        val library = Fake(listOf(song("Velo Marin", "Korvane")))
        val result = M3u.match(M3u.parse("Korvane - Velo Marin (Live)").entries, library)
        assertTrue(result.matched.isEmpty())
        assertEquals(1, result.unmatched.size)
    }

    @Test
    fun `the length picks between two recordings of one song`() = runBlocking {
        val short = song("Velo Marin", "Korvane", 150_000, GroupKey.of("Velo Marin", "Korvane", 0) + "\u0001d0")
        val long = song("Velo Marin", "Korvane", 260_000, GroupKey.of("Velo Marin", "Korvane", 0) + "\u0001d1")
        val library = Fake(listOf(short, long))
        val near = M3u.match(M3u.parse("#EXTINF:258,Korvane - Velo Marin\nx").entries, library)
        assertEquals(long.groupKey, near.songs.single().groupKey)
        val none = M3u.match(M3u.parse("Korvane - Velo Marin").entries, library)
        assertEquals(1, none.songs.size)
    }

    @Test
    fun `a title alone names one song or is ambiguous, never a guess`() = runBlocking {
        val library = Fake(listOf(song("Lumen Tide", "Pell", 180_000), song("Lumen Tide", "Korvane", 240_000), song("Solo Song", "Pell")))
        val result = M3u.match(M3u.parse("Solo Song\nLumen Tide\n#EXTINF:241,Lumen Tide\nx\n").entries, library)
        assertEquals(listOf("Solo Song", "Lumen Tide"), result.songs.map { it.title })
        assertEquals("Korvane", result.songs.last().artist)
        val ambiguous = M3u.match(M3u.parse("Lumen Tide").entries, library)
        assertEquals(M3u.Miss.Ambiguous, ambiguous.unmatched.single().reason)
    }

    @Test
    fun `the same song twice is added once and counted`() = runBlocking {
        val result = M3u.match(M3u.parse("Korvane - Velo Marin\nKORVANE - velo marin\n").entries, Fake(listOf(song("Velo Marin", "Korvane"))))
        assertEquals(1, result.matched.size)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun `a source that fails matches nothing and does not throw`() = runBlocking {
        val broken = object : M3u.SongSource {
            override suspend fun byBase(bases: Collection<String>): Map<String, List<Song>> = error("boom")
            override suspend fun byTitle(title: String): List<Song> = error("boom")
        }
        val result = M3u.match(M3u.parse("A - B\nC").entries, broken)
        assertEquals(2, result.unmatched.size)
        assertNull(result.matched.firstOrNull())
        assertFalse(result.matched.any())
    }
}
