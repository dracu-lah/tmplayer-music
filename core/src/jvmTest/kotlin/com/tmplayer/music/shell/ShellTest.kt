package com.tmplayer.music.shell

import com.tmplayer.music.data.MemoryKeyValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {

    @Test
    fun chipSurvivesARestart() {
        val store = MemoryKeyValues()
        assertEquals(LibraryShelf.Songs, ShellChoices(store).shelf, "Songs until something else is picked")
        ShellChoices(store).shelf = LibraryShelf.Favourites
        assertEquals(LibraryShelf.Favourites, ShellChoices(store).shelf)
        ShellChoices(store).shelf = LibraryShelf.Albums
        assertEquals(LibraryShelf.Albums, ShellChoices(store).shelf)
    }

    @Test
    fun unknownChipFallsBackToSongs() {
        val store = MemoryKeyValues()
        store.putString(ShellChoices.SHELF, "Downloads")
        assertEquals(LibraryShelf.Songs, ShellChoices(store).shelf)
        store.putString(ShellChoices.SHELF, "")
        assertEquals(LibraryShelf.Songs, ShellChoices(store).shelf)
        assertEquals(LibraryShelf.Songs, ShellChoices.shelfOf(null))
        assertEquals(LibraryShelf.Playlists, ShellChoices.shelfOf("Playlists"))
    }

    @Test
    fun gridChoicesSurvive() {
        val store = MemoryKeyValues()
        val first = ShellChoices(store)
        assertTrue(first.albumsGrid, "albums are covers first")
        assertFalse(first.artistsGrid)
        first.albumsGrid = false
        first.artistsGrid = true
        val again = ShellChoices(store)
        assertFalse(again.albumsGrid)
        assertTrue(again.artistsGrid)
    }

    @Test
    fun headerHidesAndReturnsWithinItsHeight() {
        val h = 200f
        assertEquals(-50f, HeaderOffset.next(0f, -50f, h))
        assertEquals(-200f, HeaderOffset.next(-180f, -50f, h), "never more than its height")
        assertEquals(0f, HeaderOffset.next(-20f, 80f, h), "never further down than shown")
        assertEquals(-50f, HeaderOffset.consumed(0f, -50f, h))
        assertEquals(-20f, HeaderOffset.consumed(-180f, -50f, h), "only what it could still move")
        assertEquals(0f, HeaderOffset.consumed(-200f, -50f, h), "hidden: the list takes it all")
        assertEquals(0f, HeaderOffset.consumed(0f, 30f, h), "shown: the list takes it all")
    }

    @Test
    fun headerSurvivesNonsense() {
        assertEquals(-10f, HeaderOffset.next(Float.NaN, -10f, 100f), "a broken offset counts as shown")
        assertEquals(0f, HeaderOffset.next(-10f, -10f, 0f))
        assertEquals(-10f, HeaderOffset.next(-10f, Float.NaN, 100f))
        assertEquals(0f, HeaderOffset.consumed(0f, -10f, -5f))
        assertNull(HeaderOffset.settleTarget(Float.NaN, 100f))
        assertNull(HeaderOffset.settleTarget(-10f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun headerSettlesOnlyWhenLeftPartWay() {
        assertNull(HeaderOffset.settleTarget(0f, 100f), "shown: idle")
        assertNull(HeaderOffset.settleTarget(-100f, 100f), "hidden: idle")
        assertEquals(0f, HeaderOffset.settleTarget(-30f, 100f))
        assertEquals(-100f, HeaderOffset.settleTarget(-70f, 100f))
    }

    @Test
    fun headerResizeKeepsTheHiddenShare() {
        assertEquals(-150f, HeaderOffset.resize(-100f, 100f, 150f))
        assertEquals(0f, HeaderOffset.resize(0f, 100f, 150f))
        assertEquals(0f, HeaderOffset.resize(-50f, 0f, 150f))
        assertEquals(0f, HeaderOffset.resize(-50f, 100f, 0f))
    }

    @Test
    fun barFollowsTheSheet() {
        assertEquals(0f, NavMotion.barOffset(0f, 80f))
        assertEquals(40f, NavMotion.barOffset(0.5f, 80f))
        assertEquals(80f, NavMotion.barOffset(1f, 80f))
        assertEquals(80f, NavMotion.barOffset(3f, 80f), "clamped")
        assertEquals(0f, NavMotion.barOffset(-1f, 80f))
        assertEquals(0f, NavMotion.barOffset(Float.NaN, 80f))
        assertEquals(0f, NavMotion.barOffset(0.5f, 0f), "no bar measured yet")
        assertEquals(-40f, NavMotion.railOffset(0.5f, 80f))
        assertEquals(0f, NavMotion.railOffset(0.5f, Float.NaN))
        assertTrue(NavMotion.barGone(1f))
        assertFalse(NavMotion.barGone(0.9f))
        assertFalse(NavMotion.barGone(Float.NaN))
    }

    @Test
    fun railOnlyInWideLandscapeWindows() {
        assertFalse(NavLayout.useRail(411f, 891f), "phone upright")
        assertTrue(NavLayout.useRail(891f, 411f), "phone sideways")
        assertFalse(NavLayout.useRail(800f, 1280f), "tablet upright keeps the bar")
        assertTrue(NavLayout.useRail(1280f, 800f), "tablet sideways")
        assertFalse(NavLayout.useRail(560f, 400f), "small split screen window")
        assertFalse(NavLayout.useRail(700f, 700f), "square")
        assertFalse(NavLayout.useRail(Float.NaN, 400f))
        assertFalse(NavLayout.useRail(0f, 0f))
    }

    @Test
    fun gridColumnsUseTheWidth() {
        assertEquals(2, NavLayout.gridColumns(320f), "narrowest phone")
        assertEquals(2, NavLayout.gridColumns(411f))
        assertEquals(5, NavLayout.gridColumns(811f), "phone sideways, beside the rail")
        assertEquals(8, NavLayout.gridColumns(3000f))
        assertEquals(2, NavLayout.gridColumns(Float.NaN))
        assertEquals(2, NavLayout.gridColumns(-1f))
    }

    @Test
    fun miniPlayerGrowsWithLargeText() {
        assertEquals(64f, NavLayout.miniHeight(1f))
        assertEquals(72f, NavLayout.miniHeight(1.3f))
        assertEquals(84f, NavLayout.miniHeight(2f))
        assertEquals(64f, NavLayout.miniHeight(Float.NaN))
    }
}
