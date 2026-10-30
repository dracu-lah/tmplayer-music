package com.tmplayer.music.tags

import java.io.ByteArrayOutputStream

/** A small byte writer for building synthetic files in tests. */
internal class Out {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16be(v: Int) = apply { u8(v shr 8).u8(v) }

    fun u16le(v: Int) = apply { u8(v).u8(v shr 8) }

    fun u24be(v: Int) = apply { u8(v shr 16).u8(v shr 8).u8(v) }

    fun u32be(v: Long) = apply { u16be((v shr 16).toInt()).u16be(v.toInt()) }

    fun u32le(v: Long) = apply { u16le(v.toInt()).u16le((v shr 16).toInt()) }

    fun u64be(v: Long) = apply { u32be(v ushr 32).u32be(v and 0xFFFFFFFFL) }

    fun u64le(v: Long) = apply { u32le(v and 0xFFFFFFFFL).u32le(v ushr 32) }

    fun syncsafe(v: Int) = apply { u8((v shr 21) and 0x7F).u8((v shr 14) and 0x7F).u8((v shr 7) and 0x7F).u8(v and 0x7F) }

    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }

    fun utf8(s: String) = apply { out.write(s.toByteArray(Charsets.UTF_8)) }

    fun zeros(n: Int) = apply { out.write(ByteArray(n)) }

    fun build(): ByteArray = out.toByteArray()
}

internal fun out(block: Out.() -> Unit): ByteArray = Out().apply(block).build()

/** Synthetic files, one per format the reader knows, shared by the reader and garbage tests. */
internal object Samples {

    val jpeg = out { u8(0xFF).u8(0xD8).u8(0xFF).u8(0xE0).ascii("fake jpeg body").zeros(40).u8(0xFF).u8(0xD9) }
    val png = out { u8(0x89).ascii("PNG").u8(0x0D).u8(0x0A).u8(0x1A).u8(0x0A).ascii("fake png body").zeros(20) }

    fun id3Frame23(id: String, data: ByteArray) = out { ascii(id).u32be(data.size.toLong()).u16be(0).bytes(data) }

    fun id3Frame24(id: String, data: ByteArray, flags: Int = 0) = out { ascii(id).syncsafe(data.size).u16be(flags).bytes(data) }

    fun id3Tag(major: Int, frames: ByteArray, flags: Int = 0, padding: Int = 0) =
        out { ascii("ID3").u8(major).u8(0).u8(flags).syncsafe(frames.size + padding).bytes(frames).zeros(padding) }

    fun latin1Text(s: String) = out { u8(0).bytes(s.toByteArray(Charsets.ISO_8859_1)) }

    fun utf8Text(s: String) = out { u8(3).utf8(s) }

    fun utf16Text(s: String) = out { u8(1).u8(0xFF).u8(0xFE).bytes(s.toByteArray(Charsets.UTF_16LE)).u16be(0) }

    fun apic(mime: String, type: Int, data: ByteArray) = out { u8(0).ascii(mime).u8(0).u8(type).ascii("d").u8(0).bytes(data) }

    /** An MPEG-1 layer III, 128 kbps, 44.1 kHz stereo frame of 417 bytes. */
    fun mpegFrame(xingFrames: Long = -1, xingBytes: Long = 0) = out {
        u8(0xFF).u8(0xFB).u8(0x90).u8(0x00)
        zeros(32)
        if (xingFrames >= 0) ascii("Xing").u32be(3).u32be(xingFrames).u32be(xingBytes)
        zeros(417 - size)
    }

    /** ID3v2.3 with a UTF-16 title, Latin-1 fields, a back cover then a front cover, then a Xing frame. */
    val mp3v23: ByteArray by lazy {
        val frames = out {
            bytes(id3Frame23("TIT2", utf16Text("Night Drive é")))
            bytes(id3Frame23("TPE1", latin1Text("First Artist")))
            bytes(id3Frame23("TALB", latin1Text("Some Album")))
            bytes(id3Frame23("TPE2", latin1Text("Album Artist")))
            bytes(id3Frame23("TYER", latin1Text("2009")))
            bytes(id3Frame23("TRCK", latin1Text("3/12")))
            bytes(id3Frame23("TPOS", latin1Text("1/2")))
            bytes(id3Frame23("APIC", apic("image/png", 4, png)))
            bytes(id3Frame23("APIC", apic("image/jpeg", 3, jpeg)))
            bytes(id3Frame23("USLT", out { u8(0).ascii("eng").u8(0).ascii("line one\nline two") }))
        }
        out {
            bytes(id3Tag(3, frames, padding = 64))
            bytes(mpegFrame(xingFrames = 1000, xingBytes = 417_000))
            bytes(mpegFrame())
        }
    }

