package com.tmplayer.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private val scheme = MusicColors

    private val steps = listOf(
        scheme.background, scheme.surfaceContainerLow, scheme.surfaceContainer,
        scheme.surfaceContainerHigh, scheme.surfaceContainerHighest,
    )

    @Test
    fun pageAndSurfacesArePureBlack() {
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.surfaceContainerLowest)
        assertEquals(
            listOf(Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF121212), Color(0xFF1A1A1A), Color(0xFF222222)),
            steps,
        )
    }

    @Test
    fun containersStepAwayFromThePageInOrder() {
        val lum = steps.map { it.luminance() }
        lum.zipWithNext().forEach { (a, b) -> assertTrue("$lum", b > a) }
    }

    @Test
    fun textStaysReadableOnEveryStep() {
        (steps + scheme.surfaceVariant + scheme.surfaceBright).forEach {
            assertTrue(contrast(scheme.onSurface, it) >= 4.5f)
            assertTrue(contrast(scheme.onSurfaceVariant, it) >= 4.5f)
        }
        assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
        assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f)
        assertTrue(contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5f)
        assertTrue(contrast(scheme.inverseOnSurface, scheme.inverseSurface) >= 4.5f)
        assertTrue(contrast(scheme.onErrorContainer, scheme.errorContainer) >= 4.5f)
        assertTrue("controls on the page", contrast(scheme.primary, scheme.background) >= 3f)
    }
}
