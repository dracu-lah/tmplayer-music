package com.tmplayer.music.tags

/** Synthetic files that carry ReplayGain tags, one per tag family; shared by the tag and garbage tests. */
internal object GainSamples {

    private fun txxx(description: String, value: String, encoding: Int = 0) = out {
        when (encoding) {
            1 -> u8(1).u8(0xFF).u8(0xFE).bytes(description.toByteArray(Charsets.UTF_16LE)).u16be(0)
                .u8(0xFF).u8(0xFE).bytes(value.toByteArray(Charsets.UTF_16LE))
            3 -> u8(3).utf8(description).u8(0).utf8(value)
            else -> u8(0).ascii(description).u8(0).ascii(value)
        }
    }

    /** ID3v2.3 with a title, Latin-1 and UTF-16 TXXX gain frames. */
    val mp3: ByteArray by lazy {
        val frames = out {
            bytes(Samples.id3Frame23("TIT2", Samples.latin1Text("Gain Title")))
            bytes(Samples.id3Frame23("TXXX", txxx("REPLAYGAIN_TRACK_GAIN", "-6.50 dB")))
            bytes(Samples.id3Frame23("TXXX", txxx("replaygain_track_peak", "0.988"))) // any case
            bytes(Samples.id3Frame23("TXXX", txxx("REPLAYGAIN_ALBUM_GAIN", "-4.25 dB", encoding = 1)))
            bytes(Samples.id3Frame23("TXXX", txxx("REPLAYGAIN_ALBUM_PEAK", "1.000000", encoding = 1)))
            bytes(Samples.id3Frame23("TXXX", txxx("SOMETHING_ELSE", "-99 dB")))
        }
        out { bytes(Samples.id3Tag(3, frames)).bytes(Samples.mpegFrame()).bytes(Samples.mpegFrame()) }
    }

    /** ID3v2.4 with UTF-8 TXXX frames and a decimal comma. */
    val mp3v24: ByteArray by lazy {
        val frames = out {
            bytes(Samples.id3Frame24("TXXX", txxx("REPLAYGAIN_TRACK_GAIN", "+3,10 dB", encoding = 3)))
            bytes(Samples.id3Frame24("TXXX", txxx("REPLAYGAIN_TRACK_PEAK", "0.5", encoding = 3)))
        }
        out { bytes(Samples.id3Tag(4, frames)).bytes(Samples.mpegFrame()).bytes(Samples.mpegFrame()) }
    }

    private fun comments(vararg entries: String) = out {
        u32le(6).ascii("vendor").u32le(entries.size.toLong())
        for (c in entries) {
            val b = c.toByteArray(Charsets.UTF_8)
            u32le(b.size.toLong()).bytes(b)
        }
    }

    val flac: ByteArray by lazy {
        val streamInfo = out {
            u16be(4096).u16be(4096).u24be(0).u24be(0)
            u64be((44100L shl 44) or (1L shl 41) or (15L shl 36) or 441_000L)
            zeros(16)
        }
        val tags = comments(
            "TITLE=Flac Gain", "ReplayGain_Track_Gain=-8.12 dB", "REPLAYGAIN_TRACK_PEAK=0.912345",
            "REPLAYGAIN_ALBUM_GAIN=-7.00 dB", "REPLAYGAIN_ALBUM_PEAK=0.999",
        )
        out {
            ascii("fLaC")
            u8(0).u24be(streamInfo.size).bytes(streamInfo)
            u8(0x84).u24be(tags.size).bytes(tags)
            u8(0xFF).u8(0xF8).zeros(30)
        }
    }

    /** Ogg Opus (the same Vorbis comment block as Ogg Vorbis) with track gain only. */
    val opus: ByteArray by lazy {
        val head = out { ascii("OpusHead").u8(1).u8(2).u16le(312).u32le(44100).u16le(0).u8(0) }
        val body = out {
            ascii("OpusTags").u32le(3).ascii("enc").u32le(2)
            for (c in listOf("TITLE=Opus Gain", "REPLAYGAIN_TRACK_GAIN=-3.5 dB")) {
                val b = c.toByteArray(Charsets.UTF_8)
                u32le(b.size.toLong()).bytes(b)
            }
        }
        val lacing = ArrayList<Int>()
        var left = body.size
        while (left >= 255) {
            lacing += 255
            left -= 255
        }
        lacing += left
        out {
            bytes(Samples.oggPage(7, 0, 0, 2, listOf(head.size), head))
            bytes(Samples.oggPage(7, 1, 0, 0, lacing, body))
        }
    }

    private fun freeform(name: String, value: String) = Samples.atom(
        "----",
        Samples.atom("mean", out { u32be(0).ascii("com.apple.iTunes") }),
        Samples.atom("name", out { u32be(0).ascii(name) }),
        Samples.dataAtom(1, value.toByteArray(Charsets.UTF_8)),
    )

    /** An M4A with iTunes style freeform gain atoms next to the usual items. */
    val m4a: ByteArray by lazy {
        val ilst = Samples.atom(
            "ilst",
            Samples.textItem("©nam", "M4A Gain"),
            freeform("REPLAYGAIN_TRACK_GAIN", "-7.10 dB"),
            freeform("REPLAYGAIN_TRACK_PEAK", "0.95"),
            freeform("REPLAYGAIN_ALBUM_GAIN", "-6.00 dB"),
            freeform("OTHER", "x"),
        )
        val hdlr = Samples.atom("hdlr", out { u32be(0).u32be(0).ascii("mdirappl").zeros(9) })
        val meta = Samples.atom("meta", out { u32be(0) }, hdlr, ilst)
        val mvhd = Samples.atom("mvhd", out { u32be(0).u32be(0).u32be(0).u32be(1000).u32be(180_000).zeros(80) })
        val moov = Samples.atom("moov", mvhd, Samples.atom("udta", meta))
        out { bytes(Samples.ftyp).bytes(moov).bytes(Samples.atom("mdat", ByteArray(64))) }
    }

    val all: List<Pair<String, ByteArray>> by lazy {
        listOf("gainMp3" to mp3, "gainMp3v24" to mp3v24, "gainFlac" to flac, "gainOpus" to opus, "gainM4a" to m4a)
    }
}
