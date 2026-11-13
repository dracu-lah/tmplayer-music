package com.tmplayer.music.connect

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import com.tmplayer.music.data.Library
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.ArtworkBytes
import com.tmplayer.music.player.MediaIds
import com.tmplayer.music.player.PlaybackService
import com.tmplayer.music.player.PlayerConnection
import com.tmplayer.music.player.PlayerEvent
import com.tmplayer.music.player.SavedQueue
import com.tmplayer.music.ui.tv.isTv
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the TV's Connect server is doing, for its Settings line. */
sealed interface HostStatus {
    /** Not listening: switched off, or the app is neither on screen nor playing. */
    data object Off : HostStatus
    data object NoNetwork : HostStatus
    data object Failed : HostStatus
    data class Listening(val address: String, val port: Int) : HostStatus
}

/**
 * The TV app's side of Connect: owns the server and ties it to the player. Everything here is a
 * no-op off a TV and in a store build ([Connect.available]).
 *
 * When it listens: [ConnectPolicy], re-decided whenever one of its inputs changes. It starts at
 * once when allowed and stops [STOP_GRACE_MS] after it stops being allowed, so flipping between two
 * screens of the app does not restart it. The address is the TV's own private one (a change of
 * network restarts it on the new one); nothing listens on any other interface.
 *
 * What a phone may do: control the player and put tracks in its queue, each only because the phone
 * asked. Tracks arrive as references and are looked up in the TV's own library; a track it does not
 * have is reported back by name, never fetched. State goes out only when the player changes, and
 * only while a phone is connected.
 */
object TvConnect : ConnectHandler {
    private const val TAG = "TvConnect"
    private const val STOP_GRACE_MS = 15_000L
    private const val QUEUE_SETTLE_MS = 300L
    private const val COVER_PX = 240
    private const val COVER_BYTES = 44 * 1024

    private val scope = JobFailures.scope("TvConnect", Dispatchers.Main.immediate)

    private lateinit var app: Application

    @Volatile
    private var started = false

    private val foreground = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val networkRev = MutableStateFlow(0)
    private val clients = MutableStateFlow(0)
    private val _status = MutableStateFlow<HostStatus>(HostStatus.Off)

    /** What the server is doing now. */
    val status: StateFlow<HostStatus> = _status.asStateFlow()

    /** The pairing door: the code on show and the lock. Shared by the screen and the server. */
    val gate = PairingGate({ SystemClock.elapsedRealtime() })

    private var server: ConnectServer? = null
    private var boundTo: InetAddress? = null
    private var advert: NsdAdvert? = null
    private var pushJob: Job? = null
    private var clientsJob: Job? = null

    /** The cover last made, with the generated key it belongs to; reused for each new phone. */
    @Volatile
    private var lastCover: ConnectBody.CoverPicture? = null

