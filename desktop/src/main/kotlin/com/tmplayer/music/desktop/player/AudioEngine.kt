// The audio half of TMPlayer's MpvPlaybackEngine (GPL-3.0), desktop/src/main/kotlin/com/tmplayer/desktop/player/.
package com.tmplayer.music.desktop.player

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.tmplayer.music.desktop.DesktopPaths
import com.tmplayer.music.i18n.L
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.MediaData
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** What the bar and the queue read from the engine. Times are milliseconds; 0 duration is unknown. */
data class EngineState(
    /** A file is loaded (set once [AudioEngine.open] returned without an error). */
    val opened: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Whether the user wants it playing; the glyph follows this, not momentary stalls. */
    val playing: Boolean = false,
    /** Waiting for bytes: a stall mid play, or a seek that has not landed yet. */
    val buffering: Boolean = false,
    /** Played to its end. The queue moves on when this turns true. */
    val ended: Boolean = false,
    val error: String? = null,
    val volume: Int = 100,
    val muted: Boolean = false,
)

/** The player as the queue sees it: one file at a time, opened from a [MediaData]. */
interface AudioEngine {
    val state: StateFlow<EngineState>

    /** Opens [data] at [startAtMs] and returns once it is loaded, or with [EngineState.error] set. */
    suspend fun open(data: MediaData, startAtMs: Long, playWhenReady: Boolean)

    fun play()
    fun pause()

    /** Stops and lets go of the stream, so nothing more is fetched for it. */
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)
    fun close()
}

