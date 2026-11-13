package com.tmplayer.music.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One line on the wire. Before the two sides trust each other only the handshake lines exist
 * ([Hello], [Auth], [Pair], [Ready], [Paired], [Err]); after it every line is a [Secured] one, whose
 * payload (a [ConnectBody]) carries a counter and an HMAC, never plain.
 *
 * Fields that came from the peer are checked here: ids and nonces are hex of a bounded length,
 * names are cut, anything else wrong-typed or too long makes the whole line null.
 */
sealed interface ConnectFrame {
    /** The host's first line: its fresh nonce, its id and its name. */
    data class Hello(val version: Int, val nonce: String, val id: String, val name: String) : ConnectFrame

    /** A paired phone proving it holds the secret. */
    data class Auth(val version: Int, val id: String, val name: String, val nonce: String, val mac: String) : ConnectFrame

    /** The host proving it holds the same secret. */
    data class Ready(val mac: String) : ConnectFrame

    /** A new phone proving it saw the code on the TV's screen. */
    data class Pair(val version: Int, val id: String, val name: String, val nonce: String, val mac: String) : ConnectFrame

    /** The new secret (hidden under a key made from the code) and the host's proof of it. */
    data class Paired(val wrapped: String, val mac: String) : ConnectFrame

    /** A refusal: [code] is one of [ConnectErrors]; [left] the tries left or [retrySeconds] the wait. */
    data class Err(val code: String, val left: Int = 0, val retrySeconds: Int = 0) : ConnectFrame

    data class Secured(val seq: Long, val payload: String, val mac: String) : ConnectFrame
}

object ConnectErrors {
    /** The phone is not paired (or was forgotten): pair again. */
    const val UNPAIRED = "unpaired"

    /** The proof did not match. */
    const val DENIED = "denied"

    /** The TV is not showing a code. */
    const val CLOSED = "closed"
    const val BAD_CODE = "bad_code"
    const val LOCKED = "locked"

    /** The peer speaks another protocol version. */
    const val VERSION = "version"

    /** Too many phones are connected or connecting. */
    const val BUSY = "busy"
    const val MALFORMED = "malformed"
}

object ConnectFrames {
    private val json = Json

    fun encode(frame: ConnectFrame): String = buildJsonObject {
        when (frame) {
            is ConnectFrame.Hello -> { put("t", "hello"); put("v", frame.version); put("sn", frame.nonce); put("id", frame.id); put("name", frame.name) }
            is ConnectFrame.Auth -> { put("t", "auth"); put("v", frame.version); put("id", frame.id); put("name", frame.name); put("cn", frame.nonce); put("m", frame.mac) }
            is ConnectFrame.Ready -> { put("t", "ready"); put("m", frame.mac) }
            is ConnectFrame.Pair -> { put("t", "pair"); put("v", frame.version); put("id", frame.id); put("name", frame.name); put("cn", frame.nonce); put("m", frame.mac) }
            is ConnectFrame.Paired -> { put("t", "paired"); put("w", frame.wrapped); put("m", frame.mac) }
            is ConnectFrame.Err -> { put("t", "err"); put("c", frame.code); put("left", frame.left); put("retry", frame.retrySeconds) }
            is ConnectFrame.Secured -> { put("s", frame.seq); put("p", frame.payload); put("m", frame.mac) }
        }
    }.toString()

    /** The frame in [line], or null when it is too long, not JSON or not a frame this build knows. */
    fun parse(line: String): ConnectFrame? {
        if (line.length > ConnectLimits.MAX_LINE) return null
        val o = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
        return runCatching { read(o) }.getOrNull()
    }

    private fun read(o: JsonObject): ConnectFrame? {
        if (o.str("t") == null) {
            val seq = o.long("s")?.takeIf { it > 0 } ?: return null
            val payload = o.str("p") ?: return null
            val mac = o.hexField("m", MAC_CHARS) ?: return null
            return ConnectFrame.Secured(seq, payload, mac)
        }
        return when (o.str("t")) {
            "hello" -> ConnectFrame.Hello(
                o.int("v") ?: return null, o.hexField("sn", NONCE_CHARS) ?: return null,
                o.hexField("id", ID_CHARS) ?: return null, o.text("name", NAME_MAX),
            )
            "auth" -> ConnectFrame.Auth(
                o.int("v") ?: return null, o.hexField("id", ID_CHARS) ?: return null, o.text("name", NAME_MAX),
                o.hexField("cn", NONCE_CHARS) ?: return null, o.hexField("m", MAC_CHARS) ?: return null,
            )
            "ready" -> ConnectFrame.Ready(o.hexField("m", MAC_CHARS) ?: return null)
            "pair" -> ConnectFrame.Pair(
                o.int("v") ?: return null, o.hexField("id", ID_CHARS) ?: return null, o.text("name", NAME_MAX),
                o.hexField("cn", NONCE_CHARS) ?: return null, o.hexField("m", MAC_CHARS) ?: return null,
            )
            "paired" -> ConnectFrame.Paired(o.hexField("w", MAC_CHARS) ?: return null, o.hexField("m", MAC_CHARS) ?: return null)
            "err" -> ConnectFrame.Err(o.text("c", 40), (o.int("left") ?: 0).coerceIn(0, 100), (o.int("retry") ?: 0).coerceIn(0, 3600))
            else -> null
        }
    }

    /** A hex field of exactly [chars] characters (an id is 16 to 64), or null. */
    private fun JsonObject.hexField(key: String, chars: IntRange): String? {
        val text = str(key) ?: return null
        if (text.length !in chars) return null
        return text.takeIf { s -> s.all { Character.digit(it, 16) >= 0 } }?.lowercase()
    }

    private val NONCE_CHARS = 16..64
    private val ID_CHARS = 16..64
    private val MAC_CHARS = 64..64
    private const val NAME_MAX = 60
}
