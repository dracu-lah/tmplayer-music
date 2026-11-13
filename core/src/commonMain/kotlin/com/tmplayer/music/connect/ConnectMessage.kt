package com.tmplayer.music.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Sizes and counts nothing on the wire may exceed; a longer line, name or list is dropped, never trusted. */
object ConnectLimits {
    /** The newest protocol this build speaks. A peer that sends another version is told so. */
    const val VERSION = 2

    /** One line on the wire, in characters. Roomy enough for a small cover, small enough for a 1 GB stick. */
    const val MAX_LINE = 96 * 1024

    /** A cover sent as base64 text. */
    const val MAX_COVER_CHARS = 64 * 1024

    const val MAX_TEXT = 300
    const val MAX_SEND_ITEMS = 400
    const val MAX_QUEUE_ITEMS = 200
    const val MAX_MISSING_NAMES = 20
    const val MAX_IDS = 80
}

/** A track as the phone names it to the TV: where it lives in Telegram, which song it is, and what to call it. */
data class TrackRef(val chatId: Long, val messageId: Long, val songKey: String, val title: String)

/** One row of the TV's queue, as the phone shows it. [id] is the TV's own media id, sent back with a jump or a remove. */
data class QueueEntry(val id: String, val title: String, val artist: String, val durationMs: Long)

enum class SendHow(val wire: String) {
    /** Replace the queue and play. */
    Play("play"),

    /** Put after the track that plays now. */
    Next("next"),

    /** Put at the end of the queue. */
    Add("add");

    companion object {
        fun of(wire: String?): SendHow? = entries.firstOrNull { it.wire == wire }
    }
}

enum class Transport(val wire: String) {
    Play("play"),
    Pause("pause"),
    Toggle("toggle"),
    Next("next"),
    Previous("prev");

    companion object {
        fun of(wire: String?): Transport? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What the phone and the TV say to each other once they trust each other (after [ConnectFrame]'s
 * handshake). The phone asks ([Command] and the rest of the first group), the TV answers and pushes
 * what changed ([NowPlaying], [QueueView], [CoverPicture], [Sent]); nothing is polled.
 */
sealed interface ConnectBody {
    // From the phone.
    data class Command(val action: Transport) : ConnectBody
    data class Seek(val positionMs: Long) : ConnectBody
    data class Shuffle(val on: Boolean) : ConnectBody
    data class Repeat(val mode: Int) : ConnectBody
    data class Volume(val percent: Int) : ConnectBody
    data class Jump(val index: Int, val id: String) : ConnectBody
    data class Remove(val index: Int, val id: String) : ConnectBody
    data class Send(val how: SendHow, val items: List<TrackRef>, val start: Int = 0, val shuffle: Boolean = false) : ConnectBody
    data object Ping : ConnectBody

    /** Asks for the whole picture again: state, queue and cover. */
    data object Resync : ConnectBody

    // From the TV.
    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val buffering: Boolean,
        val shuffle: Boolean,
        val repeat: Int,
        val index: Int,
        val count: Int,
        val volume: Int,
        val volumeFixed: Boolean,
        /** The generated cover's key, so the phone can draw the same cover when no picture was sent. */
        val coverKey: String,
        val hasTrack: Boolean,
    ) : ConnectBody

    data class QueueView(val current: Int, val total: Int, val from: Int, val items: List<QueueEntry>) : ConnectBody
    data class CoverPicture(val key: String, val base64: String) : ConnectBody

    /** How a Send went: [ok] tracks went in, [missing] names the ones the TV does not have ([missingCount] counts them all). */
    data class Sent(val how: SendHow, val ok: Int, val missing: List<String>, val missingCount: Int) : ConnectBody
    data object Pong : ConnectBody
    data class Problem(val code: String) : ConnectBody
}

/** [ConnectBody] to and from the text inside a secured frame. */
object ConnectBodies {
    private val json = Json

