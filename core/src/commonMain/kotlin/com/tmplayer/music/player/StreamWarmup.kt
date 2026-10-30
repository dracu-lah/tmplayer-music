package com.tmplayer.music.player

/**
 * TMPlayer warms the next video's head before it plays. The music app's own prefetch of the next
 * track comes with gapless tuning; until then the streaming code's claim and release are no-ops.
 */
object StreamWarmup {
    suspend fun claim(fileId: Int) = Unit

    fun release(fileId: Int) = Unit
}
