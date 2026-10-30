package com.tmplayer.music.demo

import android.graphics.Bitmap
import com.tmplayer.music.library.CoverSeed
import com.tmplayer.music.ui.common.GeneratedCover
import java.io.File

/**
 * The demo's album covers, drawn in code by the app's own painter ([GeneratedCover]): gradients and
 * simple shapes, no text, no photos. Each demo album names its style and palette in [DemoCatalog],
 * so the demo grids show every look on purpose. Drawn once into the demo's own covers folder and
 * kept, as they never change. Debug builds only.
 */
internal object DemoCovers {
    private const val SIZE = 1024

    /** The cover for [key] in [folder], drawn when it is not there yet. Never throws. */
    fun ensure(folder: File, key: String, style: Int, palette: Int) {
        runCatching {
            folder.mkdirs()
            val target = File(folder, key)
            if (target.isFile && target.length() > 0) return
            val bitmap = GeneratedCover.render(CoverSeed(style, palette), SIZE) ?: return
            val temp = File(folder, "$key.part")
            try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            if (!temp.renameTo(target)) temp.delete()
        }
    }
}