    fun encode(body: ConnectBody): String = toJson(body).toString()

    /** The body in [text], or null when it is too long, not JSON, not an object or not a message this build knows. */
    fun parse(text: String): ConnectBody? {
        if (text.length > ConnectLimits.MAX_LINE) return null
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return runCatching { fromJson(root) }.getOrNull()
    }

    private fun toJson(body: ConnectBody): JsonObject = buildJsonObject {
        when (body) {
            is ConnectBody.Command -> { put("t", "cmd"); put("a", body.action.wire) }
            is ConnectBody.Seek -> { put("t", "seek"); put("ms", body.positionMs) }
            is ConnectBody.Shuffle -> { put("t", "shuffle"); put("on", body.on) }
            is ConnectBody.Repeat -> { put("t", "repeat"); put("mode", body.mode) }
            is ConnectBody.Volume -> { put("t", "vol"); put("v", body.percent) }
            is ConnectBody.Jump -> { put("t", "jump"); put("i", body.index); put("id", body.id) }
            is ConnectBody.Remove -> { put("t", "remove"); put("i", body.index); put("id", body.id) }
            is ConnectBody.Send -> {
                put("t", "send")
                put("how", body.how.wire)
                put("start", body.start)
                put("shuffle", body.shuffle)
                put("items", buildJsonArray {
                    for (r in body.items) add(buildJsonObject {
                        put("c", r.chatId); put("m", r.messageId); put("k", r.songKey); put("n", r.title)
                    })
                })
            }
            ConnectBody.Ping -> put("t", "ping")
            ConnectBody.Resync -> put("t", "resync")
            is ConnectBody.NowPlaying -> {
                put("t", "now")
                put("title", body.title); put("artist", body.artist); put("album", body.album)
                put("dur", body.durationMs); put("pos", body.positionMs)
                put("playing", body.playing); put("buffering", body.buffering)
                put("shuffle", body.shuffle); put("repeat", body.repeat)
                put("idx", body.index); put("count", body.count)
                put("vol", body.volume); put("volFixed", body.volumeFixed)
                put("ck", body.coverKey); put("has", body.hasTrack)
            }
            is ConnectBody.QueueView -> {
                put("t", "queue")
                put("cur", body.current); put("total", body.total); put("from", body.from)
                put("items", buildJsonArray {
                    for (e in body.items) add(buildJsonObject {
                        put("id", e.id); put("n", e.title); put("a", e.artist); put("d", e.durationMs)
                    })
                })
            }
            is ConnectBody.CoverPicture -> { put("t", "cover"); put("k", body.key); put("b", body.base64) }
            is ConnectBody.Sent -> {
                put("t", "sent")
                put("how", body.how.wire); put("ok", body.ok); put("missing", body.missingCount)
                put("names", buildJsonArray { for (n in body.missing) add(JsonPrimitive(n)) })
            }
            ConnectBody.Pong -> put("t", "pong")
            is ConnectBody.Problem -> { put("t", "problem"); put("c", body.code) }
        }
    }

