package com.tmplayer.music.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The demo's album covers, drawn in code: gradients and simple shapes, no text, no photos. Each
 * album has a [style] (the layout of shapes) and a palette (three colours), so the grids look varied
 * and rich in light and dark. Drawn once into the demo's own covers folder and kept, as they never
 * change. Debug builds only.
 */
internal object DemoCovers {
    private const val SIZE = 1024

    /** Deep, a middle and a bright accent: each palette holds up on white and on black. */
    private val palettes = listOf(
        intArrayOf(0xFF0B3D4F.toInt(), 0xFF2E8B8B.toInt(), 0xFFF2A57A.toInt()),
        intArrayOf(0xFF1B1F4B.toInt(), 0xFF6A4FB3.toInt(), 0xFFFFC7A8.toInt()),
        intArrayOf(0xFF7A1F3D.toInt(), 0xFFE0526B.toInt(), 0xFFFFB347.toInt()),
        intArrayOf(0xFF102A1E.toInt(), 0xFF3F8F5A.toInt(), 0xFFD7F27A.toInt()),
        intArrayOf(0xFF1E2228.toInt(), 0xFF56606E.toInt(), 0xFFE8C26A.toInt()),
        intArrayOf(0xFF4FA3D9.toInt(), 0xFFF29BB8.toInt(), 0xFFFFF0C9.toInt()),
        intArrayOf(0xFF241A4A.toInt(), 0xFF2D7DD2.toInt(), 0xFF7FF0E0.toInt()),
        intArrayOf(0xFF4A1414.toInt(), 0xFFB5522B.toInt(), 0xFFF6C35B.toInt()),
        intArrayOf(0xFFE9D8BE.toInt(), 0xFFC8664A.toInt(), 0xFF2F3A4A.toInt()),
        intArrayOf(0xFF3D4527.toInt(), 0xFF8E9B4E.toInt(), 0xFFF4EBCF.toInt()),
        intArrayOf(0xFF1C2A5C.toInt(), 0xFFB79CE0.toInt(), 0xFFF7E6FF.toInt()),
        intArrayOf(0xFF04140F.toInt(), 0xFF0E6B57.toInt(), 0xFF5FE3C0.toInt()),
        intArrayOf(0xFF2A0E3A.toInt(), 0xFFD9486F.toInt(), 0xFFFFD27F.toInt()),
    )

