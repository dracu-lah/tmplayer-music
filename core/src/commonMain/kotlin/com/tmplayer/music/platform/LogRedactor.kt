// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/LogExport.kt (LogScrubber).
package com.tmplayer.music.platform

/**
 * Takes what is personal out of a log line before it leaves the phone in Send logs, keeping what a
 * bug needs: the tag, the time, the message's own words and the stack trace.
 *
 * In this order: anything after "JSON input:" (a parser quoting what it read), names the app knows
 * ([known]: chat titles, longest first so a title that contains another goes whole), secrets
 * written as `token=...` or `password: ...`, a login code, e-mail addresses, the file name at the
 * end of a path and a bare file name with an audio, picture, lyrics or archive extension, phone
 * numbers written with a plus, `@usernames`, long mixed letter and digit runs (tokens, hashes),
 * then any run of six or more digits, which is how Telegram's chat, user, message and file ids
 * look. Shorter numbers stay: they are line numbers, sizes, timings and error codes.
 *
 * A line is also cut to [MAX_LINE] characters and has its control characters replaced, so a
 * binary blob that reached the log cannot make the file unreadable.
 */
class LogRedactor(known: Collection<String> = emptyList()) {

    private val names: List<Regex> = known
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= MIN_KNOWN && it.length <= MAX_KNOWN_LENGTH }
        .distinct()
        .sortedByDescending { it.length }
        .take(MAX_KNOWN)
        .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
        .toList()

    fun redact(line: String): String {
        var out = if (line.length > MAX_LINE) line.take(MAX_LINE) + " [cut ${line.length - MAX_LINE} characters]" else line
        out = CONTROL.replace(out, "?")
        out = JSON_INPUT.replace(out, "$1 [cut]")
        for (name in names) out = name.replace(out, NAME)
        out = SECRET.replace(out) { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) "${g[1]}${g[2]}[secret]" else "${g[4]}${g[5]}[secret]"
        }
        out = LOGIN_CODE.replace(out) { "${it.groupValues[1]}[code]" }
        out = EMAIL.replace(out, "[email]")
        out = PATH_FILE.replace(out) { "${it.groupValues[1]}[file]" }
        out = BARE_FILE.replace(out, "[file]")
        out = PHONE.replace(out, "[phone]")
        out = USERNAME.replace(out, "@[user]")
        out = TOKEN.replace(out) { if (it.value.any(Char::isDigit) && it.value.any(Char::isLetter)) "[token]" else it.value }
        out = DIGITS.replace(out, "[number]")
        return out
    }

    companion object {
        /** A known name shorter than this is not taken out: "Up" taken out everywhere leaves nothing readable. */
        const val MIN_KNOWN = 4

        /** Longer than any chat title; anything longer is not a name. */
        private const val MAX_KNOWN_LENGTH = 256

        /** Enough for every chat a big account picks, without the scrub taking seconds. */
        const val MAX_KNOWN = 3_000

        /** About a screen of text; a longer line is cut. */
        const val MAX_LINE = 2_000

        private const val NAME = "[name]"

        private const val EXTENSIONS =
            "mp3|m4a|m4b|aac|flac|alac|ogg|oga|opus|wav|wave|aif|aiff|ape|wv|wma|mka|dsf|dff|mp2|mpc|amr|" +
                "mp4|mkv|webm|mov|lrc|ttml|cue|m3u|m3u8|pls|jpg|jpeg|png|webp|gif|bmp|heic|zip|rar|7z|pdf|txt|apk"

        private val CONTROL = Regex("""[\u0000-\u0008\u000B-\u001F\u007F]""")

        /** kotlinx.serialization quotes what it could not parse after these words; that may be anything. */
        private val JSON_INPUT = Regex("""(JSON input:).*""", RegexOption.IGNORE_CASE)

        /**
         * `token=abc`, `api_hash: abc`, `password = "abc"`, `Bearer abc`: the word stays, the value
         * goes. Only with `=` or `:` after the word (or a space after Bearer), so "the session
         * closed" keeps its words.
         */
        private val SECRET = Regex(
            """(?i)\b(api_?hash|api_?id|hash|token|access_?token|auth_?key|password|passwd|pwd|secret|authorization|cookie|session|code_?hash)\b(\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)|\b(bearer)(\s+)(\S+)""",
        )

        /** "code 12345" or "code: 12345": a sign in code. */
        private val LOGIN_CODE = Regex("""(?i)(\bcode\W{0,3})\d{4,8}\b""")

        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

        /** The last part of a path, spaces and all, when it ends in a media, picture or document extension. */
        private val PATH_FILE = Regex("""([/\\])[^/\\\r\n]+?\.(?:$EXTENSIONS)\b""", RegexOption.IGNORE_CASE)

        /** A file name on its own, with no spaces in it: `Some.Artist-Some.Song.flac`. */
        private val BARE_FILE = Regex("""[^\s/\\:'"()\[\]]+\.(?:$EXTENSIONS)\b""", RegexOption.IGNORE_CASE)

        /** A plus, then at least seven digits, which may be spaced or hyphenated as people write them. */
        private val PHONE = Regex("""\+\d[\d \-()]{5,}\d""")

        /**
         * A Telegram username: an @ not glued to a word (so `Object@1a2b3c` and an e-mail stay out
         * of it), then five or more of what a username may hold.
         */
        private val USERNAME = Regex("""(?<![\w.@])@[A-Za-z][A-Za-z0-9_]{4,}""")

        /**
         * A run of 24 or more letters, digits and token punctuation; taken out only when it mixes
         * letters and digits, so a long Kotlin name in a stack frame stays. No slash, so a folder
         * path stays readable.
         */
        private val TOKEN = Regex("""[A-Za-z0-9_\-+=]{24,}""")

        private val DIGITS = Regex("""\d{6,}""")
    }
}
