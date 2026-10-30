package com.tmplayer.music.covers

import com.tmplayer.music.platform.JobFailures
import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** What a cover lookup came to. Only [Found] shows anything; the rest keep the generated cover. */
sealed interface CoverResult {
    class Found(val file: File) : CoverResult

    /** The gateway has none, or this track is not one to look up (no artist, no title). */
    data object None : CoverResult

    /** The switch is off: nothing was sent. */
    data object Off : CoverResult

    /** On mobile data without the user's yes, or with no connection: nothing was sent. */
    data object Held : CoverResult

    /** Too many lookups just now. */
    data object Busy : CoverResult

    /** The service failed, took too long, or the cover could not be kept. */
    data object Failed : CoverResult
}

/**
 * Asks our gateway's `/v1/cover` for the cover of an album (or of a song with no album), one key at
 * a time. Sends only the title, artist and album with a `User-Agent` naming the app.
 *
 * - Nothing is sent while [enabled] is false, or while [mayUseNetwork] says no (offline, or mobile
 *   data without the user's yes for downloads).
 * - A found cover is kept on the device until the cache's cap pushes it out, "none" for 7 days
 *   ([CoverDiskCache]); busy and failed answers are never kept on disk.
 * - One request per key at a time, and at most [maxInFlight] requests at all; a caller that goes away
 *   while its request still waits for a slot takes it out of the queue (a request already on the wire
 *   finishes, within [timeoutMs], and its answer is kept).
 * - Never retries by itself: a failed or refused lookup is remembered until the app restarts, and a 429
 *   holds every lookup until its `Retry-After` has passed. No timers, no loops.
 */
class CoverLookup(
    private val http: CoverHttp,
    private val cache: CoverDiskCache,
    private val enabled: () -> Boolean,
    private val mayUseNetwork: () -> Boolean = { true },
    private val userAgent: () -> String = { "TMPlayerMusic" },
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = JobFailures.scope("Covers", Dispatchers.IO),
    private val timeoutMs: Long = TIMEOUT_MS,
    maxInFlight: Int = MAX_IN_FLIGHT,
) {
    private val lock = Any()
    private val gate = Semaphore(maxInFlight)
    private val inFlight = HashMap<String, Pending>()
    private val session = LinkedHashMap<String, CoverResult>()

    @Volatile
    private var busyUntil = 0L

    /** How many requests went to the gateway; for the tests. */
    @Volatile
    var requests = 0
        private set

    private class Pending {
        lateinit var job: Deferred<CoverResult>
        var waiters = 0
        var onWire = false
    }

    /** What the device already holds for [key], with no request: the file, or null. */
    fun cached(key: String): File? {
        if (!enabled() || key.isBlank()) return null
        return (cache.get(key) as? CoverDiskCache.Hit.Image)?.file
    }

    /** The file a cover for [key] would be kept in; no disk access. */
    fun pathOf(key: String): String = cache.pathOf(key)

    /** The cover for [query]: from the device when kept, else from the gateway when allowed. */
    suspend fun lookup(query: CoverQuery): CoverResult {
        if (!enabled()) return CoverResult.Off
        if (!query.sendable) return CoverResult.None
        val key = query.key
        when (val hit = cache.get(key)) {
            is CoverDiskCache.Hit.Image -> return CoverResult.Found(hit.file)
            CoverDiskCache.Hit.None -> return CoverResult.None
            null -> Unit
        }
        val pending = synchronized(lock) {
            inFlight[key] ?: run {
                session[key]?.let { return it }
                if (busyUntil > now()) return CoverResult.Busy
                if (!runCatching(mayUseNetwork).getOrDefault(false)) return CoverResult.Held
                Pending().also { p ->
                    // Lazy, so it is in the map before it can run (and take itself out when done).
                    p.job = scope.async(start = CoroutineStart.LAZY) { run(key, query, p) }
                    inFlight[key] = p
                }
            }.also { it.waiters++ }
        }
        pending.job.start()
        try {
            return pending.job.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                pending.waiters--
                // Nobody wants it any more and it has not gone out yet: drop it from the queue.
                if (pending.waiters <= 0 && !pending.onWire) {
                    pending.job.cancel()
                    if (inFlight[key] === pending) inFlight.remove(key)
                }
            }
            throw e
        }
    }

    /** Forgets every kept cover, on disk and for the session. */
    fun clear() {
        synchronized(lock) { session.clear() }
        cache.clear()
    }

    /** Bytes the kept covers take. */
    fun sizeBytes(): Long = cache.sizeBytes()

    private suspend fun run(key: String, query: CoverQuery, pending: Pending): CoverResult {
        try {
            return gate.withPermit {
                synchronized(lock) { pending.onWire = true }
                fetch(key, query)
            }
        } finally {
            synchronized(lock) { if (inFlight[key] === pending) inFlight.remove(key) }
        }
    }

    private suspend fun fetch(key: String, query: CoverQuery): CoverResult {
        try {
            requests++
            val headers = mapOf("User-Agent" to userAgent(), "Accept" to "image/jpeg, image/png, application/json")
            // The socket's timeouts bound connect and read; this bounds the whole call.
            val call = scope.async(Dispatchers.IO) { runCatching { http.get(query.url(baseUrl), headers) } }
            val answer = withTimeoutOrNull(timeoutMs) { call.await() }
            val result = if (answer == null) {
                call.cancel()
                Logger.i(TAG, "lookup timed out")
                CoverResult.Failed
            } else {
                store(key, CoverAnswer.parse(answer.getOrThrow()))
            }
            remember(key, result)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.i(TAG, "lookup failed: ${e.javaClass.simpleName}")
            // No connection is not kept: the network may come back. Anything else waits for a restart.
            if (!e.isNoConnection()) remember(key, CoverResult.Failed)
            return CoverResult.Failed
        }
    }

    private fun store(key: String, answer: CoverAnswer): CoverResult = when (answer) {
        is CoverAnswer.Found -> cache.putImage(key, answer.bytes)?.let { CoverResult.Found(it) } ?: CoverResult.Failed
        CoverAnswer.NotFound -> {
            cache.putNone(key)
            CoverResult.None
        }
        CoverAnswer.Refused -> CoverResult.None
        is CoverAnswer.Busy -> {
            busyUntil = now() + answer.retryAfterSec * 1000
            CoverResult.Busy
        }
        CoverAnswer.Failed -> CoverResult.Failed
    }

    /** Session memory of answers not kept on disk, so a row never asks twice by itself. */
    private fun remember(key: String, result: CoverResult) {
        if (result is CoverResult.Found || result == CoverResult.Busy) return
        synchronized(lock) {
            session[key] = result
            while (session.size > MAX_SESSION) session.remove(session.keys.first())
        }
    }

    private fun Throwable.isNoConnection(): Boolean =
        this is UnknownHostException || this is ConnectException || this is NoRouteToHostException ||
            (this is IOException && cause?.isNoConnection() == true)

    companion object {
        const val BASE_URL = "https://meta.tmplayer.org"
        const val TIMEOUT_MS = 6_000L
        const val MAX_IN_FLIGHT = 2
        private const val MAX_SESSION = 2_048
        private const val TAG = "Covers"
    }
}
