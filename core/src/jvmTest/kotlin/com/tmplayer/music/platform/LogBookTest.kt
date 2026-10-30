package com.tmplayer.music.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

class LogBookTest {

    private fun tempDir(): File = Files.createTempDirectory("logbook").toFile().apply { deleteOnExit() }

    @Test
    fun `lines go to memory and disk, oldest first`() {
        val dir = tempDir()
        val book = LogBook(dir, clock = { 0L })
        book.add('I', "T", "one")
        book.add('W', "T", "two")
        val lines = book.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("I/T: one"))
        assertTrue(lines[1].endsWith("W/T: two"))
        assertEquals(lines, book.recent())
    }

    @Test
    fun `the files rotate and never hold more than twice the cap`() {
        val dir = tempDir()
        val cap = 4_000L
        val book = LogBook(dir, maxFileBytes = cap, ringLines = 50)
        repeat(2_000) { book.add('I', "Tag", "line $it with some padding to fill the file") }
        book.close()
        val current = File(dir, LogBook.CURRENT)
        val previous = File(dir, LogBook.PREVIOUS)
        assertTrue(current.isFile && previous.isFile)
        assertTrue(current.length() <= cap)
        assertTrue(previous.length() <= cap)
        // The newest line is the last one kept, and the oldest ones are gone.
        val lines = book.lines()
        assertTrue(lines.last().endsWith("line 1999 with some padding to fill the file"))
        assertTrue(lines.none { it.contains("line 0 with") })
        assertEquals(50, book.recent().size)
    }

    @Test
    fun `an error's trace follows on its own bounded lines`() {
        val book = LogBook(null)
        book.add('W', "T", "failed", RuntimeException("boom"))
        val lines = book.recent()
        assertTrue(lines[0].endsWith("W/T: failed"))
        assertTrue(lines[1].contains("RuntimeException: boom"))
        assertTrue(lines.size <= 1 + LogBook.MAX_TRACE_LINES)
    }

    @Test
    fun `a folder that cannot be written keeps the memory half and never throws`() {
        val blocker = File(tempDir(), "not-a-dir").apply { writeText("x") }
        val book = LogBook(blocker)
        repeat(10) { book.add('I', "T", "line $it") }
        assertTrue(book.diskFailures >= 1)
        assertEquals(10, book.recent().size)
        // Disk unreadable: the lines come from memory.
        assertEquals(10, book.lines().size)
    }

    @Test
    fun `missing files fall back to memory`() {
        val dir = tempDir()
        val book = LogBook(dir)
        book.add('I', "T", "kept")
        book.close()
        File(dir, LogBook.CURRENT).delete()
        assertEquals(listOf("kept"), book.lines().map { it.substringAfter("I/T: ") })
    }

    @Test
    fun `concurrent writers lose and tear nothing`() {
        val dir = tempDir()
        val book = LogBook(dir, maxFileBytes = 10_000_000, ringLines = 10_000)
        val threads = (0 until 8).map { t -> thread { repeat(500) { book.add('I', "T$t", "message $it") } } }
        threads.forEach { it.join() }
        val lines = book.lines()
        assertEquals(4_000, lines.size)
        assertTrue(lines.all { Regex(""".* I/T\d: message \d+""").matches(it) })
        assertEquals(4_000L, book.written)
    }
}
