package com.tmplayer.music.data

/**
 * Downloads kept for offline use. None exist yet (Keep offline comes after the demo); this keeps
 * the streaming code from TMPlayer asking the same question it asks there, with the answer "no".
 */
object OfflineDownloads {
    fun isDownloading(fileId: Int): Boolean = false
}
