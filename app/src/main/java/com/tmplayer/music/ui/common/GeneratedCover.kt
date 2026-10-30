package com.tmplayer.music.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.CoverSeed
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The cover shown for a track or album with no picture of its own: gradients and simple shapes,
 * no text, no letters, no imagery, picked from a stable key by [CoverArt]. One painter for the
 * whole app (rows, grids, Now Playing, the media session) and for the debug demo's covers.
 *
 * Each cover is drawn once into a small bitmap, at a size rounded up to one of a few [BUCKETS], and
 * kept in a memory cache bounded by bytes, so a list scrolls over bitmaps, not drawing: nothing
 * runs per frame and nothing runs when idle. A missing cover is not an error, so nothing here throws.
 */
object GeneratedCover {
    private const val TAG = "GeneratedCover"

    /** Drawn sizes, in pixels: a request is rounded up to the next, so a list asks for few sizes. */
    private val BUCKETS = intArrayOf(64, 96, 128, 160, 192, 256, 384, 512, 768, 1024)

    private val memory = object : LruCache<String, ImageBitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** JPEG bytes for the media session, small (a few tens of KB each) and few. */
    private val jpegs = object : LruCache<String, ByteArray>(1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Two drawings at a time, so a fast fling does not fill every core. */
    private val gate = Semaphore(2)

    /** The pixel size a request of [px] is drawn at. */
    fun bucket(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()

    private fun memoryKey(key: String, px: Int) = "$key@${bucket(px)}"

    /** The cover for [key] at [px], when it is drawn already; for the first frame of a row. */
    fun cached(key: String, px: Int): ImageBitmap? = memory.get(memoryKey(key, px))

    /** The cover for [key] at [px], drawn off the main thread when it is not in memory; null only on failure. */
    suspend fun image(key: String, px: Int): ImageBitmap? {
        val memoryKey = memoryKey(key, px)
        memory.get(memoryKey)?.let { return it }
        return gate.withPermit {
            memory.get(memoryKey)?.let { return@withPermit it }
            withContext(Dispatchers.Default) {
                render(CoverArt.seedOf(key), bucket(px))?.asImageBitmap()?.also { memory.put(memoryKey, it) }
            }
        }
    }

    /**
     * The cover for [key] as JPEG bytes of [px] a side, for the lock screen and the notification.
     * Kept in its own small cache, so every track of an album reuses one. Null on failure.
     */
    fun jpeg(key: String, px: Int, quality: Int = 85): ByteArray? {
        val cacheKey = "$key@$px"
        jpegs.get(cacheKey)?.let { return it }
        val bitmap = render(CoverArt.seedOf(key), px) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray().also { jpegs.put(cacheKey, it) }
        } catch (e: Throwable) {
            Logger.w(TAG, "a generated cover could not be encoded", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** A new bitmap of [seed] at [px] a side, or null when there is no memory for it. */
    fun render(seed: CoverSeed, px: Int): Bitmap? = try {
        val size = px.coerceIn(1, 2048)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), size, seed) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Logger.w(TAG, "a generated cover could not be drawn", e)
        null
    }

    /** Draws [seed] over the square of [px] at the top left of [canvas]. */
    fun draw(canvas: Canvas, px: Int, seed: CoverSeed) {
        val palette = CoverArt.palettes[Math.floorMod(seed.palette, CoverArt.palettes.size)]
        val s = px.toFloat()
        canvas.save()
        if (seed.mirrored) canvas.scale(-1f, 1f, s / 2, s / 2)
        Painter(canvas, s, palette[0], palette[1], palette[2]).draw(Math.floorMod(seed.style, CoverArt.STYLES))
        canvas.restore()
    }

    /** One drawing: a canvas, its side and three colours. Made per cover, never per frame. */
    private class Painter(val canvas: Canvas, val s: Float, val deep: Int, val mid: Int, val light: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        fun draw(style: Int) {
            when (style) {
                0 -> sunOverWater()
                1 -> frostedBlobs()
                2 -> rings()
                3 -> blocks()
                4 -> lightBars()
                5 -> dunes()
                else -> dotGrid()
            }
            // A faint vignette for depth, the same on every cover.
            paint.shader = RadialGradient(s / 2, s / 2, s * 0.75f, Color.TRANSPARENT, alpha(Color.BLACK, 0.28f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, s, s, paint)
            paint.shader = null
        }

        private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int) {
            paint.shader = LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, s, s, paint)
            paint.shader = null
        }

        private fun glow(x: Float, y: Float, radius: Float, color: Int, strength: Float) {
            paint.shader = RadialGradient(x, y, radius, alpha(color, strength), alpha(color, 0f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
        }

        private fun sunOverWater() {
            linear(0f, 0f, 0f, s, deep, mid)
            paint.shader = RadialGradient(s * 0.5f, s * 0.46f, s * 0.24f, light, mix(light, mid, 0.35f), Shader.TileMode.CLAMP)
            canvas.drawCircle(s * 0.5f, s * 0.46f, s * 0.22f, paint)
            paint.shader = null
            for (i in 0 until 9) {
                val y = s * 0.6f + i * s * 0.046f
                paint.color = alpha(if (i % 2 == 0) deep else light, 0.55f - i * 0.04f)
                canvas.drawRect(0f, y, s, y + s * 0.024f, paint)
            }
            glow(s * 0.5f, s * 0.46f, s * 0.5f, light, 0.25f)
        }

        private fun frostedBlobs() {
            canvas.drawColor(deep)
            glow(s * 0.25f, s * 0.3f, s * 0.7f, mid, 0.95f)
            glow(s * 0.8f, s * 0.75f, s * 0.6f, light, 0.8f)
            glow(s * 0.7f, s * 0.15f, s * 0.35f, mix(mid, light, 0.5f), 0.6f)
            glow(s * 0.15f, s * 0.9f, s * 0.4f, mix(deep, light, 0.3f), 0.5f)
        }

        private fun rings() {
            linear(0f, 0f, s, s, mid, deep)
            paint.style = Paint.Style.STROKE
            for (i in 1..11) {
                paint.strokeWidth = s * (0.012f + i * 0.0015f)
                paint.color = alpha(if (i % 3 == 0) light else Color.WHITE, 0.08f + (11 - i) * 0.05f)
                canvas.drawCircle(s * 0.68f, s * 0.36f, s * 0.055f * i, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = light
            canvas.drawCircle(s * 0.68f, s * 0.36f, s * 0.04f, paint)
        }

        private fun blocks() {
            canvas.drawColor(mix(light, Color.WHITE, 0.55f))
            paint.color = deep
            canvas.drawArc(RectF(-s * 0.5f, s * 0.5f, s * 0.5f, s * 1.5f), 270f, 90f, true, paint)
            paint.color = mid
            canvas.drawArc(RectF(s * 0.5f, -s * 0.5f, s * 1.5f, s * 0.5f), 90f, 90f, true, paint)
            paint.color = light
            canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.16f, paint)
            paint.color = alpha(deep, 0.85f)
            canvas.drawRect(s * 0.12f, s * 0.12f, s * 0.42f, s * 0.17f, paint)
            paint.color = alpha(mid, 0.9f)
            canvas.drawRect(s * 0.58f, s * 0.83f, s * 0.88f, s * 0.88f, paint)
        }

        private fun lightBars() {
            linear(0f, s, s, 0f, deep, mid)
            canvas.save()
            canvas.rotate(-35f, s / 2, s / 2)
            for (i in -6..6) {
                val x = s / 2 + i * s * 0.11f
                paint.color = alpha(if (i % 2 == 0) light else Color.WHITE, 0.10f + (6 - abs(i)) * 0.045f)
                canvas.drawRect(x, -s, x + s * 0.035f, s * 2, paint)
            }
            canvas.restore()
            glow(s * 0.3f, s * 0.75f, s * 0.45f, light, 0.35f)
        }

        private fun dunes() {
            linear(0f, 0f, 0f, s, light, mid)
            val path = Path()
            val step = (s / 128f).coerceAtLeast(1f)
            for (layer in 0 until 6) {
                val base = s * (0.38f + layer * 0.11f)
                path.rewind()
                path.moveTo(0f, s)
                path.lineTo(0f, base)
                var x = 0f
                while (x <= s + step) {
                    path.lineTo(x, base + sin((x / s) * 2 * PI * (1.2 + layer * 0.3) + layer).toFloat() * s * 0.035f)
                    x += step
                }
                path.lineTo(s, s)
                path.close()
                paint.color = mix(mid, deep, (layer + 1) / 6f)
                canvas.drawPath(path, paint)
            }
            paint.color = alpha(Color.WHITE, 0.85f)
            canvas.drawCircle(s * 0.74f, s * 0.2f, s * 0.07f, paint)
        }

        private fun dotGrid() {
            canvas.drawColor(deep)
            glow(s * 0.5f, s * 0.55f, s * 0.75f, mid, 0.9f)
            val step = s / 12f
            for (row in 0 until 12) for (col in 0 until 12) {
                val cx = step * (col + 0.5f)
                val cy = step * (row + 0.5f)
                val d = hypot(cx - s * 0.5f, cy - s * 0.55f) / s
                paint.color = alpha(light, (0.95f - d * 1.3f).coerceIn(0.08f, 0.95f))
                canvas.drawCircle(cx, cy, step * (0.34f - d * 0.25f).coerceAtLeast(0.06f), paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.012f
            paint.color = alpha(Color.WHITE, 0.7f)
            val r = s * 0.3f
            val path = Path()
            for (i in 0..6) {
                val a = -PI / 2 + i * 2 * PI / 6
                val x = s * 0.5f + (r * cos(a)).toFloat()
                val y = s * 0.55f + (r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
