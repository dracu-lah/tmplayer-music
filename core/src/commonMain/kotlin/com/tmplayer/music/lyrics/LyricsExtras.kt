package com.tmplayer.music.lyrics

import com.tmplayer.music.data.KeyValues

/**
 * The user's lyrics timing per track: how many milliseconds later (plus) or earlier (minus) the lyrics
 * show, for a file whose lyrics run early or late. Kept in the app's [KeyValues] as one small text,
 * newest last, so it stays bounded ([MAX_ENTRIES]); a track with no offset has no entry. The key is the
 * playing file's id, since two copies of a song can be timed differently. A text that does not read
 * (cut short, from an older build) loses only the entries it cannot read.
 */
class LyricsOffsets(private val store: KeyValues) {
    private val lock = Any()
    private var entries: LinkedHashMap<String, Long> =
        LinkedHashMap(decode(runCatching { store.getString(KEY) }.getOrNull()))

    /** The offset for [key] in milliseconds, 0 when none is set. */
    fun get(key: String): Long = synchronized(lock) { entries[key] ?: 0L }

    /** Sets (or, for 0, forgets) the offset for [key]. Never throws. */
    fun set(key: String, offsetMs: Long) {
        if (!validKey(key)) return
        val clamped = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val text = synchronized(lock) {
            entries.remove(key)
            if (clamped != 0L) entries[key] = clamped
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            encode(entries)
        }
        runCatching { store.putString(KEY, text) }
    }

    /** Forgets every offset. */
    fun clear() {
        synchronized(lock) { entries = LinkedHashMap() }
        runCatching { store.putString(KEY, "") }
    }

    companion object {
        private const val KEY = "lyrics_offsets"
        const val STEP_MS = 100L
        const val MAX_OFFSET_MS = 30_000L
        const val MAX_ENTRIES = 500
        private const val MAX_KEY = 200
        private const val MAX_TEXT = 200_000

        /** [current] moved by [steps] of [STEP_MS], held within plus or minus [MAX_OFFSET_MS]. */
        fun step(current: Long, steps: Int): Long =
            (current.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS) + steps.toLong() * STEP_MS).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

        private fun validKey(key: String): Boolean =
            key.isNotEmpty() && key.length <= MAX_KEY && key.none { it == '\n' || it == '\t' || it == '\r' }

        fun encode(map: Map<String, Long>): String = map.entries.joinToString("\n") { "${it.value}\t${it.key}" }

        /** Reads what [encode] wrote; a line that is not `number TAB key` is skipped. Never throws. */
        fun decode(text: String?): Map<String, Long> {
            if (text.isNullOrEmpty() || text.length > MAX_TEXT) return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val value = line.substring(0, tab).toLongOrNull() ?: continue
                val key = line.substring(tab + 1)
                if (!validKey(key) || value == 0L) continue
                out.remove(key)
                out[key] = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
                while (out.size > MAX_ENTRIES) out.remove(out.keys.first())
            }
            return out
        }
    }
}

/**
 * The plain text the share sheet gets: the lyrics (or one line of them) and one short footer line with
 * the title and artist, nothing about the app. A line or lyrics that are blank give null, so nothing
 * empty is shared.
 */
object LyricsShare {
    /** Characters of lyrics one share carries; a longer text is cut at a line. */
    const val MAX_CHARS = 50_000

    private const val NOTE = "♪"

    fun line(text: String, title: String?, artist: String?): String? {
        val body = clean(text).trim().take(MAX_CHARS)
        if (body.isEmpty() || body == NOTE) return null
        return withFooter(body, title, artist)
    }

    fun all(lyrics: Lyrics, title: String?, artist: String?): String? {
        val lines = if (lyrics.synced) {
            lyrics.lines.map { clean(it.text).trim().let { t -> if (t == NOTE) "" else t } }
        } else {
            clean(lyrics.plain).lines().map { it.trim() }
        }
        // Runs of empty lines (the music-only gaps) shrink to one, and the ends are trimmed.
        val out = StringBuilder()
        var gap = false
        for (line in lines) {
            if (line.isEmpty()) {
                gap = out.isNotEmpty()
                continue
            }
            if (out.length + line.length + 2 > MAX_CHARS) break
            if (out.isNotEmpty()) out.append(if (gap) "\n\n" else "\n")
            gap = false
            out.append(line)
        }
        if (out.isEmpty()) return null
        return withFooter(out.toString(), title, artist)
    }

    private fun withFooter(body: String, title: String?, artist: String?): String {
        val parts = listOfNotNull(
            clean(title.orEmpty()).trim().take(200).takeIf { it.isNotEmpty() },
            clean(artist.orEmpty()).trim().take(200).takeIf { it.isNotEmpty() },
        )
        return if (parts.isEmpty()) body else body + "\n\n" + parts.joinToString(", ")
    }

    /** Control characters out (a tag or a chat can hold them); tabs and line breaks stay. */
    private fun clean(text: String): String = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }
}
