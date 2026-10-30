package com.tmplayer.music.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.Song
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the mini-player and Now Playing draw. Position is read on demand ([PlayerConnection.position]). */
data class PlayerState(
    val connected: Boolean = false,
    val item: MediaItem? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val index: Int = 0,
    val count: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    val metadata: MediaMetadata get() = item?.mediaMetadata ?: MediaMetadata.EMPTY
}

/**
 * Which file the lists mark as playing: the message it is ([track]), the song it was played as
 * ([songKey], empty when it was played as that chat's own file from a chat page) and whether it plays
 * or is paused. It changes only when the file or play and pause do, never with the position, so a
 * list that watches it is not redrawn while a track plays. [PlayingMatch] decides which rows it marks.
 */
data class PlayingMark(val songKey: String, val track: TrackId, val playing: Boolean)

/**
 * Which rows show the playing mark. A song row (Songs, albums, playlists, Home) is marked when it is
 * the song that plays; a file played from a chat page has no song key, so then the song row whose
 * default file is that same message is marked. A chat page row is one file, so it is marked only when
 * that very message plays: another version of the same song in the chat stays unmarked.
 */
object PlayingMatch {
    /** true playing, false paused, null when [songKey] (default file [defaultTrack]) is not what plays. */
    fun song(mark: PlayingMark?, songKey: String, defaultTrack: TrackId?): Boolean? {
        mark ?: return null
        val hit = if (mark.songKey.isNotEmpty()) mark.songKey == songKey else defaultTrack == mark.track
        return if (hit) mark.playing else null
    }

    /** true playing, false paused, null when the file [track] is not the one that plays. */
    fun track(mark: PlayingMark?, track: TrackId): Boolean? = mark?.takeIf { it.track == track }?.playing
}

/**
 * The app's one connection to [PlaybackService]. The screens send commands through it and read
 * [state]; the service does the rest, so playback goes on when the screens are gone.
 */
object PlayerConnection {
    private const val TAG = "PlayerConnection"

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _mark = MutableStateFlow<PlayingMark?>(null)

    /** The song the lists mark; null with an empty queue. */
    val mark: StateFlow<PlayingMark?> = _mark.asStateFlow()

    /** The queue's items, read when the queue screen asks. */
    fun queue(): List<MediaItem> = controller?.let { c -> (0 until c.mediaItemCount).map { c.getMediaItemAt(it) } }.orEmpty()

    fun position(): Long = controller?.currentPosition ?: 0L

    fun connect(context: Context) {
        if (future != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val pending = MediaController.Builder(app, token).buildAsync()
        future = pending
        pending.addListener({
            val c = runCatching { pending.get() }.onFailure { Logger.w(TAG, "could not connect to the player", it) }.getOrNull()
                ?: run {
                    future = null
                    return@addListener
                }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish(player)
            })
            publish(c)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun publish(p: Player) {
        _state.value = PlayerState(
            connected = true,
            item = p.currentMediaItem,
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
            durationMs = p.duration.takeIf { it > 0 } ?: p.currentMediaItem?.mediaMetadata?.durationMs ?: 0L,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            index = p.currentMediaItemIndex,
            count = p.mediaItemCount,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem() || p.currentPosition > 3_000,
        )
        // StateFlow drops an equal value, so only a new song or play and pause reach the lists.
        _mark.value = p.currentMediaItem?.mediaId
            ?.let { MediaIds.item(it) }
            ?.let {
                PlayingMark(
                    songKey = it.songKey,
                    track = TrackId(it.chatId, it.messageId),
                    playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING),
                )
            }
    }

    /** An item the service resolves by id from the index (see [PlaybackService]). */
    private fun itemOf(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.of(song.defaultTrack, song.groupKey))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(ArtistDisplay.of(song.artist))
                .setExtras(android.os.Bundle().apply { putString(MediaIds.COVER_ART, com.tmplayer.music.library.CoverArt.keyOf(song.albumKey, song.groupKey)) })
                .build(),
        )
        .build()

    /**
     * Plays [songs] from [start]. A long list is cut around the start to [SavedQueue.MAX_ITEMS] so
     * a "play all" of 20,000 does not hand the session 20,000 items.
     */
    fun play(songs: List<Song>, start: Int, shuffle: Boolean = false, positionMs: Long = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val begin = (start - SavedQueue.MAX_ITEMS / 4).coerceIn(0, maxOf(0, songs.size - SavedQueue.MAX_ITEMS))
        val window = songs.subList(begin, minOf(songs.size, begin + SavedQueue.MAX_ITEMS))
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(window.map(::itemOf), (start - begin).coerceIn(0, window.lastIndex), positionMs.coerceAtLeast(0))
        c.prepare()
        c.play()
    }

    /**
     * Resumes [track] where the queue already holds it: the current item plays on (or from
     * [positionMs] when it ended), another item is skipped to and started at [positionMs]. False when
     * the queue does not hold it, so the caller builds a new queue.
     */
    fun resumeInQueue(track: TrackId, positionMs: Long): Boolean {
        val c = controller ?: return false
        val at = (0 until c.mediaItemCount).firstOrNull { i ->
            MediaIds.item(c.getMediaItemAt(i).mediaId)?.let { TrackId(it.chatId, it.messageId) } == track
        } ?: return false
        if (at == c.currentMediaItemIndex && c.playbackState != Player.STATE_ENDED) {
            if (!c.isPlaying) toggle()
            return true
        }
        c.seekTo(at, positionMs.coerceAtLeast(0))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
        return true
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, itemOf(song))
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(song), 0)
        c.addMediaItem(itemOf(song))
    }

    /** Plays one exact version of a song, from the Versions sheet. */
    fun playVersion(song: Song, track: com.tmplayer.music.library.Track) {
        play(listOf(song.copy(defaultTrack = track.id)), 0)
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            c.play()
        }
    }

    fun next() = controller?.seekToNext() ?: Unit
    fun previous() = controller?.seekToPrevious() ?: Unit
    fun seekTo(ms: Long) = controller?.seekTo(ms) ?: Unit
    fun skipTo(index: Int) = controller?.let { if (index in 0 until it.mediaItemCount) it.seekTo(index, 0) } ?: Unit
    fun remove(index: Int) = controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } ?: Unit
    fun move(from: Int, to: Int) = controller?.let {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount && from != to) it.moveMediaItem(from, to)
    } ?: Unit

    fun clear() = controller?.let {
        it.stop()
        it.clearMediaItems()
    } ?: Unit

    fun setShuffle(on: Boolean) = controller?.let { it.shuffleModeEnabled = on } ?: Unit

    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    } ?: Unit

    /** Pauses, for the sleep timer and a log out. */
    fun pause() = controller?.pause() ?: Unit
}
