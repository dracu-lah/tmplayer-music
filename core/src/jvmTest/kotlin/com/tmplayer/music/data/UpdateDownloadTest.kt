package com.tmplayer.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** The APK download: the space check before it, resuming, cancelling, and the size and hash after. */
class UpdateDownloadTest {

    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val dir: File = kotlin.io.path.createTempDirectory("updates").toFile()
    private val plenty: (File) -> Long = { Long.MAX_VALUE }
    private val ranges = mutableListOf<Long>()

    private fun release(size: Long = bytes.size.toLong(), sha: String? = hash) = Release(
        "0.2.0",
        "page",
        assets = mapOf(UpdateFeed.ANDROID_UNIVERSAL to ReleaseAsset("https://x/TMPlayerMusic-0.2.0.apk", sha, size)),
    )

    /** A server that honours a range with 206, and serves [body] from there. */
    private fun serve(body: ByteArray = bytes, honoursRange: Boolean = true, stream: (InputStream) -> InputStream = { it }) {
        Updates.open = { _, from ->
            ranges += from
            if (from > 0 && honoursRange) {
                Updates.Opened(206, body.size - from, stream(ByteArrayInputStream(body, from.toInt(), body.size - from.toInt())))
            } else {
                Updates.Opened(200, body.size.toLong(), stream(ByteArrayInputStream(body)))
            }
        }
    }

    @After
    fun reset() {
        dir.deleteRecursively()
        Updates.configure(installedVersion = "0")
        Updates.dismiss()
        Updates.skip()
        Updates.backToOffer()
        Updates.skip()
    }

    @Test
    fun `a whole download is checked and kept under its own name`() = runBlocking {
        serve()
        val file = Updates.download(release(), dir, freeBytes = plenty)
        assertNotNull(file)
        assertArrayEquals(bytes, file!!.readBytes())
        assertEquals("TMPlayerMusic-0.2.0.apk", file.name)
        assertTrue(Updates.state.value is UpdateState.Ready)
        assertFalse(Updates.partFile(dir, release()).exists())
    }

    @Test
    fun `too little space stops it before a byte is fetched and says how much`() = runBlocking {
        serve()
        Updates.configure(installedVersion = "0.1.0", formatBytes = { "${it / (1024 * 1024)} MB" })
        assertNull(Updates.download(release(size = 40L * 1024 * 1024), dir, freeBytes = { 50L * 1024 * 1024 }))
        assertTrue(ranges.isEmpty())
        val message = (Updates.state.value as UpdateState.Failed).message
        assertEquals("The update needs 112 MB free and this phone has 50 MB. Free up some space and try again.", message)
    }

    @Test
    fun `space for the download and Android's copy of it`() {
        val mb = 1024L * 1024
        assertEquals(48 * mb * 2 + 32 * mb, Updates.spaceNeeded(48 * mb))
        assertEquals(Updates.UNKNOWN_SIZE_BYTES * 2 + 32 * mb, Updates.spaceNeeded(0))
    }

    @Test
    fun `a cut connection keeps the part and the next try resumes from it`() = runBlocking {
        // The first answer stops at 100 000 bytes, as a dropped connection does.
        serve(stream = { input -> LimitedStream(input, 100_000) })
        assertNull(Updates.download(release(), dir, freeBytes = plenty))
        assertTrue(Updates.state.value is UpdateState.Failed)
        assertEquals(100_000L, Updates.partFile(dir, release()).length())

        serve()
        val file = Updates.download(release(), dir, freeBytes = plenty)
        assertArrayEquals(bytes, file!!.readBytes())
        assertEquals(listOf(0L, 100_000L), ranges)
    }

    @Test
    fun `a server that ignores the range sends the whole file again`() = runBlocking {
        Updates.partFile(dir, release()).also { dir.mkdirs() }.writeBytes(bytes.copyOf(5_000))
        serve(honoursRange = false)
        val file = Updates.download(release(), dir, freeBytes = plenty)
        assertArrayEquals(bytes, file!!.readBytes())
    }

    @Test
    fun `a part of another version is deleted`() = runBlocking {
        dir.mkdirs()
        val old = File(dir, "TMPlayerMusic-0.1.9.apk.part").apply { writeBytes(ByteArray(10)) }
        serve()
        Updates.download(release(), dir, freeBytes = plenty)
        assertFalse(old.exists())
    }

    @Test
    fun `a damaged download is deleted and said so`() = runBlocking {
        serve(body = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertNull(Updates.download(release(), dir, freeBytes = plenty))
        assertEquals("The download was damaged and was not installed.", (Updates.state.value as UpdateState.Failed).message)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a download longer than GitHub said is refused`() = runBlocking {
        serve(body = bytes + ByteArray(10))
        assertNull(Updates.download(release(sha = null), dir, freeBytes = plenty))
        assertTrue(Updates.state.value is UpdateState.Failed)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `an error answer is named`() = runBlocking {
        Updates.open = { _, _ -> Updates.Opened(404, -1, null) }
        assertNull(Updates.download(release(), dir, freeBytes = plenty))
        assertEquals("GitHub answered 404. Try again in a moment.", (Updates.state.value as UpdateState.Failed).message)
    }

    @Test
    fun `cancel goes back to the offer and keeps the part for later`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        serve(stream = { input -> SlowStream(input) { started.complete(Unit) } })
        val job = async(Dispatchers.Default) { Updates.download(release(), dir, freeBytes = plenty) }
        withTimeout(5_000) { started.await() }
        Updates.cancelDownload()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertEquals(UpdateState.Available(release()), Updates.state.value)
        assertTrue(Updates.partFile(dir, release()).length() in 1 until bytes.size.toLong())
    }

    @Test
    fun `a second start while one runs does nothing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        serve(stream = { input -> SlowStream(input) { started.complete(Unit) } })
        val job = async(Dispatchers.Default) { Updates.download(release(), dir, freeBytes = plenty) }
        withTimeout(5_000) { started.await() }
        assertNull(Updates.download(release(), dir, freeBytes = plenty))
        Updates.cancelDownload()
        runCatching { job.await() }
        assertEquals(1, ranges.size)
    }

    private class LimitedStream(private val inner: InputStream, private var left: Int) : InputStream() {
        override fun read(): Int = if (left-- <= 0) -1 else inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }

    /** Hands out a kilobyte at a time, slowly, so a cancel lands part way. */
    private class SlowStream(private val inner: InputStream, private val onFirst: () -> Unit) : InputStream() {
        private var first = true
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (first) {
                first = false
            } else {
                Thread.sleep(5)
            }
            val n = inner.read(b, off, minOf(len, 1024))
            onFirst()
            return n
        }
    }
}
