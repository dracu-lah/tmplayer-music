package com.tmplayer.music.desktop.player

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Track
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayableCheckTest {
    private fun track(name: String, format: AudioFormat = AudioFormat.OTHER) = Track(
        chatId = 1, messageId = 1, fileId = 1, uniqueId = "u", fileName = name, mime = "", sizeBytes = 10, dateSec = 0,
        title = "T", artist = "A", format = format,
    )

    @Test
    fun `wav is found by its format or its name, in any case`() {
        assertTrue(PlayableCheck.isWav(track("a.mp3", AudioFormat.WAV)))
        assertTrue(PlayableCheck.isWav(track("Song.WAV")))
        assertTrue(PlayableCheck.isWav(track("song.wave")))
        assertNotNull(PlayableCheck.unplayableReason(track("x.wav")))
        assertTrue(PlayableCheck.unplayableReason(track("x.wav"))!!.isNotBlank())
    }

    @Test
    fun `other files and odd names are tried as usual`() {
        for (name in listOf("a.mp3", "a.flac", "wav", "", ".wav.mp3", "a.", "noext", "wavelength.m4a")) {
            assertFalse(PlayableCheck.isWav(track(name)), name)
            assertNull(PlayableCheck.unplayableReason(track(name)), name)
        }
    }
}
