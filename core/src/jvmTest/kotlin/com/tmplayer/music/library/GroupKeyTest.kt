package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A track with only the fields a test cares about; the rest are plausible defaults. */
internal fun track(
    messageId: Long,
    uniqueId: String = "u$messageId",
    chatId: Long = 1,
    fileId: Int = messageId.toInt(),
    dateSec: Long = 1_000 + messageId,
    sizeBytes: Long = 5_000_000,
    durationMs: Long = 210_000,
    format: AudioFormat = AudioFormat.MP3,
    bitrateKbps: Int = 0,
    album: String = "",
    albumArtist: String = "",
    year: Int = 0,
    trackNumber: Int = 0,
    thumbFileId: Int = 0,
    coverKey: String = "",
): Track = Track(
    chatId = chatId,
    messageId = messageId,
    fileId = fileId,
    uniqueId = uniqueId,
    fileName = "Glass Harbour.mp3",
    mime = "audio/mpeg",
    sizeBytes = sizeBytes,
    dateSec = dateSec,
    title = "Glass Harbour",
    artist = "Nadia Okafor",
    album = album,
    albumArtist = albumArtist,
    year = year,
    trackNumber = trackNumber,
    durationMs = durationMs,
    format = format,
    bitrateKbps = bitrateKbps,
    thumbFileId = thumbFileId,
    coverKey = coverKey,
)

class GroupKeyTest {

    private fun key(title: String, artist: String = "Nadia Okafor") = GroupKey.of(title, artist, 210_000)

    @Test
    fun `case, accents and punctuation fold`() {
        assertEquals(key("Cafe Lights"), key("Caf\u00E9 Lights"))
        assertEquals(key("CAFE LIGHTS"), key("caf\u00E9   lights!"))
        assertEquals(key("Don't Wait"), key("Dont Wait"))
        assertEquals(key("Don\u2019t Wait"), key("Don't Wait"))
        assertEquals("cafe lights", GroupKey.normaliseTitle("  Caf\u00E9, Lights... "))
        assertEquals("strasse", GroupKey.normaliseTitle("Stra\u00DFe"))
        assertEquals("\u03BA\u03B1\u03C6\u03B5\u03C3", GroupKey.normaliseTitle("\u039A\u03B1\u03C6\u03AD\u03C2"))
    }

    @Test
    fun `cyrillic folds its own accents and stays cyrillic`() {
        val title = "\u0401\u043B\u043A\u0430 \u0438 \u0432\u0435\u0442\u0435\u0440"
        assertEquals("\u0435\u043B\u043A\u0430 \u0438 \u0432\u0435\u0442\u0435\u0440", GroupKey.normaliseTitle(title))
        assertEquals(key(title), key("\u0415\u043B\u043A\u0430 \u0438 \u0432\u0435\u0442\u0435\u0440"))
        assertNotEquals(key(title), key("Elka i veter"))
    }

    @Test
    fun `japanese stays intact and folds only with itself`() {
        val title = "\u304C\u3089\u3059\u306E\u6E2F"
        assertEquals(title, GroupKey.normaliseTitle(title))
        // A decomposed kana (base plus voicing mark) is the same title once composed again.
        assertEquals(title, GroupKey.normaliseTitle("\u304B\u3099\u3089\u3059\u306E\u6E2F"))
        // Full-width letters are NFKC folded, so they match their ASCII form.
        assertEquals(key("Glass"), key("\uFF27\uFF4C\uFF41\uFF53\uFF53"))
        assertNotEquals(key(title), key("\u304B\u3089\u3059\u306E\u6E2F"))
        assertNotEquals(key(title), key("Garasu no Minato"))
    }

    @Test
    fun `other scripts keep their marks`() {
        val hindi = "\u0939\u093F\u0928\u094D\u0926\u0940"
        assertEquals(hindi, GroupKey.normaliseTitle(hindi))
        val hebrew = "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD"
        assertEquals(java.text.Normalizer.normalize(hebrew, java.text.Normalizer.Form.NFKC), GroupKey.normaliseTitle(hebrew))
    }

    @Test
    fun `featured artists are removed from the title`() {
        val plain = key("Glass Harbour")
        assertEquals(plain, key("Glass Harbour (feat. Ilse Varga)"))
        assertEquals(plain, key("Glass Harbour [ft. Ilse Varga]"))
        assertEquals(plain, key("Glass Harbour (Featuring Ilse Varga & Tomas Reyes)"))
        assertEquals(plain, key("Glass Harbour feat. Ilse Varga"))
        assertEquals(plain, key("Glass Harbour ft Ilse Varga"))
        assertEquals("glass harbour", GroupKey.normaliseTitle("Glass Harbour feat. Ilse Varga (Official Audio)"))
    }

