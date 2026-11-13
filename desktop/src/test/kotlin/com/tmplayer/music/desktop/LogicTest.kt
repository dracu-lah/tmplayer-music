package com.tmplayer.music.desktop

import androidx.compose.ui.input.key.Key
import com.tmplayer.music.desktop.player.ArrayBytes
import com.tmplayer.music.desktop.player.MpvErrors
import com.tmplayer.music.desktop.player.StreamBytes
import com.tmplayer.music.desktop.player.StreamInput
import com.tmplayer.music.desktop.ui.common.BackKeys
import com.tmplayer.music.desktop.ui.common.ImageCheck
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.common.Shortcut
import com.tmplayer.music.desktop.ui.common.Shortcuts
import com.tmplayer.music.desktop.ui.library.DoubleClicks
import com.tmplayer.music.desktop.ui.library.Navigator
import com.tmplayer.music.desktop.ui.library.PlayingMark
import com.tmplayer.music.desktop.ui.library.Route
import com.tmplayer.music.desktop.ui.library.ShuffleAll
import com.tmplayer.music.desktop.ui.library.sampleLetters
import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefsTest {
    private fun file(): File = File.createTempFile("prefs", ".properties").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `values survive a restart`() {
        val f = file()
        DesktopPrefs(f).apply {
            putBoolean("flag", true)
            putLong("n", 42)
            putString("s", "héllo wörld = with : odd chars")
            pickerDone = true
        }
        val again = DesktopPrefs(f)
        assertTrue(again.getBoolean("flag", false))
        assertEquals(42, again.getLong("n", 0))
        assertEquals("héllo wörld = with : odd chars", again.getString("s"))
        assertTrue(again.pickerDone)
    }

    @Test
    fun `a missing key gives the default and a value of the wrong type reads as the default`() {
        val prefs = DesktopPrefs(file())
        assertEquals(7, prefs.getLong("none", 7))
        assertFalse(prefs.getBoolean("none", false))
        assertNull(prefs.getString("none"))
        prefs.putString("n", "not a number")
        assertEquals(9, prefs.getLong("n", 9))
        assertTrue(prefs.getBoolean("n", true))
    }

    @Test
    fun `a cut short, garbage or huge file is empty settings and not a crash`() {
        val f = file()
        f.writeBytes(byteArrayOf(0, 1, 2, -1, -2, 0x5c, 0x75, 0x30))
        assertEquals(1, DesktopPrefs(f).getLong("x", 1))
        f.writeText("a=" + "x".repeat(5_000_000))
        assertNull(DesktopPrefs(f).getString("a"))
        f.delete()
        assertNull(DesktopPrefs(f).getString("a"))
    }

    @Test
    fun `an unwritable place keeps the value for this run`() {
        val blocked = File.createTempFile("blocker", "x").also { it.deleteOnExit() }
        // The parent is a file, so the folder cannot be made and the write fails.
        val prefs = DesktopPrefs(File(blocked, "inner/prefs.properties"))
        prefs.putLong("n", 5)
        assertEquals(5, prefs.getLong("n", 0))
    }

    @Test
    fun `absurd keys and values are ignored`() {
        val prefs = DesktopPrefs(file())
        prefs.putString("", "x")
        prefs.putString("k".repeat(500), "x")
        prefs.putString("big", "v".repeat(200_000))
        assertNull(prefs.getString(""))
        assertNull(prefs.getString("big"))
    }

    @Test
    fun `remove forgets a key`() {
        val prefs = DesktopPrefs(file())
        prefs.putLong("n", 1)
        prefs.remove("n")
        assertEquals(0, prefs.getLong("n", 0))
    }
}

class SelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun `a click selects one row and a second click moves it`() {
        val s = ListSelection<String>().click("b", order).click("d", order)
        assertEquals(setOf("d"), s.selected)
        assertEquals("d", s.anchor)
    }

    @Test
    fun `ctrl adds and removes rows`() {
        var s = ListSelection<String>().click("a", order)
        s = s.click("c", order, ctrl = true)
        assertEquals(setOf("a", "c"), s.selected)
        s = s.click("a", order, ctrl = true)
        assertEquals(setOf("c"), s.selected)
    }

    @Test
    fun `shift takes the range from the anchor, whichever way`() {
        val s = ListSelection<String>().click("b", order)
        assertEquals(setOf("b", "c", "d"), s.click("d", order, shift = true).selected)
        assertEquals(setOf("a", "b"), s.click("a", order, shift = true).selected)
        // The anchor stays, so the range can be changed.
        assertEquals(setOf("b", "c"), s.click("d", order, shift = true).click("c", order, shift = true).selected)
    }

    @Test
    fun `ctrl and shift add a range to what is selected`() {
        val s = ListSelection<String>().click("a", order).click("c", order, ctrl = true).click("e", order, ctrl = true, shift = true)
        assertEquals(setOf("a", "c", "d", "e"), s.selected)
    }

    @Test
    fun `shift with no anchor, or an anchor that is gone, selects the clicked row`() {
        assertEquals(setOf("c"), ListSelection<String>().click("c", order, shift = true).selected)
        val gone = ListSelection(setOf("x"), "x").click("c", order, shift = true)
        assertEquals(setOf("c"), gone.selected)
    }

    @Test
    fun `select all takes every row and an empty list changes nothing`() {
        assertEquals(order.toSet(), ListSelection<String>().selectAll(order).selected)
        val none = ListSelection<String>()
        assertTrue(none.selectAll(emptyList()) === none)
    }

    @Test
    fun `rows that disappear leave the selection and the order is the list's`() {
        val s = ListSelection<String>().selectAll(order).prune(listOf("b", "d", "z"))
        assertEquals(setOf("b", "d"), s.selected)
        assertEquals(listOf("b", "d"), s.inOrder(listOf("d", "b", "q")).sortedBy { it })
        assertEquals(listOf("d", "b"), s.inOrder(listOf("d", "x", "b")))
        assertTrue(s.clear().isEmpty)
    }
}

class ShortcutsTest {
    private fun of(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, typing: Boolean = false) =
        Shortcuts.of(key, ctrl, shift, alt, typing)

    @Test
    fun `space plays and pauses, the arrows seek`() {
        assertEquals(Shortcut.PlayPause, of(Key.Spacebar))
        assertEquals(Shortcut.SeekBack, of(Key.DirectionLeft))
        assertEquals(Shortcut.SeekForward, of(Key.DirectionRight))
    }

    @Test
    fun `ctrl with the arrows skips and changes the volume`() {
        assertEquals(Shortcut.Previous, of(Key.DirectionLeft, ctrl = true))
        assertEquals(Shortcut.Next, of(Key.DirectionRight, ctrl = true))
        assertEquals(Shortcut.VolumeUp, of(Key.DirectionUp, ctrl = true))
        assertEquals(Shortcut.VolumeDown, of(Key.DirectionDown, ctrl = true))
    }

    @Test
    fun `ctrl f searches and ctrl a selects`() {
        assertEquals(Shortcut.Search, of(Key.F, ctrl = true))
        assertEquals(Shortcut.SelectAll, of(Key.A, ctrl = true))
    }

    @Test
    fun `typing in a field keeps space, the arrows and ctrl a, but not ctrl f`() {
        assertNull(of(Key.Spacebar, typing = true))
        assertNull(of(Key.DirectionLeft, typing = true))
        assertNull(of(Key.DirectionRight, ctrl = true, typing = true))
        assertNull(of(Key.A, ctrl = true, typing = true))
        assertEquals(Shortcut.Search, of(Key.F, ctrl = true, typing = true))
    }

    @Test
    fun `media keys work even while typing`() {
        assertEquals(Shortcut.PlayPause, of(Key.MediaPlayPause, typing = true))
        assertEquals(Shortcut.Next, of(Key.MediaNext))
        assertEquals(Shortcut.Previous, of(Key.MediaPrevious))
    }

    @Test
    fun `other keys and other combinations are not shortcuts`() {
        assertNull(of(Key.Enter))
        assertNull(of(Key.X))
        assertNull(of(Key.Spacebar, ctrl = true))
        assertNull(of(Key.Spacebar, shift = true))
        assertNull(of(Key.DirectionLeft, alt = true))
        assertNull(of(Key.F, ctrl = true, shift = true))
    }
}

class NavigatorTest {
    private val album = com.tmplayer.music.library.Album("k", "Album", "Artist", 2020, 3, 0, "")
    private val artist = com.tmplayer.music.library.Artist("a", "Artist", 3, 1)

