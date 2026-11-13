package com.tmplayer.music.desktop.player

import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Track

/**
 * Files the desktop engine is known not to open, found before they are handed to it, so the user
 * reads "WAV files are not supported here" and the queue goes on, instead of an engine error code.
 *
 * mediamp's libmpv does not recognise WAV when its bytes come through a stream (found 2026-11-14).
 * A temporary header-fixed copy was not tried: it would need the whole file on disk before the
 * first sound, which is the opposite of streaming.
 */
object PlayableCheck {
    private val WAV_NAMES = setOf("wav", "wave")

    /** The words for why [track] cannot play on the desktop, or null when it may be tried. */
    fun unplayableReason(track: Track): String? = if (isWav(track)) L.desktopWavUnsupported else null

    fun isWav(track: Track): Boolean =
        track.format == AudioFormat.WAV ||
            track.fileName.substringAfterLast('.', "").trim().lowercase() in WAV_NAMES
}
