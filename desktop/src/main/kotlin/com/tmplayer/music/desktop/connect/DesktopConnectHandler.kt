package com.tmplayer.music.desktop.connect

import com.tmplayer.music.connect.ConnectBody
import com.tmplayer.music.connect.ConnectHandler
import com.tmplayer.music.connect.ConnectLimits
import com.tmplayer.music.connect.QueueEntry as WireEntry
import com.tmplayer.music.connect.RemoteQueue
import com.tmplayer.music.connect.SendHow
import com.tmplayer.music.connect.TrackRef
import com.tmplayer.music.connect.Transport
import com.tmplayer.music.desktop.player.PlayerController
import com.tmplayer.music.desktop.player.PlayerUi
import com.tmplayer.music.desktop.player.QueueEntry
import com.tmplayer.music.desktop.player.RepeatMode
import com.tmplayer.music.desktop.player.TrackSource
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.SavedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a paired phone may do to this computer's player: the commands of Connect mapped onto the
 * desktop [PlayerController]. It is the desktop's twin of the TV's handler: a phone controls the
 * player and puts tracks in its queue, only because the phone asked. Tracks arrive as references
 * and are looked up in this computer's own library through [source]; one it does not have is named
 * in the answer and never fetched. Nothing starts playing by itself on connect.
 *
 * State goes out only when the player changes and only while a phone is connected ([watch]): an idle
 * computer with no phone does no work for Connect at all.
 */
