package com.tmplayer.music.covers

import com.tmplayer.music.library.AlbumKey
import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.Track
import com.tmplayer.music.lyrics.LyricsQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * What a cover lookup sends to our gateway (`GET /v1/cover`, `plan/LYRICS-GATEWAY.md`): the title,
 * artist and album of one track, nothing else. [key] never leaves the device: it is the generated
 * cover's key ([CoverArt.keyOf]: the album key, else the song's base key), so every track of an album
 * and every version of a song share one answer.
 */
data class CoverQuery(
    val title: String,
    val artist: String,
    val album: String,
    val key: String,
) {
    /** Whether this query may be sent: a key, a title and a real artist (not "Unknown"). */
    val sendable: Boolean
        get() = key.isNotBlank() && title.isNotEmpty() && artist.isNotEmpty() && AlbumKey.artistKey(artist).isNotEmpty()

    /** The gateway's address for this query at [size] pixels; the album is left out when unknown. */
    fun url(base: String, size: Int = SIZE_LIST): String = buildString {
        append(base.trimEnd('/')).append("/v1/cover?title=").append(encode(title))
        append("&artist=").append(encode(artist))
        if (album.isNotEmpty()) append("&album=").append(encode(album))
        append("&size=").append(size)
    }

    companion object {
        /** The size for rows, grids and Now Playing alike: one download per album serves them all. */
        const val SIZE_LIST = 500

        /**
         * The query for the cover [key] drawn for [track]. For an album key, the album artist (or the
         * track's artist) and the album; a title that is only the file's name is not sent, and the album's
         * name stands in for it. With no album and no usable title, or no artist, the query is not
         * [sendable].
         */
        fun of(track: Track, key: String): CoverQuery {
            val clean = LyricsQuery::clean
            val rawTitle = clean(track.title)
            val fromFileName = rawTitle.isNotEmpty() && rawTitle.equals(clean(AudioKinds.baseName(track.fileName)), ignoreCase = true)
            val album = clean(track.album)
            val onAlbum = CoverArt.albumKeyOf(key) != null && album.isNotEmpty()
            val artist = if (onAlbum) clean(track.albumArtist).ifEmpty { clean(track.artist) } else clean(track.artist)
            val title = (if (fromFileName) "" else rawTitle).ifEmpty { if (onAlbum) album else "" }
            return CoverQuery(title = title, artist = artist, album = album, key = key)
        }

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}

/** What one cover GET answered: the status, the body bytes (capped), its type and a `Retry-After`. */
class CoverHttpAnswer(val code: Int, val body: ByteArray, val contentType: String?, val retryAfterSec: Long? = null)

/** How the cover client fetches a URL; tests stand in their own. May throw. */
fun interface CoverHttp {
    fun get(url: String, headers: Map<String, String>): CoverHttpAnswer
}

/** The platform's [HttpURLConnection], with connect and read timeouts, no redirects and a body cap. */
class UrlConnectionCoverHttp(private val timeoutMs: Int) : CoverHttp {
    override fun get(url: String, headers: Map<String, String>): CoverHttpAnswer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val type = connection.contentType
            // A body over the cap is read one byte past it, so the parser sees it is too large.
            val cap = if (code in 200..299) CoverAnswer.MAX_IMAGE_BYTES + 1 else CoverAnswer.MAX_ERROR_BYTES + 1
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < cap) {
                    val read = input.read(buffer, 0, minOf(buffer.size, cap - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return CoverHttpAnswer(code, body, type, retryAfter)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The gateway's cover answer, read with OrNull forms only: an image only when its type is JPEG or PNG,
 * its bytes start the way that type does and it fits the cap; anything else is "nothing usable".
 * Never throws; its line is in GarbageInputTest.
 */
sealed interface CoverAnswer {
    class Found(val bytes: ByteArray) : CoverAnswer

    /** The gateway looked and found none: remembered for [CoverDiskCache.NONE_TTL_MS]. */
    data object NotFound : CoverAnswer

    /** Our request was refused (400, 405, a wrong path): a client bug, not asked again this session. */
    data object Refused : CoverAnswer

    /** Too many lookups: no cover lookup for [retryAfterSec]. */
    data class Busy(val retryAfterSec: Long) : CoverAnswer

    /** The gateway or its sources failed, or the answer was garbage. */
    data object Failed : CoverAnswer

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_BYTES = 16 * 1024
        const val DEFAULT_RETRY_SEC = 60L
        const val MAX_RETRY_SEC = 15 * 60L

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(answer: CoverHttpAnswer): CoverAnswer = runCatching { read(answer) }.getOrDefault(Failed)

        private fun read(answer: CoverHttpAnswer): CoverAnswer = when (answer.code) {
            200 -> if (isImage(answer.body, answer.contentType)) Found(answer.body) else Failed
            404 -> if (errorOf(answer)?.str("error") == "not_found") NotFound else Refused
            400, 405 -> Refused
            429 -> {
                val wait = answer.retryAfterSec ?: errorOf(answer)?.long("retryAfter") ?: DEFAULT_RETRY_SEC
                Busy(wait.coerceIn(1, MAX_RETRY_SEC))
            }
            else -> Failed
        }

        /** True for a whole JPEG or PNG within the cap whose type and first bytes agree. */
        fun isImage(bytes: ByteArray, contentType: String?): Boolean {
            if (bytes.size < 8 || bytes.size > MAX_IMAGE_BYTES) return false
            return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "image/jpeg" -> bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
                "image/png" -> bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
                else -> false
            }
        }

        private fun errorOf(answer: CoverHttpAnswer): JsonObject? {
            if (answer.body.size > MAX_ERROR_BYTES) return null
            return runCatching { json.parseToJsonElement(answer.body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
