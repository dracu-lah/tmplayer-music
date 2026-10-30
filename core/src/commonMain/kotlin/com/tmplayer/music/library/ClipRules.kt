package com.tmplayer.music.library

/**
 * Which files the library hides by default (`plan/MVP-REVISION.md` decision j): clips shorter than
 * [SHORT_MS], and voice style files (a voice recording sent as a document). Both have a switch in
 * Settings. The rules are conservative on purpose:
 *
 * - A file whose duration is unknown (0) is never hidden. A document has no duration until its tags
 *   are read, so it is judged only then.
 * - A voice style file is an `.m4a`, `.aac`, `.ogg` or `.opus` file (the formats recorders and
 *   messengers save speech in) of known duration, either named like a recorder names its files
 *   ("Voice 001", "New Recording 12", "Recording_20231012_101112", "REC0001", a messenger's
 *   "PTT-20231012-WA0001" or a desktop client's "audio_2023-10-12_10-11-12"), or mono at a very low
 *   bitrate ([VOICE_KBPS] or less) and shorter than [VOICE_MAX_MS]. A song is rarely either.
 *
 * Each song stores its bits ([SHORT], [VOICE]) at index time from its default version, and every
 * list asks for the songs whose bits do not meet the mask the switches make, at query time.
 */
object ClipRules {
    const val SHORT = 1
    const val VOICE = 2

    /** What is hidden when the user has changed nothing: short clips and voice style files. */
    const val DEFAULT_MASK = SHORT or VOICE

    const val SHORT_MS = 30_000L
    const val VOICE_KBPS = 32
    const val VOICE_MAX_MS = 20 * 60_000L

    private val voiceFormats = setOf(AudioFormat.M4A, AudioFormat.AAC, AudioFormat.OGG, AudioFormat.OPUS)

    private val recorderNames = listOf(
        Regex("""^(voice|new recording|recording|rec)[ _-]*\d""", RegexOption.IGNORE_CASE),
        Regex("""^voice[ _-]*(note|memo|message|recording)""", RegexOption.IGNORE_CASE),
        Regex("""^ptt-\d{8}-wa\d+""", RegexOption.IGNORE_CASE),
        Regex("""^audio_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""", RegexOption.IGNORE_CASE),
    )

    /** The mask the two switches make: a bit is set for what is hidden. */
    fun mask(showShort: Boolean, showVoice: Boolean): Int = (if (showShort) 0 else SHORT) or (if (showVoice) 0 else VOICE)

    fun isShort(durationMs: Long): Boolean = durationMs in 1 until SHORT_MS

    fun isVoiceStyle(fileName: String, format: AudioFormat, durationMs: Long, bitrateKbps: Int, channels: Int): Boolean {
        if (durationMs <= 0 || format !in voiceFormats) return false
        val base = AudioKinds.baseName(fileName.take(MAX_NAME)).trim()
        if (recorderNames.any { it.containsMatchIn(base) }) return true
        return channels == 1 && bitrateKbps in 1..VOICE_KBPS && durationMs < VOICE_MAX_MS
    }

    /** The bits of one file. */
    fun bits(track: Track): Int =
        (if (isShort(track.durationMs)) SHORT else 0) or
            (if (isVoiceStyle(track.fileName, track.format, track.durationMs, track.bitrateKbps, track.channels)) VOICE else 0)

    /** True when [track] is hidden under [mask]. */
    fun hidden(track: Track, mask: Int): Boolean = mask != 0 && bits(track) and mask != 0

    private const val MAX_NAME = 512
}
