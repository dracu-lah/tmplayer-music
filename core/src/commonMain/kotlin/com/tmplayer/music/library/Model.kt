package com.tmplayer.music.library

/**
 * The library's shared shapes: what the tag reader finds in a file, what one audio file in a chat
 * is once indexed, and the song a set of versions folds into (`plan/RESEARCH.md` 4.2, 6.2, 6.3).
 * Every field read from outside may be missing: text is blank and numbers are 0 when unknown,
 * and a 0 duration means unknown, never "hide this track".
 */

/** The container a file is in, as far as the mime type, the name or the bytes tell. */
enum class AudioFormat(val label: String, val lossless: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    MP3("MP3", false),
    AAC("AAC", false),
    M4A("M4A", false),
    OGG("OGG", false),
    OPUS("Opus", false),
    OTHER("", false),
}

/** What a file in a chat is to the library. */
enum class FileKind {
    /** A playable audio file: listed in Songs. */
    Audio,

    /** A `.lrc` or `.txt` beside the audio: kept by name for lyrics, never listed. */
    Lyrics,

    /** Anything else (a zip, a video, a voice note): skipped. */
    Ignore,
}

/** An embedded cover picture, already capped in size by the reader. Equal by content. */
class Picture(val mime: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Picture && other.mime == mime && other.data.contentEquals(data)

    override fun hashCode(): Int = mime.hashCode() * 31 + data.contentHashCode()

    override fun toString(): String = "Picture($mime, ${data.size} bytes)"
}

/**
 * The tags read from a file's own bytes. All optional: a reader that finds nothing returns an
 * empty [Tags], never throws.
 */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val durationMs: Long = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val channels: Int = 0,
    val format: AudioFormat = AudioFormat.OTHER,
    /** Unsynced or LRC text from the tag (USLT, LYRICS, ©lyr), blank when none. */
    val lyrics: String = "",
    val cover: Picture? = null,
) {
    companion object {
        val EMPTY = Tags()
    }
}

/** Where a track's display fields came from, so a later tag read can improve on them. */
enum class TagState {
    /** Only Telegram's own fields (or the file name) so far. */
    TelegramOnly,

    /** The file's tags were read. */
    Read,

    /** Reading the tags failed for good (unreadable, unknown format); Telegram's fields stay. */
    Failed,
}

/**
 * One audio file in one chosen chat: one row of the index. [uniqueId] is Telegram's remote unique
 * id, the same for a forward of the same file, so a file posted in three chats is one source seen
 * three times, never three versions.
 */
data class Track(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val uniqueId: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    /** When the message was posted, Unix seconds. */
    val dateSec: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtist: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val durationMs: Long = 0,
    val format: AudioFormat = AudioFormat.OTHER,
    val bitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val tagState: TagState = TagState.TelegramOnly,
    /** Telegram's album cover thumbnail file id for an audio message, 0 when none. */
    val thumbFileId: Int = 0,
    /** A key for a cover saved from the tags, blank when none. */
    val coverKey: String = "",
    /** The song this file is a version of, from [GroupKey]; computed at index time and stored. */
    val groupKey: String = "",
    /** "live", "remix" and the like when the title says it is a different recording, blank otherwise. */
    val variant: String = "",
) {
    /** The row's identity in the index. */
    val id: TrackId get() = TrackId(chatId, messageId)
}

data class TrackId(val chatId: Long, val messageId: Long)

/**
 * One row of Songs: every version of one recording folded together, shown with the fields of the
 * version that plays by default.
 */
data class Song(
    val groupKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val defaultTrack: TrackId,
    val versionCount: Int,
    /** The best format among the versions, for the badge. */
    val bestFormat: AudioFormat,
    val thumbFileId: Int,
    val coverKey: String,
    val variant: String,
    /** The newest version's post date, for Recently added and the date sort. */
    val addedSec: Long,
    /** The album the default version is on (or the first version that has one), for "Go to album"; blank when none. */
    val albumKey: String = "",
    /** The artist the default version is listed under, for "Go to artist"; blank when the file names nobody. */
    val artistKey: String = "",
)

/** An album: album artist (or artist) plus album plus year, so two albums with one name stay apart. */
data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val songCount: Int,
    val thumbFileId: Int,
    val coverKey: String,
)

data class Artist(
    val key: String,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
)

/** How Songs is ordered. */
enum class SongSort { Title, Artist, Album, Added }
