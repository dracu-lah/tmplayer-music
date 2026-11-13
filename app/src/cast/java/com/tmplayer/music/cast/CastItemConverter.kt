package com.tmplayer.music.cast

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import com.tmplayer.music.platform.Logger
import com.tmplayer.music.player.AudioEffectsState
import com.tmplayer.music.player.MediaIds
import org.json.JSONObject
import com.google.android.gms.cast.MediaMetadata as CastMetadata

/** A queue item as the cast plan sees it: its media id, title, format and mime type. */
internal fun entryOf(item: MediaItem): CastEntry = CastEntry(
    id = item.mediaId,
    title = item.mediaMetadata.title?.toString().orEmpty(),
    format = CastFormats.formatOf(item.mediaMetadata.extras?.getString(CAST_FORMAT_KEY)),
    mime = item.localConfiguration?.uri?.getQueryParameter("type").orEmpty(),
    fileName = "",
)

/**
 * Turns a queue item into what the Cast receiver is sent, and back. Out: the URL of the audio and of
 * the cover on the phone's cast server (see [CastAudioServer]), the title, artist, album and track
 * number, and the length, so the receiver's own screen shows the track. The item itself rides along
 * as custom data (its id, its `tdfile` address, its cover and the extras the app keeps), so the
 * receiver's queue turns back into the very items the local player plays when the cast ends.
 * Back: only what this app sent is understood; anything else comes back as an empty item.
 */
@UnstableApi
internal class CastItemConverter(private val server: () -> CastAudioServer?) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val entry = entryOf(mediaItem)
        val mime = (CastQueue.verdict(entry) as? CastVerdict.Playable)?.mime ?: "audio/mpeg"
        val extras = mediaItem.mediaMetadata.extras
        val size = extras?.getLong(CAST_SIZE_KEY, 0L) ?: 0L
        val audio = server()?.audioUrl(mediaItem, mime, size) ?: run {
            // No server: nothing to play. The receiver is sent an address it cannot open and says so.
            Logger.w(TAG, "no cast server for an item")
            "http://127.0.0.1:9/unavailable"
        }
        val cover = server()?.coverUrl(mediaItem)
        val m = mediaItem.mediaMetadata
        val metadata = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            m.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
            m.artist?.let { putString(CastMetadata.KEY_ARTIST, it.toString()) }
            m.albumTitle?.let { putString(CastMetadata.KEY_ALBUM_TITLE, it.toString()) }
            m.albumArtist?.let { putString(CastMetadata.KEY_ALBUM_ARTIST, it.toString()) }
            m.trackNumber?.takeIf { it > 0 }?.let { putInt(CastMetadata.KEY_TRACK_NUMBER, it) }
            if (cover != null) addImage(WebImage(cover.toUri()))
        }
        val custom = JSONObject().apply {
            put(ID, mediaItem.mediaId)
            put(URI, mediaItem.localConfiguration?.uri?.toString().orEmpty())
            put(ART, m.artworkUri?.toString().orEmpty())
            put(COVER, extras?.getString(MediaIds.COVER_ART).orEmpty())
            put(GAIN, extras?.getString(AudioEffectsState.GAIN_KEY).orEmpty())
            put(FORMAT, extras?.getString(CAST_FORMAT_KEY).orEmpty())
            put(SIZE, size)
        }
        val info = MediaInfo.Builder(audio)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .apply { m.durationMs?.takeIf { it > 0 }?.let { setStreamDuration(it) } }
            .setCustomData(custom)
            .build()
        // Preloading the next track a few seconds ahead keeps the gap between tracks short.
        return MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(PRELOAD_SECONDS).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media ?: return MediaItem.Builder().build()
        val custom = info.customData
        val id = custom?.optString(ID).orEmpty()
        if (id.isEmpty()) return MediaItem.Builder().setUri(info.contentId.takeIf { it.isNotBlank() }?.toUri() ?: Uri.EMPTY).build()
        val extras = Bundle().apply {
            putString(MediaIds.COVER_ART, custom?.optString(COVER).orEmpty())
            putString(AudioEffectsState.GAIN_KEY, custom?.optString(GAIN).orEmpty())
            putString(CAST_FORMAT_KEY, custom?.optString(FORMAT).orEmpty())
            putLong(CAST_SIZE_KEY, custom?.optLong(SIZE, 0L) ?: 0L)
        }
        val cast = info.metadata
        val metadata = MediaMetadata.Builder()
            .setTitle(cast?.getString(CastMetadata.KEY_TITLE))
            .setArtist(cast?.getString(CastMetadata.KEY_ARTIST))
            .setAlbumTitle(cast?.getString(CastMetadata.KEY_ALBUM_TITLE))
            .setAlbumArtist(cast?.getString(CastMetadata.KEY_ALBUM_ARTIST))
            .setTrackNumber(cast?.getInt(CastMetadata.KEY_TRACK_NUMBER)?.takeIf { it > 0 })
            .setArtworkUri(custom?.optString(ART)?.takeIf { it.isNotEmpty() }?.toUri())
            .setDurationMs(info.streamDuration.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(custom?.optString(URI).orEmpty().toUri())
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        const val TAG = "CastItemConverter"
        const val PRELOAD_SECONDS = 15.0
        const val ID = "id"
        const val URI = "uri"
        const val ART = "art"
        const val COVER = "cover"
        const val GAIN = "gain"
        const val FORMAT = "fmt"
        const val SIZE = "size"
    }
}