    /** From `Application.onCreate`: starts watching what decides whether to listen. Does nothing off a TV or in a store build. */
    fun start(application: Application) {
        if (!Connect.available || started) return
        if (!isTv(application)) return
        started = true
        app = application
        Connect.init(application, tv = true)
        val power = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn.value = power?.isInteractive ?: true
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                screenOn.value = intent?.action != Intent.ACTION_SCREEN_OFF
            }
        }
        ContextCompat.registerReceiver(
            application, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchNetwork(application)
        scope.launch {
            combine<Any, Pair<Boolean, Int>>(
                Connect.enabled, foreground, PlayerConnection.state.map { it.isPlaying }.distinctUntilChanged(), screenOn, clients, networkRev,
            ) { values -> ConnectPolicy.shouldRun(values[0] as Boolean, values[1] as Boolean, values[2] as Boolean, values[3] as Boolean, values[4] as Int) to (values[5] as Int) }
                .distinctUntilChanged()
                .collectLatest { (run, _) ->
                    if (run) {
                        ensureRunning()
                    } else {
                        delay(STOP_GRACE_MS)
                        shutdown()
                    }
                }
        }
    }

    /** The activity is on screen (or not): the app is in the foreground. */
    fun setForeground(on: Boolean) {
        foreground.value = on
    }

    /** Shows a fresh pairing code. False when the server is not listening (switched off, or no network). */
    fun openPairing(): Boolean {
        if (server == null) return false
        gate.open()
        return true
    }

    fun closePairing() = gate.close()

    /** Forgets [id]: its pairing is removed and its connections end. */
    fun forget(id: String) {
        val s = server
        if (s != null) s.forget(id) else Connect.phones.forget(id)
    }

    private fun watchNetwork(application: Application) {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = bump()
            override fun onLost(network: Network) = bump()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = bump()
            fun bump() {
                networkRev.value = networkRev.value + 1
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.registerDefaultNetworkCallback(callback)
            else cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }.onFailure { Logger.w(TAG, "could not watch the network", it) }
    }

    private suspend fun ensureRunning() {
        val bind = withContext(Dispatchers.IO) { LocalNet.bindAddress() }
        if (server != null && bind == boundTo) return
        shutdown()
        if (bind == null) {
            _status.value = HostStatus.NoNetwork
            return
        }
        val s = ConnectServer(bind, Connect.deviceId, Connect.deviceName, Connect.phones, gate, this)
        val port = try {
            withContext(Dispatchers.IO) { s.start() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "could not listen", e)
            _status.value = HostStatus.Failed
            return
        }
        server = s
        boundTo = bind
        advert = NsdAdvert(app).also { it.register(port, Connect.deviceId, Connect.deviceName) }
        _status.value = HostStatus.Listening(bind.hostAddress.orEmpty(), port)
        clientsJob = scope.launch { s.clients.collect { clients.value = it } }
        pushJob = scope.launch { pushWhileConnected() }
    }

    private fun shutdown() {
        val s = server ?: run {
            if (_status.value !is HostStatus.Off) _status.value = HostStatus.Off
            return
        }
        server = null
        boundTo = null
        pushJob?.cancel()
        clientsJob?.cancel()
        advert?.unregister()
        advert = null
        gate.close()
        s.stop()
        clients.value = 0
        _status.value = HostStatus.Off
    }

    // ---- What goes out: only on change, only while a phone is connected.

    private suspend fun pushWhileConnected() {
        clients.collectLatest { n ->
            if (n == 0) return@collectLatest
            coroutineScope {
                launch { PlayerConnection.state.drop(1).collect { pushNow() } }
                launch {
                    PlayerConnection.events.collect { event ->
                        when (event) {
                            PlayerEvent.Position -> pushNow()
                            PlayerEvent.Queue -> {
                                delay(QUEUE_SETTLE_MS)
                                server?.broadcast(queueView())
                            }
                        }
                    }
                }
                launch { volumeChanges().collect { pushNow() } }
            }
        }
    }

    private fun pushNow() {
        val s = server ?: return
        val now = nowPlaying()
        s.broadcast(now)
        if (now.hasTrack && now.coverKey != lastCover?.key) scope.launch { s.broadcast(coverFor(PlayerConnection.state.value.item) ?: return@launch) }
    }

    private fun volumeChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        // The system's own broadcast for a volume change; only a nudge to read the level again.
        ContextCompat.registerReceiver(app, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }

    private fun audio(): AudioManager? = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun volumePercent(): Int {
        val am = audio() ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
    }

    private fun volumeFixed(): Boolean = audio()?.let { runCatching { it.isVolumeFixed }.getOrDefault(false) } ?: false

    private fun nowPlaying(): ConnectBody.NowPlaying {
        val s = PlayerConnection.state.value
        val m = s.metadata
        return ConnectBody.NowPlaying(
            title = m.title?.toString().orEmpty().take(ConnectLimits.MAX_TEXT),
            artist = m.artist?.toString().orEmpty().take(ConnectLimits.MAX_TEXT),
            album = m.albumTitle?.toString().orEmpty().take(ConnectLimits.MAX_TEXT),
            durationMs = s.durationMs, positionMs = PlayerConnection.position(),
            playing = s.isPlaying, buffering = s.buffering, shuffle = s.shuffle, repeat = s.repeat,
            index = s.index, count = s.count, volume = volumePercent(), volumeFixed = volumeFixed(),
            coverKey = MediaIds.coverArt(s.item).orEmpty(), hasTrack = s.item != null,
        )
    }

    private fun queueView(): ConnectBody.QueueView {
        val total = PlayerConnection.state.value.count
        val current = PlayerConnection.state.value.index
        val (from, count) = RemoteQueue.window(total, current)
        val items = (from until from + count).mapNotNull { PlayerConnection.itemAt(it) }.map { item ->
            QueueEntry(
                id = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty().take(ConnectLimits.MAX_TEXT),
                artist = item.mediaMetadata.artist?.toString().orEmpty().take(ConnectLimits.MAX_TEXT),
                durationMs = item.mediaMetadata.durationMs ?: 0L,
            )
        }
        return ConnectBody.QueueView(current, total, from, items)
    }

    /** The playing item's cover, small, as the phone shows it; null when it has none (the phone draws the generated one). */
    private suspend fun coverFor(item: MediaItem?): ConnectBody.CoverPicture? {
        item ?: return null
        val key = MediaIds.coverArt(item) ?: return null
        lastCover?.takeIf { it.key == key }?.let { return it }
        val bytes = withContext(Dispatchers.IO) { runCatching { ArtworkBytes.load(item) }.getOrNull()?.let(::small) } ?: return null
        return ConnectBody.CoverPicture(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).also { lastCover = it }
    }

    /** [source] as a JPEG of at most [COVER_PX] a side and [COVER_BYTES], so a TV stick and the wire carry it easily. */
    private fun small(source: ByteArray): ByteArray? {
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
            val longest = maxOf(decoded.width, decoded.height)
            scaled = if (longest <= COVER_PX) decoded else {
                val f = COVER_PX.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt().coerceAtLeast(1), (decoded.height * f).toInt().coerceAtLeast(1), true)
            }
            for (quality in intArrayOf(80, 60, 40)) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                if (out.size() <= COVER_BYTES) return out.toByteArray()
            }
            null
        } catch (e: Throwable) {
            Logger.w(TAG, "a cover could not be prepared for the phone", e)
            null
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded?.recycle()
        }
    }

    // ---- ConnectHandler: called on the server's threads.

    override fun snapshot(): List<ConnectBody> = runBlocking {
        withTimeoutOrNull(SNAPSHOT_MS) {
            withContext(Dispatchers.Main) {
                // The cover follows on its own once it is ready: the phone has the words at once.
                scope.launch { server?.broadcast(coverFor(PlayerConnection.state.value.item) ?: return@launch) }
                listOf(nowPlaying(), queueView())
            }
        }.orEmpty()
    }

    override fun onCommand(from: String, body: ConnectBody) {
        scope.launch { apply(from, body) }
    }

    private suspend fun apply(from: String, body: ConnectBody) {
        when (body) {
            is ConnectBody.Command -> when (body.action) {
                Transport.Play -> PlayerConnection.resume()
                Transport.Pause -> PlayerConnection.pause()
                Transport.Toggle -> PlayerConnection.toggle()
                Transport.Next -> PlayerConnection.next()
                Transport.Previous -> PlayerConnection.previous()
            }
            is ConnectBody.Seek -> PlayerConnection.seekTo(body.positionMs)
            is ConnectBody.Shuffle -> PlayerConnection.setShuffle(body.on)
            is ConnectBody.Repeat -> PlayerConnection.setRepeat(body.mode)
            is ConnectBody.Volume -> setVolume(body.percent)
            is ConnectBody.Jump ->
                if (RemoteQueue.stillThere(body.index, body.id, PlayerConnection::mediaIdAt)) PlayerConnection.jumpAndPlay(body.index)
                else server?.sendTo(from, queueView())
            is ConnectBody.Remove ->
                if (RemoteQueue.stillThere(body.index, body.id, PlayerConnection::mediaIdAt)) PlayerConnection.remove(body.index)
                else server?.sendTo(from, queueView())
            is ConnectBody.Send -> send(from, body)
            else -> Unit
        }
    }

    private fun setVolume(percent: Int) {
        val am = audio() ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, 0) }
    }

    /**
     * Looks each reference up in this TV's own library (the exact file, else the same song by its
     * key) and plays or queues what it finds. What it does not have is named in the answer and never
     * fetched; a library that is not ready (not signed in, still opening) has nothing yet.
     */
    private suspend fun send(from: String, body: ConnectBody.Send) {
        val found = ArrayList<Pair<Int, MediaItem>>()
        val missing = ArrayList<TrackRef>()
        if (Library.ready.value) {
            val resolved = withContext(Dispatchers.IO) {
                withTimeoutOrNull(RESOLVE_MS) {
                    body.items.map { ref -> runCatching { PlaybackService.resolve(SavedQueue.Item(ref.chatId, ref.messageId, ref.songKey)) }.getOrNull() }
                }
            }
            body.items.forEachIndexed { i, ref ->
                val item = resolved?.getOrNull(i)
                if (item != null) found += i to item else missing += ref
            }
        } else {
            missing += body.items
        }
        if (found.isNotEmpty()) {
            val items = found.map { it.second }
            when (body.how) {
                SendHow.Play -> PlayerConnection.playItems(items, found.indexOfFirst { it.first >= body.start }.let { if (it < 0) 0 else it }, body.shuffle)
                SendHow.Next -> PlayerConnection.enqueueItems(items, next = true)
                SendHow.Add -> PlayerConnection.enqueueItems(items, next = false)
            }
        }
        server?.sendTo(from, ConnectBody.Sent(body.how, found.size, RemoteQueue.missingNames(missing), missing.size))
    }

    private const val SNAPSHOT_MS = 3_000L
    private const val RESOLVE_MS = 20_000L
}
