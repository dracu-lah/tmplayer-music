package com.tmplayer.music.tags

import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Tags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tag reader against small synthetic files of every format it knows. */
class TagReaderTest {

    @Test
    fun id3v23WithUtf16TitleFrontCoverAndXingDuration() {
        val t = TagReader.read(Samples.mp3v23, "a.mp3", sizeBytes = Samples.mp3v23.size.toLong())
        assertEquals("Night Drive é", t.title)
        assertEquals("First Artist", t.artist)
        assertEquals("Some Album", t.album)
        assertEquals("Album Artist", t.albumArtist)
        assertEquals(2009, t.year)
        assertEquals(3, t.trackNumber)
        assertEquals(1, t.discNumber)
        assertEquals("line one\nline two", t.lyrics)
        assertEquals("image/jpeg", t.cover?.mime)
        assertArrayEquals(Samples.jpeg, t.cover?.data)
        assertEquals(AudioFormat.MP3, t.format)
        assertEquals(44100, t.sampleRate)
        assertEquals(2, t.channels)
        assertEquals(1000L * 1152 * 1000 / 44100, t.durationMs)
        assertEquals((417_000L * 8 / t.durationMs).toInt(), t.bitrateKbps)
    }

    @Test
    fun id3v24Utf8FirstValuePlainSizeAndUnsyncFrame() {
        val t = TagReader.read(Samples.mp3v24)
        assertEquals("Über Title ☃", t.title)
        assertEquals("One", t.artist)
        assertEquals("Wide Album", t.album)
        assertEquals(2015, t.year)
        assertEquals(123_456L, t.durationMs)
        assertArrayEquals(Samples.jpeg, t.cover?.data)
        assertEquals(AudioFormat.MP3, t.format)
    }

    @Test
    fun id3v23WholeTagUnsynchronisation() {
        val frames = out {
            bytes(Samples.id3Frame23("TIT2", Samples.latin1Text("Synced")))
            bytes(Samples.id3Frame23("APIC", Samples.apic("image/jpeg", 3, Samples.jpeg)))
        }
        val synced = out {
            for (b in frames) {
                u8(b.toInt())
                if (b == 0xFF.toByte()) u8(0)
            }
        }
        val tag = Samples.id3Tag(3, synced, flags = 0x80)
        val t = TagReader.read(tag)
        assertEquals("Synced", t.title)
        assertArrayEquals(Samples.jpeg, t.cover?.data)
    }

    @Test
    fun constantBitrateMp3DurationFromSize() {
        val head = out { bytes(Samples.mpegFrame()).bytes(Samples.mpegFrame()) }
        val t = TagReader.read(head, "x.mp3", sizeBytes = 1_600_000)
        assertEquals(128, t.bitrateKbps)
        assertEquals(100_000L, t.durationMs)
        assertEquals(AudioFormat.MP3, t.format)
    }

