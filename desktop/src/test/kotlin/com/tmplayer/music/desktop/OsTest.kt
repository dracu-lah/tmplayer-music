package com.tmplayer.music.desktop

import com.tmplayer.music.desktop.os.Bounds
import com.tmplayer.music.desktop.os.MprisMapping
import com.tmplayer.music.desktop.os.NowPlaying
import com.tmplayer.music.desktop.os.OpenExternal
import com.tmplayer.music.desktop.os.SavedPlacement
import com.tmplayer.music.desktop.os.SavedWindow
import com.tmplayer.music.desktop.os.UserFiles
import com.tmplayer.music.desktop.os.WindowGeometry
import com.tmplayer.music.desktop.os.WindowMemoryStore
import com.tmplayer.music.desktop.ui.common.DragReorder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsTest {
    private val screen = Bounds(0, 0, 1920, 1040)

    @Test
    fun `the first run opens at the default size, fitted to a small screen`() {
        val big = WindowGeometry.restore(null, listOf(screen))
        assertEquals(WindowGeometry.DEFAULT_WIDTH, big.width)
        assertNull(big.x)
        val small = WindowGeometry.restore(null, listOf(Bounds(0, 0, 1000, 640)))
        assertEquals(1000, small.width)
        assertEquals(640, small.height)
        // With no screens at all (headless), the default stands.
        assertEquals(WindowGeometry.DEFAULT_WIDTH, WindowGeometry.restore(null, emptyList()).width)
    }

    @Test
    fun `a saved window comes back where it was, unless its title bar is on no screen`() {
        val saved = SavedWindow(300, 200, 1200, 800, SavedPlacement.Floating)
        val back = WindowGeometry.restore(saved, listOf(screen))
        assertEquals(300, back.x)
        assertEquals(200, back.y)
        // The monitor it was on is unplugged: centred, not off screen.
        val gone = WindowGeometry.restore(saved.copy(x = 4000), listOf(screen))
        assertNull(gone.x)
        assertNull(gone.y)
        assertEquals(1200, gone.width)
    }

    @Test
    fun `a size below the minimum is raised and one above the screen is cut`() {
        val tiny = WindowGeometry.restore(SavedWindow(null, null, 100, 100, SavedPlacement.Floating), listOf(screen))
        assertEquals(WindowGeometry.MIN_WIDTH, tiny.width)
        assertEquals(WindowGeometry.MIN_HEIGHT, tiny.height)
        val huge = WindowGeometry.restore(SavedWindow(0, 0, 9000, 9000, SavedPlacement.Maximized), listOf(screen))
        assertEquals(1920, huge.width)
        assertEquals(1040, huge.height)
        assertEquals(SavedPlacement.Maximized, huge.placement)
    }

    @Test
    fun `a window file that is garbage is no saved window`() {
        fun read(vararg pairs: Pair<String, String>) = WindowGeometry.fromProperties(Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } })
        assertNull(read())
        assertNull(read("width" to "wide", "height" to "800"))
        assertNull(read("width" to "-4", "height" to "800"))
        assertNull(read("width" to "99999999", "height" to "800"))
        val ok = assertNotNull(read("width" to "1000", "height" to "700", "x" to "10", "y" to "oops", "placement" to "Sideways"))
        // A position with a missing half is no position.
        assertNull(ok.x)
        assertNull(ok.y)
        assertEquals(SavedPlacement.Floating, ok.placement)
    }

    @Test
    fun `the window is written whole and read back`() {
        val file = File.createTempFile("window", ".properties")
        try {
            val store = WindowMemoryStore(file) { listOf(screen) }
            store.write(SavedWindow(50, 60, 1000, 700, SavedPlacement.Maximized))
            assertEquals(SavedWindow(50, 60, 1000, 700, SavedPlacement.Maximized), store.read())
            file.writeText("\u0000\u0001 not properties at all ￿")
            assertNull(store.read())
            file.delete()
            assertNull(store.read())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `dragging a row moves it by whole rows and stays inside the list`() {
        assertEquals(3, DragReorder.target(start = 1, dy = 120f, rowHeightPx = 56f, count = 10))
        assertEquals(0, DragReorder.target(start = 1, dy = -900f, rowHeightPx = 56f, count = 10))
        assertEquals(9, DragReorder.target(start = 8, dy = 900f, rowHeightPx = 56f, count = 10))
        // Less than half a row is no move.
        assertEquals(4, DragReorder.target(start = 4, dy = 20f, rowHeightPx = 56f, count = 10))
        // Nonsense stays where it is.
        assertEquals(2, DragReorder.target(start = 2, dy = Float.NaN, rowHeightPx = 56f, count = 10))
        assertEquals(2, DragReorder.target(start = 2, dy = 100f, rowHeightPx = 0f, count = 10))
        assertEquals(2, DragReorder.target(start = 2, dy = 100f, rowHeightPx = 56f, count = 0))
        val drag = DragReorder()
        drag.start(2)
        drag.drag(60f)
        assertEquals(2 to 3, drag.finish(56f, 10))
        assertEquals(-1, drag.from)
        drag.start(2)
        drag.drag(10f)
        assertNull(drag.finish(56f, 10))
    }

    @Test
    fun `mpris carries the artist, the album and the speed`() {
        val now = NowPlaying(1, "A song", 200_000, 5_000, true, null, true, true, 0, artist = "Some Artist", album = "An Album", rate = 1.5)
        val meta = MprisMapping.metadata(now)
        assertEquals(listOf("Some Artist"), meta["xesam:artist"])
        assertEquals("An Album", meta["xesam:album"])
        assertEquals(200_000_000L, meta["mpris:length"])
        assertEquals(1.5, MprisMapping.playerProperties(now)["Rate"])
        // Nothing playing: no artist, the no-track id, speed 1.
        assertFalse(MprisMapping.metadata(null).containsKey("xesam:artist"))
        assertEquals(1.0, MprisMapping.playerProperties(null)["Rate"])
        // The position carries on at the playing speed.
        assertEquals(5_000L + 3_000L, now.positionAt(2_000_000_000L))
    }

    @Test
    fun `the shell's opener is each systems own`() {
        assertEquals(listOf("xdg-open", "https://x"), OpenExternal.command("linux", "https://x"))
        assertEquals(listOf("open", "https://x"), OpenExternal.command("macos", "https://x"))
        assertEquals("rundll32", OpenExternal.command("windows", "https://x").first())
    }

    @Test
    fun `a picked file is read up to its cap and written whole`() = runBlocking {
        val file = File.createTempFile("picked", ".txt")
        try {
            UserFiles.writeText(file, "line one\nline two\n")
            assertEquals("line one\nline two\n", UserFiles.readCapped(file, 1024)!!.decodeToString())
            // Over the cap: nothing is returned, not a cut off file.
            assertNull(UserFiles.readCapped(file, 5))
            // The part file is gone after a write.
            assertFalse(File(file.parentFile, file.name + ".part").exists())
            assertFailsWith<java.io.FileNotFoundException> { UserFiles.readCapped(File(file.parentFile, "missing-" + file.name), 10) }
            // Writing into a folder that does not exist fails, leaving nothing behind.
            assertFailsWith<java.io.IOException> { UserFiles.writeText(File(file.parentFile, "no-such-folder/x.txt"), "x") }
        } finally {
            file.delete()
        }
        Unit
    }

    @Test
    fun `a playlist name becomes a safe file name`() {
        assertEquals("Road trip 2026", UserFiles.safeName("Road trip 2026"))
        assertEquals("a_b_c_d", UserFiles.safeName("a/b\\c:d"))
        assertEquals("playlist", UserFiles.safeName("   "))
        assertTrue(UserFiles.safeName("x".repeat(500)).length <= 60)
    }

    @Test
    fun `a name Windows keeps for a device is never a file name`() {
        for (reserved in listOf("CON", "con", "Prn", "AUX", "NUL", "COM1", "com9", "LPT1", "lpt9", "CON ", "NUL.", "COM¹")) {
            val safe = UserFiles.safeName(reserved)
            assertFalse(UserFiles.isReservedOnWindows(safe), "$reserved gave $safe")
            assertFalse(UserFiles.isReservedOnWindows("$safe.m3u8"), "$reserved gave $safe")
        }
        assertTrue(UserFiles.isReservedOnWindows("nul.txt"))
        assertTrue(UserFiles.isReservedOnWindows("COM3"))
        assertFalse(UserFiles.isReservedOnWindows("COM10"))
        assertFalse(UserFiles.isReservedOnWindows("Console"))
        assertEquals("Console", UserFiles.safeName("Console"))
        assertEquals("COM10", UserFiles.safeName("COM10"))
    }

    @Test
    fun `a file name has no trailing dot or space and stays short`() {
        assertEquals("mix", UserFiles.safeName("mix. . "))
        assertEquals("playlist", UserFiles.safeName("..."))
        val long = UserFiles.safeName("a".repeat(59) + " " + "b".repeat(40))
        assertTrue(long.length <= 60)
        assertFalse(long.endsWith(" "))
    }
}
