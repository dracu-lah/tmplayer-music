package com.tmplayer.music.library

import java.util.Locale

/**
 * Decides what a file in a chat is from its mime type and its name alone.
 * Lossless audio is mostly posted as a document with a generic mime type, so the extension counts as
 * much as the mime. Both come from Telegram and the sender's client, so either may be null, blank,
 * wrong or absurd; nothing here throws on them.
 *
 * Voice notes are not decided here: they arrive as their own message type, and the scanner leaves them
 * out (or in, by setting) before it asks.
 */
object AudioKinds {

    /** Audio extensions the scanner keeps, whether or not this build can decode them yet. */
    private val audioExtensions = setOf(
        "flac", "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav",
        "aif", "aiff", "aifc", "alac", "wv", "ape", "mka", "wma",
    )

    /** Text kept by name for lyrics: only these two, so a stray `.nfo` or `.cue` is never read as lyrics. */
    private val lyricsExtensions = setOf("lrc", "txt")

    /**
     * Mime types that start with `audio/` but are playlists, not audio. Playlists are not followed in v1,
     * and a playlist of links must never be listed as a song.
     */
    private val playlistMimes = setOf(
        "audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/scpls", "audio/x-cue",
        "audio/x-ms-asx", "audio/x-ms-wax",
    )

    /** Extensions that are never audio even when the sender labelled them `audio/`. */
    private val notAudioExtensions = setOf("m3u", "m3u8", "pls", "cue", "xspf", "asx", "wax", "zip", "rar", "7z")

    /** The longest extension taken as one, so `Mr. Nobody` or `Vol. Seven` keep their whole name. */
    private const val MAX_EXTENSION = 5

    /** Says whether a file is audio to list, lyrics to keep by name, or something to skip. */
    fun classify(mime: String?, fileName: String?): FileKind {
        val ext = extension(fileName)
        val type = mimeType(mime)
        if (ext in lyricsExtensions) return FileKind.Lyrics
        if (ext in notAudioExtensions || type in playlistMimes) return FileKind.Ignore
        if (type.startsWith("audio/")) return FileKind.Audio
        // A video is skipped even with an audio-looking name; an application/* or unknown type is kept
        // when the name says audio, since lossless files are usually sent as application/octet-stream.
        if (type.startsWith("video/")) return FileKind.Ignore
        return if (ext in audioExtensions) FileKind.Audio else FileKind.Ignore
    }

    /**
     * The container a file is in. The extension wins over the mime type when it is a known audio one,
     * because a sender's client often labels every file with one generic type; the mime type only refines
     * it where it knows more (an `.ogg` labelled `audio/opus` is Opus).
     */
    fun format(mime: String?, fileName: String?): AudioFormat {
        val ext = extension(fileName)
        val byMime = formatOfMime(mimeType(mime))
        if (ext in audioExtensions) {
            val byExt = formatOfExtension(ext)
            if (byExt == AudioFormat.OGG && byMime == AudioFormat.OPUS) return AudioFormat.OPUS
            return byExt
        }
        return byMime
    }

    /** Whether this build can play the format, so the UI can say it cannot instead of failing to start. */
    fun playable(format: AudioFormat): Boolean = format != AudioFormat.OTHER

    /**
     * The name without its extension, for pairing `song.lrc` with `song.flac` in the same chat. Only a
     * short run of letters and digits after the last dot counts as an extension; a name without one comes
     * back trimmed and whole. The caller compares the results case-insensitively.
     */
    fun baseName(fileName: String): String {
        val name = fileName.trim()
        val dot = extensionDot(name)
        return if (dot < 0) name else name.substring(0, dot).trim()
    }

    /** The lower-case extension, or blank when the name has none that looks like one. */
    internal fun extension(fileName: String?): String {
        if (fileName.isNullOrBlank()) return ""
        val name = fileName.trim()
        val dot = extensionDot(name)
        return if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** Where the extension's dot is, or -1 when the part after the last dot is not a plausible extension. */
    private fun extensionDot(name: String): Int {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return -1
        val length = name.length - dot - 1
        if (length !in 1..MAX_EXTENSION) return -1
        for (i in dot + 1 until name.length) {
            val c = name[i]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')) return -1
        }
        return dot
    }

    /** The bare mime type: trimmed, lower case and without parameters such as `; codecs=opus`. */
    private fun mimeType(mime: String?): String {
        if (mime.isNullOrBlank()) return ""
        return mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    }

    private fun formatOfExtension(ext: String): AudioFormat = when (ext) {
        "flac" -> AudioFormat.FLAC
        "mp3" -> AudioFormat.MP3
        "m4a", "m4b" -> AudioFormat.M4A
        "aac" -> AudioFormat.AAC
        "ogg", "oga" -> AudioFormat.OGG
        "opus" -> AudioFormat.OPUS
        "wav" -> AudioFormat.WAV
        "aif", "aiff", "aifc" -> AudioFormat.AIFF
        "alac" -> AudioFormat.ALAC
        else -> AudioFormat.OTHER
    }

    private fun formatOfMime(type: String): AudioFormat = when (type) {
        "audio/flac", "audio/x-flac" -> AudioFormat.FLAC
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg", "audio/mpa" -> AudioFormat.MP3
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/x-m4b", "audio/mp4a-latm" -> AudioFormat.M4A
        "audio/aac", "audio/x-aac", "audio/aacp", "audio/x-hx-aac-adts" -> AudioFormat.AAC
        "audio/ogg", "audio/vorbis", "audio/x-vorbis", "audio/x-vorbis+ogg", "audio/x-ogg" -> AudioFormat.OGG
        "audio/opus", "audio/x-opus", "audio/x-opus+ogg" -> AudioFormat.OPUS
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/x-pn-wav" -> AudioFormat.WAV
        "audio/aiff", "audio/x-aiff", "audio/aifc", "audio/x-aifc" -> AudioFormat.AIFF
        "audio/alac", "audio/x-alac" -> AudioFormat.ALAC
        else -> AudioFormat.OTHER
    }
}