class DesktopConnectHandler(
    private val player: PlayerController,
    private val source: TrackSource,
    /** False while the library is not open (not signed in, still opening): a Send then finds nothing. */
    private val ready: () -> Boolean,
    private val scope: CoroutineScope,
    private val resolveMs: Long = RESOLVE_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ConnectHandler {

    /** Where an answer to one phone goes; the host sets it to the server's [com.tmplayer.music.connect.ConnectServer.sendTo]. */
    @Volatile
    var reply: (to: String, body: ConnectBody) -> Unit = { _, _ -> }

    override fun snapshot(): List<ConnectBody> = listOf(nowPlaying(), queueView())

    override fun onCommand(from: String, body: ConnectBody) {
        scope.launch {
            try {
                apply(from, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a phone's command failed", e)
            }
        }
    }

    private suspend fun apply(from: String, body: ConnectBody) {
        when (body) {
            is ConnectBody.Command -> when (body.action) {
                Transport.Play -> player.play()
                Transport.Pause -> player.pause()
                Transport.Toggle -> player.togglePlay()
                Transport.Next -> player.next()
                Transport.Previous -> player.previous()
            }
            is ConnectBody.Seek -> player.seekTo(body.positionMs)
            is ConnectBody.Shuffle -> player.setShuffle(body.on)
            is ConnectBody.Repeat -> player.setRepeat(RepeatMode.fromSaved(body.mode))
            is ConnectBody.Volume -> player.setVolume(body.percent)
            is ConnectBody.Jump ->
                if (RemoteQueue.stillThere(body.index, body.id, ::idAt)) player.skipTo(body.index) else reply(from, queueView())
            is ConnectBody.Remove ->
                if (RemoteQueue.stillThere(body.index, body.id, ::idAt)) player.remove(body.index) else reply(from, queueView())
            is ConnectBody.Send -> send(from, body)
            else -> Unit
        }
    }

    /** The id a phone sends back with a tap: the entry's own, so a row that moved or went is never hit by mistake. */
    private fun idAt(index: Int): String? = player.ui.value.queue.entries.getOrNull(index)?.uid?.toString()

    /**
     * Looks each reference up in this computer's library (the exact file, else the same song by its
     * key) and plays or queues what it finds. What it does not have is named in the answer.
     */
    private suspend fun send(from: String, body: ConnectBody.Send) {
        val found = ArrayList<Pair<Int, QueueEntry>>()
        val missing = ArrayList<TrackRef>()
        if (ready()) {
            val resolved = withTimeoutOrNull(resolveMs) { body.items.map { ref -> lookup(ref) } }
            body.items.forEachIndexed { i, ref ->
                val entry = resolved?.getOrNull(i)
                if (entry != null) found += i to entry else missing += ref
            }
        } else {
            missing += body.items
        }
        if (found.isNotEmpty()) {
            val entries = found.map { it.second }
            when (body.how) {
                SendHow.Play -> player.playEntries(entries, found.indexOfFirst { it.first >= body.start }.let { if (it < 0) 0 else it }, body.shuffle)
                SendHow.Next -> player.playNextEntries(entries)
                SendHow.Add -> player.addEntries(entries)
            }
        }
        reply(from, ConnectBody.Sent(body.how, found.size, RemoteQueue.missingNames(missing), missing.size))
    }

    private suspend fun lookup(ref: TrackRef): QueueEntry? =
        try {
            source.resolve(SavedQueue.Item(ref.chatId, ref.messageId, ref.songKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // ---- What goes out.

    internal fun nowPlaying(ui: PlayerUi = player.ui.value, positionMs: Long = player.position.value): ConnectBody.NowPlaying {
        val entry = ui.current
        return ConnectBody.NowPlaying(
            title = entry?.title.orEmpty().take(ConnectLimits.MAX_TEXT),
            artist = entry?.artist.orEmpty().take(ConnectLimits.MAX_TEXT),
            album = entry?.album.orEmpty().take(ConnectLimits.MAX_TEXT),
            durationMs = if (ui.durationMs > 0) ui.durationMs else entry?.durationMs ?: 0L,
            positionMs = positionMs.coerceAtLeast(0),
            playing = ui.playing, buffering = ui.buffering, shuffle = ui.shuffle, repeat = ui.repeat.saved,
            index = ui.queue.current, count = ui.queue.size,
            volume = if (ui.muted) 0 else ui.volume, volumeFixed = false,
            coverKey = entry?.artKey.orEmpty(), hasTrack = entry != null,
        )
    }

    internal fun queueView(ui: PlayerUi = player.ui.value): ConnectBody.QueueView {
        val entries = ui.queue.entries
        val (from, count) = RemoteQueue.window(entries.size, ui.queue.current)
        val items = entries.subList(from, from + count).map { e ->
            WireEntry(e.uid.toString(), e.title.take(ConnectLimits.MAX_TEXT), e.artist.take(ConnectLimits.MAX_TEXT), e.durationMs)
        }
        return ConnectBody.QueueView(ui.queue.current, entries.size, from, items)
    }

    /**
     * While at least one phone is connected, pushes what changed: the state when the player's state
     * changes, the queue when it does, and the state again when the position jumps (a seek). With
     * nobody connected the collectors are cancelled, so nothing here runs.
     */
    fun watch(clients: StateFlow<Int>, broadcast: (ConnectBody) -> Unit): Job = scope.launch {
        clients.collectLatest { n ->
            if (n == 0) return@collectLatest
            coroutineScope {
                launch {
                    var before = player.ui.value
                    player.ui.drop(1).collect { ui ->
                        broadcast(nowPlaying(ui))
                        if (ui.queue.entries !== before.queue.entries || ui.queue.current != before.queue.current) broadcast(queueView(ui))
                        before = ui
                    }
                }
                launch {
                    var lastPos = player.position.value
                    var lastAt = clock()
                    player.position.collect { pos ->
                        val now = clock()
                        if (Seeks.jumped(lastPos, now - lastAt, pos, player.ui.value.playing)) broadcast(nowPlaying(positionMs = pos))
                        lastPos = pos
                        lastAt = now
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DesktopConnect"
        const val RESOLVE_MS = 20_000L
    }
}

/** Tells a seek from the clock: the position moved by far more (or less) than the time that passed. */
object Seeks {
    /** How far from the expected spot a position may be before it counts as a jump. */
    const val SLACK_MS = 1_500L

    fun jumped(lastPosition: Long, elapsedMs: Long, position: Long, playing: Boolean): Boolean {
        val expected = if (playing) lastPosition + elapsedMs.coerceAtLeast(0) else lastPosition
        return kotlin.math.abs(position - expected) > SLACK_MS
    }
}
