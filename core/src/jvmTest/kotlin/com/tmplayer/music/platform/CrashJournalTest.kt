package com.tmplayer.music.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashJournalTest {

    private fun tempDir(): File = Files.createTempDirectory("crash").toFile().apply { deleteOnExit() }

    @Test
    fun `the offer logic is once per crash`() {
        assertFalse(CrashJournal.shouldOffer(null, null))
        assertFalse(CrashJournal.shouldOffer(null, 5))
        assertTrue(CrashJournal.shouldOffer(10, null))
        assertFalse(CrashJournal.shouldOffer(10, 10))
        assertTrue(CrashJournal.shouldOffer(11, 10))
    }

    @Test
    fun `a crash is written with its trace and recent lines, and offered once`() {
        val dir = tempDir()
        val journal = CrashJournal(dir)
        assertFalse(journal.shouldOffer())
        val error = IllegalStateException("broken", RuntimeException("cause"))
        assertTrue(journal.record(error, "0.1.0 (1), debug", listOf("a line", "another"), now = 1_000))
        val crash = journal.last()!!
        assertEquals(1_000L, crash.time)
        assertTrue(crash.text.contains("IllegalStateException: broken"))
        assertTrue(crash.text.contains("Caused by: java.lang.RuntimeException: cause"))
        assertTrue(crash.text.contains("another"))
        assertFalse(File(dir, "${CrashJournal.CRASH}.part").exists())

        assertTrue(journal.shouldOffer())
        journal.markOffered()
        assertFalse(journal.shouldOffer())
        // A fresh journal (the next open) agrees: never a loop.
        assertFalse(CrashJournal(dir).shouldOffer())

        // A new crash is offered again.
        journal.record(RuntimeException("again"), "v", emptyList(), now = 2_000)
        assertTrue(journal.shouldOffer())
    }

    @Test
    fun `the record is bounded`() {
        val journal = CrashJournal(tempDir())
        journal.record(RuntimeException("x"), "v", List(5_000) { "line $it ".repeat(1_000) }, now = 1)
        val text = journal.last()!!.text
        assertTrue(text.lines().size <= 2 + CrashJournal.MAX_TRACE_LINES + CrashJournal.MAX_RECENT_LINES + 2)
        assertTrue(text.contains("line 4999"))
        assertFalse(text.contains("line 4799 "))
    }

    @Test
    fun `an unwritable folder loses the record and does not throw`() {
        val blocker = File(tempDir(), "file").apply { writeText("x") }
        val journal = CrashJournal(blocker)
        assertFalse(journal.record(RuntimeException("x"), "v", emptyList(), now = 1))
        assertNull(journal.last())
        assertFalse(journal.shouldOffer())
        journal.markOffered()
    }

    @Test
    fun `garbage records and offer files read as none`() {
        val dir = tempDir()
        val journal = CrashJournal(dir)
        for (text in listOf("", "time=", "time=abc\n", "time=-5\nx", "\u0000\u0001", "version=1\ntime=5")) {
            File(dir, CrashJournal.CRASH).writeText(text)
            assertNull(text, journal.last())
            assertFalse(journal.shouldOffer())
        }
        File(dir, CrashJournal.CRASH).writeText("time=7\nrest")
        File(dir, CrashJournal.OFFERED).writeText("not a number")
        assertTrue(journal.shouldOffer())
        File(dir, CrashJournal.OFFERED).writeText("7".repeat(1_000))
        assertTrue(journal.shouldOffer())
    }
}