    @Test
    fun `bracket noise is removed`() {
        val plain = key("Glass Harbour")
        for (noise in listOf("(Official Audio)", "(Official Video)", "[Official Music Video]", "(Lyrics)", "(Lyric Video)",
            "(Audio)", "[HQ]", "[HD]", "(320kbps)", "(Free Download)", "(Visualizer)")) {
            assertEquals(noise, plain, key("Glass Harbour $noise"))
        }
        assertEquals(plain, key("Glass Harbour - Official Audio"))
        assertNotEquals(plain, key("Glass Harbour (Night Version Two)"))
    }

    @Test
    fun `variants are read from brackets and dash suffixes only`() {
        assertEquals("live", GroupKey.variantOf("Glass Harbour (Live)"))
        assertEquals("live", GroupKey.variantOf("Glass Harbour - Live at the Corn Hall"))
        assertEquals("live", GroupKey.variantOf("Glass Harbour \u2013 Live"))
        assertEquals("remix", GroupKey.variantOf("Glass Harbour (Tomas Reyes Remix)"))
        assertEquals("remix", GroupKey.variantOf("Glass Harbour [RMX]"))
        assertEquals("acoustic", GroupKey.variantOf("Glass Harbour (Acoustic Version)"))
        assertEquals("instrumental", GroupKey.variantOf("Glass Harbour (Instrumental)"))
        assertEquals("karaoke", GroupKey.variantOf("Glass Harbour (Karaoke Version)"))
        assertEquals("radio edit", GroupKey.variantOf("Glass Harbour (Radio Edit)"))
        assertEquals("remaster", GroupKey.variantOf("Glass Harbour (Remastered 2011)"))
        assertEquals("remaster", GroupKey.variantOf("Glass Harbour - 2011 Remaster"))
        assertEquals("demo", GroupKey.variantOf("Glass Harbour (Demo)"))
        assertEquals("cover", GroupKey.variantOf("Glass Harbour (Cover)"))
        assertEquals("extended", GroupKey.variantOf("Glass Harbour (Extended Mix)"))
        assertEquals("slowed", GroupKey.variantOf("Glass Harbour (Slowed + Reverb)"))
        assertEquals("sped up", GroupKey.variantOf("Glass Harbour (Sped Up)"))
        assertEquals("nightcore", GroupKey.variantOf("Glass Harbour [Nightcore]"))
        assertEquals("live", GroupKey.variantOf("Glass Harbour (Live) [Remastered]"))
        assertEquals("", GroupKey.variantOf("Glass Harbour"))
        assertEquals("", GroupKey.variantOf("Live Forever Tonight"))
        assertEquals("", GroupKey.variantOf("Cover Me Slowly"))
        assertEquals("", GroupKey.variantOf("Glass Harbour (Alive Again)"))
        assertEquals("", GroupKey.variantOf("Glass Harbour (feat. Live Wire)"))
        assertEquals("", GroupKey.variantOf(""))
    }

    @Test
    fun `live and studio are different keys, live versions fold together`() {
        assertNotEquals(key("Glass Harbour"), key("Glass Harbour (Live)"))
        assertEquals(key("Glass Harbour (Live)"), key("Glass Harbour [Live Version]"))
        assertEquals(key("Glass Harbour (Live)"), key("Glass Harbour - Live"))
        assertNotEquals(key("Glass Harbour (Live)"), key("Glass Harbour (Acoustic)"))
    }

    @Test
    fun `a remaster is a variant`() {
        assertNotEquals(key("Glass Harbour"), key("Glass Harbour (Remastered 2011)"))
        assertEquals(key("Glass Harbour (Remastered 2011)"), key("Glass Harbour - 2011 Remaster"))
        assertEquals(key("Glass Harbour (Remastered 2011)"), key("Glass Harbour [Remastered]"))
    }

    @Test
    fun `the key has artist, title and variant`() {
        assertEquals("nadia okafor\u0001glass harbour\u0001live", key("Glass Harbour (Live)"))
        assertEquals("nadia okafor\u0001glass harbour\u0001", key("Glass Harbour"))
    }

