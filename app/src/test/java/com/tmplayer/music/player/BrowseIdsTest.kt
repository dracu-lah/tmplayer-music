package com.tmplayer.music.player

import com.tmplayer.music.auto.BrowseIds
import com.tmplayer.music.library.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The browse tree's song ids are the queue's own ids, and its folder ids are never taken for songs. */
class BrowseIdsTest {
    @Test
    fun aBrowseSongIdIsTheQueueItemId() {
        val id = TrackId(-1001234L, 987L)
        assertEquals(MediaIds.of(id, "korvane|velo marin"), BrowseIds.song(id, "korvane|velo marin"))
        assertEquals(SavedQueue.Item(-1001234L, 987L, "korvane|velo marin"), MediaIds.item(BrowseIds.song(id, "korvane|velo marin")))
    }

    @Test
    fun aFolderOrInfoIdIsNeverASong() {
        for (id in listOf(BrowseIds.ROOT, BrowseIds.RECENT, BrowseIds.INFO, BrowseIds.album("a:b:c"), BrowseIds.playlist(3), BrowseIds.chat(-100))) {
            assertNull(id, MediaIds.item(id))
        }
    }
}
