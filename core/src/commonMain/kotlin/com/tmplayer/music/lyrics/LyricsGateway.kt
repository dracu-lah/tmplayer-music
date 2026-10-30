package com.tmplayer.music.lyrics

import com.tmplayer.music.library.AudioKinds
import com.tmplayer.music.library.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * What a lyrics lookup sends to our gateway (`plan/LYRICS-GATEWAY.md`): the title, artist, album and
 * length of one song, nothing else. [songKey] never leaves the device: it names the cache entry, so the
 * versions of one song share one answer.
 */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String,
    /** Whole seconds, 0 when not known. */
    val durationSec: Long,
    val songKey: String,
) {
    /** Where the answer is kept: the song key, or the folded title and artist when there is none. */
    val cacheKey: String
        get() = songKey.ifBlank { "${fold(artist)}\u0001${fold(title)}" }

    /** Why this query is not sent, or null when it may be. */
    val skip: Skip?
        get() = when {
            durationSec <= 0 -> Skip.UnknownLength
            durationSec > MAX_DURATION_SEC -> Skip.NotASong
            durationSec < MIN_DURATION_SEC -> Skip.NotASong
            title.isEmpty() || artist.isEmpty() -> Skip.NotASong
            else -> null
        }

    /** The gateway's address for this query. Only the four values; the album is left out when unknown. */
    fun url(base: String): String = buildString {
        append(base.trimEnd('/')).append("/v1/lyrics?title=").append(encode(title))
        append("&artist=").append(encode(artist))
        if (album.isNotEmpty()) append("&album=").append(encode(album))
        append("&duration=").append(durationSec)
    }

    enum class Skip {
        /** The length is missing or zero: a lookup would match the wrong song, so none is made. */
        UnknownLength,

        /** A clip, a voice note, a file with no artist, or a title that is only the file name. */
        NotASong,
    }

    companion object {
        const val MAX_FIELD = 200
        const val MIN_DURATION_SEC = 30L
        const val MAX_DURATION_SEC = 86_400L

        /**
         * The query for [track], or a query that [skip]s. A title that is just the file's name (no tags,
         * no Telegram fields) is not sent: it would be the file name, which never leaves the device.
         */
        fun of(track: Track, songKey: String = track.groupKey): LyricsQuery {
            val title = clean(track.title)
            val fromFileName = title.isNotEmpty() && title.equals(clean(AudioKinds.baseName(track.fileName)), ignoreCase = true)
            val seconds = if (track.durationMs > 0) ((track.durationMs + 500) / 1000).coerceAtLeast(1) else 0
            return LyricsQuery(
                title = if (fromFileName) "" else title,
                artist = clean(track.artist),
                album = clean(track.album),
                durationSec = seconds,
                songKey = songKey,
            )
        }

        /** Control characters out, spaces folded, trimmed, and at most [MAX_FIELD] characters. */
        fun clean(text: String): String {
            val out = StringBuilder(minOf(text.length, MAX_FIELD * 2))
            var space = false
            for (c in text) {
                if (out.length >= MAX_FIELD * 2) break
                if (c.isISOControl() || c.isWhitespace()) {
                    space = out.isNotEmpty()
                    continue
                }
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
            return out.toString().take(MAX_FIELD).trim()
        }

        private fun fold(text: String) = text.lowercase()

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}

/** What one HTTP GET answered: the status, the body (capped) and a `Retry-After` in seconds, if any. */
data class HttpAnswer(val code: Int, val body: String, val retryAfterSec: Long? = null)

/** How the lyrics client fetches a URL; tests and the debug demo stand in their own. May throw. */
fun interface LyricsHttp {
    fun get(url: String, headers: Map<String, String>): HttpAnswer
}

/** The platform's [HttpURLConnection], with the lookup's connect and read timeouts and a body cap. */
class UrlConnectionLyricsHttp(private val timeoutMs: Int) : LyricsHttp {
    override fun get(url: String, headers: Map<String, String>): HttpAnswer {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                    if (out.length > GatewayAnswer.MAX_BODY_CHARS) break
                }
                out.toString()
            }.orEmpty()
            return HttpAnswer(code, body, retryAfter)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The gateway's answer, read with OrNull forms only: unknown fields are ignored, wrong types read as
 * missing, and a body that is cut short, huge or not JSON is "nothing usable". Never throws; its line
 * is in GarbageInputTest.
 */
sealed interface GatewayAnswer {
    /** Lyrics came back; [lyrics] is synced when the LRC parsed, else plain. */
    data class Found(val lyrics: Lyrics, val synced: String?, val plain: String?) : GatewayAnswer

    data object Instrumental : GatewayAnswer

    /** The gateway looked and has none: remembered for a while. */
    data object NotFound : GatewayAnswer

    /** Our request was refused (400, 405, a wrong path): a client bug, never retried this session. */
    data object Refused : GatewayAnswer

    /** Too many lookups: wait [retryAfterSec] before the next one. */
    data class Busy(val retryAfterSec: Long) : GatewayAnswer

    /** The gateway or LRCLIB failed (5xx, garbage): a manual Try again may work. */
    data object Failed : GatewayAnswer

    companion object {
        const val MAX_BODY_CHARS = 512 * 1024
        const val DEFAULT_RETRY_SEC = 60L
        const val MAX_RETRY_SEC = 15 * 60L

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        fun parse(answer: HttpAnswer): GatewayAnswer = runCatching { read(answer) }.getOrDefault(Failed)

        private fun read(answer: HttpAnswer): GatewayAnswer {
            val root = if (answer.body.length > MAX_BODY_CHARS) null else runCatching { json.parseToJsonElement(answer.body) }.getOrNull() as? JsonObject
            return when (answer.code) {
                200 -> found(root)
                404 -> if (root?.str("error") == "not_found") NotFound else Refused
                400, 405 -> Refused
                429 -> {
                    val wait = answer.retryAfterSec ?: root?.long("retryAfter") ?: DEFAULT_RETRY_SEC
                    Busy(wait.coerceIn(1, MAX_RETRY_SEC))
                }
                else -> Failed
            }
        }

        private fun found(root: JsonObject?): GatewayAnswer {
            root ?: return Failed
            val synced = root.str("synced")?.takeIf { it.isNotBlank() }
            val plain = root.str("plain")?.takeIf { it.isNotBlank() }
            lyricsOf(synced, plain)?.let { return Found(it, synced, plain) }
            return if (root.bool("instrumental") == true) Instrumental else NotFound
        }

        /** Synced lyrics when the LRC holds timed lines, else the plain text; null when neither has any. */
        fun lyricsOf(synced: String?, plain: String?): Lyrics? {
            val timed = synced?.let(LyricsParser::parse)
            if (timed != null && timed.synced) return timed
            val text = plain?.let(LyricsParser::parse) ?: timed
            return text?.takeIf { it.synced || it.plain.isNotBlank() }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
