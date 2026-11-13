package com.tmplayer.music.library

import kotlin.math.pow

/**
 * The ReplayGain values a file's tags carry (REPLAYGAIN_TRACK_GAIN, ALBUM_GAIN and the two peaks),
 * kept on the track. A missing value is null; a tag that does not read as a sane number is dropped,
 * never guessed. Gains are decibels, peaks are linear sample peaks (1.0 is full scale).
 */
data class ReplayGain(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    /** No gain at all: playing it changes nothing. */
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    /**
     * Compact text for the index and for a media item: "track;album;trackPeak;albumPeak", each part
     * blank when missing; an empty value is the empty string.
     */
    fun encode(): String =
        if (this == NONE) "" else listOf(trackGainDb, albumGainDb, trackPeak, albumPeak).joinToString(";") { it?.toString().orEmpty() }

    /**
     * The linear factor to play this track with: the track gain, else the album gain, clamped to
     * [MIN_GAIN_DB] to [MAX_GAIN_DB], then held so the peak does not pass full scale. Without a
     * peak the track is taken to peak at 1.0, so such a track is only ever turned down. 1.0 when
     * there is no gain.
     */
    fun factor(): Float {
        val useTrack = trackGainDb != null
        val gain = (trackGainDb ?: albumGainDb) ?: return 1f
        val peak = (if (useTrack) trackPeak ?: albumPeak else albumPeak ?: trackPeak)?.takeIf { it > 0f } ?: 1f
        val wanted = 10.0.pow(gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) / 20.0)
        val limit = 1.0 / peak
        return minOf(wanted, limit).toFloat().coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    companion object {
        val NONE = ReplayGain()

        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 12f

        /** 10^(-24/20) and 10^(12/20), rounded outward: the factor never leaves these. */
        private const val MIN_FACTOR = 0.0630f
        private const val MAX_FACTOR = 3.99f

        /** A gain beyond this either way is a broken tag, not a loud or quiet master. */
        private const val ABSURD_GAIN_DB = 60.0

        /** A peak above this is a broken tag too (a few times full scale is as far as a real one goes). */
        private const val ABSURD_PEAK = 16.0

        /** "-6.54 dB", "+3.2dB", "-6,54" or "2": the number in decibels, or null when it is not a sane one. */
        fun parseGain(text: String?): Float? {
            val bare = text?.trim()?.let { if (it.endsWith("db", ignoreCase = true)) it.dropLast(2) else it }
            val value = number(bare) ?: return null
            return value.takeIf { kotlin.math.abs(it) <= ABSURD_GAIN_DB }?.toFloat()
        }

        /** "0.987654": a linear peak above 0, or null. */
        fun parsePeak(text: String?): Float? {
            val value = number(text?.trim()) ?: return null
            return value.takeIf { it > 0.0 && it <= ABSURD_PEAK }?.toFloat()
        }

        /** The inverse of [ReplayGain.encode]; anything that does not read as that gives [NONE]. */
        fun decode(text: String?): ReplayGain {
            if (text.isNullOrBlank() || text.length > 128) return NONE
            val parts = text.split(';')
            if (parts.size != 4) return NONE
            return ReplayGain(
                trackGainDb = parseGain(parts[0]),
                albumGainDb = parseGain(parts[1]),
                trackPeak = parsePeak(parts[2]),
                albumPeak = parsePeak(parts[3]),
            ).let { if (it.isEmpty) NONE else it }
        }

        private fun number(text: String?): Double? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 } ?: return null
            val parsed = t.replace(',', '.').trimEnd().toDoubleOrNull() ?: return null
            return parsed.takeIf { it.isFinite() }
        }
    }
}
