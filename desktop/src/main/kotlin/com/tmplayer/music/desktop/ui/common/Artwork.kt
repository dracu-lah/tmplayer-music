// Adapted from the Android app's Artwork (app/.../ui/common/Artwork.kt): the same rules, decoded
// with Skia instead of Android's BitmapFactory.
package com.tmplayer.music.desktop.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.music.covers.CoverOnline
import com.tmplayer.music.data.FileFetch
import com.tmplayer.music.data.FileIds
import com.tmplayer.music.data.Td
import com.tmplayer.music.data.TdFileCalls
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.io.File
import java.io.RandomAccessFile

/**
 * Small pictures for rows: a chat avatar or Telegram's album cover thumbnail by file id, or a cover
 * saved from a file's tags by path. Decoded down to the size drawn, never full size (a 10 MB cover
 * in 3,000 rows), and kept in a memory cache bounded by bytes. A few downloads at a time, so a
 * fast scroll through a long list does not queue thousands of requests: a row that scrolls away
 * cancels its own.
 */
object Artwork {
    private const val TAG = "Artwork"

    /** An inline preview is a few hundred bytes; anything far larger is not one. */
    private const val MINI_MAX_BYTES = 64 * 1024
    private const val MEMORY_BYTES = 48L * 1024 * 1024

    /** The largest picture file read at all: a cover bigger than this is not decoded. */
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024

    private class Entry(val image: ImageBitmap, val bytes: Long)

    private val memory = ByteBoundedLru<String, Entry>(MEMORY_BYTES) { it.bytes }
    private val gate = Semaphore(4)

    fun cached(key: String): ImageBitmap? = synchronized(memory) { memory[key]?.image }

    private fun remember(key: String, image: ImageBitmap) = synchronized(memory) {
        memory.put(key, Entry(image, image.width.toLong() * image.height * 4))
    }

    /**
     * The thumbnail of a message's audio file ([track]), with its id asked again in this session, or
     * of a file id that is current already ([fileId], a chat photo just listed).
     */
    suspend fun thumbnail(fileId: Int, sizePx: Int, track: TrackId? = null): ImageBitmap? {
        val key = keyOf(fileId, sizePx, track) ?: return null
        cached(key)?.let { return it }
        val current = if (track != null) FileIds.of(track)?.thumbFileId ?: 0 else fileId
        if (current == 0) return null
        return download(key, current, sizePx)
    }

    /** The memory key of a thumbnail; null when there is none to show. */
    fun keyOf(fileId: Int, sizePx: Int, track: TrackId?): String? = when {
        track != null -> "t${track.chatId}:${track.messageId}@$sizePx"
        fileId != 0 -> "f$fileId@$sizePx"
        else -> null
    }

    private suspend fun download(key: String, fileId: Int, sizePx: Int): ImageBitmap? = gate.withPermit {
        // Already on the disk: no download. Else asked for at a low priority and waited for through
        // TDLib's file updates; withdrawn when the row leaves the screen or the time runs out.
        val path = withTimeoutOrNull(FileFetch.TIMEOUT_MS) {
            val td = Td.awaitAuthorizedSession().client
            FileFetch.localPath(TdFileCalls(td), fileId)
        }
        path?.takeIf { it.isNotEmpty() }?.let { decode(key, File(it), sizePx) }
    }

    /** Telegram's inline preview of a picture (about 40 pixels across), decoded once and remembered. */
    fun mini(data: ByteArray?): ImageBitmap? {
        if (data == null || data.isEmpty() || data.size > MINI_MAX_BYTES) return null
        val key = "m${data.size}:${data.contentHashCode()}"
        cached(key)?.let { return it }
        return try {
            scaled(data, 0)?.also { remember(key, it) }
        } catch (e: Throwable) {
            Logger.w(TAG, "could not decode an inline preview", e)
            null
        }
    }

