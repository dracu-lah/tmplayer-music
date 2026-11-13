package com.tmplayer.music.connect

import android.content.Context
import android.os.SystemClock
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How the phone stands with the TV it controls. */
enum class Link { Idle, Searching, Connecting, Connected, Offline, Forgotten }

/** Everything the Remote screen draws: the TV, the link, and what the TV last said. */
data class RemoteUi(
    val tv: Paired? = null,
    val link: Link = Link.Idle,
    val now: ConnectBody.NowPlaying? = null,
    /** When [now] arrived ([SystemClock.elapsedRealtime]): the position is [positionNow] from there while it plays. */
    val receivedAt: Long = 0,
    val queue: ConnectBody.QueueView? = null,
    val cover: ConnectBody.CoverPicture? = null,
) {
    /** The TV's position now: what it said, moved on by the time since while it plays and is not waiting for data. */
    fun positionNow(at: Long = SystemClock.elapsedRealtime()): Long {
        val n = now ?: return 0
        val moved = if (n.playing && !n.buffering) at - receivedAt else 0
        val total = n.positionMs + moved
        return if (n.durationMs > 0) total.coerceIn(0, n.durationMs) else total.coerceAtLeast(0)
    }
}

/** How a Send to the TV ended. */
sealed interface SendOutcome {
    /** [ok] went in; [missing] names (at most 20 of [missingCount]) are not in the TV's library. */
    data class Done(val tvName: String, val ok: Int, val missing: List<String>, val missingCount: Int) : SendOutcome
    data class Unreachable(val tvName: String) : SendOutcome

    /** The TV no longer knows this phone. */
    data class Forgotten(val tvName: String) : SendOutcome
    data object NoTv : SendOutcome
}

/**
 * The phone's side of Connect. The Remote screen calls [open] while it is on screen and [close] when
 * it leaves: the link is kept (and re-made with a growing wait when it drops) only in between, so a
 * phone with the screen closed does nothing. A Send from a menu ([sendToTv]) reuses that link when
 * there is one, else connects for the moment and hangs up.
 *
 * It finds the TV where it was last seen, then by looking on the network (NSD) for its id, so a TV
 * whose address changed is found again. It only ever connects to the TV whose id it was paired with.
 */
object PhoneRemote {
    private const val TAG = "PhoneRemote"
    private const val FIND_MS = 8_000L
    private const val REPLY_MS = 15_000L
    private const val MAX_WAIT_MS = 30_000L

    private val scope = JobFailures.scope("PhoneRemote", Dispatchers.Default)
    private val _ui = MutableStateFlow(RemoteUi())
    val ui: StateFlow<RemoteUi> = _ui.asStateFlow()
    private val replies = MutableSharedFlow<ConnectBody.Sent>(extraBufferCapacity = 16)

    private var loop: Job? = null

    @Volatile
    private var live: RemoteConnection? = null

    private fun me() = ClientIdentity(Connect.deviceId, Connect.deviceName)

    /** The remote screen is on: connects to [tv] (the active TV when null) and keeps the link while open. */
    @Synchronized
    fun open(context: Context, tv: Paired? = Connect.activeTv()) {
        tv ?: return
        if (_ui.value.tv?.id == tv.id && loop?.isActive == true) return
        loop?.cancel()
        _ui.value = RemoteUi(tv = tv, link = Link.Searching)
        val app = context.applicationContext
        loop = scope.launch { keep(app, tv) }
    }

    /** The remote screen left: hangs up and stops looking. */
    @Synchronized
    fun close() {
        loop?.cancel()
        loop = null
        live?.close()
        live = null
        _ui.value = RemoteUi()
    }

    fun send(body: ConnectBody): Boolean = live?.send(body) ?: false

