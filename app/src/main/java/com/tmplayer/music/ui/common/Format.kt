package com.tmplayer.music.ui.common

import com.tmplayer.music.i18n.L
import com.tmplayer.music.library.AudioFormat
import com.tmplayer.music.library.Track
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** How the library writes lengths, sizes, dates and qualities. A 0 length is unknown, never hidden. */
object Format {

    fun duration(ms: Long): String {
        if (ms <= 0) return L.formatUnknownDuration
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
        return when {
            bytes >= 1L shl 30 -> L.formatGigabytes(number.format(bytes / (1024.0 * 1024 * 1024)))
            bytes >= 1L shl 20 -> L.formatMegabytes(number.format(bytes / (1024.0 * 1024)))
            else -> L.formatKilobytes(number.format(maxOf(1.0, bytes / 1024.0)))
        }
    }

    fun date(epochSec: Long): String =
        if (epochSec <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSec * 1000))

    /** "FLAC, 24 bit, 96 kHz" or "MP3, 320 kbps", leaving out what is not known. */
    fun quality(track: Track): String {
        val parts = ArrayList<String>()
        track.format.label.takeIf { it.isNotEmpty() && track.format != AudioFormat.OTHER }?.let(parts::add)
        if (track.format.lossless) {
            if (track.bitDepth > 0) parts += L.formatBitDepth(track.bitDepth)
            if (track.sampleRate > 0) parts += L.formatSampleRate(NumberFormat.getNumberInstance().format(track.sampleRate / 1000.0))
        } else {
            val kbps = track.bitrateKbps.takeIf { it > 0 }
                ?: if (track.durationMs > 0 && track.sizeBytes > 0) (track.sizeBytes * 8 / track.durationMs).toInt() else 0
            if (kbps > 0) parts += L.formatBitrate(kbps)
        }
        return parts.joinToString(", ")
    }
}