    suspend fun file(file: File, sizePx: Int): ImageBitmap? {
        val key = "p${file.path}@$sizePx"
        cached(key)?.let { return it }
        return decode(key, file, sizePx)
    }

    private suspend fun decode(key: String, file: File, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile || file.length() !in 4..MAX_FILE_BYTES) return@withContext null
            if (!ImageCheck.isWhole(file)) {
                Logger.w(TAG, "cut short, not shown: ${file.name}")
                return@withContext null
            }
            scaled(file.readBytes(), sizePx)?.also { remember(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Out of memory on a huge picture, or a file that is not an image: the placeholder stays.
            Logger.w(TAG, "could not decode ${file.name}", e)
            null
        }
    }

    /** [bytes] decoded and, when larger than [sizePx] on a side, scaled down to it. 0 keeps the size. */
    private fun scaled(bytes: ByteArray, sizePx: Int): ImageBitmap? {
        val image = Image.makeFromEncoded(bytes)
        try {
            if (image.width <= 0 || image.height <= 0) return null
            val longest = maxOf(image.width, image.height)
            if (sizePx <= 0 || longest <= sizePx) return image.toComposeImageBitmap()
            val ratio = sizePx.toDouble() / longest
            val w = (image.width * ratio).toInt().coerceAtLeast(1)
            val h = (image.height * ratio).toInt().coerceAtLeast(1)
            val bitmap = Bitmap().apply { allocN32Pixels(w, h) }
            Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    null,
                    true,
                )
            }
            return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
        } finally {
            image.close()
        }
    }
}

/**
 * Whether a picture file is whole. A JPEG cut short (a cover cut off in the file's tags, or a
 * thumbnail read before it was all written) still decodes, as the top of the picture over a blank
 * area, so it is checked for its end marker (FF D9) near the end of the file. Other formats pass.
 */
object ImageCheck {
    private const val TAIL = 4096

    fun isWhole(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 4) return@use false
            val head = ByteArray(2)
            raf.readFully(head)
            if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return@use true
            val n = minOf(TAIL.toLong(), length).toInt()
            val tail = ByteArray(n)
            raf.seek(length - n)
            raf.readFully(tail)
            hasJpegEnd(tail)
        }
    }.getOrDefault(false)

    /** True when [tail], the last bytes of a JPEG, holds its end marker (trailing padding after it is allowed). */
    fun hasJpegEnd(tail: ByteArray): Boolean {
        for (i in tail.size - 2 downTo 0) {
            if (tail[i] == 0xFF.toByte() && tail[i + 1] == 0xD9.toByte()) return true
        }
        return false
    }
}

/**
 * A square of cover art. In order: the file's own cover or Telegram's thumbnail, then, with no
 * picture, the generated cover of [art] ([GeneratedCover]) for a track, album or playlist, or, for
 * a chat or an artist ([art] null), the name's initials on a colour picked from the name, so a row
 * is never a blank box. Decorative either way: the row says the name.
 *
 * Covers found online come from the lyrics and covers switch ([CoverOnline]).
 */