    private suspend fun keep(context: Context, tv: Paired) {
        var wait = 2_000L
        while (currentCoroutineContext().isActive) {
            _ui.update { it.copy(link = Link.Searching) }
            val closed = CompletableDeferred<Unit>()
            val result = withContext(Dispatchers.IO) { connectOnce(context, tv, ::onBody) { closed.complete(Unit) } }
            when (result) {
                is ConnectResult.Connected -> {
                    live = result.connection
                    wait = 2_000L
                    _ui.update { it.copy(link = Link.Connected) }
                    try {
                        closed.await()
                    } finally {
                        withContext(NonCancellable) {
                            result.connection.close()
                            if (live === result.connection) live = null
                        }
                    }
                    _ui.update { it.copy(link = Link.Offline) }
                }
                is ConnectResult.Refused -> {
                    // The TV forgot this phone or the secret no longer fits: asking again will not help.
                    _ui.update { it.copy(link = Link.Forgotten) }
                    return
                }
                is ConnectResult.Unreachable -> _ui.update { it.copy(link = Link.Offline) }
            }
            delay(wait)
            wait = minOf(wait * 2, MAX_WAIT_MS)
        }
    }

    private fun onBody(body: ConnectBody) {
        when (body) {
            is ConnectBody.NowPlaying -> _ui.update { it.copy(now = body, receivedAt = SystemClock.elapsedRealtime()) }
            is ConnectBody.QueueView -> _ui.update { it.copy(queue = body) }
            is ConnectBody.CoverPicture -> _ui.update { it.copy(cover = body) }
            is ConnectBody.Sent -> replies.tryEmit(body)
            else -> Unit
        }
    }

    /** Looks for [tv] where it was seen, then on the network, and connects; blocking, so off the main thread. */
    private suspend fun connectOnce(context: Context, tv: Paired, onBody: (ConnectBody) -> Unit, onClosed: () -> Unit): ConnectResult {
        var last: ConnectResult = ConnectResult.Unreachable("not found")
        if (tv.host.isNotBlank() && tv.port > 0) {
            _ui.update { it.copy(link = Link.Connecting) }
            last = ConnectClient.connect(tv.host, tv.port, me(), tv, onBody, onClosed)
            if (last !is ConnectResult.Unreachable) return last
        }
        _ui.update { it.copy(link = Link.Searching) }
        val found = withTimeoutOrNull(FIND_MS) {
            TvDiscovery.scan(context).first { list -> list.any { it.id == tv.id } }.first { it.id == tv.id }
        } ?: return last
        _ui.update { it.copy(link = Link.Connecting) }
        val result = ConnectClient.connect(found.host, found.port, me(), tv, onBody, onClosed)
        if (result is ConnectResult.Connected) Connect.tvs.seen(tv.id, found.host, found.port)
        return result
    }

    /**
     * Sends [refs] to [tv]: Play on TV, Play next, or Add to TV queue. Uses the open remote link or
     * connects for the moment. The TV answers with what it took and the names of what its library
     * does not have; nothing is fetched or guessed for the missing ones.
     */
    suspend fun sendToTv(context: Context, tv: Paired?, how: SendHow, refs: List<TrackRef>, start: Int = 0, shuffle: Boolean = false): SendOutcome {
        tv ?: return SendOutcome.NoTv
        if (refs.isEmpty()) return SendOutcome.Done(tv.name, 0, emptyList(), 0)
        val own = live?.takeIf { it.isOpen && it.tvId == tv.id }
        val adhoc = if (own == null) {
            // A connection made for one send listens only for the answer to it.
            val answers: (ConnectBody) -> Unit = { if (it is ConnectBody.Sent) replies.tryEmit(it) }
            when (val result = withContext(Dispatchers.IO) { connectOnce(context.applicationContext, tv, answers) { } }) {
                is ConnectResult.Connected -> result.connection
                is ConnectResult.Refused -> return SendOutcome.Forgotten(tv.name)
                is ConnectResult.Unreachable -> return SendOutcome.Unreachable(tv.name)
            }
        } else {
            null
        }
        val connection = own ?: adhoc ?: return SendOutcome.Unreachable(tv.name)
        try {
            Connect.setActiveTv(tv.id)
            val inbox = Channel<ConnectBody.Sent>(Channel.UNLIMITED)
            val collector = scope.launch { replies.collect { inbox.trySend(it) } }
            try {
                var ok = 0
                var missingCount = 0
                val names = ArrayList<String>()
                for (message in SendPlan.messages(how, refs, start, shuffle)) {
                    if (!connection.send(message)) return SendOutcome.Unreachable(tv.name)
                    val answer = withTimeoutOrNull(REPLY_MS) { inbox.receive() } ?: return SendOutcome.Unreachable(tv.name)
                    ok += answer.ok
                    missingCount += answer.missingCount
                    names += answer.missing
                }
                return SendOutcome.Done(tv.name, ok, names.take(ConnectLimits.MAX_MISSING_NAMES), missingCount)
            } finally {
                collector.cancel()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "a send to the TV failed", e)
            return SendOutcome.Unreachable(tv.name)
        } finally {
            adhoc?.close()
        }
    }