/** libmpv could not be loaded: the message is the sentence to show. */
class EngineUnavailable(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** libmpv and its friends, unpacked once into the app's own folder rather than a fresh temp dir per launch. */
object MpvNatives {
    private val prepared = AtomicBoolean(false)

    fun prepare() {
        if (!prepared.compareAndSet(false, true)) return
        runCatching { MpvMediampPlayer.prepareLibraries(DesktopPaths.nativeDir.absolutePath) }
            .onFailure { Logger.w("MpvNatives", "Falling back to mediamp's temp folder", it) }
    }

    /** Why libmpv would not load, kept for the session so the next play does not try again. */
    @Volatile
    private var unavailable: EngineUnavailable? = null

    /**
     * A new [MpvAudioEngine], or [EngineUnavailable] when libmpv cannot be loaded. The loader's
     * failure is an `Error` (`UnsatisfiedLinkError`, then `NoClassDefFoundError` on every later
     * try), which nothing downstream catches, so it is caught here, once, and remembered.
     */
    fun create(): AudioEngine {
        unavailable?.let { throw it }
        try {
            return MpvAudioEngine()
        } catch (e: LinkageError) {
            Logger.w("MpvNatives", "libmpv could not be loaded", e)
            val failure = EngineUnavailable(L.desktopEngineMissing, e)
            unavailable = failure
            throw failure
        }
    }
}

/**
 * [AudioEngine] over libmpv through mediamp. Transport calls (play, pause, seek) go to mediamp's
 * state machine on the Swing thread, which is where it wants them; the volume is mpv's own
 * property, which libmpv allows from any thread. The picture is never wanted (`vid=no`), so a
 * cover embedded in a file opens no window.
 */
class MpvAudioEngine : AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.tmplayer.music.platform.JobFailures.handler("MpvAudioEngine"))

    /** mediamp loads a file only once a render surface is attached, even with no picture: see [AudioSurface]. */
    internal val player: MpvMediampPlayer
    private val handle: MPVHandle get() = player.impl as MPVHandle

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var seekTarget: Long? = null

    @Volatile
    private var seekIssuedAt = 0L

    @Volatile
    private var closed = false

    init {
        MpvNatives.prepare()
        player = MpvMediampPlayer(Unit, SupervisorJob() + Dispatchers.Default, configureOptions = { h ->
            // Sound only: no picture, and no window for a cover art stream.
            h.option("vid", "no")
            h.option("audio-display", "no")
            h.option("audio-channels", "auto-safe")
            h.option("msg-level", "all=warn")
            // A read ahead worth the name for a stream: mpv's cache is "auto", which is off for a
            // plain seekable input. Memory is mpv's own, outside the JVM's heap.
            h.option("cache", "yes")
            h.option("demuxer-readahead-secs", "30")
            h.option("demuxer-max-bytes", "64MiB")
            h.option("demuxer-max-back-bytes", "16MiB")
            h.option("cache-pause-wait", "1")
        })
        watch()
    }

    private fun watch() {
        scope.launch {
            player.state.collect { s ->
                _state.update { it.copy(playing = s.playWhenReady, buffering = s.isBuffering || seekTarget != null) }
            }
        }
        scope.launch {
            // mediamp says the position often; about four a second is all the clock and the bar show.
            var lastAt = 0L
            player.currentPositionMillis.collect { ms ->
                if (seekTarget != null) return@collect
                val now = System.currentTimeMillis()
                if (now - lastAt < POSITION_EVERY_MS) return@collect
                lastAt = now
                _state.update { it.copy(positionMs = ms) }
            }
        }
        scope.launch {
            player.mediaProperties.collect { p ->
                val length = p?.durationMillis ?: return@collect
                if (length > 0) _state.update { it.copy(durationMs = length) }
            }
        }
        scope.launch {
            player.events.collect { e ->
                when (e) {
                    is PlaybackEvent.SeekCompleted -> landSeek(e.positionMillis)
                    is PlaybackEvent.MediaEnded -> _state.update { it.copy(ended = true, playing = false) }
                    is PlaybackEvent.ErrorOccurred -> {
                        Logger.w(TAG, "Playback failed: ${e.error.code} ${e.error.message}")
                        _state.update { it.copy(error = MpvErrors.describe(e.error.message, e.error.code.toString()), playing = false, buffering = false) }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                if (closed || !_state.value.opened) continue
                runCatching { poll() }
            }
        }
    }

    private fun poll() {
        val h = handle
        val pausedForCache = h.bool("paused-for-cache")
        val seeking = h.bool("seeking")
        val duration = h.double("duration")
        // The completion event is the normal way out; this is for the seek it never reports.
        if (seekTarget != null && System.currentTimeMillis() - seekIssuedAt > SEEK_SETTLE_MS && seeking == false && pausedForCache != true) {
            landSeek(null)
        }
        // The position the throttle in [watch] may have left behind, once the sound has stopped.
        val stillAt = if (seekTarget == null && !player.state.value.playWhenReady && !_state.value.ended) {
            player.currentPositionMillis.value.takeIf { it > 0 }
        } else {
            null
        }
        _state.update {
            it.copy(
                positionMs = stillAt ?: it.positionMs,
                durationMs = duration?.takeIf { d -> d > 0 }?.let { d -> (d * 1000).toLong() } ?: it.durationMs,
                buffering = pausedForCache == true || seekTarget != null || player.state.value.isBuffering,
            )
        }
    }

    private fun landSeek(at: Long?) {
        seekTarget = null
        _state.update { it.copy(buffering = false, positionMs = at ?: player.currentPositionMillis.value) }
    }

    override suspend fun open(data: MediaData, startAtMs: Long, playWhenReady: Boolean) {
        seekTarget = null
        val volume = _state.value.volume
        val muted = _state.value.muted
        _state.value = EngineState(positionMs = startAtMs.coerceAtLeast(0), buffering = playWhenReady, volume = volume, muted = muted)
        runCatching {
            handle.setPropertyDouble("volume", volume.toDouble())
            handle.setPropertyBoolean("mute", muted)
        }
        try {
            withContext(Dispatchers.IO) {
                player.setMediaData(data, playWhenReady = playWhenReady, startPositionMillis = startAtMs.coerceAtLeast(0))
            }
            _state.update { it.copy(opened = true, error = null, buffering = false, playing = playWhenReady) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "Open failed", e)
            val error = if (MpvErrors.codeIn(e.message) != null) MpvErrors.describe(e.message, null) else e.message ?: L.playerPlaybackFailed("?")
            _state.update { it.copy(error = error, buffering = false, playing = false) }
        }
    }

    override fun play() {
        if (_state.value.ended) seekToExact(0)
        _state.update { it.copy(playing = true, ended = false) }
        onEdt { player.play() }
    }

    override fun pause() {
        _state.update { it.copy(playing = false) }
        onEdt { player.pause() }
    }

    override fun stop() {
        seekTarget = null
        _state.update { it.copy(playing = false, opened = false, buffering = false) }
        onEdt { player.stopPlayback() }
    }

    override fun seekTo(positionMs: Long) = seekToExact(positionMs)

    private fun seekToExact(positionMs: Long) {
        if (!_state.value.opened) return
        val duration = _state.value.durationMs
        val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        seekTarget = target
        seekIssuedAt = System.currentTimeMillis()
        _state.update { it.copy(positionMs = target, buffering = true, ended = false) }
        onEdt { player.seekTo(target) }
    }

    override fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        runCatching { handle.setPropertyDouble("volume", v.toDouble()) }
        // Turning the volume up is a way out of mute, as in every other player.
        if (v > 0 && _state.value.muted) setMuted(false)
        _state.update { it.copy(volume = v) }
    }

    override fun setMuted(muted: Boolean) {
        runCatching { handle.setPropertyBoolean("mute", muted) }
        _state.update { it.copy(muted = muted) }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        runCatching { player.close() }
    }

    private fun onEdt(block: () -> Unit) {
        if (closed) return
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { if (!closed) block() }
    }

    private companion object {
        const val TAG = "MpvAudioEngine"
        const val POLL_MS = 250L
        const val SEEK_SETTLE_MS = 600L
        const val POSITION_EVERY_MS = 250L
    }
}

