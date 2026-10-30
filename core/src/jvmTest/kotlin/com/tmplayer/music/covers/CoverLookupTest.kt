package com.tmplayer.music.covers

import com.tmplayer.music.library.CoverArt
import com.tmplayer.music.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoverLookupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0xFF.toByte(), 0xD9.toByte())
    private var clock = 1_000_000_000L

    private fun query(key: String = "a:k1", title: String = "Morning Tide", artist: String = "Nadia Okafor", album: String = "Glass") =
        CoverQuery(title, artist, album, key)

    private class Http(var answer: (String) -> CoverHttpAnswer) : CoverHttp {
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()
        val calls = AtomicInteger()
        override fun get(url: String, headers: Map<String, String>): CoverHttpAnswer {
            calls.incrementAndGet()
            synchronized(urls) {
                urls += url
                this.headers += headers
            }
            return answer(url)
        }
    }

    private fun image() = CoverHttpAnswer(200, jpeg, "image/jpeg")
    private fun notFound() = CoverHttpAnswer(404, """{"error":"not_found","message":"x"}""".toByteArray(), "application/json")

    private fun lookup(
        http: CoverHttp,
        enabled: () -> Boolean = { true },
        network: () -> Boolean = { true },
        cache: CoverDiskCache = CoverDiskCache(folder.newFolder(), now = { clock }, minFreeBytes = 0),
        timeoutMs: Long = 6_000,
        maxInFlight: Int = 2,
    ) = CoverLookup(
        http = http,
        cache = cache,
        enabled = enabled,
        mayUseNetwork = network,
        userAgent = { "TMPlayerMusic/1.2.3 (Android)" },
        baseUrl = "https://meta.example",
        now = { clock },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        timeoutMs = timeoutMs,
        maxInFlight = maxInFlight,
    )

    private fun params(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun `the url carries only title, artist, album and size, encoded`() {
        val url = query(title = "Rain & Sun / 2", artist = "A=B ?", album = "").url("https://meta.example/")
        assertEquals("/v1/cover", URI(url).path)
        assertEquals(mapOf("title" to "Rain & Sun / 2", "artist" to "A=B ?", "size" to "500"), params(url))
    }

    @Test
    fun `a query is built from the album artist for an album, and never from a file name`() {
        val track = Track(
            chatId = 1, messageId = 2, fileId = 0, uniqueId = "u", fileName = "Morning Tide.mp3", mime = "audio/mpeg", sizeBytes = 1,
            dateSec = 0, title = "Morning Tide", artist = "Guest Singer", album = "Glass", albumArtist = "Nadia Okafor",
        )
        val album = CoverQuery.of(track, CoverArt.keyOfAlbum("k"))
        assertEquals("Nadia Okafor", album.artist)
        assertEquals("Glass", album.title)
        assertTrue(album.sendable)
        val song = CoverQuery.of(track.copy(album = ""), "s:base")
        assertEquals("", song.title)
        assertFalse(song.sendable)
        assertFalse(CoverQuery.of(track.copy(artist = "Unknown", albumArtist = ""), "a:k").sendable)
        assertFalse(CoverQuery.of(track.copy(artist = "", albumArtist = ""), "a:k").sendable)
    }

    @Test
    fun `nothing is sent while the switch is off, on held mobile data, or for an unsendable query`() = runBlocking {
        val http = Http { image() }
        assertEquals(CoverResult.Off, lookup(http, enabled = { false }).lookup(query()))
        assertEquals(CoverResult.Held, lookup(http, network = { false }).lookup(query()))
        assertEquals(CoverResult.None, lookup(http).lookup(query(artist = "")))
        assertEquals(CoverResult.None, lookup(http).lookup(query(artist = "Unknown Artist")))
        assertEquals(0, http.calls.get())
    }

    @Test
    fun `a found cover is kept, and sent with only the app's headers`() = runBlocking {
        val http = Http { image() }
        val covers = lookup(http)
        val first = covers.lookup(query())
        assertTrue(first is CoverResult.Found)
        assertTrue((first as CoverResult.Found).file.readBytes().contentEquals(jpeg))
        assertTrue(covers.lookup(query(title = "Other Track")) is CoverResult.Found)
        assertEquals(1, http.calls.get())
        assertEquals(setOf("User-Agent", "Accept"), http.headers.single().keys)
        assertEquals(first.file, covers.cached("a:k1"))
        assertEquals(first.file.path, covers.pathOf("a:k1"))
    }

    @Test
    fun `a miss is remembered for seven days, then asked again`() = runBlocking {
        val http = Http { notFound() }
        val dir = folder.newFolder()
        val covers = lookup(http, cache = CoverDiskCache(dir, now = { clock }, minFreeBytes = 0))
        assertEquals(CoverResult.None, covers.lookup(query()))
        assertEquals(CoverResult.None, covers.lookup(query()))
        assertEquals(1, http.calls.get())
        clock += CoverDiskCache.NONE_TTL_MS + 1
        val fresh = lookup(http, cache = CoverDiskCache(dir, now = { clock }, minFreeBytes = 0))
        assertEquals(CoverResult.None, fresh.lookup(query()))
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `a failure is not retried in the session, and nothing is kept on disk`() = runBlocking {
        val http = Http { CoverHttpAnswer(502, ByteArray(0), null) }
        val dir = folder.newFolder()
        val covers = lookup(http, cache = CoverDiskCache(dir, now = { clock }, minFreeBytes = 0))
        assertEquals(CoverResult.Failed, covers.lookup(query()))
        assertEquals(CoverResult.Failed, covers.lookup(query()))
        assertEquals(1, http.calls.get())
        assertEquals(0, dir.listFiles()?.size ?: 0)
        // Garbage in a 200 is a failure too.
        val garbage = Http { CoverHttpAnswer(200, "<html>".toByteArray(), "image/jpeg") }
        assertEquals(CoverResult.Failed, lookup(garbage).lookup(query()))
    }

    @Test
    fun `no connection is not remembered, so the next look may ask`() = runBlocking {
        val http = Http { throw UnknownHostException("meta.example") }
        val covers = lookup(http)
        assertEquals(CoverResult.Failed, covers.lookup(query()))
        assertEquals(CoverResult.Failed, covers.lookup(query()))
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `a 429 holds every lookup until its Retry-After has passed`() = runBlocking {
        val http = Http { CoverHttpAnswer(429, ByteArray(0), null, retryAfterSec = 30) }
        val covers = lookup(http)
        assertEquals(CoverResult.Busy, covers.lookup(query("a:1")))
        assertEquals(CoverResult.Busy, covers.lookup(query("a:2")))
        assertEquals(1, http.calls.get())
        clock += 31_000
        http.answer = { image() }
        assertTrue(covers.lookup(query("a:2")) is CoverResult.Found)
    }

    @Test
    fun `a slow gateway is given up after the timeout`() = runBlocking {
        val release = CountDownLatch(1)
        val http = Http {
            release.await(5, TimeUnit.SECONDS)
            image()
        }
        val started = System.nanoTime()
        assertEquals(CoverResult.Failed, lookup(http, timeoutMs = 200).lookup(query()))
        assertTrue(System.nanoTime() - started < 3_000_000_000L)
        release.countDown()
    }

    @Test
    fun `one request per key, and at most two at once`() = runBlocking {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val http = Http {
            val now = running.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(100)
            running.decrementAndGet()
            image()
        }
        val covers = lookup(http)
        val same = (1..5).map { async(Dispatchers.IO) { covers.lookup(query("a:same")) } }
        val many = (1..6).map { i -> async(Dispatchers.IO) { covers.lookup(query("a:$i")) } }
        (same + many).awaitAll().forEach { assertTrue(it is CoverResult.Found) }
        assertEquals(7, http.calls.get())
        assertTrue("at most two at once, saw ${most.get()}", most.get() <= 2)
    }

    @Test
    fun `a row that leaves before its turn takes its request out of the queue`() = runBlocking {
        val gate = CountDownLatch(1)
        val http = Http { url ->
            if (url.contains("Busy")) gate.await(5, TimeUnit.SECONDS)
            image()
        }
        val covers = lookup(http, maxInFlight = 1)
        val first = launch(Dispatchers.IO) { covers.lookup(query("a:busy", title = "Busy")) }
        while (http.calls.get() == 0) yield()
        val waiting = launch(Dispatchers.IO) { covers.lookup(query("a:queued")) }
        Thread.sleep(100)
        waiting.cancelAndJoin()
        gate.countDown()
        first.join()
        Thread.sleep(100)
        assertEquals(1, http.calls.get())
        // Asked again later, it goes out.
        assertTrue(covers.lookup(query("a:queued")) is CoverResult.Found)
        assertEquals(2, http.calls.get())
    }

    @Test
    fun `the disk cache drops the oldest covers past its cap, and refuses when space is short`() {
        val dir = folder.newFolder()
        val cache = CoverDiskCache(dir, now = { clock }, maxBytes = jpeg.size * 3L, minFreeBytes = 0)
        val files = (1..5).map { i ->
            cache.putImage("k$i", jpeg)!!.also { it.setLastModified(1_000L * i) }
        }
        cache.putImage("k6", jpeg)
        assertTrue(cache.sizeBytes() <= jpeg.size * 3L)
        assertFalse(files[0].exists())
        assertTrue(cache.get("k6") is CoverDiskCache.Hit.Image)
        val full = CoverDiskCache(folder.newFolder(), minFreeBytes = Long.MAX_VALUE / 2)
        assertEquals(null, full.putImage("k", jpeg))
        // A found cover replaces a remembered miss.
        cache.putNone("k7")
        assertEquals(CoverDiskCache.Hit.None, cache.get("k7"))
        cache.putImage("k7", jpeg)
        assertTrue(cache.get("k7") is CoverDiskCache.Hit.Image)
        cache.clear()
        assertEquals(null, cache.get("k7"))
    }

    @Test
    fun `a broken miss mark on disk reads as nothing kept`() {
        val dir = folder.newFolder()
        val cache = CoverDiskCache(dir, now = { clock }, minFreeBytes = 0)
        cache.putNone("k")
        dir.listFiles()!!.single().writeText("not a time")
        assertEquals(null, cache.get("k"))
        assertEquals(0, dir.listFiles()!!.size)
        File(dir, "junk.part").writeText("x")
        assertEquals(null, cache.get("junk"))
    }
}