@Composable
fun Cover(
    name: String,
    fileId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    coverPath: String = "",
    round: Boolean = false,
    /**
     * The message whose thumbnail to show. A file id saved in the index belongs to the session
     * that scanned it, so a stored thumbnail is always looked up by its message; [fileId] alone is
     * only for ids that are current (a chat photo just listed).
     */
    track: TrackId? = null,
    /** The key of the generated cover shown when there is no picture ([com.tmplayer.music.library.CoverArt.keyOf]); null for initials. */
    art: String? = null,
    /** Telegram's inline preview, shown while the real picture downloads or when it cannot. */
    mini: ByteArray? = null,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val source = if (coverPath.isNotEmpty()) "p$coverPath@$px" else Artwork.keyOf(fileId, px, track)
    val key = source ?: "none"
    var image by remember(key) { mutableStateOf(Artwork.cached(key)) }
    // Whether the look for a real picture is over; until then a generated cover would flash first.
    var looked by remember(key) { mutableStateOf(source == null || image != null) }
    LaunchedEffect(key) {
        if (image != null || source == null) {
            looked = true
            return@LaunchedEffect
        }
        image = try {
            // A cover from the tags that cannot be shown (cut short in the file) falls back to Telegram's thumbnail.
            (if (coverPath.isNotEmpty()) Artwork.file(File(coverPath), px) else null)
                ?: Artwork.thumbnail(fileId, px, track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        looked = true
    }
    // Between the real picture and the generated one: a cover found online (the lyrics and covers
    // switch, on by default, CoverOnline). Only once the real look has ended empty, only for rows on
    // screen (the effect ends with the row), and from the device first, so a kept cover shows with
    // no generated one flashing before it.
    val switchOn by LyricsOnline.choices.online.collectAsState()
    val onlineOn = art != null && switchOn && CoverOnline.started
    var online by remember(art, px, onlineOn) {
        mutableStateOf(if (onlineOn) CoverOnline.pathOf(art.orEmpty())?.let { Artwork.cached("p$it@$px") } else null)
    }
    var onlineChecked by remember(art, px, onlineOn) { mutableStateOf(!onlineOn || online != null) }
    val wantsOnline = onlineOn && image == null && looked && online == null
    LaunchedEffect(art, px, wantsOnline) {
        if (!wantsOnline || art == null) {
            onlineChecked = true
            return@LaunchedEffect
        }
        try {
            withContext(Dispatchers.IO) { CoverOnline.cached(art) }?.let { online = Artwork.file(it, px) }
            onlineChecked = true
            if (online == null) CoverOnline.find(art)?.let { online = Artwork.file(it, px) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onlineChecked = true
        }
    }
    val shape = if (round) RoundedCornerShape(50) else RoundedCornerShape(8.dp)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val preview = remember(mini) { Artwork.mini(mini) }
        val shown = image ?: online ?: preview
        when {
            shown != null -> Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            art != null && looked && onlineChecked -> GeneratedCover(art, Modifier.size(size))
            art != null -> Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant))
            else -> Box(Modifier.size(size).background(placeholderColour(name)), contentAlignment = Alignment.Center) {
                Text(
                    initials(name),
                    color = Color.White,
                    fontSize = (size.value * 0.36f).sp,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private val palette = listOf(
    Color(0xFF5B4FE0), Color(0xFF2E7D6B), Color(0xFFB4533C), Color(0xFF3F6FB5),
    Color(0xFF8A4FA8), Color(0xFF9A7B1F), Color(0xFF3C8D3F), Color(0xFFB0416E),
)

private fun placeholderColour(name: String): Color = palette[Math.floorMod(name.hashCode(), palette.size)]

/** Up to two letters, by code point so an emoji or a non-Latin name is not cut in half. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters = words.take(2).mapNotNull { word ->
        val cp = word.codePointAt(0)
        if (Character.isLetterOrDigit(cp)) String(Character.toChars(cp)).uppercase() else null
    }
    return letters.joinToString("").ifEmpty { "♪" }
}

/**
 * A least recently used map bounded by the bytes its values weigh, not by their count. After each
 * [put] the oldest entries go until the total is back under [capBytes], however many that takes: one
 * big picture can push out several small ones. The newest entry always stays, even when it alone is
 * over the cap. Not thread safe; the caller holds a lock.
 */
internal class ByteBoundedLru<K, V>(private val capBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(64, 0.75f, true)

    var total = 0L
        private set

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    fun containsKey(key: K): Boolean = map.containsKey(key)

    fun put(key: K, value: V) {
        map.put(key, value)?.let { total -= sizeOf(it) }
        total += sizeOf(value)
        val iterator = map.entries.iterator()
        while (total > capBytes && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            total -= sizeOf(eldest.value)
            iterator.remove()
        }
    }
}
