package com.tmplayer.music.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One pairing, kept on both sides: the TV keeps the phones it trusts, the phone keeps the TVs.
 * [secret] is the 256 bit secret as hex; [host] and [port] are where the TV was last found (blank on
 * the TV's side), only a hint to try first before looking again.
 */
data class Paired(
    val id: String,
    val name: String,
    val secret: String,
    val since: Long,
    val host: String = "",
    val port: Int = 0,
) {
    val secretBytes: ByteArray? get() = ConnectCrypto.unhex(secret, ConnectHandshake.SECRET_BYTES)?.takeIf { it.size == ConnectHandshake.SECRET_BYTES }
}

/** The list of [Paired], as text for the app's settings file, and the edits on it. A damaged file is an empty list, never a crash. */
object PairingBook {
    const val MAX = 16

    private val json = Json

    fun encode(list: List<Paired>): String = buildJsonArray {
        for (p in list.take(MAX)) add(buildJsonObject {
            put("id", p.id); put("name", p.name); put("secret", p.secret); put("since", p.since)
            put("host", p.host); put("port", p.port)
        })
    }.toString()

    fun decode(text: String?): List<Paired> {
        if (text.isNullOrBlank() || text.length > 64 * 1024) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.length in 16..64 } ?: return@mapNotNull null
            val secret = o.str("secret")?.takeIf { ConnectCrypto.unhex(it, ConnectHandshake.SECRET_BYTES)?.size == ConnectHandshake.SECRET_BYTES }
                ?: return@mapNotNull null
            Paired(id, o.text("name", 60), secret, o.long("since") ?: 0L, o.text("host", 80), (o.int("port") ?: 0).takeIf { it in 0..65535 } ?: 0)
        }.distinctBy { it.id }.take(MAX)
    }

    /** [list] with [entry] added, or replacing the one with its id (a phone paired again). The oldest goes when full. */
    fun with(list: List<Paired>, entry: Paired): List<Paired> = (list.filterNot { it.id == entry.id } + entry).takeLast(MAX)

    fun without(list: List<Paired>, id: String): List<Paired> = list.filterNot { it.id == id }
}
