// From TMPlayer (GPL-3.0), core/src/commonMain/kotlin/com/tmplayer/data/LogExport.kt.
package com.tmplayer.music.platform

/**
 * The text Send logs shares: a short header with the facts a bug report needs, the last crash if
 * there is one, then the newest log lines that fit in [maxBytes], every line through [LogRedactor].
 *
 * The header holds only the app version, the Android version, the device model and counts. No
 * account, no chat, no time zone, no language. It is for the maintainer, who reads reports in
 * English, so it is not in the catalog.
 */
object LogReport {

    /** About 1 MB: small enough for any mail app or chat to take as an attachment. */
    const val MAX_BYTES = 1_000_000

    /** The crash part's own share of the file, so a huge trace cannot push every log line out. */
    const val MAX_CRASH_BYTES = 96_000

    class Facts(
        val appName: String,
        /** As About shows it: name, code and build type. */
        val version: String,
        val android: String,
        val device: String,
        /** Background jobs that failed and were contained in this run ([JobFailures.count]). */
        val containedFailures: Int,
    )

    fun fileName(stamp: String): String = "tmplayer-music-logs-${stamp.filter { it.isLetterOrDigit() || it == '-' }}.txt"

    fun build(
        facts: Facts,
        lines: List<String>,
        crash: CrashJournal.Crash? = null,
        known: Collection<String> = emptyList(),
        maxBytes: Int = MAX_BYTES,
    ): String {
        val redactor = LogRedactor(known)
        val crashLines = crash?.text?.split('\n')?.filter { it.isNotEmpty() }?.map(redactor::redact).orEmpty()
        val crashKept = newestThatFit(crashLines, MAX_CRASH_BYTES.coerceAtMost(maxBytes / 2))

        val header = buildString {
            appendLine("${facts.appName} logs")
            appendLine("App version: ${oneLine(facts.version)}")
            appendLine("Android: ${oneLine(facts.android)}")
            appendLine("Device: ${oneLine(facts.device)}")
            appendLine("Background failures contained in this run: ${facts.containedFailures}")
            appendLine(
                "Chat names, long numbers, phone numbers, @usernames, file names and secrets are " +
                    "replaced with placeholders such as [name] and [number].",
            )
        }
        val crashPart = if (crash == null) "" else buildString {
            appendLine()
            appendLine("Last crash (${crashKept.size} of ${crashLines.size} lines):")
            crashKept.forEach { appendLine(it) }
        }
        // The counts line is written after the lines are chosen; its size is reserved first.
        val reserve = 200
        val budget = (maxBytes - utf8(header) - utf8(crashPart) - reserve).coerceAtLeast(0)
        val redacted = lines.map(redactor::redact)
        val kept = newestThatFit(redacted, budget)
        return buildString {
            append(header)
            appendLine("Log lines: ${kept.size} of ${lines.size} (newest kept, at most about ${maxBytes / 1000} KB)")
            append(crashPart)
            appendLine()
            appendLine("Log:")
            kept.forEach { appendLine(it) }
        }
    }

    /** The newest lines whose total, with their line breaks, is at most [budget] bytes; oldest first. */
    fun newestThatFit(lines: List<String>, budget: Int): List<String> {
        var left = budget
        var from = lines.size
        while (from > 0) {
            val cost = utf8(lines[from - 1]) + 1
            if (cost > left) break
            left -= cost
            from--
        }
        return lines.subList(from, lines.size)
    }

    private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ').take(200)

    private fun utf8(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}
