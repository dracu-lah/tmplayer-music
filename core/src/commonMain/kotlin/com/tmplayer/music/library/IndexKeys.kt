package com.tmplayer.music.library

import java.text.Normalizer
import java.util.Locale

/*
 * The small rules the library index applies when it folds files into songs, albums and artists.
 * They live apart from the store so the real rules (GroupKey, AlbumKey, TrackVersions.pick) can
 * replace them later without touching a query: each one is a plain function of a track.
 */

/** Separates the parts of every key the index builds; it never appears in a tag. */
internal const val KEY_SEPARATOR = '\u0001'

/**
 * Sorts after every real name, so a track with no artist or no album sits at the end of the list
 * instead of filling the top of it. It is U+10FFFF, the highest code point, so it also sorts after
 * emoji and other characters outside the basic plane.
 */
internal const val SORT_LAST = "􏿿"

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * The text a name sorts by: trimmed, lowercased and with accents taken off, so "Émile" sorts
 * beside "emile" rather than after "z". Non-Latin text keeps its own letters. This is a plain code
 * point order, not a locale's collation; that is a later refinement for the A to Z bar.
 */
internal fun sortText(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val plain = COMBINING_MARKS.replace(Normalizer.normalize(trimmed, Normalizer.Form.NFD), "")
    return plain.lowercase(Locale.ROOT)
}

/** The sort key of one name, with a blank name moved to the end. */
internal fun sortOrLast(text: String): String = sortText(text).ifEmpty { SORT_LAST }

/** Songs by title: the title alone, ties broken by the song key in the query. */
internal fun titleOrder(title: String): String = sortOrLast(title)

/** Songs by artist: the artist, then the title within one artist. */
internal fun artistOrder(artist: String, title: String): String =
    sortOrLast(artist) + KEY_SEPARATOR + sortOrLast(title)

/** Songs by album: the album, then the title within one album. */
internal fun albumOrder(album: String, title: String): String =
    sortOrLast(album) + KEY_SEPARATOR + sortOrLast(title)

/**
 * The album a track belongs to: [AlbumKey], album artist (or the primary artist) plus album plus
 * year, with compilations kept together. A track with no album is in no album.
 */
internal fun indexAlbumKey(track: Track): String =
    AlbumKey.of(track.albumArtist, track.artist, track.album, track.year)

/** The artist a track is listed under, by its primary artist; blank when the file names nobody. */
internal fun indexArtistKey(track: Track): String = AlbumKey.artistKey(track.artist)

/**
 * The base key a track folds by. A scanner that has not computed one yet still gets a row of its
 * own, keyed by its message, so nothing disappears and nothing folds by accident.
 */
internal fun indexBaseKey(track: Track): String =
    track.groupKey.ifBlank { "$KEY_SEPARATOR" + "m${track.chatId}:${track.messageId}" }

/**
 * Which version of a song plays by default when nobody chose one: lossless before lossy, then the
 * larger file (more bits for the same recording), then the older post, which is the one most
 * likely to still be there. Returns null only for an empty list.
 */
fun defaultPick(tracks: List<Track>): Track? = tracks.minWithOrNull(
    compareByDescending<Track> { it.format.lossless }
        .thenByDescending { it.sizeBytes }
        .thenBy { it.dateSec }
        .thenBy { it.chatId }
        .thenBy { it.messageId },
)

/**
 * Splits the files of one base key into recordings by length: files more than max(3 s, 2 percent)
 * apart are different edits, so they become separate songs. When the artist is unknown the title
 * alone is weak evidence, so the window narrows to 1 s (`plan/RESEARCH.md` 4.2). An unknown
 * duration (0) proves nothing either way and joins cluster 0. Clusters are numbered from the
 * shortest, and each is measured from its own shortest file so a long chain cannot drift.
 */
fun defaultClusters(durations: List<Long>, artistKnown: Boolean): List<Int> {
    val out = IntArray(durations.size)
    val known = durations.indices.filter { durations[it] > 0 }.sortedBy { durations[it] }
    var cluster = -1
    var start = 0L
    for (i in known) {
        val d = durations[i]
        val window = if (artistKnown) maxOf(3_000L, start / 50) else 1_000L
        if (cluster < 0 || d - start > window) {
            cluster++
            start = d
        }
        out[i] = cluster
    }
    return out.toList()
}

/** Orders formats for the badge: lossless first, then the known lossy ones, unknown last. */
internal fun formatRank(format: AudioFormat): Int = when {
    format.lossless -> format.ordinal
    format == AudioFormat.OTHER -> 1_000
    else -> 100 + format.ordinal
}