    @Test
    fun `a sidebar choice starts over and a detail goes one deeper`() {
        val nav = Navigator()
        nav.open(Route.Albums)
        nav.push(Route.AlbumPage(album))
        assertEquals(2, nav.stack.size)
        assertEquals(Route.Albums, nav.current.tab)
        nav.open(Route.Artists)
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun `back comes out of details one at a time and stops at the first page`() {
        val nav = Navigator(Route.Songs)
        nav.push(Route.ArtistPage(artist))
        nav.push(Route.AlbumPage(album))
        assertTrue(nav.back())
        assertEquals(Route.ArtistPage(artist), nav.current)
        assertTrue(nav.back())
        assertEquals(Route.Songs, nav.current)
        assertFalse(nav.back())
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `the same page twice in a row is one`() {
        val nav = Navigator()
        nav.push(Route.AlbumPage(album))
        nav.push(Route.AlbumPage(album))
        assertEquals(2, nav.stack.size)
        nav.open(Route.Songs)
        nav.open(Route.Songs)
        assertEquals(1, nav.stack.size)
    }

    @Test
    fun `the history is bounded`() {
        val nav = Navigator()
        repeat(500) { nav.push(Route.Chat(it.toLong(), "c$it")) }
        assertTrue(nav.stack.size <= 50)
        assertEquals(Route.Chat(499, "c499"), nav.current)
    }
}

class ListRulesTest {
    @Test
    fun `shuffle all reads once when the library fits a queue, else windows from all over`() {
        assertTrue(ShuffleAll.offsets(0).isEmpty())
        assertEquals(listOf(0), ShuffleAll.offsets(500))
        assertEquals(ShuffleAll.QUEUE, ShuffleAll.limit(500))
        val windows = ShuffleAll.offsets(100_000, Random(1))
        assertEquals(ShuffleAll.QUEUE / ShuffleAll.WINDOW, windows.size)
        assertEquals(windows.sorted(), windows)
        assertTrue(windows.all { it in 0 until 100_000 && it % ShuffleAll.WINDOW == 0 })
        assertEquals(windows.size, windows.toSet().size)
        assertEquals(ShuffleAll.WINDOW, ShuffleAll.limit(100_000))
        // Not just the first rows: with 100,000 songs the windows reach well past the first thousand.
        assertTrue(windows.last() > 1_000)
    }

    @Test
    fun `the letter bar shows every letter when they fit and keeps A to Z first when they do not`() {
        val letters = ('A'..'Z').mapIndexed { i, c -> c to i * 10 } + listOf('Я' to 300, 'あ' to 320)
        assertEquals(letters, sampleLetters(letters, 40))
        val few = sampleLetters(letters, 10)
        assertTrue(few.size <= 10)
        assertEquals('A', few.first().first)
        assertEquals(few.map { it.second }, few.map { it.second }.sorted())
        val tiny = sampleLetters(letters, 2)
        assertEquals(2, tiny.size)
    }

    @Test
    fun `two clicks on the same row quickly make a double click, on different rows they do not`() {
        assertFalse(DoubleClicks.isDouble("a", now = 1_000))
        assertTrue(DoubleClicks.isDouble("a", now = 1_200))
        // A third click starts over.
        assertFalse(DoubleClicks.isDouble("a", now = 1_300))
        assertFalse(DoubleClicks.isDouble("b", now = 1_400))
        assertFalse(DoubleClicks.isDouble("a", now = 1_500))
        assertFalse(DoubleClicks.isDouble("a", now = 9_000))
    }

    @Test
    fun `the playing mark follows the song, or the exact file when played from a chat`() {
        val file = TrackId(1, 5)
        val bySong = PlayingMark("song", file, playing = true)
        assertEquals(true, bySong.forSong("song", null))
        assertNull(bySong.forSong("other", file))
        assertEquals(true, bySong.forTrack(file))
        assertNull(bySong.forTrack(TrackId(1, 6)))
        // A file played from a chat page has no song key: the song whose default file it is is marked.
        val fromChat = PlayingMark("", file, playing = false)
        assertEquals(false, fromChat.forSong("any", file))
        assertNull(fromChat.forSong("any", TrackId(1, 9)))
    }

    @Test
    fun `escape goes to the newest handler`() {
        BackKeys.clear()
        val calls = mutableListOf<String>()
        val first: () -> Unit = { calls += "first" }
        val second: () -> Unit = { calls += "second" }
        assertFalse(BackKeys.pressed())
        BackKeys.push(first)
        BackKeys.push(second)
        assertTrue(BackKeys.pressed())
        BackKeys.remove(second)
        assertTrue(BackKeys.pressed())
        BackKeys.remove(first)
        assertFalse(BackKeys.pressed())
        assertEquals(listOf("second", "first"), calls)
    }

    @Test
    fun `a jpeg cut short is not whole, other pictures pass`() {
        val whole = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val cut = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5)
        fun check(bytes: ByteArray): Boolean = File.createTempFile("img", ".bin").let {
            it.writeBytes(bytes)
            ImageCheck.isWhole(it).also { _ -> it.delete() }
        }
        assertTrue(check(whole))
        assertFalse(check(cut))
        assertTrue(check(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)))
        assertFalse(check(byteArrayOf(1)))
    }

