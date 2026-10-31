package com.tmplayer.music.lyrics

import com.tmplayer.music.data.MemoryKeyValues
import com.tmplayer.music.library.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnlineLyricsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val synced = "[00:01.00]Paper lanterns hum\n[00:05.50]Over the quiet yard"
    private val found = """{"synced":${quote(synced)},"plain":"Paper lanterns hum\nOver the quiet yard","instrumental":false,"source":"LRCLIB","extra":[1,2]}"""

    private fun quote(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    private fun query(key: String = "k1", seconds: Long = 200, title: String = "Morning Tide", artist: String = "Nadia Okafor", album: String = "Glass") =
        LyricsQuery(title, artist, album, seconds, key)

    private var clock = 1_000_000_000L

    private class Http(var answer: (String) -> HttpAnswer) : LyricsHttp {
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()
        val calls = AtomicInteger()
        override fun get(url: String, headers: Map<String, String>): HttpAnswer {
            calls.incrementAndGet()
            synchronized(urls) {
                urls += url
                this.headers += headers
            }
            return answer(url)
        }
    }

    private fun repo(
        http: LyricsHttp,
        enabled: Boolean = true,
        online: Boolean = true,
        cache: LyricsDiskCache = LyricsDiskCache(folder.newFolder(), now = { clock }),
        timeoutMs: Long = 6_000,
    ) = LyricsRepository(
        http = http,
        cache = cache,
        enabled = { enabled },
        online = { online },
        userAgent = { "TMPlayerMusic/1.2.3 (Android)" },
        baseUrl = "https://meta.example",
        now = { clock },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        timeoutMs = timeoutMs,
    )

    @Test
    fun `the url carries only the four values, encoded, and leaves out an unknown album`() {
        val url = query(album = "", title = "Rain & Sun / 2", artist = "A=B ?").url("https://meta.example/")
        val uri = URI(url)
        assertEquals("/v1/lyrics", uri.path)
        val params = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(setOf("title", "artist", "duration"), params.keys)
        assertEquals("Rain & Sun / 2", params["title"])
        assertEquals("A=B ?", params["artist"])
        assertEquals("200", params["duration"])
        assertTrue(query().url("https://meta.example").contains("&album=Glass&"))
    }

    @Test
    fun `a query from a track rounds the length and never sends the file name`() {
        val track = Track(
            chatId = 5, messageId = 6, fileId = 1, uniqueId = "u", fileName = "Secret Chat Upload.mp3", mime = "audio/mpeg",
            sizeBytes = 1, dateSec = 0, title = "Secret Chat Upload", artist = "Nadia\u0000 Okafor", durationMs = 199_600, groupKey = "g",
        )
        val q = LyricsQuery.of(track)
        assertEquals(200, q.durationSec)
        assertEquals("", q.title)
        assertEquals("Nadia Okafor", q.artist)
        assertEquals(LyricsQuery.Skip.NotASong, q.skip)
        val tagged = LyricsQuery.of(track.copy(title = "Morning Tide"))
        assertNull(tagged.skip)
        assertEquals("g", tagged.cacheKey)
        assertEquals(LyricsQuery.Skip.UnknownLength, LyricsQuery.of(track.copy(title = "Morning Tide", durationMs = 0)).skip)
        assertEquals(LyricsQuery.Skip.NotASong, LyricsQuery.of(track.copy(title = "Morning Tide", durationMs = 12_000)).skip)
        assertEquals(200, LyricsQuery.clean("x".repeat(5000)).length)
    }

    @Test
    fun `answers read as the contract says`() {
        val ok = GatewayAnswer.parse(HttpAnswer(200, found))
        assertTrue(ok is GatewayAnswer.Found && ok.lyrics.synced && ok.lyrics.lines.size == 2)
        val plainOnly = GatewayAnswer.parse(HttpAnswer(200, """{"synced":null,"plain":"Just words\nMore words","instrumental":false}"""))
        assertTrue(plainOnly is GatewayAnswer.Found && !plainOnly.lyrics.synced && plainOnly.lyrics.plain.startsWith("Just words"))
        assertEquals(GatewayAnswer.Instrumental, GatewayAnswer.parse(HttpAnswer(200, """{"synced":null,"plain":null,"instrumental":true}""")))
        assertEquals(GatewayAnswer.NotFound, GatewayAnswer.parse(HttpAnswer(200, """{"synced":5,"plain":false}""")))
        assertEquals(GatewayAnswer.NotFound, GatewayAnswer.parse(HttpAnswer(404, """{"error":"not_found"}""")))
        assertEquals(GatewayAnswer.Refused, GatewayAnswer.parse(HttpAnswer(404, """{"error":"no_route"}""")))
        assertEquals(GatewayAnswer.Refused, GatewayAnswer.parse(HttpAnswer(400, """{"error":"bad_request"}""")))
        assertEquals(GatewayAnswer.Busy(42), GatewayAnswer.parse(HttpAnswer(429, """{"error":"rate_limited","retryAfter":42}""")))
        assertEquals(GatewayAnswer.Busy(7), GatewayAnswer.parse(HttpAnswer(429, "", retryAfterSec = 7)))
        assertEquals(GatewayAnswer.Busy(GatewayAnswer.MAX_RETRY_SEC), GatewayAnswer.parse(HttpAnswer(429, "", retryAfterSec = 99_999_999)))
        assertEquals(GatewayAnswer.Failed, GatewayAnswer.parse(HttpAnswer(502, """{"error":"upstream"}""")))
        assertEquals(GatewayAnswer.Failed, GatewayAnswer.parse(HttpAnswer(200, "<html>")))
    }

    @Test
    fun `nothing is sent while the switch is off`() = runBlocking {
        val http = Http { HttpAnswer(200, found) }
        assertEquals(OnlineLyrics.Off, repo(http, enabled = false).lookup(query()))
        assertEquals(0, http.calls.get())
    }

    @Test
    fun `an unknown length is never looked up`() = runBlocking {
        val http = Http { HttpAnswer(200, found) }
        assertEquals(OnlineLyrics.NeedsLength, repo(http).lookup(query(seconds = 0)))
        assertEquals(0, http.calls.get())
    }

    @Test
    fun `found lyrics are kept for 90 days and the versions of a song share them`() = runBlocking {
        val http = Http { HttpAnswer(200, found) }
        val repo = repo(http)
        assertTrue(repo.lookup(query(seconds = 200)) is OnlineLyrics.Found)
        // Another file of the same song, a second longer: the same entry.
        assertTrue(repo.lookup(query(seconds = 201)) is OnlineLyrics.Found)
        assertEquals(1, http.calls.get())
        assertEquals("TMPlayerMusic/1.2.3 (Android)", http.headers[0]["User-Agent"])
        assertEquals(setOf("User-Agent", "Accept"), http.headers[0].keys)
        clock += 89 * LyricsDiskCache.DAY_MS
        assertTrue(repo.lookup(query()) is OnlineLyrics.Found)
        assertEquals(1, http.calls.get())
        clock += 2 * LyricsDiskCache.DAY_MS
        repo.lookup(query(), retry = true)
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `not found is kept for 7 days, then asked again`() = runBlocking {
        val http = Http { HttpAnswer(404, """{"error":"not_found"}""") }
        val cache = LyricsDiskCache(folder.newFolder(), now = { clock })
        assertEquals(OnlineLyrics.NotFound, repo(http, cache = cache).lookup(query()))
        // A new process (a new repository) still remembers it.
        assertEquals(OnlineLyrics.NotFound, repo(http, cache = cache).lookup(query(), retry = true))
        assertEquals(1, http.calls.get())
        clock += 8 * LyricsDiskCache.DAY_MS
        repo(http, cache = cache).lookup(query())
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `busy holds every lookup until its time, and is never kept`() = runBlocking {
        val http = Http { HttpAnswer(429, """{"error":"rate_limited","retryAfter":30}""", 30) }
        val repo = repo(http)
        assertEquals(OnlineLyrics.Busy(30), repo.lookup(query()))
        val held = repo.lookup(query("k2"), retry = true)
        assertTrue(held is OnlineLyrics.Busy)
        assertEquals(1, http.calls.get())
        clock += 31_000
        http.answer = { HttpAnswer(200, found) }
        assertTrue(repo.lookup(query()) is OnlineLyrics.Found)
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `no connection says offline, sends nothing, keeps nothing`() = runBlocking {
        val http = Http { HttpAnswer(200, found) }
        assertEquals(OnlineLyrics.Offline, repo(http, online = false).lookup(query()))
        assertEquals(0, http.calls.get())
        val failing = Http { throw UnknownHostException("meta.example") }
        val repo = repo(failing)
        assertEquals(OnlineLyrics.Offline, repo.lookup(query()))
        failing.answer = { HttpAnswer(200, found) }
        assertTrue(repo.lookup(query()) is OnlineLyrics.Found)
    }

    @Test
    fun `a failure is not retried by itself, only by Try again`() = runBlocking {
        val http = Http { HttpAnswer(502, """{"error":"upstream"}""") }
        val repo = repo(http)
        assertEquals(OnlineLyrics.Failed, repo.lookup(query()))
        assertEquals(OnlineLyrics.Failed, repo.lookup(query()))
        assertEquals(1, http.calls.get())
        http.answer = { HttpAnswer(200, found) }
        assertTrue(repo.lookup(query(), retry = true) is OnlineLyrics.Found)
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `a slow service times out as a failure`() = runBlocking {
        val gate = CountDownLatch(1)
        val http = Http {
            gate.await(5, TimeUnit.SECONDS)
            HttpAnswer(200, found)
        }
        val started = System.nanoTime()
        assertEquals(OnlineLyrics.Failed, repo(http, timeoutMs = 200).lookup(query()))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        gate.countDown()
    }

    @Test
    fun `the same song asked several times at once sends one request`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val http = Http {
            runBlocking { release.await() }
            HttpAnswer(200, found)
        }
        val repo = repo(http)
        val asks = (1..5).map { async(Dispatchers.IO) { repo.lookup(query()) } }
        Thread.sleep(200)
        release.complete(Unit)
        val results = asks.awaitAll()
        assertTrue(results.all { it is OnlineLyrics.Found })
        assertEquals(1, http.calls.get())
    }

    @Test
    fun `the disk cache stays under its cap and drops the oldest`() {
        val dir = folder.newFolder()
        val cache = LyricsDiskCache(dir, now = { clock }, maxBytes = 10_000, maxEntries = 3)
        for (i in 1..5) {
            assertTrue(cache.put("k$i", LyricsDiskCache.Entry(LyricsDiskCache.Kind.Found, 200, clock, "[00:01]x".repeat(10), null)))
            // Oldest first goes by the file's time, so the writes are spaced out.
            Thread.sleep(15)
        }
        assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".json") })
        assertNull(cache.get("k1", 200))
        assertTrue(cache.get("k5", 200) != null)
        // A different length under the same key is a miss, not the kept answer.
        assertNull(cache.get("k5", 260))
        assertFalse(dir.listFiles()!!.any { it.name.contains("k5") })
    }

    @Test
    fun `a broken cache file is dropped, not trusted`() {
        val dir = folder.newFolder()
        val cache = LyricsDiskCache(dir, now = { clock })
        cache.put("k", LyricsDiskCache.Entry(LyricsDiskCache.Kind.None, 200, clock, null, null))
        dir.listFiles()!!.single().writeText("{\"kind\":\"Found\",\"dur\":\"x\"")
        assertNull(cache.get("k", 200))
    }

    @Test
    fun `choices start on and remember the answer`() {
        val store = MemoryKeyValues()
        val choices = LyricsChoices(store)
        assertTrue(choices.online.value)
        assertFalse(choices.asked.value)
        choices.declined()
        assertFalse(choices.online.value)
        assertTrue(LyricsChoices(store).asked.value)
        assertFalse(LyricsChoices(store).online.value)
        choices.setOnline(true)
        assertTrue(LyricsChoices(store).online.value)
    }

    @Test
    fun `someone who answered before the default changed stays off`() {
        val store = MemoryKeyValues()
        store.putBoolean("lyrics_online_asked", true)
        assertFalse(LyricsChoices(store).online.value)
    }
}
