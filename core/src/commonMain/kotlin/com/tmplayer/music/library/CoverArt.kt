package com.tmplayer.music.library

/**
 * The cover drawn for a track or album that has no picture of its own (none in the tags, none from
 * Telegram): a layout of shapes ([CoverSeed.style]), three colours ([CoverSeed.palette]) and a
 * mirror flag, all picked from a stable key. The same key gives the same cover on every launch and
 * every device, so it is a plain function of the key: no random numbers, no clock, no locale.
 *
 * The key is the album's ([AlbumKey]: album artist plus album plus year) for a track on an album, so
 * every track of the album shows one cover, else the song's base key, so the versions of one song
 * share it. The drawing itself is the app's (`GeneratedCover`); this is the part a test can check.
 * A missing cover is not an error, so nothing here throws, whatever the key.
 */
object CoverArt {
    /** How many layouts the painter knows; [CoverSeed.style] is always below it. */
    const val STYLES = 7

    /**
     * A deep, a middle and a light colour (ARGB). Muted on purpose: each holds up on a white and on a
     * black background and sits beside the theme's neutral accents without competing with them. They
     * do not follow the theme, so a cover looks the same in light and dark.
     */
    val palettes: List<IntArray> = listOf(
        intArrayOf(0xFF0B3D4F.toInt(), 0xFF2E8B8B.toInt(), 0xFFF2A57A.toInt()),
        intArrayOf(0xFF1B1F4B.toInt(), 0xFF6A4FB3.toInt(), 0xFFFFC7A8.toInt()),
        intArrayOf(0xFF5E1F33.toInt(), 0xFFC4677A.toInt(), 0xFFF2C08A.toInt()),
        intArrayOf(0xFF102A1E.toInt(), 0xFF3F8F5A.toInt(), 0xFFCFE08A.toInt()),
        intArrayOf(0xFF1E2228.toInt(), 0xFF56606E.toInt(), 0xFFE8C26A.toInt()),
        intArrayOf(0xFF4F8FBF.toInt(), 0xFFDDA3B6.toInt(), 0xFFFBEFD2.toInt()),
        intArrayOf(0xFF241A4A.toInt(), 0xFF2D7DD2.toInt(), 0xFF8FD9CF.toInt()),
        intArrayOf(0xFF4A1414.toInt(), 0xFFB5522B.toInt(), 0xFFF0C46A.toInt()),
        intArrayOf(0xFFE9D8BE.toInt(), 0xFFC8664A.toInt(), 0xFF2F3A4A.toInt()),
        intArrayOf(0xFF3D4527.toInt(), 0xFF8E9B4E.toInt(), 0xFFF4EBCF.toInt()),
        intArrayOf(0xFF1C2A5C.toInt(), 0xFFB79CE0.toInt(), 0xFFF3E8FA.toInt()),
        intArrayOf(0xFF04140F.toInt(), 0xFF0E6B57.toInt(), 0xFF7FCDB5.toInt()),
        intArrayOf(0xFF2A0E3A.toInt(), 0xFFB8577A.toInt(), 0xFFF5D08F.toInt()),
    )

    /** The look for [key]; any string, blank included, gives a valid one. */
    fun seedOf(key: String): CoverSeed {
        val h = hash(key)
        return CoverSeed(
            style = Math.floorMod(h, STYLES.toLong()).toInt(),
            palette = Math.floorMod(h ushr 21, palettes.size.toLong()).toInt(),
            mirrored = (h ushr 43) and 1L == 1L,
        )
    }

    /**
     * The key a song row draws its cover from: its album when it has one, else its base key, so a
     * row of Songs and the same file in a chat page show the same picture.
     */
    fun keyOf(albumKey: String, songKey: String): String =
        if (albumKey.isNotBlank()) ALBUM + albumKey else SONG + baseOfSongKey(songKey)

    /** The same key for one file, as the index would build it. */
    fun keyOf(track: Track): String {
        val album = indexAlbumKey(track)
        return if (album.isNotBlank()) ALBUM + album else SONG + indexBaseKey(track)
    }

    /** The key of an album's own cover, the same one its tracks get from [keyOf]. */
    fun keyOfAlbum(albumKey: String): String = ALBUM + albumKey

    /** The key of a playlist with no cover yet. */
    fun keyOfPlaylist(id: Long): String = "p:$id"

    private const val ALBUM = "a:"
    private const val SONG = "s:"

    /**
     * FNV-1a over the key's UTF-16 units, then a final mix so nearby keys ("Album 1", "Album 2")
     * land far apart. Fixed constants: never `hashCode`, never seeded.
     */
    internal fun hash(key: String): Long {
        var h = 0xcbf29ce484222325uL.toLong() // the FNV-1a 64-bit offset basis
        for (c in key) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        h = h xor (h ushr 33)
        h *= 0xff51afd7ed558ccduL.toLong() // the MurmurHash3 finaliser
        h = h xor (h ushr 33)
        return h
    }
}

/** One generated cover's look: a layout, a palette index into [CoverArt.palettes], and a left to right flip. */
data class CoverSeed(val style: Int, val palette: Int, val mirrored: Boolean = false)
