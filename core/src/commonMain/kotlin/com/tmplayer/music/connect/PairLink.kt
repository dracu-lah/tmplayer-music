package com.tmplayer.music.connect

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the TV's QR code says: where the TV listens, which TV it is, a fingerprint of the key it will
 * answer with ([fingerprint], see [ConnectHandshake.fingerprint]), the one time 128 bit token of the
 * pairing on show and, only to show the user, the TV's name:
 * `tmplayermusic://pair?h=<address>&p=<port>&i=<tv id>&f=<key fingerprint>&t=<token>&n=<name>`.
 *
 * Scanned text is untrusted: [parse] is strict (lengths, hex, port range, a local address literal)
 * and gives null for anything else. The name is only text for a dialog; nothing trusts it. A link
 * proves nothing about where it came from: the phone's check of the fingerprint tells that the TV that
 * answers is the one the link names, not that the link came from the user's own TV.
 */
data class PairLink(val host: String, val port: Int, val tvId: String, val token: String, val fingerprint: String, val name: String = "") {
    fun text(): String {
        val shown = encodedName(name)
        return "$PREFIX?h=$host&p=$port&i=$tvId&f=$fingerprint&t=$token" + if (shown.isEmpty()) "" else "&n=$shown"
    }

    companion object {
        const val PREFIX = "tmplayermusic://pair"
        private const val MAX_CHARS = 400
        private const val NAME_CHARS = 30
        private const val NAME_ENCODED_MAX = 60

        /** [name] cut to what fits the link, percent encoded. */
        private fun encodedName(name: String): String {
            val clean = name.filter { !it.isISOControl() }.trim()
            var out = ""
            for (cp in clean.codePoints().toArray().take(NAME_CHARS)) {
                val next = out + URLEncoder.encode(String(Character.toChars(cp)), "UTF-8")
                if (next.length > NAME_ENCODED_MAX) break
                out = next
            }
            return out
        }

        fun parse(text: String?): PairLink? {
            val t = text?.trim()?.takeIf { it.length <= MAX_CHARS && it.startsWith("$PREFIX?") } ?: return null
            val fields = t.substring(PREFIX.length + 1).split('&').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val host = fields["h"]?.takeIf { h -> h.length in 3..45 && h.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' } } ?: return null
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val tvId = fields["i"]?.takeIf { it.length in 16..64 && it.all { c -> Character.digit(c, 16) >= 0 } }?.lowercase() ?: return null
            val fingerprint = fields["f"]?.takeIf { it.length == ConnectHandshake.FINGERPRINT_CHARS && it.all { c -> Character.digit(c, 16) >= 0 } }?.lowercase() ?: return null
            val token = fields["t"]?.takeIf { it.length == PairingGate.TOKEN_BYTES * 2 && it.all { c -> Character.digit(c, 16) >= 0 } }?.lowercase() ?: return null
            val name = fields["n"]?.takeIf { it.length <= NAME_ENCODED_MAX }?.let { raw ->
                runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            }?.filter { !it.isISOControl() }?.trim()?.take(NAME_CHARS).orEmpty()
            return PairLink(host, port, tvId, token, fingerprint, name)
        }
    }
}
