package com.tmplayer.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Short clips and voice style files: hidden by default, never when the length is unknown. */
class ClipRulesTest {

    private fun file(
        name: String,
        format: AudioFormat = AudioFormat.M4A,
        durationMs: Long = 120_000,
        kbps: Int = 0,
        channels: Int = 0,
    ) = Track(
        chatId = 1, messageId = 1, fileId = 1, uniqueId = "u", fileName = name, mime = "", sizeBytes = 1, dateSec = 1,
        title = name, artist = "", durationMs = durationMs, format = format, bitrateKbps = kbps, channels = channels,
    )

    @Test
    fun anUnknownLengthIsNeverHidden() {
        assertFalse(ClipRules.isShort(0))
        assertFalse(ClipRules.hidden(file("Voice 001.m4a", durationMs = 0), ClipRules.DEFAULT_MASK))
        assertFalse(ClipRules.hidden(file("PTT-20231012-WA0001.opus", AudioFormat.OPUS, durationMs = 0, kbps = 16, channels = 1), ClipRules.DEFAULT_MASK))
    }

    @Test
    fun shortClipsAreUnderThirtySeconds() {
        assertTrue(ClipRules.isShort(29_999))
        assertFalse(ClipRules.isShort(30_000))
        assertTrue(ClipRules.hidden(file("Jingle.mp3", AudioFormat.MP3, durationMs = 12_000), ClipRules.SHORT))
        assertFalse(ClipRules.hidden(file("Jingle.mp3", AudioFormat.MP3, durationMs = 12_000), ClipRules.VOICE))
        assertFalse(ClipRules.hidden(file("Jingle.mp3", AudioFormat.MP3, durationMs = 12_000), 0))
    }

    @Test
    fun recorderNamesAreVoiceFiles() {
        for (name in listOf(
            "Voice 001.m4a", "New Recording 12.m4a", "Recording_20231012_101112.m4a", "REC0001.m4a",
            "voice_note_3.ogg", "PTT-20231012-WA0001.opus", "audio_2023-10-12_10-11-12.ogg",
        )) {
            val format = AudioKinds.format("", name)
            assertTrue(ClipRules.isVoiceStyle(name, format, 90_000, 0, 0), name)
        }
    }

    @Test
    fun songsAreNotVoiceFiles() {
        assertFalse(ClipRules.isVoiceStyle("Velo Marin.m4a", AudioFormat.M4A, 200_000, 256, 2))
        assertFalse(ClipRules.isVoiceStyle("Recovery.m4a", AudioFormat.M4A, 200_000, 256, 2))
        assertFalse(ClipRules.isVoiceStyle("Voice of the Tide.ogg", AudioFormat.OGG, 200_000, 160, 2))
        // A recorder name in a format recorders do not use is left alone.
        assertFalse(ClipRules.isVoiceStyle("Voice 001.flac", AudioFormat.FLAC, 200_000, 900, 2))
        // A messenger's audio file (not its voice note) can be music.
        assertFalse(ClipRules.isVoiceStyle("AUD-20231012-WA0001.m4a", AudioFormat.M4A, 200_000, 128, 2))
    }

    @Test
    fun lowBitrateMonoIsVoiceButStereoIsNot() {
        assertTrue(ClipRules.isVoiceStyle("lecture part.ogg", AudioFormat.OPUS, 600_000, 24, 1))
        assertFalse(ClipRules.isVoiceStyle("lecture part.ogg", AudioFormat.OPUS, 600_000, 24, 2))
        assertFalse("channels unknown", ClipRules.isVoiceStyle("lecture part.ogg", AudioFormat.OPUS, 600_000, 24, 0))
        assertFalse("too long for a voice note", ClipRules.isVoiceStyle("lecture part.ogg", AudioFormat.OPUS, 60 * 60_000L, 24, 1))
    }

    @Test
    fun switchesMakeTheMask() {
        assertEquals(ClipRules.DEFAULT_MASK, ClipRules.mask(showShort = false, showVoice = false))
        assertEquals(ClipRules.VOICE, ClipRules.mask(showShort = true, showVoice = false))
        assertEquals(ClipRules.SHORT, ClipRules.mask(showShort = false, showVoice = true))
        assertEquals(0, ClipRules.mask(showShort = true, showVoice = true))
    }

    @Test
    fun garbageNamesDoNotThrow() {
        assertFalse(ClipRules.isVoiceStyle("", AudioFormat.M4A, 1, 0, 0))
        assertFalse(ClipRules.isVoiceStyle("x".repeat(100_000), AudioFormat.M4A, 1, 0, 0))
        ClipRules.isVoiceStyle("\u0000￿.m4a", AudioFormat.OGG, 1, -5, -1)
    }

    private fun assertFalse(message: String, value: Boolean) = assertFalse(value, message)
}
