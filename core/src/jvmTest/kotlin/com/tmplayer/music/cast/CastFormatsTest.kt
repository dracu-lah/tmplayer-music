package com.tmplayer.music.cast

import com.tmplayer.music.library.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class CastFormatsTest {
    private fun playable(format: AudioFormat, mime: String = "", name: String = "") =
        (CastFormats.check(format, mime, name) as? CastVerdict.Playable)?.mime

    @Test
    fun `the formats the receiver plays`() {
        assertEquals("audio/mpeg", playable(AudioFormat.MP3))
        assertEquals("audio/aac", playable(AudioFormat.AAC))
        assertEquals("audio/mp4", playable(AudioFormat.M4A))
        assertEquals("audio/flac", playable(AudioFormat.FLAC))
        assertEquals("audio/ogg", playable(AudioFormat.OGG))
        assertEquals("audio/ogg", playable(AudioFormat.OPUS))
        assertEquals("audio/wav", playable(AudioFormat.WAV))
    }

    @Test
    fun `alac and aiff are refused with their reason`() {
        assertEquals(CastVerdict.Refused(CastRefusal.Alac), CastFormats.check(AudioFormat.ALAC, "audio/mp4", "a.m4a"))
        assertEquals(CastVerdict.Refused(CastRefusal.Aiff), CastFormats.check(AudioFormat.AIFF, "audio/aiff", "a.aif"))
    }

    @Test
    fun `an undetected format falls back to its mime type then its name`() {
        assertEquals("audio/mpeg", playable(AudioFormat.OTHER, "audio/mp3"))
        assertEquals("audio/flac", playable(AudioFormat.OTHER, "audio/x-flac; charset=x"))
        assertEquals("audio/flac", playable(AudioFormat.OTHER, "application/octet-stream", "Song.FLAC"))
        assertEquals(CastVerdict.Refused(CastRefusal.Alac), CastFormats.check(AudioFormat.OTHER, "", "x.alac"))
    }

    @Test
    fun `an unknown file is refused, never guessed`() {
        assertEquals(CastVerdict.Refused(CastRefusal.Unknown), CastFormats.check(AudioFormat.OTHER, "", ""))
        assertEquals(CastVerdict.Refused(CastRefusal.Unknown), CastFormats.check(AudioFormat.OTHER, "video/x-matroska", "x.mka"))
        assertEquals(CastVerdict.Refused(CastRefusal.Unknown), CastFormats.check(AudioFormat.OTHER, "\u0000".repeat(10_000), "." + "x".repeat(10_000)))
    }

    @Test
    fun `a stored format name reads back, and unknown text is other`() {
        assertEquals(AudioFormat.FLAC, CastFormats.formatOf("FLAC"))
        assertEquals(AudioFormat.OTHER, CastFormats.formatOf("nonsense"))
        assertEquals(AudioFormat.OTHER, CastFormats.formatOf(null))
    }
}
