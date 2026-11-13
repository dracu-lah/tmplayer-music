package com.tmplayer.music.library

/**
 * Playlists as M3U8 text: written for another player, and read back (M3U, M3U8 or a plain list of
 * names, one per line) into entries that are matched to songs already in the library.
 *
 * A list is outside data, so reading never throws: a line that is too long, blank or a comment is
 * skipped, the work is capped, and nothing is ever opened by the path a line names. Matching never
 * invents a song: an entry that finds no song, or finds several it cannot tell apart, is reported as
 * unmatched.
 */
object M3u {

    /** The most characters of a list that are looked at; the rest is dropped and reported. */
    const val MAX_CHARS = 8 * 1024 * 1024

    /** The most lines read; the rest is dropped and reported. */
    const val MAX_LINES = 60_000

    /** A line longer than this is not a name or a path, so it is skipped. */
    const val MAX_LINE = 2_000

    /** The most entries one list yields: what a playlist can hold. */
    const val MAX_ENTRIES = LibraryIndex.MAX_PLAYLIST_ITEMS

    /** The longest name or title kept for one entry. */
    private const val MAX_TEXT = 300

    private const val EXTINF = "#EXTINF:"
    private const val PLAYLIST = "#PLAYLIST:"
    private val LINE_BREAK = Regex("\r\n|\n|\r")
    private val CONTROL = Regex("\\p{Cc}")
    private val SPACES = Regex("\\s+")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]{1,15}://")
    private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/]")

    /** One entry of a list: what to look for. [durationMs] is 0 when the list does not say. */
    data class Entry(val title: String, val artist: String, val durationMs: Long, val line: Int) {
        /** The entry as the Unmatched list shows it. */
        val label: String get() = if (artist.isBlank()) title else "$artist - $title"
    }

    /** What reading a list gave. [skipped] counts lines that were not usable; [truncated] says the list was cut at a cap. */
    data class Parsed(
        val name: String?,
        val entries: List<Entry>,
        val skipped: Int,
        val truncated: Boolean,
    )

    // Writing

    /**
     * The M3U8 text of a playlist: `#EXTM3U`, then for each song `#EXTINF:<seconds or -1>,Artist - Title`
     * and a line naming the song. There is no file to point at (the audio lives in Telegram), so that
     * line repeats the label; this app reads the `#EXTINF` line and matches by name, other players show the
     * name. Line breaks and control characters in a name become spaces so one entry is always two lines.
     */
    fun export(name: String, songs: List<Song>): String {
        val out = StringBuilder("#EXTM3U\n")
        val title = clean(name)
        if (title.isNotEmpty()) out.append(PLAYLIST).append(title).append('\n')
        for (song in songs) {
            val label = label(song.artist, song.title)
            if (label.isEmpty()) continue
            val seconds = if (song.durationMs > 0) (song.durationMs + 500) / 1000 else -1
            out.append(EXTINF).append(seconds).append(',').append(label).append('\n')
            out.append(label).append('\n')
        }
        return out.toString()
    }

    private fun label(artist: String, title: String): String {
        val a = clean(artist)
        val t = clean(title)
        return if (a.isEmpty()) t else if (t.isEmpty()) a else "$a - $t"
    }

    private fun clean(text: String): String = TextFold.clip(CONTROL.replace(text, " "), MAX_TEXT).replace(SPACES, " ").trim()

    // Reading

    /**
     * Reads a list. A leading byte order mark is ignored. `#EXTINF` gives the duration and the
     * `Artist - Title` text for the line after it; any other line starting with `#` is a comment (except
     * `#PLAYLIST:`, which names the list). A line that is not a comment is an entry: when no `#EXTINF`
     * came before it, its name is read from it the way a file name is (a path or address keeps only its
     * last part, the extension and a track number are dropped, `Artist - Title` is split).
     */
    fun parse(text: String?): Parsed {
        if (text.isNullOrEmpty()) return Parsed(null, emptyList(), 0, false)
        var truncated = text.length > MAX_CHARS
        val body = (if (truncated) text.substring(0, safeCut(text, MAX_CHARS)) else text).removePrefix("\uFEFF")
        val entries = ArrayList<Entry>()
        var name: String? = null
        var skipped = 0
        var pending: Entry? = null
        var lineNo = 0
        for (raw in LINE_BREAK.splitToSequence(body)) {
            lineNo++
            if (lineNo > MAX_LINES) {
                truncated = true
                break
            }
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
            if (raw.length > MAX_LINE) {
                skipped++
                pending = null
                continue
            }
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(EXTINF, ignoreCase = true) -> pending = extinf(line.substring(EXTINF.length), lineNo)
                    line.startsWith(PLAYLIST, ignoreCase = true) -> {
                        val named = clean(line.substring(PLAYLIST.length))
                        if (named.isNotEmpty() && name == null) name = named
                    }
                }
                continue
            }
            val entry = pending?.takeIf { it.title.isNotBlank() } ?: fromName(line, lineNo, pending?.durationMs ?: 0)
            pending = null
            if (entry == null) skipped++ else entries += entry
        }
        return Parsed(name, entries, skipped, truncated)
    }

    private fun safeCut(text: String, max: Int): Int = if (max > 0 && text[max - 1].isHighSurrogate()) max - 1 else max

    /** The part after `#EXTINF:`, `<duration>[ attributes],<text>`, or null when it says nothing usable. */
    private fun extinf(rest: String, line: Int): Entry? {
        val comma = rest.indexOf(',')
        val head = if (comma < 0) rest else rest.substring(0, comma)
        val seconds = head.trim().takeWhile { it.isDigit() || it == '-' || it == '.' }.substringBefore('.').toLongOrNull() ?: -1
        val durationMs = if (seconds in 1..MAX_SECONDS) seconds * 1000 else 0L
        val text = if (comma < 0) "" else clean(rest.substring(comma + 1))
        if (text.isEmpty()) return Entry("", "", durationMs, line)
        val parts = TextFold.DASH_SEPARATOR.split(text, limit = 2)
        val artist = parts.getOrElse(0) { "" }.trim()
        val title = parts.getOrElse(1) { "" }.trim()
        return if (parts.size == 2 && artist.isNotEmpty() && title.isNotEmpty()) {
            Entry(title, artist, durationMs, line)
        } else {
            Entry(text, "", durationMs, line)
        }
    }

    /** A day; a longer "duration" is a mistake (some lists write milliseconds), so it counts as unknown. */
    private const val MAX_SECONDS = 24L * 3600

    /** An entry read from a path, an address or a plain name; null when nothing readable is left. */
    private fun fromName(line: String, lineNo: Int, durationMs: Long): Entry? {
        var name = line
        if (SCHEME.containsMatchIn(name) || WINDOWS_PATH.containsMatchIn(name) || name.contains('/') || name.contains('\\')) {
            name = name.substringBefore('?').substringBefore('#').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            name = percentDecoded(name)
        }
        name = clean(name)
        if (name.isEmpty()) return null
        val (artist, title) = TitleFallback.fromFileName(name)
        val t = clean(title)
        if (t.isEmpty()) return null
        return Entry(t, clean(artist), durationMs, lineNo)
    }

    /** `%20` and friends in a file URL's name; anything that does not decode stays as written. */
    private fun percentDecoded(text: String): String {
        if (!text.contains('%')) return text
        return try {
            java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }
    }

    // Matching

    /** Where entries are looked up; the library index implements it. */
    interface SongSource {
        /** The songs of each group base key (one song per duration cluster), by key; a key with none is absent. */
        suspend fun byBase(bases: Collection<String>): Map<String, List<Song>>

        /** Songs whose title or artist holds the words of [title], a short list: the candidates, not the answer. */
        suspend fun byTitle(title: String): List<Song>
    }

    /** Why an entry found no song. */
    enum class Miss { NotFound, Ambiguous }

    data class Unmatched(val entry: Entry, val reason: Miss)

    /**
     * What matching a list gave. [matched] is each entry with its song, in list order, without a
     * song twice ([duplicates] counts the repeats left out). [total] is the entries that were looked at.
     */
    data class Matched(
        val matched: List<Pair<Entry, Song>>,
        val unmatched: List<Unmatched>,
        val duplicates: Int,
        val total: Int,
    ) {
        val songs: List<Song> get() = matched.map { it.second }
    }

    /**
     * Matches each entry to a song by title and artist, folded the way the library folds them
     * ([GroupKey]): the same title, artist and variant, so a live version never matches the studio one.
     * With an artist, the song of that group key is taken (the one nearest in length when a recording has
     * several lengths). With no artist, the title is looked up and must name exactly one song; two songs
     * of one title that the length cannot tell apart are [Miss.Ambiguous], not a guess.
     */
    suspend fun match(entries: List<Entry>, source: SongSource): Matched {
        val capped = entries.take(MAX_ENTRIES)
        val bases = LinkedHashSet<String>()
        for (entry in capped) bases += GroupKey.of(entry.title, entry.artist, 0)
        val byBase = try {
            source.byBase(bases)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val matched = ArrayList<Pair<Entry, Song>>()
        val unmatched = ArrayList<Unmatched>()
        val seen = HashSet<String>()
        var duplicates = 0
        for (entry in capped) {
            val result = lookup(entry, byBase, source)
            val song = result.first
            when {
                song == null -> unmatched += Unmatched(entry, result.second)
                !seen.add(song.groupKey) -> duplicates++
                else -> matched += entry to song
            }
        }
        return Matched(matched, unmatched, duplicates, capped.size)
    }

    private suspend fun lookup(entry: Entry, byBase: Map<String, List<Song>>, source: SongSource): Pair<Song?, Miss> {
        val base = GroupKey.of(entry.title, entry.artist, 0)
        val direct = byBase[base].orEmpty()
        if (direct.isNotEmpty()) return nearest(direct, entry.durationMs) to Miss.NotFound
        val wantTitle = GroupKey.normaliseTitle(entry.title)
        if (wantTitle.isEmpty()) return null to Miss.NotFound
        val wantVariant = GroupKey.variantOf(entry.title)
        val wantArtist = GroupKey.primaryArtist(entry.artist)
        val found = try {
            source.byTitle(entry.title)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val same = found.filter { song ->
            GroupKey.normaliseTitle(song.title) == wantTitle && GroupKey.variantOf(song.title) == wantVariant &&
                (wantArtist.isEmpty() || GroupKey.primaryArtist(song.artist).let { it.isEmpty() || it == wantArtist })
        }.distinctBy { it.groupKey }
        if (same.isEmpty()) return null to Miss.NotFound
        if (same.size == 1) return same.first() to Miss.NotFound
        // Several songs share the title: the length can still say which, never a coin toss.
        if (entry.durationMs > 0) {
            val close = same.filter { GroupKey.sameRecording(it.durationMs, entry.durationMs, true) && it.durationMs > 0 }
            if (close.size == 1) return close.first() to Miss.NotFound
        }
        return null to Miss.Ambiguous
    }

    /** Of several songs of one group key (different lengths), the one nearest [durationMs]; the first when it is unknown. */
    private fun nearest(songs: List<Song>, durationMs: Long): Song? {
        if (songs.size == 1 || durationMs <= 0) return songs.minByOrNull { it.groupKey }
        return songs.minWithOrNull(
            compareBy<Song> { if (it.durationMs > 0) kotlin.math.abs(it.durationMs - durationMs) else Long.MAX_VALUE }.thenBy { it.groupKey },
        )
    }
}
