package com.tmplayer.music.player

/**
 * How big the cover handed to the media session may be. The session sends it to the lock screen,
 * the notification and Bluetooth displays as bytes in its metadata, which crosses processes in a
 * binder transaction (about 1 MB for everything in flight), so it is kept small: at most [MAX_PX]
 * on its longer side and [MAX_BYTES] encoded.
 */
object ArtworkBounds {
    const val MAX_PX = 512
    const val MAX_BYTES = 300 * 1024

    /** The largest power of two that decodes [width] by [height] no smaller than [target] on its shorter side. */
    fun sampleSize(width: Int, height: Int, target: Int = MAX_PX): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (sample < 1 shl 12 && width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** [width] by [height] scaled down to fit [max] on the longer side, keeping the shape; never up. */
    fun fit(width: Int, height: Int, max: Int = MAX_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= max && height <= max) return width to height
        return if (width >= height) {
            max to (height.toLong() * max / width).toInt().coerceAtLeast(1)
        } else {
            (width.toLong() * max / height).toInt().coerceAtLeast(1) to max
        }
    }

    /** Whether encoded bytes may go in the session's metadata as they are. */
    fun fits(bytes: ByteArray?): Boolean = bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_BYTES
}
