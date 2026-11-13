package com.tmplayer.music.desktop.player

import com.tmplayer.music.data.Failures
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.Track
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.SavedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openani.mediamp.source.MediaData
import kotlin.random.Random

/** Where a queue entry's bytes and facts come from: TDLib and the index in the app, fakes in the tests. */
interface TrackSource {
    /** The entry for a saved queue item, or null when its track (and its song's other versions) are gone. */
    suspend fun resolve(item: SavedQueue.Item): QueueEntry?

    /** The media to hand the engine for [entry]; throws with a sentence for the user when it cannot. */
    suspend fun open(entry: QueueEntry): MediaData

    /** A track started playing: Recently played. */
    suspend fun played(entry: QueueEntry, atSec: Long) {}

    /** Where [entry] was left, for Speed dial; a spot not worth coming back to clears it. */
    suspend fun keep(entry: QueueEntry, positionMs: Long, durationMs: Long, atMs: Long) {}

    /** [entry] played to its end: its card starts from the top next time. */
    suspend fun finished(entry: QueueEntry) {}

    /** The tracks about to play, so their tags are read before the rest of the library's. */
    fun readAhead(entries: List<QueueEntry>) {}
}

/** The saved queue's file: read whole, written whole. */
interface QueueStore {
    fun read(): String?
    fun write(text: String)
}

/** What the bar, the queue panel and the lists read. Position is separate ([PlayerController.position]). */
data class PlayerUi(
    val queue: PlayQueue = PlayQueue(),
    val playing: Boolean = false,
    /** Waiting for the first bytes of a track or for more. */
    val buffering: Boolean = false,
    /** The engine's duration once known, else 0 (the bar uses the entry's own until then). */
    val durationMs: Long = 0,
    /** A sentence for the user: the last failure, with Try again. Null while all is well. */
    val error: String? = null,
    /** Set when the queue was restored and is waiting, paused, for the first play. */
    val restored: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
) {
    val current: QueueEntry? get() = queue.currentEntry
    val shuffle: Boolean get() = queue.shuffle
    val repeat: RepeatMode get() = queue.repeat
}

/**
 * The player: a queue of tracks over one [AudioEngine], the way the phone's PlaybackService is one
 * queue over ExoPlayer. Nothing autoplays: a restored queue waits, paused, and its track is not
 * even opened (no download) until the first Play. The queue is saved whole and restored whole
 * ([SavedQueue]), the playing spot is kept for Speed dial, a track that cannot play is skipped
 * (three in a row at most) with the reason said, and every job runs under [JobFailures].
 *
 * All changes go through one serial dispatcher, so two clicks never interleave half a change.
 */
