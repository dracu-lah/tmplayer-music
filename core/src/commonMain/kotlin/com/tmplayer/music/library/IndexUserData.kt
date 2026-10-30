package com.tmplayer.music.library

import java.util.Locale

/*
 * The shapes and small rules behind what the user keeps in the index (favourites, plays,
 * playlists, recent searches) and behind search. They are plain functions so they can be tested
 * without a database.
 */

/**
 * One playlist as the Playlists tab lists it. [songCount] counts only the entries that still
 * resolve to a song, so a playlist whose chat was removed does not promise rows it cannot show.
 * The cover is the art of the first entry that resolves.
 */
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val thumbFileId: Int,
    val coverKey: String,
    val updatedSec: Long,
)

/** What one search found, each list ranked with matches at the start of the name first. */
data class SearchResults(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
) {
    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** Longest query search accepts; anything longer is a paste accident, not a search. */
internal const val MAX_QUERY_CHARS = 200

/** Words of one query that must all match; more are ignored so the SQL stays a few known shapes. */
internal const val MAX_QUERY_WORDS = 6

/** The longest playlist name kept; a longer one is cut. */
internal const val MAX_PLAYLIST_NAME = 200

/** The name a playlist gets when the user leaves it blank. */
internal const val DEFAULT_PLAYLIST_NAME = "Playlist"

/**
 * Separates the fields of a search column. It is a space either side of a control character, so a
 * word start after it still counts as a word start, while a query (which never holds a control
 * character) cannot match across two fields as if they were one phrase.
 */
internal const val SEARCH_FIELD_GAP = " \u0001 "

private val WHITESPACE = Regex("\\s+")
private val CONTROL = Regex("\\p{Cc}")

/**
 * The words of a query, folded the way [sortText] folds names so "EMILE" finds "Émile". Control
 * characters are dropped. Empty for a blank query or one over [MAX_QUERY_CHARS].
 */
internal fun searchWords(query: String): List<String> {
    if (query.length > MAX_QUERY_CHARS) return emptyList()
    val folded = sortText(CONTROL.replace(query, " "))
    if (folded.isEmpty()) return emptyList()
    return folded.split(WHITESPACE).filter { it.isNotEmpty() }.distinct().take(MAX_QUERY_WORDS)
}

/** The folded text a song, album or artist is searched by: each field folded, joined by [SEARCH_FIELD_GAP]. */
internal fun searchText(vararg fields: String): String =
    fields.map { WHITESPACE.replace(sortText(CONTROL.replace(it, " ")), " ") }.joinToString(SEARCH_FIELD_GAP)

/** [text] with LIKE's wildcards and the escape character itself escaped by a backslash. */
internal fun likeEscape(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * The base key part of a song key (base, separator, "d", cluster). The cluster marker is the last
 * one, so a title that starts with "d" does not confuse it. A key without one is its own base.
 */
internal fun baseOfSongKey(songKey: String): String {
    val marker = "${KEY_SEPARATOR}d"
    val at = songKey.lastIndexOf(marker)
    return if (at < 0) songKey else songKey.substring(0, at)
}

/** A playlist name as stored: trimmed, blank made [DEFAULT_PLAYLIST_NAME], cut to [MAX_PLAYLIST_NAME] without splitting a character. */
internal fun playlistName(name: String): String {
    val trimmed = CONTROL.replace(name, " ").trim()
    if (trimmed.isEmpty()) return DEFAULT_PLAYLIST_NAME
    if (trimmed.length <= MAX_PLAYLIST_NAME) return trimmed
    var end = MAX_PLAYLIST_NAME
    if (trimmed[end - 1].isHighSurrogate()) end--
    return trimmed.substring(0, end).trim().ifEmpty { DEFAULT_PLAYLIST_NAME }
}

/** The key two recent searches are the same by: trimmed, whitespace collapsed, lowercased. */
internal fun recentSearchKey(query: String): String =
    WHITESPACE.replace(query.trim(), " ").lowercase(Locale.ROOT)
