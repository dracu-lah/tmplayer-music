package com.tmplayer.music.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebouncedVolumeTest {
    private fun prefsFile(): File = File.createTempFile("prefs", ".properties").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `a burst of changes writes nothing until the flush, then only the last`() {
        val file = prefsFile()
        val prefs = DesktopPrefs(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saver = DebouncedVolume(prefs, scope, delayMs = 60_000)
        for (v in 10..90 step 10) saver.set(v, false)
        assertFalse(file.exists(), "nothing is written while the changes keep coming")
        saver.flush()
        assertEquals(90, prefs.getLong(DesktopApp.VOLUME, -1).toInt())
        assertEquals(false, prefs.getBoolean(DesktopApp.MUTED, true))
        // A second flush has nothing left to write.
        file.delete()
        saver.flush()
        assertFalse(file.exists())
    }

    @Test
    fun `the last change is written by itself after the delay`() {
        val prefs = DesktopPrefs(prefsFile())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saver = DebouncedVolume(prefs, scope, delayMs = 50)
        saver.set(42, true)
        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.getLong(DesktopApp.VOLUME, -1) != 42L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(42, prefs.getLong(DesktopApp.VOLUME, -1).toInt())
        assertEquals(true, prefs.getBoolean(DesktopApp.MUTED, false))
    }
}
