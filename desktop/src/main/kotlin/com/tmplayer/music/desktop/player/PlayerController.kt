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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    /** The loudness factor of [entry] from its ReplayGain tags (the track's own, never the position's); 1 for none. */
    suspend fun gainOf(entry: QueueEntry): Float = 1f
}

/** What the sleep timer is set to (the phone's SleepTimer, without Android's clock). */
sealed interface SleepSetting {
    data object Off : SleepSetting

    /** Pause when the clock reaches [endsAtMs]. */
    data class At(val endsAtMs: Long) : SleepSetting

    /** Pause when the track playing now ends, also with repeat one. */
    data object EndOfTrack : SleepSetting
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
    private val sleepFadeMs: Long = SLEEP_FADE_MS,
    private val prefetchLeadMs: Long = PREFETCH_LEAD_MS,
) {
    private val scope = JobFailures.scope("Player", dispatcher)
    private val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)

    private val _ui = MutableStateFlow(PlayerUi(volume = initialVolume.coerceIn(0, 100), muted = initialMuted))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _position = MutableStateFlow(0L)

    /** Where the playing track is, in milliseconds; about four updates a second while it plays. */
    val position: StateFlow<Long> = _position.asStateFlow()

    private var engine: AudioEngine? = null

    private val _engine = MutableStateFlow<AudioEngine?>(null)

    /** The engine once one exists (the first play), for the shell to hold its surface ([AudioSurface]). */
    val engineFlow: StateFlow<AudioEngine?> = _engine.asStateFlow()
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

    // Sound: the user's choices, and what the playing track's loudness asks of them.
    private var soundSettings = SoundSettings.NEUTRAL
    private var loadedGain = 1f

    private val _capabilities = MutableStateFlow<EngineCapabilities?>(null)

    /** What the engine can honour; null until an engine exists (then every control is offered). */
    val capabilities: StateFlow<EngineCapabilities?> = _capabilities.asStateFlow()

    // The sleep timer.
    private val _sleep = MutableStateFlow<SleepSetting>(SleepSetting.Off)
    val sleep: StateFlow<SleepSetting> = _sleep.asStateFlow()
    private var sleeping: Job? = null

    // The next track, opened ahead of its turn so the change has no wait for bytes.
    private class Prefetched(val uid: Long, val index: Int, val media: MediaData, val queued: Boolean)

    private var prefetched: Prefetched? = null
    private var prefetching: Job? = null
    private var prefetchTried: Long? = null
    private var seenAdvance = 0
    private var endedHandled: Long? = null

    // Queue changes

    /** Plays [songs] from [start]; a long list is cut around the start to what a saved queue holds. */
    fun play(songs: List<Song>, start: Int, shuffle: Boolean = false, positionMs: Long = 0) =
        playEntries(songs.map(QueueEntry::of), start, shuffle, positionMs)

    /** Plays [tracks] (a chat page's own files) from [start]. */
    fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean = false, positionMs: Long = 0) =
        playEntries(tracks.map { QueueEntry.of(it) }, start, shuffle, positionMs)

    /** Plays [entries] from [start], the first one from [positionMs] (a Speed dial card resumes where it was left). */
    fun playEntries(entries: List<QueueEntry>, start: Int, shuffle: Boolean = false, positionMs: Long = 0) = run {
        if (entries.isEmpty()) return@run
        val queue = PlayQueue.of(entries, start, shuffle, _ui.value.queue.repeat, random)
        // With shuffle the song picked still plays first; the rest follow in a random order.
        keepSpot()
        dropPrefetch()
        _ui.update { it.copy(queue = queue, restored = false, error = null) }
        skipsInARow = 0
        openCurrent(startAtMs = positionMs.coerceAtLeast(0), playNow = true)
    }

    /**
     * Skips to [id] when it is in the queue already, from [positionMs]; false when it is not (the caller queues it).
     * The same file playing now is just resumed or paused by the caller, so it is not reopened here.
     */
    fun resumeInQueue(id: com.tmplayer.music.library.TrackId, positionMs: Long): Boolean {
        val index = _ui.value.queue.entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        run {
            val at = _ui.value.queue.entries.indexOfFirst { it.id == id }
            if (at < 0) return@run
            keepSpot()
            dropPrefetch()
            _ui.update { it.copy(queue = it.queue.jumpTo(at), error = null, restored = false) }
            skipsInARow = 0
            openCurrent(positionMs.coerceAtLeast(0), playNow = true)
        }
        return true
    }

    /** Plays one exact version of [song], from the Versions sheet. */
    fun playVersion(song: Song, track: Track) = playEntries(listOf(QueueEntry.of(track, song.groupKey)), 0)

    fun playNext(songs: List<Song>) = run {
        if (songs.isEmpty()) return@run
        val empty = _ui.value.queue.isEmpty
        dropPrefetch()
        _ui.update { it.copy(queue = it.queue.playNext(songs.map(QueueEntry::of))) }
        if (empty) openCurrent(0, playNow = true) else saveSoon()
    }

    fun addToQueue(songs: List<Song>) = run {
        if (songs.isEmpty()) return@run
        val empty = _ui.value.queue.isEmpty
        dropPrefetch()
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
        dropPrefetch()
        _ui.update { it.copy(queue = removal.queue) }
        when {
            removal.queue.isEmpty -> stopEngine()
            removal.currentChanged -> openCurrent(0, playNow = _ui.value.playing || _ui.value.buffering)
            else -> saveSoon()
        }
        saveSoon()
    }

    fun move(from: Int, to: Int) = run {
        dropPrefetch()
        _ui.update { it.copy(queue = it.queue.move(from, to)) }
        saveSoon()
    }

    /** Empties the queue and stops. */
    fun clear() = run {
        keepSpot()
        dropPrefetch()
        stopEngine()
        _ui.update { it.copy(queue = it.queue.copy(entries = emptyList(), current = 0, order = emptyList()), restored = false, error = null) }
        saveNow()
    }

    fun setShuffle(on: Boolean) = run {
        dropPrefetch()
        _ui.update { it.copy(queue = it.queue.withShuffle(on, random)) }
        saveSoon()
    }

    fun cycleRepeat() = run {
        dropPrefetch()
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

    // Sound

    /** The sound choices (speed, equalizer, skip silence, normalise): held, and given to the engine now and at every open. */
    fun setSound(settings: SoundSettings) = run {
        soundSettings = settings
        engine?.setSound(settings, if (settings.normalise) loadedGain else 1f)
    }

    // Sleep timer

    /** Pauses in [minutes] (1 to a day), the last seconds fading the volume down. */
    fun sleepAfter(minutes: Int) = sleepAfterMs(minutes.coerceIn(1, 24 * 60) * 60_000L)

    internal fun sleepAfterMs(ms: Long) = run {
        sleeping?.cancel()
        val endsAt = clock() + ms
        _sleep.value = SleepSetting.At(endsAt)
        sleeping = scope.launch(serial) { sleepUntil(endsAt) }
    }

    /** Pauses when the track playing now ends (also with repeat one); nothing is queued after it. */
    fun sleepAtEndOfTrack() = run {
        sleeping?.cancel()
        dropPrefetch()
        _sleep.value = SleepSetting.EndOfTrack
    }

    fun sleepOff() = run {
        sleeping?.cancel()
        _sleep.value = SleepSetting.Off
    }

    private suspend fun sleepUntil(endsAt: Long) {
        val wait = endsAt - clock() - sleepFadeMs
        if (wait > 0) delay(wait)
        val e = engine
        val original = _ui.value.volume
        val fade = e != null && !_ui.value.muted && _ui.value.playing && sleepFadeMs > 0
        try {
            if (fade) {
                val steps = 20
                for (i in 1..steps) {
                    e?.setVolume(original * (steps - i) / steps)
                    delay((sleepFadeMs / steps).coerceAtLeast(1))
                }
            } else {
                delay((endsAt - clock()).coerceAtLeast(0))
            }
            if (_ui.value.playing || _ui.value.buffering) pauseNow()
        } finally {
            // Also when the timer is turned off part way through the fade.
            if (fade) e?.setVolume(original)
        }
        _sleep.value = SleepSetting.Off
    }

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
            // Called from the window's close, on the UI thread: the queue is a small file and goes
            // first; the listening spot is a database write and is given a short time (see below).
            writeQueue()
            keepSpotBlocking()
        }
        watching?.cancel()
        runCatching { engine?.close() }
        engine = null
        _engine.value = null
    }

    // The engine

    private fun engineOrNull(): AudioEngine? = engine

    private fun ensureEngine(): AudioEngine {
        engine?.let { return it }
        val created = engineFactory()
        created.setVolume(_ui.value.volume)
        created.setMuted(_ui.value.muted)
        created.setSound(soundSettings, 1f)
        _capabilities.value = created.capabilities
        engine = created
        _engine.value = created
        watching = scope.launch(serial) { watch(created) }
        return created
    }

    private suspend fun watch(e: AudioEngine) {
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
            if (s.advanced != seenAdvance) {
                seenAdvance = s.advanced
                onEngineAdvanced()
            }
            // Once per loaded track: a state that stays "ended" while the next one opens is not a second end.
            if (s.ended && endedHandled != loaded) {
                endedHandled = loaded
                onEnded()
            }
            maybePrefetch(s)
        }
    }

    private fun onEnded() {
        val entry = _ui.value.current ?: return
        scope.launch(serial) { runCatching { source.finished(entry) } }
        if (_sleep.value == SleepSetting.EndOfTrack) {
            // The sleep timer: the track played out, and the next one waits paused.
            _sleep.value = SleepSetting.Off
            val queue = _ui.value.queue
            val step = queue.nextStep(auto = true)?.takeIf { !it.restart }
            if (step != null) _ui.update { it.copy(queue = it.queue.jumpTo(step.index)) }
            _ui.update { it.copy(playing = false, buffering = false) }
            loaded = null
            pendingStartMs = 0
            lastPlayed = null
            dropPrefetch()
            onPlayingChanged(false)
            saveNow()
            return
        }
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
            // Once the engine has the stream it closes it; until then every way out of here does.
            var handedToEngine = false
            try {
                val e = ensureEngine()
                // What was playing goes quiet at once; the new stream replaces it when it is loaded.
                e.pause()
                runCatching { source.readAhead(upcoming(queueNow)) }
                // The next track, opened ahead of its turn, is taken as it is; any other is dropped.
                val ahead = prefetched?.takeIf { it.uid == entry.uid && !it.queued && startAtMs == 0L }
                if (ahead != null) prefetched = null else dropPrefetch()
                val media = ahead?.media ?: source.open(entry)
                data = media
                val gain = if (soundSettings.normalise) runCatching { source.gainOf(entry) }.getOrDefault(1f) else 1f
                loadedGain = gain
                e.setSound(soundSettings, gain)
                // From here the engine's state is this track's: its end is a new end.
                endedHandled = null
                loaded = entry.uid
                if (_ui.value.current?.uid != entry.uid) return@launch
                e.open(media, startAtMs, playNow)
                handedToEngine = true
                // The queue moved on while it loaded (a newer open, or it was emptied): that has the say now.
                if (!isActive || _ui.value.current?.uid != entry.uid) {
                    if (_ui.value.queue.isEmpty) e.stop()
                    return@launch
                }
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
                throw e
            } catch (e: EngineUnavailable) {
                loaded = null
                _ui.update { it.copy(error = e.message ?: L.desktopEngineMissing, playing = false, buffering = false) }
            } catch (e: Exception) {
                Logger.w(TAG, "could not open a track", e)
                onOpenFailed(entry, Failures.humanise(e))
            } finally {
                if (!handedToEngine) runCatching { data?.close() }
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

    // Gapless: the next track opened before this one ends

    private fun dropPrefetch() {
        prefetching?.cancel()
        prefetching = null
        prefetchTried = null
        val dropped = prefetched
        prefetched = null
        if (dropped != null) {
            if (dropped.queued) engine?.clearQueuedNext()
            runCatching { dropped.media.close() }
        }
    }

    /** Starts the next track's stream once the playing one is [prefetchLeadMs] from its end. */
    private fun maybePrefetch(s: EngineState) {
        val entry = _ui.value.current ?: return
        if (loaded != entry.uid || !s.opened || !s.playing || s.ended || s.error != null) return
        if (prefetched != null || prefetching?.isActive == true || prefetchTried == entry.uid) return
        if (_sleep.value == SleepSetting.EndOfTrack) return
        val duration = s.durationMs.takeIf { it > 0 } ?: return
        if (duration - s.positionMs > prefetchLeadMs) return
        val step = _ui.value.queue.nextStep(auto = true)?.takeIf { !it.restart } ?: return
        val next = _ui.value.queue.entries.getOrNull(step.index) ?: return
        prefetchTried = entry.uid
        val forUid = entry.uid
        prefetching = scope.launch(serial) {
            try {
                val media = source.open(next)
                if (_ui.value.current?.uid != forUid || prefetched != null) {
                    runCatching { media.close() }
                    return@launch
                }
                val queued = engine?.let { runCatching { it.queueNext(media) }.getOrDefault(false) } ?: false
                prefetched = Prefetched(next.uid, step.index, media, queued)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not worth a message: the track opens the usual way when its turn comes.
                Logger.i(TAG, "the next track could not be opened ahead: ${e.message}")
            }
        }
    }

    /** The engine moved on to the track it was given ([AudioEngine.queueNext]) with no gap: the queue follows it. */
    private fun onEngineAdvanced() {
        val ahead = prefetched?.takeIf { it.queued } ?: return
        val before = _ui.value.current
        before?.let { entry -> scope.launch(serial) { runCatching { source.finished(entry) } } }
        prefetched = null
        prefetchTried = null
        lastPlayed = null
        skipsInARow = 0
        _ui.update { it.copy(queue = it.queue.jumpTo(ahead.index)) }
        val now = _ui.value.current ?: return
        loaded = now.uid
        endedHandled = null
        _position.value = 0
        runCatching { source.readAhead(upcoming(_ui.value.queue)) }
        scope.launch(serial) {
            loadedGain = if (soundSettings.normalise) runCatching { source.gainOf(now) }.getOrDefault(1f) else 1f
            engine?.setSound(soundSettings, loadedGain)
        }
        played(now)
        saveSoon()
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
        // Runs off the UI thread and is waited for a moment only: Library.index() and the SQLite
        // write behind it must never hold the window's close for long.
        val job = CoroutineScope(Dispatchers.IO).async { runCatching { source.keep(entry, position, duration, at) } }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(KEEP_SPOT_TIMEOUT_MS) { job.await() } }
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
        const val KEEP_SPOT_TIMEOUT_MS = 1_500L
        const val CHECKPOINT_MS = 30_000L
        const val MAX_SKIPS = 3
        const val UPCOMING = 5
        const val SLEEP_FADE_MS = 5_000L

        /** The next track's stream starts this long before the playing one ends. */
        const val PREFETCH_LEAD_MS = 20_000L
    }
}
