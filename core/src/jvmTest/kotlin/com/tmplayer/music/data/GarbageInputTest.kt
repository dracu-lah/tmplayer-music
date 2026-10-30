package com.tmplayer.music.data

import com.tmplayer.music.player.SavedQueue
import com.tmplayer.music.tags.Samples
import com.tmplayer.music.tags.TagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every parser of outside data against truncated, wrong-typed, huge and random input: it must
 * return (an empty or partial answer is fine) without throwing and without hanging. A new parser
 * adds its lines here.
 */
class GarbageInputTest {

    /** Runs both tag reader entry points; either throwing fails the test with the input's name. */
    private fun tagReader(name: String, bytes: ByteArray, sizeBytes: Long = bytes.size.toLong(), tail: ByteArray? = null) {
        var swallowed: Throwable? = null
        TagReader.onInternalFailure = { swallowed = it }
        try {
            TagReader.read(bytes, "x.bin", sizeBytes, tail)
            TagReader.read(bytes, "x.mp3", -1, bytes)
            TagReader.bytesNeeded(bytes)?.let { assertTrue("$name: bytesNeeded $it", it >= 0 && it <= TagReader.MAX_NEEDED_BYTES) }
        } catch (t: Throwable) {
            throw AssertionError("TagReader threw on $name", t)
        } finally {
            TagReader.onInternalFailure = null
        }
        swallowed?.let { throw AssertionError("a tag parser threw inside read on $name", it) }
    }

    @Test
    fun tagReaderEmptyAndPrefixes() {
        tagReader("empty", ByteArray(0))
        for ((name, sample) in Samples.all) {
            for (n in 1..minOf(20, sample.size)) tagReader("$name prefix $n", sample.copyOf(n))
        }
    }

    @Test
    fun tagReaderTruncatedEverySeventhByte() {
        for ((name, sample) in Samples.all) {
            var n = 0
            while (n < sample.size) {
                tagReader("$name cut at $n", sample.copyOf(n), sizeBytes = sample.size.toLong(), tail = sample.copyOfRange(n, sample.size))
                n += 7
            }
        }
    }

    @Test
    fun tagReaderAbsurdSizes() {
        val patterns = listOf(
            byteArrayOf(0x7F, -1, -1, -1), // Int.MAX_VALUE
            byteArrayOf(-1, -1, -1, -1), // -1, or 4 GB unsigned
            byteArrayOf(-128, 0, 0, 0), // Int.MIN_VALUE
            byteArrayOf(0, 0, 0, 0), // zero
            byteArrayOf(0, 0, 0, 1), // one
        )
        for ((name, sample) in Samples.all) {
            var at = 0
            while (at + 4 <= sample.size) {
                for (p in patterns) {
                    val copy = sample.copyOf()
                    p.copyInto(copy, at)
                    tagReader("$name size ${p.toList()} at $at", copy, tail = copy)
                }
                at += if (at < 128) 1 else 5
            }
        }
    }

    @Test
    fun tagReaderRandomBuffers() {
        val random = Random(20261030)
        repeat(2000) { i ->
            val bytes = random.nextBytes(random.nextInt(0, 64 * 1024 + 1))
            // Some start with a real magic so the random part reaches the format's parser.
            if (i % 2 == 0 && bytes.size >= 12) {
                val magic = listOf("ID3\u0003\u0000\u0000", "fLaC", "OggS", "RIFF\u0000\u0000\u0000\u0000WAVE", "FORM\u0000\u0000\u0000\u0000AIFF")[i / 2 % 5]
                magic.toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
                if (i % 10 == 4) "ftyp".toByteArray().copyInto(bytes, 4)
            }
            tagReader("random $i", bytes, sizeBytes = random.nextLong(-1, Long.MAX_VALUE), tail = if (i % 3 == 0) bytes else null)
        }
    }

    @Test
    fun tagReaderValidHeaderThenTenMegabytesOfZeros() {
        val started = System.nanoTime()
        for ((name, sample) in Samples.all) {
            val bytes = ByteArray(10 * 1024 * 1024)
            sample.copyInto(bytes, 0, 0, minOf(64, sample.size))
            tagReader("$name then zeros", bytes, tail = if (name == "opus" || name == "m4a") bytes else null)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 10)
    }

    @Test
    fun `a saved queue that is garbage restores as nothing, never a crash`() {
        val good = SavedQueue(listOf(SavedQueue.Item(1, 2, "k")), current = 0, positionMs = 1000).encode()
        val cases = listOf(
            null, "", " ", "{", "[]", "null", "42", "\"text\"", "{\"items\": 5}",
            "{\"items\": [], \"current\": 0, \"positionMs\": 0}",
            "{\"items\": [{\"chatId\": \"x\"}], \"current\": 0, \"positionMs\": 0}",
            "{\"items\": [{\"chatId\": 0, \"messageId\": 0}], \"current\": 0, \"positionMs\": 0}",
            good.substring(0, good.length / 2),
            "x".repeat(5 * 1024 * 1024),
        )
        for (case in cases) assertNull(case?.take(40), SavedQueue.decode(case))
        val random = Random(7)
        repeat(2000) {
            val text = String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0x2FFF).toChar() })
            SavedQueue.decode(text)
            SavedQueue.decode(good.substring(0, random.nextInt(0, good.length)))
        }
    }

    @Test
    fun `a saved queue with values out of range is brought back in`() {
        val text = "{\"items\": [{\"chatId\": 1, \"messageId\": 2}], \"current\": 99, \"positionMs\": -5, \"repeat\": 7}"
        val queue = SavedQueue.decode(text)
        assertEquals(0, queue?.current)
        assertEquals(0L, queue?.positionMs)
        assertEquals(0, queue?.repeat)
        assertEquals(1, queue?.items?.size)
    }
}
