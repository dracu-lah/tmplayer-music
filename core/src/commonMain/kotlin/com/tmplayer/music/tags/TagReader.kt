package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Tags

/**
 * Reads the tags and audio properties of a file from its first bytes (and, when given, its last
 * ones), so the library can show real titles and durations without downloading whole files.
 * Knows ID3v2 and ID3v1 with MPEG audio, ADTS AAC, FLAC, Ogg Vorbis, Opus, MP4 and M4A (AAC and
 * ALAC), WAV and AIFF. Every byte is outside data: nothing here throws, and a file it cannot make
 * sense of gives what was read before the trouble, or [Tags.EMPTY].
 */
object TagReader {

    /** The default first fetch: enough for the tags and a usual cover in most files. */
    const val HEAD_BYTES = 256 * 1024

    /** A cover larger than this is dropped (cover = null), never copied. */
    const val MAX_COVER_BYTES = 4 * 1024 * 1024

    /** No [bytesNeeded] answer is larger than this. */
    const val MAX_NEEDED_BYTES = 16L * 1024 * 1024

    /**
     * Told about a failure [read] swallowed. The garbage tests set it, since a parser that throws
     * on bad input loses the fields it would have read after the bad spot.
     */
    internal var onInternalFailure: ((Throwable) -> Unit)? = null

    /**
     * The tags in [head], the file's first bytes. [fileName] gives the format when the bytes do
     * not, [sizeBytes] (the whole file's size, 0 when unknown) gives a constant bitrate file's
     * duration and any file's average bitrate, and [tail], the file's last bytes, gives an ID3v1
     * tag, an Ogg stream's duration and an MP4's moov when it sits after the audio. Never throws.
     */
    fun read(head: ByteArray, fileName: String = "", sizeBytes: Long = 0, tail: ByteArray? = null): Tags {
        val out = TagBuilder()
        try {
            readInto(out, head, fileName, sizeBytes, tail)
        } catch (t: Throwable) {
            // Outside data: keep what was read before the failure.
            onInternalFailure?.invoke(t)
        }
        return try {
            if (out.format == AudioFormat.OTHER) out.format = formatFromName(fileName)
            out.build()
        } catch (t: Throwable) {
            Tags.EMPTY
        }
    }

    /**
     * How many bytes from the file's start are needed to read its whole tag block, given at least
     * its first 10 bytes in [head]: an ID3v2 tag's full size (header, body and footer), or the end
     * of a FLAC file's metadata blocks as far as [head] lets the walk go. When the answer is past
     * the end of [head], fetch that much and ask again, since a longer head can show more blocks.
     * Null means [head] is all the reader needs (or the format has no such block). Answers are
     * capped at [MAX_NEEDED_BYTES]. Never throws.
     */
    fun bytesNeeded(head: ByteArray): Long? =
        try {
            needed(head)?.coerceAtMost(MAX_NEEDED_BYTES)
        } catch (t: Throwable) {
            null
        }

    private fun needed(head: ByteArray): Long? {
        if (head.size < 10) return null
        val id3 = Id3.tagSize(head, 0)
        if (id3 > 0) {
            if (id3 <= head.size - 4 && head.at(id3.toInt(), "fLaC")) return Flac.bytesNeeded(head, id3.toInt())
            return id3
        }
        if (head.at(0, "fLaC")) return Flac.bytesNeeded(head, 0)
        return null
    }

    private fun readInto(out: TagBuilder, head: ByteArray, fileName: String, sizeBytes: Long, tail: ByteArray?) {
        var start = 0
        val id3 = Id3.tagSize(head, 0)
        if (id3 > 0) {
            Id3.parseV2(head, 0, out)
            start = minOf(id3, head.size.toLong()).toInt()
        }
        val ext = extension(fileName)
        when {
            head.at(start, "fLaC") -> Flac.parse(head, start, sizeBytes, out)
            start == 0 && head.at(0, "OggS") -> Ogg.parse(head, sizeBytes, tail, out)
            start == 0 && head.at(4, "ftyp") -> Mp4.parse(head, sizeBytes, tail, out)
            start == 0 && (head.at(0, "RIFF") || head.at(0, "RF64")) && head.at(8, "WAVE") -> Riff.wav(head, sizeBytes, out)
            start == 0 && head.at(0, "FORM") && (head.at(8, "AIFF") || head.at(8, "AIFC")) -> Riff.aiff(head, out)
            Mpeg.isAdts(head, start) -> Mpeg.parseAdts(head, start, sizeBytes, out)
            id3 > 0 || ext == "mp3" || ext == "mp2" || Mpeg.frameAt(head, 0) != null -> {
                val frame = Mpeg.findFrame(head, start)
                if (frame >= 0) {
                    Mpeg.parse(head, frame, sizeBytes, if (Id3.hasV1(tail)) 128L else 0L, out)
                } else if (id3 > 0 && formatFromName(fileName) == AudioFormat.OTHER) {
                    out.format = AudioFormat.MP3
                }
            }
        }
        if (tail != null) Id3.parseV1(tail, out)
    }

    private fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** The format a file name's extension suggests, for bytes that say nothing. */
    fun formatFromName(fileName: String): AudioFormat =
        when (extension(fileName)) {
            "mp3", "mp2" -> AudioFormat.MP3
            "flac" -> AudioFormat.FLAC
            "ogg", "oga" -> AudioFormat.OGG
            "opus" -> AudioFormat.OPUS
            "m4a", "mp4", "m4b" -> AudioFormat.M4A
            "aac" -> AudioFormat.AAC
            "alac" -> AudioFormat.ALAC
            "wav", "wave" -> AudioFormat.WAV
            "aif", "aiff", "aifc" -> AudioFormat.AIFF
            else -> AudioFormat.OTHER
        }
}
