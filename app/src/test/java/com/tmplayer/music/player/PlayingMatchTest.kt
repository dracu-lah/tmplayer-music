package com.tmplayer.music.player

import com.tmplayer.music.library.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayingMatchTest {
    private val file = TrackId(-100L, 42L)
    private val otherVersion = TrackId(-100L, 43L)
    private val sameMessageOtherChat = TrackId(-200L, 42L)

    @Test
    fun `played from a song list marks that song and that file on the chat page`() {
        val mark = PlayingMark(songKey = "song-a", track = file, playing = true)
        assertEquals(true, PlayingMatch.song(mark, "song-a", file))
        assertNull(PlayingMatch.song(mark, "song-b", otherVersion))
        assertEquals(true, PlayingMatch.track(mark, file))
        // Another version of the same song in the chat is a different message: not marked.
        assertNull(PlayingMatch.track(mark, otherVersion))
        assertNull(PlayingMatch.track(mark, sameMessageOtherChat))
    }

    @Test
    fun `played from a chat page marks that row and the song whose default file it is`() {
        val mark = PlayingMark(songKey = "", track = file, playing = false)
        assertEquals(false, PlayingMatch.track(mark, file))
        assertEquals(false, PlayingMatch.song(mark, "song-a", file))
        // A song whose default is another version is not what plays.
        assertNull(PlayingMatch.song(mark, "song-a", otherVersion))
        // An empty song key never matches by itself.
        assertNull(PlayingMatch.song(mark, "", otherVersion))
        assertNull(PlayingMatch.song(mark, "", null))
    }

    @Test
    fun `nothing playing marks nothing`() {
        assertNull(PlayingMatch.song(null, "song-a", file))
        assertNull(PlayingMatch.track(null, file))
    }
}