    /** ID3v2.4 with UTF-8 text, two artists, a plain (non-syncsafe) size, and an unsynchronised cover. */
    val mp3v24: ByteArray by lazy {
        val unsyncCover = out {
            val raw = apic("image/jpeg", 3, jpeg)
            val synced = out {
                var i = 0
                while (i < raw.size) {
                    u8(raw[i].toInt())
                    if (raw[i] == 0xFF.toByte()) u8(0)
                    i++
                }
            }
            ascii("APIC").syncsafe(synced.size + 4).u16be(0x0003).syncsafe(raw.size).bytes(synced)
        }
        val album = out { u8(3).utf8("Wide Album").zeros(256 - 11) }
        val frames = out {
            bytes(id3Frame24("TIT2", utf8Text("Über Title ☃")))
            bytes(id3Frame24("TPE1", out { u8(3).utf8("One").u8(0).utf8("Two") }))
            // Size 256 written as a plain integer, the common v2.4 mistake: syncsafe would read 128.
            ascii("TALB").u32be(256).u16be(0).bytes(album)
            bytes(id3Frame24("TDRC", utf8Text("2015-06-01")))
            bytes(id3Frame24("TLEN", utf8Text("123456")))
            bytes(unsyncCover)
        }
        id3Tag(4, frames, padding = 32)
    }

    val flac: ByteArray by lazy {
        val streamInfo = out {
            u16be(4096).u16be(4096).u24be(0).u24be(0)
            u64be((44100L shl 44) or (1L shl 41) or (15L shl 36) or 441_000L)
            zeros(16)
        }
        val comments = out {
            u32le(6).ascii("vendor").u32le(7)
            for (c in listOf(
                "TITLE=Flac Title", "artist=Flac Artist", "ALBUM=Flac Album", "ALBUM ARTIST=Flac Band",
                "DATE=2001-02-03", "TRACKNUMBER=7/10", "DISCNUMBER=2",
            )) {
                val b = c.toByteArray(Charsets.UTF_8)
                u32le(b.size.toLong()).bytes(b)
            }
        }
        out {
            ascii("fLaC")
            u8(0).u24be(streamInfo.size).bytes(streamInfo)
            u8(4).u24be(comments.size).bytes(comments)
            val pic = flacPicture(3, "image/png", png)
            u8(0x86).u24be(pic.size).bytes(pic)
            u8(0xFF).u8(0xF8).zeros(30) // a frame start
        }
    }

    fun flacPicture(type: Int, mime: String, data: ByteArray) = out {
        u32be(type.toLong()).u32be(mime.length.toLong()).ascii(mime).u32be(0).u32be(1).u32be(1).u32be(24).u32be(0)
        u32be(data.size.toLong()).bytes(data)
    }

    fun oggPage(serial: Long, seq: Int, granule: Long, type: Int, lacing: List<Int>, body: ByteArray) = out {
        ascii("OggS").u8(0).u8(type).u64le(granule).u32le(serial).u32le(seq.toLong()).u32le(0)
        u8(lacing.size)
        lacing.forEach { u8(it) }
        bytes(body)
    }

    val opusLyrics = "la ".repeat(200).trim()

    /** Ogg Opus: OpusHead on page 0, the OpusTags packet split over pages 1 and 2. */
    val opus: ByteArray by lazy {
        val head = out { ascii("OpusHead").u8(1).u8(2).u16le(312).u32le(44100).u16le(0).u8(0) }
        val pic = java.util.Base64.getEncoder().encodeToString(flacPicture(3, "image/jpeg", jpeg))
        val tags = out {
            ascii("OpusTags").u32le(3).ascii("enc").u32le(4)
            for (c in listOf("TITLE=Opus Title", "Artist=Opus Artist", "LYRICS=$opusLyrics", "METADATA_BLOCK_PICTURE=$pic")) {
                val b = c.toByteArray(Charsets.UTF_8)
                u32le(b.size.toLong()).bytes(b)
            }
        }
        val lacing = ArrayList<Int>()
        var left = tags.size
        while (left >= 255) {
            lacing += 255
            left -= 255
        }
        lacing += left
        val split = 1
        out {
            bytes(oggPage(7, 0, 0, 2, listOf(head.size), head))
            bytes(oggPage(7, 1, 0, 0, lacing.take(split), tags.copyOfRange(0, 255 * split)))
            bytes(oggPage(7, 2, 0, 1, lacing.drop(split), tags.copyOfRange(255 * split, tags.size)))
        }
    }

    val opusTail: ByteArray by lazy {
        out {
            zeros(100)
            bytes(oggPage(7, 9, 48000L * 5 + 312, 4, listOf(10), ByteArray(10)))
        }
    }

    fun atom(type: String, vararg children: ByteArray) = out {
        u32be(8L + children.sumOf { it.size }).ascii(type)
        children.forEach { bytes(it) }
    }

    fun dataAtom(type: Int, value: ByteArray) = atom("data", out { u32be(type.toLong()).u32be(0).bytes(value) })