    /** The cover for [key] in [folder], drawn when it is not there yet. Never throws. */
    fun ensure(folder: File, key: String, style: Int, palette: Int) {
        runCatching {
            folder.mkdirs()
            val target = File(folder, key)
            if (target.isFile && target.length() > 0) return
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap), style, palettes[palette.mod(palettes.size)])
            val temp = File(folder, "$key.part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    private fun draw(canvas: Canvas, style: Int, c: IntArray) {
        val s = SIZE.toFloat()
        val (deep, mid, accent) = Triple(c[0], c[1], c[2])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style.mod(7)) {
            // A low sun over banded water.
            0 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, s, deep, mid, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, s, s, paint)
                paint.shader = RadialGradient(s * 0.5f, s * 0.46f, s * 0.24f, accent, blend(accent, mid, 0.35f), Shader.TileMode.CLAMP)
                canvas.drawCircle(s * 0.5f, s * 0.46f, s * 0.22f, paint)
                paint.shader = null
                for (i in 0 until 9) {
                    val y = s * 0.6f + i * s * 0.046f
                    paint.color = withAlpha(if (i % 2 == 0) deep else accent, 0.55f - i * 0.04f)
                    canvas.drawRect(0f, y, s, y + s * 0.024f, paint)
                }
                glow(canvas, paint, s * 0.5f, s * 0.46f, s * 0.5f, accent, 0.25f)
            }
            // Soft blobs, like light through frosted glass.
            1 -> {
                canvas.drawColor(deep)
                glow(canvas, paint, s * 0.25f, s * 0.3f, s * 0.7f, mid, 0.95f)
                glow(canvas, paint, s * 0.8f, s * 0.75f, s * 0.6f, accent, 0.8f)
                glow(canvas, paint, s * 0.7f, s * 0.15f, s * 0.35f, blend(mid, accent, 0.5f), 0.6f)
                glow(canvas, paint, s * 0.15f, s * 0.9f, s * 0.4f, blend(deep, accent, 0.3f), 0.5f)
            }
            // Rings around an off-centre point.
            2 -> {
                paint.shader = LinearGradient(0f, 0f, s, s, mid, deep, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, s, s, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                for (i in 1..11) {
                    paint.strokeWidth = s * (0.012f + i * 0.0015f)
                    paint.color = withAlpha(if (i % 3 == 0) accent else Color.WHITE, 0.08f + (11 - i) * 0.05f)
                    canvas.drawCircle(s * 0.68f, s * 0.36f, s * 0.055f * i, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = accent
                canvas.drawCircle(s * 0.68f, s * 0.36f, s * 0.04f, paint)
            }
            // Bauhaus blocks: quarter circles and a bar.
            3 -> {
                canvas.drawColor(blend(accent, Color.WHITE, 0.55f))
                paint.color = deep
                canvas.drawArc(RectF(-s * 0.5f, s * 0.5f, s * 0.5f, s * 1.5f), 270f, 90f, true, paint)
                paint.color = mid
                canvas.drawArc(RectF(s * 0.5f, -s * 0.5f, s * 1.5f, s * 0.5f), 90f, 90f, true, paint)
                paint.color = accent
                canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.16f, paint)
                paint.color = withAlpha(deep, 0.85f)
                canvas.drawRect(s * 0.12f, s * 0.12f, s * 0.42f, s * 0.17f, paint)
                paint.color = withAlpha(mid, 0.9f)
                canvas.drawRect(s * 0.58f, s * 0.83f, s * 0.88f, s * 0.88f, paint)
            }
            // Diagonal light bars over a dusk gradient.
            4 -> {
                paint.shader = LinearGradient(0f, s, s, 0f, deep, mid, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, s, s, paint)
                paint.shader = null
                canvas.save()
                canvas.rotate(-35f, s / 2, s / 2)
                for (i in -6..6) {
                    val x = s / 2 + i * s * 0.11f
                    paint.color = withAlpha(if (i % 2 == 0) accent else Color.WHITE, 0.10f + (6 - kotlin.math.abs(i)) * 0.045f)
                    canvas.drawRect(x, -s, x + s * 0.035f, s * 2, paint)
                }
                canvas.restore()
                glow(canvas, paint, s * 0.3f, s * 0.75f, s * 0.45f, accent, 0.35f)
            }
            // Waves stacked like dunes.
            5 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, s, accent, mid, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, s, s, paint)
                paint.shader = null
                for (layer in 0 until 6) {
                    val base = s * (0.38f + layer * 0.11f)
                    val path = Path().apply {
                        moveTo(0f, s)
                        lineTo(0f, base)
                        var x = 0f
                        while (x <= s) {
                            lineTo(x, base + sin((x / s) * 2 * PI * (1.2 + layer * 0.3) + layer).toFloat() * s * 0.035f)
                            x += 8f
                        }
                        lineTo(s, s)
                        close()
                    }
                    paint.color = blend(mid, deep, (layer + 1) / 6f)
                    canvas.drawPath(path, paint)
                }
                paint.color = withAlpha(Color.WHITE, 0.85f)
                canvas.drawCircle(s * 0.74f, s * 0.2f, s * 0.07f, paint)
            }
            // A grid of dots fading into a glow.
            else -> {
                canvas.drawColor(deep)
                glow(canvas, paint, s * 0.5f, s * 0.55f, s * 0.75f, mid, 0.9f)
                val step = s / 12f
                for (row in 0 until 12) for (col in 0 until 12) {
                    val cx = step * (col + 0.5f)
                    val cy = step * (row + 0.5f)
                    val d = kotlin.math.hypot(cx - s * 0.5f, cy - s * 0.55f) / s
                    paint.color = withAlpha(accent, (0.95f - d * 1.3f).coerceIn(0.08f, 0.95f))
                    canvas.drawCircle(cx, cy, step * (0.34f - d * 0.25f).coerceAtLeast(0.06f), paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.012f
                paint.color = withAlpha(Color.WHITE, 0.7f)
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
        // A faint vignette for depth, the same on every cover.
        paint.shader = RadialGradient(s / 2, s / 2, s * 0.75f, Color.TRANSPARENT, withAlpha(Color.BLACK, 0.28f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, s, s, paint)
        paint.shader = null
    }

    private fun glow(canvas: Canvas, paint: Paint, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        paint.shader = RadialGradient(x, y, radius, withAlpha(color, alpha), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
