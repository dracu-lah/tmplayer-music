package com.tmplayer.music.data

import com.tmplayer.music.library.ArtistCredits
import com.tmplayer.music.library.ArtistDisplay
import com.tmplayer.music.library.TextFold
import com.tmplayer.music.library.baseOfSongKey
import com.tmplayer.music.library.likeEscape
import com.tmplayer.music.library.playlistName
import com.tmplayer.music.library.recentSearchKey
import com.tmplayer.music.library.searchText
import com.tmplayer.music.library.searchWords
import com.tmplayer.music.lyrics.LyricsParser
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

    @Test
    fun `lyrics that are garbage parse to something or nothing, never a crash`() {
        val random = Random(11)
        val cases = listOf(
            "[", "]", "[[[[", "[00:", "[00:00", "[99999:99.999]x", "[-1:00]x", "[offset:abc]", "[offset:99999999999999999999]",
            "[00:01]".repeat(50_000), "<00:01>".repeat(10_000), "\u0000\u0001[00:01]\u0002",
        )
        for (case in cases) LyricsParser.parse(case)
        LyricsParser.parse(ByteArray(LyricsParser.MAX_BYTES + 1))
        repeat(2000) {
            LyricsParser.parse(random.nextBytes(random.nextInt(0, 4096)))
            val alphabet = "[]:.0123456789<>ab\n"
            LyricsParser.parse(String(CharArray(random.nextInt(0, 400)) { alphabet[random.nextInt(alphabet.length)] }))
        }
    }

    @Test
    fun `artist credits that are garbage split into a bounded list, never a crash`() {
        val random = Random(17)
        val alphabet = "ab ,|&/;()[]\u0000\t\n\u0007.ftandeuFEATé🎵"
        val inputs = ArrayList<String>()
        inputs += listOf("", " ", ",", "|||", ", & ,", "feat.", " feat. ", "(", ")", "\u0000\u0000", "\uD83C", "\uDFB5x")
        inputs += "a, ".repeat(100_000)
        inputs += "x".repeat(1_000_000)
        inputs += " feat. ".repeat(50_000)
        inputs += "Ana, Brio & Cato".substring(0, 9)
        repeat(2_000) {
            inputs += String(CharArray(random.nextInt(0, 80)) { alphabet[random.nextInt(alphabet.length)] })
        }
        for (input in inputs) {
            val split = try {
                ArtistCredits.split(input)
            } catch (t: Throwable) {
                throw AssertionError("ArtistCredits threw on ${input.take(40)}", t)
            }
            assertTrue(split.size <= ArtistCredits.MAX_ARTISTS)
            for (artist in split) {
                assertTrue(artist.name.isNotBlank() && artist.name.length <= ArtistCredits.MAX_NAME)
                assertTrue(artist.key.isNotEmpty())
                assertTrue(artist.name.none { it.isISOControl() })
            }
            assertEquals(split.size, split.map { it.key }.toSet().size)
            val shown = ArtistDisplay.of(input)
            assertTrue(shown.length <= TextFold.MAX_FIELD && shown.none { it == '|' || it == '\u0000' })
        }
        // Wrong typed: a number or a date stored where a credit belongs is one artist named by it.
        assertEquals(listOf("1999"), ArtistCredits.split("1999").map { it.name })
        assertEquals(listOf("2024-01-01"), ArtistCredits.split("2024-01-01").map { it.name })
    }

    @Test
    fun `search queries, playlist names and stored keys that are garbage never crash`() {
        val random = Random(13)
        val odd = listOf("", " ", "%", "_", "\\", "%%__\\", "\u0000", "\uD800", "\u0001\u0001", "x".repeat(100_000))
        repeat(3000) {
            val text = if (it < odd.size) odd[it] else String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0xFFFF).toChar() })
            searchWords(text)
            searchText(text, text)
            likeEscape(text)
            baseOfSongKey(text)
            playlistName(text)
            recentSearchKey(text)
        }
    }

    @Test
    fun `the support counters' stored list that is garbage reads as a bounded list, never a crash`() {
        val random = Random(19)
        val odd = listOf("", ";", "|", "||", ";;;;", "a|", "|1", "a|1|1", "a|x", "a|99999999999999999999", "\u0000|1", "a|1;".repeat(100_000))
        repeat(3000) {
            val text = if (it < odd.size) odd[it] else String(CharArray(random.nextInt(0, 300)) { "ab|;19-".random(random) })
            assertTrue(SupportReminder.decodeRecent(text).size <= 16)
        }
        assertTrue(SupportReminder.decodeRecent(null).isEmpty())
    }

    @Test
    fun `phone numbers, codes and country rows that are garbage never crash`() {
        val random = Random(17)
        val countries = BundledCountries.all
        val odd = listOf(
            "", "+", "++", "00", "0000000000000000", "+" + "9".repeat(10_000), "(", ")", "-", "\u0000", "\uD800",
            "+44 \u0000 7700", "٠٠٤٤", "+١٢", "+1 (242) 555-0100 ext. 12", "x".repeat(100_000),
        )
        repeat(3000) {
            val alphabet = "+0123456789 ()-.٠١٢x\u0000"
            val text = when {
                it < odd.size -> odd[it]
                it % 2 == 0 -> String(CharArray(random.nextInt(0, 300)) { alphabet[random.nextInt(alphabet.length)] })
                else -> String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0xFFFF).toChar() })
            }
            val entry = PhoneEntry().typeDial(text, countries).typeNational(text, countries)
            assertTrue("dial ${entry.dial}", entry.dial.length <= PhoneCountries.MAX_DIAL_DIGITS && entry.dial.all { c -> c in '0'..'9' })
            assertTrue("number ${entry.national}", entry.national.length <= PhoneRules.MAX_DIGITS && entry.national.all { c -> c in '0'..'9' })
            entry.problem(PhoneRules.country(countries, entry.dial, text, text))
            PhoneRules.splitInternational(text, countries)
            PhoneCountries.splitInternational(text, emptyList())
            PhoneFormat.format(PhoneRules.digits(text), text)
            val formatted = PhoneFormat.format(entry.national, text.take(40))
            PhoneFormat.toFormatted(random.nextInt(-5, 50), formatted)
            PhoneFormat.toRaw(random.nextInt(-5, 50), formatted)
            PhoneCountries.matches(countries[it % countries.size], text)
            PhoneCountries.byIso(countries, text)
            flagEmoji(text)
            DeviceRegion.pick(text, text, text)
            DeviceRegion.regionOf(listOf(text))
            PhoneEntry.restore(listOf(text, text, text, text))
            val code = CodeInput.clean(text, digits = true, length = random.nextInt(0, 9))
            assertTrue("code $code", code.length <= 64 && code.all { c -> c in '0'..'9' })
            CodeInput.clean(text, digits = false, length = 0)
            PhoneCountries.fromRows(listOf(CountryRow(text, text, listOf(text, ""), hidden = false)))
        }
    }

    @Test
    fun `crash records and log lines that are garbage never crash`() {
        val random = Random(19)
        val odd = listOf(
            "", "time=", "time=\n", "time=99999999999999999999999\n", "time=1", "\u0000time=5", "time=5\u0000\n",
            "x".repeat(200_000), "time=1\n" + "\n".repeat(10_000), "\uD800", "JSON input:", "token=", "+",
        )
        val redactor = com.tmplayer.music.platform.LogRedactor(odd)
        repeat(1_000) {
            val text = if (it < odd.size) odd[it] else String(CharArray(random.nextInt(0, 2_000)) { random.nextInt(0, 0xFFFF).toChar() })
            com.tmplayer.music.platform.CrashJournal.parse(text)
            redactor.redact(text)
        }
    }

    @Test
    fun `update feed answers that are garbage never crash`() {
        val random = Random(29)
        val good = """{"tag_name":"v0.2.0","html_url":"https://github.com/x","body":"- a\n- b",""" +
            """"assets":[{"name":"TMPlayerMusic-0.2.0.apk","size":9,"digest":"sha256:${"e".repeat(64)}",""" +
            """"browser_download_url":"https://github.com/x/TMPlayerMusic-0.2.0.apk"}]}"""
        val odd = listOf(
            "", "{", "[", "null", "1", "\"x\"", "[]", "{}", "[null,1,\"a\",[],{}]",
            """{"tag_name":5,"assets":"x"}""", """{"tag_name":"v","assets":[1,null,{}]}""",
            """{"tag_name":"v1.2.3","assets":[{"browser_download_url":5,"size":"big","digest":[]}]}""",
            """{"tag_name":"v1.2.3","assets":[{"name":"TMPlayerMusic-1.2.3.apk","size":-5,"browser_download_url":"http://insecure/x.apk"}]}""",
            """{"tag_name":"v99999999999.0.0","body":${"\"" + "- x\\n".repeat(20_000) + "\""}}""",
            """{"tag_name":"${"9".repeat(100_000)}"}""",
            "[" + "[".repeat(5_000), "{\"a\":" .repeat(3_000), "\uD800", "x".repeat(UpdateFeed.MAX_ANSWER_CHARS + 1),
        )
        val cuts = (0..good.length).map { good.take(it) }
        repeat(1_500) {
            val text = when {
                it < odd.size -> odd[it]
                it < odd.size + cuts.size -> cuts[it - odd.size]
                else -> String(CharArray(random.nextInt(0, 400)) { random.nextInt(0, 0xFFFF).toChar() })
            }
            UpdateFeed.parseGitHub(text)?.let { release ->
                assertTrue(release.changes.size <= 30)
                assertTrue(release.assets.values.all { a -> a.url.startsWith("https://") && a.size >= 0 })
            }
            UpdateFeed.parseGitHubList(text)
            UpdateFeed.parseGitHubList("[$text]")
            UpdateFeed.leadingList(text)
            UpdateFeed.keyFor(text)
            UpdateFeed.compare(text, "0.1.0")
            UpdateFeed.compare("0.1.0", text)
            ReleaseVersion.parse(text)
            ReleaseVersion.androidCode(text)
            UpdateRestart.Pending.decode(text)
            UpdateRestart.onLaunch(text, "0.1.0", 0L)
            UpdateRestart.notifyAfterReplace(text, text)
        }
    }

    @Test
    fun `name tidying and bar letters on garbage never crash`() {
        val random = Random(23)
        val odd = listOf(
            "", "#", "##", "_", "___", "#_#_", "01", "(", "(1", "(123) ", "0 - ", "1. ", "\u0000_\u0000", "\uD800_x",
            "_".repeat(100_000), "#".repeat(50_000) + "a", "01 " + "x_".repeat(30_000), "<unknown>", " UNKNOWN ARTIST ",
        )
        repeat(1_500) {
            val text = if (it < odd.size) odd[it] else String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0xFFFF).toChar() })
            val title = com.tmplayer.music.library.TitleFallback.tidyTitle(text)
            assertTrue("title grew: ${text.length} to ${title.length}", title.length <= text.length)
            com.tmplayer.music.library.TitleFallback.tidyArtist(text)
            com.tmplayer.music.library.TitleFallback.display(text, text, text, text, text)
            com.tmplayer.music.library.Letters.of(text)
        }
        com.tmplayer.music.library.TitleFallback.tidyTitle(null)
        com.tmplayer.music.library.TitleFallback.tidyArtist(null)
    }
}
