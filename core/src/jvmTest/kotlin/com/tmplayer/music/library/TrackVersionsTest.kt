package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackVersionsTest {

    private val mp3 = track(messageId = 1, format = AudioFormat.MP3, bitrateKbps = 320, sizeBytes = 8_000_000)
    private val flac = track(messageId = 2, format = AudioFormat.FLAC, bitrateKbps = 900, sizeBytes = 25_000_000)

    private fun check(expected: Track, reason: PickReason, versions: List<Track>, onDevice: Set<Int> = emptySet(),
                      preferred: String? = null, saveData: Boolean = false) {
        assertEquals(expected, TrackVersions.pick(versions, onDevice, preferred, saveData))
        assertEquals(expected, TrackVersions.pick(versions.reversed(), onDevice, preferred, saveData))
        assertEquals(reason, TrackVersions.reason(versions, onDevice, preferred, saveData))
    }

    @Test
    fun `nothing to pick from`() {
        assertNull(TrackVersions.pick(emptyList()))
        assertNull(TrackVersions.reason(emptyList()))
        assertEquals(AudioFormat.OTHER, TrackVersions.bestFormat(emptyList()))
    }

    @Test
    fun `a single version plays with no reason to give`() {
        assertEquals(mp3, TrackVersions.pick(listOf(mp3)))
        assertNull(TrackVersions.reason(listOf(mp3)))
    }

    @Test
    fun `the user's choice wins`() {
        check(mp3, PickReason.Preferred, listOf(mp3, flac), onDevice = setOf(flac.fileId), preferred = mp3.uniqueId)
    }

    @Test
    fun `a preferred id that is gone is ignored`() {
        check(flac, PickReason.Lossless, listOf(mp3, flac), preferred = "deleted")
        check(flac, PickReason.Lossless, listOf(mp3, flac), preferred = "")
    }

    @Test
    fun `a playable version beats one this build cannot play`() {
        val ape = track(messageId = 3, format = AudioFormat.OTHER, bitrateKbps = 1000)
        check(mp3, PickReason.OnlyPlayable, listOf(ape, mp3), onDevice = setOf(ape.fileId))
    }

    @Test
    fun `on the device beats lossless`() {
        check(mp3, PickReason.OnDevice, listOf(mp3, flac), onDevice = setOf(mp3.fileId))
    }

    @Test
    fun `lossless beats lossy`() {
        check(flac, PickReason.Lossless, listOf(mp3, flac))
    }

    @Test
    fun `save data picks the smaller file`() {
        check(mp3, PickReason.SmallerForData, listOf(mp3, flac), saveData = true)
        val unknownSize = track(messageId = 4, sizeBytes = 0)
        check(mp3, PickReason.SmallerForData, listOf(unknownSize, mp3), saveData = true)
    }

    @Test
    fun `save data still takes the copy already on the device`() {
        check(flac, PickReason.OnDevice, listOf(mp3, flac), onDevice = setOf(flac.fileId), saveData = true)
    }

    @Test
    fun `higher bit rate wins between lossy copies`() {
        val low = track(messageId = 5, bitrateKbps = 128)
        val high = track(messageId = 6, bitrateKbps = 320)
        check(high, PickReason.HigherQuality, listOf(low, high))
    }

    @Test
    fun `bit rate is estimated from size and length when not reported`() {
        // 7.2 MB over 180 s is 320 kbps; 2.88 MB over 180 s is 128 kbps.
        val big = track(messageId = 7, sizeBytes = 7_200_000, durationMs = 180_000)
        val small = track(messageId = 8, sizeBytes = 2_880_000, durationMs = 180_000)
        assertEquals(320L, TrackVersions.bitrateKbps(big))
        assertEquals(128L, TrackVersions.bitrateKbps(small))
        assertEquals(0L, TrackVersions.bitrateKbps(track(messageId = 9, sizeBytes = 0)))
        assertEquals(0L, TrackVersions.bitrateKbps(track(messageId = 9, durationMs = 0)))
        check(big, PickReason.HigherQuality, listOf(small, big))
    }

    @Test
    fun `fuller tags win when quality is equal`() {
        val bare = track(messageId = 10, bitrateKbps = 320)
        val tagged = track(messageId = 11, bitrateKbps = 320, album = "Salt Year", year = 2019, thumbFileId = 4)
        assertEquals(0, TrackVersions.tagCount(bare))
        assertEquals(3, TrackVersions.tagCount(tagged))
        check(tagged, PickReason.FullerTags, listOf(bare, tagged))
    }

    @Test
    fun `the older message wins when nothing else differs`() {
        val older = track(messageId = 12, dateSec = 100, bitrateKbps = 320)
        val newer = track(messageId = 13, dateSec = 900, bitrateKbps = 320)
        check(older, PickReason.Oldest, listOf(newer, older))
    }

    @Test
    fun `best format is lossless first, then bit rate, playable before not`() {
        assertEquals(AudioFormat.FLAC, TrackVersions.bestFormat(listOf(mp3, flac)))
        val opus = track(messageId = 14, format = AudioFormat.OPUS, bitrateKbps = 160)
        val aac = track(messageId = 15, format = AudioFormat.AAC, bitrateKbps = 256)
        assertEquals(AudioFormat.AAC, TrackVersions.bestFormat(listOf(opus, aac)))
        val ape = track(messageId = 16, format = AudioFormat.OTHER, bitrateKbps = 1000)
        assertEquals(AudioFormat.MP3, TrackVersions.bestFormat(listOf(ape, mp3)))
        assertEquals(AudioFormat.OTHER, TrackVersions.bestFormat(listOf(ape)))
    }
}
