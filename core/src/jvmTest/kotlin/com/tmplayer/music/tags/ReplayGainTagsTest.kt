package com.tmplayer.music.tags

import com.tmplayer.music.library.ReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReplayGain read from each tag family, and how the factor to play with is worked out. */
class ReplayGainTagsTest {

    @Test
    fun id3v23TxxxFramesInLatin1AndUtf16() {
        val t = TagReader.read(GainSamples.mp3, "a.mp3")
        assertEquals("Gain Title", t.title)
        assertEquals(-6.5f, t.replayGain.trackGainDb)
        assertEquals(0.988f, t.replayGain.trackPeak)
        assertEquals(-4.25f, t.replayGain.albumGainDb)
        assertEquals(1.0f, t.replayGain.albumPeak)
    }

    @Test
    fun id3v24Utf8AndDecimalComma() {
        val t = TagReader.read(GainSamples.mp3v24, "a.mp3")
        assertEquals(3.1f, t.replayGain.trackGainDb)
        assertEquals(0.5f, t.replayGain.trackPeak)
        assertNull(t.replayGain.albumGainDb)
    }

    @Test
    fun flacVorbisCommentsAnyCase() {
        val t = TagReader.read(GainSamples.flac, "a.flac")
        assertEquals(-8.12f, t.replayGain.trackGainDb)
        assertEquals(0.912345f, t.replayGain.trackPeak)
        assertEquals(-7.0f, t.replayGain.albumGainDb)
        assertEquals(0.999f, t.replayGain.albumPeak)
    }

    @Test
    fun oggOpusComments() {
        val t = TagReader.read(GainSamples.opus, "a.opus")
        assertEquals("Opus Gain", t.title)
        assertEquals(-3.5f, t.replayGain.trackGainDb)
        assertNull(t.replayGain.trackPeak)
    }

    @Test
    fun mp4FreeformAtoms() {
        val t = TagReader.read(GainSamples.m4a, "a.m4a")
        assertEquals("M4A Gain", t.title)
        assertEquals(-7.1f, t.replayGain.trackGainDb)
        assertEquals(0.95f, t.replayGain.trackPeak)
        assertEquals(-6.0f, t.replayGain.albumGainDb)
    }

    @Test
    fun filesWithoutTagsHaveNoGain() {
        for (bytes in listOf(Samples.mp3v23, Samples.flac, Samples.opus, Samples.m4a, Samples.wav)) {
            assertEquals(ReplayGain.NONE, TagReader.read(bytes).replayGain)
        }
    }

    @Test
    fun theFirstValueOfEachKindStays() {
        val comments = out {
            u32le(1).ascii("v").u32le(2)
            for (c in listOf("REPLAYGAIN_TRACK_GAIN=-5 dB", "REPLAYGAIN_TRACK_GAIN=-9 dB")) {
                u32le(c.length.toLong()).ascii(c)
            }
        }
        val file = out { ascii("fLaC").u8(0x84).u24be(comments.size).bytes(comments) }
        assertEquals(-5f, TagReader.read(file).replayGain.trackGainDb)
    }

    @Test
    fun nonsenseValuesAreDropped() {
        for (value in listOf("loud", "", "NaN", "Infinity", "-1e99 dB", "999 dB", "--3 dB", "1 2 dB")) {
            assertNull("[$value]", ReplayGain.parseGain(value))
        }
        for (value in listOf("loud", "0", "-1", "NaN", "1e30", "17")) {
            assertNull("[$value]", ReplayGain.parsePeak(value))
        }
        assertEquals(-6.5f, ReplayGain.parseGain(" -6.50 DB "))
        assertEquals(2f, ReplayGain.parseGain("2"))
    }

    @Test
    fun textRoundTripAndGarbageDecode() {
        val gain = ReplayGain(-6.5f, -4f, 0.9f, null)
        assertEquals(gain, ReplayGain.decode(gain.encode()))
        assertEquals("", ReplayGain.NONE.encode())
        for (bad in listOf(null, "", "x", ";;;", "a;b;c;d", "1;2;3", "9999;9999;0;0", "x".repeat(500))) {
            assertEquals("[$bad]", ReplayGain.NONE, ReplayGain.decode(bad))
        }
    }

    @Test
    fun factorUsesTheTrackGainThenTheAlbumGain() {
        assertEquals(1f, ReplayGain.NONE.factor(), 0f)
        assertEquals(0.5012f, ReplayGain(-6f, null, 1f, null).factor(), 0.001f)
        assertEquals(0.5012f, ReplayGain(null, -6f, null, 1f).factor(), 0.001f)
        assertEquals(0.1259f, ReplayGain(-18f, -6f, 1f, 1f).factor(), 0.001f)
    }

    @Test
    fun factorIsClampedAndHeldBelowThePeak() {
        // +6 dB wanted, but the peak is 0.8: the most that keeps it under full scale is 1.25.
        assertEquals(1.25f, ReplayGain(6f, null, 0.8f, null).factor(), 0.0001f)
        // No peak: taken as full scale, so a track is never turned up.
        assertEquals(1f, ReplayGain(6f, null, null, null).factor(), 0f)
        // Far beyond the clamp either way.
        assertTrue(ReplayGain(-60f, null, 1f, null).factor() >= 0.06f)
        val loud = ReplayGain(40f, null, 0.01f, null).factor()
        assertTrue("$loud", loud <= 3.99f)
        // A quiet peak never makes a cut bigger or smaller than asked.
        assertEquals(0.5012f, ReplayGain(-6f, null, 0.2f, null).factor(), 0.001f)
    }
}
