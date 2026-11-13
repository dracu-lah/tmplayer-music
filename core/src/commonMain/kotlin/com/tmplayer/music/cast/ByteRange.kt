// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/cast/CastServer.kt (ByteRange), hardened.
package com.tmplayer.music.cast

/** A `Range: bytes=` header against a file of a known length. The header comes from the network: any text. */
sealed interface ByteRange {
    data class Span(val start: Long, val endInclusive: Long) : ByteRange
    data object Unsatisfiable : ByteRange

    companion object {
        /** The longest header looked at; anything longer is not a range a receiver sends. */
        private const val MAX_HEADER = 256

        /**
         * The one range asked for, or null to send the whole file (no header, a header this does
         * not understand, or several ranges, which no receiver asks for). A start past the end, an
         * end before the start and an empty file are [Unsatisfiable]. Only plain digits count as
         * numbers: signs, spaces inside a number and overflowing values are not understood.
         */
        fun parse(header: String?, total: Long): ByteRange? {
            if (header == null || header.length > MAX_HEADER) return null
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substring(6).trim()
            if (',' in spec) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty() && last.isEmpty()) return null
            if (!first.isDigits() && first.isNotEmpty()) return null
            if (!last.isDigits() && last.isNotEmpty()) return null
            if (total <= 0) return Unsatisfiable
            if (first.isEmpty()) {
                // The last N bytes.
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0) return Unsatisfiable
                return Span((total - suffix).coerceAtLeast(0), total - 1)
            }
            val start = first.toLongOrNull() ?: return null
            if (start >= total) return Unsatisfiable
            val end = if (last.isEmpty()) total - 1 else (last.toLongOrNull() ?: return null).coerceAtMost(total - 1)
            if (end < start) return Unsatisfiable
            return Span(start, end)
        }

        private fun String.isDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
    }
}