    @Test
    fun `primary artist is the first name of a credit`() {
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor feat. Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor ft. Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor & Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor x Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor X Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor, Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor; Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor / Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("Nadia Okafor\u0000Ilse Varga"))
        assertEquals("nadia okafor", GroupKey.primaryArtist("  NADIA  Okafor  "))
        assertEquals("zoe lindqvist", GroupKey.primaryArtist("Zo\u00EB Lindqvist"))
        assertEquals("", GroupKey.primaryArtist(""))
        assertEquals("", GroupKey.primaryArtist("Unknown Artist"))
        assertEquals("", GroupKey.primaryArtist("<unknown>"))
    }

    @Test
    fun `a slash or ampersand without spaces keeps the name whole`() {
        assertEquals("rx kt", GroupKey.primaryArtist("RX/KT"))
        assertEquals("rx kt", GroupKey.primaryArtist("RX/KT feat. Ilse Varga"))
        assertEquals("bolt nut", GroupKey.primaryArtist("Bolt&Nut"))
        assertEquals("maxx", GroupKey.primaryArtist("Maxx"))
    }

    @Test
    fun `different artists never share a key`() {
        assertNotEquals(key("Glass Harbour", "Nadia Okafor"), key("Glass Harbour", "The Pale Rivers"))
        assertNotEquals(key("Glass Harbour", "Nadia Okafor"), key("Glass Harbour", ""))
        assertEquals(key("Glass Harbour", "Nadia Okafor"), key("Glass Harbour", "Nadia Okafor & The Pale Rivers"))
        assertTrue(GroupKey.artistKnown(key("Glass Harbour")))
        assertFalse(GroupKey.artistKnown(key("Glass Harbour", "")))
        assertFalse(GroupKey.artistKnown(""))
    }

    @Test
    fun `with no artist the title must be the same text`() {
        assertEquals(key("Glass Harbour", ""), key("  glass   HARBOUR ", ""))
        assertNotEquals(key("Glass Harbour", ""), key("Glass Harbour (Official Audio)", ""))
        assertNotEquals(key("Cafe Lights", ""), key("Caf\u00E9 Lights", ""))
    }

    @Test
    fun `a key never contains its separator from the input`() {
        val k = GroupKey.of("Glass\u0001Harbour", "Nadia\u0001Okafor", 0)
        assertEquals(2, k.count { it == GroupKey.SEPARATOR })
        val noArtist = GroupKey.of("Glass\u0001Harbour", "", 0)
        assertEquals(2, noArtist.count { it == GroupKey.SEPARATOR })
    }

    @Test
    fun `same recording within three seconds or two percent when the artist is known`() {
        assertTrue(GroupKey.sameRecording(210_000, 211_000, true))
        // Under two and a half minutes the 3 second floor is the limit.
        assertTrue(GroupKey.sameRecording(100_000, 103_000, true))
        assertFalse(GroupKey.sameRecording(100_000, 103_001, true))
        assertFalse(GroupKey.sameRecording(210_000, 250_000, true))
        // Two percent of a ten minute track is 12 seconds.
        assertTrue(GroupKey.sameRecording(600_000, 588_000, true))
        assertFalse(GroupKey.sameRecording(600_000, 587_000, true))
        assertTrue(GroupKey.sameRecording(0, 250_000, true))
        assertTrue(GroupKey.sameRecording(-5, 0, true))
    }

    @Test
    fun `with no artist only a second apart is the same, and unknown never is`() {
        assertTrue(GroupKey.sameRecording(210_000, 211_000, false))
        assertFalse(GroupKey.sameRecording(210_000, 211_001, false))
        assertFalse(GroupKey.sameRecording(0, 210_000, false))
        assertFalse(GroupKey.sameRecording(0, 0, false))
        assertTrue(GroupKey.sameRecording(Long.MAX_VALUE, Long.MAX_VALUE, false))
    }

    @Test
    fun `clusters split a key by duration`() {
        // 3:30 and 3:31 are one recording; 4:10 is another.
        assertEquals(listOf(0, 0, 1), GroupKey.clusters(listOf(210_000, 211_000, 250_000), true))
        // Input order does not change the numbering.
        assertEquals(listOf(1, 0, 0), GroupKey.clusters(listOf(250_000, 211_000, 210_000), true))
        // Unknown lengths join cluster 0 when the artist is known.
        assertEquals(listOf(0, 1, 0), GroupKey.clusters(listOf(0, 250_000, 210_000), true))
        assertEquals(listOf(0, 0), GroupKey.clusters(listOf(0, 0), true))
        assertEquals(emptyList<Int>(), GroupKey.clusters(emptyList(), true))
    }

    @Test
    fun `clusters compare with the first member so small steps do not chain`() {
        // Each is 2.5 s from the last, but the third is 5 s from the first.
        assertEquals(listOf(0, 0, 1, 1), GroupKey.clusters(listOf(200_000, 202_500, 205_000, 207_500), true))
    }

    @Test
    fun `clusters with no artist need one second, and each unknown stands alone`() {
        assertEquals(listOf(0, 0, 1), GroupKey.clusters(listOf(210_000, 211_000, 212_500), false))
        assertEquals(listOf(1, 0, 2), GroupKey.clusters(listOf(0, 210_000, 0), false))
    }

    @Test
    fun `a forwarded file is one source, the oldest message kept`() {
        val original = track(messageId = 10, uniqueId = "same", dateSec = 100)
        val forward = track(messageId = 20, uniqueId = "same", chatId = 2, dateSec = 500)
        val other = track(messageId = 30, uniqueId = "other")
        assertEquals(listOf(original, other), GroupKey.dedupeSources(listOf(forward, original, other)))
        assertEquals(listOf(original, other), GroupKey.dedupeSources(listOf(original, forward, other)))
        assertEquals(listOf(original), GroupKey.dedupeSources(listOf(original, original)))
        assertEquals(emptyList<Track>(), GroupKey.dedupeSources(emptyList()))
    }

    @Test
    fun `a blank unique id is never merged`() {
        val a = track(messageId = 1, uniqueId = "")
        val b = track(messageId = 2, uniqueId = "")
        assertEquals(listOf(a, b), GroupKey.dedupeSources(listOf(a, b)))
    }
}
