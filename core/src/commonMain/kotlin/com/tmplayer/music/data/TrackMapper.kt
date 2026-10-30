package com.tmplayer.music.data

import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.FileKind
import com.tmplayer.music.library.GroupKey
import com.tmplayer.music.library.LyricsFile
import com.tmplayer.music.library.TitleFallback
import com.tmplayer.music.library.Track
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageDocument

/** What one message is to the library. */
sealed interface Mapped {
    data class Audio(val track: Track) : Mapped
    data class Lyrics(val file: LyricsFile) : Mapped
}

/**
 * A Telegram message as the library sees it: an audio message, an audio file sent as a document
 * (lossless usually is), a lyrics file, or nothing.
 *
 * Messages with a restriction reason are skipped, as TMPlayer's MediaMapper does: Telegram has
 * said this client may not show them. Voice notes and round videos are other content types and
 * never reach here. Every field is Telegram's and may be blank; the display title is never blank.
 */
object TrackMapper {

    fun fromMessage(message: Message): Mapped? {
        if (!message.restrictionInfo?.restrictionReason.isNullOrEmpty()) return null
        return when (val content = message.content) {
            is MessageAudio -> {
                val audio = content.audio
                val (artist, title) = TitleFallback.display(
                    tagTitle = "",
                    tagArtist = "",
                    tgTitle = audio.title.orEmpty(),
                    tgPerformer = audio.performer.orEmpty(),
                    fileName = audio.fileName.orEmpty(),
                )
                Mapped.Audio(
                    track(
                        message = message,
                        file = audio.audio,
                        fileName = audio.fileName.orEmpty(),
                        mime = audio.mimeType.orEmpty(),
                        title = title,
                        artist = artist,
                        durationMs = audio.duration.coerceAtLeast(0) * 1000L,
                        thumbFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                    ),
                )
            }
            is MessageDocument -> {
                val document = content.document
                val name = document.fileName.orEmpty()
                val mime = document.mimeType.orEmpty()
                when (AudioKinds.classify(mime, name)) {
                    FileKind.Audio -> {
                        val (artist, title) = TitleFallback.display("", "", "", "", name)
                        Mapped.Audio(
                            track(
                                message = message,
                                file = document.document,
                                fileName = name,
                                mime = mime,
                                title = title,
                                artist = artist,
                                durationMs = 0,
                                thumbFileId = document.thumbnail?.file?.id ?: 0,
                            ),
                        )
                    }
                    FileKind.Lyrics -> Mapped.Lyrics(
                        LyricsFile(
                            chatId = message.chatId,
                            messageId = message.id,
                            fileId = document.document.id,
                            baseName = AudioKinds.baseName(name),
                            sizeBytes = size(document.document),
                        ),
                    )
                    FileKind.Ignore -> null
                }
            }
            else -> null
        }
    }

    private fun track(
        message: Message,
        file: File,
        fileName: String,
        mime: String,
        title: String,
        artist: String,
        durationMs: Long,
        thumbFileId: Int,
    ): Track = Track(
        chatId = message.chatId,
        messageId = message.id,
        fileId = file.id,
        uniqueId = file.remote?.uniqueId.orEmpty().ifEmpty { "f${file.id}" },
        fileName = fileName,
        mime = mime,
        sizeBytes = size(file),
        dateSec = message.date.toLong(),
        title = title,
        artist = artist,
        durationMs = durationMs,
        format = AudioKinds.format(mime, fileName),
        thumbFileId = thumbFileId,
        groupKey = GroupKey.of(title, artist, durationMs),
        variant = GroupKey.variantOf(title),
    )

    /** The file's size, or what Telegram expects it to be while it is still uploading; 0 unknown. */
    private fun size(file: File): Long = file.size.takeIf { it > 0 } ?: file.expectedSize.coerceAtLeast(0)
}
