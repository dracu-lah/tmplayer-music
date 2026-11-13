package com.tmplayer.music.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetModelTest {
    @Test
    fun twoByOneIsCompactAndFourByOneIsWide() {
        assertEquals(WidgetLayout.Compact, WidgetLayout.forWidthDp(0))
        assertEquals(WidgetLayout.Compact, WidgetLayout.forWidthDp(-5))
        assertEquals(WidgetLayout.Compact, WidgetLayout.forWidthDp(180))
        assertEquals(WidgetLayout.Wide, WidgetLayout.forWidthDp(250))
        assertEquals(WidgetLayout.Wide, WidgetLayout.forWidthDp(Int.MAX_VALUE))
    }

    @Test
    fun noItemIsTheEmptyState() {
        assertEquals(WidgetState.EMPTY, WidgetState.of(null, "t", "a", true))
        assertEquals(WidgetState.EMPTY, WidgetState.of("", "t", "a", true))
        assertFalse(WidgetState.EMPTY.hasItem)
    }

    @Test
    fun anItemKeepsItsLinesTrimmedAndBounded() {
        val state = WidgetState.of("1:2:k", "  Title ", null, true)
        assertTrue(state.hasItem)
        assertEquals("Title", state.title)
        assertEquals("", state.artist)
        assertTrue(state.playing)
        assertEquals(WidgetState.MAX_TEXT, WidgetState.of("1:2:k", "x".repeat(10_000), "", false).title.length)
    }
}
