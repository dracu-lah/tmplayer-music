package com.tmplayer.music.lyrics

import com.tmplayer.music.data.KeyValues
import com.tmplayer.music.data.MemoryKeyValues
import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** What an online lyrics lookup came to, for the lyrics view. */
sealed interface OnlineLyrics {
    /** [lyrics] came from the gateway (LRCLIB), now or from the device cache. */
    data class Found(val lyrics: Lyrics) : OnlineLyrics

    data object Instrumental : OnlineLyrics

    /** The gateway has none, or this track is not one to look up (a clip, no artist). */
    data object NotFound : OnlineLyrics

    /** The track's length is not known, so no lookup is made: it would match the wrong song. */
    data object NeedsLength : OnlineLyrics

    /** The switch is off: nothing was sent. */
    data object Off : OnlineLyrics

    /** Too many lookups just now; the next may go after [retryAfterSec]. */
    data class Busy(val retryAfterSec: Long) : OnlineLyrics

    /** No connection. */
    data object Offline : OnlineLyrics

    /** The service failed or took too long; Try again may work. */
    data object Failed : OnlineLyrics
}

/**
 * The user's lyrics choices, kept across restarts: "Look up lyrics online" (off by default; only the
 * user turns it on) and whether the first-time question was answered.
 */
class LyricsChoices(private val store: KeyValues) {
    private val _online = MutableStateFlow(runCatching { store.getBoolean(ONLINE, false) }.getOrDefault(false))
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _asked = MutableStateFlow(runCatching { store.getBoolean(ASKED, false) }.getOrDefault(false) || _online.value)

    /** True once the user answered the question (either way) or set the switch in Settings. */
    val asked: StateFlow<Boolean> = _asked.asStateFlow()

    fun setOnline(on: Boolean) {
        _online.value = on
        _asked.value = true
        runCatching {
            store.putBoolean(ONLINE, on)
            store.putBoolean(ASKED, true)
        }
    }

    /** "Not now" in the question: the switch stays off and the question is not asked again by itself. */
    fun declined() {
        _asked.value = true
        runCatching { store.putBoolean(ASKED, true) }
    }

    private companion object {
        const val ONLINE = "lyrics_online"
        const val ASKED = "lyrics_online_asked"
    }
}

/**
 * Asks our lyrics gateway for one song at a time (`plan/LYRICS-GATEWAY.md`). Sends only the title,
 * artist, album and length with a `User-Agent` naming the app; never a chat, file or account.
 *
 * - Nothing is sent while [enabled] is false, or for a song whose length is unknown.
 * - A found answer is kept on the device for 90 days, "none" for 7 ([LyricsDiskCache]); busy, offline
 *   and failed answers are never kept on disk.
 * - One request per song at a time: a second ask for the same song waits for the first.
 * - Never retries by itself. A failed or refused lookup is remembered for the session, so only the
 *   user's Try again ([lookup] with `retry`) asks again. A 429 holds every lookup until its
 *   `Retry-After` has passed, Try again included.
 * - [timeoutMs] bounds the connect, the read and the whole call.
 */
