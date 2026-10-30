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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.music.data.Td
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

    suspend fun thumbnail(fileId: Int, sizePx: Int): ImageBitmap? {
        if (fileId == 0) return null
        val key = "f$fileId@$sizePx"
        memory.get(key)?.let { return it }
        return gate.withPermit {
            val path = withTimeoutOrNull(20_000) {
                val td = Td.awaitAuthorizedSession().client
                td.downloadFile(fileId, 8, 0, 0, true)
            }?.let { (it as? dev.g000sha256.tdl.TdlResult.Success)?.result?.local?.path }
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
 * A square of cover art, or a placeholder made of the name's initials on a colour picked from the
 * name, so a row is never a blank box.
 */
@Composable
fun Cover(
    name: String,
    fileId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    coverPath: String = "",
    round: Boolean = false,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = if (coverPath.isNotEmpty()) "p$coverPath@$px" else "f$fileId@$px"
    var image by remember(key) { mutableStateOf(Artwork.cached(key)) }
    LaunchedEffect(key) {
        if (image != null) return@LaunchedEffect
        image = try {
            if (coverPath.isNotEmpty()) Artwork.file(File(coverPath), px) else Artwork.thumbnail(fileId, px)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val shape = if (round) RoundedCornerShape(50) else RoundedCornerShape(8.dp)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
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
