package com.tmplayer.music.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioKindsTest {

    @Test
    fun `every audio extension is audio whatever the mime says`() {
        val extensions = listOf(
            "flac", "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav",
            "aif", "aiff", "aifc", "alac", "wv", "ape", "mka", "wma",
        )
        for (ext in extensions) {
            for (mime in listOf(null, "", "application/octet-stream", "application/x-whatever")) {
                assertEquals("$ext $mime", FileKind.Audio, AudioKinds.classify(mime, "Track.$ext"))
                assertEquals("upper $ext $mime", FileKind.Audio, AudioKinds.classify(mime, "Track.${ext.uppercase()}"))
            }
        }
    }

    @Test
    fun `an audio mime is audio even with no name`() {
        assertEquals(FileKind.Audio, AudioKinds.classify("audio/mpeg", null))
        assertEquals(FileKind.Audio, AudioKinds.classify("AUDIO/FLAC", ""))
        assertEquals(FileKind.Audio, AudioKinds.classify("audio/ogg; codecs=opus", "voice"))
        assertEquals(FileKind.Audio, AudioKinds.classify(" audio/x-wav ", "noext"))
    }

    @Test
    fun `lyrics are lrc and txt only`() {
        assertEquals(FileKind.Lyrics, AudioKinds.classify(null, "track.lrc"))
        assertEquals(FileKind.Lyrics, AudioKinds.classify("text/plain", "Track.TXT"))
        assertEquals(FileKind.Lyrics, AudioKinds.classify("application/octet-stream", "Nadia Okafor - Glass Harbour.lrc"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("text/plain", "notes.nfo"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("text/plain", "album.cue"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("text/plain", "readme.md"))
    }

    @Test
    fun `archives, playlists, video and other documents are ignored`() {
        assertEquals(FileKind.Ignore, AudioKinds.classify("application/zip", "x.zip"))
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, "x.zip"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("audio/x-mpegurl", "list.m3u"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("audio/x-mpegurl", "list"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("audio/mpeg", "list.m3u8"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("video/mp4", "clip.mp4"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("video/mp4", "clip.m4a"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("application/pdf", "booklet.pdf"))
        assertEquals(FileKind.Ignore, AudioKinds.classify("image/jpeg", "cover.jpg"))
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, null))
        assertEquals(FileKind.Ignore, AudioKinds.classify("", ""))
        assertEquals(FileKind.Ignore, AudioKinds.classify("   ", "   "))
    }

    @Test
    fun `odd names are read by their last real extension`() {
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, ""))
        assertEquals(FileKind.Audio, AudioKinds.classify(null, ".flac"))
        assertEquals(FileKind.Audio, AudioKinds.classify(null, "a.b.c.FLAC"))
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, "trailing."))
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, "no extension at all"))
        assertEquals(FileKind.Ignore, AudioKinds.classify(null, "Mr. Nobody"))
        assertEquals(FileKind.Audio, AudioKinds.classify(null, "  spaced.mp3  "))
    }

    @Test
    fun `format comes from the extension, refined by the mime`() {
        assertEquals(AudioFormat.FLAC, AudioKinds.format("audio/flac", null))
        assertEquals(AudioFormat.FLAC, AudioKinds.format("audio/x-flac", "a"))
        assertEquals(AudioFormat.MP3, AudioKinds.format("audio/mpeg", null))
        assertEquals(AudioFormat.M4A, AudioKinds.format("audio/mp4", null))
        assertEquals(AudioFormat.M4A, AudioKinds.format("audio/x-m4a", null))
        assertEquals(AudioFormat.AAC, AudioKinds.format("audio/aac", null))
        assertEquals(AudioFormat.OGG, AudioKinds.format("audio/ogg", null))
        assertEquals(AudioFormat.OGG, AudioKinds.format("audio/ogg", "song.ogg"))
        assertEquals(AudioFormat.OPUS, AudioKinds.format("audio/ogg", "song.opus"))
        assertEquals(AudioFormat.OPUS, AudioKinds.format("audio/opus", "song.ogg"))
        assertEquals(AudioFormat.OPUS, AudioKinds.format("audio/opus", null))
        assertEquals(AudioFormat.WAV, AudioKinds.format("audio/wav", null))
        assertEquals(AudioFormat.WAV, AudioKinds.format("audio/x-wav", null))
        assertEquals(AudioFormat.AIFF, AudioKinds.format("audio/aiff", null))
        assertEquals(AudioFormat.AIFF, AudioKinds.format("audio/x-aiff", null))
        assertEquals(AudioFormat.FLAC, AudioKinds.format("application/octet-stream", "Song.FLAC"))
        assertEquals(AudioFormat.FLAC, AudioKinds.format("audio/mpeg", "mislabelled.flac"))
        assertEquals(AudioFormat.ALAC, AudioKinds.format(null, "song.alac"))
        assertEquals(AudioFormat.AIFF, AudioKinds.format(null, "song.aifc"))
        assertEquals(AudioFormat.M4A, AudioKinds.format(null, "book.m4b"))
        assertEquals(AudioFormat.OGG, AudioKinds.format(null, "song.oga"))
        assertEquals(AudioFormat.OTHER, AudioKinds.format("audio/flac", "song.ape"))
        assertEquals(AudioFormat.OTHER, AudioKinds.format(null, "song.wv"))
        assertEquals(AudioFormat.OTHER, AudioKinds.format(null, "song.wma"))
        assertEquals(AudioFormat.OTHER, AudioKinds.format(null, "song.mka"))
        assertEquals(AudioFormat.OTHER, AudioKinds.format(null, null))
        assertEquals(AudioFormat.OTHER, AudioKinds.format("audio/x-something", "noext"))
    }

    @Test
    fun `only OTHER is unplayable`() {
        for (format in AudioFormat.entries) {
            assertEquals(format.name, format != AudioFormat.OTHER, AudioKinds.playable(format))
        }
        assertFalse(AudioKinds.playable(AudioKinds.format(null, "song.ape")))
        assertTrue(AudioKinds.playable(AudioKinds.format(null, "song.flac")))
    }

    @Test
    fun `base name pairs lyrics with their track`() {
        assertEquals("Glass Harbour", AudioKinds.baseName("Glass Harbour.flac"))
        assertEquals("Glass Harbour", AudioKinds.baseName("Glass Harbour.lrc"))
        assertEquals("a.b.c", AudioKinds.baseName("a.b.c.FLAC"))
        assertEquals("", AudioKinds.baseName(".flac"))
        assertEquals("", AudioKinds.baseName(""))
        assertEquals("No extension", AudioKinds.baseName(" No extension "))
        assertEquals("Mr. Nobody", AudioKinds.baseName("Mr. Nobody"))
        assertEquals("01. Opening", AudioKinds.baseName("01. Opening"))
        assertEquals("trailing.", AudioKinds.baseName("trailing."))
    }
}
