package com.tmplayer.music.ui.common

import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.music.covers.CoverOnline
import com.tmplayer.music.data.FileIds
import com.tmplayer.music.data.Td
import com.tmplayer.music.library.TrackId
import com.tmplayer.music.lyrics.LyricsOnline
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Small pictures for rows: a chat avatar or Telegram's album cover thumbnail by file id, or a cover
 * saved from a file's tags by path. Decoded down to the size drawn, never full size (a 10 MB cover
 * on a 1 GB phone), and kept in a memory cache bounded by bytes. A few downloads at a time, so a
 * fast fling through 3,000 rows does not queue 3,000 requests: a row that scrolls away cancels its own.
 */
object Artwork {
    private const val TAG = "Artwork"
    private val memory = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val gate = Semaphore(4)

    fun cached(key: String): ImageBitmap? = memory.get(key)

    private val tints = LruCache<String, Int>(256)

    /**
     * A colour for tinting behind a cover: its average over an 8 by 8 copy, made richer, computed
     * once per cover and remembered. Null for a picture that is nearly grey, so the theme stays.
     */
    fun tint(key: String, image: ImageBitmap): Color? {
        tints.get(key)?.let { return if (it == 0) null else Color(it) }
        val argb = runCatching {
            val small = android.graphics.Bitmap.createScaledBitmap(image.asAndroidBitmap(), 8, 8, true)
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in 0 until 8) for (x in 0 until 8) {
                val p = small.getPixel(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt(), hsv)
            if (hsv[1] < 0.12f) 0 else android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], maxOf(hsv[1], 0.45f), 0.55f))
        }.getOrDefault(0)
        tints.put(key, argb)
        return if (argb == 0) null else Color(argb)
    }

    /**
     * The thumbnail of a message's audio file ([track]), with its id asked again in this session, or of
     * a file id that is current already ([fileId], a chat photo just listed).
     */
    suspend fun thumbnail(fileId: Int, sizePx: Int, track: TrackId? = null): ImageBitmap? {
        val key = keyOf(fileId, sizePx, track) ?: return null
        memory.get(key)?.let { return it }
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

    private suspend fun download(key: String, fileId: Int, sizePx: Int): ImageBitmap? {
        return gate.withPermit {
            val path = withTimeoutOrNull(20_000) {
                val td = Td.awaitAuthorizedSession().client
                td.downloadFile(fileId, 8, 0, 0, true)
            }?.let { (it as? dev.g000sha256.tdl.TdlResult.Success)?.result?.local }
                // Only a finished download: a file still being written decodes as a strip of picture over a blank square.
                ?.takeIf { it.isDownloadingCompleted }?.path
            path?.takeIf { it.isNotEmpty() }?.let { decode(key, File(it), sizePx) }
        }
    }

    suspend fun file(file: File, sizePx: Int): ImageBitmap? {
        val key = "p${file.path}@$sizePx"
        memory.get(key)?.let { return it }
        return decode(key, file, sizePx)
    }

    private suspend fun decode(key: String, file: File, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            if (!ImageCheck.isWhole(file)) {
                Logger.w(TAG, "cut short, not shown: ${file.name}")
                return@withContext null
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            bitmap.asImageBitmap().also { memory.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Out of memory on a huge picture, or a file that is not an image: the placeholder stays.
            Logger.w(TAG, "could not decode ${file.name}", e)
            null
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
        java.io.RandomAccessFile(file, "r").use { raf ->
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
 * A square of cover art. In order: the file's own cover or Telegram's thumbnail, then (only when the
 * user turned on online lookups) a cover found online for [art] ([CoverOnline]). With no picture: the generated cover of [art] ([GeneratedCover]) for a
 * track, album or playlist, or, for a chat or an artist ([art] null), the name's initials on a colour
 * picked from the name, so a row is never a blank box. Decorative either way: the row says the name.
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
    // Between the real picture and the generated one: a cover found online (opt in, CoverOnline).
    // Only once the real look has ended empty, only for rows on screen (the effect ends with the row),
    // and from the device first, so a kept cover shows with no generated one flashing before it.
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
    var generated by remember(art, px) { mutableStateOf(art?.let { GeneratedCover.cached(it, px) }) }
    val wantsGenerated = art != null && image == null && looked && onlineChecked && online == null && generated == null
    LaunchedEffect(art, px, wantsGenerated) {
        if (!wantsGenerated) return@LaunchedEffect
        generated = try {
            GeneratedCover.image(art, px)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val shape = if (round) RoundedCornerShape(50) else RoundedCornerShape(8.dp)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val shown = image ?: online ?: generated?.takeIf { looked && onlineChecked }
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else if (art != null) {
            // Still looking for the real picture, or drawing the generated one: a quiet square.
            Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant))
        } else {
            Box(Modifier.size(size).background(placeholderColour(name)), contentAlignment = Alignment.Center) {
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