    /**
     * Sends [songs] to the active TV and tells the user how it went in a toast: what went in, and by
     * name what the TV's library does not have. Runs on its own scope, so a menu closing does not cancel it.
     */
    fun sendAndTell(context: Context, how: SendHow, songs: List<com.tmplayer.music.library.Song>, start: Int = 0, shuffle: Boolean = false) {
        val app = context.applicationContext
        val tv = Connect.activeTv() ?: return
        val refs = songs.map { TrackRef(it.defaultTrack.chatId, it.defaultTrack.messageId, it.groupKey, it.title) }
        scope.launch {
            val text = when (val outcome = sendToTv(app, tv, how, refs, start, shuffle)) {
                is SendOutcome.Done -> listOfNotNull(
                    com.tmplayer.music.i18n.L.connectSentOk(outcome.tvName).takeIf { outcome.ok > 0 || outcome.missingCount == 0 },
                    com.tmplayer.music.i18n.L.connectSentMissing(outcome.missingCount, outcome.missing.joinToString(", ")).takeIf { outcome.missingCount > 0 },
                ).joinToString(" ")
                is SendOutcome.Unreachable -> com.tmplayer.music.i18n.L.connectSendFailed(outcome.tvName)
                is SendOutcome.Forgotten -> com.tmplayer.music.i18n.L.connectSendForgotten(outcome.tvName)
                SendOutcome.NoTv -> return@launch
            }
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** Pairs with a TV found by [FoundTv] or typed ("host:port"), with [code]; stores the pairing when it works. */
    suspend fun pair(host: String, port: Int, code: String): PairResult {
        val result = withContext(Dispatchers.IO) { ConnectClient.pair(host, port, me(), code) }
        if (result is PairResult.Success) {
            Connect.tvs.add(result.paired)
            Connect.setActiveTv(result.paired.id)
        }
        return result
    }

    /**
     * Pairs with the TV a scanned [link] named, with its one time token (nothing typed). Stores the
     * pairing when it works, like [pair].
     */
    suspend fun pairLink(link: PairLink): PairResult {
        val result = withContext(Dispatchers.IO) { ConnectClient.pair(link, me()) }
        if (result is PairResult.Success) {
            Connect.tvs.add(result.paired)
            Connect.setActiveTv(result.paired.id)
        }
        return result
    }

    private val _pendingLink = MutableStateFlow<PairLink?>(null)

    /** A TV's pairing link that was just opened (from the phone's camera app), waiting for the user to confirm. */
    val pendingLink: StateFlow<PairLink?> = _pendingLink.asStateFlow()

    /** Takes the text a camera app opened us with; ignored unless it is a well formed link. */
    fun offerLink(text: String?) {
        if (!Connect.available) return
        _pendingLink.value = PairLink.parse(text) ?: return
    }

    fun dismissLink() {
        _pendingLink.value = null
    }

    /** Forgets [id] on this phone (the TV keeps listing this phone until it forgets it too). */
    fun forget(id: String) {
        if (_ui.value.tv?.id == id) close()
        Connect.tvs.forget(id)
    }

    /** A typed "host:port" or "host" (the default port is not known: a port is needed). Null when it is not one. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val t = text.trim().removePrefix("http://").removeSuffix("/")
        val at = t.lastIndexOf(':')
        if (at <= 0) return null
        val port = t.substring(at + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = t.substring(0, at).trim().takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
        return host to port
    }
}
