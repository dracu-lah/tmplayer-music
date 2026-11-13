package com.tmplayer.music.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tmplayer.music.auto.BrowseArt
import com.tmplayer.music.data.Library
import com.tmplayer.music.platform.Logger
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The small covers of the browse tree rows a car shows, as JPEG bytes in each item's metadata (the
 * car's process cannot open the app's own `file://` or `tdfile://` URIs). They go through the
 * session cover path ([ArtworkBytes]: the saved cover, Telegram's thumbnail, the generated cover),
 * then shrink to [PX] a side, so a page of 100 rows is about a megabyte, not thirty. A few at a
 * time, each given [LOAD_MS] for a thumbnail that has to be fetched; a row that cannot get one
 * falls back to the generated cover, never to a wait. Recent ones are kept. A page waits for the
 * covers of its first rows only ([of]); the rest get [quick] (kept or generated) and are fetched
 * afterwards by [warm], so a long page is never held back by a slow thumbnail.
 */
object BrowseArtwork {
    private const val TAG = "BrowseArtwork"
    private const val PX = 192
    private const val LOAD_MS = 2_500L
    private const val CACHE_BYTES = 3 * 1024 * 1024

    private val gate = Semaphore(4)
    private val recent = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun keyOf(art: BrowseArt) =
        "${art.coverKey}|${art.thumbFileId}|${art.track?.chatId}:${art.track?.messageId}|${art.generatedKey}"

    suspend fun of(art: BrowseArt?): ByteArray? {
        art ?: return null
        val key = keyOf(art)
        synchronized(recent) { recent.get(key) }?.let { return it }
        val bytes = gate.withPermit { withContext(Dispatchers.IO) { build(art) } } ?: return null
        synchronized(recent) { recent.put(key, bytes) }
        return bytes
    }

    /** The cover without any wait: the kept one, else the generated cover (a quick local draw); null for no art. */
    suspend fun quick(art: BrowseArt?): ByteArray? {
        art ?: return null
        synchronized(recent) { recent.get(keyOf(art)) }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { com.tmplayer.music.ui.common.GeneratedCover.jpeg(art.generatedKey, PX, quality = 80) }.getOrNull()
        }
    }

    /** Loads and keeps the cover of [art] for later; true when it was not kept before and now is. */
    suspend fun warm(art: BrowseArt?): Boolean {
        art ?: return false
        if (synchronized(recent) { recent.get(keyOf(art)) } != null) return false
        return of(art) != null
    }

    private suspend fun build(art: BrowseArt): ByteArray? {
        val uri: Uri? = Library.coverFile(art.coverKey)?.let(Uri::fromFile)
            ?: art.track?.takeIf { art.thumbFileId != 0 }?.let { tdFileUri(art.thumbFileId, track = it, thumb = true) }
        val real = if (uri == null) {
            null
        } else {
            val item = MediaItem.Builder().setMediaId("browse-art")
                .setMediaMetadata(MediaMetadata.Builder().setArtworkUri(uri).build()).build()
            runCatching { withTimeoutOrNull(LOAD_MS) { ArtworkBytes.load(item) } }.getOrNull()?.let(::shrink)
        }
        if (real != null) return real
        val online = com.tmplayer.music.covers.CoverOnline.cached(art.generatedKey)?.let(ArtworkBytes::encode)?.let(::shrink)
        return online ?: com.tmplayer.music.ui.common.GeneratedCover.jpeg(art.generatedKey, PX, quality = 80)
    }

    /** [bytes] (a cover of the session's size) as a JPEG of at most [PX] a side; null if it cannot be read. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val (w, h) = ArtworkBounds.fit(decoded.width, decoded.height, PX)
            if (w <= 0 || h <= 0) return null
            scaled = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) null else out.toByteArray()
        } catch (e: Throwable) {
            Logger.w(TAG, "a browse cover could not be shrunk", e)
            null
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded?.recycle()
        }
    }
}
