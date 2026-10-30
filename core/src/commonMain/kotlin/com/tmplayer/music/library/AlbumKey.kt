package com.tmplayer.music.library

/**
 * The keys that group tracks into albums and artists. An album is its album artist (or the track's
 * primary artist when there is none) plus its title plus its year, so two albums that share a name stay
 * apart. A compilation credited to Various Artists keys on title and year alone, so its tracks by many
 * artists stay one album.
 */
object AlbumKey {

    /** Album artists that mean "a compilation", compared folded (`V.A.` folds to `v a`). */
    private val VARIOUS = setOf("various artists", "various", "va", "v a", "various artist")

    /** Stands in for the artist part of a compilation's key; folded names never contain it. */
    private const val COMPILATION = "\u0002"

    /** The album's key, or blank when the track has no album. */
    fun of(albumArtist: String, artist: String, album: String, year: Int): String {
        val albumPart = TextFold.fold(album)
        if (albumPart.isEmpty()) return ""
        val credited = artistKey(albumArtist)
        val artistPart = when {
            credited.isNotEmpty() -> if (credited in VARIOUS) COMPILATION else credited
            artistKey(artist) in VARIOUS -> COMPILATION
            else -> GroupKey.primaryArtist(artist)
        }
        val yearPart = if (year in 1..9999) year else 0
        return "$artistPart${GroupKey.SEPARATOR}$albumPart${GroupKey.SEPARATOR}$yearPart"
    }

    /**
     * A whole name folded (case, accents, punctuation), not split, as album keys compare album artists.
     * Blank for no artist or a placeholder like `Unknown`. The Artists list splits credits instead, see
     * [ArtistCredits].
     */
    fun artistKey(name: String): String {
        val folded = TextFold.fold(name)
        return if (folded in GroupKey.UNKNOWN_ARTISTS) "" else folded
    }
}
