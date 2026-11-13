package com.tmplayer.music.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.tmplayer.music.data.AuthState
import com.tmplayer.music.desktop.player.FakeEngine
import com.tmplayer.music.desktop.player.FakeSource
import com.tmplayer.music.desktop.player.MemoryStore
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.eventually
import com.tmplayer.music.desktop.ui.auth.SignInScreen
import com.tmplayer.music.desktop.ui.common.ListSelection
import com.tmplayer.music.desktop.ui.library.LocalPlayingMark
import com.tmplayer.music.desktop.ui.library.PlayingMark
import com.tmplayer.music.desktop.ui.library.SongActions
import com.tmplayer.music.desktop.ui.library.SongTable
import com.tmplayer.music.desktop.ui.player.NOW_PLAYING_HEIGHT
import com.tmplayer.music.desktop.ui.player.NowPlayingBar
import com.tmplayer.music.desktop.ui.player.QueuePanel
import com.tmplayer.music.desktop.ui.theme.MusicTheme
import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.TrackId
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the desktop's screens off screen (no window, no display needed) at the sizes the layout
 * must hold, saves them under `build/screenshots`, and checks the look that does not change: the
 * page is pure black and something is drawn on it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): Image {
        val scene = ImageComposeScene(width, height, Density(1f)) { MusicTheme(content) }
        try {
            // A few frames, so effects launched on the first one have run.
            var image = scene.render(0)
            for (i in 1..6) image = scene.render(i * 50_000_000L)
            File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return image
        } finally {
            scene.close()
        }
    }

    private fun Image.pixels(): IntArray {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(this)
        val array = IntArray(width * height)
        val bytes = bitmap.readPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(width, height), width * 4)!!
        for (i in array.indices) {
            array[i] = (bytes[i * 4 + 3].toInt() and 0xFF shl 24) or (bytes[i * 4 + 2].toInt() and 0xFF shl 16) or
                (bytes[i * 4 + 1].toInt() and 0xFF shl 8) or (bytes[i * 4].toInt() and 0xFF)
        }
        return array
    }

    private fun assertBlackPageWithContent(image: Image) {
        val pixels = image.pixels()
        val black = 0xFF000000.toInt()
        assertEquals(black, pixels[0], "the top left corner is pure black")
        val drawn = pixels.count { it != black }
        assertTrue(drawn > pixels.size / 200, "something is drawn on the page ($drawn of ${pixels.size} pixels)")
    }

    @Test
    fun `the first sign in step renders at the smallest and a large size`() {
        for ((w, h) in listOf(900 to 600, 1920 to 1080)) {
            val image = render("signin-choose-${w}x$h", w, h) { SignInScreen(AuthState.ChooseMethod) }
            assertBlackPageWithContent(image)
        }
    }

    @Test
    fun `the phone step and the code step render`() {
        assertBlackPageWithContent(render("signin-phone", 1100, 700) { SignInScreen(AuthState.Phone()) })
        assertBlackPageWithContent(
            render("signin-code", 1100, 700) {
                SignInScreen(AuthState.Code("+44 7700 900123", delivery = com.tmplayer.music.data.CodeDelivery.TelegramApp, length = 5, canResend = true))
            },
        )
        assertBlackPageWithContent(render("signin-password", 1100, 700) { SignInScreen(AuthState.Password("a hint")) })
        assertBlackPageWithContent(render("signin-qr", 1100, 700) { SignInScreen(AuthState.Qr("tg://login?token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")) })
        assertBlackPageWithContent(render("signin-failed", 1100, 700) { SignInScreen(AuthState.Failed("Telegram did not answer. Try again in a moment.")) })
    }

    private fun song(n: Int, album: String = "Album $n") = Song(
        groupKey = "song$n",
        title = "A song called number $n",
        artist = "Some Artist $n",
        album = album,
        durationMs = 180_000L + n * 1_000L,
        defaultTrack = TrackId(1, n.toLong()),
        versionCount = if (n % 3 == 0) 2 else 1,
        bestFormat = if (n % 2 == 0) AudioFormat.FLAC else AudioFormat.MP3,
        thumbFileId = 0,
        coverKey = "",
        variant = "",
        addedSec = 0,
    )

    @Composable
    private fun Library(player: PlayerController, queue: Boolean, songs: List<Song>) {
        val none = SongActions({ _, _ -> }, {}, {}, {}, {}, {}, {})
        val mark = player.ui.value.current?.let { PlayingMark(it.songKey, it.id, player.ui.value.playing) }
        CompositionLocalProvider(LocalPlayingMark provides mark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SongTable(songs, rememberLazyListState(), none, ListSelection(setOf("song2", "song3"), "song2"), wide = true) {}
                        if (queue) QueuePanel(player, onClose = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                    }
                    NowPlayingBar(player, queueOpen = queue, onToggleQueue = {})
                }
            }
        }
    }

    private fun playingPlayer(): PlayerController = runBlocking {
        val source = FakeSource((1..12).map { "song$it" })
        val player = PlayerController(source, MemoryStore(), { FakeEngine() })
        player.playEntries((1..12).map { source.entry("song$it") }, 2)
        eventually("playing") { player.ui.value.playing }
        player
    }

    @Test
    fun `songs with the now playing bar render at 900 by 600 and 1600 by 1000`() {
        val player = playingPlayer()
        val songs = (1..12).map(::song)
        for ((w, h) in listOf(900 to 600, 1600 to 1000)) {
            assertBlackPageWithContent(render("library-${w}x$h", w, h) { Library(player, queue = false, songs = songs) })
        }
        player.close()
    }

    @Test
    fun `the queue panel renders over the page`() {
        val player = playingPlayer()
        assertBlackPageWithContent(render("queue-900x600", 900, 600) { Library(player, queue = true, songs = (1..12).map(::song)) })
        player.close()
    }

    @Test
    fun `the bar is as tall as it says`() {
        assertEquals(88f, NOW_PLAYING_HEIGHT.value)
    }
}