    fun textItem(name: String, value: String) = atom(name, dataAtom(1, value.toByteArray(Charsets.UTF_8)))

    fun mp4Moov(alac: Boolean): ByteArray {
        val mvhd = atom("mvhd", out { u32be(0).u32be(0).u32be(0).u32be(1000).u32be(180_000).zeros(80) })
        val entry = if (alac) {
            val inner = atom("alac", out { u32be(0).u32be(4096).u8(0).u8(24).u8(40).u8(10).u8(14).u8(2).u16be(255).u32be(0).u32be(2_000_000).u32be(96000) })
            atom("alac", out { zeros(6).u16be(1).zeros(8).u16be(2).u16be(16).u16be(0).u16be(0).u32be(0) }, inner)
        } else {
            atom("mp4a", out { zeros(6).u16be(1).zeros(8).u16be(2).u16be(16).u16be(0).u16be(0).u32be(44100L shl 16) })
        }
        val stsd = atom("stsd", out { u32be(0).u32be(1) }, entry)
        val trak = atom("trak", atom("mdia", atom("minf", atom("stbl", stsd))))
        val ilst = atom(
            "ilst",
            textItem("©nam", "M4A Title"),
            textItem("©ART", "M4A Artist"),
            textItem("©alb", "M4A Album"),
            textItem("aART", "M4A Band"),
            textItem("©day", "2019-01-01T00:00:00Z"),
            atom("trkn", dataAtom(0, out { u16be(0).u16be(5).u16be(12).u16be(0) })),
            atom("disk", dataAtom(0, out { u16be(0).u16be(1).u16be(2) })),
            textItem("©lyr", "m4a words"),
            atom("covr", dataAtom(13, jpeg)),
        )
        val hdlr = atom("hdlr", out { u32be(0).u32be(0).ascii("mdirappl").zeros(9) })
        val meta = atom("meta", out { u32be(0) }, hdlr, ilst)
        return atom("moov", mvhd, trak, atom("udta", meta))
    }

    val ftyp = atom("ftyp", out { ascii("M4A ").u32be(0).ascii("M4A isom") })

    val m4a: ByteArray by lazy { out { bytes(ftyp).bytes(mp4Moov(false)).bytes(atom("mdat", ByteArray(64))) } }

    val alac: ByteArray by lazy { out { bytes(ftyp).bytes(mp4Moov(true)).bytes(atom("mdat", ByteArray(64))) } }

    /** An M4A whose moov is after the audio: the head has ftyp and the start of a big mdat. */
    val m4aMoovLastHead: ByteArray by lazy { out { bytes(ftyp).u32be(8L + 1_000_000).ascii("mdat").zeros(200) } }

    val m4aMoovLastTail: ByteArray by lazy { out { zeros(333).bytes(mp4Moov(false)) } }

    val wav: ByteArray by lazy {
        val fmt = out { u16le(1).u16le(2).u32le(44100).u32le(176_400).u16le(4).u16le(16) }
        val info = out {
            ascii("INFO")
            ascii("INAM").u32le(10).ascii("Wav Title").u8(0)
            ascii("IART").u32le(11).ascii("Wav Artist").u8(0).u8(0)
        }
        val body = out {
            ascii("WAVE")
            ascii("fmt ").u32le(fmt.size.toLong()).bytes(fmt)
            ascii("LIST").u32le(info.size.toLong()).bytes(info)
            ascii("data").u32le(1_764_000).zeros(64)
        }
        out { ascii("RIFF").u32le(body.size.toLong()).bytes(body) }
    }

    val aiff: ByteArray by lazy {
        val body = out {
            ascii("AIFF")
            ascii("COMM").u32be(18).u16be(2).u32be(441_000).u16be(16)
            u16be(0x400E).u32be(0xAC440000L).u32be(0)
            ascii("NAME").u32be(10).ascii("Aiff Title")
            ascii("SSND").u32be(72).zeros(72)
        }
        out { ascii("FORM").u32be(body.size.toLong()).bytes(body) }
    }

    fun id3v1(title: ByteArray, track: Int = 0) = out {
        ascii("TAG").bytes(title.copyOf(30)).bytes("V1 Artist".toByteArray().copyOf(30)).zeros(30).ascii("1999")
        zeros(28).u8(0).u8(track).u8(255)
    }

    /** Every valid sample with a name, for the garbage tests. */
    val all: List<Pair<String, ByteArray>> by lazy {
        listOf(
            "mp3v23" to mp3v23,
            "mp3v24" to mp3v24,
            "flac" to flac,
            "opus" to opus,
            "m4a" to m4a,
            "alac" to alac,
            "m4aMoovLastTail" to m4aMoovLastTail,
            "wav" to wav,
            "aiff" to aiff,
            "id3v1" to id3v1("Title".toByteArray(), 4),
        )
    }
}