    @Test
    fun flacStreamInfoCommentsAndPicture() {
        val t = TagReader.read(Samples.flac, sizeBytes = 882_000)
        assertEquals(AudioFormat.FLAC, t.format)
        assertEquals("Flac Title", t.title)
        assertEquals("Flac Artist", t.artist)
        assertEquals("Flac Album", t.album)
        assertEquals("Flac Band", t.albumArtist)
        assertEquals(2001, t.year)
        assertEquals(7, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertEquals(44100, t.sampleRate)
        assertEquals(16, t.bitDepth)
        assertEquals(2, t.channels)
        assertEquals(10_000L, t.durationMs)
        assertEquals(705, t.bitrateKbps)
        assertEquals("image/png", t.cover?.mime)
        assertArrayEquals(Samples.png, t.cover?.data)
    }

    @Test
    fun flacBehindAnId3Tag() {
        val file = out { bytes(Samples.id3Tag(3, Samples.id3Frame23("TIT2", Samples.latin1Text("From Id3")))).bytes(Samples.flac) }
        val t = TagReader.read(file)
        assertEquals(AudioFormat.FLAC, t.format)
        assertEquals("From Id3", t.title)
        assertEquals("Flac Artist", t.artist)
        assertEquals(10_000L, t.durationMs)
    }

    @Test
    fun opusCommentAcrossPagesPictureAndDurationFromTail() {
        val t = TagReader.read(Samples.opus, "x.opus", tail = Samples.opusTail)
        assertEquals(AudioFormat.OPUS, t.format)
        assertEquals("Opus Title", t.title)
        assertEquals("Opus Artist", t.artist)
        assertEquals(Samples.opusLyrics, t.lyrics)
        assertEquals(48000, t.sampleRate)
        assertEquals(2, t.channels)
        assertEquals(5_000L, t.durationMs)
        assertArrayEquals(Samples.jpeg, t.cover?.data)
    }

    @Test
    fun opusWithoutTailHasNoDuration() {
        val t = TagReader.read(Samples.opus)
        assertEquals("Opus Title", t.title)
        assertEquals(0L, t.durationMs)
    }

    @Test
    fun m4aItemsAndMvhd() {
        val t = TagReader.read(Samples.m4a)
        assertEquals(AudioFormat.M4A, t.format)
        assertEquals("M4A Title", t.title)
        assertEquals("M4A Artist", t.artist)
        assertEquals("M4A Album", t.album)
        assertEquals("M4A Band", t.albumArtist)
        assertEquals(2019, t.year)
        assertEquals(5, t.trackNumber)
        assertEquals(1, t.discNumber)
        assertEquals("m4a words", t.lyrics)
        assertEquals(180_000L, t.durationMs)
        assertEquals(44100, t.sampleRate)
        assertEquals(2, t.channels)
        assertEquals("image/jpeg", t.cover?.mime)
        assertArrayEquals(Samples.jpeg, t.cover?.data)
    }

    @Test
    fun alacSampleEntry() {
        val t = TagReader.read(Samples.alac)
        assertEquals(AudioFormat.ALAC, t.format)
        assertEquals(96000, t.sampleRate)
        assertEquals(24, t.bitDepth)
        assertEquals(2000, t.bitrateKbps)
        assertEquals("M4A Title", t.title)
    }

    @Test
    fun m4aWithMoovInTheTail() {
        assertEquals("", TagReader.read(Samples.m4aMoovLastHead).title)
        val t = TagReader.read(Samples.m4aMoovLastHead, tail = Samples.m4aMoovLastTail)
        assertEquals("M4A Title", t.title)
        assertEquals(180_000L, t.durationMs)
    }

    @Test
    fun wavFormatInfoAndDuration() {
        val t = TagReader.read(Samples.wav)
        assertEquals(AudioFormat.WAV, t.format)
        assertEquals("Wav Title", t.title)
        assertEquals("Wav Artist", t.artist)
        assertEquals(44100, t.sampleRate)
        assertEquals(16, t.bitDepth)
        assertEquals(2, t.channels)
        assertEquals(10_000L, t.durationMs)
        assertEquals(1411, t.bitrateKbps)
    }

    @Test
    fun aiffCommChunk() {
        val t = TagReader.read(Samples.aiff)
        assertEquals(AudioFormat.AIFF, t.format)
        assertEquals("Aiff Title", t.title)
        assertEquals(44100, t.sampleRate)
        assertEquals(16, t.bitDepth)
        assertEquals(2, t.channels)
        assertEquals(10_000L, t.durationMs)
    }

    @Test
    fun id3v1FromTheTailFillsWhatIsMissing() {
        val tail = out { zeros(50).bytes(Samples.id3v1("V1 Title".toByteArray(), track = 9)) }
        val t = TagReader.read(Samples.mpegFrame(), "x.mp3", tail = tail)
        assertEquals("V1 Title", t.title)
        assertEquals("V1 Artist", t.artist)
        assertEquals(1999, t.year)
        assertEquals(9, t.trackNumber)
    }

    @Test
    fun formatFallsBackToTheExtension() {
        assertEquals(AudioFormat.FLAC, TagReader.read(ByteArray(0), "song.FLAC").format)
        assertEquals(AudioFormat.OPUS, TagReader.read(ByteArray(16), "a.b.opus").format)
        assertEquals(Tags.EMPTY, TagReader.read(ByteArray(0)))
    }

    @Test
    fun jpegCoverCutShortIsDropped() {
        val cut = Samples.jpeg.copyOfRange(0, Samples.jpeg.size - 2) + ByteArray(64)
        val tag = Samples.id3Tag(
            3,
            out {
                bytes(Samples.id3Frame23("TIT2", Samples.latin1Text("Cut")))
                bytes(Samples.id3Frame23("APIC", Samples.apic("image/jpeg", 3, cut)))
            },
        )
        val t = TagReader.read(tag)
        assertEquals("Cut", t.title)
        assertNull(t.cover)
    }

    @Test
    fun oversizedCoverIsDropped() {
        val big = ByteArray(TagReader.MAX_COVER_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val tag = Samples.id3Tag(
            3,
            out {
                bytes(Samples.id3Frame23("TIT2", Samples.latin1Text("Big")))
                bytes(Samples.id3Frame23("APIC", Samples.apic("image/jpeg", 3, big)))
            },
        )
        val t = TagReader.read(tag)
        assertEquals("Big", t.title)
        assertNull(t.cover)
    }

    @Test
    fun bytesNeededForId3() {
        val head = Samples.mp3v23
        val size = (head.u8(6) shl 21) or (head.u8(7) shl 14) or (head.u8(8) shl 7) or head.u8(9)
        assertEquals(10L + size, TagReader.bytesNeeded(head))
        assertEquals(10L + size, TagReader.bytesNeeded(head.copyOf(10)))
        val footer = head.copyOf(64).also { it[3] = 4; it[5] = 0x10 }
        assertEquals(20L + size, TagReader.bytesNeeded(footer))
        assertNull(TagReader.bytesNeeded(head.copyOf(9)))
        assertNull(TagReader.bytesNeeded(Samples.wav))
    }

    @Test
    fun bytesNeededForFlac() {
        val flac = Samples.flac
        val metadataEnd = flac.size - 32L
        assertEquals(metadataEnd, TagReader.bytesNeeded(flac))
        // With only the first block's header, the walk can say no more than "the next header".
        val partial = TagReader.bytesNeeded(flac.copyOf(12))
        assertEquals(4L + 4 + 34 + 4, partial)
        val again = TagReader.bytesNeeded(flac.copyOf(partial!!.toInt()))!!
        assertTrue(again > partial)
        val tagged = out { bytes(Samples.id3Tag(3, Samples.id3Frame23("TIT2", Samples.latin1Text("x")))).bytes(flac) }
        assertEquals(tagged.size - 32L, TagReader.bytesNeeded(tagged))
    }

    @Test
    fun cyrillicWindows1251InLatin1FramesAndId3v1() {
        val title = "Летняя песня"
        val cp1251 = title.toByteArray(charset("windows-1251"))
        val tag = Samples.id3Tag(3, Samples.id3Frame23("TIT2", out { u8(0).bytes(cp1251) }))
        assertEquals(title, TagReader.read(tag).title)
        val tail = Samples.id3v1(cp1251)
        assertEquals(title, TagReader.read(ByteArray(0), tail = tail).title)
        assertEquals("ЗИМА", TextDecoding.decodeLegacy("ЗИМА".toByteArray(charset("windows-1251"))))
    }

    @Test
    fun shiftJisDecodesToJapanese() {
        val title = "夏の歌"
        val sjis = title.toByteArray(charset("Shift_JIS"))
        assertEquals(title, TextDecoding.decodeLegacy(sjis))
        val tag = Samples.id3Tag(3, Samples.id3Frame23("TPE1", out { u8(0).bytes(sjis) }))
        assertEquals(title, TagReader.read(tag).artist)
        val longer = "こんにちは世界"
        assertEquals(longer, TextDecoding.decodeLegacy(longer.toByteArray(charset("Shift_JIS"))))
    }

    @Test
    fun gbkDecodesToChinese() {
        val title = "我的歌"
        assertEquals(title, TextDecoding.decodeLegacy(title.toByteArray(charset("GBK"))))
    }

    @Test
    fun asciiAndRealUtf8AreLeftAlone() {
        assertEquals("Plain Title 42", TextDecoding.decodeLegacy("  Plain Title 42\u0000\u0000".toByteArray()))
        val utf8 = "Жук and 日本"
        assertEquals(utf8, TextDecoding.decodeLegacy(utf8.toByteArray(Charsets.UTF_8)))
        val tag = Samples.id3Tag(3, Samples.id3Frame23("TIT2", out { u8(0).utf8(utf8) }))
        assertEquals(utf8, TagReader.read(tag).title)
    }

    @Test
    fun accentedLatinStaysLatin() {
        val title = "Café Müller Straße"
        assertEquals(title, TextDecoding.decodeLegacy(title.toByteArray(charset("windows-1252"))))
    }
}
