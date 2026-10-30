package com.tmplayer.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.tmplayer.music.MainActivity
import com.tmplayer.music.R
import com.tmplayer.music.data.Failures
import com.tmplayer.music.data.Library
import com.tmplayer.music.data.Storage
import com.tmplayer.music.data.SupportChecks
import com.tmplayer.music.data.SupportReminder
import com.tmplayer.music.data.TagEnricher
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.Track
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The music player and its media session, alive while anything is connected to it or it plays:
 * the app's screens, the notification, the lock screen, Bluetooth and car displays.
 *
 * Audio focus, a headset being unplugged (pause) and the wake lock are Media3's, switched on here.
 * Gapless playback is ExoPlayer's own between items of the playlist. FLAC, Opus and ALAC fall back
 * to FFmpeg where the phone has no decoder (Android 6 and 7 have none for FLAC).
 *
 * Nothing autoplays: there is no media button receiver, so a headset or a car connecting cannot
 * start the service, and the queue restored on open is paused. A track that cannot be played is
 * skipped with its reason shown ([PlaybackErrors]); three failures in a row stop the skipping.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = JobFailures.scope("PlaybackService", Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var failuresInARow = 0
    private var saveJob: Job? = null
    private var checkpointJob: Job? = null

    /** When the cache was last trimmed to its ceiling, so a run of skips does not trim each time. */
    private var trimmedAt = 0L

    /** The item a play was last recorded for, so a pause and resume does not count twice. */
    private var recorded: String? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notify) },
        )
        val sources = TdDataSource.Factory(this)
        val renderers = NextRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(Watcher(player))
        SleepTimer.attach(player, scope)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The system sees the cover as bytes, not as the app's own URIs it cannot open (SessionArtwork.kt).
        session = MediaSession.Builder(this, SessionArtworkPlayer(player, scope))
            .setSessionActivity(open)
            .setCallback(Callback())
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(sources).build()))
            .build()
        restore(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        save()
        player?.let { keepCurrent(it, announce = false) }
        // Swiped away while paused or empty: nothing to keep going for.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Library.setPlaying(false)
        save()
        checkpointJob?.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** The saved queue, back in the player, paused at its position. */
    private fun restore(player: Player) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                SavedQueue.decode(runCatching { queueFile().readText() }.getOrNull())
            } ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            val items = withContext(Dispatchers.IO) { saved.items.mapNotNull { resolve(it) } }
            if (items.isEmpty()) return@launch
            val current = saved.current.coerceIn(0, items.lastIndex)
            player.setMediaItems(items, current, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeat
            player.playWhenReady = false
        }
    }

    /** Writes the queue soon, once, however many changes come together. */
    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(SAVE_DELAY_MS)
            save()
        }
    }

    private fun save() {
        val player = session?.player ?: return
        val items = (0 until player.mediaItemCount).mapNotNull { MediaIds.item(player.getMediaItemAt(it).mediaId) }
        val (window, current) = SavedQueue.window(items, player.currentMediaItemIndex)
        val queue = SavedQueue(window, current, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        val text = if (items.isEmpty()) "" else queue.encode()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val file = queueFile()
                val temp = File(file.path + ".part")
                temp.writeText(text)
                if (!temp.renameTo(file)) temp.delete()
            }.onFailure { Logger.w(TAG, "saving the queue failed", it) }
        }
    }

    private fun queueFile() = File(filesDir, "queue.json")

    /** Keeps the cache under its ceiling as tracks go by; at most every [TRIM_GAP_MS]. */
    private fun trimSoon() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - trimmedAt < TRIM_GAP_MS) return
        trimmedAt = now
        scope.launch(Dispatchers.IO) { runCatching { Storage.trim() } }
    }

    /** Counts a play for Recently played, once per start of an item. */
    private fun recordPlay(item: MediaItem?) {
        val mediaId = item?.mediaId ?: return
        if (recorded == mediaId) return
        recorded = mediaId
        val queued = MediaIds.item(mediaId) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { Library.index().recordPlay(queued.id, queued.songKey, System.currentTimeMillis() / 1000) }
            Library.notifyChanged()
        }
    }

    /**
     * Keeps where [item] was left for Speed dial ([com.tmplayer.music.library.SpeedDial]); a spot not
     * worth coming back to clears it. [announce] tells the lists, for a pause or a skip; the
     * checkpoint while playing stays quiet, so a list is not read again every half minute.
     */
    private fun keepPosition(item: MediaItem?, positionMs: Long, durationMs: Long, announce: Boolean) {
        val queued = item?.mediaId?.let { MediaIds.item(it) } ?: return
        val length = durationMs.takeIf { it > 0 } ?: item.mediaMetadata.durationMs ?: 0L
        val at = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            runCatching { Library.index().saveResume(queued.id, queued.songKey, positionMs.coerceAtLeast(0), length, at) }
                .onFailure { Logger.w(TAG, "keeping where a track was left failed", it) }
            if (announce) Library.notifyChanged()
        }
    }

    /** [item] played to its end: its card starts from the top next time. */
    private fun finished(item: MediaItem?) {
        val queued = item?.mediaId?.let { MediaIds.item(it) } ?: return
        val at = System.currentTimeMillis()
        // A track played to its end is what the sponsor ask counts, and its good moment.
        SupportReminder.trackFinished(at)
        scope.launch(Dispatchers.IO) {
            runCatching { Library.index().clearResume(queued.id) }.onFailure { Logger.w(TAG, "clearing a finished track failed", it) }
            Library.notifyChanged()
            runCatching { SupportChecks.store(applicationContext).noteCompleted(queued.songKey.ifBlank { queued.id.toString() }, at) }
        }
    }

    private fun keepCurrent(player: Player, announce: Boolean) {
        if (player.playbackState == Player.STATE_ENDED) return
        keepPosition(player.currentMediaItem, player.currentPosition, player.duration, announce)
    }

    /** While something plays, its spot is kept every [CHECKPOINT_MS], so a killed process loses little. Stops on pause. */
    private fun checkpoint(player: Player, playing: Boolean) {
        checkpointJob?.cancel()
        checkpointJob = null
        if (!playing) return
        checkpointJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CHECKPOINT_MS)
                keepCurrent(player, announce = false)
            }
        }
    }

    private inner class Watcher(private val player: Player) : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val left = oldPosition.mediaItem ?: return
            val sameItem = newPosition.mediaItemIndex == oldPosition.mediaItemIndex && newPosition.mediaItem?.mediaId == left.mediaId
            when {
                // Played to the end, into the next item or round again with repeat one.
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> finished(left)
                // A seek inside the same track is kept at the next pause.
                sameItem -> Unit
                // Skipped, removed or another item picked: keep where it was left.
                else -> keepPosition(left, oldPosition.positionMs, left.mediaMetadata.durationMs ?: 0L, announce = true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new item, or the same one again with repeat one: its next start is a new play.
            recorded = null
            if (player.isPlaying) recordPlay(player.currentMediaItem)
            saveSoon()
            trimSoon()
            readAhead()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            saveSoon()
            readAhead()
        }

        /** The playing track and the next few get their tags read before the rest of the library. */
        private fun readAhead() {
            val count = player.mediaItemCount
            if (count == 0) return
            val from = player.currentMediaItemIndex.coerceIn(0, count - 1)
            val ids = (from until minOf(count, from + TagEnricher.UPCOMING)).mapNotNull { MediaIds.item(player.getMediaItemAt(it).mediaId)?.id }
            runCatching { Library.enricher.preferUpcoming(ids) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSoon()

        override fun onRepeatModeChanged(repeatMode: Int) = saveSoon()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Library.setPlaying(isPlaying)
            if (isPlaying) recordPlay(player.currentMediaItem) else saveSoon()
            checkpoint(player, isPlaying)
            // A pause by the user (not a wait for data) keeps the spot and tells Speed dial.
            if (!isPlaying && !player.playWhenReady) keepCurrent(player, announce = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finished(player.currentMediaItem)
            if (playbackState == Player.STATE_READY) {
                failuresInARow = 0
                PlaybackErrors.clear()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
            // No title in the log: Send logs shares these lines, and a title is the user's own.
            Logger.w(TAG, "could not play the current item: ${error.errorCodeName}", error)
            PlaybackErrors.report(L.playerSkipped(title, Failures.humanise(error.cause ?: error)))
            failuresInARow++
            if (failuresInARow < MAX_SKIPS && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /** Items sent by a controller carry only their id; the track is found in the index here. */
    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val resolved = runCatching {
                    withContext(Dispatchers.IO) {
                        mediaItems.mapNotNull { item ->
                            if (item.localConfiguration != null) item else MediaIds.item(item.mediaId)?.let { resolve(it) }
                        }.toMutableList()
                    }
                }
                resolved.onSuccess { future.set(it) }.onFailure { future.setException(it) }
            }
            return future
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val MAX_SKIPS = 3
        private const val SAVE_DELAY_MS = 500L
        private const val CHECKPOINT_MS = 30_000L
        private const val TRIM_GAP_MS = 5 * 60_000L

        /** A queue item as a playable MediaItem, from the index: the version, or the song's default. */
        suspend fun resolve(item: SavedQueue.Item): MediaItem? {
            val index = Library.index()
            val track = index.track(item.id)
                ?: item.songKey.takeIf { it.isNotBlank() }?.let { key -> index.song(key)?.let { index.track(it.defaultTrack) } }
                ?: return null
            return mediaItem(track, item.songKey)
        }

        fun mediaItem(track: Track, songKey: String): MediaItem {
            val artwork: Uri? = Library.coverFile(track.coverKey)?.let(Uri::fromFile)
                ?: track.thumbFileId.takeIf { it != 0 }?.let { tdFileUri(it, track = track.id, thumb = true) }
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(ArtistDisplay.of(track.artist).ifBlank { L.libraryUnknownArtist })
                .setAlbumTitle(track.album.ifBlank { null })
                .setAlbumArtist(track.albumArtist.ifBlank { null })
                .setTrackNumber(track.trackNumber.takeIf { it > 0 })
                .setArtworkUri(artwork)
                .setDurationMs(track.durationMs.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
            return MediaItem.Builder()
                .setMediaId(MediaIds.of(track.id, songKey))
                .setUri(tdFileUri(track.fileId, track.mime.ifBlank { null }, track = track.id))
                .setMediaMetadata(metadata)
                .build()
        }
    }
}

/** The media id of a queue item: chat, message and the song's key, so a lost version can be replaced. */
object MediaIds {
    fun of(id: TrackId, songKey: String): String = "${id.chatId}:${id.messageId}:$songKey"

    fun item(mediaId: String): SavedQueue.Item? {
        val parts = mediaId.split(':', limit = 3)
        val chat = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val message = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return SavedQueue.Item(chat, message, parts.getOrNull(2).orEmpty())
    }
}

/** The last thing playback could not do, for the mini-player to say; cleared once a track plays. */
object PlaybackErrors {
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last.asStateFlow()

    fun report(message: String) {
        _last.value = message
    }

    fun clear() {
        _last.value = null
    }
}
