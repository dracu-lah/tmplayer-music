package com.tmplayer.music.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LogRedactorTest {

    private val plain = LogRedactor()

    @Test
    fun `known chat names go word for word, any case, longest first`() {
        val r = LogRedactor(listOf("Night Drive Mixes", "Night Drive", "Up"))
        val out = r.redact("scanning night drive mixes and Night Drive, Up next")
        assertEquals("scanning [name] and [name], Up next", out)
    }

    @Test
    fun `chat ids, message ids and other long numbers go, short ones stay`() {
        val out = plain.redact("scanning -1001234567890 message 98765432 at line 142 took 35 ms")
        assertFalse(out.contains("1234567890"))
        assertFalse(out.contains("98765432"))
        assertTrue(out.contains("line 142"))
        assertTrue(out.contains("35 ms"))
        assertTrue(out.contains("-[number]"))
    }

    @Test
    fun `phone numbers go, written any way`() {
        for (phone in listOf("+15551234567", "+1 555 123 4567", "+44-20-7946-0958", "+91 (98) 7654 3210")) {
            val out = plain.redact("sign in for $phone failed")
            assertEquals("sign in for [phone] failed", out)
        }
    }

    @Test
    fun `file names of tracks go, bare or at the end of a path, spaces and all`() {
        assertEquals("could not decode [file]", plain.redact("could not decode Some.Band-A.Song.flac"))
        assertEquals(
            "open /data/user/0/x/files/cache/music/[file] failed",
            plain.redact("open /data/user/0/x/files/cache/music/My Song (Live).mp3 failed"),
        )
        assertEquals("cover [file]", plain.redact("cover 123_45.jpg"))
        assertEquals("lyrics [file]", plain.redact("lyrics track.LRC"))
    }

    @Test
    fun `tokens, hashes and secrets go, ordinary words stay`() {
        assertEquals("api_hash=[secret] ok", plain.redact("api_hash=0123456789abcdef0123456789abcdef ok"))
        assertEquals("token: [secret]", plain.redact("token: abc"))
        assertEquals("password = [secret] then", plain.redact("password = \"hunter 2\" then"))
        assertEquals("Authorization: [secret]", plain.redact("Authorization: Basic"))
        assertEquals("Bearer [secret]", plain.redact("Bearer eyJhbGciOi"))
        assertEquals("the session closed", plain.redact("the session closed"))
        assertEquals("got [token] back", plain.redact("got 5f2b9c1d8e7a6b5c4d3e2f1a0b9c8d7e back"))
        // A bot style token: digits, a colon and a long mixed run.
        assertFalse(plain.redact("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw").contains("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
    }

    @Test
    fun `a sign in code goes`() {
        assertEquals("code [code] sent", plain.redact("code 12345 sent"))
        assertEquals("Code: [code]", plain.redact("Code: 54321"))
        assertEquals("error code ERROR_CODE_IO", plain.redact("error code ERROR_CODE_IO"))
    }

    @Test
    fun `emails and usernames go`() {
        assertEquals("mail [email]", plain.redact("mail some.one+x@example.co.uk"))
        assertEquals("from @[user] here", plain.redact("from @someone_here here"))
        assertEquals("Object@1a2b3c", plain.redact("Object@1a2b3c"))
    }

    @Test
    fun `a stack trace stays readable`() {
        val frame = "\tat com.tmplayer.music.data.LibraryScanner\$scan\$2.invokeSuspend(LibraryScanner.kt:142)"
        assertEquals(frame, plain.redact(frame))
        val name = "\tat androidx.compose.runtime.ComposerImpl.recomposeToGroupEndInternalSomething(Composer.kt:1234)"
        assertEquals(name, plain.redact(name))
    }

    @Test
    fun `what a parser quotes after JSON input goes`() {
        assertEquals(
            "Unexpected JSON token at offset 3: JSON input: [cut]",
            plain.redact("Unexpected JSON token at offset 3: JSON input: {\"text\":\"a private message\"}"),
        )
    }

    @Test
    fun `a long line is cut and says so`() {
        val out = plain.redact("x ".repeat(5_000))
        assertTrue(out.length < LogRedactor.MAX_LINE + 50)
        assertTrue(out.endsWith("[cut 8000 characters]"))
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("a?b?c\td", plain.redact("a\u0000b\u001Bc\td"))
    }

    @Test
    fun `garbage never throws`() {
        val random = Random(7)
        val names = List(50) { String(CharArray(random.nextInt(0, 40)) { random.nextInt(0, 0xD7FF).toChar() }) }
        val r = LogRedactor(names + listOf("", "   ", "(", "[a-", "\\", "x".repeat(10_000)))
        repeat(500) {
            val line = String(CharArray(random.nextInt(0, 3_000)) { random.nextInt(0, 0xFFFF).toChar() })
            r.redact(line)
        }
        r.redact("")
    }

    @Test
    fun `names are bounded`() {
        val r = LogRedactor(List(10_000) { "Chat number $it" })
        // Only the first MAX_KNOWN longest names are used; a line still comes back.
        assertTrue(r.redact("Chat number 9999 here").isNotEmpty())
    }
}
