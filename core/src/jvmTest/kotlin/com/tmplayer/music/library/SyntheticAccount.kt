package com.tmplayer.music.library

import java.util.Locale
import kotlin.random.Random

/**
 * A made-up account for scale tests: [trackCount] audio files over [chatCount] chats, the first
 * of which holds [bigChat] of them. About 30 percent of the files repeat an earlier recording (in
 * another chat or another format, some as forwards of the same file), some are live or remix
 * variants, some have no duration or no artist. Every name is invented from syllables, and the
 * same [seed] always gives the same account.
 */
class SyntheticAccount(
    val trackCount: Int,
    val chatCount: Int,
    val bigChat: Int = 0,
    seed: Long = 42,
) {
    private val random = Random(seed)

    /** The chats, chat ids 1 to [chatCount], titled with invented words. */
    val chats: List<ChatPick> = (1..chatCount).map { ChatPick(it.toLong(), "${word(2)} ${word(2)}") }

    /** Every file, in posting order per chat, ready to upsert in batches. */
    val tracks: List<Track> = generate()

    private class Recording(
        val title: String,
        val artist: String,
        val album: String,
        val year: Int,
        val trackNumber: Int,
        val durationMs: Long,
        val variant: String,
    )

    private fun syllable(): String = SYLLABLES[random.nextInt(SYLLABLES.size)]

    private fun word(syllables: Int): String =
        (1..syllables).joinToString("") { syllable() }.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun generate(): List<Track> {
        val artists = List(maxOf(1, trackCount / 20)) { "${word(2)} ${word(1 + random.nextInt(2))}" }
        val recordings = ArrayList<Recording>()
        val out = ArrayList<Track>(trackCount)
        val nextMessage = LongArray(chatCount + 1) { 1 }
        var albumLeft = 0
        var album = ""
        var albumArtist = ""
        var year = 0
        var trackNumber = 0
        var uniqueSeq = 0

        fun chatFor(index: Int): Long =
            if (index < bigChat || chatCount == 1) 1L else 2L + random.nextInt(chatCount - 1)

        for (i in 0 until trackCount) {
            val chatId = chatFor(i)
            val messageId = nextMessage[chatId.toInt()]++ * 10
            val repeat = recordings.isNotEmpty() && random.nextInt(100) < 30
            val rec: Recording
            var uniqueId: String
            var format: AudioFormat
            if (repeat) {
                val originalIndex = random.nextInt(recordings.size)
                val original = recordings[originalIndex]
                val far = random.nextInt(100) < 3
                val drift = if (far) 60_000L else random.nextLong(-500, 500)
                rec = Recording(
                    original.title, original.artist, original.album, original.year, original.trackNumber,
                    if (original.durationMs == 0L) 0 else original.durationMs + drift, original.variant,
                )
                // A third of the repeats are forwards of the very same file.
                uniqueId = if (random.nextInt(3) == 0) "r$originalIndex" else "u${uniqueSeq++}"
                format = if (random.nextBoolean()) AudioFormat.FLAC else AudioFormat.MP3
            } else {
                if (albumLeft == 0) {
                    albumLeft = 8 + random.nextInt(6)
                    album = if (random.nextInt(10) == 0) "" else word(2) + " " + word(2)
                    albumArtist = artists[random.nextInt(artists.size)]
                    year = 1970 + random.nextInt(55)
                    trackNumber = 0
                }
                albumLeft--
                trackNumber++
                val artist = if (random.nextInt(100) < 3) "" else albumArtist
                val variant = when (random.nextInt(100)) {
                    0, 1 -> "live"
                    2 -> "remix"
                    else -> ""
                }
                val duration = if (random.nextInt(100) < 5) 0L else 120_000L + random.nextLong(240_000)
                rec = Recording(word(2) + " " + word(1 + random.nextInt(3)), artist, album, year, trackNumber, duration, variant)
                uniqueId = "r${recordings.size}"
                recordings += rec
                format = if (random.nextInt(4) == 0) AudioFormat.FLAC else AudioFormat.MP3
            }
            val title = if (rec.variant.isEmpty()) rec.title else "${rec.title} (${rec.variant})"
            out += Track(
                chatId = chatId,
                messageId = messageId,
                fileId = i + 1,
                uniqueId = uniqueId,
                fileName = "$title.${format.label.lowercase(Locale.ROOT)}",
                mime = if (format == AudioFormat.FLAC) "audio/flac" else "audio/mpeg",
                sizeBytes = if (format == AudioFormat.FLAC) 30_000_000L + random.nextInt(9_000_000) else 8_000_000L + random.nextInt(2_000_000),
                dateSec = 1_600_000_000L + i * 60L,
                title = title,
                artist = rec.artist,
                album = rec.album,
                albumArtist = if (rec.album.isEmpty()) "" else rec.artist,
                year = rec.year,
                trackNumber = rec.trackNumber,
                durationMs = rec.durationMs,
                format = format,
                bitrateKbps = if (format == AudioFormat.FLAC) 900 else 320,
                tagState = TagState.Read,
                thumbFileId = if (random.nextInt(4) == 0) 0 else i + 1_000_000,
                groupKey = groupKeyOf(rec.artist, rec.title, rec.variant),
                variant = rec.variant,
            )
        }
        return out
    }

    companion object {
        private val SYLLABLES = listOf(
            "ka", "lo", "mi", "ren", "tas", "vo", "qui", "zel", "dor", "fen", "gal", "hu", "jo", "nar",
            "pex", "ru", "sil", "tor", "um", "wy", "bri", "cal", "dex", "elm", "fro", "gin", "hal", "ix",
        )

        /** A stand-in for the real GroupKey: artist and title, with the variant kept apart. */
        fun groupKeyOf(artist: String, title: String, variant: String = ""): String =
            artist.lowercase(Locale.ROOT).trim() + "\u0001" + title.lowercase(Locale.ROOT).trim() +
                (if (variant.isEmpty()) "" else "\u0001$variant")
    }
}
