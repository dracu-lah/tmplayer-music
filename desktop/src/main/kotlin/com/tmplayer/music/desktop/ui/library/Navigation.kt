package com.tmplayer.music.desktop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.music.library.Album
import com.tmplayer.music.library.Artist
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import kotlin.random.Random

/** Where the content area is. The first five are the sidebar's; an album and an artist open from a list. */
sealed interface Route {
    data object Songs : Route
    data object Albums : Route
    data object Artists : Route
    data object Search : Route
    data class Chat(val chatId: Long, val title: String) : Route
    data class AlbumPage(val album: Album) : Route
    data class ArtistPage(val artist: Artist) : Route

    /** Whether the sidebar shows this route as chosen (a detail page counts for its list). */
    val tab: Route
        get() = when (this) {
            is AlbumPage -> Albums
            is ArtistPage -> Artists
            else -> this
        }
}

/**
 * The content area's history: choosing something in the sidebar starts over, opening an album or an
 * artist from a list goes one deeper, and Back (Escape) comes out. A plain object over state so the
 * rules run in a unit test.
 */
class Navigator(start: Route = Route.Songs) {
    var stack: List<Route> by mutableStateOf(listOf(start))
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** A sidebar choice: the history is replaced. The same place again changes nothing. */
    fun open(route: Route) {
        if (stack.size == 1 && stack.last() == route) return
        stack = listOf(route)
    }

    /** A detail page from a list: one deeper, and never the same page twice in a row. */
    fun push(route: Route) {
        if (stack.last() == route) return
        stack = (stack + route).takeLast(MAX_DEPTH)
    }

    /** One step back; false at the first page, so Escape can mean something else. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    private companion object {
        const val MAX_DEPTH = 50
    }
}

/** What a tap, a double click and a menu on a song do, from any list. */
class SongActions(
    val play: (List<Song>, Int) -> Unit,
    val playNext: (List<Song>) -> Unit,
    val addToQueue: (List<Song>) -> Unit,
    val shuffle: (List<Song>) -> Unit,
    val versions: (Song) -> Unit,
    val openAlbum: (Song) -> Unit,
    val openArtist: (Song) -> Unit,
)

/** What the same does for the files of a chat page, which are single files rather than songs. */
class TrackActions(
    val play: (List<Track>, Int) -> Unit,
    val shuffle: (List<Track>) -> Unit,
)

/** Which row shows the playing mark: the song or the file that is current, and whether it plays. */
data class PlayingMark(val songKey: String, val track: TrackId, val playing: Boolean) {
    /** true playing, false paused, null when [songKey] (default file [defaultTrack]) is not what plays. */
    fun forSong(songKey: String, defaultTrack: TrackId?): Boolean? {
        val hit = if (this.songKey.isNotEmpty()) this.songKey == songKey else defaultTrack == track
        return if (hit) playing else null
    }

    /** true playing, false paused, null when the file [id] is not the one that plays. */
    fun forTrack(id: TrackId): Boolean? = if (track == id) playing else null
}

/**
 * Shuffle all for a library bigger than a queue holds: instead of the first rows in some order,
 * windows of rows read from random places, so every part of the library has a chance.
 */
object ShuffleAll {
    /** The most songs one queue takes: [com.tmplayer.music.player.SavedQueue.MAX_ITEMS]. */
    const val QUEUE = 2_000

    /** Rows read per window. */
    const val WINDOW = 200

    /** Window start offsets for a library of [total] songs: one read of everything when it fits, else spread windows. */
    fun offsets(total: Int, random: Random = Random.Default): List<Int> {
        if (total <= 0) return emptyList()
        if (total <= QUEUE) return listOf(0)
        val windows = QUEUE / WINDOW
        val slots = (0 until total step WINDOW).toList()
        val picked = slots.shuffled(random).take(windows).sorted()
        return picked
    }

    /** How many rows each read asks for with [offsets]: everything that fits, else one window. */
    fun limit(total: Int): Int = if (total <= QUEUE) QUEUE else WINDOW
}