    @Test
    fun `mpv errors are said in plain words with the code kept`() {
        assertEquals(-17, MpvErrors.codeIn("mpv playback failed (mpv_error=-17)"))
        assertNull(MpvErrors.codeIn(null))
        assertNull(MpvErrors.codeIn("mpv_error=abc"))
        val said = MpvErrors.describe("mpv playback failed (mpv_error=-13)", null)
        assertTrue("mpv_error=-13" in said)
        assertTrue(said.length > "mpv_error=-13".length)
        assertEquals("odd text", MpvErrors.describe("odd text", null))
        assertTrue(MpvErrors.describe(null, "3").isNotBlank())
        assertTrue(MpvErrors.describe("", null).isNotBlank())
    }
}

class StreamInputTest {
    private val data = ByteArray(1000) { (it % 251).toByte() }

    @Test
    fun `reads what is asked at the position it was moved to`() {
        val input = StreamInput(ArrayBytes(data), null)
        assertEquals(1000, input.size)
        val buffer = ByteArray(10)
        assertEquals(10, input.read(buffer, 0, 10))
        assertEquals(data.copyOfRange(0, 10).toList(), buffer.toList())
        input.seekTo(500)
        assertEquals(500, input.position)
        assertEquals(10, input.read(buffer, 0, 10))
        assertEquals(data.copyOfRange(500, 510).toList(), buffer.toList())
        assertEquals(490, input.bytesRemaining)
    }

    @Test
    fun `the end is the end and a read of nothing is nothing`() {
        val input = StreamInput(ArrayBytes(data), null)
        input.seekTo(1000)
        assertEquals(-1, input.read(ByteArray(8), 0, 8))
        assertEquals(0, input.read(ByteArray(8), 0, 0))
        input.seekTo(995)
        assertEquals(5, input.read(ByteArray(64), 0, 64))
    }

    @Test
    fun `a negative seek and a use after close fail with an IOException`() {
        val input = StreamInput(ArrayBytes(data), null)
        assertFailsWith<IOException> { input.seekTo(-1) }
        input.close()
        input.close()
        assertFailsWith<IOException> { input.read(ByteArray(4), 0, 4) }
        assertFailsWith<IOException> { input.seekTo(3) }
    }

    @Test
    fun `a read that has to wait waits for the bytes`() {
        val arriving = object : StreamBytes by ArrayBytes(data) {
            private var ready = false
            override fun cachedAvailable(position: Long): Long? = if (ready) 100 else null
            override suspend fun awaitBytesAt(position: Long): Long {
                kotlinx.coroutines.delay(30)
                ready = true
                return 100
            }
        }
        val input = StreamInput(arriving, null)
        val buffer = ByteArray(10)
        assertEquals(10, input.read(buffer, 0, 10))
        assertEquals(data.copyOfRange(0, 10).toList(), buffer.toList())
    }

    @Test
    fun `a wait that outlives the timeout is an IOException, not a hang`() {
        val never = object : StreamBytes by ArrayBytes(data) {
            override fun cachedAvailable(position: Long): Long? = null
            override suspend fun awaitBytesAt(position: Long): Long {
                kotlinx.coroutines.delay(60_000)
                return 0
            }
        }
        val input = StreamInput(never, null, readTimeoutMs = 80)
        assertFailsWith<IOException> { input.read(ByteArray(4), 0, 4) }
    }

    @Test
    fun `a file shorter on disk than said is asked again a few times, then fails`() {
        var reads = 0
        val short = object : StreamBytes by ArrayBytes(data) {
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                if (reads < 3) throw EOFException("short")
                return 4
            }
        }
        val input = StreamInput(short, null)
        assertEquals(4, input.read(ByteArray(4), 0, 4))
        assertEquals(3, reads)

        val always = object : StreamBytes by ArrayBytes(data) {
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw EOFException("short")
        }
        assertFailsWith<IOException> { StreamInput(always, null).read(ByteArray(4), 0, 4) }
    }

    @Test
    fun `closing the media closes the bytes once`() = runBlocking {
        val bytes = ArrayBytes(data)
        var closes = 0
        val media = com.tmplayer.music.desktop.player.TdMediaData(bytes, "t://x") { closes++ }
        assertEquals(1000, media.fileLength())
        media.close()
        media.close()
        assertTrue(bytes.closed)
        assertEquals(1, closes)
        assertFailsWith<IOException> { media.createInput(kotlin.coroutines.EmptyCoroutineContext) }
        Unit
    }
}