private fun MPVHandle.double(name: String): Double? = runCatching { getPropertyDouble(name) }.getOrNull()
private fun MPVHandle.bool(name: String): Boolean? = runCatching { getPropertyBoolean(name) }.getOrNull()

/**
 * mpv's error codes in words a listener can act on (from TMPlayer's MpvErrors, GPL-3.0). mediamp
 * hands a failure on as "mpv playback failed (mpv_error=-17)"; the code stays on a second line so
 * a bug report still carries it. The numbers are `mpv_error` in mpv's client.h.
 */
object MpvErrors {
    private val CODE = Regex("""mpv_error\s*=\s*(-?\d{1,4})(?!\d)""")

    /** The `mpv_error` number in [message], or null when it carries none. */
    fun codeIn(message: String?): Int? = message?.let { CODE.find(it) }?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** What the error line says for a failure whose text is [message] and whose kind is [kind]. Never empty, never throws. */
    fun describe(message: String?, kind: String?): String {
        val code = codeIn(message)
        val plain = code?.let(::plainFor)
        return when {
            plain != null -> "$plain\n(mpv_error=$code)"
            !message.isNullOrBlank() -> message
            else -> L.playerPlaybackFailed(kind ?: "?")
        }
    }

    /** The plain line for [code], or null for a code with nothing better to say than its number. */
    fun plainFor(code: Int): String? = when (code) {
        -13 -> L.playerMpvLoadingFailed
        -14 -> L.playerMpvAudioFailed
        -16 -> L.playerMpvNothingToPlay
        -17 -> L.playerMpvUnknownFormat
        -18 -> L.playerMpvUnsupported
        -2 -> L.playerMpvNoMemory
        else -> null
    }
}

/**
 * mediamp's libmpv player starts a load only when a render surface is attached to it, picture or
 * no picture. Audio has no picture (`vid=no`), so this is one dp, invisible, kept in the shell for
 * as long as an engine exists. Nothing is ever drawn into it.
 */
@androidx.compose.runtime.Composable
fun AudioSurface(engine: AudioEngine?) {
    val mpv = engine as? MpvAudioEngine ?: return
    org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface(
        mpv.player,
        androidx.compose.ui.Modifier.size(1.dp),
    )
}
