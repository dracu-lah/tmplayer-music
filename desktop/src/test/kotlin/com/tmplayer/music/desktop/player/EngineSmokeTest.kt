package com.tmplayer.music.desktop.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plays a generated sound through the real libmpv, streamed from memory the way a Telegram file is
 * ([TdMediaData] over [StreamBytes]). It loads native libraries, opens a small window (mediamp's
 * libmpv loads only with a render surface attached) and may need a sound output, so it runs only
 * when asked: `TMPLAYER_SMOKE=1 ./gradlew :desktop:test` (with `TMPLAYER_AO=null` on a machine with
 * no sound card).
 */
class EngineSmokeTest {
    @Test
    fun `libmpv plays a streamed file, seeks, pauses and reports its end`() {
        assumeTrue(System.getenv("TMPLAYER_SMOKE") == "1")
        var failure: Throwable? = null
        application {
            val engine = remember { MpvNatives.create() }
            Window(onCloseRequest = ::exitApplication, title = "tmplayer-smoke", state = rememberWindowState(size = DpSize(240.dp, 120.dp))) {
                AudioSurface(engine)
                LaunchedEffect(Unit) {
                    try {
                        withTimeout(90_000) { script(engine) }
                    } catch (e: Throwable) {
                        failure = e
                    } finally {
                        engine.close()
                        exitApplication()
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    /** The formats a chat's audio files come in, made with the system's ffmpeg (the test is skipped without it). */
    private val formats = listOf(
        "flac" to listOf("-c:a", "flac"),
        "mp3" to listOf("-c:a", "libmp3lame"),
        "m4a" to listOf("-c:a", "aac"),
        "ogg" to listOf("-c:a", "libvorbis"),
        "opus" to listOf("-c:a", "libopus"),
        "wav" to listOf("-c:a", "pcm_s16le"),
    )

    private fun sample(ext: String, codec: List<String>): ByteArray? = runCatching {
        val out = java.io.File.createTempFile("smoke", ".$ext")
        try {
            val p = ProcessBuilder(listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=4") + codec + listOf(out.path))
                .redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() == 0 && out.length() > 0) out.readBytes() else null
        } finally {
            out.delete()
        }
    }.getOrNull()

    private suspend fun script(engine: AudioEngine) {
        val report = StringBuilder()
        var played = 0
        for ((ext, codec) in formats) {
            val data = sample(ext, codec) ?: continue
            engine.open(TdMediaData(ArrayBytes(data), "test://tone.$ext"), 0, true)
            val error = engine.state.value.error
            if (error != null) {
                report.append("$ext: FAILED $error\n")
                continue
            }
            try {
                eventually("$ext position advances", ms = 8_000) { engine.state.value.positionMs >= 300 }
                report.append("$ext: ok, ${engine.state.value.durationMs} ms\n")
                played++
            } catch (e: AssertionError) {
                report.append("$ext: opened but did not advance (${engine.state.value.error})\n")
            }
        }
        println("SMOKE formats:\n$report")
        assertTrue(played > 0, "no format played:\n$report")
        // The controls, on the last file that played.
        val last = sample("flac", listOf("-c:a", "flac"))
        if (last != null) {
            engine.open(TdMediaData(ArrayBytes(last), "test://tone.flac"), 0, true)
            assertNull(engine.state.value.error, "open failed: ${engine.state.value.error}")
            eventually("position advances", ms = 15_000) { engine.state.value.positionMs >= 500 }
            assertTrue(engine.state.value.playing)
            eventually("duration known", ms = 5_000) { engine.state.value.durationMs in 3_000..5_000 }
            engine.pause()
            eventually("paused", ms = 5_000) { !engine.state.value.playing }
            engine.seekTo(3_000)
            eventually("seek lands", ms = 5_000) { engine.state.value.positionMs in 2_900..3_600 }
            engine.setVolume(30)
            assertEquals(30, engine.state.value.volume)
            engine.setMuted(true)
            assertTrue(engine.state.value.muted)
            engine.setMuted(false)
            engine.play()
            eventually("the end is reported", ms = 15_000) { engine.state.value.ended }
        }
    }
}
