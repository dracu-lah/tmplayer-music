package com.tmplayer.music.connect

/**
 * What the TV's QR code says: where the TV listens, which TV it is and the one time 128 bit token
 * of the pairing on show, as `tmplayermusic://pair?h=<address>&p=<port>&i=<tv id>&t=<token>`.
 * Scanned text is untrusted: [parse] is strict (lengths, hex, port range, a local address literal)
 * and gives null for anything else.
 */
data class PairLink(val host: String, val port: Int, val tvId: String, val token: String) {
    fun text(): String = "$PREFIX?h=$host&p=$port&i=$tvId&t=$token"

    companion object {
        const val PREFIX = "tmplayermusic://pair"
        private const val MAX_CHARS = 200

        fun parse(text: String?): PairLink? {
            val t = text?.trim()?.takeIf { it.length <= MAX_CHARS && it.startsWith("$PREFIX?") } ?: return null
            val fields = t.substring(PREFIX.length + 1).split('&').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val host = fields["h"]?.takeIf { h -> h.length in 3..45 && h.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' } } ?: return null
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val tvId = fields["i"]?.takeIf { it.length in 16..64 && it.all { c -> Character.digit(c, 16) >= 0 } }?.lowercase() ?: return null
            val token = fields["t"]?.takeIf { it.length == PairingGate.TOKEN_BYTES * 2 && it.all { c -> Character.digit(c, 16) >= 0 } }?.lowercase() ?: return null
            return PairLink(host, port, tvId, token)
        }
    }
}
