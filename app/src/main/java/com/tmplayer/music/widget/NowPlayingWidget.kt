package com.tmplayer.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.scale
import androidx.media3.common.Player
import com.tmplayer.music.BuildHooks
import com.tmplayer.music.MainActivity
import com.tmplayer.music.R
import com.tmplayer.music.data.Library
import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.player.ArtworkBounds
import com.tmplayer.music.player.SavedQueue
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.ui.common.GeneratedCover
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home screen widget: cover, title, artist, and previous, play or pause and next, in a 2x1
 * (compact) or 4x1 (wide) layout chosen from its width ([WidgetLayout]).
 *
 * It is driven by the media session and by nothing else: [WidgetSync] redraws it when the track or
 * the playing state changes, the launcher when it is placed or resized. No timer, no polling, no
 * progress bar, so it costs nothing while the music plays. With nothing queued it says so, and a
 * tap anywhere but a button opens the app. When the app's process is not running, the track is read
 * from the saved queue ([loadSaved]), shown paused, as it would be restored.
 */
object NowPlayingWidget {
    private const val TAG = "NowPlayingWidget"
    private const val COVER_PX = 160

    const val ACTION_PREVIOUS = "com.tmplayer.music.widget.PREVIOUS"
    const val ACTION_PLAY_PAUSE = "com.tmplayer.music.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "com.tmplayer.music.widget.NEXT"

    /** What was last drawn, and what to compare the next event with so an unchanged state draws nothing. */
    private class Snapshot(val state: WidgetState, val cover: Bitmap?, val key: String)

    @Volatile
    private var last: Snapshot? = null

    private val scope = JobFailures.scope("NowPlayingWidget", Dispatchers.IO)

    private fun component(context: Context) = ComponentName(context, NowPlayingWidgetProvider::class.java)

    private fun ids(context: Context): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)) }.getOrDefault(IntArray(0))

    /** The service is gone: the next redraw reads the saved queue again instead of trusting what it last saw. */
    fun serviceStopped() {
        last = null
    }

    /** The session's item and state changed: draws the widgets, unless there are none or nothing differs. Main thread. */
    fun onPlayerChanged(context: Context, player: Player) {
        val app = context.applicationContext
        val ids = ids(app)
        if (ids.isEmpty()) {
            last = null
            return
        }
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        val playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        val state = WidgetState.of(item?.mediaId, metadata.title ?: item?.mediaMetadata?.title, metadata.artist ?: item?.mediaMetadata?.artist, playing)
        val art = metadata.artworkData
        val key = "${item?.mediaId}|${state.title}|${state.artist}|${state.playing}|${art?.size}|${art?.contentHashCode()}"
        if (last?.key == key) return
        // A new cover is decoded here only when the bytes are new; the same track keeps its bitmap.
        val keepCover = last?.takeIf { it.state.hasItem && it.state.title == state.title && art == null }?.cover
        val cover = if (art != null) small(art) else keepCover
        draw(app, Snapshot(state, cover, key), ids)
    }

    /** The launcher placed, resized or refreshed the widgets [ids]: draws what is known, or reads the saved queue first. */
    fun refresh(context: Context, pending: BroadcastReceiver.PendingResult?, ids: IntArray) {
        val app = context.applicationContext
        val known = last
        if (known != null) {
            draw(app, known, ids)
            pending?.finish()
            return
        }
        scope.launch {
            try {
                draw(app, loadSaved(app), ids)
            } catch (e: Throwable) {
                Logger.w(TAG, "the widget could not be drawn", e)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun draw(context: Context, snapshot: Snapshot, ids: IntArray) {
        last = snapshot
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids) {
            runCatching {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                manager.updateAppWidget(id, views(context, snapshot, WidgetLayout.forWidthDp(width)))
            }.onFailure { Logger.w(TAG, "a widget could not be updated", it) }
        }
    }

    private fun views(context: Context, snapshot: Snapshot, layout: WidgetLayout): RemoteViews {
        val state = snapshot.state
        val views = RemoteViews(
            context.packageName,
            if (layout == WidgetLayout.Wide) R.layout.widget_wide else R.layout.widget_compact,
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setViewVisibility(R.id.widget_content, if (state.hasItem) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_empty, if (state.hasItem) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, L.widgetEmpty)
        if (!state.hasItem) return views

        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_artist, state.artist)
        views.setViewVisibility(R.id.widget_artist, if (state.artist.isEmpty()) View.GONE else View.VISIBLE)
        if (snapshot.cover != null) {
            views.setImageViewBitmap(R.id.widget_cover, snapshot.cover)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_empty)
        }
        views.setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setContentDescription(R.id.widget_play, if (state.playing) L.playerPause else L.playerPlay)
        views.setContentDescription(R.id.widget_previous, L.playerPrevious)
        views.setContentDescription(R.id.widget_next, L.playerNext)
        views.setOnClickPendingIntent(R.id.widget_previous, action(context, ACTION_PREVIOUS, 1))
        views.setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_PLAY_PAUSE, 2))
        views.setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT, 3))
        return views
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The queue as it was saved, paused at its current track: what the service will restore. Off the main thread. */
    private suspend fun loadSaved(context: Context): Snapshot {
        val empty = Snapshot(WidgetState.EMPTY, null, "empty")
        val text = runCatching { File(context.filesDir, BuildHooks.appFile("queue.json")).readText() }.getOrNull()
        val saved = SavedQueue.decode(text) ?: return empty
        val item = saved.items.getOrNull(saved.current) ?: return empty
        val index = runCatching { Library.index() }.getOrNull() ?: return empty
        val track = runCatching {
            index.track(item.id)
                ?: item.songKey.takeIf { it.isNotBlank() }?.let { key -> index.song(key)?.let { index.track(it.defaultTrack) } }
        }.getOrNull() ?: return empty
        val state = WidgetState.of(
            "${track.chatId}:${track.messageId}",
            track.title,
            ArtistDisplay.of(track.artist).ifBlank { L.libraryUnknownArtist },
            playing = false,
        )
        val cover = Library.coverFile(track.coverKey)?.let { file -> runCatching { smallFile(file) }.getOrNull() }
            ?: GeneratedCover.jpeg(CoverArt.keyOf(track), COVER_PX)?.let(::small)
        return Snapshot(state, cover, "saved|${track.chatId}:${track.messageId}")
    }

    /** [bytes] (a cover of the session's size) as a bitmap of at most [COVER_PX] a side; null when unreadable. */
    private fun small(bytes: ByteArray): Bitmap? = try {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        decoded?.let(::fitted)
    } catch (e: Throwable) {
        Logger.w(TAG, "a cover could not be decoded for the widget", e)
        null
    }

    private fun smallFile(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val sample = ArtworkBounds.sampleSize(bounds.outWidth, bounds.outHeight, COVER_PX)
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return fitted(decoded)
    }

    private fun fitted(decoded: Bitmap): Bitmap {
        val (w, h) = ArtworkBounds.fit(decoded.width, decoded.height, COVER_PX)
        if (w <= 0 || h <= 0 || (w == decoded.width && h == decoded.height)) return decoded
        return decoded.scale(w, h).also { decoded.recycle() }
    }
}

/** Tells the widget when the session's track or state changes; added to the service's player. */
class WidgetSync(private val context: Context, private val player: Player) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
            )
        ) {
            runCatching { NowPlayingWidget.onPlayerChanged(context, this.player) }
                .onFailure { Logger.w("WidgetSync", "the widget could not follow the session", it) }
        }
    }
}
