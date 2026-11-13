package com.tmplayer.music.desktop.player

import com.tmplayer.music.library.TrackId
import com.tmplayer.music.player.SavedQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.openani.mediamp.source.MediaData
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** A file that is just an array: always on disk, so a read never waits. */
class ArrayBytes(private val data: ByteArray) : StreamBytes {
    override val size: Long get() = data.size.toLong()
    var closed = false

    override fun cachedAvailable(position: Long): Long? = (data.size - position).coerceAtLeast(0)

    override suspend fun awaitBytesAt(position: Long): Long = cachedAvailable(position) ?: 0

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length.toLong(), data.size - position).toInt()
        if (n <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() {
        closed = true
    }
}

/** An engine that plays nothing: the tests move its state by hand. */
class FakeEngine : AudioEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    val opened = CopyOnWriteArrayList<Triple<String, Long, Boolean>>()
    var stops = 0
    var closed = false
    val seeks = CopyOnWriteArrayList<Long>()
    val sounds = CopyOnWriteArrayList<Pair<SoundSettings, Float>>()
    val queuedNext = CopyOnWriteArrayList<String>()
    var queueNextResult = false
    var queueCleared = 0

    override fun setSound(settings: SoundSettings, gain: Float) {
        sounds += settings to gain
    }

    override suspend fun queueNext(next: MediaData): Boolean {
        if (queueNextResult) queuedNext += (next as TdMediaData).uri
        return queueNextResult
    }

    override fun clearQueuedNext() {
        queueCleared++
    }

    /** The engine moved on to the queued file by itself. */
    fun advance() = _state.update { it.copy(advanced = it.advanced + 1, positionMs = 0, ended = false, playing = true) }

    /** The playing file is [positionMs] in. */
    fun at(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }

    override suspend fun open(data: MediaData, startAtMs: Long, playWhenReady: Boolean) {
        opened += Triple((data as TdMediaData).uri, startAtMs, playWhenReady)
        _state.value = EngineState(opened = true, positionMs = startAtMs, durationMs = 200_000, playing = playWhenReady, volume = _state.value.volume, muted = _state.value.muted)
    }

    override fun play() = _state.update { it.copy(playing = true, ended = false) }

    override fun pause() = _state.update { it.copy(playing = false) }

    override fun stop() {
        stops++
        _state.update { it.copy(playing = false, opened = false) }
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun setVolume(percent: Int) = _state.update { it.copy(volume = percent) }

    override fun setMuted(muted: Boolean) = _state.update { it.copy(muted = muted) }

    override fun close() {
        closed = true
    }

    /** The track played to its end. */
    fun end() = _state.update { it.copy(ended = true, playing = false) }

    /** The track fails part way. */
    fun fail(message: String) = _state.update { it.copy(error = message, playing = false) }
}

/** A library of [songs] by key; [broken] keys cannot be opened. */
class FakeSource(songs: List<String>, private val broken: Set<String> = emptySet()) : TrackSource {
    private val entries = songs.mapIndexed { i, key -> key to QueueEntry(TrackId(1, i + 1L), key, "Title $key", "Artist") }.toMap()
    val opens = CopyOnWriteArrayList<String>()
    val plays = CopyOnWriteArrayList<String>()
    val kept = CopyOnWriteArrayList<Pair<String, Long>>()
    val finished = CopyOnWriteArrayList<String>()

    fun entry(key: String): QueueEntry = entries.getValue(key)

    /** The loudness factor per song key; 1 for the rest. */
    val gains = java.util.concurrent.ConcurrentHashMap<String, Float>()

    override suspend fun gainOf(entry: QueueEntry): Float = gains[entry.songKey] ?: 1f

    override suspend fun resolve(item: SavedQueue.Item): QueueEntry? = entries[item.songKey]

    override suspend fun open(entry: QueueEntry): MediaData {
        opens += entry.songKey
        if (entry.songKey in broken) throw IOException("cannot open ${entry.songKey}")
        return TdMediaData(ArrayBytes(ByteArray(16)), uri = "test://${entry.songKey}")
    }

    override suspend fun played(entry: QueueEntry, atSec: Long) {
        plays += entry.songKey
    }

    override suspend fun keep(entry: QueueEntry, positionMs: Long, durationMs: Long, atMs: Long) {
        kept += entry.songKey to positionMs
    }

    override suspend fun finished(entry: QueueEntry) {
        finished += entry.songKey
    }
}

class MemoryStore(var text: String? = null) : QueueStore {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/** Waits for [condition] on another thread, up to [ms], and fails with [what] when it never holds. */
suspend fun eventually(what: String, ms: Long = 20_000, condition: () -> Boolean) {
    try {
        withTimeout(ms) {
            while (!condition()) delay(5)
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what")
    }
}
