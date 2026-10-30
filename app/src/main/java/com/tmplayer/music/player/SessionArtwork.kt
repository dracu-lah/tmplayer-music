package com.tmplayer.music.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tmplayer.music.data.FileIds
import com.tmplayer.music.data.Td
import com.tmplayer.music.platform.Logger
import dev.g000sha256.tdl.TdlResult
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player as the media session shows it to the system: the same player, but the cover goes to
 * the lock screen, the notification and Bluetooth displays as bytes ([MediaMetadata.artworkData]),
 * never as the app's own `tdfile://` or `file://` URI, which no other process can open (the system
 * logged "Invalid album art uri" and showed no cover).
 *
 * The items themselves keep their artwork URI, so the app's own screens load covers as before;
 * only [getMediaMetadata], the playing item's metadata that the session reads, is changed. The
 * bytes are loaded off the main thread when the item changes ([ArtworkBytes]), bounded by
 * [ArtworkBounds], and announced with a metadata change once they are there. Until then there is
 * no artwork. When the track has no cover, or it cannot be read, the session gets the generated one
 * ([generate], the same picture the app's screens show), unless the stream carries a picture of its
 * own, which wins. A few recent covers are kept, so skipping back does not load again.
 */
@OptIn(UnstableApi::class)
class SessionArtworkPlayer(
    private val inner: Player,
    private val scope: CoroutineScope,
    private val load: suspend (MediaItem) -> ByteArray? = ArtworkBytes::load,
    private val generate: (MediaItem) -> ByteArray? = ArtworkBytes::generated,
) : ForwardingPlayer(inner) {

    /** The listeners added through this player; main thread only. */
    private val listeners = LinkedHashSet<Player.Listener>()
    private val recent = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private var shownFor: String? = null
    private var shown: ByteArray? = null

    /** Whether [shown] is a generated cover, which gives way to a picture embedded in the stream. */
    private var shownGenerated = false
    private val generatedIds = HashSet<String>()
    private var job: Job? = null

    init {
        inner.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        })
        refresh()
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val builder = base.buildUpon().setArtworkUri(null)
        val mine = shown?.takeIf { shownFor != null && shownFor == inner.currentMediaItem?.mediaId }
        val embedded = ArtworkBounds.fits(base.artworkData)
        when {
            mine != null && shownGenerated && embedded -> Unit
            mine != null -> builder.setArtworkData(mine, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            // A picture embedded in the stream, as ExoPlayer read it: kept only when small enough.
            !embedded -> builder.setArtworkData(null, null)
        }
        return builder.build()
    }

    // Listeners are forwarded as they are (a Kotlin `by` wrapper would drop Java's default methods);
    // they are also kept here, to be told when a cover comes in. The session reads the metadata
    // it shows the system through [getMediaMetadata], so the cover reaches it either way.
    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    /** Starts loading the cover of the item now playing, unless it is the one shown already. */
    private fun refresh() {
        val item = inner.currentMediaItem
        val id = item?.mediaId
        if (id == shownFor && shown != null) return
        job?.cancel()
        shownFor = id
        shown = null
        shownGenerated = false
        if (item == null || id.isNullOrEmpty()) return
        recent.get(id)?.let {
            shown = it
            shownGenerated = id in generatedIds
            announce()
            return
        }
        job = scope.launch {
            val loaded: ByteArray? = try {
                withContext(Dispatchers.IO) { load(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "the cover for the media session could not be loaded", e)
                null
            }
            val real = loaded?.takeIf { ArtworkBounds.fits(it) }
            val bytes = real ?: try {
                withContext(Dispatchers.Default) { generate(item) }?.takeIf { ArtworkBounds.fits(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "the generated cover for the media session could not be drawn", e)
                null
            } ?: return@launch
            recent.put(id, bytes)
            if (real == null) generatedIds += id else generatedIds -= id
            // The set only names ids whose bytes may still be in [recent]; it stays small.
            if (generatedIds.size > MAX_GENERATED_IDS) generatedIds.clear()
            if (inner.currentMediaItem?.mediaId != id) return@launch
            shownFor = id
            shown = bytes
            shownGenerated = real == null
            announce()
        }
    }

    /** Tells the session (and anything else listening) that the metadata changed: the cover is in. */
    private fun announce() {
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        for (listener in listeners.toList()) {
            runCatching {
                listener.onMediaMetadataChanged(metadata)
                listener.onEvents(this, events)
            }.onFailure { Logger.w(TAG, "a listener failed on the cover change", it) }
        }
    }

    private companion object {
        const val TAG = "SessionArtwork"
        const val CACHE_BYTES = 2 * 1024 * 1024
        const val MAX_GENERATED_IDS = 256
    }
}

/**
 * The cover of an item as small JPEG bytes for the media session: the cover file saved from the
 * track's tags (the item's `file://` artwork URI), or Telegram's thumbnail of the message (its
 * `tdfile://` URI), with the thumbnail's file id asked again through [FileIds] as the row covers
 * do. Decoded down to [ArtworkBounds.MAX_PX], never full size. Null when there is none.
 */
object ArtworkBytes {
    private const val TAG = "ArtworkBytes"
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L

    suspend fun load(item: MediaItem): ByteArray? {
        val uri = item.mediaMetadata.artworkUri ?: return null
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            TD_SCHEME -> thumbnail(uri)
            else -> null
        } ?: return null
        return encode(file)
    }

    private suspend fun thumbnail(uri: Uri): File? {
        val track = tdTrackOf(uri) ?: return null
        val fileId = FileIds.of(track)?.thumbFileId?.takeIf { it != 0 } ?: return null
        val path = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            Td.awaitAuthorizedSession().client.downloadFile(fileId, 8, 0, 0, true)
        }?.let { (it as? TdlResult.Success)?.result?.local }?.takeIf { it.isDownloadingCompleted }?.path
        return path?.takeIf { it.isNotEmpty() }?.let(::File)
    }

    /**
     * The kept online cover of [item], else its generated cover ([com.tmplayer.music.ui.common.GeneratedCover]),
     * as JPEG bytes of [ArtworkBounds.MAX_PX] a side, for a track with no picture of its own. Null on failure.
     */
    fun generated(item: MediaItem): ByteArray? {
        val key = MediaIds.coverArt(item) ?: return null
        // A cover found online and already kept on the device (no request from here) comes first.
        com.tmplayer.music.covers.CoverOnline.cached(key)?.let(::encode)?.let { return it }
        return com.tmplayer.music.ui.common.GeneratedCover.jpeg(key, ArtworkBounds.MAX_PX)
    }

    /** The picture in [file], at most [ArtworkBounds.MAX_PX] a side, as JPEG within [ArtworkBounds.MAX_BYTES]. */
    fun encode(file: File): ByteArray? {
        if (!file.isFile) return null
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            if (!com.tmplayer.music.ui.common.ImageCheck.isWhole(file)) return null
            val sample = ArtworkBounds.sampleSize(bounds.outWidth, bounds.outHeight)
            decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val (w, h) = ArtworkBounds.fit(decoded.width, decoded.height)
            scaled = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
            for (quality in intArrayOf(85, 70, 50)) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                val bytes = out.toByteArray()
                if (ArtworkBounds.fits(bytes)) return bytes
            }
            null
        } catch (e: Throwable) {
            // Out of memory on a huge picture, or a file that is not a picture: no artwork.
            Logger.w(TAG, "a cover could not be prepared for the media session", e)
            null
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded?.recycle()
        }
    }
}