    private fun fromJson(o: JsonObject): ConnectBody? = when (o.str("t")) {
        "cmd" -> Transport.of(o.str("a"))?.let { ConnectBody.Command(it) }
        "seek" -> o.long("ms")?.let { ConnectBody.Seek(it.coerceIn(0, MAX_MS)) }
        "shuffle" -> o.bool("on")?.let { ConnectBody.Shuffle(it) }
        "repeat" -> o.int("mode")?.takeIf { it in 0..2 }?.let { ConnectBody.Repeat(it) }
        "vol" -> o.int("v")?.let { ConnectBody.Volume(it.coerceIn(0, 100)) }
        "jump" -> index(o)?.let { (i, id) -> ConnectBody.Jump(i, id) }
        "remove" -> index(o)?.let { (i, id) -> ConnectBody.Remove(i, id) }
        "send" -> {
            val how = SendHow.of(o.str("how"))
            val items = (o["items"] as? JsonArray)?.mapNotNull { ref(it) }?.take(ConnectLimits.MAX_SEND_ITEMS).orEmpty()
            if (how == null || items.isEmpty()) null
            else ConnectBody.Send(how, items, (o.int("start") ?: 0).coerceIn(0, items.lastIndex), o.bool("shuffle") ?: false)
        }
        "ping" -> ConnectBody.Ping
        "resync" -> ConnectBody.Resync
        "now" -> ConnectBody.NowPlaying(
            title = o.text("title"), artist = o.text("artist"), album = o.text("album"),
            durationMs = (o.long("dur") ?: 0).coerceIn(0, MAX_MS), positionMs = (o.long("pos") ?: 0).coerceIn(0, MAX_MS),
            playing = o.bool("playing") ?: false, buffering = o.bool("buffering") ?: false,
            shuffle = o.bool("shuffle") ?: false, repeat = (o.int("repeat") ?: 0).coerceIn(0, 2),
            index = (o.int("idx") ?: 0).coerceAtLeast(0), count = (o.int("count") ?: 0).coerceAtLeast(0),
            volume = (o.int("vol") ?: 0).coerceIn(0, 100), volumeFixed = o.bool("volFixed") ?: false,
            coverKey = o.text("ck"), hasTrack = o.bool("has") ?: false,
        )
        "queue" -> {
            val items = (o["items"] as? JsonArray)?.mapNotNull { entry(it) }?.take(ConnectLimits.MAX_QUEUE_ITEMS).orEmpty()
            ConnectBody.QueueView(
                current = (o.int("cur") ?: 0).coerceAtLeast(0), total = (o.int("total") ?: items.size).coerceAtLeast(0),
                from = (o.int("from") ?: 0).coerceAtLeast(0), items = items,
            )
        }
        "cover" -> {
            val data = o.str("b")
            if (data == null || data.length > ConnectLimits.MAX_COVER_CHARS) null else ConnectBody.CoverPicture(o.text("k"), data)
        }
        "sent" -> SendHow.of(o.str("how"))?.let { how ->
            val names = (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(ConnectLimits.MAX_TEXT) }
                ?.take(ConnectLimits.MAX_MISSING_NAMES).orEmpty()
            ConnectBody.Sent(how, (o.int("ok") ?: 0).coerceAtLeast(0), names, (o.int("missing") ?: names.size).coerceAtLeast(names.size))
        }
        "pong" -> ConnectBody.Pong
        "problem" -> ConnectBody.Problem(o.text("c", 40))
        else -> null
    }

    private fun index(o: JsonObject): Pair<Int, String>? {
        val i = o.int("i")?.takeIf { it >= 0 } ?: return null
        val id = o.str("id")?.takeIf { it.length <= ConnectLimits.MAX_TEXT } ?: return null
        return i to id
    }

    private fun ref(e: JsonElement): TrackRef? {
        val o = e as? JsonObject ?: return null
        val chat = o.long("c")?.takeIf { it != 0L } ?: return null
        val message = o.long("m")?.takeIf { it != 0L } ?: return null
        return TrackRef(chat, message, o.text("k"), o.text("n"))
    }

    private fun entry(e: JsonElement): QueueEntry? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id")?.takeIf { it.isNotEmpty() && it.length <= ConnectLimits.MAX_TEXT } ?: return null
        return QueueEntry(id, o.text("n"), o.text("a"), (o.long("d") ?: 0).coerceIn(0, MAX_MS))
    }

    /** A day, as far as any position or length goes: a number past it is garbage. */
    private const val MAX_MS = 24L * 60 * 60 * 1000
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Text capped to [max] characters; blank when missing or not text. */
internal fun JsonObject.text(key: String, max: Int = ConnectLimits.MAX_TEXT): String = str(key)?.take(max).orEmpty()

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && this[key] !is JsonNull }?.contentOrNull?.toLongOrNull()

internal fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