class PlayerController(
    private val source: TrackSource,
    private val store: QueueStore,
    private val engineFactory: () -> AudioEngine,
    initialVolume: Int = 100,
    initialMuted: Boolean = false,
    private val onVolume: (volume: Int, muted: Boolean) -> Unit = { _, _ -> },
    private val onPlayingChanged: (Boolean) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val saveDelayMs: Long = SAVE_DELAY_MS,
    private val checkpointMs: Long = CHECKPOINT_MS,
) {
    private val scope = JobFailures.scope("Player", dispatcher)
    private val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)

    private val _ui = MutableStateFlow(PlayerUi(volume = initialVolume.coerceIn(0, 100), muted = initialMuted))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _position = MutableStateFlow(0L)

    /** Where the playing track is, in milliseconds; about four updates a second while it plays. */
    val position: StateFlow<Long> = _position.asStateFlow()

    private var engine: AudioEngine? = null
    private var watching: Job? = null
    private var opening: Job? = null
    private var saving: Job? = null
    private var checkpointing: Job? = null

    /** The entry the engine has loaded (its uid), or null: nothing is loaded after a restore or a stop. */
    private var loaded: Long? = null

    /** Where the next open starts: a restored position, or a seek made while nothing is loaded. */
    private var pendingStartMs = 0L

    /** Whether the track being opened should start playing when it is loaded (Pause while it loads clears it). */
    @Volatile
    private var wantPlay = false

    private var skipsInARow = 0
    private var lastPlayed: Long? = null
    private var closed = false

    // Queue changes

    /** Plays [songs] from [start]; a long list is cut around the start to what a saved queue holds. */
    fun play(songs: List<Song>, start: Int, shuffle: Boolean = false) =
        playEntries(songs.map(QueueEntry::of), start, shuffle)

    /** Plays [tracks] (a chat page's own files) from [start]. */
    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean = false) =
        playEntries(tracks.map { QueueEntry.of(it) }, start, shuffle)

    fun playEntries(entries: List<QueueEntry>, start: Int, shuffle: Boolean = false) = run {
        if (entries.isEmpty()) return@run
        val queue = PlayQueue.of(entries, start, shuffle, _ui.value.queue.repeat, random)
        // With shuffle the song picked still plays first; the rest follow in a random order.
        keepSpot()
        _ui.update { it.copy(queue = queue, restored = false, error = null) }
        skipsInARow = 0
        openCurrent(startAtMs = 0, playNow = true)
    }

    /** Plays one exact version of [song], from the Versions sheet. */
    fun playVersion(song: Song, track: Track) = playEntries(listOf(QueueEntry.of(track, song.groupKey)), 0)

    fun playNext(songs: List<Song>) = run {
        if (songs.isEmpty()) return@run
        val empty = _ui.value.queue.isEmpty
        _ui.update { it.copy(queue = it.queue.playNext(songs.map(QueueEntry::of))) }
        if (empty) openCurrent(0, playNow = true) else saveSoon()
    }

    fun addToQueue(songs: List<Song>) = run {
        if (songs.isEmpty()) return@run
        val empty = _ui.value.queue.isEmpty
        _ui.update { it.copy(queue = it.queue.append(songs.map(QueueEntry::of))) }
        if (empty) openCurrent(0, playNow = true) else saveSoon()
    }

    fun skipTo(index: Int) = run {
        if (index !in _ui.value.queue.entries.indices) return@run
        keepSpot()
        _ui.update { it.copy(queue = it.queue.jumpTo(index), error = null, restored = false) }
        skipsInARow = 0
        openCurrent(0, playNow = true)
    }

    fun remove(index: Int) = run {
        val removal = _ui.value.queue.remove(index)
        if (removal.queue === _ui.value.queue) return@run
        if (removal.currentChanged) keepSpot()
        _ui.update { it.copy(queue = removal.queue) }
        when {
            removal.queue.isEmpty -> stopEngine()
            removal.currentChanged -> openCurrent(0, playNow = _ui.value.playing || _ui.value.buffering)
            else -> saveSoon()
        }
        saveSoon()
    }

    fun move(from: Int, to: Int) = run {
        _ui.update { it.copy(queue = it.queue.move(from, to)) }
        saveSoon()
    }

    /** Empties the queue and stops. */
    fun clear() = run {
        keepSpot()
        stopEngine()
        _ui.update { it.copy(queue = it.queue.copy(entries = emptyList(), current = 0, order = emptyList()), restored = false, error = null) }
        saveNow()
    }

    fun setShuffle(on: Boolean) = run {
        _ui.update { it.copy(queue = it.queue.withShuffle(on, random)) }
        saveSoon()
    }

    fun cycleRepeat() = run {
        _ui.update { it.copy(queue = it.queue.withRepeat(it.queue.repeat.next())) }
        saveSoon()
    }

    // Transport

    fun togglePlay() = run {
        val ui = _ui.value
        if (ui.queue.isEmpty) return@run
        if (ui.playing || ui.buffering && loaded != null) pauseNow() else playNow()
    }

    fun play() = run { if (!_ui.value.queue.isEmpty && !_ui.value.playing) playNow() }

    fun pause() = run { if (_ui.value.playing || _ui.value.buffering) pauseNow() }

    private fun playNow() {
        val current = _ui.value.queue.currentEntry ?: return
        _ui.update { it.copy(error = null, restored = false) }
        wantPlay = true
        if (opening?.isActive == true) {
            // Still loading: it starts as soon as it is loaded.
            _ui.update { it.copy(buffering = true) }
        } else if (loaded == current.uid && engine != null) {
            engine?.play()
            _ui.update { it.copy(playing = true) }
        } else {
            openCurrent(pendingStartMs, playNow = true)
        }
    }

    private fun pauseNow() {
        wantPlay = false
        engine?.pause()
        _ui.update { it.copy(playing = false, buffering = false) }
        keepSpot()
        saveNow()
    }

    /** Try again after an error: opens the current track again from where it was. */
    fun retry() = run {
        if (_ui.value.queue.isEmpty) return@run
        skipsInARow = 0
        _ui.update { it.copy(error = null) }
        openCurrent(pendingStartMs, playNow = true)
    }

    fun next() = run {
        val step = _ui.value.queue.nextStep(auto = false) ?: return@run
        advance(step, playNow = true)
    }

    fun previous() = run {
        val step = _ui.value.queue.previousStep(_position.value) ?: return@run
        advance(step, playNow = true)
    }

    private fun advance(step: QueueStep, playNow: Boolean) {
        keepSpot()
        if (step.restart && _ui.value.error == null) {
            if (loaded == _ui.value.queue.currentEntry?.uid && engine != null) {
                engine?.seekTo(0)
                if (playNow && !_ui.value.playing) engine?.play()
                _ui.update { it.copy(playing = playNow || it.playing) }
            } else {
                // Nothing loaded yet (a restored queue): the next Play starts from the top.
                pendingStartMs = 0
            }
            _position.value = 0
            return
        }
        _ui.update { it.copy(queue = it.queue.jumpTo(step.index), error = null, restored = false) }
        skipsInARow = 0
        openCurrent(0, playNow)
    }

    fun seekTo(positionMs: Long) = run {
        val target = positionMs.coerceAtLeast(0)
        val current = _ui.value.queue.currentEntry ?: return@run
        if (loaded == current.uid && engine != null) {
            engine?.seekTo(target)
        } else {
            // Nothing loaded yet (a restored queue): the next Play starts here.
            pendingStartMs = target
        }
        _position.value = target
        saveSoon()
    }

    fun seekBy(deltaMs: Long) = run {
        val duration = knownDuration()
        val target = (_position.value + deltaMs).let { if (duration > 0) it.coerceIn(0, duration) else it.coerceAtLeast(0) }
        val current = _ui.value.queue.currentEntry ?: return@run
        if (loaded == current.uid && engine != null) engine?.seekTo(target) else pendingStartMs = target
        _position.value = target
        saveSoon()
    }

    fun setVolume(percent: Int) = run {
        val v = percent.coerceIn(0, 100)
        engineOrNull()?.setVolume(v)
        // Turning the volume up is a way out of mute, as in every other player.
        if (v > 0 && _ui.value.muted) engineOrNull()?.setMuted(false)
        _ui.update { it.copy(volume = v, muted = if (v > 0) false else it.muted) }
        onVolume(_ui.value.volume, _ui.value.muted)
    }

    fun toggleMute() = run {
        val muted = !_ui.value.muted
        engineOrNull()?.setMuted(muted)
        _ui.update { it.copy(muted = muted) }
        onVolume(_ui.value.volume, muted)
    }

    private fun knownDuration(): Long = _ui.value.durationMs.takeIf { it > 0 } ?: _ui.value.current?.durationMs ?: 0L

    // Restore and close

    /** The saved queue, back in the player, paused at its position. Nothing plays and nothing downloads. */
    fun restore() = run {
        if (!_ui.value.queue.isEmpty) return@run
        val saved = SavedQueue.decode(runCatching { store.read() }.getOrNull()) ?: return@run
        val entries = saved.items.map { item ->
            try {
                source.resolve(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "a saved queue item could not be resolved", e)
                null
            }
        }
        val queue = PlayQueue.restored(saved, entries, random)
        if (queue.isEmpty || !_ui.value.queue.isEmpty) return@run
        pendingStartMs = if (entries.getOrNull(saved.current) != null) saved.positionMs else 0L
        _position.value = pendingStartMs
        _ui.update { it.copy(queue = queue, restored = true) }
    }

    /** Saves the queue and lets the engine go; the app is closing. */
    fun close() {
        closed = true
        runCatching {
            // Called from the window's close, on the UI thread: the write is small and must finish.
            keepSpotBlocking()
            writeQueue()
        }
        watching?.cancel()
        runCatching { engine?.close() }
        engine = null
    }

    // The engine

    private fun engineOrNull(): AudioEngine? = engine

    private fun ensureEngine(): AudioEngine {
        engine?.let { return it }
        val created = engineFactory()
        created.setVolume(_ui.value.volume)
        created.setMuted(_ui.value.muted)
        engine = created
        watching = scope.launch(serial) { watch(created) }
        return created
    }

    private suspend fun watch(e: AudioEngine) {
        var wasEnded = false
        e.state.collect { s ->
            if (closed) return@collect
            // Only what belongs to the track loaded now: the engine says nothing of an old one.
            if (loaded == null) return@collect
            _position.value = s.positionMs
            // While a track is still being opened the open job owns playing and buffering.
            if (!s.opened) return@collect
            val before = _ui.value.playing
            _ui.update {
                it.copy(
                    playing = s.playing,
                    buffering = s.buffering && s.playing,
                    durationMs = s.durationMs,
                    volume = s.volume,
                    muted = s.muted,
                )
            }
            if (before != _ui.value.playing) onPlayingChanged(_ui.value.playing)
            // A failure part way through a track: the same skip as one that fails to open.
            if (s.error != null) {
                onFailed(s.error)
                return@collect
            }
            if (s.ended && !wasEnded) onEnded()
            wasEnded = s.ended
        }
    }

    private fun onEnded() {
        val entry = _ui.value.current ?: return
        scope.launch(serial) { runCatching { source.finished(entry) } }
        val step = _ui.value.queue.nextStep(auto = true)
        if (step == null) {
            // The end of the queue with repeat off: stopped on the last track, paused.
            _ui.update { it.copy(playing = false, buffering = false) }
            loaded = null
            pendingStartMs = 0
            lastPlayed = null
            saveNow()
            return
        }
        lastPlayed = null
        _ui.update { it.copy(queue = it.queue.jumpTo(step.index)) }
        skipsInARow = 0
        openCurrent(0, playNow = true)
    }

    private fun onFailed(reason: String) {
        val entry = _ui.value.current ?: return
        Logger.w(TAG, "playback of a track failed: $reason")
        fail(entry, reason)
    }

    /** A track that cannot play: the next is tried, three in a row at most, and the reason is said. */
    private fun fail(entry: QueueEntry, reason: String) {
        val queue = _ui.value.queue
        val step = queue.nextStep(auto = false)?.takeIf { !it.restart }
        val message = L.playerSkipped(entry.title.ifBlank { L.libraryUnknownArtist }, reason)
        loaded = null
        if (step != null && skipsInARow < MAX_SKIPS) {
            skipsInARow++
            _ui.update { it.copy(queue = it.queue.jumpTo(step.index), error = message, playing = false, buffering = true) }
            openCurrent(0, playNow = true, keepError = true)
        } else {
            _ui.update { it.copy(error = message, playing = false, buffering = false) }
            onPlayingChanged(false)
            saveSoon()
        }
    }

    /**
     * Opens the current entry in the engine at [startAtMs]. Cancels an open still on its way, so
     * a fast run of Next presses opens the last one only.
     */
    private fun openCurrent(startAtMs: Long, playNow: Boolean, keepError: Boolean = false) {
        val entry = _ui.value.queue.currentEntry ?: return
        opening?.cancel()
        pendingStartMs = startAtMs
        wantPlay = playNow
        loaded = null
        _position.value = startAtMs
        _ui.update { it.copy(buffering = playNow, durationMs = 0, error = if (keepError) it.error else null) }
        val queueNow = _ui.value.queue
        opening = scope.launch(serial) {
            var data: MediaData? = null
            try {
                val e = ensureEngine()
                // What was playing goes quiet at once; the new stream replaces it when it is loaded.
                e.pause()
                loaded = entry.uid
                runCatching { source.readAhead(upcoming(queueNow)) }
                val media = source.open(entry)
                data = media
                if (_ui.value.current?.uid != entry.uid) {
                    runCatching { media.close() }
                    return@launch
                }
                e.open(media, startAtMs, playNow)
                val s = e.state.value
                if (s.error != null) {
                    onOpenFailed(entry, s.error)
                    return@launch
                }
                // Pause pressed while it loaded: it opens, and waits.
                val playing = wantPlay
                if (!playing && playNow) e.pause()
                pendingStartMs = 0
                _ui.update { it.copy(playing = playing, buffering = false, restored = false) }
                if (playing) {
                    onPlayingChanged(true)
                    played(entry)
                    startCheckpoints()
                }
                saveSoon()
            } catch (e: CancellationException) {
                runCatching { data?.close() }
                throw e
            } catch (e: EngineUnavailable) {
                loaded = null
                _ui.update { it.copy(error = e.message ?: L.desktopEngineMissing, playing = false, buffering = false) }
            } catch (e: Exception) {
                Logger.w(TAG, "could not open a track", e)
                onOpenFailed(entry, Failures.humanise(e))
            }
        }
    }

    private fun onOpenFailed(entry: QueueEntry, reason: String) {
        if (_ui.value.current?.uid != entry.uid) return
        fail(entry, reason)
    }

    private fun upcoming(queue: PlayQueue): List<QueueEntry> {
        val out = ArrayList<QueueEntry>()
        var q = queue
        repeat(UPCOMING) {
            out += q.currentEntry ?: return@repeat
            val step = q.nextStep(auto = false)?.takeIf { !it.restart } ?: return out
            q = q.jumpTo(step.index)
        }
        return out
    }

    private fun stopEngine() {
        opening?.cancel()
        wantPlay = false
        engine?.stop()
        loaded = null
        pendingStartMs = 0
        lastPlayed = null
        checkpointing?.cancel()
        _position.value = 0
        _ui.update { it.copy(playing = false, buffering = false, durationMs = 0) }
        onPlayingChanged(false)
    }

    // Plays, spots and the saved queue

    private fun played(entry: QueueEntry) {
        if (lastPlayed == entry.uid) return
        lastPlayed = entry.uid
        scope.launch(serial) { runCatching { source.played(entry, clock() / 1000) } }
    }

    private fun startCheckpoints() {
        checkpointing?.cancel()
        checkpointing = scope.launch(serial) {
            while (true) {
                delay(checkpointMs)
                keepSpot()
                saveNow()
            }
        }
    }

    /** Keeps where the playing track is, for Speed dial. */
    private fun keepSpot() {
        val entry = _ui.value.current ?: return
        if (loaded != entry.uid) return
        val position = _position.value
        val duration = knownDuration()
        val at = clock()
        scope.launch(serial) { runCatching { source.keep(entry, position, duration, at) } }
    }

    private fun keepSpotBlocking() {
        val entry = _ui.value.current ?: return
        if (loaded != entry.uid) return
        val position = _position.value
        val duration = knownDuration()
        val at = clock()
        kotlinx.coroutines.runBlocking { runCatching { source.keep(entry, position, duration, at) } }
    }

    private fun saveSoon() {
        if (closed) return
        saving?.cancel()
        saving = scope.launch(serial) {
            delay(saveDelayMs)
            writeQueue()
        }
    }

    private fun saveNow() {
        saving?.cancel()
        writeQueue()
    }

    private fun writeQueue() {
        val ui = _ui.value
        val position = if (loaded == ui.current?.uid) _position.value else pendingStartMs.coerceAtLeast(_position.value)
        val saved = ui.queue.toSaved(position)
        runCatching { store.write(saved?.encode().orEmpty()) }
            .onFailure { Logger.w(TAG, "saving the queue failed", it) }
    }

    /** Runs [block] on the serial dispatcher, under the job failure handler. */
    private fun run(block: suspend () -> Unit) {
        if (closed) return
        scope.launch(serial) { block() }
    }

    companion object {
        private const val TAG = "PlayerController"
        const val SAVE_DELAY_MS = 500L
        const val CHECKPOINT_MS = 30_000L
        const val MAX_SKIPS = 3
        const val UPCOMING = 5
    }
}
