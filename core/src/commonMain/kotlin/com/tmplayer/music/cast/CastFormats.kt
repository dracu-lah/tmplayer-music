package com.tmplayer.music.cast

import com.tmplayer.music.library.AudioFormat

/** Why a track cannot be sent to a Cast device; the app turns each into a sentence. */
enum class CastRefusal {
    /** Apple Lossless: the Default Media Receiver has no decoder for it. */
    Alac,

    /** AIFF: not on the receiver's list. */
    Aiff,

    /** A format or container that is not known to be playable there. */
    Unknown,
}

/** What the Default Media Receiver does with one file. */
sealed interface CastVerdict {
    /** Plays; [mime] is the Content-Type to load it with. */
    data class Playable(val mime: String) : CastVerdict

    data class Refused(val reason: CastRefusal) : CastVerdict
}

/**
 * Which tracks Google's Default Media Receiver plays: MP3, AAC (raw and in an MP4 or M4A), FLAC,
 * Opus, Ogg Vorbis and WAV. Anything else is refused up front, with a reason, instead of being
 * sent and failing on the device. The answer comes from the format the index detected, and for a
 * file whose format was not detected from its mime type and then its name.
 */
object CastFormats {

    fun check(format: AudioFormat, mime: String, fileName: String): CastVerdict = when (format) {
        AudioFormat.MP3 -> CastVerdict.Playable("audio/mpeg")
        AudioFormat.AAC -> CastVerdict.Playable("audio/aac")
        // M4A is AAC in an MP4 box nearly always; an ALAC file is told apart by the tag reader (AudioFormat.ALAC).
        AudioFormat.M4A -> CastVerdict.Playable("audio/mp4")
        AudioFormat.FLAC -> CastVerdict.Playable("audio/flac")
        AudioFormat.OGG, AudioFormat.OPUS -> CastVerdict.Playable("audio/ogg")
        AudioFormat.WAV -> CastVerdict.Playable("audio/wav")
        AudioFormat.ALAC -> CastVerdict.Refused(CastRefusal.Alac)
        AudioFormat.AIFF -> CastVerdict.Refused(CastRefusal.Aiff)
        AudioFormat.OTHER -> byMime(mime) ?: byName(fileName) ?: CastVerdict.Refused(CastRefusal.Unknown)
    }

    /** The format's name from its stored name, so a queue item can carry it as text; unknown names are OTHER. */
    fun formatOf(name: String?): AudioFormat = AudioFormat.entries.firstOrNull { it.name == name } ?: AudioFormat.OTHER

    private fun byMime(mime: String): CastVerdict? {
        val base = mime.substringBefore(';').trim().lowercase()
        val ok = when (base) {
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg-3" -> "audio/mpeg"
            "audio/aac", "audio/aacp", "audio/x-aac" -> "audio/aac"
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mp4a-latm" -> "audio/mp4"
            "audio/flac", "audio/x-flac" -> "audio/flac"
            "audio/ogg", "audio/opus", "audio/vorbis", "application/ogg" -> "audio/ogg"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "audio/wav"
            else -> return null
        }
        return CastVerdict.Playable(ok)
    }

    private fun byName(fileName: String): CastVerdict? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp3" -> CastVerdict.Playable("audio/mpeg")
            "aac" -> CastVerdict.Playable("audio/aac")
            "m4a", "mp4" -> CastVerdict.Playable("audio/mp4")
            "flac" -> CastVerdict.Playable("audio/flac")
            "ogg", "oga", "opus" -> CastVerdict.Playable("audio/ogg")
            "wav" -> CastVerdict.Playable("audio/wav")
            "alac" -> CastVerdict.Refused(CastRefusal.Alac)
            "aif", "aiff", "aifc" -> CastVerdict.Refused(CastRefusal.Aiff)
            else -> null
        }
    }
}