class LyricsRepository(
    private val http: LyricsHttp,
    private val cache: LyricsDiskCache,
    private val enabled: () -> Boolean,
    private val online: () -> Boolean = { true },
    private val userAgent: () -> String = { "TMPlayerMusic" },
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = JobFailures.scope("Lyrics", Dispatchers.IO),
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private val lock = Any()
    private val inFlight = HashMap<String, Deferred<OnlineLyrics>>()
    private val session = LinkedHashMap<String, OnlineLyrics>()

    @Volatile
    private var busyUntil = 0L

    /** How many requests went to the gateway; for the tests. */
    @Volatile
    var requests = 0
        private set

    /**
     * The online lyrics for [query]. [retry] is the user's Try again: it asks again after a failure
     * this session, but never while the service said to wait, and never past a kept answer.
     */
    suspend fun lookup(query: LyricsQuery, retry: Boolean = false): OnlineLyrics {
        if (!enabled()) return OnlineLyrics.Off
        when (query.skip) {
            LyricsQuery.Skip.UnknownLength -> return OnlineLyrics.NeedsLength
            LyricsQuery.Skip.NotASong -> return OnlineLyrics.NotFound
            null -> Unit
        }
        val key = query.cacheKey
        cache.get(key, query.durationSec)?.let { return it.toResult() }
        val job = synchronized(lock) {
            inFlight[key] ?: run {
                if (!retry) session[key]?.let { return it }
                val wait = busyUntil - now()
                if (wait > 0) return OnlineLyrics.Busy((wait + 999) / 1000)
                if (!online()) return OnlineLyrics.Offline
                // Lazy, so it is in the map before it can run (and take itself out of it when done).
                scope.async(start = CoroutineStart.LAZY) { fetch(key, query) }.also { inFlight[key] = it }
            }
        }
        // The call runs in the client's own scope: a screen that goes away does not cancel it and
        // cause a second request when it comes back; its answer is kept for that screen.
        job.start()
        return job.await()
    }

    /** Forgets every kept answer, on disk and for the session. */
    fun clear() {
        synchronized(lock) { session.clear() }
        cache.clear()
    }

    private suspend fun fetch(key: String, query: LyricsQuery): OnlineLyrics {
        try {
            requests++
            val headers = mapOf("User-Agent" to userAgent(), "Accept" to "application/json")
            // The socket's own timeouts bound connect and read; this bounds the whole call, even when a
            // slow body trickles in under the read timeout.
            val call = scope.async(Dispatchers.IO) { runCatching { http.get(query.url(baseUrl), headers) } }
            val answer = withTimeoutOrNull(timeoutMs) { call.await() }
            val result = if (answer == null) {
                call.cancel()
                Logger.i(TAG, "lookup timed out")
                OnlineLyrics.Failed
            } else {
                store(key, query, GatewayAnswer.parse(answer.getOrThrow()))
            }
            remember(key, result)
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val result = if (e.isNoConnection()) OnlineLyrics.Offline else OnlineLyrics.Failed
            Logger.i(TAG, "lookup failed: ${e.javaClass.simpleName}")
            if (result == OnlineLyrics.Failed) remember(key, result)
            return result
        } finally {
            synchronized(lock) { inFlight.remove(key) }
        }
    }

    private fun store(key: String, query: LyricsQuery, answer: GatewayAnswer): OnlineLyrics {
        val at = now()
        return when (answer) {
            is GatewayAnswer.Found -> {
                cache.put(key, LyricsDiskCache.Entry(LyricsDiskCache.Kind.Found, query.durationSec, at, answer.synced, answer.plain))
                OnlineLyrics.Found(answer.lyrics)
            }
            GatewayAnswer.Instrumental -> {
                cache.put(key, LyricsDiskCache.Entry(LyricsDiskCache.Kind.Instrumental, query.durationSec, at, null, null))
                OnlineLyrics.Instrumental
            }
            GatewayAnswer.NotFound -> {
                cache.put(key, LyricsDiskCache.Entry(LyricsDiskCache.Kind.None, query.durationSec, at, null, null))
                OnlineLyrics.NotFound
            }
            GatewayAnswer.Refused -> OnlineLyrics.NotFound
            is GatewayAnswer.Busy -> {
                busyUntil = at + answer.retryAfterSec * 1000
                OnlineLyrics.Busy(answer.retryAfterSec)
            }
            GatewayAnswer.Failed -> OnlineLyrics.Failed
        }
    }

    /** Session memory of answers not kept on disk, so the view never asks twice by itself. */
    private fun remember(key: String, result: OnlineLyrics) {
        if (result is OnlineLyrics.Busy || result == OnlineLyrics.Offline) return
        synchronized(lock) {
            session[key] = result
            while (session.size > MAX_SESSION) session.remove(session.keys.first())
        }
    }

    private fun LyricsDiskCache.Entry.toResult(): OnlineLyrics = when (kind) {
        LyricsDiskCache.Kind.Found -> GatewayAnswer.lyricsOf(synced, plain)?.let { OnlineLyrics.Found(it) } ?: OnlineLyrics.NotFound
        LyricsDiskCache.Kind.Instrumental -> OnlineLyrics.Instrumental
        LyricsDiskCache.Kind.None -> OnlineLyrics.NotFound
    }

    private fun Throwable.isNoConnection(): Boolean =
        this is UnknownHostException || this is ConnectException || this is NoRouteToHostException ||
            (this is IOException && cause?.isNoConnection() == true)

    companion object {
        const val BASE_URL = "https://meta.tmplayer.org"
        const val TIMEOUT_MS = 6_000L
        private const val MAX_SESSION = 256
        private const val TAG = "Lyrics"
    }
}

/**
 * The app's one lyrics client, started by the library with its folder and the user's store. Until
 * then it is off and in memory, so nothing can be sent before the user's choice is read.
 */
object LyricsOnline {
    var choices: LyricsChoices = LyricsChoices(MemoryKeyValues())
        private set

    @Volatile
    var repository: LyricsRepository? = null
        private set

    /** Where the version for the `User-Agent` comes from; the app sets it. */
    @Volatile
    var version: () -> String = { "0" }

    /** Whether the device has a connection; the app sets it. */
    @Volatile
    var online: () -> Boolean = { true }

    /** Replaces the HTTP client, for the debug demo; null for the platform's own. */
    @Volatile
    var httpOverride: LyricsHttp? = null

    fun start(dir: File, store: KeyValues) {
        choices = LyricsChoices(store)
        val http = LyricsHttp { url, headers ->
            (httpOverride ?: UrlConnectionLyricsHttp(LyricsRepository.TIMEOUT_MS.toInt())).get(url, headers)
        }
        repository = LyricsRepository(
            http = http,
            cache = LyricsDiskCache(dir),
            enabled = { choices.online.value },
            online = { runCatching { online() }.getOrDefault(true) },
            userAgent = { "TMPlayerMusic/${runCatching { version() }.getOrDefault("0")} (Android)" },
        )
    }

    /** The lookup, or [OnlineLyrics.Off] before [start]. */
    suspend fun lookup(query: LyricsQuery, retry: Boolean = false): OnlineLyrics =
        repository?.lookup(query, retry) ?: OnlineLyrics.Off

    fun clear() {
        repository?.clear()
    }
}
