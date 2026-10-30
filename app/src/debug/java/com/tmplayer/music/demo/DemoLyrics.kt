package com.tmplayer.music.demo

import com.tmplayer.music.lyrics.HttpAnswer
import com.tmplayer.music.lyrics.LyricsHttp
import java.net.URI

/**
 * The demo's lyrics service: answers every lookup at once, with no network, with a short synced lyric
 * of invented nonsense lines spread over the track's length, so screenshots can show the lyrics page.
 * Nothing here is a real lyric.
 */
internal object DemoLyrics : LyricsHttp {
    private val LINES = listOf(
        "Paper lanterns over the quiet yard",
        "Counting tiles along the harbour wall",
        "Blue kettle humming on a borrowed stove",
        "",
        "We fold the maps and leave them on the stair",
        "Somewhere a bicycle bell keeps time",
        "Paper lanterns over the quiet yard",
        "Lemon light across the window frame",
        "",
        "Slow tide, slow tide, carry the spoons back home",
        "Slow tide, slow tide, nobody waits for long",
        "Paper lanterns over the quiet yard",
    )

    override fun get(url: String, headers: Map<String, String>): HttpAnswer {
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val seconds = query.split('&').firstOrNull { it.startsWith("duration=") }?.substringAfter('=')?.toLongOrNull() ?: 180
        val step = ((seconds - 10).coerceAtLeast(LINES.size.toLong()) * 1000 / LINES.size).coerceAtLeast(1000)
        val lrc = LINES.mapIndexed { i, line ->
            val at = 5_000 + i * step
            "[%02d:%02d.%02d]%s".format(at / 60_000, (at / 1000) % 60, (at % 1000) / 10, line)
        }.joinToString("\\n")
        return HttpAnswer(200, """{"synced":"$lrc","plain":null,"instrumental":false,"source":"LRCLIB"}""")
    }
}
