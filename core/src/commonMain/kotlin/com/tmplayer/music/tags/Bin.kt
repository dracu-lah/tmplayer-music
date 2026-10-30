package com.tmplayer.music.tags

// Bounds-checked reads over a byte array. Every offset and length in a tag comes from the file, so
// nothing here throws: a read past either end gives 0, and callers check [fits] before they trust
// a length they read. Lengths are Long so a 32 bit size near 4 GB never wraps to a negative Int.

/** No single length read from a file is believed above this. */
internal const val MAX_LEN: Long = 16L * 1024 * 1024

/** Whether [len] bytes from [off] are all inside the array. */
internal fun ByteArray.fits(off: Int, len: Long): Boolean = off >= 0 && len >= 0 && off.toLong() + len <= size

internal fun ByteArray.u8(i: Int): Int = if (i >= 0 && i < size) this[i].toInt() and 0xFF else 0

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u24be(i: Int): Long = (u8(i).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i + 2).toLong()

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

/** A 64 bit big-endian value; a huge one comes out negative, which callers treat as absurd. */
internal fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)

internal fun ByteArray.u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)

/** An ID3 syncsafe integer (7 bits per byte), or -1 when a byte has its top bit set. */
internal fun ByteArray.syncsafe(i: Int): Long {
    var v = 0L
    for (k in 0 until 4) {
        val c = u8(i + k)
        if (c and 0x80 != 0) return -1
        v = (v shl 7) or c.toLong()
    }
    return v
}

/** [len] bytes from [off] as Latin-1 characters, cut at the array's end. */
internal fun ByteArray.ascii(off: Int, len: Int): String {
    if (off < 0 || len <= 0) return ""
    val end = minOf(size.toLong(), off.toLong() + len).toInt()
    if (off >= end) return ""
    val chars = CharArray(end - off) { (this[off + it].toInt() and 0xFF).toChar() }
    return String(chars)
}

/** Whether the bytes at [off] spell [magic] (Latin-1). */
internal fun ByteArray.at(off: Int, magic: String): Boolean {
    if (!fits(off, magic.length.toLong())) return false
    for (k in magic.indices) if (u8(off + k) != (magic[k].code and 0xFF)) return false
    return true
}

/** A copy of [len] bytes from [off], or null when they are not all there or the length is absurd. */
internal fun ByteArray.sliceOrNull(off: Int, len: Long): ByteArray? =
    if (len <= MAX_LEN && fits(off, len)) copyOfRange(off, off + len.toInt()) else null

/** The index of the first byte equal to [value] in [from, to), or [to] when there is none. */
internal fun ByteArray.indexOfByte(value: Int, from: Int, to: Int): Int {
    var i = maxOf(from, 0)
    val end = minOf(to, size)
    while (i < end) {
        if (u8(i) == value) return i
        i++
    }
    return maxOf(to, from)
}
